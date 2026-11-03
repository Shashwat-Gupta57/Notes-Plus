package org.flexstudios.notes.plus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links between notes: {@code [[Note title]]} (or {@code [[Note title|words shown]]}) written in a
 * note's words or list items, the way Obsidian writes them. Matched by title, ignoring case and outer
 * spaces; when titles repeat, the newest note wins. A picture line ({@code ![[IMG_….png]]}) is never
 * a link. Mind-map ideas link to notes by uuid instead, and count as backlinks too.
 *
 * Pure: the same on the phone and the desktop, and as notelinks.js on the web.
 */
final class NoteLinks {

    private NoteLinks() {}

    static final Pattern LINK = Pattern.compile("(?<!!)\\[\\[([^\\[\\]|\\n]+)(\\|[^\\[\\]\\n]*)?\\]\\]");
    /** What's typed after an unclosed "[[" just before the caret: the start of a link. */
    static final Pattern TYPING = Pattern.compile("(?<!!)\\[\\[([^\\[\\]|\\n]{0,80})$");

    static String key(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }

    /** The titles {@code text} links to, each once, in order. */
    static List<String> titlesIn(String text) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (text == null || !text.contains("[[")) return out;
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty() && seen.add(key(t))) out.add(t);
        }
        return out;
    }

    /** Words for a card: "[[Trip|the trip]]" reads "the trip", "[[Trip]]" reads "Trip". */
    static String shown(String text) {
        if (text == null || !text.contains("[[")) return text;
        Matcher m = LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (m.find()) {
            out.append(text, at, m.start());
            out.append(m.group(2) != null && m.group(2).length() > 1 ? m.group(2).substring(1) : m.group(1).trim());
            at = m.end();
        }
        return out.append(text.substring(at)).toString();
    }

    /** Can be linked to: a titled note that isn't deleted or a template. */
    static boolean linkable(NoteEntity n) {
        return !n.isDeleted() && !n.isTemplate() && !n.isHidden() && !key(n.getTitle()).isEmpty();
    }

    /** The note {@code title} opens: the newest linkable one with that title, or null. */
    static NoteEntity resolve(String title, List<NoteEntity> notes) {
        String k = key(title);
        if (k.isEmpty()) return null;
        NoteEntity best = null;
        for (NoteEntity n : notes) {
            if (!linkable(n) || !key(n.getTitle()).equals(k)) continue;
            if (best == null || n.getTimestamp() > best.getTimestamp()) best = n;
        }
        return best;
    }

    /**
     * The notes that link to {@code note}: by its title in their words or list items, or (mind maps)
     * an idea pointing at its uuid. Newest first; never itself.
     */
    static List<NoteEntity> backlinks(NoteEntity note, List<NoteEntity> notes) {
        List<NoteEntity> out = new ArrayList<>();
        String k = key(note.getTitle());
        // Only the newest note with a title is what that title opens.
        boolean byTitle = !k.isEmpty() && resolve(note.getTitle(), notes) != null
                && resolve(note.getTitle(), notes).getId() == note.getId();
        String uuid = note.getUuid();
        for (NoteEntity n : notes) {
            // A hidden note never shows up on another's "Linked from" row.
            if (n.getId() == note.getId() || n.isDeleted() || n.isHidden()) continue;
            String words = n.getContent() == null ? "" : n.getContent();
            boolean links = false;
            if (byTitle && words.contains("[[") && words.toLowerCase(Locale.ROOT).contains(k)) {
                for (String t : titlesIn(words)) {
                    if (key(t).equals(k)) {
                        links = true;
                        break;
                    }
                }
            }
            if (!links && uuid != null && NoteData.MINDMAP.equals(n.getType()) && n.getData() != null
                    && n.getData().contains(uuid)) {
                for (NoteData.MindNode node : NoteData.mindMap(n.getData()).nodes) {
                    if (uuid.equals(node.linkUuid)) {
                        links = true;
                        break;
                    }
                }
            }
            if (links) out.add(n);
        }
        out.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return out;
    }

    /** {@code text} with its links to {@code from} pointing at {@code to} instead (words shown kept). */
    static String renameIn(String text, String from, String to) {
        if (text == null || !text.contains("[[")) return text;
        String k = key(from);
        Matcher m = LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        int at = 0;
        boolean changed = false;
        while (m.find()) {
            if (!key(m.group(1)).equals(k)) continue;
            out.append(text, at, m.start()).append("[[").append(to.trim()).append(m.group(2) == null ? "" : m.group(2)).append("]]");
            at = m.end();
            changed = true;
        }
        return changed ? out.append(text.substring(at)).toString() : text;
    }

    /**
     * Note {@code n} with its links to {@code from} renamed to {@code to}: {content, data}, or null
     * when it has none. List items and mind-map ideas are renamed in the note's data too.
     */
    static String[] renamed(NoteEntity n, String from, String to) {
        String content = n.getContent() == null ? "" : n.getContent();
        String next = renameIn(content, from, to);
        if (next.equals(content)) return null;
        String data = n.getData();
        if (NoteData.LIST.equals(n.getType())) {
            List<NoteData.TodoItem> items = NoteData.todos(data);
            for (NoteData.TodoItem item : items) item.text = renameIn(item.text, from, to);
            data = NoteData.todosJson(items, NoteData.wraps(data));
        } else if (NoteData.MINDMAP.equals(n.getType())) {
            NoteData.MindMap map = NoteData.mindMap(data);
            for (NoteData.MindNode node : map.nodes) node.text = renameIn(node.text, from, to);
            data = NoteData.mindMapJson(map);
        }
        return new String[]{next, data};
    }

    /** Titles to offer after "[[": those containing what's typed (starting with it first), newest first, at most {@code max}. */
    static List<String> suggestions(String typed, List<NoteEntity> notes, int max, int exceptId) {
        String q = key(typed);
        List<NoteEntity> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (NoteEntity n : notes) {
            if (!linkable(n) || n.getId() == exceptId) continue;
            String k = key(n.getTitle());
            if (k.startsWith(q)) starts.add(n);
            else if (k.contains(q)) contains.add(n);
        }
        java.util.Comparator<NoteEntity> newest = (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp());
        starts.sort(newest);
        contains.sort(newest);
        starts.addAll(contains);
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (NoteEntity n : starts) {
            if (seen.add(key(n.getTitle()))) out.add(n.getTitle().trim());
            if (out.size() >= max) break;
        }
        return out;
    }
}
