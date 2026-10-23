package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the notes app knows about its note types, in one place: what each type is
 * called and which editor opens it, the JSON shape of its structured data, and where its
 * media lives. The JSON is byte-for-byte the phone's, so a note means the same on both.
 *
 * Structured data is parsed leniently: a missing or malformed field reads as empty, so a
 * damaged note opens as an empty one rather than crashing the list.
 */
final class NoteData {

    private static final System.Logger LOG = System.getLogger("NoteData");

    static final String TEXT = "TEXT";
    static final String LIST = "LIST";
    static final String MINDMAP = "MINDMAP";
    static final String JOURNAL = "JOURNAL";
    static final String SKETCH = "SKETCH";
    static final String AUDIO = "AUDIO";

    private NoteData() {}

    // =================================================================== types

    /** How a type presents itself in the "New" sheet and the filter chips. */
    record Kind(String type, String label, String plural, String description, Icons.Icon icon, String accent) {}

    static final Kind[] KINDS = {
            new Kind(TEXT, "Note", "Notes", "Plain words, fast", Icons.NOTE_TEXT, "tile_orange"),
            new Kind(LIST, "List", "Lists", "Things to do, tick them off", Icons.NOTE_LIST, "tile_green"),
            new Kind(JOURNAL, "Journal", "Journal", "Today, with photos and videos", Icons.NOTE_JOURNAL, "tile_purple"),
            new Kind(MINDMAP, "Mind map", "Mind maps", "Ideas and how they connect", Icons.NOTE_MINDMAP, "tile_blue"),
            new Kind(SKETCH, "Sketch", "Sketches", "Draw with your mouse or pen", Icons.NOTE_SKETCH, "tile_red"),
            new Kind(AUDIO, "Audio", "Audio", "Record your voice", Icons.NOTE_AUDIO, "tile_teal"),
    };

    static Kind kind(String type) {
        for (Kind k : KINDS) if (k.type.equals(type)) return k;
        return KINDS[0];
    }

    /** The right editor for a note, or for a new one of {@code type} ({@code noteId} ≤ 0). */
    static NoteEditor editor(String type, int noteId) {
        return switch (type) {
            case MINDMAP -> new MindMapEditor(type, noteId);
            case SKETCH -> new SketchEditor(type, noteId);
            case AUDIO -> new AudioNoteEditor(type, noteId);
            default -> new NoteEditorView(type, noteId);
        };
    }

    /** Card tints, chosen to read well in light and dark. Index 0 is "no colour". */
    static final String[] COLORS = {
            null, "note_yellow", "note_green", "note_blue", "note_purple", "note_pink", "note_gray"
    };

    static final String[] COLOR_NAMES = {"None", "Yellow", "Green", "Blue", "Purple", "Pink", "Grey"};

    /** The colour a note's card and editor are painted with. */
    static String tint(int color, String fallback) {
        return color > 0 && color < COLORS.length ? COLORS[color] : fallback;
    }

    // =================================================================== files

    /** The note's private folder for photos, videos, sketches and recordings. */
    static Path dir(int noteId) {
        Path dir = Storage.notesDir().resolve(String.valueOf(noteId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not create " + dir, e);
        }
        return dir;
    }

    static void deleteFiles(int noteId) {
        Storage.deleteRecursive(Storage.notesDir().resolve(String.valueOf(noteId)));
    }

    // ==================================================================== list

    static final class TodoItem {
        String text;
        boolean done;

        TodoItem(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    static List<TodoItem> todos(String json) {
        List<TodoItem> items = new ArrayList<>();
        JSONArray array = array(json, "items");
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o != null) items.add(new TodoItem(o.optString("text", ""), o.optBoolean("done", false)));
        }
        return items;
    }

    static String todosJson(List<TodoItem> items) {
        JSONArray array = new JSONArray();
        for (TodoItem item : items) array.put(new JSONObject().put("text", item.text).put("done", item.done));
        return new JSONObject().put("items", array).toString();
    }

    // ================================================================ mind map

    static final class MindNode {
        long id;
        long parent; // -1 for the root
        String text;
        double x, y;
        /** 0 inherits the branch colour; otherwise 1 + an index into MindMapCanvas's palette. */
        int color;
        /** Children hidden behind a "+N" badge. */
        boolean collapsed;
        /** Id of another note this idea opens, or 0. Row ids differ per device, so... */
        int link;
        /** ...the linked note's uuid wins when present: it is the same everywhere. */
        String linkUuid;
        /** A picture shown in the idea: a file in the note's folder, or null. */
        String image;

        MindNode(long id, long parent, String text, double x, double y) {
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
    }

    static MindMap mindMap(String json) {
        MindMap map = new MindMap();
        JSONArray array = array(json, "nodes");
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            MindNode n = new MindNode(o.optLong("id"), o.optLong("parent", -1), o.optString("text", ""),
                    o.optDouble("x", 0), o.optDouble("y", 0));
            n.color = o.optInt("color", 0);
            n.collapsed = o.optBoolean("collapsed", false);
            n.link = o.optInt("link", 0);
            n.linkUuid = o.optString("linkUuid", "").isEmpty() ? null : o.optString("linkUuid");
            n.image = o.optString("image", "").isEmpty() ? null : o.optString("image");
            map.nodes.add(n);
        }
        JSONArray links = array(json, "links");
        for (int i = 0; i < links.length(); i++) {
            JSONArray pair = links.optJSONArray(i);
            if (pair != null && pair.length() == 2) map.links.add(new long[]{pair.optLong(0), pair.optLong(1)});
        }
        return map;
    }

    static String mindMapJson(MindMap map) {
        JSONArray array = new JSONArray();
        JSONArray links = new JSONArray();
        for (MindNode n : map.nodes) {
            JSONObject o = new JSONObject().put("id", n.id).put("parent", n.parent).put("text", n.text)
                    .put("x", Math.round(n.x * 10) / 10.0).put("y", Math.round(n.y * 10) / 10.0);
            if (n.color != 0) o.put("color", n.color);
            if (n.collapsed) o.put("collapsed", true);
            if (n.link != 0) o.put("link", n.link);
            if (n.linkUuid != null) o.put("linkUuid", n.linkUuid);
            if (n.image != null) o.put("image", n.image);
            array.put(o);
        }
        for (long[] l : map.links) links.put(new JSONArray().put(l[0]).put(l[1]));
        return new JSONObject().put("nodes", array).put("links", links).toString();
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
        return new JSONObject().put("day", j.day).put("mood", j.mood)
                .put("media", new JSONArray(j.media)).toString();
    }

    static boolean isVideo(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".webm") || lower.endsWith(".mkv")
                || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.startsWith("vid_");
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
        for (Recording r : recordings) {
            JSONArray wave = new JSONArray();
            for (float v : r.wave) wave.put(Math.round(v * 100) / 100.0);
            array.put(new JSONObject().put("file", r.file).put("label", r.label)
                    .put("duration", r.durationMs).put("createdAt", r.createdAt).put("wave", wave));
        }
        return new JSONObject().put("recordings", array).toString();
    }

    // ================================================================= helpers

    /** "1:05", or "1:02:03" past an hour. */
    static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    private static JSONObject object(String json) {
        if (json == null || json.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Unreadable note data", e);
            return new JSONObject();
        }
    }

    private static JSONArray array(String json, String key) {
        JSONArray array = object(json).optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }
}
