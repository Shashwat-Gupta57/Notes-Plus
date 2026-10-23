package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Editor tab: Notepad in Notes+ clothes. New, open, save, save as, find and replace,
 * word wrap and zoom, with the line, column and file form in a status bar. Files keep their
 * charset, BOM and line endings.
 *
 * "Save to notes" turns the file into a text note linked to it ({@link FileLinks}): from then
 * on the note can have a colour, pictures and a lock, sync to the phone, and every change on
 * either side reaches the other. A linked file shows "Open note" instead; a locked note's file
 * (armoured) isn't edited here at all, only opened as its note.
 */
final class TextEditorView {

    private final BorderPane root = new BorderPane();
    private final TextArea area = new TextArea();
    private final Label fileName = new Label("Untitled");
    private final Label filePath = new Label();
    private final Label dirtyDot = new Label("●");
    private final Label status = new Label();
    private final Button notesButton = new Button();
    private final StackPane center = new StackPane();
    private final VBox findBar = new VBox(6);
    private final TextField findField = new TextField(), replaceField = new TextField();
    private final Label findCount = new Label();

    private Path file;
    private FileLinks.Text form;
    private String savedText = "";
    private boolean wrap = true;
    private double zoom = 1;
    private boolean loading;

    TextEditorView() {
        root.getStyleClass().add("text-editor");

        // ------------------------------------------------------------- top bar
        fileName.getStyleClass().add("editor-file-name");
        filePath.getStyleClass().add("secondary");
        filePath.setStyle("-fx-font-size: 12px;");
        dirtyDot.setStyle("-fx-text-fill: " + Theme.ref("brand_yellow") + "; -fx-font-size: 12px;");
        dirtyDot.setVisible(false);
        HBox nameRow = new HBox(8, fileName, dirtyDot);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        VBox names = new VBox(1, nameRow, filePath);
        names.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(names, Priority.ALWAYS);
        names.setMaxWidth(Double.MAX_VALUE);

        notesButton.getStyleClass().setAll("filled-button", "small");
        notesButton.setOnAction(e -> notesAction());

        Button more = Ui.iconButton(Icons.MORE, "text_primary", "More", () -> {});
        more.setOnAction(e -> moreMenu(more));
        HBox bar = new HBox(4,
                tool(Icons.FILE_NEW, "New (Ctrl+N)", this::newFile),
                tool(Icons.FOLDER_OPEN, "Open (Ctrl+O)", this::open),
                tool(Icons.FLOPPY, "Save (Ctrl+S)", () -> save(null)),
                gap(10), names, notesButton, more);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("editor-bar");

        // ---------------------------------------------------------------- page
        area.getStyleClass().add("editor-area");
        area.setWrapText(true);
        area.textProperty().addListener((o, a, b) -> {
            if (!loading) showDirty();
            updateStatus();
        });
        area.caretPositionProperty().addListener((o, a, b) -> updateStatus());
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown()) {
                e.consume();
                zoom(e.getDeltaY() > 0 ? 0.1 : -0.1);
            }
        });
        StackPane page = new StackPane(area);
        page.getStyleClass().add("editor-page");
        center.getChildren().setAll(page);
        // Drop a file on the page to open it.
        center.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
        });
        center.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().getFiles();
            if (!files.isEmpty()) confirmLeave(() -> load(files.get(0).toPath()));
            e.setDropCompleted(true);
        });

        // ------------------------------------------------------------ find bar
        findField.setPromptText("Find");
        replaceField.setPromptText("Replace with");
        for (TextField f : new TextField[]{findField, replaceField}) {
            f.getStyleClass().setAll("text-input", "text-field", "search-field");
            HBox.setHgrow(f, Priority.ALWAYS);
        }
        findField.textProperty().addListener((o, a, b) -> countMatches());
        findField.setOnAction(e -> find(true));
        findCount.getStyleClass().add("secondary");
        findCount.setMinWidth(70);
        HBox findRow = new HBox(6, findField, findCount,
                Ui.iconButton(Icons.ARROW_BACK, 36, 18, "text_secondary", "Previous (Shift+F3)", () -> find(false)),
                Ui.textButton("Next", () -> find(true)),
                Ui.iconButton(Icons.CLOSE, 36, 18, "text_secondary", "Close (Esc)", this::closeFind));
        HBox replaceRow = new HBox(6, replaceField, Ui.textButton("Replace", this::replaceOne), Ui.textButton("Replace all", this::replaceAll));
        findRow.setAlignment(Pos.CENTER_LEFT);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        findBar.getChildren().setAll(findRow, replaceRow);
        findBar.getStyleClass().add("editor-find");
        findBar.setVisible(false);
        findBar.setManaged(false);

        // ------------------------------------------------------------- status
        status.getStyleClass().add("secondary");
        status.setStyle("-fx-font-size: 12px;");
        HBox statusBar = new HBox(status);
        statusBar.getStyleClass().add("editor-status");

        root.setTop(new VBox(bar, findBar));
        root.setCenter(center);
        root.setBottom(statusBar);

        FileLinks.onFileWritten = this::fileRewritten;
        newDocument();
    }

    Node view() {
        return root;
    }

    void focus() {
        Platform.runLater(area::requestFocus);
    }

    /** Keys while the Editor is showing. True when handled. */
    boolean onKey(KeyEvent e) {
        if (e.isShortcutDown() && e.getCode() == KeyCode.S) {
            save(e.isShiftDown() ? Boolean.TRUE : null);
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.O) {
            open();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.N) {
            newFile();
        } else if (e.isShortcutDown() && (e.getCode() == KeyCode.F || e.getCode() == KeyCode.H)) {
            openFind(e.getCode() == KeyCode.H);
        } else if (e.getCode() == KeyCode.F3) {
            find(!e.isShiftDown());
        } else if (e.getCode() == KeyCode.ESCAPE && findBar.isVisible()) {
            closeFind();
        } else if (e.isShortcutDown() && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.ADD)) {
            zoom(0.1);
        } else if (e.isShortcutDown() && (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT)) {
            zoom(-0.1);
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.DIGIT0) {
            zoom(1 - zoom);
        } else {
            return false;
        }
        e.consume();
        return true;
    }

    // ================================================================= files

    boolean dirty() {
        return !FileLinks.normalize(area.getText()).equals(savedText);
    }

    /** Forgets unsaved changes (the window is closing without them). */
    void discard() {
        savedText = FileLinks.normalize(area.getText());
        file = file == null && savedText.isEmpty() ? null : file;
        showDirty();
    }

    private void newFile() {
        confirmLeave(this::newDocument);
    }

    private void newDocument() {
        file = null;
        form = null;
        setText("");
        savedText = "";
        showFile();
    }

    private void open() {
        confirmLeave(() -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Open");
            chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Text files", "*.txt"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            if (file != null && file.getParent() != null) chooser.setInitialDirectory(file.getParent().toFile());
            File f = chooser.showOpenDialog(NotesApp.stage());
            if (f != null) load(f.toPath());
        });
    }

    /** Opens a file here (double-clicked in Explorer, dropped, or picked). */
    void openPath(Path path) {
        confirmLeave(() -> load(path));
    }

    private void load(Path path) {
        try {
            FileLinks.Text t = FileLinks.read(path);
            String text = FileLinks.normalize(t.text());
            if (FileLinks.isArmored(text)) {
                showLockedFile(path, text);
                return;
            }
            file = path;
            form = t;
            setText(text);
            savedText = text;
            showFile();
            focus();
        } catch (Exception e) {
            Ui.dialog().title("Couldn't open it").message(path + "\n\n" + e.getMessage()).positive("OK", null).show();
        }
    }

    /** {@code as}: TRUE for Save as; null saves in place (asking for a name if it has none). */
    private boolean save(Boolean as) {
        Path target = file;
        if (target == null || Boolean.TRUE.equals(as)) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(Boolean.TRUE.equals(as) ? "Save as" : "Save");
            chooser.setInitialFileName(file == null ? "Untitled.txt" : file.getFileName().toString());
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
            if (file != null && file.getParent() != null) chooser.setInitialDirectory(file.getParent().toFile());
            File f = chooser.showSaveDialog(NotesApp.stage());
            if (f == null) return false;
            target = f.toPath();
        }
        try {
            String text = FileLinks.normalize(area.getText());
            FileLinks.write(target, text, target.equals(file) ? form : null);
            form = FileLinks.read(target);
            file = target;
            savedText = text;
            showFile();
            // A linked file: its note takes the edit (and the phone gets it).
            if (FileLinks.noteFor(file) != null) FileLinks.syncLater();
            Ui.toast("Saved");
            return true;
        } catch (Exception e) {
            Ui.dialog().title("Couldn't save").message(e.getMessage()).positive("OK", null).show();
            return false;
        }
    }

    /** Unsaved changes: save, don't, or stay. {@code then} runs unless they stay. */
    void confirmLeave(Runnable then) {
        if (!dirty()) {
            then.run();
            return;
        }
        Ui.dialog().title("Save changes to " + displayName() + "?")
                .message("Your changes are lost if you don't save them.")
                .positive("Save", () -> {
                    if (save(null)) then.run();
                })
                .neutral("Don't save", then)
                .negative("Cancel", null).width(440).show();
    }

    private String displayName() {
        return file == null ? "Untitled" : file.getFileName().toString();
    }

    /** A linked file was rewritten from its note (a phone edit, say): shown here if untouched. */
    private void fileRewritten(Path p) {
        if (file == null || !p.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) return;
        if (dirty()) return; // Their unsaved edit wins when saved; the note takes it then.
        load(p);
    }

    // ================================================================= notes

    private void notesAction() {
        String uuid = file == null ? null : FileLinks.noteFor(file);
        if (uuid != null) {
            openNote(uuid);
            return;
        }
        if (file == null || dirty()) {
            // A note links to a file, so the text is saved to one first.
            if (!save(null)) return;
        }
        String text = FileLinks.normalize(area.getText());
        Path linked = file;
        String name = linked.getFileName().toString();
        String suggested = name.toLowerCase(Locale.ROOT).endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        Ui.prompt("Add to notes", "A title for the note, like any other. The file keeps only its text.", "Title",
                suggested, "Add", title -> addAsNote(title.trim(), text, linked), null, null, null);
    }

    private void addAsNote(String title, String text, Path linked) {
        NoteStore.load(s -> {
            NoteEntity n = new NoteEntity(title, text, System.currentTimeMillis());
            n.setType(NoteData.TEXT);
            n.setAccount(Account.uid());
            n.setId(s.insert(n));
            FileLinks.link(n.getUuid(), linked, text);
            return n;
        }, n -> {
            NoteStore.get().notifyChanged();
            NoteSync.schedule();
            showFile();
            Ui.snackbar("Added to Notes. Changes on any device now reach this file.", Ui.LONG, "Open note",
                    () -> openNote(n.getUuid()), null);
        });
    }

    private void openNote(String uuid) {
        NoteStore.load(s -> s.byUuid(uuid), n -> {
            if (n == null || n.isDeleted()) {
                Ui.toast("That note is gone; the file stays as it is");
                FileLinks.unlink(uuid);
                showFile();
                return;
            }
            confirmLeave(() -> NotesApp.open(NoteData.editor(n.getType(), n.getId())));
        });
    }

    /** A locked note's file: not text to edit, but a note to open with its lock. */
    private void showLockedFile(Path path, String text) {
        String uuid = FileLinks.noteFor(path);
        Label title = Ui.label("This file is a locked note", 22, "bold");
        Label line = Ui.label(uuid != null ? "Its text is encrypted. Open the note and enter its lock to read or change it."
                : "Its text is encrypted. Add it to your notes, then open it with its lock.", 14, "secondary");
        line.setWrapText(true);
        line.setMaxWidth(420);
        Button act = new Button(uuid != null ? "Open the note" : "Add to notes");
        act.getStyleClass().setAll("filled-button", "compact");
        act.setOnAction(e -> {
            if (uuid != null) {
                openNote(uuid);
                return;
            }
            String envelope = FileLinks.unarmor(text);
            if (envelope == null) {
                Ui.toast("This locked file is damaged");
                return;
            }
            String name = path.getFileName().toString();
            String noteTitle = name.toLowerCase(Locale.ROOT).endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
            NoteStore.load(s -> {
                NoteEntity n = new NoteEntity(noteTitle, "", System.currentTimeMillis());
                n.setType(NoteData.TEXT);
                n.setData(envelope);
                n.setAccount(Account.uid());
                n.setId(s.insert(n));
                FileLinks.link(n.getUuid(), path, text);
                return n;
            }, n -> {
                NoteStore.get().notifyChanged();
                NoteSync.schedule();
                openNote(n.getUuid());
            });
        });
        Button back = Ui.textButton("Back to the editor", () -> {
            newDocument();
            focus();
        });
        VBox box = new VBox(14, Ui.icon(Icons.LOCK, 56, "brand_yellow"), title, line, act, back);
        box.setAlignment(Pos.CENTER);
        center.getChildren().setAll(box);
        fileName.setText(path.getFileName().toString());
        filePath.setText(path.toString());
        notesButton.setVisible(false);
        notesButton.setManaged(false);
    }

    // ================================================================== find

    private void openFind(boolean replace) {
        findBar.setVisible(true);
        findBar.setManaged(true);
        findBar.getChildren().get(1).setVisible(replace);
        ((javafx.scene.layout.Region) findBar.getChildren().get(1)).setManaged(replace);
        String sel = area.getSelectedText();
        if (!sel.isEmpty() && !sel.contains("\n")) findField.setText(sel);
        Platform.runLater(() -> {
            findField.requestFocus();
            findField.selectAll();
        });
    }

    private void closeFind() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        focus();
    }

    private void find(boolean forward) {
        String q = findField.getText();
        if (q.isEmpty()) {
            openFind(false);
            return;
        }
        String text = area.getText().toLowerCase(Locale.ROOT), needle = q.toLowerCase(Locale.ROOT);
        int at;
        if (forward) {
            at = text.indexOf(needle, area.getSelection().getEnd());
            if (at < 0) at = text.indexOf(needle);
        } else {
            at = text.lastIndexOf(needle, Math.max(0, area.getSelection().getStart() - 1));
            if (at < 0) at = text.lastIndexOf(needle);
        }
        if (at < 0) {
            Ui.toast("Not found");
            return;
        }
        area.selectRange(at, at + q.length());
    }

    private void countMatches() {
        String q = findField.getText().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            findCount.setText("");
            return;
        }
        String text = area.getText().toLowerCase(Locale.ROOT);
        int n = 0;
        for (int i = text.indexOf(q); i >= 0; i = text.indexOf(q, i + q.length())) n++;
        findCount.setText(n == 0 ? "No matches" : n + (n == 1 ? " match" : " matches"));
    }

    private void replaceOne() {
        String q = findField.getText();
        if (q.isEmpty()) return;
        if (area.getSelectedText().equalsIgnoreCase(q)) area.replaceSelection(replaceField.getText());
        find(true);
        countMatches();
    }

    private void replaceAll() {
        String q = findField.getText();
        if (q.isEmpty()) return;
        String replaced = area.getText().replaceAll("(?i)" + java.util.regex.Pattern.quote(q),
                java.util.regex.Matcher.quoteReplacement(replaceField.getText()));
        if (!replaced.equals(area.getText())) area.setText(replaced);
        countMatches();
    }

    // ================================================================ view

    private void moreMenu(Button anchor) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Save as…  (Ctrl+Shift+S)");
        actions.add(() -> save(Boolean.TRUE));
        labels.add("Find  (Ctrl+F)");
        actions.add(() -> openFind(false));
        labels.add("Replace  (Ctrl+H)");
        actions.add(() -> openFind(true));
        labels.add(wrap ? "Don't wrap lines" : "Wrap lines");
        actions.add(() -> {
            wrap = !wrap;
            area.setWrapText(wrap);
        });
        labels.add("Zoom in  (Ctrl +)");
        actions.add(() -> zoom(0.1));
        labels.add("Zoom out  (Ctrl -)");
        actions.add(() -> zoom(-0.1));
        if (file != null) {
            labels.add("Show in folder");
            actions.add(() -> {
                try {
                    new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
                } catch (Exception e) {
                    Ui.toast("Couldn't open the folder");
                }
            });
            String uuid = FileLinks.noteFor(file);
            if (uuid != null) {
                labels.add("Stop linking to its note");
                actions.add(() -> {
                    FileLinks.unlink(uuid);
                    showFile();
                    Ui.toast("The note and this file now go their own ways");
                });
            }
        }
        Ui.menu(anchor, labels, actions);
    }

    private void zoom(double by) {
        zoom = Math.max(0.5, Math.min(3, Math.round((zoom + by) * 10) / 10.0));
        area.setStyle("-fx-font-size: " + Math.round(15 * zoom) + "px;");
        updateStatus();
    }

    private void setText(String text) {
        loading = true;
        area.setText(text);
        area.positionCaret(0);
        loading = false;
        center.getChildren().setAll(area.getParent() == null ? new StackPane(area) : area.getParent());
    }

    private void showFile() {
        fileName.setText(displayName());
        filePath.setText(file == null ? "Not saved yet" : file.toString());
        boolean linked = file != null && FileLinks.noteFor(file) != null;
        notesButton.setVisible(true);
        notesButton.setManaged(true);
        notesButton.setText(linked ? "Open note" : "Save to notes");
        notesButton.setGraphic(Ui.icon(linked ? Icons.NOTE_TEXT : Icons.ADD, 18, "on_brand_yellow"));
        Ui.tooltip(notesButton, linked ? "This file is a note: open it to add colour, pictures or a lock"
                : "Keep this file as a note: synced to your phone, and every change reaches this file");
        showDirty();
        updateStatus();
    }

    private void showDirty() {
        boolean d = dirty();
        dirtyDot.setVisible(d);
        Ui.tooltip(dirtyDot, "Not saved");
    }

    private void updateStatus() {
        String text = area.getText();
        int caret = Math.min(area.getCaretPosition(), text.length());
        int line = 1, col = 1;
        for (int i = 0; i < caret; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        String charset = form == null ? "UTF-8" : form.charset().equals(StandardCharsets.UTF_8) ? (form.bom() ? "UTF-8 with BOM" : "UTF-8") : "Windows-1252";
        String endings = form == null || form.crlf() ? "Windows (CRLF)" : "Unix (LF)";
        status.setText("Ln " + line + ", Col " + col + "    ·    " + String.format("%,d", text.length()) + " characters    ·    "
                + Math.round(zoom * 100) + "%    ·    " + endings + "    ·    " + charset);
    }

    private static Button tool(Icons.Icon icon, String tip, Runnable action) {
        return Ui.iconButton(icon, 40, 22, "text_primary", tip, action);
    }

    private static javafx.scene.layout.Region gap(double w) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinWidth(w);
        return r;
    }

    /** True when a file could be opened by this app (any regular file; text is what it expects). */
    static boolean openable(Path p) {
        return p != null && Files.isRegularFile(p);
    }
}
