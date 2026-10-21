package org.flexstudios.notes.plus;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Local .bak files: the whole vault as one portable, encrypted file.
 *
 * <pre>
 * "NPBK" 0x03 | keyringLength (u32) | keyring JSON | VaultCrypto v3 payload of a zip:
 *     snapshot.json            the same manifest a Drive restore point uses
 *     media/&lt;fileName&gt;        plaintext bytes (inside the encryption)
 *     backgrounds/&lt;dir&gt;/&lt;f&gt;  wallpapers
 * </pre>
 *
 * Everything streams: vault file → zip → encryption → destination, and back, so memory stays
 * flat and nothing decrypted touches disk. The keyring travels inside the file, so a backup
 * opens on a new phone with any vault's lock or the backup password. Older .bak files (v1:
 * main-lock key; v2: from the migration screen) are still read.
 *
 * The methods block and report byte-accurate progress; {@link SyncWorker} runs them as
 * foreground jobs so a multi-gigabyte vault survives the user leaving the app.
 */
public final class BackupManager {
    private static final String TAG = "BackupManager";
    private static final byte[] MAGIC = {'N', 'P', 'B', 'K'};
    private static final int FORMAT = 3;
    private static final int MAX_KEYRING_BYTES = 1 << 20;
    private static final int BUFFER = 64 * 1024;

    private static final String ENTRY_SNAPSHOT = "snapshot.json";
    private static final String ENTRY_LEGACY_SETTINGS = "settings.json";
    private static final String DIR_MEDIA = "media/";
    private static final String DIR_BACKGROUNDS = "backgrounds/";

    private BackupManager() {}

    /** Called from the working thread. Return true from isStopped to cancel. */
    public interface Progress {
        void update(long done, long total, String message);

        default boolean isStopped() {
            return false;
        }
    }

    /** What a finished job did, for the completion notice. */
    public static final class Result {
        public int items;
        public long bytes;
    }

    // ------------------------------------------------------------------ create

