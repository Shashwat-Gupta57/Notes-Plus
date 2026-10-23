package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * The vault's Google Drive backup: the phone's CloudSync, on the same Drive layout and formats,
 * so the phone and this computer back up into one store and either restores the other's.
 *
 * <pre>
 * Notes-plus/Saved-Notes/Cloud/Remote/Backup/
 *   keyring.json                 plain: the backup key wrapped under vault locks / the backup password
 *   objects_index.json           encrypted: every stored object {name, driveId, size, sha256, uploadedAt}
 *   objects/&lt;fileName&gt;          encrypted media, write-once, named like the vault file
 *   snapshots/index.json         encrypted: the restore points
 *   snapshots/&lt;id&gt;.json         encrypted: one restore point ({@link Snapshot})
 *   backgrounds/bg_*&#47;&lt;file&gt;    encrypted wallpapers
 * </pre>
 *
 * Encrypted = the portable v3 stream under the backup key ({@link Portable}), which on the phone
 * is the vault data key. A backup uploads only what the index doesn't list. Nothing a retained
 * restore point names is ever deleted.
 *
 * Its Google account is the vault's own, kept encrypted in the vault ({@code vault/drive.bin}):
 * never the notes account, which never learns the vault exists.
 */
final class CloudBackup {

    private CloudBackup() {}

    private static final String[] ROOT_PATH = {"Notes-plus", "Saved-Notes", "Cloud", "Remote", "Backup"};
    private static final String OBJECTS = "objects", SNAPSHOTS = "snapshots", BACKGROUNDS = "backgrounds";
    private static final String INDEX = "objects_index.json", SNAPSHOT_INDEX = "index.json", KEYRING = "keyring.json";
    private static final String BINARY = "application/octet-stream";
    private static final String ACCOUNT_FILE = "drive.bin";
    private static final String PREF_LAST = "vault_drive_last_backup";

    private static final int CHECKPOINT_FILES = 20;
    private static final long CHECKPOINT_MS = 60_000;
    private static final int KEEP_RECENT = 3;
    private static final long KEEP_DAILY_FOR_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long ORPHAN_GRACE_MS = 24L * 60 * 60 * 1000;

    /** One Drive job at a time. */
    private static final ReentrantLock RUN = new ReentrantLock();

    /** A restore point as the snapshot index lists it. */
    static final class Summary {
        String id, device, installId;
        long createdAt, totalBytes;
        int itemCount;
        boolean pinned;
    }

    // =============================================================== account

    /** True when a Google account is connected for the vault's backups. */
    static boolean connected() {
        return Files.exists(VaultCrypto.dir().resolve(ACCOUNT_FILE));
    }

    /** The connected account's email, or null. The vault must be open. */
    static String email() {
        JSONObject a = account();
        return a == null ? null : a.optString("email", null);
    }

