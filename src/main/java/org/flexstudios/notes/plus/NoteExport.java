package org.flexstudios.notes.plus;

import javafx.stage.FileChooser;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A note as one file ({@code .npnote}) to keep anywhere, a USB stick included: its text and
 * data, title, colour and pin, every picture and recording, and its lock if it has one (a
 * locked note stays encrypted inside). Opening the file in Notes+ brings the note back as it
 * was, as a new note, so it never overwrites the one it came from.
 *
 * <pre>
 * a zip:  note.json   {format, type, title, content, data, color, pinned, edited}
 *         files/…     the note's folder (pictures, recordings, sketch.json, sketch.png)
 * </pre>
 */
final class NoteExport {

    private NoteExport() {}

    static final String EXTENSION = ".npnote";
    private static final int FORMAT = 1;

    /** Asks where, then writes the note as stored (a locked note's text stays sealed). */
    static void export(int noteId) {
        NoteStore.load(s -> s.byId(noteId), n -> {
            if (n == null) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export note");
            String name = n.getTitle() == null || n.getTitle().isBlank() ? NoteData.kind(n.getType()).label() : n.getTitle();
            chooser.setInitialFileName(name.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + EXTENSION);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Notes+ note", "*" + EXTENSION));
            File target = chooser.showSaveDialog(NotesApp.stage());
            if (target == null) return;
            NoteStore.load(s -> {
                try {
                    write(n, target.toPath());
                    return null;
                } catch (IOException e) {
                    return e.getMessage() == null ? "Couldn't write it" : e.getMessage();
                }
            }, error -> Ui.toast(error == null ? "Exported: " + target.getName() + ". Open it in Notes+ to bring it back." : error));
        });
    }

    private static void write(NoteEntity n, Path target) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part))) {
            JSONObject o = new JSONObject().put("format", FORMAT).put("type", n.getType())
                    .put("title", n.getTitle() == null ? "" : n.getTitle()).put("content", n.getContent() == null ? "" : n.getContent())
                    .put("color", n.getColor()).put("pinned", n.isPinned()).put("edited", n.getTimestamp());
            if (n.getData() != null) o.put("data", n.getData());
            zip.putNextEntry(new ZipEntry("note.json"));
            zip.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            Path dir = Storage.notesDir().resolve(String.valueOf(n.getId()));
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        String name = f.getFileName().toString();
                        if (name.endsWith(".part")) continue;
                        zip.putNextEntry(new ZipEntry("files/" + name));
                        Files.copy(f, zip);
                        zip.closeEntry();
                    }
                }
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Brings an exported note back as a new note, and opens it. */
    static void importFile(Path file) {
        NoteStore.load(s -> {
            try {
                return read(s, file);
            } catch (Exception e) {
                System.getLogger("NoteExport").log(System.Logger.Level.WARNING, "Couldn't import " + file, e);
                return null;
            }
        }, n -> {
            if (n == null) {
                Ui.dialog().title("Couldn't open it").message(file.getFileName() + " isn't a Notes+ note, or it's damaged.")
                        .positive("OK", null).show();
                return;
            }
            NoteStore.get().notifyChanged();
            NoteSync.schedule();
            Ui.toast("Added to your notes");
            NotesApp.open(NoteData.editor(n.getType(), n.getId()));
        });
    }

    private static NoteEntity read(NoteStore s, Path file) throws Exception {
        JSONObject o = null;
        Path staging = Files.createTempDirectory("npnote");
        try {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.equals("note.json")) {
                        o = new JSONObject(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    } else if (name.startsWith("files/") && !e.isDirectory()) {
                        String base = name.substring("files/".length());
                        if (!NoteSync.safeName(base)) continue;
                        try (OutputStream out = Files.newOutputStream(staging.resolve(base))) {
                            zip.transferTo(out);
                        }
                    }
                }
            }
            if (o == null) return null;
            String type = o.optString("type", NoteData.TEXT);
            NoteEntity n = new NoteEntity(o.optString("title", ""), o.optString("content", ""),
                    o.optLong("edited", System.currentTimeMillis()));
            n.setType(type.matches("TEXT|LIST|MINDMAP|JOURNAL|SKETCH|AUDIO") ? type : NoteData.TEXT);
            n.setData(o.has("data") ? o.optString("data") : null);
            n.setColor(o.optInt("color"));
            n.setPinned(o.optBoolean("pinned"));
            n.setAccount(Account.uid());
            n.setDirty(true);
            n.setId(s.insert(n));
            Path dir = NoteData.dir(n.getId());
            try (Stream<Path> files = Files.list(staging)) {
                for (Path f : files.toList()) Files.move(f, dir.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
            return n;
        } finally {
            try (Stream<Path> left = Files.walk(staging)) {
                for (Path p : left.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    static boolean isExport(Path p) {
        return p != null && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(EXTENSION);
    }
}
