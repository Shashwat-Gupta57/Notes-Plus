package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Local .bak files, byte-compatible with the phone's BackupManager, so a backup made on either
 * device restores on the other:
 *
 * <pre>
 * "NPBK" 0x03 | keyring length (u32) | keyring JSON | payload
 * payload = "NP" 0x03 keyId(8) | streaming AES-GCM-HKDF (256 KB segments), header as associated data,
 *           under HMAC-SHA256(backup key, "notes-plus/portable-stream/v3"), of a zip:
 *     snapshot.json            the manifest ({@link Snapshot})
 *     media/&lt;fileName&gt;        plaintext bytes (inside the encryption)
 *     backgrounds/&lt;dir&gt;/&lt;f&gt;  wallpapers
 * </pre>
 *
 * The keyring wraps the backup key under every vault's lock (or only the backup password, when
 * one is set): PBKDF2-SHA256 at 210,000 rounds, AES-GCM. Everything streams, so memory stays
 * flat and nothing decrypted touches the disk.
 */
final class Backup {

    private Backup() {}

    private static final byte[] MAGIC = {'N', 'P', 'B', 'K'};
    private static final int FORMAT = 3, KEYRING_FORMAT = 2, ITERATIONS = 210_000;
    private static final int MAX_KEYRING = 1 << 20;
    private static final String SNAPSHOT = "snapshot.json", MEDIA = "media/", BACKGROUNDS = "backgrounds/";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Progress, reported from the working thread; return true from {@link #stopped} to cancel. */
    interface Progress {
        void update(long done, long total, String message);

        default boolean stopped() {
            return false;
        }
    }

    record Result(int items, long bytes) {}

    /** The supplied password opens none of the backup's wraps. */
    static final class WrongPassword extends IOException {
        WrongPassword() {
            super("That password doesn't open this backup");
        }
    }

    // ================================================================ create

    /** Writes a backup of every vault to {@code target} (via a .part file). The vault must be open. */
    static Result create(Path target, Progress progress) throws Exception {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        int items = 0;
        long total = 0, done = 0;
        try {
            progress.update(0, 0, "Gathering data");
            VaultStore store = VaultStore.get();
            Snapshot snapshot = Snapshot.capture(store);
            byte[] keyring = keyring(store).toString().getBytes(StandardCharsets.UTF_8);
            for (Snapshot.MediaRecord r : snapshot.media) {
                Path f = VaultCrypto.mediaDir().resolve(r.fileName);
                if (Files.exists(f)) total += Files.size(f);
            }
            try (OutputStream file = Files.newOutputStream(part)) {
                DataOutputStream header = new DataOutputStream(file);
                header.write(MAGIC);
                header.writeByte(FORMAT);
                header.writeInt(keyring.length);
                header.write(keyring);
                try (OutputStream encrypted = Portable.encrypting(file, VaultCrypto.backupKey(), VaultCrypto.backupKeyId());
                     ZipOutputStream zip = new ZipOutputStream(encrypted)) {
                    // Photos and videos are already compressed; deflating them again only burns time.
                    zip.setLevel(Deflater.NO_COMPRESSION);
                    zip.putNextEntry(new ZipEntry(SNAPSHOT));
                    zip.write(snapshot.toJson().toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    byte[] buffer = new byte[64 * 1024];
                    for (Snapshot.MediaRecord r : snapshot.media) {
                        Path f = VaultCrypto.mediaDir().resolve(r.fileName);
                        if (!Files.exists(f)) continue;
                        items++;
                        String message = "Saving " + items + " of " + snapshot.media.size();
                        zip.putNextEntry(new ZipEntry(MEDIA + r.fileName));
                        try (InputStream in = VaultCrypto.read(f)) {
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (progress.stopped()) throw new CancellationException("Backup cancelled");
                                zip.write(buffer, 0, n);
                                done += n;
                                progress.update(done, total, message);
                            }
                        }
                        zip.closeEntry();
                    }
                    writeBackgrounds(zip);
                }
                file.flush();
            }
            progress.update(total, total, "Checking the backup");
            // Read back before it counts as saved: written is not the same as restorable.
            verify(part, null, progress);
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new Result(items, total);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** Every vault's lock wraps the backup key, or only the backup password when one is set. */
    private static JSONObject keyring(VaultStore store) throws Exception {
        JSONArray wraps = new JSONArray();
        JSONObject passwordWrap = VaultCrypto.backupPasswordWrap();
        if (passwordWrap != null) {
            wraps.put(passwordWrap);
        } else {
            byte[] key = VaultCrypto.backupKey();
            for (VaultStore.Vault v : store.vaults()) {
                String lock = VaultCrypto.lockValue(v.uuid);
                if (lock != null) wraps.put(wrap(key, lock, v.name));
            }
        }
        return new JSONObject().put("formatVersion", KEYRING_FORMAT).put("keyId", VaultCrypto.backupKeyId()).put("wraps", wraps);
    }

    /** The phone's wrap: {label, iterations, salt, iv, wrapped}. */
    static JSONObject wrap(byte[] key, String password, String label) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt, ITERATIONS), new GCMParameterSpec(128, iv));
        Base64.Encoder b64 = Base64.getEncoder();
        return new JSONObject().put("label", label == null ? "Vault" : label).put("iterations", ITERATIONS)
                .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
                .put("wrapped", b64.encodeToString(c.doFinal(key)));
    }

