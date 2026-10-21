package org.flexstudios.notes.plus;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory of everything already stored in the cloud object folder.
 *
 * This is what makes a backup cost only the new data. Vault media is write-once — a
 * {@code fileName} is assigned at import, never reused, and the bytes behind it never change —
 * so a name present in this index is a name that does not need uploading again. A run
 * downloads the index (one API call), diffs it against the database in memory (no calls), and
 * uploads only the difference. With 5 GB stored and 300 MB new, 300 MB moves.
 *
 * Without it, the same decision costs one {@code files.list} per item, which is what the old
 * code did inside its upload and verification loops.
 */
public class ObjectIndex {

    private static final String TAG = "ObjectIndex";
    public static final String FILE_NAME = "objects_index.json";
    private static final int FORMAT_VERSION = 2;

    public static class Entry {
        public final String name;
        public final String driveId;
        public final long size;
        /** SHA-256 of the plaintext, or null for objects inherited from an older backup. */
        public final String sha256;
        /** When it was uploaded; 0 when unknown. Fresh objects are exempt from cleanup. */
        public final long uploadedAt;

        public Entry(String name, String driveId, long size, String sha256, long uploadedAt) {
            this.name = name;
            this.driveId = driveId;
            this.size = size;
            this.sha256 = sha256;
            this.uploadedAt = uploadedAt;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long generation;

    // ------------------------------------------------------------------- access

    public boolean contains(String name) {
        return entries.containsKey(name);
    }

    public Entry get(String name) {
        return entries.get(name);
    }

    public void put(Entry entry) {
        entries.put(entry.name, entry);
    }

    public void remove(String name) {
        entries.remove(name);
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public Set<String> names() {
        return entries.keySet();
    }

    public int size() {
        return entries.size();
    }

    public long totalBytes() {
        long total = 0;
        for (Entry entry : entries.values()) total += entry.size;
        return total;
    }

    /** Names present locally but not yet stored, in the order given. */
    public List<String> missingFrom(Collection<String> localNames) {
        List<String> missing = new ArrayList<>();
        for (String name : localNames) {
            if (!entries.containsKey(name)) missing.add(name);
        }
        return missing;
    }

    // --------------------------------------------------------------- load / save

    /**
     * Loads the index, rebuilding it from a folder listing when it is absent or unreadable.
     *
     * A network failure is not "absent": it is rethrown, because rebuilding then would lose
     * every content hash and make the next run re-upload the whole vault.
     *
     * The rebuild path is what lets an existing 7.50 backup be adopted without re-uploading:
     * whatever is already sitting in the objects folder becomes the starting inventory.
     */
    public static ObjectIndex load(DriveStore store, String rootId, String objectsFolderId,
                                   VaultCrypto.KeyResolver resolver) throws Exception {
        try {
            JSONObject document = store.readEncrypted(FILE_NAME, rootId, resolver);
            if (document != null) return fromJson(document);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Object index unreadable; rebuilding from the folder listing", e);
        }
        return rebuild(store, objectsFolderId);
    }

    /** Enumerates the objects folder. One paged listing, used only when the index is missing. */
    public static ObjectIndex rebuild(DriveStore store, String objectsFolderId) throws Exception {
        ObjectIndex index = new ObjectIndex();
        for (com.google.api.services.drive.model.File file : store.listChildren(objectsFolderId)) {
            long size = file.getSize() == null ? 0L : file.getSize();
            index.put(new Entry(file.getName(), file.getId(), size, null, 0));
        }
        Log.i(TAG, "Rebuilt object index with " + index.size() + " objects");
        return index;
    }

    public void save(DriveStore store, String rootId) throws Exception {
        generation++;
        store.writeEncrypted(FILE_NAME, rootId, toJson());
    }

    // -------------------------------------------------------------------- codec

    public JSONObject toJson() throws Exception {
        JSONObject document = new JSONObject();
        document.put("formatVersion", FORMAT_VERSION);
        document.put("generation", generation);

        JSONArray array = new JSONArray();
        for (Entry entry : entries.values()) {
            JSONObject item = new JSONObject();
            item.put("name", entry.name);
            item.put("driveId", entry.driveId);
            item.put("size", entry.size);
            // JSONObject.put drops a null value, which is how the old manifests lost fields
            // and made restore throw. JSONObject.NULL round-trips properly.
            item.put("sha256", entry.sha256 == null ? JSONObject.NULL : entry.sha256);
            item.put("uploadedAt", entry.uploadedAt);
            array.put(item);
        }
        document.put("objects", array);
        return document;
    }

    public static ObjectIndex fromJson(JSONObject document) throws Exception {
        ObjectIndex index = new ObjectIndex();
        index.generation = document.optLong("generation", 0);

        JSONArray array = document.optJSONArray("objects");
        if (array == null) return index;

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", null);
            String driveId = item.optString("driveId", null);
            if (name == null || driveId == null) continue;
            String sha256 = item.isNull("sha256") ? null : item.optString("sha256", null);
            index.put(new Entry(name, driveId, item.optLong("size", 0), sha256,
                    item.optLong("uploadedAt", 0)));
        }
        return index;
    }

    /** Snapshot of the current state, so a failed run can be rolled back in memory. */
    public ObjectIndex copy() {
        ObjectIndex copy = new ObjectIndex();
        copy.generation = generation;
        copy.entries.putAll(new HashMap<>(entries));
        return copy;
    }
}