    /** Writes a backup of every vault to {@code out}, then closes it. */
    public static Result create(Context context, OutputStream out, Progress progress) throws Exception {
        Result result = new Result();
        try (OutputStream destination = out) {
            progress.update(0, 0, "Gathering data");
            AppDatabase db = AppDatabase.getInstance(context);
            Snapshot snapshot = Snapshot.capture(context, db, null);
            byte[] keyring = VaultCrypto.buildKeyring(context, db.vaultDao().getAllVaultsDirect())
                    .toString().getBytes(StandardCharsets.UTF_8);

            File vaultDir = VaultMedia.vaultDir(context);
            long total = 0;
            for (Snapshot.MediaRecord record : snapshot.media) {
                total += new File(vaultDir, record.fileName).length();
            }

            DataOutputStream header = new DataOutputStream(destination);
            header.write(MAGIC);
            header.writeByte(FORMAT);
            header.writeInt(keyring.length);
            header.write(keyring);

            long done = 0;
            try (OutputStream encrypted = VaultCrypto.encryptingStream(destination,
                    VaultCrypto.getOrCreateDataKey(context), VaultCrypto.getKeyId(context));
                 ZipOutputStream zip = new ZipOutputStream(encrypted)) {
                // Photos and videos are already compressed; deflating them again burns CPU.
                zip.setLevel(Deflater.NO_COMPRESSION);

                zip.putNextEntry(new ZipEntry(ENTRY_SNAPSHOT));
                zip.write(snapshot.toJson().toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                int count = 0;
                for (Snapshot.MediaRecord record : snapshot.media) {
                    File file = new File(vaultDir, record.fileName);
                    if (!file.exists()) continue;
                    count++;
                    String message = "Saving " + count + " of " + snapshot.media.size();
                    zip.putNextEntry(new ZipEntry(DIR_MEDIA + record.fileName));
                    try (InputStream in = VaultCrypto.openRead(context, file)) {
                        done = copy(in, zip, done, total, message, progress);
                    }
                    zip.closeEntry();
                    result.items++;
                }
                writeBackgrounds(context, zip);
            }
            destination.flush();
            result.bytes = total;
            db.secretDao().markAllAsSynced();
            progress.update(total, total, "Done");
        }
        return result;
    }

    private static void writeBackgrounds(Context context, ZipOutputStream zip) throws IOException {
        File[] dirs = context.getFilesDir().listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith("bg_")) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                zip.putNextEntry(new ZipEntry(DIR_BACKGROUNDS + dir.getName() + "/" + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    VaultCrypto.copy(in, zip);
                }
                zip.closeEntry();
            }
        }
    }

    // ----------------------------------------------------------------- restore

    /**
     * Restores a backup. Items already in the vault are skipped; nothing local is deleted.
     * {@code replaceLocal} lets the backup's vault names, locks and backgrounds win.
     *
     * @param size     the backup's length in bytes for progress, or -1 if unknown
     * @param password any vault lock or the backup password; ignored when this phone's own
     *                 key opens the file
     * @throws VaultCrypto.WrongPasswordException when {@code password} doesn't open it
     */
    public static Result restore(Context context, InputStream input, long size, String password,
                                 boolean replaceLocal, Progress progress) throws Exception {
        Result result = new Result();
        List<File> written = new ArrayList<>();
        try (CountingInputStream counted = new CountingInputStream(input)) {
            progress.update(0, size, "Opening backup");
            InputStream zipStream = open(context, counted, password);

            Snapshot snapshot = null;
            JSONObject legacySettings = null;
            File vaultDir = VaultMedia.vaultDir(context);
            byte[] buffer = new byte[BUFFER];
            try (ZipInputStream zip = new ZipInputStream(zipStream)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (progress.isStopped()) throw new CancellationException("Restore cancelled");
                    String name = entry.getName();
                    if (name.equals(ENTRY_SNAPSHOT)) {
                        snapshot = Snapshot.fromJson(readJson(zip));
                    } else if (name.equals(ENTRY_LEGACY_SETTINGS)) {
                        legacySettings = readJson(zip);
                    } else if (name.startsWith(DIR_MEDIA)) {
                        File target = resolveInside(vaultDir, name.substring(DIR_MEDIA.length()));
                        if (target.exists()) continue; // Vault file names are write-once.
                        final int number = result.items + 1;
                        VaultCrypto.write(context, target, out -> {
                            int len;
                            while ((len = zip.read(buffer)) != -1) {
                                out.write(buffer, 0, len);
                                progress.update(counted.count, size, "Restoring item " + number);
                                if (progress.isStopped()) throw new CancellationException("Restore cancelled");
                            }
                        });
                        written.add(target);
                        result.items++;
                    } else if (name.startsWith(DIR_BACKGROUNDS)) {
                        restoreBackground(context, zip, name.substring(DIR_BACKGROUNDS.length()));
                    }
                }
            }

            if (snapshot == null && legacySettings != null) {
                snapshot = fromLegacySettings(context, legacySettings);
            }
            if (snapshot == null) throw new IOException("This file is not a Notes+ backup");

            progress.update(size, size, "Updating your vaults");
            snapshot.apply(context, AppDatabase.getInstance(context), replaceLocal);
            written.clear(); // Now referenced by rows; they stay.
            ThumbnailStore.get(context).clearAll();
            result.bytes = counted.count;
            return result;
        } finally {
            // A failed, cancelled or truncated restore must not leave files no row points at.
            for (File f : written) {
                if (f.exists() && !f.delete()) Log.w(TAG, "Could not remove " + f.getName());
            }
        }
    }

    /**
     * Reads a backup end to end without restoring anything, and confirms it is whole.
     *
     * Every encrypted segment is authenticated as it is read, and the stream refuses to end
     * early, so reaching the end proves no byte was altered or lost. On top of that the
     * manifest must be present and every item it lists must be in the archive. This is what
     * turns "the file was written" into "this file will restore".
     *
     * @return what the backup holds
     * @throws IOException describing what is wrong, if anything is
     */
    public static Result verify(Context context, InputStream input, long size, String password,
                                Progress progress) throws Exception {
        Result result = new Result();
        java.util.Set<String> media = new java.util.HashSet<>();
        Snapshot snapshot = null;
        boolean legacy = false;
        byte[] buffer = new byte[BUFFER];
        try (CountingInputStream counted = new CountingInputStream(input);
             ZipInputStream zip = new ZipInputStream(open(context, counted, password))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (progress.isStopped()) throw new CancellationException("Verification cancelled");
                String name = entry.getName();
                if (name.equals(ENTRY_SNAPSHOT)) {
                    snapshot = Snapshot.fromJson(readJson(zip));
                    continue;
                }
                if (name.equals(ENTRY_LEGACY_SETTINGS)) legacy = true;
                if (name.startsWith(DIR_MEDIA)) media.add(name.substring(DIR_MEDIA.length()));
                long sinceReport = 0;
                int len;
                while ((len = zip.read(buffer)) != -1) {
                    sinceReport += len;
                    if (sinceReport >= 1 << 20) {
                        sinceReport = 0;
                        progress.update(counted.count, size, "Checking " + media.size() + " items");
                    }
                }
            }
            result.bytes = counted.count;
        }
        if (snapshot == null && !legacy) throw new IOException("The backup has no manifest");
        if (snapshot != null) {
            int missing = 0;
            for (Snapshot.MediaRecord record : snapshot.media) {
                if (!media.contains(record.fileName)) missing++;
            }
            if (missing > 0) throw new IOException(missing + " items listed in the backup are not in it");
        }
        result.items = media.size();
        return result;
    }

    /** Plaintext zip stream of any .bak version. */
    private static InputStream open(Context context, InputStream raw, String password) throws Exception {
        PushbackInputStream in = new PushbackInputStream(raw, MAGIC.length);
        byte[] prefix = new byte[MAGIC.length];
        int read = new DataInputStream(in).read(prefix);
        if (read == MAGIC.length && Arrays.equals(prefix, MAGIC)) {
            DataInputStream header = new DataInputStream(in);
            int format = header.readUnsignedByte();
            if (format != FORMAT) throw new IOException("This backup needs a newer version of Notes+");
            int length = header.readInt();
            if (length <= 0 || length > MAX_KEYRING_BYTES) throw new IOException("Damaged backup header");
            byte[] keyring = new byte[length];
            header.readFully(keyring);
            VaultCrypto.KeyResolver resolver = VaultCrypto.keyringResolver(context,
                    new JSONObject(new String(keyring, StandardCharsets.UTF_8)), password);
            return payload(in, resolver);
        }
        if (read > 0) in.unread(prefix, 0, read);
        // v1 (lock-derived key) or v2 (this phone's data key, from the migration screen).
        return payload(in, VaultCrypto.keyringResolver(context, null, password));
    }

    private static InputStream payload(InputStream in, VaultCrypto.KeyResolver resolver) throws Exception {
        PushbackInputStream pushback = new PushbackInputStream(in, 3);
        VaultCrypto.Header header = VaultCrypto.readHeader(pushback);
        try {
            return VaultCrypto.openDecrypted(pushback, header, resolver);
        } catch (javax.crypto.AEADBadTagException e) {
            // Single-shot formats can only report a wrong key as a failed tag.
            throw new VaultCrypto.WrongPasswordException("That password doesn't open this backup");
        }
    }

    /**
     * Re-wraps a pre-7.50 .bak (encrypted with an old lock) as a current one. Used by the
     * migration screen. The old format was single-shot, so the old file is decrypted in
     * memory, exactly as the version that wrote it had to.
     */
    public static void convertLegacy(Context context, InputStream legacy, String oldLock,
                                     OutputStream out) throws Exception {
        InputStream zip = payload(legacy, VaultCrypto.keyringResolver(context, null, oldLock));
        AppDatabase db = AppDatabase.getInstance(context);
        byte[] keyring = VaultCrypto.buildKeyring(context, db.vaultDao().getAllVaultsDirect())
                .toString().getBytes(StandardCharsets.UTF_8);
        DataOutputStream header = new DataOutputStream(out);
        header.write(MAGIC);
        header.writeByte(FORMAT);
        header.writeInt(keyring.length);
        header.write(keyring);
        try (OutputStream encrypted = VaultCrypto.encryptingStream(out,
                VaultCrypto.getOrCreateDataKey(context), VaultCrypto.getKeyId(context))) {
            VaultCrypto.copy(zip, encrypted);
        }
        out.flush();
    }

    private static void restoreBackground(Context context, ZipInputStream zip, String path) throws Exception {
        // Backups only ever contain bg_vault_N/ and bg_lock/ directories.
        if (!path.startsWith("bg_")) {
            Log.w(TAG, "Skipping unexpected background entry: " + path);
            return;
        }
        File target = resolveInside(context.getFilesDir(), path);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        try (OutputStream out = new java.io.FileOutputStream(target)) {
            VaultCrypto.copy(zip, out);
        }
    }

    /**
     * Converts the settings.json of a pre-7.50 backup into a restore point. Those had no
     * stable identities, so a vault is matched to an existing one by name and lock, and
     * otherwise given a fresh identity.
     */
    private static Snapshot fromLegacySettings(Context context, JSONObject settings) {
        Snapshot snapshot = new Snapshot();
        snapshot.id = Snapshot.newId();
        snapshot.createdAt = System.currentTimeMillis();
        List<VaultEntity> local = AppDatabase.getInstance(context).vaultDao().getAllVaultsDirect();

        Map<Integer, String> vaultUuidById = new HashMap<>();
        JSONArray vaults = settings.optJSONArray("vaults");
        for (int i = 0; vaults != null && i < vaults.length(); i++) {
            JSONObject vo = vaults.optJSONObject(i);
            if (vo == null) continue;
            Snapshot.VaultRecord record = new Snapshot.VaultRecord();
            record.name = optString(vo, "name");
            record.lockType = optString(vo, "lockType");
            record.lockValue = optString(vo, "lockValue");
            record.isMain = vo.optBoolean("isMain", false);
            record.sortOrder = vo.optInt("sortOrder", i);
            record.bgType = optString(vo, "bgType");
            record.bgValue = optString(vo, "bgValue");
            record.uuid = "legacy-" + vo.optInt("id", i);
            for (VaultEntity v : local) {
                if (v.getUuid() != null && java.util.Objects.equals(v.getName(), record.name)
                        && java.util.Objects.equals(v.getLockValue(), record.lockValue)) {
                    record.uuid = v.getUuid();
                }
            }
            vaultUuidById.put(vo.optInt("id", i), record.uuid);
            snapshot.vaults.add(record);
        }

        Map<Integer, String> albumUuidById = new HashMap<>();
        JSONArray albums = settings.optJSONArray("albums");
        for (int i = 0; albums != null && i < albums.length(); i++) {
            JSONObject ao = albums.optJSONObject(i);
            if (ao == null) continue;
            Snapshot.AlbumRecord record = new Snapshot.AlbumRecord();
            record.uuid = "legacy-album-" + ao.optInt("id", i);
            record.name = optString(ao, "name");
            record.vaultUuid = vaultUuidById.get(ao.optInt("vaultId", 1));
            record.coverFileName = optString(ao, "coverFileName");
            albumUuidById.put(ao.optInt("id", i), record.uuid);
            snapshot.albums.add(record);
        }

        JSONArray secrets = settings.optJSONArray("secrets");
        for (int i = 0; secrets != null && i < secrets.length(); i++) {
            JSONObject so = secrets.optJSONObject(i);
            if (so == null || optString(so, "fileName") == null) continue;
            Snapshot.MediaRecord record = new Snapshot.MediaRecord();
            record.fileName = optString(so, "fileName");
            record.originalPath = optString(so, "originalPath");
            record.dateTaken = so.optLong("dateTaken", 0);
            record.dateAdded = so.optLong("dateAdded", 0);
            record.isVideo = so.optBoolean("isVideo", false);
            record.isFavourite = so.optBoolean("isFavourite", false);
            record.vaultUuid = vaultUuidById.get(so.optInt("vaultId", 1));
            record.albumUuid = so.isNull("albumId") ? null : albumUuidById.get(so.optInt("albumId"));
            snapshot.media.add(record);
        }

        try {
            snapshot.lockSettings.put("auto_lock_time", settings.optLong("auto_lock_time", 0));
            snapshot.lockSettings.put("lock_bg_type", settings.optString("lock_bg_type", "DEFAULT"));
            snapshot.lockSettings.put("lock_bg_value", settings.isNull("lock_bg_value")
                    ? JSONObject.NULL : settings.optString("lock_bg_value"));
            snapshot.lockSettings.put("lock_bg_blur", settings.optBoolean("lock_bg_blur", false));
            snapshot.lockSettings.put("lock_bg_dim", settings.optBoolean("lock_bg_dim", false));
        } catch (Exception e) {
            Log.w(TAG, "Could not carry over lock settings", e);
        }
        return snapshot;
    }

    // ------------------------------------------------------------------ helpers

    /** Copies one entry, reporting progress about every megabyte and honouring cancel. */
    private static long copy(InputStream in, OutputStream out, long done, long total, String message,
                             Progress progress) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long sinceReport = 0;
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            done += len;
            sinceReport += len;
            if (sinceReport >= 1 << 20) {
                sinceReport = 0;
                progress.update(done, total, message);
                if (progress.isStopped()) throw new CancellationException("Backup cancelled");
            }
        }
        progress.update(done, total, message);
        return done;
    }

    /** Counts bytes consumed from the backup file, which is what restore progress is measured in. */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }

    private static JSONObject readJson(ZipInputStream zip) throws Exception {
        return new JSONObject(new String(VaultCrypto.readAll(zip), StandardCharsets.UTF_8));
    }

    private static String optString(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String value = o.optString(key, null);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Resolves a zip entry name inside its directory, rejecting anything that escapes it.
     * A .bak is a file the user picks, so its entry names are untrusted ("zip slip").
     */
    private static File resolveInside(File parentDir, String relativePath) throws IOException {
        File target = new File(parentDir, relativePath);
        String parentPath = parentDir.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(parentPath)) {
            throw new IOException("Backup entry escapes its directory: " + relativePath);
        }
        return target;
    }
}
