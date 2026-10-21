package org.flexstudios.notes.plus;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;

/**
 * Backup, restore and retention against the Drive object store.
 *
 * The upload set is {@code local − alreadyStored}, read from {@link ObjectIndex} in one API
 * call. With 5 GB stored and 300 MB new, 300 MB is read, encrypted and sent.
 *
 * Three rules keep it safe:
 * <ul>
 *   <li><b>One operation at a time.</b> Every mutating call holds {@link #RUN}, so the
 *       background worker and a restore started from Settings can never interleave their
 *       read-modify-write of the index.</li>
 *   <li><b>Progress is durable.</b> The index is saved every {@link #CHECKPOINT_FILES} files
 *       or {@link #CHECKPOINT_MS}, and when the run is asked to stop. A multi-gigabyte first
 *       backup that the system interrupts resumes where it was instead of starting over.</li>
 *   <li><b>Nothing a restore point names is deleted.</b> Restore points are kept by age (see
 *       {@link #applyRetention}); an object is collectable only once none of them names it.</li>
 * </ul>
 */
public class CloudSync {

    private static final String TAG = "CloudSync";
    private static final String SNAPSHOT_INDEX = "index.json";
    private static final String BASELINE_FILE = "last_synced_manifest.json";
    private static final String PREF_LAST_SYNC = "drive_last_sync";

    private static final int CHECKPOINT_FILES = 20;
    private static final long CHECKPOINT_MS = TimeUnit.SECONDS.toMillis(60);

    /** The newest restore points of each installation, kept regardless of age. */
    private static final int KEEP_RECENT = 3;
    /** Beyond those, one per day is kept for this long. */
    private static final long KEEP_DAILY_FOR_MS = TimeUnit.DAYS.toMillis(30);
    /**
     * Objects younger than this are never collected, even if no restore point names them
     * yet: another device may be mid-backup and about to publish one that does.
     */
    private static final long ORPHAN_GRACE_MS = TimeUnit.HOURS.toMillis(24);

    /** Serialises every mutating operation in the process. */
    private static final ReentrantLock RUN = new ReentrantLock();

    public interface Progress {
        void onStage(String message);

        void onItem(int done, int total, long bytesDone, long bytesTotal);

        /** Polled between files; return true to checkpoint and stop. */
        default boolean isStopped() {
            return false;
        }
    }

    /** What a backup is about to do, computed before anything is transferred. */
    public static class Plan {
        public int localCount;
        public int storedCount;
        public long storedBytes;
        public final List<String> toUpload = new ArrayList<>();
        public long uploadBytes;
        /** This device has never synced against a store that already holds data. */
        public boolean needsChoice;
    }

    /** How a first-time mismatch between device and store should be settled. */
    public enum Resolution {
        /** Union of both. Nothing is deleted anywhere. The safe default. */
        MERGE,
        /** The store wins; local media missing from it is left alone but not backed up. */
        DOWNLOAD,
        /** The device wins; objects no retained snapshot names become collectable. */
        UPLOAD
    }

    private interface Operation<T> {
        T run() throws Exception;
    }

    private final Context context;
    private final DriveStore store;
    private final AppDatabase db;

    private String rootId;
    private String objectsId;
    private String snapshotsId;

    public CloudSync(Context context, GoogleSignInAccount account) {
        this.context = context.getApplicationContext();
        this.store = new DriveStore(this.context, account);
        this.db = AppDatabase.getInstance(this.context);
    }

    private <T> T exclusive(Progress progress, Operation<T> operation) throws Exception {
        if (!RUN.tryLock()) {
            stage(progress, "Waiting for the backup in progress");
            RUN.lockInterruptibly();
        }
        try {
            return operation.run();
        } finally {
            RUN.unlock();
        }
    }

    private void resolveFolders() throws IOException {
        if (rootId != null) return;
        rootId = store.rootFolder();
        objectsId = store.folder(DriveStore.FOLDER_OBJECTS, rootId);
        snapshotsId = store.folder(DriveStore.FOLDER_SNAPSHOTS, rootId);
    }

    // -------------------------------------------------------------------- plan

