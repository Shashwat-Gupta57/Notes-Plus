package org.flexstudios.notes.plus;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Note folders, as on the phone: a name on the note ({@link NoteEntity#getFolder}), synced with
 * it. A folder is there while a note is in it; one made here with nothing in it yet is
 * remembered on this computer ({@code note_folders}) until a note goes in or it is removed.
 */
final class NoteFolders {

    private NoteFolders() {}

    private static final String PREF = "note_folders";

    /** Every folder, A to Z: those notes are in, and empty ones made here. */
    static List<String> all(List<NoteEntity> notes) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (NoteEntity n : notes) if (n.getFolder() != null && !n.getFolder().isEmpty()) names.add(n.getFolder());
        names.addAll(remembered());
        return new ArrayList<>(names);
    }

    /** Trimmed, one line, at most 100 characters; null when nothing is left. */
    static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() > 100) s = s.substring(0, 100).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> remembered() {
        List<String> out = new ArrayList<>();
        try {
            String raw = Prefs.get(PREF);
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
            // Unreadable: none.
        }
        return out;
    }

    private static void remember(String name) {
        List<String> names = remembered();
        for (String n : names) if (n.equalsIgnoreCase(name)) return;
        names.add(name);
        Prefs.put(PREF, new JSONArray(names).toString());
    }

    private static void forget(String name) {
        List<String> names = remembered();
        names.removeIf(n -> n.equalsIgnoreCase(name));
        Prefs.put(PREF, new JSONArray(names).toString());
    }

    /** Asks for a new folder's name; {@code then} gets it (already remembered). */
    static void create(Consumer<String> then) {
        Ui.prompt("New folder", "Folder name", "", "Create", raw -> {
            String name = clean(raw);
            if (name == null) return;
            remember(name);
            then.accept(name);
        });
    }

    /** "Move to folder": none, one of {@code folders}, or a new one. */
    static void move(NoteEntity note, List<String> folders) {
        String[] labels = new String[folders.size() + 2];
        labels[0] = "No folder";
        for (int i = 0; i < folders.size(); i++) labels[i + 1] = folders.get(i);
        labels[labels.length - 1] = "New folder…";
        int checked = note.getFolder() == null ? 0 : Math.max(0, folders.indexOf(note.getFolder()) + 1);
        Ui.dialog().title("Move to folder").singleChoice(labels, checked, which -> {
            if (which == labels.length - 1) create(name -> put(note, name));
            else put(note, which == 0 ? null : folders.get(which - 1));
        }).show();
    }

    private static void put(NoteEntity note, String folder) {
        if (folder != null) forget(folder); // It has a note now: no need to remember it.
        NoteStore.write(s -> {
            s.setFolder(note.getId(), folder);
            NoteSync.schedule();
        });
    }

    /** A folder chip's right-click: rename it, or remove it (its notes stay, in no folder). */
    static void manage(String folder, Runnable onChanged) {
        Ui.dialog().title(folder).items(List.of("Rename", "Remove folder"), List.of(
                () -> Ui.prompt("Rename folder", "Folder name", folder, "Rename", raw -> {
                    String name = clean(raw);
                    if (name == null || name.equals(folder)) return;
                    forget(folder);
                    remember(name);
                    NoteStore.write(s -> {
                        s.renameFolder(folder, name);
                        NoteSync.schedule();
                    });
                    onChanged.run();
                }),
                () -> Ui.dialog().title("Remove “" + folder + "”?")
                        .message("Its notes aren't deleted: they stay, in no folder.")
                        .positive("Remove", () -> {
                            forget(folder);
                            NoteStore.write(s -> {
                                s.renameFolder(folder, null);
                                NoteSync.schedule();
                            });
                            onChanged.run();
                        })
                        .negative("Cancel", null).show())).show();
    }

    /** Archive or bring back, with a word about where it went. */
    static void setArchived(NoteEntity note, boolean archived) {
        NoteStore.write(s -> {
            s.setArchived(note.getId(), archived);
            NoteSync.schedule();
        });
        Ui.toast(archived ? "Archived. It's under the Archived filter." : "Back with your notes");
    }
}