    private static JSONObject account() {
        try {
            if (!connected()) return null;
            return new JSONObject(new String(VaultCrypto.readAll(VaultCrypto.dir().resolve(ACCOUNT_FILE)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Signs a Google account in for Drive (browser). Blocking. */
    static void connect() throws IOException {
        GoogleAuth.Tokens t = GoogleAuth.authorize("openid email " + GoogleAuth.SCOPE_DRIVE, null);
        if (t.refreshToken() == null) throw new IOException("Google didn't grant lasting access. Try again.");
        try (OutputStream out = VaultCrypto.write(VaultCrypto.dir(), ACCOUNT_FILE)) {
            out.write(new JSONObject().put("refresh", t.refreshToken()).put("email", t.email() == null ? "" : t.email())
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Forgets the account here (the backup on Drive stays). */
    static void disconnect() throws IOException {
        JSONObject a = account();
        if (a != null) GoogleAuth.revoke(a.optString("refresh"));
        Files.deleteIfExists(VaultCrypto.dir().resolve(ACCOUNT_FILE));
        Prefs.put(PREF_LAST, null);
    }

    static long lastBackup() {
        return Prefs.getLong(PREF_LAST);
    }

    private static Drive drive() throws IOException {
        JSONObject a = account();
        if (a == null || a.optString("refresh").isEmpty()) throw new IOException("Connect a Google account first");
        return new Drive(a.optString("refresh"));
    }

    // ================================================================ session

    /** The folders and key of one job. */
    private static final class Store {
        final Drive drive;
        final String root, objects, snapshots;

        Store() throws IOException {
            drive = drive();
            String parent = "root";
            for (String part : ROOT_PATH) parent = drive.folder(part, parent);
            root = parent;
            objects = drive.folder(OBJECTS, root);
            snapshots = drive.folder(SNAPSHOTS, root);
        }
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private static <T> T exclusive(Backup.Progress progress, Work<T> work) throws Exception {
        if (!RUN.tryLock()) {
            progress.update(0, 0, "Waiting for the backup in progress");
            RUN.lockInterruptibly();
        }
        try {
            return work.run();
        } finally {
            RUN.unlock();
        }
    }

    /**
     * Makes this computer's backup key the store's. A store that is new gets our keyring; one
     * the phone started keeps its own, and its key is taken here once, opened with
     * {@code password} (a vault lock from the phone, or its backup password).
     *
     * @throws Backup.WrongPassword when the store is another installation's and the password is
     *                              missing or doesn't open it
     */
    private static void settleKey(Store s, String password) throws Exception {
        JSONObject keyring = readPlain(s, KEYRING, s.root);
        String theirs = keyring == null ? "" : keyring.optString("keyId", "");
        if (theirs.isEmpty() || theirs.equals(VaultCrypto.backupKeyId())) {
            // Ours (or new): a fresh keyring every time, so a changed lock always reaches Drive.
            writePlain(s, KEYRING, s.root, Backup.keyring(VaultStore.get()));
            return;
        }
        String tried = password != null ? password : mainLock();
        byte[] key;
        try {
            key = Backup.unwrap(keyring, tried);
        } catch (Backup.WrongPassword e) {
            throw new Backup.WrongPassword(Backup.describe(keyring));
        }
        // The phone's backup password (its only wrap when set) becomes this computer's too.
        JSONObject passwordWrap = null;
        JSONArray wraps = keyring.optJSONArray("wraps");
        for (int i = 0; wraps != null && i < wraps.length(); i++) {
            JSONObject w = wraps.getJSONObject(i);
            if (w.optString("label").startsWith("Backup")) passwordWrap = w;
        }
        VaultCrypto.adoptBackupKey(key, theirs, passwordWrap);
        writePlain(s, KEYRING, s.root, Backup.keyring(VaultStore.get()));
    }

    /** The main vault's lock: tried first, so the usual case needs no question at all. */
    private static String mainLock() {
        try {
            for (VaultStore.Vault v : VaultStore.get().vaults()) if (v.main) return VaultCrypto.lockValue(v.uuid);
        } catch (Exception ignored) {
            // Then the person is asked.
        }
        return null;
    }

    // ================================================================= backup

    /**
     * Uploads what Drive doesn't have, then publishes a restore point. Objects go first, then
     * the index, then the restore point, so a run that stops part-way leaves a consistent store
     * holding fewer objects; the next run carries on.
     */
    static Backup.Result backup(String password, Backup.Progress progress) throws Exception {
        return exclusive(progress, () -> {
            progress.update(0, 0, "Connecting to Google Drive");
            Store s = new Store();
            settleKey(s, password);
            Map<String, JSONObject> index = loadIndex(s);

            VaultStore vault = VaultStore.get();
            List<Path> toUpload = new ArrayList<>();
            long total = 0;
            for (VaultStore.Secret x : vault.allSecrets()) {
                if (x.fileName == null || index.containsKey(x.fileName) || !Snapshot.safe(x.fileName)) continue;
                Path f = VaultCrypto.mediaDir().resolve(x.fileName);
                if (!Files.exists(f)) continue;
                toUpload.add(f);
                total += Files.size(f);
            }

            long done = 0, lastSave = System.currentTimeMillis();
            int count = 0, sinceSave = 0, failed = 0;
            for (Path f : toUpload) {
                if (progress.stopped()) {
                    saveIndex(s, index);
                    throw new CancellationException("Backup stopped; the next one carries on from here");
                }
                String name = f.getFileName().toString();
                String message = "Uploading " + (count + 1) + " of " + toUpload.size();
                progress.update(done, total, message);
                Path staged = stage(name);
                try {
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = new DigestInputStream(VaultCrypto.read(f), sha);
                         OutputStream file = Files.newOutputStream(staged);
                         OutputStream enc = Portable.encrypting(file, VaultCrypto.backupKey(), VaultCrypto.backupKeyId())) {
                        in.transferTo(enc);
                    }
                    String id = s.drive.putOrReplace(staged, name, BINARY, s.objects);
                    index.put(name, new JSONObject().put("name", name).put("driveId", id).put("size", Files.size(staged))
                            .put("sha256", HexFormat.of().formatHex(sha.digest())).put("uploadedAt", System.currentTimeMillis()));
                    sinceSave++;
                } catch (Drive.AuthException | Drive.QuotaException fatal) {
                    saveIndex(s, index);
                    throw fatal;
                } catch (IOException network) {
                    // Most likely the connection: keep what made it.
                    saveIndex(s, index);
                    throw network;
                } catch (Exception e) {
                    failed++;
                    System.getLogger("CloudBackup").log(System.Logger.Level.WARNING, "Could not upload " + name, e);
                } finally {
                    Files.deleteIfExists(staged);
                }
                count++;
                done += Files.size(f);
                progress.update(done, total, message);
                if (sinceSave >= CHECKPOINT_FILES || System.currentTimeMillis() - lastSave >= CHECKPOINT_MS) {
                    saveIndex(s, index);
                    sinceSave = 0;
                    lastSave = System.currentTimeMillis();
                }
            }

            progress.update(total, total, "Saving the index");
            saveIndex(s, index);
            progress.update(total, total, "Backing up wallpapers");
            uploadBackgrounds(s);

            progress.update(total, total, "Creating a restore point");
            Snapshot snapshot = Snapshot.capture(vault);
            // A restore point names only what is stored, with the hash recorded at upload.
            snapshot.media.removeIf(r -> !index.containsKey(r.fileName));
            for (Snapshot.MediaRecord r : snapshot.media) {
                JSONObject e = index.get(r.fileName);
                if (r.sha256 == null && e != null && !e.isNull("sha256")) r.sha256 = e.optString("sha256", null);
            }
            writeEncrypted(s, snapshot.id + ".json", s.snapshots, snapshot.toJson());

            List<Summary> all = readSnapshotIndex(s);
            Summary mine = new Summary();
            mine.id = snapshot.id;
            mine.createdAt = snapshot.createdAt;
            mine.device = snapshot.device;
            mine.installId = snapshot.installId;
            mine.itemCount = snapshot.media.size();
            long stored = 0;
            for (JSONObject e : index.values()) stored += e.optLong("size");
            mine.totalBytes = stored;
            all.add(mine);
            List<Summary> kept = retain(all, System.currentTimeMillis());
            writeSnapshotIndex(s, kept);

            progress.update(total, total, "Tidying up");
            collectGarbage(s, index, kept, all);
            Prefs.put(PREF_LAST, System.currentTimeMillis());
            if (failed > 0) throw new IOException(failed + " items couldn't be uploaded this time; the rest are backed up.");
            return new Backup.Result(snapshot.media.size(), stored);
        });
    }

    // ================================================================ restore

    /** The restore points on Drive, newest first. May need the key (see {@link #settleKey}). */
    static List<Summary> restorePoints(String password, Backup.Progress progress) throws Exception {
        return exclusive(progress, () -> {
            progress.update(0, 0, "Reading your Drive backup");
            Store s = new Store();
            settleKey(s, password);
            return readSnapshotIndex(s);
        });
    }

    /**
     * Restores one point. Only items missing here are downloaded, each checked against the hash
     * recorded when it was uploaded; nothing local is deleted. {@code replaceLocal} lets the
     * backup's vault names, locks and wallpapers win.
     */
    static Backup.Result restore(String snapshotId, boolean replaceLocal, Backup.Progress progress) throws Exception {
        return exclusive(progress, () -> {
            progress.update(0, 0, "Reading the restore point");
            Store s = new Store();
            JSONObject doc = readEncrypted(s, snapshotId + ".json", s.snapshots);
            if (doc == null) throw new IOException("That restore point is no longer on Drive");
            Snapshot snapshot = Snapshot.fromJson(doc);
            Map<String, JSONObject> index = loadIndex(s);

            List<Snapshot.MediaRecord> missing = new ArrayList<>();
            long total = 0;
            for (Snapshot.MediaRecord r : snapshot.media) {
                if (!Snapshot.safe(r.fileName) || Files.exists(VaultCrypto.mediaDir().resolve(r.fileName))) continue;
                missing.add(r);
                JSONObject e = index.get(r.fileName);
                if (e != null) total += e.optLong("size");
            }
            List<Path> written = new ArrayList<>();
            int failed = 0, count = 0;
            long done = 0;
            try {
                for (Snapshot.MediaRecord r : missing) {
                    if (progress.stopped()) throw new CancellationException("Restore cancelled");
                    JSONObject e = index.get(r.fileName);
                    count++;
                    if (e == null) {
                        failed++;
                        continue;
                    }
                    String message = "Downloading " + count + " of " + missing.size();
                    progress.update(done, total, message);
                    Path staged = stage(r.fileName);
                    Path target = VaultCrypto.mediaDir().resolve(r.fileName);
                    try {
                        s.drive.download(e.getString("driveId"), staged);
                        String expected = r.sha256 != null ? r.sha256 : e.isNull("sha256") ? null : e.optString("sha256", null);
                        MessageDigest sha = MessageDigest.getInstance("SHA-256");
                        try (InputStream raw = Files.newInputStream(staged);
                             InputStream plain = decrypting(raw);
                             OutputStream out = new DigestOutputStream(VaultCrypto.write(VaultCrypto.mediaDir(), r.fileName), sha)) {
                            plain.transferTo(out);
                        }
                        written.add(target);
                        if (expected != null && !expected.equalsIgnoreCase(HexFormat.of().formatHex(sha.digest()))) {
                            Files.deleteIfExists(target);
                            written.remove(target);
                            throw new IOException(r.fileName + " didn't match its recorded hash");
                        }
                    } catch (Drive.AuthException fatal) {
                        throw fatal;
                    } catch (IOException ex) {
                        if (ex.getMessage() != null && ex.getMessage().startsWith("Drive: HTTP")) throw ex;
                        failed++;
                        System.getLogger("CloudBackup").log(System.Logger.Level.WARNING, "Could not restore " + r.fileName, ex);
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                    done += e.optLong("size");
                    progress.update(done, total, message);
                }
                progress.update(total, total, "Restoring wallpapers");
                downloadBackgrounds(s);
                progress.update(total, total, "Updating your vaults");
                snapshot.media.removeIf(r -> !Files.exists(VaultCrypto.mediaDir().resolve(r.fileName)));
                snapshot.apply(VaultStore.get(), replaceLocal);
                written.clear(); // Rows point at them now; they stay.
            } finally {
                // A failed or cancelled restore leaves no file without a row.
                for (Path f : written) Files.deleteIfExists(f);
            }
            if (failed > 0) {
                throw new IOException(failed + " of " + missing.size() + " items couldn't be restored. Everything else is back."
                        + " (Items from very old phone backups open only on the phone.)");
            }
            return new Backup.Result(count, done);
        });
    }

    /** Keeps a restore point regardless of age, or lets it age out again. */
    static void setPinned(String snapshotId, boolean pinned, Backup.Progress progress) throws Exception {
        exclusive(progress, () -> {
            Store s = new Store();
            List<Summary> all = readSnapshotIndex(s);
            for (Summary x : all) if (x.id.equals(snapshotId)) x.pinned = pinned;
            writeSnapshotIndex(s, all);
            return null;
        });
    }

    // ====================================================== retention and GC

    /** The phone's rule: anything pinned, the 3 newest per installation, one a day for 30 days. */
    static List<Summary> retain(List<Summary> all, long now) {
        Map<String, List<Summary>> byInstall = new LinkedHashMap<>();
        for (Summary x : all) {
            String key = x.installId != null ? x.installId : x.device != null ? x.device : "unknown";
            byInstall.computeIfAbsent(key, k -> new ArrayList<>()).add(x);
        }
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        List<Summary> kept = new ArrayList<>();
        for (List<Summary> bucket : byInstall.values()) {
            bucket.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            Set<String> days = new HashSet<>();
            for (int i = 0; i < bucket.size(); i++) {
                Summary x = bucket.get(i);
                boolean daily = now - x.createdAt < KEEP_DAILY_FOR_MS && days.add(day.format(new Date(x.createdAt)));
                if (x.pinned || i < KEEP_RECENT || daily) kept.add(x);
            }
        }
        kept.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return kept;
    }

    /**
     * Removes aged-out restore points, then objects no kept point names (and older than a day:
     * another device may be mid-backup). If any kept point can't be read, nothing is deleted.
     */
    private static void collectGarbage(Store s, Map<String, JSONObject> index, List<Summary> kept, List<Summary> all) {
        Set<String> keptIds = new HashSet<>(), live = new HashSet<>();
        for (Summary x : kept) keptIds.add(x.id);
        for (Summary x : kept) {
            try {
                JSONObject doc = readEncrypted(s, x.id + ".json", s.snapshots);
                if (doc == null) continue;
                for (Snapshot.MediaRecord r : Snapshot.fromJson(doc).media) live.add(r.fileName);
            } catch (Exception e) {
                System.getLogger("CloudBackup").log(System.Logger.Level.INFO, "Restore point unreadable here; no cleanup this time", e);
                return;
            }
        }
        for (Summary x : all) {
            if (keptIds.contains(x.id)) continue;
            try {
                String id = s.drive.newest(x.id + ".json", s.snapshots);
                if (id != null) s.drive.delete(id);
            } catch (Exception ignored) {
                // Next time.
            }
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String name : new ArrayList<>(index.keySet())) {
            JSONObject e = index.get(name);
            long at = e.optLong("uploadedAt");
            if (live.contains(name) || (at > 0 && now - at < ORPHAN_GRACE_MS)) continue;
            try {
                s.drive.delete(e.getString("driveId"));
                index.remove(name);
                changed = true;
            } catch (Exception ignored) {
                // Next time.
            }
        }
        if (changed) {
            try {
                saveIndex(s, index);
            } catch (Exception ignored) {
                // The objects are gone; a stale entry only costs a re-upload check.
            }
        }
    }

    // =============================================================== documents

    private static Map<String, JSONObject> loadIndex(Store s) throws Exception {
        Map<String, JSONObject> index = new LinkedHashMap<>();
        JSONObject doc;
        try {
            doc = readEncrypted(s, INDEX, s.root);
        } catch (IOException network) {
            if (network.getMessage() != null && network.getMessage().startsWith("Drive")) throw network;
            doc = null; // Unreadable here: rebuilt from the folder, as the phone does.
        }
        if (doc != null) {
            JSONArray objects = doc.optJSONArray("objects");
            for (int i = 0; objects != null && i < objects.length(); i++) {
                JSONObject o = objects.optJSONObject(i);
                if (o != null && o.has("name") && o.has("driveId")) index.put(o.getString("name"), o);
            }
            return index;
        }
        for (JSONObject f : s.drive.children(s.objects)) {
            index.put(f.getString("name"), new JSONObject().put("name", f.getString("name")).put("driveId", f.getString("id"))
                    .put("size", f.optLong("size")).put("sha256", JSONObject.NULL).put("uploadedAt", 0));
        }
        return index;
    }

    private static void saveIndex(Store s, Map<String, JSONObject> index) throws Exception {
        JSONArray objects = new JSONArray();
        for (JSONObject e : index.values()) objects.put(e);
        writeEncrypted(s, INDEX, s.root, new JSONObject().put("formatVersion", 2)
                .put("generation", System.currentTimeMillis()).put("objects", objects));
    }

    private static List<Summary> readSnapshotIndex(Store s) throws Exception {
        List<Summary> out = new ArrayList<>();
        JSONObject doc = readEncrypted(s, SNAPSHOT_INDEX, s.snapshots);
        JSONArray array = doc == null ? null : doc.optJSONArray("snapshots");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null || o.isNull("id")) continue;
            Summary x = new Summary();
            x.id = o.optString("id");
            x.createdAt = o.optLong("createdAt");
            x.device = o.isNull("device") ? null : o.optString("device", null);
            x.installId = o.isNull("installId") ? null : o.optString("installId", null);
            x.itemCount = o.optInt("itemCount");
            x.totalBytes = o.optLong("totalBytes");
            x.pinned = o.optBoolean("pinned");
            out.add(x);
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    private static void writeSnapshotIndex(Store s, List<Summary> list) throws Exception {
        JSONArray array = new JSONArray();
        for (Summary x : list) {
            array.put(new JSONObject().put("id", x.id).put("createdAt", x.createdAt)
                    .put("device", x.device == null ? JSONObject.NULL : x.device)
                    .put("installId", x.installId == null ? JSONObject.NULL : x.installId)
                    .put("itemCount", x.itemCount).put("totalBytes", x.totalBytes).put("pinned", x.pinned));
        }
        writeEncrypted(s, SNAPSHOT_INDEX, s.snapshots, new JSONObject().put("formatVersion", Snapshot.FORMAT_VERSION)
                .put("snapshots", array));
    }

    private static void writeEncrypted(Store s, String name, String parent, JSONObject doc) throws Exception {
        Path staged = stage("doc_" + name);
        try {
            try (OutputStream file = Files.newOutputStream(staged);
                 OutputStream enc = Portable.encrypting(file, VaultCrypto.backupKey(), VaultCrypto.backupKeyId())) {
                enc.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            }
            s.drive.putOrReplace(staged, name, BINARY, parent);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Null when there is no such document. A plain-JSON one (very old) is read as it is. */
    private static JSONObject readEncrypted(Store s, String name, String parent) throws Exception {
        byte[] raw = download(s, name, parent);
        if (raw == null) return null;
        if (raw.length > 0 && raw[0] == '{') return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        try (InputStream plain = decrypting(new ByteArrayInputStream(raw))) {
            return new JSONObject(new String(plain.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void writePlain(Store s, String name, String parent, JSONObject doc) throws Exception {
        Path staged = stage("doc_" + name);
        try {
            Files.writeString(staged, doc.toString(), StandardCharsets.UTF_8);
            s.drive.putOrReplace(staged, name, "application/json", parent);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static JSONObject readPlain(Store s, String name, String parent) throws Exception {
        byte[] raw = download(s, name, parent);
        return raw == null ? null : new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    private static byte[] download(Store s, String name, String parent) throws IOException {
        String id = s.drive.newest(name, parent);
        if (id == null) return null;
        Path staged = stage("dl_" + name);
        try {
            s.drive.download(id, staged);
            return Files.readAllBytes(staged);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** The plaintext of a v3 payload under this computer's backup key. */
    private static InputStream decrypting(InputStream raw) throws IOException {
        PushbackInputStream in = new PushbackInputStream(raw, 11);
        byte[] header = Portable.header(in);
        if (!Portable.keyId(header).equals(VaultCrypto.backupKeyId())) {
            throw new IOException("Made with another vault key");
        }
        return Portable.decrypting(in, header, VaultCrypto.backupKey());
    }

    // ============================================================ wallpapers

    /** Wallpapers (vault backgrounds), each uploaded once by name, as the phone does. */
    private static void uploadBackgrounds(Store s) {
        try (Stream<Path> dirs = Files.list(VaultCrypto.dir())) {
            String bgRoot = s.drive.folder(BACKGROUNDS, s.root);
            for (Path dir : dirs.filter(d -> Files.isDirectory(d) && d.getFileName().toString().startsWith("bg_")).toList()) {
                String remote = s.drive.folder(dir.getFileName().toString(), bgRoot);
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        String name = f.getFileName().toString();
                        if (s.drive.newest(name, remote) != null) continue;
                        Path staged = stage("bg_" + name);
                        try {
                            try (InputStream in = Files.newInputStream(f);
                                 OutputStream file = Files.newOutputStream(staged);
                                 OutputStream enc = Portable.encrypting(file, VaultCrypto.backupKey(), VaultCrypto.backupKeyId())) {
                                in.transferTo(enc);
                            }
                            s.drive.putOrReplace(staged, name, BINARY, remote);
                        } finally {
                            Files.deleteIfExists(staged);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.getLogger("CloudBackup").log(System.Logger.Level.INFO, "Wallpapers not backed up this time", e);
        }
    }

    /** Wallpapers this computer doesn't have yet. */
    private static void downloadBackgrounds(Store s) {
        try {
            String bgRoot = s.drive.newest(BACKGROUNDS, s.root);
            if (bgRoot == null) return;
            for (JSONObject dir : s.drive.children(bgRoot)) {
                String dirName = dir.getString("name");
                if (!dirName.startsWith("bg_") || !Snapshot.safe(dirName)) continue;
                Path local = VaultCrypto.dir().resolve(dirName);
                for (JSONObject f : s.drive.children(dir.getString("id"))) {
                    String name = f.getString("name");
                    if (!Snapshot.safe(name) || Files.exists(local.resolve(name))) continue;
                    Path staged = stage("bg_" + name);
                    try {
                        s.drive.download(f.getString("id"), staged);
                        Files.createDirectories(local);
                        Path part = local.resolve(name + ".part");
                        try (InputStream raw = Files.newInputStream(staged); InputStream plain = decrypting(raw)) {
                            Files.copy(plain, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        Files.move(part, local.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        System.getLogger("CloudBackup").log(System.Logger.Level.INFO, "Wallpaper " + name + " skipped", e);
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                }
            }
        } catch (Exception e) {
            System.getLogger("CloudBackup").log(System.Logger.Level.INFO, "Wallpapers not restored", e);
        }
    }

    // ================================================================ plumbing

    /** A staging file for ciphertext only: plaintext never touches the disk. */
    private static Path stage(String name) throws IOException {
        Path dir = Storage.root().resolve("cache").resolve("cloudstage");
        Files.createDirectories(dir);
        Path f = dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.deleteIfExists(f);
        return f;
    }

    /** Leftovers of a run that was killed. */
    static void clearStaging() {
        Path dir = Storage.root().resolve("cache").resolve("cloudstage");
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // Next time.
        }
    }

}
