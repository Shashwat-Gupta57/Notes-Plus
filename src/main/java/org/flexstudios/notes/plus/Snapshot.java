package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One restore point, the phone's Snapshot, field for field: the vaults (with their locks),
 * albums and items, referencing media by file name. It is the {@code snapshot.json} inside a
 * .bak, so a backup made on either device restores on the other.
 *
 * Every field is read leniently: one malformed item is skipped, never fatal.
 */
final class Snapshot {

    static final int FORMAT_VERSION = 3;
    /** How long a deletion is remembered: longer than restore points are kept (30 days). */
    static final long REMOVED_KEEP_MS = 60L * 24 * 60 * 60 * 1000;

    String id;
    long createdAt;
    String device, installId;
    final List<VaultRecord> vaults = new ArrayList<>();
    final List<AlbumRecord> albums = new ArrayList<>();
    final List<MediaRecord> media = new ArrayList<>();
    JSONObject lockSettings = new JSONObject();
    /**
     * What was deleted, and when: item file names, and "album:" / "vault:" + uuid. Merging never
     * brings these back, and a device that still has one deletes it at its next pull.
     */
    final Map<String, Long> removed = new HashMap<>();

    static final class VaultRecord {
        String uuid, name, lockType, lockValue, bgType, bgValue;
        boolean isMain;
        int sortOrder;
        /** When this lock was set (0: before 19.9); with lock sync on, the newest wins. */
        long lockAt;
    }

    static final class AlbumRecord {
        String uuid, name, vaultUuid, coverFileName;
    }

    static final class MediaRecord {
        String fileName, vaultUuid, albumUuid, originalPath, sha256;
        boolean isVideo, isFavourite;
        long dateTaken, dateAdded;
    }

    static String newId() {
        String stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(new Date());
        return stamp + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    static String installId() {
        String id = Prefs.get("install_id");
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            Prefs.put("install_id", id);
        }
        return id;
    }

    /**
     * Add-only: takes in everything {@code older} describes that this one doesn't, as long as
     * its bytes are stored. A backup from a computer holding fewer items then never shrinks the
     * backup made by the others. The older one's main vault is this one's main vault.
     */
    void mergeFrom(Snapshot older, java.util.Set<String> stored) {
        java.util.Set<String> vaultIds = new java.util.HashSet<>(), albumIds = new java.util.HashSet<>(),
                files = new java.util.HashSet<>();
        String main = null;
        for (VaultRecord v : vaults) {
            vaultIds.add(v.uuid);
            if (v.isMain) main = v.uuid;
        }
        Map<String, String> sameVault = new HashMap<>();
        for (VaultRecord v : older.vaults) {
            if (v.uuid == null || vaultIds.contains(v.uuid)) continue;
            if (v.isMain && main != null) {
                sameVault.put(v.uuid, main);
                continue;
            }
            vaults.add(v);
            vaultIds.add(v.uuid);
            if (v.isMain) main = v.uuid;
        }
        for (AlbumRecord a : albums) albumIds.add(a.uuid);
        for (AlbumRecord a : older.albums) {
            if (a.uuid == null || albumIds.contains(a.uuid)) continue;
            if (sameVault.containsKey(a.vaultUuid)) a.vaultUuid = sameVault.get(a.vaultUuid);
            albums.add(a);
            albumIds.add(a.uuid);
        }
        for (MediaRecord m : media) files.add(m.fileName);
        for (MediaRecord m : older.media) {
            if (m.fileName == null || files.contains(m.fileName) || (stored != null && !stored.contains(m.fileName))) continue;
            if (sameVault.containsKey(m.vaultUuid)) m.vaultUuid = sameVault.get(m.vaultUuid);
            media.add(m);
            files.add(m.fileName);
        }
    }

