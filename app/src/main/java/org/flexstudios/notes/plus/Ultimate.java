package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Ultimate notes: a column of canvases, {@code {"blocks": [...]}}. Each block is the data of the type
 * it borrows from plus its {@code kind} and {@code id}: a list's {@code items}, a mind map's
 * {@code nodes}/{@code links} (and {@code h}, its height), an audio note's {@code recordings}; text
 * keeps {@code md} (pictures as {@code ![[…]]} lines in it), a day keeps {@code day} (its midnight, ms)
 * and {@code mood}. Kinds this version doesn't know are kept as they are. Identical on the desktop, and
 * the web's model.js; the same UltimateTest cases keep them in step.
 */
final class Ultimate {

    static final String TEXT = "text", LIST = "list", MINDMAP = "mindmap", AUDIO = "audio", DAY = "day";
    static final int MAP_H = 320, MAP_MIN_H = 160, MAP_MAX_H = 1200;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};

    private Ultimate() {}

    static String newId() {
        return String.format("%08x", RANDOM.nextInt());
    }

    /** The blocks (never null); junk entries are left out, and a block without an id gets one. */
    static List<JSONObject> blocks(String json) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray array;
        try {
            array = json == null || json.isEmpty() ? null : new JSONObject(json).optJSONArray("blocks");
        } catch (Exception e) {
            array = null;
        }
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject b = array.optJSONObject(i);
            if (b == null || !(b.opt("kind") instanceof String)) continue;
            try {
                if (!(b.opt("id") instanceof String) || b.optString("id").isEmpty()) b.put("id", newId());
            } catch (Exception ignored) {
                continue;
            }
            out.add(b);
        }
        return out;
    }

    static String blocksJson(List<JSONObject> blocks) {
        JSONArray array = new JSONArray();
        for (JSONObject b : blocks) array.put(b);
        try {
            return new JSONObject().put("blocks", array).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String kind(JSONObject b) {
        return b.optString("kind", "");
    }

    /** A block of {@code kind} holding {@code typeJson} (a list's, mind map's or audio note's data). */
    static JSONObject block(String id, String kind, String typeJson) {
        JSONObject b;
        try {
            b = typeJson == null ? new JSONObject() : new JSONObject(typeJson);
            b.put("id", id).put("kind", kind);
        } catch (Exception e) {
            b = new JSONObject();
        }
        return b;
    }

    /** How far a canvas sits in (0–4): nested under the list item or words above it. */
    static int indent(JSONObject b) {
        return (int) Math.max(0, Math.min(4, Math.round(b.optDouble("indent", 0))));
    }

    /** The blocks inside an idea, or an empty list. */
    static List<JSONObject> inside(NoteData.MindNode n) {
        return n.blocks == null ? new ArrayList<>() : blocks("{\"blocks\":" + n.blocks + "}");
    }

    /** A short summary of what's inside an idea, for its badge: "☑ 1/3 · 🎙 2". */
    static String insideSummary(List<JSONObject> list) {
        int items = 0, done = 0, recs = 0, maps = 0, words = 0, days = 0;
        for (JSONObject b : list) {
            switch (kind(b)) {
                case LIST:
                    for (NoteData.TodoItem i : NoteData.todos(b.toString())) {
                        if (i.text.trim().isEmpty()) continue;
                        items++;
                        if (i.done) done++;
                    }
                    break;
                case AUDIO: recs += NoteData.recordings(b.toString()).size(); break;
                case MINDMAP: maps++; break;
                case TEXT: if (!b.optString("md", "").trim().isEmpty()) words++; break;
                case DAY: days++; break;
                default: break;
            }
        }
        List<String> parts = new ArrayList<>();
        if (items > 0) parts.add("☑ " + done + "/" + items);
        if (recs > 0) parts.add("🎙 " + recs);
        if (maps > 0) parts.add(maps > 1 ? "◈ " + maps : "◈");
        if (days > 0) parts.add("📅");
        if (words > 0) parts.add("¶");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) s.append(i == 0 ? "" : " · ").append(parts.get(i));
        return s.toString();
    }

    static int mapHeight(JSONObject b) {
        double v = b.optDouble("h", 0);
        if (Double.isNaN(v) || v == 0) v = MAP_H;
        return (int) Math.max(MAP_MIN_H, Math.min(MAP_MAX_H, Math.round(v)));
    }

    /** "Thursday, 26 November 2026": the same words on every device, whatever its language settings. */
    static String dayWords(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " "
                + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    /** Midnight of the day {@code ms} falls on, here. */
    static long dayStart(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The note's words as Markdown, for search, cards, links and older versions (which open it as a text note). */
    static String content(List<JSONObject> blocks) {
        List<String> out = new ArrayList<>();
        for (JSONObject b : blocks) {
            String json = b.toString();
            switch (kind(b)) {
                case TEXT:
                    if (b.opt("md") instanceof String && !b.optString("md").trim().isEmpty()) out.add(b.optString("md").trim());
                    break;
                case LIST:
                    for (NoteData.TodoItem i : NoteData.todos(json)) {
                        if (!i.text.trim().isEmpty()) out.add((i.done ? "- [x] " : "- [ ] ") + i.text.trim());
                    }
                    break;
                case MINDMAP:
                    for (NoteData.MindNode n : NoteData.mindMap(json).nodes) {
                        if (!n.text.trim().isEmpty()) out.add("- " + n.text.trim());
                        if (!n.sub.trim().isEmpty()) out.add("- " + n.sub.trim());
                        if (n.blocks != null) {
                            String inside = content(blocks("{\"blocks\":" + n.blocks + "}"));
                            if (!inside.isEmpty()) out.add(inside);
                        }
                    }
                    break;
                case AUDIO:
                    for (NoteData.Recording r : NoteData.recordings(json)) {
                        out.add("🎙 " + (r.label.trim().isEmpty() ? "Recording" : r.label.trim()));
                    }
                    break;
                case DAY:
                    long day = (long) b.optDouble("day", 0);
                    if (day != 0) {
                        String mood = b.optString("mood", "");
                        out.add("### " + dayWords(day) + (mood.isEmpty() ? "" : " " + mood));
                    }
                    break;
                default:
                    break;
            }
        }
        StringBuilder s = new StringBuilder(); // Not String.join: Android 7 lacks it.
        for (int i = 0; i < out.size(); i++) s.append(i == 0 ? "" : "\n").append(out.get(i));
        return s.toString();
    }
}