    /** Reads the store inventory and works out what a backup would cost. Transfers nothing. */
    public Plan plan() throws Exception {
        resolveFolders();
        ObjectIndex index = loadIndex();
        List<SecretEntity> secrets = db.secretDao().getAllSecretsDirect();

        Plan plan = new Plan();
        plan.localCount = secrets.size();
        plan.storedCount = index.size();
        plan.storedBytes = index.totalBytes();

        File vaultDir = VaultMedia.vaultDir(context);
        for (SecretEntity secret : secrets) {
            String name = secret.getFileName();
            if (name == null || index.contains(name)) continue;
            File local = new File(vaultDir, name);
            if (!local.exists()) continue;
            plan.toUpload.add(name);
            plan.uploadBytes += local.length();
        }

        plan.needsChoice = !hasBaseline() && index.size() > 0;
        return plan;
    }

    // ------------------------------------------------------------------ backup

    /**
     * Uploads what the plan says is missing, then publishes a new restore point.
     *
     * Objects go first, then the index, then the restore point, so a run that dies part-way
     * leaves a consistent store that simply holds fewer objects. The restore point lists only
     * objects that are actually stored.
     */
    public Snapshot backup(Plan plan, Resolution resolution, Progress progress) throws Exception {
        return exclusive(progress, () -> {
            resolveFolders();
            ensureKeyring();

            ObjectIndex index = loadIndex();
            SecretKey dataKey = VaultCrypto.getOrCreateDataKey(context);
            String keyId = VaultCrypto.getKeyId(context);

            stage(progress, "Preparing");
            File vaultDir = VaultMedia.vaultDir(context);
            Map<String, SecretEntity> byName = new HashMap<>();
            for (SecretEntity secret : db.secretDao().getAllSecretsDirect()) {
                if (secret.getFileName() != null) byName.put(secret.getFileName(), secret);
            }

            int total = plan.toUpload.size();
            int done = 0;
            int failed = 0;
            long bytesDone = 0;
            int sinceCheckpoint = 0;
            long lastCheckpoint = System.currentTimeMillis();

            for (String name : plan.toUpload) {
                if (progress != null && progress.isStopped()) {
                    index.save(store, rootId);
                    throw new InterruptedIOException("Backup paused; it will resume");
                }
                File local = new File(vaultDir, name);
                if (index.contains(name) || !local.exists()) {
                    done++;
                    continue;
                }
                stage(progress, "Uploading " + (done + 1) + " of " + total);

                File staged = stagingFile(name);
                try {
                    String sha256 = transcodeForUpload(local, staged, dataKey, keyId);
                    String driveId = store.putFile(staged, name, "application/octet-stream", objectsId);
                    index.put(new ObjectIndex.Entry(name, driveId, staged.length(), sha256,
                            System.currentTimeMillis()));
                    sinceCheckpoint++;

                    SecretEntity secret = byName.get(name);
                    if (secret != null && secret.getContentHash() == null) {
                        secret.setContentHash(sha256);
                        db.secretDao().update(secret);
                    }
                } catch (DriveStore.AuthException | DriveStore.QuotaException fatal) {
                    index.save(store, rootId);
                    throw fatal;
                } catch (IOException network) {
                    // Likely the connection; keep what made it and let the worker retry.
                    index.save(store, rootId);
                    throw network;
                } catch (Exception e) {
                    failed++;
                    Log.w(TAG, "Could not upload " + name + "; continuing", e);
                } finally {
                    delete(staged);
                }

                done++;
                bytesDone += local.length();
                item(progress, done, total, bytesDone, plan.uploadBytes);

                long now = System.currentTimeMillis();
                if (sinceCheckpoint >= CHECKPOINT_FILES || now - lastCheckpoint >= CHECKPOINT_MS) {
                    index.save(store, rootId);
                    sinceCheckpoint = 0;
                    lastCheckpoint = now;
                }
            }

            stage(progress, "Saving index");
            index.save(store, rootId);

            stage(progress, "Backing up wallpapers");
            uploadBackgrounds(dataKey, keyId);

            stage(progress, "Creating restore point");
            Snapshot snapshot = Snapshot.capture(context, db, index.names());
            store.writeEncrypted(snapshot.fileName(), snapshotsId, snapshot.toJson());

            List<Snapshot.Summary> summaries = readSnapshotIndex();
            Snapshot.Summary summary = new Snapshot.Summary();
            summary.id = snapshot.id;
            summary.createdAt = snapshot.createdAt;
            summary.device = snapshot.device;
            summary.installId = snapshot.installId;
            summary.itemCount = snapshot.media.size();
            summary.totalBytes = index.totalBytes();
            summaries.add(summary);

            List<Snapshot.Summary> retained = applyRetention(summaries, System.currentTimeMillis());
            writeSnapshotIndex(retained);

            // DOWNLOAD means "the store wins": it must not lose anything on this run.
            if (resolution != Resolution.DOWNLOAD) {
                stage(progress, "Tidying up");
                collectGarbage(index, retained, summaries);
            }

            writeBaseline(snapshot.mediaFileNames());
            SecurityHelper.getAppPrefs(context).edit()
                    .putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply();
            if (failed > 0) Log.w(TAG, failed + " item(s) could not be uploaded this run");
            stage(progress, "Done");
            return snapshot;
        });
    }