    /**
     * Makes this (just captured) restore point the next one after {@code previous}: its
     * deletions plus this device's {@code marks}, less what this device {@code revived}; then
     * everything else it holds ({@link #mergeFrom}); then the locks: the backup's stay unless
     * {@code syncLocks} is on and this device's are newer.
     */
    void settle(Snapshot previous, java.util.Set<String> stored, Map<String, Long> marks,
                java.util.Set<String> revived, boolean syncLocks, long now) {
        if (previous != null) previous.removed.forEach((k, at) -> removed.merge(k, at, Math::max));
        marks.forEach((k, at) -> removed.merge(k, at, Math::max));
        for (String k : revived) removed.remove(k);
        removed.values().removeIf(at -> at < now - REMOVED_KEEP_MS);
        if (previous != null) {
            Map<String, VaultRecord> before = new HashMap<>();
            for (VaultRecord v : previous.vaults) if (v.uuid != null) before.put(v.uuid, v);
            for (VaultRecord v : vaults) {
                VaultRecord p = before.get(v.uuid);
                if (p == null || p.lockType == null || p.lockValue == null) continue;
                if (!syncLocks || p.lockAt > v.lockAt) {
                    v.lockType = p.lockType;
                    v.lockValue = p.lockValue;
                    v.lockAt = p.lockAt;
                }
            }
            mergeFrom(previous, stored);
        }
        media.removeIf(m -> removed.containsKey(m.fileName));
        albums.removeIf(a -> removed.containsKey("album:" + a.uuid));
        vaults.removeIf(v -> !v.isMain && removed.containsKey("vault:" + v.uuid));
    }

    // ================================================================ capture

    /** The vaults as they stand now. The vault must be open (lock values are needed). */
    static Snapshot capture(VaultStore s) throws Exception {
        Snapshot snap = new Snapshot();
        snap.id = newId();
        snap.createdAt = System.currentTimeMillis();
        snap.device = NoteSync.device() + " (Notes+ desktop)";
        snap.installId = installId();
        Map<Integer, String> vaultUuid = new HashMap<>();
        for (VaultStore.Vault v : s.vaults()) {
            VaultRecord r = new VaultRecord();
            r.uuid = v.uuid;
            r.name = v.name;
            r.lockType = v.lockType;
            r.lockValue = VaultCrypto.lockValue(v.uuid);
            r.isMain = v.main;
            r.sortOrder = v.sortOrder;
            r.lockAt = v.lockChangedAt;
            r.bgType = v.bgType;
            r.bgValue = v.bgValue;
            snap.vaults.add(r);
            vaultUuid.put(v.id, v.uuid);
        }
        Map<Integer, String> albumUuid = new HashMap<>();
        for (VaultStore.Album a : s.allAlbums()) {
            AlbumRecord r = new AlbumRecord();
            r.uuid = a.uuid;
            r.name = a.name;
            r.vaultUuid = vaultUuid.get(a.vaultId);
            snap.albums.add(r);
            albumUuid.put(a.id, a.uuid);
        }
        for (VaultStore.Secret x : s.allSecrets()) {
            MediaRecord r = new MediaRecord();
            r.fileName = x.fileName;
            r.vaultUuid = vaultUuid.get(x.vaultId);
            r.albumUuid = x.albumId == null ? null : albumUuid.get(x.albumId);
            r.isVideo = x.video;
            r.isFavourite = x.favourite;
            r.dateTaken = x.dateTaken;
            r.dateAdded = x.dateAdded;
            r.originalPath = x.originalPath;
            snap.media.add(r);
        }
        String bgType = Prefs.get(Vault.KEY_LOCK_BG_TYPE);
        snap.lockSettings.put("auto_lock_time", Vault.autoLockDelay());
        snap.lockSettings.put("lock_bg_type", bgType == null ? "DEFAULT" : bgType);
        snap.lockSettings.put("lock_bg_value", orNull(Prefs.get(Vault.KEY_LOCK_BG_VALUE)));
        snap.lockSettings.put("lock_bg_blur", "true".equals(Prefs.get(Vault.KEY_LOCK_BG_BLUR)));
        snap.lockSettings.put("lock_bg_dim", "true".equals(Prefs.get(Vault.KEY_LOCK_BG_DIM)));
        return snap;
    }

