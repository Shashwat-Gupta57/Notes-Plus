package org.flexstudios.notes.plus;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Base for every note editor (text, list, journal, mind map, sketch, audio): the phone's
 * NoteActivity.
 *
 * Owns the parts they all share so none of them re-implements it: loading the note or
 * starting a new one, saving when it is left or the window loses focus (only when something
 * changed), pin / colour / delete, and throwing away a new note that was left empty.
 */
abstract class NoteEditor extends Screen {

    protected NoteEntity note;
    protected final String type;
    private final int noteId;
    private boolean createdHere;
    private String savedState;

    protected final BorderPane root = new BorderPane();
    private final HBox toolbar = new HBox();
    private Button pinButton;

    NoteEditor(String type, int noteId) {
        this.type = type;
        this.noteId = noteId;
    }

    /** Fill the editor from the note. FX thread. */
    protected abstract void onNoteLoaded(NoteEntity note);

    /** Copy the editor's state into the note. Return false if there is nothing worth keeping. */
    protected abstract boolean collect(NoteEntity note);

    @Override
    Node view() {
        return root;
    }

    /**
     * Sets up the toolbar and loads the note. Call it <b>last</b> in the constructor: a new note
     * is passed to {@link #onNoteLoaded} straight away, so every view it touches must exist.
     */
    protected void setUpNote(Node body) {
        pinButton = Ui.iconButton(Icons.PIN, "text_secondary", "Pin", this::togglePin);
        Button more = Ui.iconButton(Icons.MORE, "text_primary", "More options", () -> {});
        more.setOnAction(e -> Ui.menu(more, List.of("Delete"), List.of(() ->
                Ui.confirm("Delete this note?", "Delete", () -> delete(true)))));
        toolbar.getChildren().setAll(
                Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Back (Esc)", NotesApp::back),
                Ui.spacer(),
                pinButton,
                Ui.iconButton(Icons.PALETTE, "text_secondary", "Colour", this::pickColor),
                more);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.setTop(toolbar);
        root.setCenter(body);

        if (noteId <= 0) {
            note = new NoteEntity("", "", System.currentTimeMillis());
            note.setType(type);
            createdHere = true;
            savedState = state(note);
            applyColor();
            showPin();
            onNoteLoaded(note);
            return;
        }
        NoteStore.load(s -> s.byId(noteId), loaded -> {
            if (loaded == null || loaded.isDeleted()) {
                NotesApp.back();
                return;
            }
            note = loaded;
            NoteSync.OPEN.add(note.getId());
            savedState = state(note);
            applyColor();
            showPin();
            onNoteLoaded(note);
            // Opened to read: no caret anywhere until a click puts one, like the phone.
            javafx.application.Platform.runLater(root::requestFocus);
        });
    }

    /** The note's media folder, inserting the row first if this is a brand-new note. */
    protected void withFolder(Consumer<Path> then) {
        if (note == null) return;
        if (note.getId() > 0) {
            then.accept(NoteData.dir(note.getId()));
            return;
        }
        collect(note);
        final NoteEntity live = note;
        NoteStore.load(s -> {
            if (live.getId() <= 0) insert(s, live, live.copy());
            return NoteData.dir(live.getId());
        }, dir -> {
            savedState = state(live);
            then.accept(dir);
        });
    }

    @Override
    void onPause() {
        save(false);
    }

    /**
     * Sync waits until the user has finished with a note: an open note is never pushed or
     * overwritten, and leaving it is what queues the sync (after the last save has landed).
     */
    @Override
    void onDestroy() {
        NoteEntity n = note;
        save(true);
        NoteStore.write(s -> {
            if (n != null) NoteSync.OPEN.remove(n.getId());
            NoteSync.schedule();
        });
    }

    @Override
    void onKey(KeyEvent e) {
        if (e.isShortcutDown() && e.getCode() == KeyCode.S) {
            e.consume();
            save(false);
            Ui.toast("Saved");
        }
    }

