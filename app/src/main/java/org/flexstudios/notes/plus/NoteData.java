package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the notes app knows about its note types, in one place: what each type is
 * called and which editor opens it, the JSON shape of its structured data, and where its
 * media lives.
 *
 * Structured data is parsed leniently: a missing or malformed field reads as empty, so a
 * damaged note opens as an empty one rather than crashing the list.
 */
final class NoteData {

    private static final String TAG = "NoteData";

    static final String TEXT = "TEXT";
    static final String LIST = "LIST";
    static final String MINDMAP = "MINDMAP";
    static final String JOURNAL = "JOURNAL";
    static final String SKETCH = "SKETCH";
    static final String AUDIO = "AUDIO";

    static final String EXTRA_ID = "note_id";
    static final String EXTRA_TYPE = "note_type";

    private NoteData() {}

    // =================================================================== types

    /** How a type presents itself in the "New" sheet and the filter chips. */
    static final class Kind {
        final String type, label, plural, description;
        final int icon, accent;

        Kind(String type, String label, String plural, String description, int icon, int accent) {
            this.type = type;
            this.label = label;
            this.plural = plural;
            this.description = description;
            this.icon = icon;
            this.accent = accent;
        }
    }

    static final Kind[] KINDS = {
            new Kind(TEXT, "Note", "Notes", "Plain words, fast", R.drawable.ic_note_text, R.color.tile_orange),
            new Kind(LIST, "List", "Lists", "Things to do, tick them off", R.drawable.ic_note_list, R.color.tile_green),
            new Kind(JOURNAL, "Journal", "Journal", "Today, with photos and videos", R.drawable.ic_note_journal, R.color.tile_purple),
            new Kind(MINDMAP, "Mind map", "Mind maps", "Ideas and how they connect", R.drawable.ic_note_mindmap, R.color.tile_blue),
            new Kind(SKETCH, "Sketch", "Sketches", "Draw with your finger", R.drawable.ic_note_sketch, R.color.tile_red),
            new Kind(AUDIO, "Audio", "Audio", "Record your voice", R.drawable.ic_note_audio, R.color.tile_teal),
    };

    static Kind kind(String type) {
        for (Kind k : KINDS) if (k.type.equals(type)) return k;
        return KINDS[0];
    }

    /** Opens the right editor for a note, or for a new one of {@code type}. */
    static Intent editor(Context context, String type, int noteId) {
        Class<?> target;
        switch (type) {
            case MINDMAP: target = MindMapActivity.class; break;
            case SKETCH: target = SketchActivity.class; break;
            case AUDIO: target = AudioNoteActivity.class; break;
            default: target = NoteEditorActivity.class; break;
        }
        Intent intent = new Intent(context, target).putExtra(EXTRA_TYPE, type);
        if (noteId > 0) intent.putExtra(EXTRA_ID, noteId);
        return intent;
    }

    /** Card tints, chosen to read well in light and dark. Index 0 is "no colour". */
    static final int[] COLORS = {
            0, R.color.note_yellow, R.color.note_green, R.color.note_blue,
            R.color.note_purple, R.color.note_pink, R.color.note_gray,
            R.color.note_orange, R.color.note_red, R.color.note_teal, R.color.note_indigo, R.color.note_brown, R.color.note_lime
    };

    static final String[] COLOR_NAMES = {"None", "Yellow", "Green", "Blue", "Purple", "Pink", "Grey", "Orange", "Red", "Teal", "Indigo", "Brown", "Lime"};

    // =================================================================== files

    /** The note's private folder for photos, videos, sketches and recordings. */
    static File dir(Context context, int noteId) {
        File dir = new File(new File(context.getFilesDir(), "notes"), String.valueOf(noteId));
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create " + dir);
        return dir;
    }

    static void deleteFiles(Context context, int noteId) {
        VaultCrypto.deleteRecursive(new File(new File(context.getFilesDir(), "notes"), String.valueOf(noteId)));
    }

    // ==================================================================== list

    static final class TodoItem {
        String text;
        boolean done;

        TodoItem(String text, boolean done) {
            this.text = text;
            this.done = done;
        }

        /**
         * A picture between the items: an item that is just {@code ![[IMG_….png]]} (the embed line
         * the desktop uses in text notes), its file in the note's folder. Its name, or null.
         */
        @androidx.annotation.Nullable
        String picture() {
            if (text == null) return null;
            java.util.regex.Matcher m = PICTURE_ITEM.matcher(text.trim());
            return m.matches() ? m.group(1) : null;
        }
    }

    private static final java.util.regex.Pattern PICTURE_ITEM = java.util.regex.Pattern.compile("^!\\[\\[([^\\]/\\\\]+)\\]\\]$");