    // ----------------------------------------------------------------- restore

    public List<Snapshot.Summary> listRestorePoints() throws Exception {
        resolveFolders();
        return readSnapshotIndex();
    }

    /**
     * Applies a restore point. Only objects genuinely absent locally are downloaded, so
     * restoring onto the same phone after a crash pulls almost nothing. Each download is
     * checked against the content hash recorded when it was uploaded.
     *
     * @param lockValue a vault lock or the backup password; opens the keyring
     */
    public void restore(String snapshotId, boolean replaceLocal, String lockValue,
                        Progress progress) throws Exception {
        exclusive(progress, () -> {
            resolveFolders();

            stage(progress, "Unlocking backup");
            adoptKeyFromKeyring(lockValue);

            stage(progress, "Reading restore point");
            VaultCrypto.KeyResolver resolver = VaultCrypto.localResolver(context, lockValue);
            JSONObject document = store.readEncrypted(snapshotId + ".json", snapshotsId, resolver);
            if (document == null) throw new IOException("That restore point is no longer on Drive");
            Snapshot snapshot = Snapshot.fromJson(document);

            ObjectIndex index = loadIndex();
            File vaultDir = VaultMedia.vaultDir(context);

            List<Snapshot.MediaRecord> missing = new ArrayList<>();
            for (Snapshot.MediaRecord record : snapshot.media) {
                if (!new File(vaultDir, record.fileName).exists()) missing.add(record);
            }

            int total = missing.size();
            int done = 0;
            int failed = 0;
            long bytesTotal = 0, bytesDone = 0;
            for (Snapshot.MediaRecord record : missing) {
                ObjectIndex.Entry entry = index.get(record.fileName);
                if (entry != null) bytesTotal += entry.size;
            }
            for (Snapshot.MediaRecord record : missing) {
                if (progress != null && progress.isStopped()) {
                    throw new java.util.concurrent.CancellationException("Restore cancelled");
                }
                ObjectIndex.Entry entry = index.get(record.fileName);
                if (entry == null) {
                    Log.w(TAG, "Restore point references a missing object: " + record.fileName);
                    failed++;
                    done++;
                    continue;
                }
                stage(progress, "Downloading " + (done + 1) + " of " + total);

                File staged = stagingFile(record.fileName);
                try {
                    store.downloadFile(entry.driveId, staged);
                    String expected = record.sha256 != null ? record.sha256 : entry.sha256;
                    transcodeForStorage(staged, new File(vaultDir, record.fileName), resolver, expected);
                } catch (IOException network) {
                    throw network;
                } catch (Exception e) {
                    failed++;
                    Log.w(TAG, "Could not restore " + record.fileName, e);
                } finally {
                    delete(staged);
                }
                done++;
                if (entry != null) bytesDone += entry.size;
                item(progress, done, total, bytesDone, bytesTotal);
            }

            stage(progress, "Updating your vaults");
            snapshot.apply(context, db, replaceLocal);
            ThumbnailStore.get(context).clearAll();
            writeBaseline(snapshot.mediaFileNames());
            if (failed > 0) {
                throw new IOException(failed + " of " + snapshot.media.size()
                        + " items could not be restored. Everything else is back.");
            }
            stage(progress, "Done");
            return null;
        });
    }