    // ================================================================== apply

    /**
     * Writes the restore point's vaults, albums and rows into the database, after the media is
     * in place (only items whose file is present get a row). Nothing local is deleted;
     * {@code replaceLocal} lets the backup's names, locks and wallpapers win. Every restored
     * vault's lock is hashed and gets its wrap of the data key, so it opens with the lock it had.
     */
    void apply(VaultStore s, boolean replaceLocal) throws Exception {
        apply(s, replaceLocal, false, java.util.Set.of());
    }

    /**
     * As {@link #apply(VaultStore, boolean)}, and then: with {@code syncLocks}, a vault whose lock
     * here is older than the backup's takes the backup's (unless another vault here has it);
     * items, albums and empty vaults the backup says were deleted go from here too, except what
     * this device has {@code revived} since.
     */
    void apply(VaultStore s, boolean replaceLocal, boolean syncLocks, java.util.Set<String> revived) throws Exception {
        Map<String, Integer> vaultId = new HashMap<>();
        for (VaultRecord r : vaults) {
            if (r.uuid == null) continue;
            VaultStore.Vault existing = s.vaultByUuid(r.uuid);
            if (existing == null && r.isMain) {
                // This computer already has a main vault: the backup's main items go into it.
                existing = s.mainVault();
            }
            if (existing == null) {
                if (r.lockValue == null || r.lockType == null) continue; // No way to open it.
                VaultStore.Vault v = new VaultStore.Vault();
                v.uuid = r.uuid;
                v.name = r.name == null ? "Vault" : r.name;
                v.lockType = r.lockType;
                v.lockHash = VaultCrypto.hashLock(r.lockType, r.lockValue);
                v.lockChangedAt = r.lockAt;
                v.sortOrder = r.sortOrder;
                v.bgType = r.bgType == null ? "DEFAULT" : r.bgType;
                v.bgValue = remap(r.bgValue);
                s.insertVault(v);
                VaultCrypto.wrapFor(v.uuid, v.lockType, r.lockValue);
                vaultId.put(r.uuid, v.id);
            } else {
                if (replaceLocal) {
                    if (r.name != null) existing.name = r.name;
                    existing.sortOrder = r.sortOrder;
                    existing.bgType = r.bgType == null ? "DEFAULT" : r.bgType;
                    existing.bgValue = remap(r.bgValue);
                    if (r.lockValue != null && r.lockType != null) {
                        existing.lockType = r.lockType;
                        existing.lockHash = VaultCrypto.hashLock(r.lockType, r.lockValue);
                        existing.lockChangedAt = r.lockAt;
                        VaultCrypto.wrapFor(existing.uuid, r.lockType, r.lockValue);
                    }
                    s.updateVault(existing);
                } else if (syncLocks && r.lockValue != null && r.lockType != null && r.lockAt > existing.lockChangedAt
                        && !lockTaken(s, existing.id, r.lockType, r.lockValue)) {
                    existing.lockType = r.lockType;
                    existing.lockHash = VaultCrypto.hashLock(r.lockType, r.lockValue);
                    existing.lockChangedAt = r.lockAt;
                    VaultCrypto.wrapFor(existing.uuid, r.lockType, r.lockValue);
                    s.updateVault(existing);
                }
                vaultId.put(r.uuid, existing.id);
            }
        }
        Map<String, Integer> albumId = new HashMap<>();
        Map<String, Integer> known = new HashMap<>();
        for (VaultStore.Album a : s.allAlbums()) known.put(a.uuid, a.id);
        for (AlbumRecord r : albums) {
            if (r.uuid == null) continue;
            Integer v = vaultId.get(r.vaultUuid);
            if (v == null) continue;
            Integer existing = known.get(r.uuid);
            albumId.put(r.uuid, existing != null ? existing : s.insertAlbum(r.name == null ? "Album" : r.name, v, r.uuid).id);
        }
        VaultStore.Vault main = s.mainVault();
        int fallback = main == null ? 1 : main.id;
        for (MediaRecord r : media) {
            if (!Files.exists(VaultCrypto.mediaDir().resolve(r.fileName)) || s.secret(r.fileName) != null) continue;
            VaultStore.Secret x = new VaultStore.Secret();
            x.fileName = r.fileName;
            x.originalPath = r.originalPath;
            x.dateTaken = r.dateTaken;
            x.dateAdded = r.dateAdded;
            x.video = r.isVideo;
            x.favourite = r.isFavourite;
            Integer v = vaultId.get(r.vaultUuid);
            x.vaultId = v == null ? fallback : v;
            x.albumId = r.albumUuid == null ? null : albumId.get(r.albumUuid);
            s.insertSecret(x);
        }
        // Deleted on another device: gone here too (without a mark of its own).
        for (VaultStore.Secret x : s.allSecrets()) {
            if (removed.containsKey(x.fileName) && !revived.contains(x.fileName)) VaultMedia.erase(x);
        }
        for (VaultStore.Album a : s.allAlbums()) {
            String k = "album:" + a.uuid;
            if (removed.containsKey(k) && !revived.contains(k)) s.deleteAlbum(a.id);
        }
        for (VaultStore.Vault v : s.vaults()) {
            String k = "vault:" + v.uuid;
            if (!v.main && removed.containsKey(k) && !revived.contains(k) && s.countFor(v.id) == 0) {
                s.deleteVault(v.id);
                VaultCrypto.forget(v.uuid);
            }
        }
        if (replaceLocal) {
            Vault.setAutoLockDelay(lockSettings.optLong("auto_lock_time", 0));
            Prefs.put(Vault.KEY_LOCK_BG_TYPE, lockSettings.optString("lock_bg_type", "DEFAULT"));
            Prefs.put(Vault.KEY_LOCK_BG_VALUE, lockSettings.isNull("lock_bg_value") ? null : remap(lockSettings.optString("lock_bg_value", null)));
            Prefs.put(Vault.KEY_LOCK_BG_BLUR, String.valueOf(lockSettings.optBoolean("lock_bg_blur")));
            Prefs.put(Vault.KEY_LOCK_BG_DIM, String.valueOf(lockSettings.optBoolean("lock_bg_dim")));
        }
    }

