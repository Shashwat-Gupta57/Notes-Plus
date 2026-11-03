package org.flexstudios.notes.plus;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Templates: a note of my own with {@code template} set, kept off the grid except under the
 * Templates filter, and offered in the New sheet. It syncs like any of my notes (the flag travels
 * only in {@code users/{uid}/notes}). Text, list, journal and mind map notes can be templates: their
 * substance is words and structure. Pictures stay with the note they were added to.
 *
 * The desktop's Templates.java and the web's templates.js make the same fresh copy.
 */
final class Templates {

    private Templates() {}

    private static final java.util.regex.Pattern EMBED = java.util.regex.Pattern.compile("^!\\[\\[[^\\]/\\\\]+\\]\\]$");

    static boolean kindAllowed(String type) {
        return NoteData.TEXT.equals(type) || NoteData.LIST.equals(type)
                || NoteData.JOURNAL.equals(type) || NoteData.MINDMAP.equals(type);
    }

    /** Whether "Save as template" is offered: my own, unlocked, words-and-structure note. */
    static boolean canSave(NoteEntity n) {
        return !n.isTemplate() && !n.isReceived() && n.getGroupId() == null
                && kindAllowed(n.getType()) && !NoteLock.isLocked(n.getData());
    }

    /**
     * What a note starts as, from a template (and what a template keeps of a note): {content, data}.
     * Ticks cleared, one-off days dropped, repeating items on their first day from {@code today},
     * a journal on {@code dayStart} with no mood, and no pictures anywhere.
     */
    static String[] fresh(String type, String content, String data, long today, long dayStart) {
        content = content == null ? "" : content;
        if (NoteData.LIST.equals(type)) {
            List<NoteData.TodoItem> items = new ArrayList<>();
            StringBuilder words = new StringBuilder();
            for (NoteData.TodoItem item : NoteData.todos(data)) {
                if (item.picture() != null) continue;
                item.done = false;
                item.undoDue = null;
                if (Due.repeats(item)) {
                    item.due = Due.ymd(Due.first(item.repeat, today));
                } else {
                    item.due = item.time = item.repeat = null;
                }
                items.add(item);
                if (!item.text.trim().isEmpty()) words.append(item.text.trim()).append('\n');
            }
            return new String[]{words.toString().trim(), NoteData.todosJson(items, NoteData.wraps(data))};
        }
        if (NoteData.JOURNAL.equals(type)) {
            NoteData.Journal j = NoteData.journal(data, dayStart);
            j.day = dayStart;
            j.mood = "";
            j.media.clear();
            return new String[]{withoutPictures(content), NoteData.journalJson(j)};
        }
        if (NoteData.MINDMAP.equals(type)) {
            NoteData.MindMap map = NoteData.mindMap(data);
            for (NoteData.MindNode n : map.nodes) n.image = null;
            return new String[]{content, NoteData.mindMapJson(map)};
        }
        return new String[]{withoutPictures(content), data};
    }

    /** Text without its picture lines ({@code ![[IMG_….png]]}). */
    static String withoutPictures(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (EMBED.matcher(line.trim()).matches()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** A copy of {@code note} as a template named {@code name}. Off the main thread. */
    static void saveAs(Context context, NoteEntity note, String name) {
        insert(context, note, name, true);
    }

    /** A new note from {@code template}; its id. Off the main thread. */
    static int newFrom(Context context, NoteEntity template) {
        return insert(context, template, template.getTitle(), false);
    }

    private static int insert(Context context, NoteEntity from, String title, boolean template) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        String[] fresh = fresh(from.getType(), from.getContent(), from.getData(), Due.today(), c.getTimeInMillis());
        NoteEntity n = new NoteEntity(title == null ? "" : title.trim(), fresh[0], System.currentTimeMillis());
        n.setType(from.getType());
        n.setData(fresh[1]);
        n.setColor(from.getColor());
        n.setTemplate(template);
        n.setAccount(Account.uid());
        n.setRev(0);
        n.setDirty(true);
        int id = (int) AppDatabase.getInstance(context).noteDao().insert(n);
        NoteSync.schedule(context);
        return id;
    }

    /** My templates in a list of notes, by name. */
    static List<NoteEntity> of(List<NoteEntity> notes) {
        List<NoteEntity> out = new ArrayList<>();
        for (NoteEntity n : notes) if (n.isTemplate() && !n.isDeleted() && !n.isHidden()) out.add(n);
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(name(a), name(b)));
        return out;
    }

    static String name(NoteEntity n) {
        String t = n.getTitle() == null ? "" : n.getTitle().trim();
        return t.isEmpty() ? NoteData.kind(n.getType()).label + " template" : t;
    }
}
