package org.flexstudios.notes.plus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Searching every note at once: every word typed must be somewhere in the note (its title, words,
 * list items, mind-map ideas and their sub-texts, a journal day's words: what {@code content} holds),
 * in any order, ignoring case. Archived notes and templates are searched too; hidden notes never are.
 * A locked note is searched by its title only. Titles that match come first, then the newest.
 *
 * Filters narrow it: a kind of note, a colour, a folder, "has pictures", "has dates".
 * A match is shown as a short piece of the note's words around it ({@link #snippet}).
 *
 * The same on the phone and the desktop, and as search.js on the web, kept in step by the same tests.
 */
final class Search {

    private Search() {}

    /** What narrows a search: null / -1 / false mean "any". */
    static final class Filters {
        String type;
        int color = -1;
        /** A folder's name, or "" for notes in no folder. */
        String folder;
        boolean pictures, dates;

        boolean any() {
            return type != null || color >= 0 || folder != null || pictures || dates;
        }
    }

    /** A piece of a note's words with where the typed words are in it. */
    static final class Snippet {
        final String text;
        final List<int[]> hits;

        Snippet(String text, List<int[]> hits) {
            this.text = text;
            this.hits = hits;
        }
    }

    private static final Pattern PICTURE = Pattern.compile("!\\[\\[[^\\]]+\\.(png|jpe?g|gif|webp|bmp|heic)\\]\\]", Pattern.CASE_INSENSITIVE);

    /** The words typed, each once, lowercased. */
    static List<String> terms(String query) {
        Set<String> out = new LinkedHashSet<>();
        if (query != null) {
            for (String w : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                w = w.replaceAll("^[\"'“”]+|[\"'“”]+$", "");
                if (!w.isEmpty()) out.add(w);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean locked(NoteEntity n) {
        return NoteLock.isLocked(n.getData());
    }

    /** Everything a search reads in a note, lowercased. */
    static String haystack(NoteEntity n) {
        String title = n.getTitle() == null ? "" : n.getTitle();
        if (locked(n)) return title.toLowerCase(Locale.ROOT);
        return (title + "\n" + (n.getContent() == null ? "" : n.getContent())).toLowerCase(Locale.ROOT);
    }

    /** How well {@code n} matches (higher is better), or -1 when a word is missing. */
    static int score(NoteEntity n, List<String> terms) {
        String hay = haystack(n), title = n.getTitle() == null ? "" : n.getTitle().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String t : terms) {
            if (!hay.contains(t)) return -1;
            score += title.contains(t) ? (title.startsWith(t) ? 120 : 100) : 10;
        }
        return score;
    }

    /** Whether the note has a picture: in its words, as a list item, a journal photo, an idea's picture, or a sketch. */
    static boolean hasPictures(NoteEntity n) {
        if (locked(n)) return false;
        String type = n.getType() == null ? NoteData.TEXT : n.getType();
        if (NoteData.SKETCH.equals(type)) return true;
        String data = n.getData() == null ? "" : n.getData();
        switch (type) {
            case NoteData.LIST:
                for (NoteData.TodoItem item : NoteData.todos(data)) if (item.picture() != null) return true;
                return false;
            case NoteData.JOURNAL:
                if (!NoteData.journal(data, 0).media.isEmpty()) return true;
                break;
            case NoteData.MINDMAP:
                for (NoteData.MindNode node : NoteData.mindMap(data).nodes) if (node.image != null) return true;
                return false;
            default:
                break;
        }
        return n.getContent() != null && PICTURE.matcher(n.getContent()).find();
    }

    /** Whether a list has an item with a day. */
    static boolean hasDates(NoteEntity n) {
        return NoteData.LIST.equals(n.getType()) && !locked(n) && Due.anyDated(NoteData.todos(n.getData()));
    }

    static boolean passes(NoteEntity n, Filters f) {
        if (f == null) return true;
        if (f.type != null && !f.type.equals(n.getType())) return false;
        if (f.color >= 0 && n.getColor() != f.color) return false;
        if (f.folder != null) {
            String folder = n.getFolder() == null ? "" : n.getFolder();
            if (!folder.equalsIgnoreCase(f.folder)) return false;
        }
        if (f.pictures && !hasPictures(n)) return false;
        return !f.dates || hasDates(n);
    }

    /**
     * Every note matching {@code query} and {@code filters}, best first. With nothing typed and no
     * filter, nothing (the grid shows as usual).
     */
    static List<NoteEntity> run(List<NoteEntity> notes, String query, Filters filters) {
        List<String> terms = terms(query);
        List<NoteEntity> out = new ArrayList<>();
        if (terms.isEmpty() && (filters == null || !filters.any())) return out;
        List<int[]> ranked = new ArrayList<>(); // {score, index}
        for (int i = 0; i < notes.size(); i++) {
            NoteEntity n = notes.get(i);
            if (n.isDeleted() || n.isHidden() || !passes(n, filters)) continue;
            int s = terms.isEmpty() ? 0 : score(n, terms);
            if (s < 0) continue;
            ranked.add(new int[]{s, i});
        }
        ranked.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0])
                : Long.compare(notes.get(b[1]).getTimestamp(), notes.get(a[1]).getTimestamp()));
        for (int[] r : ranked) out.add(notes.get(r[1]));
        return out;
    }

    /**
     * About {@code width} characters of the note's words around the first typed word found there (not
     * just in its title), on one line, with every typed word marked; null when the words don't match.
     */
    static Snippet snippet(NoteEntity n, List<String> terms, int width) {
        if (terms.isEmpty() || locked(n) || n.getContent() == null) return null;
        String words = NoteData.TEXT.equals(n.getType()) || NoteData.JOURNAL.equals(n.getType())
                ? Markdown.plain(n.getContent()) : n.getContent().replace("\n", " · "); // Items and ideas apart.
        words = NoteLinks.shown(words).replaceAll("\\s+", " ").trim();
        String lower = words.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String t : terms) {
            int i = lower.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return null;
        int start = Math.max(0, at - width / 3);
        if (start > 0) {
            int space = words.indexOf(' ', start);
            if (space > 0 && space < at) start = space + 1;
        }
        int end = Math.min(words.length(), start + width);
        if (end < words.length()) {
            int space = words.lastIndexOf(' ', end);
            if (space > at) end = space;
        }
        String prefix = start > 0 ? "…" : "", suffix = end < words.length() ? "…" : "";
        String text = prefix + words.substring(start, end) + suffix;
        return new Snippet(text, marks(text, terms));
    }

    /** Where the typed words are in {@code text} (a title), merged; empty when none. */
    static List<int[]> marks(String text, List<String> terms) {
        List<int[]> hits = new ArrayList<>();
        if (text == null) return hits;
        String low = text.toLowerCase(Locale.ROOT);
        for (String t : terms) {
            for (int i = low.indexOf(t); i >= 0; i = low.indexOf(t, i + t.length())) hits.add(new int[]{i, i + t.length()});
        }
        hits.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] h : hits) {
            if (!merged.isEmpty() && h[0] <= merged.get(merged.size() - 1)[1]) merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], h[1]);
            else merged.add(h);
        }
        return merged;
    }
}