    private static void writeBackgrounds(ZipOutputStream zip) throws IOException {
        try (Stream<Path> dirs = Files.list(VaultCrypto.dir())) {
            for (Path dir : dirs.filter(d -> Files.isDirectory(d) && d.getFileName().toString().startsWith("bg_")).toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        zip.putNextEntry(new ZipEntry(BACKGROUNDS + dir.getFileName() + "/" + f.getFileName()));
                        Files.copy(f, zip);
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    // =============================================================== restore

    /**
     * Restores a backup. Items already here are skipped; nothing local is deleted.
     * {@code replaceLocal} lets the backup's vault names, locks and wallpapers win.
     *
     * @param password any vault lock of that backup, or its backup password; may be null when
     *                 the backup was made here
     * @throws WrongPassword when {@code password} doesn't open it
     */
    static Result restore(Path file, String password, boolean replaceLocal, Progress progress) throws Exception {
        long size = Files.size(file);
        List<Path> written = new ArrayList<>();
        int items = 0;
        try (Counting counted = new Counting(Files.newInputStream(file));
             ZipInputStream zip = new ZipInputStream(open(counted, password))) {
            progress.update(0, size, "Opening backup");
            Snapshot snapshot = null;
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (progress.stopped()) throw new CancellationException("Restore cancelled");
                String name = entry.getName();
                if (name.equals(SNAPSHOT)) {
                    snapshot = Snapshot.fromJson(new JSONObject(new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
                } else if (name.startsWith(MEDIA)) {
                    String fileName = name.substring(MEDIA.length());
                    if (!Snapshot.safe(fileName)) continue;
                    Path target = VaultCrypto.mediaDir().resolve(fileName);
                    if (Files.exists(target)) continue; // Vault file names are write-once.
                    int number = ++items;
                    try (OutputStream out = VaultCrypto.write(VaultCrypto.mediaDir(), fileName)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            if (progress.stopped()) throw new CancellationException("Restore cancelled");
                            out.write(buffer, 0, n);
                            progress.update(counted.count, size, "Restoring item " + number);
                        }
                    }
                    written.add(target);
                } else if (name.startsWith(BACKGROUNDS)) {
                    String path = name.substring(BACKGROUNDS.length());
                    String[] parts = path.split("/");
                    if (parts.length != 2 || !parts[0].startsWith("bg_") || !Snapshot.safe(parts[0]) || !Snapshot.safe(parts[1])) continue;
                    Path dir = VaultCrypto.dir().resolve(parts[0]);
                    Files.createDirectories(dir);
                    Files.copy(zip, dir.resolve(parts[1]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (snapshot == null) throw new IOException("This file is not a Notes+ backup");
            progress.update(size, size, "Updating your vaults");
            snapshot.apply(VaultStore.get(), replaceLocal);
            written.clear(); // Rows point at them now; they stay.
            return new Result(items, counted.count);
        } finally {
            // A failed, cancelled or truncated restore leaves no file without a row.
            for (Path f : written) Files.deleteIfExists(f);
        }
    }

    /**
     * Reads a backup end to end without restoring anything, and confirms it is whole: every
     * segment authenticates, the stream ends where it should, the manifest is there, and every
     * item it lists is in the archive.
     */
    static Result verify(Path file, String password, Progress progress) throws Exception {
        long size = Files.size(file);
        Set<String> media = new HashSet<>();
        Snapshot snapshot = null;
        byte[] buffer = new byte[64 * 1024];
        long count;
        try (Counting counted = new Counting(Files.newInputStream(file));
             ZipInputStream zip = new ZipInputStream(open(counted, password))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (progress.stopped()) throw new CancellationException("Check cancelled");
                String name = entry.getName();
                if (name.equals(SNAPSHOT)) {
                    snapshot = Snapshot.fromJson(new JSONObject(new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
                    continue;
                }
                if (name.startsWith(MEDIA)) media.add(name.substring(MEDIA.length()));
                while (zip.read(buffer) != -1) progress.update(counted.count, size, "Checking " + media.size() + " items");
            }
            // Drain to the end so the last segment's authentication is checked too.
            while (zip.read(buffer) != -1) { }
            count = counted.count;
        }
        if (snapshot == null) throw new IOException("The backup has no manifest");
        int missing = 0;
        for (Snapshot.MediaRecord r : snapshot.media) if (!media.contains(r.fileName)) missing++;
        if (missing > 0) throw new IOException(missing + " items listed in the backup are not in it");
        return new Result(media.size(), count);
    }

    // ================================================================= crypto

    /** The plaintext zip inside a .bak. */
    private static InputStream open(InputStream raw, String password) throws Exception {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("This file is not a Notes+ backup (or is from a very old version)");
        int format = in.readUnsignedByte();
        if (format != FORMAT) throw new IOException("This backup needs a newer version of Notes+");
        int length = in.readInt();
        if (length <= 0 || length > MAX_KEYRING) throw new IOException("Damaged backup header");
        byte[] ring = new byte[length];
        in.readFully(ring);
        JSONObject keyring = new JSONObject(new String(ring, StandardCharsets.UTF_8));

        byte[] header;
        try {
            header = Portable.header(in);
        } catch (IOException e) {
            throw new IOException("This backup uses an older format; open it once in the phone app to update it");
        }
        String keyId = Portable.keyId(header);
        byte[] key = null;
        if (VaultCrypto.isUnlocked() && keyId.equals(VaultCrypto.backupKeyId())) key = VaultCrypto.backupKey();
        if (key == null) key = unwrap(keyring, password);
        return Portable.decrypting(in, header, key);
    }

    /** Tries every wrap with the password: the caller needn't know which vault's lock it is. */
    static byte[] unwrap(JSONObject keyring, String password) throws IOException {
        if (password == null || password.isEmpty()) throw new WrongPassword();
        JSONArray wraps = keyring.optJSONArray("wraps");
        for (int i = 0; wraps != null && i < wraps.length(); i++) {
            JSONObject w = wraps.getJSONObject(i);
            try {
                Base64.Decoder b64 = Base64.getDecoder();
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, derive(password, b64.decode(w.getString("salt")), w.optInt("iterations", ITERATIONS)),
                        new GCMParameterSpec(128, b64.decode(w.getString("iv"))));
                return c.doFinal(b64.decode(w.getString("wrapped")));
            } catch (Exception wrongForThisOne) {
                // The next wrap may be the one.
            }
        }
        throw new WrongPassword();
    }

    private static SecretKeySpec derive(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
    }

    /** Counts bytes read, for progress. */
    private static final class Counting extends FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
