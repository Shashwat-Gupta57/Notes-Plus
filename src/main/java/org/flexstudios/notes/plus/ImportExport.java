package org.flexstudios.notes.plus;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Import and export on the desktop: files, a folder or a zip in; a zip of Markdown out. The work is
 * in {@link Interchange}. Notes only: nothing here reads or writes the hidden spaces.
 */
final class ImportExport {

    private ImportExport() {}

    private static final long MAX_FILE = 200L << 20;

    /** What was read, and what was too big to read. */
    private record Picked(List<Interchange.Entry> entries, int tooBig) {}

    // ================================================================== import

    static void chooseImport() {
        Ui.dialog().title("Import notes")
                .message("Choose what you exported from another app:\n\n"
                        + "•  Google Keep: the zip from Google Takeout\n"
                        + "•  Apple Notes: the exported notes (.html or .txt), or their folder\n"
                        + "•  Markdown: .md files, or a whole folder (Obsidian, Bear, Joplin…)\n"
                        + "•  A Notes+ export\n\n"
                        + "You'll see what was found before anything is added.")
                .positive("Choose files", ImportExport::pickFiles)
                .neutral("Choose a folder", ImportExport::pickFolder)
                .negative("Cancel", null).width(500).show();
    }

    private static void pickFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import notes");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Notes (zip, Markdown, text, HTML, Keep JSON)", "*.zip", "*.md", "*.markdown", "*.txt", "*.html", "*.htm", "*.json"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(NotesApp.stage());
        if (files == null || files.isEmpty()) return;
        Ui.toast("Reading…");
        NoteStore.load(s -> {
            List<Interchange.Entry> entries = new ArrayList<>();
            int tooBig = 0;
            for (File f : files) {
                try {
                    if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) tooBig += unzip(f.toPath(), entries);
                    else if (f.length() > MAX_FILE) tooBig++;
                    else entries.add(new Interchange.Entry(f.getName(), Files.readAllBytes(f.toPath())));
                } catch (Exception e) {
                    tooBig++;
                }
            }
            return found(s, new Picked(entries, tooBig));
        }, ImportExport::confirm);
    }

    private static void pickFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Import a folder of notes");
        File dir = chooser.showDialog(NotesApp.stage());
        if (dir == null) return;
        Ui.toast("Reading…");
        NoteStore.load(s -> {
            List<Interchange.Entry> entries = new ArrayList<>();
            int tooBig = 0;
            Path root = dir.toPath();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    try {
                        if (Files.size(p) > MAX_FILE) tooBig++;
                        // The chosen folder's name stays in the path: what's under it is filed by its subfolders.
                        else entries.add(new Interchange.Entry(dir.getName() + "/" + root.relativize(p).toString(), Files.readAllBytes(p)));
                    } catch (Exception e) {
                        tooBig++;
                    }
                }
            } catch (Exception e) {
                // Whatever was read.
            }
            return found(s, new Picked(entries, tooBig));
        }, ImportExport::confirm);
    }

    private static int unzip(Path zipFile, List<Interchange.Entry> entries) throws Exception {
        int tooBig = 0;
        try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                byte[] bytes = zip.readNBytes((int) MAX_FILE + 1);
                if (bytes.length > MAX_FILE) {
                    tooBig++;
                    continue;
                }
                entries.add(new Interchange.Entry(e.getName(), bytes));
            }
        }
        return tooBig;
    }

    /** What was found, and which of it is new here. */
    private record Result(Interchange.Found found, List<Interchange.Note> fresh, int tooBig) {}

    private static Result found(NoteStore s, Picked picked) {
        Interchange.Found found = Interchange.read(picked.entries(), namer(), System.currentTimeMillis());
        Set<String> here = new HashSet<>();
        for (NoteEntity n : s.all()) here.add(Interchange.sameKey(n.getTitle(), n.getContent()));
        List<Interchange.Note> fresh = new ArrayList<>();
        for (Interchange.Note n : found.notes) if (here.add(Interchange.sameKey(n.title, n.content))) fresh.add(n);
        return new Result(found, fresh, picked.tooBig());
    }

    private static void confirm(Result r) {
        if (r.fresh().isEmpty()) {
            Ui.dialog().title(r.found().notes.isEmpty() ? "No notes found" : "Already here")
                    .message(r.found().notes.isEmpty()
                            ? "Notes+ reads Google Keep (Takeout), Apple Notes (.html, .txt) and Markdown (.md) files, folders of them, or a zip."
                            : "Every note in what you chose is already in Notes+.")
                    .positive("OK", null).show();
            return;
        }
        int skipped = r.found().notes.size() - r.fresh().size();
        String message = r.found().summary()
                + (skipped > 0 ? "\n\n" + skipped + (skipped == 1 ? " is" : " are") + " already here and will be skipped." : "")
                + (r.tooBig() > 0 ? "\n\n" + r.tooBig() + (r.tooBig() == 1 ? " file couldn't" : " files couldn't") + " be read." : "");
        int n = r.fresh().size();
        Ui.dialog().title("Import " + n + (n == 1 ? " note?" : " notes?")).message(message)
                .positive("Import", () -> add(r.fresh())).negative("Cancel", null).width(500).show();
    }

    private static void add(List<Interchange.Note> notes) {
        NoteStore.load(s -> {
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
                int id = s.insert(e);
                for (Map.Entry<String, byte[]> f : n.files.entrySet()) {
                    try {
                        Files.write(NoteData.dir(id).resolve(f.getKey()), f.getValue());
                    } catch (Exception ignored) {
                        // The note is there; a picture it couldn't keep shows as missing.
                    }
                }
            }
            return notes.size();
        }, added -> {
            Ui.toast("Imported " + added + (added == 1 ? " note" : " notes"));
            NoteSync.schedule();
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

    static void chooseExport() {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export all notes");
        chooser.setInitialFileName("Notes+ " + day + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip of Markdown", "*.zip"));
        File file = chooser.showSaveDialog(NotesApp.stage());
        if (file == null) return;
        Ui.toast("Exporting…");
        NoteStore.load(s -> {
            List<Interchange.Note> notes = new ArrayList<>();
            int locked = 0;
            for (NoteEntity e : s.all()) {
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
            try {
                List<Interchange.Entry> files = Interchange.write(notes, (n, name) -> {
                    Path p = Storage.notesDir().resolve(String.valueOf(n.key)).resolve(name);
                    try {
                        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
                    } catch (Exception ex) {
                        return null;
                    }
                }, "Notes+ " + day);
                try (OutputStream out = Files.newOutputStream(file.toPath()); ZipOutputStream zip = new ZipOutputStream(out)) {
                    for (Interchange.Entry f : files) {
                        zip.putNextEntry(new ZipEntry(f.path));
                        zip.write(f.bytes);
                        zip.closeEntry();
                    }
                }
                int exported = notes.size() - locked;
                return "Exported " + exported + (exported == 1 ? " note" : " notes")
                        + (locked > 0 ? ". " + locked + " locked " + (locked == 1 ? "note was" : "notes were") + " left out." : "");
            } catch (Exception e) {
                return "Couldn't export: " + e.getMessage();
            }
        }, Ui::toast);
    }
}