    static List<TodoItem> todos(String json) {
        List<TodoItem> items = new ArrayList<>();
        JSONArray array = array(json, "items");
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o != null) items.add(new TodoItem(o.optString("text", ""), o.optBoolean("done", false)));
        }
        return items;
    }

    /** Whether a list's or a mind map's long lines wrap (the default) rather than run on one line. */
    static boolean wraps(String json) {
        return object(json).optBoolean("wrap", true);
    }

    static String todosJson(List<TodoItem> items) {
        return todosJson(items, true);
    }

    static String todosJson(List<TodoItem> items, boolean wrap) {
        JSONArray array = new JSONArray();
        try {
            for (TodoItem item : items) array.put(new JSONObject().put("text", item.text).put("done", item.done));
            JSONObject o = new JSONObject().put("items", array);
            if (!wrap) o.put("wrap", false);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ mind map

    static final class MindNode {
        long id;
        long parent; // -1 for the root
        String text;
        float x, y;
        /** 0 inherits the branch colour; otherwise 1 + an index into MindMapView's palette. */
        int color;
        /** Children hidden behind a "+N" badge. */
        boolean collapsed;
        /** Id of another note this idea opens, or 0. Row ids differ per phone, so... */
        int link;
        /** ...the linked note's uuid wins when present: it is the same on every phone. */
        String linkUuid;
        /** A picture shown in the idea (set on the desktop): a file in the note's folder, or null. */
        String image;
        /** Sub-text: more about the idea, drawn smaller under its text. Never null. */
        String sub = "";

        MindNode(long id, long parent, String text, float x, float y) {
            this.id = id;
            this.parent = parent;
            this.text = text;
            this.x = x;
            this.y = y;
        }

        boolean hasLink() {
            return link != 0 || linkUuid != null;
        }

        void setLink(NoteEntity target) {
            link = target == null ? 0 : target.getId();
            linkUuid = target == null ? null : target.getUuid();
        }
    }

    /** The nodes, plus cross-links between any two ideas (drawn dashed). */
    static final class MindMap {
        final List<MindNode> nodes = new ArrayList<>();
        final List<long[]> links = new ArrayList<>();
        /** Long ideas wrap onto more lines (the default); off, each stays on one line, in full. */
        boolean wrap = true;
    }

    static MindMap mindMap(String json) {
        MindMap map = new MindMap();
        JSONArray array = array(json, "nodes");
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            MindNode n = new MindNode(o.optLong("id"), o.optLong("parent", -1), o.optString("text", ""),
                    (float) o.optDouble("x", 0), (float) o.optDouble("y", 0));
            n.color = o.optInt("color", 0);
            n.collapsed = o.optBoolean("collapsed", false);
            n.link = o.optInt("link", 0);
            n.linkUuid = o.optString("linkUuid", "").isEmpty() ? null : o.optString("linkUuid");
            n.image = o.optString("image", "").isEmpty() ? null : o.optString("image");
            n.sub = o.optString("sub", "");
            map.nodes.add(n);
        }
        JSONArray links = array(json, "links");
        for (int i = 0; i < links.length(); i++) {
            JSONArray pair = links.optJSONArray(i);
            if (pair != null && pair.length() == 2) map.links.add(new long[]{pair.optLong(0), pair.optLong(1)});
        }
        map.wrap = wraps(json);
        return map;
    }

    static String mindMapJson(MindMap map) {
        JSONArray array = new JSONArray();
        JSONArray links = new JSONArray();
        try {
            for (MindNode n : map.nodes) {
                JSONObject o = new JSONObject().put("id", n.id).put("parent", n.parent).put("text", n.text)
                        .put("x", Math.round(n.x * 10) / 10.0).put("y", Math.round(n.y * 10) / 10.0);
                if (n.color != 0) o.put("color", n.color);
                if (n.collapsed) o.put("collapsed", true);
                if (n.link != 0) o.put("link", n.link);
                if (n.linkUuid != null) o.put("linkUuid", n.linkUuid);
                if (n.image != null) o.put("image", n.image);
                if (!n.sub.isEmpty()) o.put("sub", n.sub);
                array.put(o);
            }
            for (long[] l : map.links) links.put(new JSONArray().put(l[0]).put(l[1]));
            JSONObject o = new JSONObject().put("nodes", array).put("links", links);
            if (!map.wrap) o.put("wrap", false);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================= journal

    static final class Journal {
        long day;
        String mood = "";
        final List<String> media = new ArrayList<>();
    }

    static Journal journal(String json, long fallbackDay) {
        Journal j = new Journal();
        JSONObject o = object(json);
        j.day = o.optLong("day", fallbackDay);
        j.mood = o.optString("mood", "");
        JSONArray media = o.optJSONArray("media");
        for (int i = 0; media != null && i < media.length(); i++) j.media.add(media.optString(i));
        return j;
    }

    static String journalJson(Journal j) {
        try {
            return new JSONObject().put("day", j.day).put("mood", j.mood)
                    .put("media", new JSONArray(j.media)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isVideo(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".webm") || lower.endsWith(".mkv")
                || lower.startsWith("vid_");
    }

    // =================================================================== audio

    static final class Recording {
        String file;
        String label;
        long durationMs;
        long createdAt;
        /** 48 amplitude samples in 0..1, for the waveform. */
        float[] wave;

        Recording(String file, String label, long durationMs, long createdAt, float[] wave) {
            this.file = file;
            this.label = label;
            this.durationMs = durationMs;
            this.createdAt = createdAt;
            this.wave = wave;
        }
    }

    static List<Recording> recordings(String json) {
        List<Recording> out = new ArrayList<>();
        JSONArray array = array(json, "recordings");
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            JSONArray w = o.optJSONArray("wave");
            float[] wave = new float[w == null ? 0 : w.length()];
            for (int k = 0; k < wave.length; k++) wave[k] = (float) w.optDouble(k, 0);
            out.add(new Recording(o.optString("file"), o.optString("label", ""), o.optLong("duration"),
                    o.optLong("createdAt"), wave));
        }
        return out;
    }

    static String recordingsJson(List<Recording> recordings) {
        JSONArray array = new JSONArray();
        try {
            for (Recording r : recordings) {
                JSONArray wave = new JSONArray();
                for (float v : r.wave) wave.put(Math.round(v * 100) / 100.0);
                array.put(new JSONObject().put("file", r.file).put("label", r.label)
                        .put("duration", r.durationMs).put("createdAt", r.createdAt).put("wave", wave));
            }
            return new JSONObject().put("recordings", array).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================= helpers

    private static JSONObject object(String json) {
        if (json == null || json.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            Log.w(TAG, "Unreadable note data", e);
            return new JSONObject();
        }
    }

    private static JSONArray array(String json, String key) {
        JSONArray array = object(json).optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }
}
