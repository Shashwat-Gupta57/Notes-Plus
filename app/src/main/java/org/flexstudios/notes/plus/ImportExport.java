package org.flexstudios.notes.plus;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Import and export on the phone: the system's file picker in, a zip of Markdown out, with the work
 * in {@link Interchange}. Notes only: nothing here reads or writes the hidden spaces.
 * Made in an activity's onCreate (it registers for picker results).
 */
final class ImportExport {

    /** Per file and in all: a Takeout zip is mostly photos, and the phone holds it in memory to read it. */
    private static final long MAX_FILE = 25L << 20, MAX_TOTAL = 220L << 20;

    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String[]> pick;
    private final ActivityResultLauncher<String> save;

    ImportExport(AppCompatActivity activity) {
        this.activity = activity;
        pick = activity.registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::picked);
        save = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::save);
    }

    // ================================================================== import

    void chooseImport() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Import notes")
                .setMessage("Pick what you exported from another app:\n\n"
                        + "• Google Keep: the zip from Google Takeout\n"
                        + "• Apple Notes: the exported notes (.html or .txt), or a zip of them\n"
                        + "• Markdown: .md files, or a zip of a folder (Obsidian, Bear, Joplin…)\n"
                        + "• A Notes+ export\n\n"
                        + "You'll see what was found before anything is added.")
                .setPositiveButton("Choose files", (d, w) -> pick.launch(new String[]{"application/zip", "application/x-zip-compressed",
                        "application/json", "text/*", "application/octet-stream"}))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void picked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        AlertDialog progress = Prompts.progress(activity, "Reading files…");
        AppDatabase.IO.execute(() -> {
            List<Interchange.Entry> entries = new ArrayList<>();
            int[] tooBig = {0};
            String failure = null;
            try {
                read(uris, entries, tooBig);
            } catch (Exception e) {
                failure = e.getMessage();
            }
            Interchange.Found found = Interchange.read(entries, namer(), System.currentTimeMillis());
            Set<String> here = new HashSet<>();
            for (NoteEntity n : AppDatabase.getInstance(activity).noteDao().getAllDirect()) {
                here.add(Interchange.sameKey(n.getTitle(), n.getContent()));
            }
            List<Interchange.Note> fresh = new ArrayList<>();
            for (Interchange.Note n : found.notes) if (here.add(Interchange.sameKey(n.title, n.content))) fresh.add(n);
            int skipped = found.notes.size() - fresh.size();
            String error = failure;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                if (fresh.isEmpty()) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(found.notes.isEmpty() ? "No notes found" : "Already here")
                            .setMessage(found.notes.isEmpty()
                                    ? (error != null ? "Couldn't read that: " + error + "\n\n" : "")
                                    + "Notes+ reads Google Keep (Takeout), Apple Notes (.html, .txt) and Markdown (.md) files, or a zip of them."
                                    : "Every note in what you picked is already in Notes+.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                String message = found.summary()
                        + (skipped > 0 ? "\n\n" + skipped + (skipped == 1 ? " is" : " are") + " already here and will be skipped." : "")
                        + (tooBig[0] > 0 ? "\n\n" + tooBig[0] + (tooBig[0] == 1 ? " file was" : " files were") + " too big to bring in on a phone." : "");
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Import " + fresh.size() + (fresh.size() == 1 ? " note?" : " notes?"))
                        .setMessage(message)
                        .setPositiveButton("Import", (d, w) -> add(fresh))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void read(List<Uri> uris, List<Interchange.Entry> entries, int[] tooBig) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        long total = 0;
        for (Uri uri : uris) {
            String name = displayName(uri);
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) continue;
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    ZipInputStream zip = new ZipInputStream(in);
                    for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                        if (e.isDirectory()) continue;
                        byte[] bytes = bytes(zip, MAX_FILE);
                        if (bytes == null || total + bytes.length > MAX_TOTAL) {
                            tooBig[0]++;
                            continue;
                        }
                        total += bytes.length;
                        entries.add(new Interchange.Entry(e.getName(), bytes));
                    }
                } else {
                    byte[] bytes = bytes(in, MAX_FILE);
                    if (bytes == null) {
                        tooBig[0]++;
                        continue;
                    }
                    total += bytes.length;
                    entries.add(new Interchange.Entry(name, bytes));
                }
            }
        }
    }

    /** Adds the notes, with their pictures, then lets them sync. */
    private void add(List<Interchange.Note> notes) {
        AlertDialog progress = Prompts.progress(activity, "Importing " + notes.size() + (notes.size() == 1 ? " note…" : " notes…"));
        AppDatabase.IO.execute(() -> {
            NoteDao dao = AppDatabase.getInstance(activity).noteDao();
            int added = 0;
            for (Interchange.Note n : notes) {
                NoteEntity e = new NoteEntity(n.title, n.content, n.edited > 0 ? n.edited : System.currentTimeMillis());
                e.setType(n.type);
                e.setData(n.data);
                e.setColor(n.color);
                e.setPinned(n.pinned);
                e.setArchived(n.archived);
                e.setTemplate(n.template);
                e.setFolder(n.folder);
                e.setAccount(Account.uid());
                e.setRev(0);
                e.setDirty(true);
                int id = (int) dao.insert(e);
                if (!n.files.isEmpty()) {
                    File dir = NoteData.dir(activity, id);
                    dir.mkdirs();
                    for (Map.Entry<String, byte[]> f : n.files.entrySet()) {
                        try (OutputStream out = new FileOutputStream(new File(dir, f.getKey()))) {
                            out.write(f.getValue());
                        } catch (Exception ignored) {
                            // The note is there; a picture it couldn't keep shows as missing.
                        }
                    }
                }
                added++;
            }
            int done = added;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                Toast.makeText(activity, "Imported " + done + (done == 1 ? " note" : " notes"), Toast.LENGTH_LONG).show();
                NoteSync.schedule(activity);
            });
        });
    }

    /** "IMG_<millis>_<8 hex>.<ext>", like every picture the apps make, never the same twice. */
    private static Interchange.Namer namer() {
        SecureRandom random = new SecureRandom();
        Set<String> used = new HashSet<>();
        return (prefix, ext) -> {
            String name;
            do {
                name = String.format(Locale.ROOT, "%s_%d_%08x.%s", prefix, System.currentTimeMillis(), random.nextInt(), ext);
            } while (!used.add(name));
            return name;
        };
    }

    // ================================================================== export

    void chooseExport() {
        save.launch("Notes+ " + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".zip");
    }

    private void save(Uri uri) {
        if (uri == null) return;
        AlertDialog progress = Prompts.progress(activity, "Exporting notes…");
        AppDatabase.IO.execute(() -> {
            String message;
            try {
                List<Interchange.Note> notes = new ArrayList<>();
                int locked = 0;
                for (NoteEntity e : AppDatabase.getInstance(activity).noteDao().getAllDirect()) {
                    Interchange.Note n = new Interchange.Note();
                    n.type = e.getType();
                    n.title = e.getTitle() == null ? "" : e.getTitle();
                    n.content = e.getContent() == null ? "" : e.getContent();
                    n.data = e.getData();
                    n.color = e.getColor();
                    n.pinned = e.isPinned();
                    n.archived = e.isArchived();
                    n.template = e.isTemplate();
                    n.folder = e.getFolder();
                    n.edited = e.getTimestamp();
                    n.locked = NoteLock.isLocked(e.getData());
                    if (n.locked) locked++;
                    n.key = e.getId();
                    notes.add(n);
                }
                String root = "Notes+ " + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
                List<Interchange.Entry> files = Interchange.write(notes, (n, name) -> {
                    File f = new File(NoteData.dir(activity, (Integer) n.key), name);
                    if (!f.isFile()) return null;
                    try (InputStream in = new FileInputStream(f)) {
                        return bytes(in, Long.MAX_VALUE);
                    } catch (Exception ex) {
                        return null;
                    }
                }, root);
                try (OutputStream raw = activity.getContentResolver().openOutputStream(uri);
                     ZipOutputStream zip = new ZipOutputStream(raw)) {
                    for (Interchange.Entry f : files) {
                        zip.putNextEntry(new ZipEntry(f.path));
                        zip.write(f.bytes);
                        zip.closeEntry();
                    }
                }
                int exported = notes.size() - locked;
                message = "Exported " + exported + (exported == 1 ? " note" : " notes")
                        + (locked > 0 ? ". " + locked + " locked " + (locked == 1 ? "note was" : "notes were") + " left out." : "");
            } catch (Exception e) {
                message = "Couldn't export: " + e.getMessage();
            }
            String shown = message;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                Toast.makeText(activity, shown, Toast.LENGTH_LONG).show();
            });
        });
    }

    // ================================================================= helpers

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) return name;
            }
        } catch (Exception ignored) {
            // The path's last part, then.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last.substring(last.lastIndexOf('/') + 1);
    }

    /** All of a stream, or null when it's over {@code max} bytes. */
    private static byte[] bytes(InputStream in, long max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        for (int r; (r = in.read(buf)) > 0; ) {
            total += r;
            if (total > max) {
                // Read past it, so the next zip entry starts in the right place.
                while (in.read(buf) > 0) { /* skip */ }
                return null;
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