    /** Another vault here already opens with this lock (two vaults never share one). */
    private static boolean lockTaken(VaultStore s, int except, String type, String value) {
        for (VaultStore.Vault v : s.vaults()) {
            if (v.id != except && v.lockHash != null && type.equals(v.lockType) && VaultCrypto.matches(v.lockHash, type, value)) return true;
        }
        return false;
    }

    /**
     * Wallpaper paths are absolute on the device that made them (".../files/bg_vault_3/x.jpg"
     * on the phone, "...\vault\bg_vault_3\x.jpg" here): re-rooted under this vault's folder.
     */
    static String remap(String stored) {
        if (stored == null) return null;
        String path = stored.replace('\\', '/');
        int at = path.indexOf("/bg_");
        if (at < 0) return stored;
        return VaultCrypto.dir().resolve(path.substring(at + 1)).toString();
    }

    // ================================================================== codec

    JSONObject toJson() {
        JSONObject d = new JSONObject();
        d.put("formatVersion", FORMAT_VERSION);
        d.put("id", id);
        d.put("createdAt", createdAt);
        d.put("device", orNull(device));
        d.put("installId", orNull(installId));
        d.put("lockSettings", lockSettings);
        JSONArray va = new JSONArray();
        for (VaultRecord r : vaults) {
            va.put(new JSONObject().put("uuid", orNull(r.uuid)).put("name", orNull(r.name))
                    .put("lockType", orNull(r.lockType)).put("lockValue", orNull(r.lockValue))
                    .put("isMain", r.isMain).put("sortOrder", r.sortOrder)
                    .put("bgType", orNull(r.bgType)).put("bgValue", orNull(r.bgValue)).put("lockAt", r.lockAt));
        }
        d.put("vaults", va);
        JSONArray aa = new JSONArray();
        for (AlbumRecord r : albums) {
            aa.put(new JSONObject().put("uuid", orNull(r.uuid)).put("name", orNull(r.name))
                    .put("vaultUuid", orNull(r.vaultUuid)).put("coverFileName", orNull(r.coverFileName)));
        }
        d.put("albums", aa);
        JSONArray ma = new JSONArray();
        for (MediaRecord r : media) {
            ma.put(new JSONObject().put("fileName", orNull(r.fileName)).put("vaultUuid", orNull(r.vaultUuid))
                    .put("albumUuid", orNull(r.albumUuid)).put("isVideo", r.isVideo).put("isFavourite", r.isFavourite)
                    .put("dateTaken", r.dateTaken).put("dateAdded", r.dateAdded)
                    .put("originalPath", orNull(r.originalPath)).put("sha256", orNull(r.sha256)));
        }
        d.put("media", ma);
        JSONObject rm = new JSONObject();
        removed.forEach(rm::put);
        d.put("removed", rm);
        return d;
    }