    /**
     * Saves if anything changed. Leaving a note made here with nothing in it throws it away,
     * files included; losing focus only skips the save.
     */
    protected void save(boolean leaving) {
        if (note == null) return;
        boolean keep = collect(note);
        if (!keep) {
            if (leaving && createdHere && note.getId() > 0) delete(false);
            return;
        }
        String state = state(note);
        if (state.equals(savedState)) return;
        savedState = state;
        note.setTimestamp(System.currentTimeMillis());
        final NoteEntity snapshot = note.copy();
        final NoteEntity live = note;
        NoteStore.write(s -> {
            // On the one IO thread, so an earlier insert has always landed.
            if (live.getId() <= 0) {
                insert(s, live, snapshot);
            } else {
                s.saveContent(live.getId(), snapshot.getTitle(), snapshot.getContent(), snapshot.getData(),
                        snapshot.getColor(), snapshot.isPinned(), snapshot.getTimestamp());
            }
        });
    }

    /** Inserts a new note into the signed-in account (if any), and marks it open here. */
    private static void insert(NoteStore s, NoteEntity live, NoteEntity snapshot) {
        snapshot.setAccount(Account.uid());
        live.setAccount(snapshot.getAccount());
        live.setId(s.insert(snapshot));
        NoteSync.OPEN.add(live.getId());
    }

    private void delete(boolean thenFinish) {
        final NoteEntity live = note;
        note = null;
        NoteStore.write(s -> {
            if (live.getId() <= 0) return;
            NoteSync.OPEN.remove(live.getId());
            s.setDeleted(live.getId(), true);
            s.forget(live.getId());
        });
        if (thenFinish) NotesApp.back();
    }

    // ===================================================================== actions

    private void togglePin() {
        if (note == null) return;
        note.setPinned(!note.isPinned());
        showPin();
    }

    private void showPin() {
        boolean pinned = note != null && note.isPinned();
        ((Ui.IconView) pinButton.getGraphic()).color(pinned ? "brand_yellow" : "text_secondary");
        Ui.tooltip(pinButton, pinned ? "Unpin" : "Pin");
    }

    private void pickColor() {
        if (note == null) return;
        HBox row = new HBox(8);
        Ui.Modal[] dialog = new Ui.Modal[1];
        for (int i = 0; i < NoteData.COLORS.length; i++) {
            int index = i;
            javafx.scene.layout.Region swatch = new javafx.scene.layout.Region();
            boolean on = i == note.getColor();
            String fill = Theme.ref(i == 0 ? "app_surface" : NoteData.COLORS[i]);
            String ring = Theme.ref(on ? "brand_yellow" : "divider");
            swatch.setStyle("-fx-background-color: " + ring + ", " + fill + "; -fx-background-radius: 100;"
                    + "-fx-background-insets: 0, " + (on ? 3 : 1) + "; -fx-cursor: hand;");
            swatch.setMinSize(40, 40);
            swatch.setMaxSize(40, 40);
            Ui.tooltip(swatch, NoteData.COLOR_NAMES[i]);
            swatch.setOnMouseClicked(e -> {
                note.setColor(index);
                applyColor();
                dialog[0].dismiss();
            });
            row.getChildren().add(swatch);
        }
        row.setPadding(new javafx.geometry.Insets(4, 0, 24, 0));
        dialog[0] = Ui.dialog().title("Colour").content(row).width(400).show();
    }

    /** Tints the editor with the note's colour, so it matches its card. */
    protected void applyColor() {
        if (note == null) return;
        root.setStyle("-fx-background-color: " + Theme.ref(NoteData.tint(note.getColor(), "app_background")) + ";");
    }

    /** The colour behind the editor, for things drawn over it. */
    protected String background() {
        return note == null ? "app_background" : NoteData.tint(note.getColor(), "app_background");
    }

    // ===================================================================== helpers

    private static String state(NoteEntity n) {
        return n.getTitle() + "\u0000" + n.getContent() + "\u0000" + n.getData() + "\u0000"
                + n.getColor() + "\u0000" + n.isPinned();
    }
}
