package org.flexstudios.notes.plus;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Note folders: a name on the note ({@link NoteEntity#getFolder}), synced with it. A folder is
 * there while a note is in it; one made here with nothing in it yet is remembered on this
 * phone ({@code note_folders}) until a note goes in or it is removed.
 */
final class NoteFolders {

    private NoteFolders() {}

    private static final String PREF = "note_folders";

    /** Every folder, A to Z: those notes are in, and empty ones made here. */
    static List<String> all(Context context, List<NoteEntity> notes) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (NoteEntity n : notes) if (n.getFolder() != null && !n.getFolder().isEmpty()) names.add(n.getFolder());
        names.addAll(remembered(context));
        return new ArrayList<>(names);
    }

    /** Trimmed, one line, at most 100 characters; null when nothing is left. */
    @Nullable
    static String clean(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() > 100) s = s.substring(0, 100).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> remembered(Context context) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(context).getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
            // Unreadable: none.
        }
        return out;
    }

    private static void store(Context context, List<String> names) {
        prefs(context).edit().putString(PREF, new JSONArray(names).toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return SecurityHelper.getAppPrefs(context);
    }

    static void remember(Context context, String name) {
        List<String> names = remembered(context);
        for (String n : names) if (n.equalsIgnoreCase(name)) return;
        names.add(name);
        store(context, names);
    }

    private static void forget(Context context, String name) {
        List<String> names = remembered(context);
        names.removeIf(n -> n.equalsIgnoreCase(name));
        store(context, names);
    }

    /** Asks for a new folder's name; {@code then} gets it (already remembered). */
    static void create(Activity activity, java.util.function.Consumer<String> then) {
        Prompts.text(activity, "New folder", null, "Folder name", null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "Create", raw -> {
                    String name = clean(raw);
                    if (name == null) return;
                    remember(activity, name);
                    then.accept(name);
                }, null);
    }

    /** "Move to folder": none, one of {@code folders}, or a new one. */
    static void move(Activity activity, NoteEntity note, List<String> folders) {
        List<String> labels = new ArrayList<>();
        labels.add("No folder");
        labels.addAll(folders);
        labels.add("New folder…");
        int checked = note.getFolder() == null ? 0 : Math.max(0, folders.indexOf(note.getFolder()) + 1);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Move to folder")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    if (which == labels.size() - 1) create(activity, name -> put(activity, note, name));
                    else put(activity, note, which == 0 ? null : folders.get(which - 1));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void put(Context context, NoteEntity note, @Nullable String folder) {
        Context app = context.getApplicationContext();
        AppDatabase.IO.execute(() -> {
            AppDatabase.getInstance(app).noteDao().setFolder(note.getId(), folder);
            NoteSync.schedule(app);
        });
        if (folder != null) forget(context, folder); // It has a note now: no need to remember it.
    }

    /** A folder chip's long press: rename it, or remove it (its notes stay, in no folder). */
    static void manage(Activity activity, String folder, Runnable onChanged) {
        Context app = activity.getApplicationContext();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(folder)
                .setItems(new String[]{"Rename", "Remove folder"}, (d, which) -> {
                    if (which == 0) {
                        Prompts.text(activity, "Rename folder", null, "Folder name", folder,
                                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "Rename", raw -> {
                                    String name = clean(raw);
                                    if (name == null || name.equals(folder)) return;
                                    forget(activity, folder);
                                    remember(activity, name);
                                    AppDatabase.IO.execute(() -> {
                                        AppDatabase.getInstance(app).noteDao().renameFolder(folder, name);
                                        NoteSync.schedule(app);
                                    });
                                    onChanged.run();
                                }, null);
                    } else {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle("Remove “" + folder + "”?")
                                .setMessage("Its notes aren't deleted: they stay, in no folder.")
                                .setPositiveButton("Remove", (d2, w) -> {
                                    forget(activity, folder);
                                    AppDatabase.IO.execute(() -> {
                                        AppDatabase.getInstance(app).noteDao().renameFolder(folder, null);
                                        NoteSync.schedule(app);
                                    });
                                    onChanged.run();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    /** Archive or bring back, with a word about where it went. */
    static void setArchived(Activity activity, NoteEntity note, boolean archived) {
        Context app = activity.getApplicationContext();
        AppDatabase.IO.execute(() -> {
            AppDatabase.getInstance(app).noteDao().setArchived(note.getId(), archived);
            NoteSync.schedule(app);
        });
        Toast.makeText(activity, archived ? "Archived. It's under the Archived filter." : "Back with your notes",
                Toast.LENGTH_SHORT).show();
    }

}