    static Snapshot fromJson(JSONObject d) {
        Snapshot s = new Snapshot();
        s.id = d.optString("id", newId());
        s.createdAt = d.optLong("createdAt", System.currentTimeMillis());
        s.device = str(d, "device");
        s.installId = str(d, "installId");
        s.lockSettings = d.optJSONObject("lockSettings") == null ? new JSONObject() : d.optJSONObject("lockSettings");
        JSONArray va = d.optJSONArray("vaults");
        for (int i = 0; va != null && i < va.length(); i++) {
            JSONObject o = va.optJSONObject(i);
            if (o == null) continue;
            VaultRecord r = new VaultRecord();
            r.uuid = str(o, "uuid");
            r.name = str(o, "name");
            r.lockType = str(o, "lockType");
            r.lockValue = str(o, "lockValue");
            r.isMain = o.optBoolean("isMain");
            r.sortOrder = o.optInt("sortOrder", i);
            r.bgType = str(o, "bgType");
            r.bgValue = str(o, "bgValue");
            r.lockAt = o.optLong("lockAt");
            s.vaults.add(r);
        }
        JSONArray aa = d.optJSONArray("albums");
        for (int i = 0; aa != null && i < aa.length(); i++) {
            JSONObject o = aa.optJSONObject(i);
            if (o == null) continue;
            AlbumRecord r = new AlbumRecord();
            r.uuid = str(o, "uuid");
            r.name = str(o, "name");
            r.vaultUuid = str(o, "vaultUuid");
            r.coverFileName = str(o, "coverFileName");
            s.albums.add(r);
        }
        JSONArray ma = d.optJSONArray("media");
        for (int i = 0; ma != null && i < ma.length(); i++) {
            JSONObject o = ma.optJSONObject(i);
            if (o == null || !safe(str(o, "fileName"))) continue;
            MediaRecord r = new MediaRecord();
            r.fileName = str(o, "fileName");
            r.vaultUuid = str(o, "vaultUuid");
            r.albumUuid = str(o, "albumUuid");
            r.isVideo = o.optBoolean("isVideo");
            r.isFavourite = o.optBoolean("isFavourite");
            r.dateTaken = o.optLong("dateTaken");
            r.dateAdded = o.optLong("dateAdded");
            r.originalPath = str(o, "originalPath");
            r.sha256 = str(o, "sha256");
            s.media.add(r);
        }
        JSONObject rm = d.optJSONObject("removed");
        if (rm != null) for (String k : rm.keySet()) if (rm.optLong(k) > 0) s.removed.put(k, rm.optLong(k));
        return s;
    }

    /** A name from a backup only ever names a file inside the vault folder. */
    static boolean safe(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\")
                && !name.contains("..") && !name.contains(":") && name.length() <= 200;
    }

    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String v = o.optString(k, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Object orNull(String v) {
        return v == null ? JSONObject.NULL : v;
    }
}
