package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notes in and out: Google Keep (Takeout), Apple Notes (its .html / .txt export), Markdown folders
 * (Obsidian, Bear, Joplin, plain files), and Notes+'s own export, a zip of Markdown that reads back
 * here. Pure: files in, notes out (and back). The screens pick the files and store the notes.
 *
 * This file is the same on the phone and the desktop; the web's interchange.js reads and writes the
 * same way, kept in step by the same test cases.
 */
final class Interchange {

    private Interchange() {}

    static final int MAX_TEXT = 2_000_000;
    private static final String[] COLOR_NAMES = {"None", "Yellow", "Green", "Blue", "Purple", "Pink", "Grey", "Orange", "Red", "Teal", "Indigo", "Brown", "Lime"};
    private static final Pattern EMBED = Pattern.compile("^!\\[\\[([^\\]]+?)(\\|[^\\]]*)?\\]\\]$");
    private static final Pattern MD_IMAGE = Pattern.compile("^!\\[[^\\]]*\\]\\(<?([^)>]+?)>?(\\s+\"[^\"]*\")?\\)$");
    private static final Pattern TASK = Pattern.compile("^\\s*[-*+]\\s+\\[([ xX])\\]\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Set<String> PICTURE_EXT = new HashSet<>(java.util.Arrays.asList("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic"));

    /** A file found in what was picked (a zip's entry, a folder's file, a picked file). */
    static final class Entry {
        final String path;
        final byte[] bytes;

        Entry(String path, byte[] bytes) {
            this.path = path.replace('\\', '/').replaceAll("^\\./", "").replaceAll("^/+", "");
            this.bytes = bytes;
        }
    }

    /** One note, coming in or going out. */
    static final class Note {
        String type = NoteData.TEXT, title = "", content = "", data, folder;
        int color;
        boolean pinned, archived, template, locked;
        long edited, created;
        /** Its files, by the name its words use. */
        final Map<String, byte[]> files = new LinkedHashMap<>();
        /** Out: the app's own note (to find its files). */
        Object key;
    }

    /** Names a picture the way the apps do ("IMG_<millis>_<8 hex>.png"). */
    interface Namer {
        String name(String prefix, String ext);
    }

    /** Reads one of a note's files (for export); null when it isn't on this device. */
    interface Files {
        byte[] read(Note note, String name);
    }

    // =================================================================== in

    /** What was found: the notes, where they came from, and what was left. */
    static final class Found {
        final List<Note> notes = new ArrayList<>();
        final Set<String> sources = new LinkedHashSet<>();
        final Set<String> folders = new LinkedHashSet<>();
        int pictures, trashed, ignored;

        String summary() {
            int lists = 0;
            for (Note n : notes) if (NoteData.LIST.equals(n.type)) lists++;
            StringBuilder s = new StringBuilder("Found " + notes.size() + (notes.size() == 1 ? " note" : " notes"));
            if (!sources.isEmpty()) s.append(" from ").append(join(", ", sources));
            s.append(".");
            List<String> parts = new ArrayList<>();
            if (notes.size() - lists > 0) parts.add((notes.size() - lists) + " text");
            if (lists > 0) parts.add(lists + (lists == 1 ? " list" : " lists"));
            if (pictures > 0) parts.add(pictures + (pictures == 1 ? " picture" : " pictures"));
            if (!parts.isEmpty()) s.append(" ").append(capital(join(", ", parts))).append(".");
            if (!folders.isEmpty()) {
                List<String> f = new ArrayList<>(folders);
                s.append(" Folders: ").append(join(", ", f.subList(0, Math.min(6, f.size()))))
                        .append(f.size() > 6 ? " and " + (f.size() - 6) + " more" : "").append(".");
            }
            if (trashed > 0) s.append(" ").append(trashed).append(" in Keep's trash left out.");
            return s.toString();
        }
    }

    /** Every note in {@code entries}. {@code now} dates notes that carry no date of their own. */
    static Found read(List<Entry> entries, Namer namer, long now) {
        Found found = new Found();
        Map<String, Entry> byPath = new HashMap<>();
        Map<String, Entry> byName = new HashMap<>();
        List<Entry> notes = new ArrayList<>();
        for (Entry e : entries) {
            if (hidden(e.path)) continue;
            byPath.put(e.path.toLowerCase(Locale.ROOT), e);
            byName.putIfAbsent(base(e.path).toLowerCase(Locale.ROOT), e);
            String ext = ext(e.path);
            if (inAttachments(e.path) || base(e.path).equalsIgnoreCase("Not exported.md")) continue;
            if (ext.equals("json") || ext.equals("md") || ext.equals("markdown") || ext.equals("txt")
                    || ext.equals("html") || ext.equals("htm")) notes.add(e);
        }
        String root = commonRoot(notes);
        Pics pics = new Pics(byPath, byName, namer, found);
        for (Entry e : notes) {
            if (e.bytes.length > MAX_TEXT) {
                found.ignored++;
                continue;
            }
            String text = new String(e.bytes, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            String rel = e.path.substring(Math.min(root.length(), e.path.length()));
            String ext = ext(e.path);
            Note n = null;
            if (ext.equals("json")) {
                n = keep(text, e.path, pics, found);
                if (n != null) found.sources.add("Google Keep");
            } else if (ext.equals("html") || ext.equals("htm")) {
                n = html(text, e.path, pics);
                found.sources.add("Apple Notes");
            } else {
                n = markdown(text, e.path, pics);
                found.sources.add(ext.equals("txt") ? "text files" : "Markdown");
            }
            if (n == null) {
                found.ignored++;
                continue;
            }
            if (!ext.equals("json")) fileFolder(n, rel);
            if (n.edited <= 0) n.edited = now;
            if (n.folder != null) found.folders.add(n.folder);
            found.notes.add(n);
        }
        if (found.sources.contains("Markdown") && found.sources.contains("text files")) found.sources.remove("text files");
        return found;
    }

    /** The key that says two notes are the same (importing twice skips what's already here). */
    static String sameKey(String title, String content) {
        String t = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        // Without its picture lines: a picture gets a new name each time it's brought in.
        String c = content == null ? "" : content.replaceAll("!\\[\\[[^\\]]*\\]\\]", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return t + "\n" + c;
    }

    // ------------------------------------------------------------- Google Keep

    private static Note keep(String text, String path, Pics pics, Found found) {
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
        if (!(o.has("textContent") || o.has("listContent")) || !(o.has("userEditedTimestampUsec") || o.has("createdTimestampUsec") || o.has("isTrashed"))) {
            return null;
        }
        if (o.optBoolean("isTrashed")) {
            found.trashed++;
            return null;
        }
        Note n = new Note();
        n.title = o.optString("title", "").trim();
        n.pinned = o.optBoolean("isPinned");
        n.archived = o.optBoolean("isArchived");
        n.color = keepColor(o.optString("color", "DEFAULT"));
        n.edited = o.optLong("userEditedTimestampUsec", 0) / 1000;
        n.created = o.optLong("createdTimestampUsec", 0) / 1000;
        JSONArray labels = o.optJSONArray("labels");
        if (labels != null && labels.length() > 0) {
            String label = labels.optJSONObject(0) == null ? "" : labels.optJSONObject(0).optString("name", "").trim();
            if (!label.isEmpty()) n.folder = clip(label, 100);
        }
        List<String> pictureNames = new ArrayList<>();
        JSONArray attachments = o.optJSONArray("attachments");
        for (int i = 0; attachments != null && i < attachments.length(); i++) {
            JSONObject a = attachments.optJSONObject(i);
            if (a == null || !a.optString("mimetype", "").startsWith("image/")) continue;
            String name = pics.take(a.optString("filePath", ""), parent(path), n);
            if (name != null) pictureNames.add(name);
        }
        JSONArray list = o.optJSONArray("listContent");
        if (list != null) {
            List<NoteData.TodoItem> items = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.optJSONObject(i);
                if (it == null) continue;
                items.add(new NoteData.TodoItem(it.optString("text", "").replace("\n", " "), it.optBoolean("isChecked")));
            }
            for (String p : pictureNames) items.add(new NoteData.TodoItem("![[" + p + "]]", false));
            listNote(n, items);
        } else {
            StringBuilder words = new StringBuilder(o.optString("textContent", "").trim());
            for (String p : pictureNames) words.append(words.length() > 0 ? "\n\n" : "").append("![[").append(p).append("]]");
            n.content = words.toString();
        }
        if (n.title.isEmpty() && n.content.isEmpty() && n.data == null) return null;
        return n;
    }

    private static int keepColor(String c) {
        switch (c.toUpperCase(Locale.ROOT)) {
            case "RED": return 8;
            case "ORANGE": return 7;
            case "YELLOW": return 1;
            case "GREEN": return 2;
            case "TEAL": return 9;
            case "BLUE": return 3;
            case "CERULEAN": return 10;
            case "PURPLE": return 4;
            case "PINK": return 5;
            case "BROWN": return 11;
            case "GRAY": case "GREY": return 6;
            default: return 0;
        }
    }

    // --------------------------------------------------------------- Markdown

    private static Note markdown(String text, String path, Pics pics) {
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        Note n = new Note();
        Map<String, String> front = new HashMap<>();
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                for (String line : text.substring(4, end).split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) front.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), unquote(line.substring(colon + 1).trim()));
                }
                int after = text.indexOf('\n', end + 4);
                text = after < 0 ? "" : text.substring(after + 1);
            }
        }
        String title = front.getOrDefault("title", "");
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(0).trim().isEmpty()) lines.remove(0);
        if (title.isEmpty() && !lines.isEmpty() && lines.get(0).startsWith("# ")) {
            title = lines.remove(0).substring(2).trim();
            while (!lines.isEmpty() && lines.get(0).trim().isEmpty()) lines.remove(0);
        }
        if (title.isEmpty()) title = stripExt(base(path));
        n.title = clip(title, 500);
        n.edited = date(front.getOrDefault("updated", front.getOrDefault("modified", front.get("date"))));
        n.created = date(front.get("created"));
        n.pinned = "true".equalsIgnoreCase(front.get("pinned"));
        n.color = colorIndex(front.getOrDefault("colour", front.get("color")));
        if (front.containsKey("folder") && !front.get("folder").isEmpty()) n.folder = clip(front.get("folder"), 100);
        String dir = parent(path);
        String type = front.getOrDefault("type", "").toLowerCase(Locale.ROOT);

        if (type.equals("mind map") || type.equals("mindmap")) {
            Note map = mindMapFrom(lines, dir, pics, n);
            if (map != null) return map;
        }
        // Only tasks (and pictures): a list.
        boolean tasks = false, other = false;
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            if (TASK.matcher(l).matches()) tasks = true;
            else if (imageLine(l) == null) other = true;
        }
        if (type.equals("list") || (tasks && !other)) {
            List<NoteData.TodoItem> items = new ArrayList<>();
            for (String l : lines) {
                if (l.trim().isEmpty()) continue;
                Matcher t = TASK.matcher(l);
                String img = imageLine(l);
                if (t.matches()) items.add(task(t.group(2), t.group(1).equalsIgnoreCase("x")));
                else if (img != null) {
                    String name = pics.take(img, dir, n);
                    if (name != null) items.add(new NoteData.TodoItem("![[" + name + "]]", false));
                } else {
                    Matcher b = BULLET.matcher(l);
                    items.add(new NoteData.TodoItem(b.matches() ? b.group(2).trim() : l.trim(), false));
                }
            }
            listNote(n, items);
            return n;
        }
        StringBuilder words = new StringBuilder();
        for (String l : lines) {
            String img = imageLine(l);
            if (img != null) {
                String name = pics.take(img, dir, n);
                l = name != null ? "![[" + name + "]]" : l;
            }
            words.append(l).append('\n');
        }
        n.content = words.toString().replaceAll("\n{3,}", "\n\n").trim();
        if (type.equals("journal")) {
            n.type = NoteData.JOURNAL;
            NoteData.Journal j = NoteData.journal(null, n.edited > 0 ? n.edited : 0);
            long day = date(front.get("day"));
            if (day > 0) j.day = day;
            j.mood = front.getOrDefault("mood", "");
            // Photos were written after the words: back into the day's photos.
            List<String> kept = new ArrayList<>();
            for (String l : n.content.split("\n", -1)) {
                Matcher m = EMBED.matcher(l.trim());
                if (m.matches()) j.media.add(m.group(1));
                else kept.add(l);
            }
            n.content = join("\n", kept).trim();
            n.data = NoteData.journalJson(j);
        }
        return n;
    }

    /** A task's words, with a 📅 day, ⏰ time and 🔁 repeat taken off them. */
    private static NoteData.TodoItem task(String words, boolean done) {
        NoteData.TodoItem item = new NoteData.TodoItem(words, done);
        Matcher due = Pattern.compile("\\s*📅\\s*(\\d{4}-\\d{2}-\\d{2})").matcher(words);
        if (due.find() && Due.day(due.group(1)) != Due.NO_DAY) {
            item.due = due.group(1);
            words = words.substring(0, due.start()) + words.substring(due.end());
            Matcher time = Pattern.compile("\\s*⏰\\s*(\\d{1,2}):(\\d{2})").matcher(words);
            if (time.find()) {
                item.time = String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(time.group(1)), time.group(2));
                words = words.substring(0, time.start()) + words.substring(time.end());
            }
            Matcher repeat = Pattern.compile("\\s*🔁\\s*([^📅⏰]+)$").matcher(words);
            if (repeat.find()) {
                item.repeat = repeatFrom(repeat.group(1).trim(), item.due);
                words = words.substring(0, repeat.start());
            }
        }
        item.text = words.trim();
        return item;
    }

    /** "every Mon, Wed" (what {@link Due#repeatLong} writes, and Obsidian Tasks' words) back to a repeat. */
    static String repeatFrom(String words, String due) {
        String w = words.toLowerCase(Locale.ROOT).replace("every", "").trim();
        if (w.equals("day") || w.equals("daily")) return "daily";
        if (w.equals("weekday") || w.equals("weekdays")) return "weekdays";
        Matcher month = Pattern.compile("month(?: on the (\\d{1,2})(?:st|nd|rd|th)?)?").matcher(w);
        if (month.matches()) {
            int d = month.group(1) != null ? Integer.parseInt(month.group(1)) : Due.civil(Due.day(due))[2];
            return d >= 1 && d <= 31 ? "monthly:" + d : null;
        }
        boolean[] on = new boolean[8];
        boolean any = false;
        String[] full = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
        for (String part : w.replace("week on", "").split("[,\\s]+(and\\s+)?")) {
            for (int d = 0; d < 7; d++) {
                if (!part.isEmpty() && (full[d].startsWith(part) && part.length() >= 3)) {
                    on[d + 1] = true;
                    any = true;
                }
            }
        }
        if (!any && w.equals("week")) {
            on[Due.weekday(Due.day(due))] = true;
            any = true;
        }
        return any ? Due.weekly(on) : null;
    }

    /** A nested bullet outline (what the export writes for a mind map) back into its ideas. */
    private static Note mindMapFrom(List<String> lines, String dir, Pics pics, Note n) {
        NoteData.MindMap map = NoteData.mindMap(null);
        List<int[]> stack = new ArrayList<>(); // {indent, node index}
        long nextId = 1;
        int row = 0;
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            Matcher b = BULLET.matcher(l);
            if (b.matches()) {
                int indent = b.group(1).replace("\t", "    ").length();
                while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) stack.remove(stack.size() - 1);
                long parent = stack.isEmpty() ? -1 : map.nodes.get(stack.get(stack.size() - 1)[1]).id;
                NoteData.MindNode node = new NoteData.MindNode(nextId++, parent, b.group(2).trim(), 240 * stack.size(), 90 * row++);
                map.nodes.add(node);
                stack.add(new int[]{indent, map.nodes.size() - 1});
            } else if (!map.nodes.isEmpty()) {
                NoteData.MindNode last = map.nodes.get(map.nodes.size() - 1);
                String t = l.trim();
                String img = imageLine(t);
                if (img != null) last.image = pics.take(img, dir, n);
                else if (t.length() > 2 && t.startsWith("_") && t.endsWith("_")) last.sub = t.substring(1, t.length() - 1);
            }
        }
        if (map.nodes.isEmpty()) return null;
        n.type = NoteData.MINDMAP;
        n.data = NoteData.mindMapJson(map);
        StringBuilder words = new StringBuilder();
        for (NoteData.MindNode node : map.nodes) words.append(node.text).append('\n');
        n.content = words.toString().trim();
        return n;
    }

    // ------------------------------------------------------- Apple Notes (HTML)

    private static Note html(String text, String path, Pics pics) {
        Note n = new Note();
        Matcher t = Pattern.compile("(?is)<title>(.*?)</title>").matcher(text);
        String title = t.find() ? entities(t.group(1)).trim() : "";
        String md = htmlToMarkdown(text, parent(path), pics, n);
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(md.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(0).trim().isEmpty()) lines.remove(0);
        if (!lines.isEmpty() && lines.get(0).startsWith("# ")) {
            String first = lines.get(0).substring(2).trim();
            if (title.isEmpty() || title.equalsIgnoreCase(first)) {
                title = first;
                lines.remove(0);
            }
        }
        if (title.isEmpty()) title = stripExt(base(path));
        n.title = clip(title, 500);
        n.content = join("\n", lines).replaceAll("\n{3,}", "\n\n").trim();
        return n;
    }

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9]+)([^>]*)>|<!--.*?-->", Pattern.DOTALL);

    /** HTML as Notes+ Markdown: headings, bold, italic, underline, strike, lists, checklists, links, pictures. */
    static String htmlToMarkdown(String html, String dir, Pics pics, Note n) {
        html = html.replaceAll("(?is)<(head|style|script)\\b.*?</\\1>", "");
        StringBuilder out = new StringBuilder();
        List<String> lists = new ArrayList<>();
        String href = null;
        int linkStart = 0;
        Matcher m = TAG.matcher(html);
        int at = 0;
        while (m.find()) {
            out.append(entities(html.substring(at, m.start()).replaceAll("\\s+", " ")));
            at = m.end();
            if (m.group(2) == null) continue; // A comment.
            boolean close = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase(Locale.ROOT), attrs = m.group(3);
            switch (tag) {
                case "br": out.append('\n'); break;
                // Block tags start a new line, never an empty one: Apple Notes puts each line in a div.
                case "p": case "div": case "tr": case "blockquote": newLine(out); break;
                case "h1": newLine(out); if (!close) out.append("# "); break;
                case "h2": newLine(out); if (!close) out.append("## "); break;
                case "h3": case "h4": case "h5": case "h6": newLine(out); if (!close) out.append("### "); break;
                case "b": case "strong": out.append("**"); break;
                case "i": case "em": out.append("*"); break;
                case "u": out.append(close ? "</u>" : "<u>"); break;
                case "s": case "strike": case "del": out.append("~~"); break;
                case "ul": case "ol":
                    if (close) { if (!lists.isEmpty()) lists.remove(lists.size() - 1); }
                    else lists.add(tag + (attrs.toLowerCase(Locale.ROOT).contains("checklist") ? ":check" : ""));
                    newLine(out);
                    break;
                case "li":
                    newLine(out);
                    if (close) break;
                    String kind = lists.isEmpty() ? "ul" : lists.get(lists.size() - 1);
                    String a = attrs.toLowerCase(Locale.ROOT);
                    String indent = repeat("  ", Math.max(0, lists.size() - 1));
                    if (kind.endsWith(":check") || a.contains("checked") || a.contains("unchecked")) {
                        boolean done = a.contains("checked") && !a.contains("unchecked") || a.contains("done");
                        out.append(indent).append(done ? "- [x] " : "- [ ] ");
                    } else out.append(indent).append(kind.startsWith("ol") ? "1. " : "- ");
                    break;
                case "input":
                    if (attrs.toLowerCase(Locale.ROOT).contains("checkbox")) {
                        int line = out.lastIndexOf("\n") + 1;
                        String cur = out.substring(line);
                        boolean done = attrs.toLowerCase(Locale.ROOT).matches(".*\\bchecked\\b.*");
                        if (cur.trim().equals("-")) out.setLength(line + cur.indexOf('-'));
                        if (cur.trim().equals("-") || cur.trim().isEmpty()) out.append(done ? "- [x] " : "- [ ] ");
                    }
                    break;
                case "a":
                    if (!close) {
                        Matcher h = Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(attrs);
                        href = h.find() ? h.group(1) : null;
                        linkStart = out.length();
                    } else if (href != null) {
                        String words = out.substring(linkStart).trim();
                        if (!words.equals(href) && href.matches("(?i)https?://.*")) out.append(" (").append(href).append(")");
                        href = null;
                    }
                    break;
                case "img":
                    Matcher src = Pattern.compile("(?i)src\\s*=\\s*[\"']([^\"']+)[\"']").matcher(attrs);
                    if (src.find()) {
                        String name = pics.take(entities(src.group(1)), dir, n);
                        if (name != null) out.append("\n![[").append(name).append("]]\n");
                    }
                    break;
                default:
                    break;
            }
        }
        out.append(entities(html.substring(at).replaceAll("\\s+", " ")));
        StringBuilder clean = new StringBuilder();
        for (String line : out.toString().split("\n", -1)) {
            String t = line.replaceAll("[ \\t\\u00A0]+$", "").replaceAll("^ (?=\\S)", "");
            t = t.replace("****", "").replace("~~~~", "");
            clean.append(t).append('\n');
        }
        return clean.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Starts a new line unless one has just started. */
    private static void newLine(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private static String entities(String s) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp|#39);").matcher(s);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (m.find()) {
            out.append(s, at, m.start());
            String e = m.group(1);
            switch (e) {
                case "amp": out.append('&'); break;
                case "lt": out.append('<'); break;
                case "gt": out.append('>'); break;
                case "quot": out.append('"'); break;
                case "apos": case "#39": out.append('\''); break;
                case "nbsp": out.append(' '); break;
                default:
                    try {
                        out.appendCodePoint(e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1)));
                    } catch (Exception bad) {
                        out.append(m.group());
                    }
            }
            at = m.end();
        }
        return out.append(s.substring(at)).toString();
    }

    // ---------------------------------------------------------------- pictures

    /** Finds pictures a note names and gives them the apps' names; counts them. */
    static final class Pics {
        final Map<String, Entry> byPath, byName;
        final Namer namer;
        final Found found;

        Pics(Map<String, Entry> byPath, Map<String, Entry> byName, Namer namer, Found found) {
            this.byPath = byPath;
            this.byName = byName;
            this.namer = namer;
            this.found = found;
        }

        /** The picture at {@code ref} (relative to {@code dir}, by its name anywhere, or a data: URI), or null. */
        String take(String ref, String dir, Note n) {
            ref = ref.trim();
            byte[] bytes = null;
            String ext;
            if (ref.startsWith("data:image/")) {
                int comma = ref.indexOf(','), slash = ref.indexOf('/'), semi = ref.indexOf(';');
                if (comma < 0 || !ref.substring(0, comma).endsWith(";base64")) return null;
                ext = ref.substring(slash + 1, semi > 0 ? semi : comma).toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
                try {
                    bytes = base64(ref.substring(comma + 1));
                } catch (Exception e) {
                    return null;
                }
            } else {
                if (ref.matches("(?i)[a-z]+://.*")) return null; // Online: not a file we have.
                String clean = decode(ref.split("[?#]")[0]);
                ext = ext(clean);
                if (!PICTURE_EXT.contains(ext)) {
                    // Keep names its files without the extension sometimes ("abc" for "abc.jpg").
                    for (String e : new String[]{"jpg", "png", "jpeg", "gif", "webp"}) {
                        if (find(clean + "." + e, dir) != null) {
                            clean = clean + "." + e;
                            ext = e;
                            break;
                        }
                    }
                }
                if (!PICTURE_EXT.contains(ext)) return null;
                Entry e = find(clean, dir);
                if (e == null) return null;
                bytes = e.bytes;
            }
            if (!PICTURE_EXT.contains(ext)) return null;
            String name = namer.name("IMG", ext.equals("jpeg") ? "jpg" : ext);
            n.files.put(name, bytes);
            found.pictures++;
            return name;
        }

        private Entry find(String ref, String dir) {
            String joined = normalize((dir.isEmpty() ? "" : dir + "/") + ref).toLowerCase(Locale.ROOT);
            Entry e = byPath.get(joined);
            if (e == null) e = byPath.get(normalize(ref).toLowerCase(Locale.ROOT));
            if (e == null) e = byName.get(base(ref).toLowerCase(Locale.ROOT));
            return e;
        }
    }

    /** The picture a line is ({@code ![[x.png]]} or {@code ![](x.png)}), or null. */
    private static String imageLine(String line) {
        String t = line.trim();
        Matcher m = EMBED.matcher(t);
        if (m.matches()) return m.group(1);
        m = MD_IMAGE.matcher(t);
        return m.matches() ? m.group(1) : null;
    }

    // =================================================================== out

    /**
     * Every note as Markdown in a folder named {@code root}: folders as folders, Archived and
     * Templates too, pictures and recordings in attachments/. Locked notes can't be read: they're
     * listed in "Not exported.md".
     */
    static List<Entry> write(List<Note> notes, Files files, String root) {
        List<Entry> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> attachments = new HashSet<>();
        List<String> locked = new ArrayList<>();
        String base = root.isEmpty() ? "" : root + "/";
        for (Note n : notes) {
            String title = n.title == null ? "" : n.title.trim();
            if (n.locked) {
                locked.add(title.isEmpty() ? "An untitled " + kindName(n.type).toLowerCase(Locale.ROOT) : title);
                continue;
            }
            String dir = n.template ? "Templates/" : n.archived ? "Archived/" : n.folder != null && !n.folder.trim().isEmpty() ? safe(n.folder) + "/" : "";
            String name = safe(title.isEmpty() ? "Untitled " + kindName(n.type).toLowerCase(Locale.ROOT) : title);
            String path = base + dir + name + ".md";
            for (int k = 2; used.contains(path.toLowerCase(Locale.ROOT)); k++) path = base + dir + name + " (" + k + ").md";
            used.add(path.toLowerCase(Locale.ROOT));
            String up = dir.isEmpty() ? "" : "../";

            List<String> missing = new ArrayList<>();
            java.util.function.Function<String, String> attach = file -> {
                byte[] bytes = files.read(n, file);
                if (bytes == null) {
                    missing.add(file);
                    return null;
                }
                String at = "attachments/" + file;
                for (int k = 2; attachments.contains(at.toLowerCase(Locale.ROOT)); k++) at = "attachments/" + k + "-" + file;
                attachments.add(at.toLowerCase(Locale.ROOT));
                out.add(new Entry(base + at, bytes));
                return up + at;
            };

            StringBuilder md = new StringBuilder("---\n");
            md.append("title: ").append(quote(title)).append('\n');
            md.append("type: ").append(kindName(n.type).toLowerCase(Locale.ROOT)).append('\n');
            if (n.created > 0) md.append("created: ").append(iso(n.created)).append('\n');
            if (n.edited > 0) md.append("updated: ").append(iso(n.edited)).append('\n');
            if (n.pinned) md.append("pinned: true\n");
            if (n.color > 0 && n.color < COLOR_NAMES.length) md.append("colour: ").append(COLOR_NAMES[n.color]).append('\n');
            if (n.folder != null && !n.folder.trim().isEmpty()) md.append("folder: ").append(quote(n.folder.trim())).append('\n');

            StringBuilder body = new StringBuilder();
            switch (n.type) {
                case NoteData.LIST:
                    for (NoteData.TodoItem item : NoteData.todos(n.data)) {
                        String pic = item.picture();
                        if (pic != null) {
                            String at = attach.apply(pic);
                            body.append(at != null ? "![](" + at + ")" : "![[" + pic + "]]").append('\n');
                            continue;
                        }
                        body.append(item.done ? "- [x] " : "- [ ] ").append(item.text.replace("\n", " "));
                        if (Due.dated(item)) {
                            body.append(" 📅 ").append(item.due);
                            if (item.time != null) body.append(" ⏰ ").append(item.time);
                            if (Due.repeats(item)) body.append(" 🔁 ").append(Due.repeatLong(item.repeat).toLowerCase(Locale.ROOT));
                        }
                        body.append('\n');
                    }
                    break;
                case NoteData.JOURNAL: {
                    NoteData.Journal j = NoteData.journal(n.data, n.edited);
                    md.append("day: ").append(isoDay(j.day)).append('\n');
                    if (j.mood != null && !j.mood.isEmpty()) md.append("mood: ").append(j.mood).append('\n');
                    body.append(pictures(n.content, attach)).append('\n');
                    for (String m : j.media) {
                        String at = attach.apply(m);
                        body.append('\n').append(at != null ? "![](" + at + ")" : "![[" + m + "]]");
                    }
                    break;
                }
                case NoteData.MINDMAP: {
                    NoteData.MindMap map = NoteData.mindMap(n.data);
                    for (NoteData.MindNode r : map.nodes) if (r.parent < 0) outline(map, r, 0, body, attach, new HashSet<>());
                    break;
                }
                case NoteData.SKETCH: {
                    String at = attach.apply("sketch.png");
                    if (at != null) body.append("![](").append(at).append(")\n");
                    break;
                }
                case NoteData.AUDIO:
                    for (NoteData.Recording r : NoteData.recordings(n.data)) {
                        String at = attach.apply(r.file);
                        String label = r.label == null || r.label.isEmpty() ? r.file : r.label;
                        body.append("- ").append(at != null ? "[" + label + "](" + at.replace(" ", "%20") + ")" : label).append('\n');
                    }
                    break;
                default:
                    body.append(pictures(n.content, attach));
            }
            md.append("---\n\n").append(body.toString().trim()).append('\n');
            if (!missing.isEmpty()) {
                md.append("\n> ").append(missing.size() == 1 ? "1 picture or recording isn't" : missing.size() + " pictures or recordings aren't")
                        .append(" on this device yet (they're in Google Drive): open the note in Notes+ once to bring them here.\n");
            }
            out.add(0, new Entry(path, md.toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (!locked.isEmpty()) {
            StringBuilder s = new StringBuilder("# Not exported\n\nLocked notes can only be read with their lock, so these weren't exported. "
                    + "Unlock one in Notes+ (and remove its lock) to export it.\n\n");
            for (String t : locked) s.append("- ").append(t).append('\n');
            out.add(new Entry(base + "Not exported.md", s.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static void outline(NoteData.MindMap map, NoteData.MindNode node, int depth, StringBuilder body,
                                java.util.function.Function<String, String> attach, Set<Long> seen) {
        if (!seen.add(node.id)) return;
        String pad = repeat("  ", depth);
        body.append(pad).append("- ").append(node.text == null || node.text.isEmpty() ? "(idea)" : node.text.replace("\n", " ")).append('\n');
        if (node.sub != null && !node.sub.isEmpty()) body.append(pad).append("  _").append(node.sub.replace("\n", " ")).append("_\n");
        if (node.image != null) {
            String at = attach.apply(node.image);
            if (at != null) body.append(pad).append("  ![](").append(at).append(")\n");
        }
        for (NoteData.MindNode c : map.nodes) if (c.parent == node.id) outline(map, c, depth + 1, body, attach, seen);
    }

    /** Words with their picture lines pointing at attachments/. */
    private static String pictures(String content, java.util.function.Function<String, String> attach) {
        StringBuilder out = new StringBuilder();
        for (String line : (content == null ? "" : content).split("\n", -1)) {
            Matcher m = EMBED.matcher(line.trim());
            if (m.matches()) {
                String at = attach.apply(m.group(1));
                if (at != null) line = "![](" + at + ")";
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    // ================================================================= helpers

    private static void listNote(Note n, List<NoteData.TodoItem> items) {
        n.type = NoteData.LIST;
        n.data = NoteData.todosJson(items);
        StringBuilder words = new StringBuilder();
        for (NoteData.TodoItem item : items) if (item.picture() == null && !item.text.trim().isEmpty()) words.append(item.text.trim()).append('\n');
        n.content = words.toString().trim();
    }

    /** Folders from where a file was: the first folder under what all files share; Archived and Templates mean those. */
    private static void fileFolder(Note n, String rel) {
        String[] parts = rel.split("/");
        if (parts.length < 2) return;
        String first = parts[0];
        if (first.equalsIgnoreCase("Archived")) {
            n.archived = true;
            if (parts.length > 2 && n.folder == null) n.folder = clip(parts[1], 100);
        } else if (first.equalsIgnoreCase("Templates")) {
            n.template = true;
        } else if (n.folder == null && !first.equalsIgnoreCase("Notes")) { // Apple's default folder is just "Notes".
            n.folder = clip(first, 100);
        }
    }

    private static String commonRoot(List<Entry> files) {
        if (files.isEmpty()) return "";
        String root = parent(files.get(0).path);
        for (Entry e : files) {
            while (!root.isEmpty() && !(e.path + "/").startsWith(root + "/")) root = parent(root);
            if (root.isEmpty()) break;
        }
        return root.isEmpty() ? "" : root + "/";
    }

    private static boolean hidden(String path) {
        for (String part : path.split("/")) if (part.startsWith(".") || part.equals("__MACOSX")) return true;
        return false;
    }

    private static boolean inAttachments(String path) {
        for (String part : path.split("/")) {
            String p = part.toLowerCase(Locale.ROOT);
            if (p.equals("attachments") || p.equals("_resources") || p.equals("assets")) return true;
        }
        return false;
    }

    static String safe(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^\\.+", "").replaceAll("[. ]+$", "");
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "Untitled" : s;
    }

    private static String kindName(String type) {
        switch (type == null ? "" : type) {
            case NoteData.LIST: return "List";
            case NoteData.JOURNAL: return "Journal";
            case NoteData.MINDMAP: return "Mind map";
            case NoteData.SKETCH: return "Sketch";
            case NoteData.AUDIO: return "Recording";
            default: return "Text";
        }
    }

    private static int colorIndex(String name) {
        if (name == null) return 0;
        for (int i = 1; i < COLOR_NAMES.length; i++) if (COLOR_NAMES[i].equalsIgnoreCase(name.trim()) || (name.trim().equalsIgnoreCase("gray") && i == 6)) return i;
        return 0;
    }

    private static String iso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String isoDay(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(millis));
    }

    /** A date in front matter ("2026-11-03T13:42:00Z", "2026-11-03 13:42", "2026-11-03"), or 0. */
    static long date(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        String[][] forms = {{"yyyy-MM-dd'T'HH:mm:ss'Z'", "UTC"}, {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "UTC"}, {"yyyy-MM-dd'T'HH:mm:ssXXX", null},
                {"yyyy-MM-dd'T'HH:mm:ss", null}, {"yyyy-MM-dd HH:mm:ss", null}, {"yyyy-MM-dd HH:mm", null}, {"yyyy-MM-dd", null}};
        for (String[] f : forms) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f[0], Locale.ROOT);
                p.setLenient(false);
                if (f[1] != null) p.setTimeZone(TimeZone.getTimeZone(f[1]));
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                Date d = p.parse(s, pos);
                if (d != null && pos.getIndex() == s.length()) return d.getTime();
            } catch (Exception ignored) {
                // The next form.
            }
        }
        return 0;
    }

    private static String quote(String s) {
        return s.matches("[\\p{L}\\p{N} _.,()'&!-]*") && !s.isEmpty() && !s.startsWith(" ") ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
            if (s.contains("\\")) s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String p : path.replace('\\', '/').split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else out.add(p);
        }
        return join("/", out);
    }

    static String base(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String ext(String path) {
        String b = base(path);
        int dot = b.lastIndexOf('.');
        return dot < 0 ? "" : b.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // Android 7 has no String.join, String.repeat or java.util.Base64: these stand in, the same everywhere.
    private static String join(String sep, Iterable<String> parts) {
        StringBuilder s = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (!first) s.append(sep);
            s.append(p);
            first = false;
        }
        return s.toString();
    }

    private static String repeat(String s, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(s);
        return out.toString();
    }

    /** Standard base64 (data: URIs), spaces ignored; throws on anything else. */
    static byte[] base64(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4);
        int buffer = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (Character.isWhitespace(c)) continue;
            int v = alphabet.indexOf(c == '-' ? '+' : c == '_' ? '/' : c);
            if (v < 0) throw new IllegalArgumentException("Not base64");
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