    /** Empties the store so the next backup rewrites everything in the current format. */
    public void discardStore(Progress progress) throws Exception {
        exclusive(progress, () -> {
            resolveFolders();
            stage(progress, "Clearing the old backup");
            for (com.google.api.services.drive.model.File file : store.listChildren(objectsId)) {
                store.deleteFile(file.getId());
            }
            for (com.google.api.services.drive.model.File file : store.listChildren(snapshotsId)) {
                store.deleteFile(file.getId());
            }
            String indexId = store.findFile(ObjectIndex.FILE_NAME, rootId);
            if (indexId != null) store.deleteFile(indexId);

            clearBaseline(context);
            publishKeyring();
            VaultCrypto.markLegacyPayloadsCleared(context, true);
            return null;
        });
    }

    // ------------------------------------------------------------- legacy re-key

    /**
     * Objects that may still be in the pre-key-hierarchy format: ones inherited from an older
     * backup carry no content hash, everything this build uploads does.
     */
    public List<String> legacyCandidates() throws Exception {
        resolveFolders();
        List<String> candidates = new ArrayList<>();
        for (ObjectIndex.Entry entry : loadIndex().all()) {
            if (entry.sha256 == null) candidates.add(entry.name);
        }
        return candidates;
    }

    /**
     * Rewrites objects still encrypted with a lock value so they use the vault data key.
     * Streamed end to end: no plaintext copy is written. Safe to interrupt.
     *
     * @param legacyPassword the lock value those objects were written with
     */
    public void rekeyLegacyObjects(String legacyPassword, Progress progress) throws Exception {
        exclusive(progress, () -> {
            resolveFolders();
            ObjectIndex index = loadIndex();
            SecretKey dataKey = VaultCrypto.getOrCreateDataKey(context);
            String keyId = VaultCrypto.getKeyId(context);
            VaultCrypto.KeyResolver resolver = VaultCrypto.localResolver(context, legacyPassword);

            List<ObjectIndex.Entry> candidates = new ArrayList<>();
            for (ObjectIndex.Entry entry : index.all()) {
                if (entry.sha256 == null) candidates.add(entry);
            }

            int total = candidates.size();
            int done = 0;
            for (ObjectIndex.Entry entry : candidates) {
                stage(progress, "Updating " + (done + 1) + " of " + total);
                File downloaded = stagingFile("rk_" + entry.name);
                File rewritten = stagingFile("rw_" + entry.name);
                try {
                    store.downloadFile(entry.driveId, downloaded);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    boolean legacy;
                    try (PushbackInputStream in = new PushbackInputStream(new FileInputStream(downloaded), 3)) {
                        VaultCrypto.Header header = VaultCrypto.readHeader(in);
                        legacy = header.isLegacy();
                        try (InputStream plain = new DigestInputStream(
                                VaultCrypto.openDecrypted(in, header, resolver), digest)) {
                            if (legacy) {
                                try (OutputStream out = new FileOutputStream(rewritten)) {
                                    VaultCrypto.encrypt(plain, out, dataKey, keyId);
                                }
                            } else {
                                // Already current: read it through once so the digest covers
                                // it, and it is never reconsidered.
                                byte[] buffer = new byte[64 * 1024];
                                while (plain.read(buffer) != -1) {
                                    // The digest updates as a side effect of reading.
                                }
                            }
                        }
                    }
                    String hash = VaultCrypto.bytesToHex(digest.digest());
                    if (legacy) {
                        String driveId = store.putFile(rewritten, entry.name, "application/octet-stream", objectsId);
                        index.put(new ObjectIndex.Entry(entry.name, driveId, rewritten.length(), hash,
                                System.currentTimeMillis()));
                    } else {
                        index.put(new ObjectIndex.Entry(entry.name, entry.driveId, entry.size, hash,
                                entry.uploadedAt));
                    }
                } catch (VaultCrypto.WrongPasswordException e) {
                    throw e;
                } catch (Exception e) {
                    Log.w(TAG, "Could not re-key " + entry.name, e);
                } finally {
                    delete(downloaded);
                    delete(rewritten);
                }
                done++;
                item(progress, done, total, done, total);
            }

            stage(progress, "Saving index");
            index.save(store, rootId);
            publishKeyring();

            boolean remaining = false;
            for (ObjectIndex.Entry entry : index.all()) {
                if (entry.sha256 == null) {
                    remaining = true;
                    break;
                }
            }
            if (!remaining) VaultCrypto.markLegacyPayloadsCleared(context, true);
            stage(progress, "Done");
            return null;
        });
    }

