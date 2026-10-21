package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One restore point: the complete description of a vault at a moment in time, referencing
 * media by object name rather than containing any bytes.
 *
 * A few thousand items is roughly a megabyte of JSON, so keeping ten restore points costs
 * about ten megabytes on top of the shared object store. That is what makes "multiple
 * backups" affordable, and it is what turns an accidental delete into something recoverable
 * rather than permanent.
 *
 * The same document is the manifest of a Drive restore point and the {@code snapshot.json}
 * inside a local .bak, and {@link #apply} is the one place either is written back into the
 * database.
 *
 * Every field is read through {@code isNull}/{@code optX}. The old manifest code used
 * {@code getString}, and because {@code JSONObject.put} silently drops a null value, a single
 * item with no resolvable {@code originalPath} threw and rolled back the entire restore.
 */
public class Snapshot {

    private static final String TAG = "Snapshot";
    public static final int FORMAT_VERSION = 2;

    private static final String PREF_INSTALL_ID = "install_id";

    public String id;
    public long createdAt;
    /** Human label, e.g. "Google Pixel 8". Display only: two phones can share it. */
    public String device;
    /** Stable per installation; what retention groups restore points by. */
    public String installId;
    public final List<VaultRecord> vaults = new ArrayList<>();
    public final List<AlbumRecord> albums = new ArrayList<>();
    public final List<MediaRecord> media = new ArrayList<>();
    public JSONObject lockSettings = new JSONObject();

    public static class VaultRecord {
        public String uuid, name, lockType, lockValue, bgType, bgValue;
        public boolean isMain;
        public int sortOrder;
    }

    public static class AlbumRecord {
        public String uuid, name, vaultUuid, coverFileName;
    }

    public static class MediaRecord {
        public String fileName, vaultUuid, albumUuid, originalPath, sha256;
        public boolean isVideo, isFavourite;
        public long dateTaken, dateAdded;
    }

    /** Lightweight row for the restore-point list, so the UI never parses a full manifest. */
    public static class Summary {
        public String id;
        public long createdAt;
        public String device;
        public String installId;
        public int itemCount;
        public long totalBytes;
        public boolean pinned;

        public String displayName() {
            return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                    .format(new Date(createdAt));
        }
    }

    public static String newId() {
        String stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(new Date());
        return stamp + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    public String fileName() {
        return id + ".json";
    }

    /** Identity of this installation, created once. A reinstall is a new installation. */
    public static String installId(Context context) {
        SharedPreferences prefs = SecurityHelper.getAppPrefs(context);
        String id = prefs.getString(PREF_INSTALL_ID, null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(PREF_INSTALL_ID, id).apply();
        }
        return id;
    }

    public Set<String> mediaFileNames() {
        Set<String> names = new HashSet<>();
        for (MediaRecord record : media) names.add(record.fileName);
        return names;
    }

    // ------------------------------------------------------------------ capture

    /**
     * Builds a snapshot describing the database as it stands right now.
     *
     * @param stored when non-null, only media whose bytes are in this set is listed. A restore
     *               point must never name an object that failed to upload; it would look
     *               complete and silently restore less.
     */
    public static Snapshot capture(Context context, AppDatabase db, Set<String> stored) {
        Snapshot snapshot = new Snapshot();
        snapshot.id = newId();
        snapshot.createdAt = System.currentTimeMillis();
        snapshot.device = Build.MANUFACTURER + " " + Build.MODEL;
        snapshot.installId = installId(context);

        for (VaultEntity vault : db.vaultDao().getAllVaultsDirect()) {
            VaultRecord record = new VaultRecord();
            record.uuid = vault.getUuid();
            record.name = vault.getName();
            record.lockType = vault.getLockType();
            record.lockValue = vault.getLockValue();
            record.isMain = vault.isMain();
            record.sortOrder = vault.getSortOrder();
            record.bgType = vault.getBgType();
            record.bgValue = vault.getBgValue();
            snapshot.vaults.add(record);
        }

        java.util.Map<Integer, String> vaultUuidById = new java.util.HashMap<>();
        for (VaultEntity vault : db.vaultDao().getAllVaultsDirect()) {
            vaultUuidById.put(vault.getId(), vault.getUuid());
        }
        java.util.Map<Integer, String> albumUuidById = new java.util.HashMap<>();
        for (AlbumEntity album : db.albumDao().getAllAlbumsDirect()) {
            albumUuidById.put(album.getId(), album.getUuid());
            AlbumRecord record = new AlbumRecord();
            record.uuid = album.getUuid();
            record.name = album.getName();
            record.vaultUuid = vaultUuidById.get(album.getVaultId());
            record.coverFileName = album.getCoverFileName();
            snapshot.albums.add(record);
        }

        for (SecretEntity secret : db.secretDao().getAllSecretsDirect()) {
            if (secret.getFileName() == null) continue;
            if (stored != null && !stored.contains(secret.getFileName())) continue;
            MediaRecord record = new MediaRecord();
            record.fileName = secret.getFileName();
            record.vaultUuid = vaultUuidById.get(secret.getVaultId());
            record.albumUuid = secret.getAlbumId() == null
                    ? null : albumUuidById.get(secret.getAlbumId());
            record.isVideo = secret.isVideo();
            record.isFavourite = secret.isFavourite();
            record.dateTaken = secret.getDateTaken();
            record.dateAdded = secret.getDateAdded();
            record.originalPath = secret.getOriginalPath();
            record.sha256 = secret.getContentHash();
            snapshot.media.add(record);
        }

        SharedPreferences prefs = SecurityHelper.getAppPrefs(context);
        try {
            snapshot.lockSettings.put("auto_lock_time", SecurityHelper.getAutoLockDelay(context));
            snapshot.lockSettings.put("lock_bg_type",
                    prefs.getString(SecurityHelper.KEY_LOCK_BG_TYPE, "DEFAULT"));
            snapshot.lockSettings.put("lock_bg_value",
                    orNull(prefs.getString(SecurityHelper.KEY_LOCK_BG_VALUE, null)));
            snapshot.lockSettings.put("lock_bg_blur",
                    prefs.getBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, false));
            snapshot.lockSettings.put("lock_bg_dim",
                    prefs.getBoolean(SecurityHelper.KEY_LOCK_BG_DIM, false));
        } catch (Exception e) {
            Log.w(TAG, "Could not capture lock settings", e);
        }
        return snapshot;
    }

    // -------------------------------------------------------------------- apply

    /**
     * Writes this restore point's vaults, albums and media rows into the database.
     *
     * Only rows whose vault file is present are added, so call it after the media is in
     * place. Nothing local is ever deleted; {@code replaceLocal} only lets the backup's vault
     * names, locks and backgrounds overwrite the ones here.
     */
    public void apply(Context context, AppDatabase db, boolean replaceLocal) {
        File vaultDir = VaultMedia.vaultDir(context);
        db.runInTransaction(() -> {
            Map<String, Integer> vaultIdByUuid = new HashMap<>();
            for (VaultRecord record : vaults) {
                if (record.uuid == null) continue;
                VaultEntity existing = db.vaultDao().getVaultByUuid(record.uuid);
                if (existing == null && record.isMain) {
                    // A fresh install already has a main vault; adopt it rather than adding a
                    // second "main" that the unlock screen would never reach.
                    existing = db.vaultDao().getMainVault();
                    if (existing != null) {
                        existing.setUuid(record.uuid);
                        db.vaultDao().update(existing);
                    }
                }
                if (existing == null) {
                    VaultEntity vault = new VaultEntity(record.name, record.lockType,
                            record.lockValue, record.isMain, record.sortOrder);
                    vault.setUuid(record.uuid);
                    vault.setBgType(record.bgType == null ? "DEFAULT" : record.bgType);
                    vault.setBgValue(remapBackgroundPath(context, record.bgValue));
                    vaultIdByUuid.put(record.uuid, (int) db.vaultDao().insert(vault));
                } else {
                    if (replaceLocal) {
                        existing.setName(record.name);
                        existing.setLockType(record.lockType);
                        existing.setLockValue(record.lockValue);
                        existing.setSortOrder(record.sortOrder);
                        existing.setBgType(record.bgType == null ? "DEFAULT" : record.bgType);
                        existing.setBgValue(remapBackgroundPath(context, record.bgValue));
                        db.vaultDao().update(existing);
                    }
                    vaultIdByUuid.put(record.uuid, existing.getId());
                }
            }

            Map<String, Integer> albumIdByUuid = new HashMap<>();
            for (AlbumRecord record : albums) {
                if (record.uuid == null) continue;
                Integer vaultId = vaultIdByUuid.get(record.vaultUuid);
                if (vaultId == null) continue;
                AlbumEntity existing = db.albumDao().getAlbumByUuid(record.uuid);
                if (existing == null) {
                    AlbumEntity album = new AlbumEntity(record.name, vaultId, record.coverFileName);
                    album.setUuid(record.uuid);
                    albumIdByUuid.put(record.uuid, (int) db.albumDao().insert(album));
                } else {
                    albumIdByUuid.put(record.uuid, existing.getId());
                }
            }

            VaultEntity main = db.vaultDao().getMainVault();
            int fallbackVault = main == null ? 1 : main.getId();
            for (MediaRecord record : media) {
                if (!new File(vaultDir, record.fileName).exists()) continue;
                if (db.secretDao().getSecretByFileName(record.fileName) != null) continue;
                Integer vaultId = vaultIdByUuid.get(record.vaultUuid);
                SecretEntity secret = new SecretEntity(record.fileName, record.originalPath,
                        record.dateTaken, record.dateAdded, record.isVideo,
                        vaultId == null ? fallbackVault : vaultId);
                secret.setFavourite(record.isFavourite);
                secret.setContentHash(record.sha256);
                secret.setAlbumId(record.albumUuid == null ? null : albumIdByUuid.get(record.albumUuid));
                db.secretDao().insert(secret);
            }
        });

        try {
            JSONObject settings = lockSettings;
            SecurityHelper.setAutoLockDelay(context, settings.optLong("auto_lock_time", 0));
            SecurityHelper.getAppPrefs(context).edit()
                    .putString(SecurityHelper.KEY_LOCK_BG_TYPE, settings.optString("lock_bg_type", "DEFAULT"))
                    .putString(SecurityHelper.KEY_LOCK_BG_VALUE, remapBackgroundPath(context,
                            settings.isNull("lock_bg_value") ? null : settings.optString("lock_bg_value", null)))
                    .putBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, settings.optBoolean("lock_bg_blur", false))
                    .putBoolean(SecurityHelper.KEY_LOCK_BG_DIM, settings.optBoolean("lock_bg_dim", false))
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not apply lock settings from the restore point", e);
        }
    }

    /** Background paths are absolute; re-root them under this install's files dir. */
    static String remapBackgroundPath(Context context, String storedPath) {
        if (storedPath == null) return null;
        int marker = storedPath.indexOf("/files/bg_");
        if (marker < 0) return storedPath;
        String relative = storedPath.substring(marker + "/files/".length());
        return new File(context.getFilesDir(), relative).getAbsolutePath();
    }

    // -------------------------------------------------------------------- codec

    public JSONObject toJson() throws Exception {
        JSONObject document = new JSONObject();
        document.put("formatVersion", FORMAT_VERSION);
        document.put("id", id);
        document.put("createdAt", createdAt);
        document.put("device", orNull(device));
        document.put("installId", orNull(installId));
        document.put("lockSettings", lockSettings);

        JSONArray vaultArray = new JSONArray();
        for (VaultRecord record : vaults) {
            JSONObject item = new JSONObject();
            item.put("uuid", orNull(record.uuid));
            item.put("name", orNull(record.name));
            item.put("lockType", orNull(record.lockType));
            item.put("lockValue", orNull(record.lockValue));
            item.put("isMain", record.isMain);
            item.put("sortOrder", record.sortOrder);
            item.put("bgType", orNull(record.bgType));
            item.put("bgValue", orNull(record.bgValue));
            vaultArray.put(item);
        }
        document.put("vaults", vaultArray);

        JSONArray albumArray = new JSONArray();
        for (AlbumRecord record : albums) {
            JSONObject item = new JSONObject();
            item.put("uuid", orNull(record.uuid));
            item.put("name", orNull(record.name));
            item.put("vaultUuid", orNull(record.vaultUuid));
            item.put("coverFileName", orNull(record.coverFileName));
            albumArray.put(item);
        }
        document.put("albums", albumArray);

        JSONArray mediaArray = new JSONArray();
        for (MediaRecord record : media) {
            JSONObject item = new JSONObject();
            item.put("fileName", orNull(record.fileName));
            item.put("vaultUuid", orNull(record.vaultUuid));
            item.put("albumUuid", orNull(record.albumUuid));
            item.put("isVideo", record.isVideo);
            item.put("isFavourite", record.isFavourite);
            item.put("dateTaken", record.dateTaken);
            item.put("dateAdded", record.dateAdded);
            item.put("originalPath", orNull(record.originalPath));
            item.put("sha256", orNull(record.sha256));
            mediaArray.put(item);
        }
        document.put("media", mediaArray);
        return document;
    }

    public static Snapshot fromJson(JSONObject document) {
        Snapshot snapshot = new Snapshot();
        snapshot.id = document.optString("id", newId());
        snapshot.createdAt = document.optLong("createdAt", System.currentTimeMillis());
        snapshot.device = str(document, "device");
        snapshot.installId = str(document, "installId");
        snapshot.lockSettings = document.optJSONObject("lockSettings");
        if (snapshot.lockSettings == null) snapshot.lockSettings = new JSONObject();

        JSONArray vaultArray = document.optJSONArray("vaults");
        if (vaultArray != null) {
            for (int i = 0; i < vaultArray.length(); i++) {
                JSONObject item = vaultArray.optJSONObject(i);
                if (item == null) continue;
                VaultRecord record = new VaultRecord();
                record.uuid = str(item, "uuid");
                record.name = str(item, "name");
                record.lockType = str(item, "lockType");
                record.lockValue = str(item, "lockValue");
                record.isMain = item.optBoolean("isMain", false);
                record.sortOrder = item.optInt("sortOrder", i);
                record.bgType = str(item, "bgType");
                record.bgValue = str(item, "bgValue");
                snapshot.vaults.add(record);
            }
        }

        JSONArray albumArray = document.optJSONArray("albums");
        if (albumArray != null) {
            for (int i = 0; i < albumArray.length(); i++) {
                JSONObject item = albumArray.optJSONObject(i);
                if (item == null) continue;
                AlbumRecord record = new AlbumRecord();
                record.uuid = str(item, "uuid");
                record.name = str(item, "name");
                record.vaultUuid = str(item, "vaultUuid");
                record.coverFileName = str(item, "coverFileName");
                snapshot.albums.add(record);
            }
        }

        JSONArray mediaArray = document.optJSONArray("media");
        if (mediaArray != null) {
            int skipped = 0;
            for (int i = 0; i < mediaArray.length(); i++) {
                JSONObject item = mediaArray.optJSONObject(i);
                // One malformed row is skipped and counted. It must never abort the restore.
                if (item == null || str(item, "fileName") == null) {
                    skipped++;
                    continue;
                }
                MediaRecord record = new MediaRecord();
                record.fileName = str(item, "fileName");
                record.vaultUuid = str(item, "vaultUuid");
                record.albumUuid = str(item, "albumUuid");
                record.isVideo = item.optBoolean("isVideo", false);
                record.isFavourite = item.optBoolean("isFavourite", false);
                record.dateTaken = item.optLong("dateTaken", 0);
                record.dateAdded = item.optLong("dateAdded", 0);
                record.originalPath = str(item, "originalPath");
                record.sha256 = str(item, "sha256");
                snapshot.media.add(record);
            }
            if (skipped > 0) Log.w(TAG, "Skipped " + skipped + " unreadable manifest entries");
        }
        return snapshot;
    }

    /** {@code null} rather than the literal string "null", which optString would return. */
    private static String str(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return null;
        String value = object.optString(key, null);
        return (value == null || value.isEmpty()) ? null : value;
    }

    /** JSONObject.put drops a Java null outright; JSONObject.NULL round-trips. */
    private static Object orNull(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