    // ------------------------------------------------------------- retention/GC

    /**
     * Which restore points survive.
     *
     * Kept: anything pinned; the {@link #KEEP_RECENT} newest of each installation; and the
     * newest of each calendar day for {@link #KEEP_DAILY_FOR_MS}. The old rule kept the five
     * newest per phone model, and with auto-backup after every import five runs could happen
     * in minutes — an accidental delete aged out before anyone noticed.
     */
    static List<Snapshot.Summary> applyRetention(List<Snapshot.Summary> all, long now) {
        Map<String, List<Snapshot.Summary>> byInstall = new LinkedHashMap<>();
        for (Snapshot.Summary summary : all) {
            String key = summary.installId != null ? summary.installId
                    : summary.device != null ? summary.device : "unknown";
            List<Snapshot.Summary> bucket = byInstall.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byInstall.put(key, bucket);
            }
            bucket.add(summary);
        }

        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        List<Snapshot.Summary> retained = new ArrayList<>();
        for (List<Snapshot.Summary> bucket : byInstall.values()) {
            bucket.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            Set<String> daysKept = new HashSet<>();
            for (int i = 0; i < bucket.size(); i++) {
                Snapshot.Summary summary = bucket.get(i);
                String dayKey = day.format(new Date(summary.createdAt));
                boolean recent = i < KEEP_RECENT;
                boolean daily = now - summary.createdAt < KEEP_DAILY_FOR_MS && daysKept.add(dayKey);
                if (summary.pinned || recent || daily) retained.add(summary);
            }
        }
        retained.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return retained;
    }

    /**
     * Deletes the manifests of restore points that aged out, then objects no retained point
     * names. If any retained manifest cannot be read, nothing is deleted at all.
     */
    private void collectGarbage(ObjectIndex index, List<Snapshot.Summary> retained,
                                List<Snapshot.Summary> all) {
        Set<String> retainedIds = new HashSet<>();
        for (Snapshot.Summary summary : retained) retainedIds.add(summary.id);

        Set<String> live = new HashSet<>();
        VaultCrypto.KeyResolver resolver = VaultCrypto.localResolver(context, null);
        for (Snapshot.Summary summary : retained) {
            try {
                JSONObject document = store.readEncrypted(summary.id + ".json", snapshotsId, resolver);
                if (document == null) continue;
                live.addAll(Snapshot.fromJson(document).mediaFileNames());
            } catch (Exception e) {
                Log.w(TAG, "Could not read restore point " + summary.id + "; skipping cleanup", e);
                return;
            }
        }

        for (Snapshot.Summary summary : all) {
            if (retainedIds.contains(summary.id)) continue;
            try {
                String fileId = store.findFile(summary.id + ".json", snapshotsId);
                if (fileId != null) store.deleteFile(fileId);
            } catch (Exception e) {
                Log.w(TAG, "Could not remove aged-out restore point " + summary.id, e);
            }
        }

        long now = System.currentTimeMillis();
        List<String> orphans = new ArrayList<>();
        for (ObjectIndex.Entry entry : index.all()) {
            if (live.contains(entry.name)) continue;
            if (entry.uploadedAt > 0 && now - entry.uploadedAt < ORPHAN_GRACE_MS) continue;
            orphans.add(entry.name);
        }
        for (String name : orphans) {
            ObjectIndex.Entry entry = index.get(name);
            try {
                if (entry != null) store.deleteFile(entry.driveId);
                index.remove(name);
            } catch (Exception e) {
                Log.w(TAG, "Could not remove orphaned object " + name, e);
            }
        }
        if (!orphans.isEmpty()) {
            try {
                index.save(store, rootId);
            } catch (Exception e) {
                Log.w(TAG, "Could not save the index after cleanup", e);
            }
        }
    }

    // -------------------------------------------------------------- snapshot index

    private List<Snapshot.Summary> readSnapshotIndex() throws Exception {
        JSONObject document = store.readEncrypted(SNAPSHOT_INDEX, snapshotsId,
                VaultCrypto.localResolver(context, null));
        List<Snapshot.Summary> summaries = new ArrayList<>();
        if (document == null) return summaries;

        JSONArray array = document.optJSONArray("snapshots");
        if (array == null) return summaries;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || item.isNull("id")) continue;
            Snapshot.Summary summary = new Snapshot.Summary();
            summary.id = item.optString("id");
            summary.createdAt = item.optLong("createdAt", 0);
            summary.device = item.isNull("device") ? null : item.optString("device", null);
            summary.installId = item.isNull("installId") ? null : item.optString("installId", null);
            summary.itemCount = item.optInt("itemCount", 0);
            summary.totalBytes = item.optLong("totalBytes", 0);
            summary.pinned = item.optBoolean("pinned", false);
            summaries.add(summary);
        }
        summaries.sort(Comparator.comparingLong((Snapshot.Summary s) -> s.createdAt).reversed());
        return summaries;
    }

    private void writeSnapshotIndex(List<Snapshot.Summary> summaries) throws Exception {
        JSONObject document = new JSONObject();
        document.put("formatVersion", Snapshot.FORMAT_VERSION);
        JSONArray array = new JSONArray();
        for (Snapshot.Summary summary : summaries) {
            JSONObject item = new JSONObject();
            item.put("id", summary.id);
            item.put("createdAt", summary.createdAt);
            item.put("device", summary.device == null ? JSONObject.NULL : summary.device);
            item.put("installId", summary.installId == null ? JSONObject.NULL : summary.installId);
            item.put("itemCount", summary.itemCount);
            item.put("totalBytes", summary.totalBytes);
            item.put("pinned", summary.pinned);
            array.put(item);
        }
        document.put("snapshots", array);
        store.writeEncrypted(SNAPSHOT_INDEX, snapshotsId, document);
    }

    public void setPinned(String snapshotId, boolean pinned) throws Exception {
        exclusive(null, () -> {
            resolveFolders();
            List<Snapshot.Summary> summaries = readSnapshotIndex();
            for (Snapshot.Summary summary : summaries) {
                if (summary.id.equals(snapshotId)) summary.pinned = pinned;
            }
            writeSnapshotIndex(summaries);
            return null;
        });
    }

    // -------------------------------------------------------------------- keyring

    /** Publishes a keyring if the store has none. Never overwrites another install's. */
    private void ensureKeyring() throws Exception {
        JSONObject existing = store.readPlain(VaultCrypto.KEYRING_FILE, rootId);
        if (existing == null) publishKeyring();
    }

    /**
     * Re-publishes the keyring. This is all a lock or backup-password change has to touch.
     * Skipped when the store belongs to another installation's key, which only a restore may
     * adopt.
     */
    public void republishKeyring() throws Exception {
        exclusive(null, () -> {
            resolveFolders();
            JSONObject existing = store.readPlain(VaultCrypto.KEYRING_FILE, rootId);
            String theirs = VaultCrypto.keyIdOf(existing);
            if (theirs != null && !theirs.equals(VaultCrypto.getKeyId(context))) {
                Log.w(TAG, "Drive holds another installation's keyring; not overwriting it");
                return null;
            }
            publishKeyring();
            return null;
        });
    }

    private void publishKeyring() throws Exception {
        JSONObject keyring = VaultCrypto.buildKeyring(context, db.vaultDao().getAllVaultsDirect());
        store.writePlain(VaultCrypto.KEYRING_FILE, rootId, keyring);
    }

    /** Unwraps the store's data key with a lock or backup password and adopts it locally. */
    private void adoptKeyFromKeyring(String password) throws Exception {
        JSONObject keyring = store.readPlain(VaultCrypto.KEYRING_FILE, rootId);
        if (keyring == null) return; // Pre-keyring store: payloads are legacy, handled by the resolver.

        String keyId = VaultCrypto.keyIdOf(keyring);
        if (keyId != null && keyId.equals(VaultCrypto.getKeyId(context))) return;

        SecretKey dataKey = VaultCrypto.unwrapDataKey(keyring, password);
        VaultCrypto.adoptDataKey(context, dataKey, keyId);
    }

    // -------------------------------------------------------------- backgrounds

    /** Wallpapers, including video ones. Uploaded once per file name. */
    private void uploadBackgrounds(SecretKey dataKey, String keyId) {
        try {
            String backgroundsId = store.folder(DriveStore.FOLDER_BACKGROUNDS, rootId);
            File[] dirs = context.getFilesDir().listFiles();
            if (dirs == null) return;
            for (File dir : dirs) {
                if (!dir.isDirectory() || !dir.getName().startsWith("bg_")) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                String remoteDirId = store.folder(dir.getName(), backgroundsId);
                for (File file : files) {
                    if (!file.isFile()) continue;
                    if (store.findFile(file.getName(), remoteDirId) != null) continue;
                    File staged = stagingFile("bg_" + file.getName());
                    try {
                        try (FileInputStream in = new FileInputStream(file);
                             FileOutputStream out = new FileOutputStream(staged)) {
                            VaultCrypto.encrypt(in, out, dataKey, keyId);
                        }
                        store.putFile(staged, file.getName(), "application/octet-stream", remoteDirId);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not upload wallpaper " + file.getName(), e);
                    } finally {
                        delete(staged);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Wallpaper backup skipped", e);
        }
    }

    // ------------------------------------------------------------------ baseline

    /** Absent on a device that has never synced; that is what triggers the choice screen. */
    private boolean hasBaseline() {
        return new File(context.getFilesDir(), BASELINE_FILE).exists();
    }

    private void writeBaseline(Set<String> fileNames) {
        try {
            JSONObject document = new JSONObject();
            document.put("savedAt", System.currentTimeMillis());
            document.put("files", new JSONArray(new ArrayList<>(fileNames)));
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), BASELINE_FILE))) {
                out.write(document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not save the sync baseline", e);
        }
    }

    public static void clearBaseline(Context context) {
        File baseline = new File(context.getFilesDir(), BASELINE_FILE);
        if (baseline.exists() && !baseline.delete()) Log.w(TAG, "Could not clear the sync baseline");
    }

    // ------------------------------------------------------------------- crypto

    /** Vault file to upload payload, hashing the plaintext on the way through. */
    private String transcodeForUpload(File vaultFile, File staged, SecretKey dataKey, String keyId)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(VaultCrypto.openRead(context, vaultFile), digest);
             FileOutputStream out = new FileOutputStream(staged)) {
            VaultCrypto.encrypt(in, out, dataKey, keyId);
        }
        return VaultCrypto.bytesToHex(digest.digest());
    }

    /**
     * Downloaded payload into a device-encrypted vault file. When the upload recorded a hash,
     * a mismatch aborts the write, so a corrupted object never becomes a vault file.
     */
    private void transcodeForStorage(File staged, File target, VaultCrypto.KeyResolver resolver,
                                     String expectedSha256) throws Exception {
        VaultCrypto.write(context, target, out -> {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(staged)) {
                VaultCrypto.decrypt(in, new DigestOutputStream(out, digest), resolver);
            }
            String actual = VaultCrypto.bytesToHex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.equals(actual)) {
                throw new java.security.GeneralSecurityException(
                        "Downloaded " + target.getName() + " does not match its recorded hash");
            }
        });
    }

    // ------------------------------------------------------------------ plumbing

    private ObjectIndex loadIndex() throws Exception {
        return ObjectIndex.load(store, rootId, objectsId, VaultCrypto.localResolver(context, null));
    }

    private File stagingFile(String name) throws IOException {
        File dir = new File(context.getCacheDir(), "cloudstage");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create staging area");
        File file = new File(dir, name);
        delete(file);
        return file;
    }

    private static void delete(File file) {
        if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
    }

    public static void clearStaging(Context context) {
        File[] files = new File(context.getCacheDir(), "cloudstage").listFiles();
        if (files == null) return;
        for (File file : files) delete(file);
    }

    private static void stage(Progress progress, String message) {
        if (progress != null) progress.onStage(message);
    }

    private static void item(Progress progress, int done, int total, long bytesDone, long bytesTotal) {
        if (progress != null) progress.onItem(done, total, bytesDone, bytesTotal);
    }
}
