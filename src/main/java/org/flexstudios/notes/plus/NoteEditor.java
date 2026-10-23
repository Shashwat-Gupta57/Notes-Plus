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
    /** Someone else's note sent to me: shown, never changed (pin and colour are mine). */
    protected boolean readOnly;
    protected final String type;
    private final int noteId;
    private boolean createdHere;
    private String savedState;

    protected final BorderPane root = new BorderPane();
    private final HBox toolbar = new HBox();
    private Button pinButton, lockButton;
    /** The key of a locked note while it is open: the editor holds the text, the database only its envelope. */
    private NoteLock.Key lock;

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
        lockButton = Ui.iconButton(Icons.LOCK_OPEN, "text_secondary", "Lock", this::lockClicked);
        lockButton.setVisible(false);
        lockButton.setManaged(false);
        Button more = Ui.iconButton(Icons.MORE, "text_primary", "More options", () -> {});
        more.setOnAction(e -> showMenu(more));
        toolbar.getChildren().setAll(
                Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Back (Esc)", NotesApp::back),
                Ui.spacer(),
                lockButton,
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
            showLock();
            onNoteLoaded(note);
            return;
        }
        NoteStore.load(s -> s.byId(noteId), loaded -> {
            if (loaded == null || loaded.isDeleted()) {
                NotesApp.back();
                return;
            }
            if (!loaded.isReceived() && NoteLock.isLocked(loaded.getData())) {
                NoteSync.OPEN.add(loaded.getId());
                unlockThenShow(loaded);
                return;
            }
            showLoaded(loaded);
        });
    }

    /** Fills the editor with a loaded note (already decrypted, if it was locked). */
    private void showLoaded(NoteEntity loaded) {
        note = loaded;
        readOnly = loaded.isReceived();
        NoteSync.OPEN.add(note.getId());
        savedState = state(note);
        applyColor();
        showPin();
        showLock();
        onNoteLoaded(note);
        if (!readOnly) markBaseline();
        if (readOnly) {
            lockForReading();
            javafx.scene.control.Label from = Ui.label("From @" + loaded.getSharedFrom() + "  ·  read only", 14, "secondary");
            toolbar.getChildren().add(1, from);
            HBox.setMargin(from, new javafx.geometry.Insets(0, 0, 0, 8));
        }
        // Opened to read: no caret anywhere until a click puts one, like the phone.
        javafx.application.Platform.runLater(root::requestFocus);
    }

    /** A locked note: the key from the recent session, or asked for. Cancel leaves it. */
    private void unlockThenShow(NoteEntity loaded) {
        NoteLock.Key cached = NoteLock.session(loaded.getUuid());
        if (cached != null && NoteLock.fits(cached, loaded.getData())) {
            openLocked(loaded, cached);
            return;
        }
        String type = NoteLock.typeOf(loaded.getData());
        String title = loaded.getTitle() == null || loaded.getTitle().isEmpty() ? "Locked note" : loaded.getTitle();
        askLock(type, title, "Enter this note's " + NoteLock.typeWord(type) + ".", value ->
                slow(() -> NoteLock.open(loaded.getData(), value), k -> openLocked(loaded, k), e -> {
                    Ui.toast("That isn't this note's " + NoteLock.typeWord(type));
                    unlockThenShow(loaded);
                }), () -> {
            NoteSync.OPEN.remove(loaded.getId());
            NotesApp.back();
        });
    }

    private void openLocked(NoteEntity loaded, NoteLock.Key k) {
        try {
            String[] plain = NoteLock.unseal(k, loaded.getData());
            loaded.setContent(plain[0]);
            loaded.setData(plain[1]);
        } catch (Exception e) {
            Ui.toast("Couldn't open this note");
            NoteSync.OPEN.remove(loaded.getId());
            NotesApp.back();
            return;
        }
        lock = k;
        NoteLock.opened(loaded.getUuid(), k);
        showLoaded(loaded);
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
        if (n != null) NoteLock.left(n.getUuid());
        NoteStore.write(s -> {
            if (n != null) NoteSync.OPEN.remove(n.getId());
            NoteSync.schedule();
            FileLinks.syncLater(); // Its linked file (if any) takes the edit.
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
        if (readOnly) {
            // Only my own choices about it: pinned, and its colour.
            final int id = note.getId(), color = note.getColor();
            final boolean pinned = note.isPinned();
            NoteStore.write(s -> {
                s.setPinned(id, pinned);
                s.setColor(id, color);
            });
            return;
        }
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
        if (lock != null) {
            try {
                snapshot.setData(NoteLock.seal(lock, snapshot.getContent(), snapshot.getData()));
                snapshot.setContent("");
            } catch (Exception e) {
                System.getLogger("NoteEditor").log(System.Logger.Level.ERROR, "Couldn't seal a locked note; not saved", e);
                savedState = null;
                return;
            }
        }
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

    /**
     * Makes the editor read-only, for a note sent to me. The default stops every text field and
     * tick box from changing; editors with more to lock (drawing, the map, recording) add to it.
     */
    protected void lockForReading() {
        lock(root);
    }

    static void lock(Node n) {
        if (n instanceof javafx.scene.control.TextInputControl t) t.setEditable(false);
        else if (n instanceof javafx.scene.control.CheckBox c) c.setDisable(true);
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) lock(c);
    }

    // ===================================================================== actions

    /** The "more" menu: what can be done with this note, mine or someone else's. */
    private void showMenu(Button more) {
        if (note == null) return;
        List<String> labels = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        if (readOnly) {
            NoteEntity received = note;
            labels.add("Save a copy");
            actions.add(() -> SharingUi.saveCopy(received, true));
            labels.add("Remove");
            actions.add(() -> SharingUi.confirmRemoveReceived(received, NotesApp::back));
        } else {
            boolean signedIn = Account.uid() != null;
            if (lock != null) {
                labels.add("Change lock…");
                actions.add(this::chooseNewLock);
                labels.add(FileLinks.pathOf(note.getUuid()) != null ? "Decrypt note and file…" : "Decrypt…");
                actions.add(this::confirmDecrypt);
            }
            Path linked = FileLinks.pathOf(note.getUuid());
            if (linked != null) {
                String uuid = note.getUuid();
                labels.add("Show its file");
                actions.add(() -> {
                    try {
                        new ProcessBuilder("explorer.exe", "/select,", linked.toString()).start();
                    } catch (Exception e) {
                        Ui.toast("Couldn't open the folder");
                    }
                });
                labels.add("Stop linking to its file");
                actions.add(() -> {
                    FileLinks.unlink(uuid);
                    Ui.toast("The note and " + linked.getFileName() + " now go their own ways");
                });
            }
            if (signedIn && lock == null && note.getGroupId() == null) {
                int sent = SentNotes.count(note.getSentTo());
                labels.add(sent > 0 ? "Sent to " + sent : "Send to people");
                actions.add(() -> afterSave(SharingUi::sendSheet));
            }
            if (signedIn && lock == null && !NoteData.AUDIO.equals(note.getType()) && note.getSentTo() == null) {
                labels.add(note.getGroupId() != null ? "People" : "Share with people");
                actions.add(() -> afterSave(SharingUi::peopleSheet));
            }
            Boolean wrap = wrapText();
            if (wrap != null) {
                labels.add(wrap ? "Don't wrap text" : "Wrap text");
                actions.add(() -> setWrapText(!wrap));
            }
            labels.add("Delete");
            actions.add(() -> Ui.confirm("Delete this note?", "Delete", () -> delete(true)));
        }
        Ui.menu(more, labels, actions);
    }

    // ==================================================================== lock

    /** True while this editor holds a locked note open. */
    protected boolean locked() {
        return lock != null;
    }

    private void showLock() {
        boolean can = note != null && !readOnly && NoteLock.lockable(note.getType())
                && note.getGroupId() == null && SentNotes.count(note.getSentTo()) == 0;
        lockButton.setVisible(can);
        lockButton.setManaged(can);
        // Locked and opened: an open padlock in yellow (unlocked for now; the stored copies stay
        // encrypted). Not locked: a grey padlock, to lock it.
        lockButton.setGraphic(Ui.icon(lock != null ? Icons.LOCK_OPEN : Icons.LOCK, 24, lock != null ? "brand_yellow" : "text_secondary"));
        Ui.tooltip(lockButton, lock != null ? "Unlocked for now: click to lock it again" : "Lock this note");
    }

    private void lockClicked() {
        if (note == null) return;
        if (lock == null) startLocking();
        else lockedOptions();
    }

    /** With the key still open from earlier, with its last lock, or a new one. */
    private void startLocking() {
        String uuid = note.getUuid();
        NoteLock.Key open = NoteLock.session(uuid);
        if (open != null) {
            applyLock(open);
            return;
        }
        String lastType = NoteLock.lastType(uuid);
        if (lastType == null) {
            chooseNewLock();
            return;
        }
        String word = NoteLock.typeWord(lastType);
        Ui.dialog().title("Lock this note").message("Lock it again with its last " + word + "?")
                .positive("Use last " + word, () -> askLock(lastType, "Last " + word, "Enter it once.", value ->
                        slow(() -> NoteLock.lastKey(uuid, value), this::applyLock,
                                e -> Ui.toast("That isn't its last " + word)), null))
                .neutral("New lock", this::chooseNewLock)
                .negative("Cancel", null).width(420).show();
    }

    private void chooseNewLock() {
        Ui.Tabs tabs = new Ui.Tabs("m3_primary", "PIN", "Password", "Pattern");
        LockInput[] inputs = new LockInput[3];
        for (int i = 0; i < 3; i++) inputs[i] = new LockInput(LockInput.TYPES[i], null, null);
        javafx.scene.layout.StackPane slot = new javafx.scene.layout.StackPane(inputs[0]);
        slot.setMinHeight(300);
        tabs.selected.addListener((o, a, b) -> slot.getChildren().setAll(inputs[b.intValue()]));
        Ui.dialog().title("Lock this note").message("Choose a lock. It opens this note on every device.")
                .content(new javafx.scene.layout.VBox(12, tabs, slot)).width(440)
                .positive("Next", () -> {
                    LockInput first = inputs[tabs.selected.get()];
                    String value = first.value();
                    String problem = LockInput.problem(first.type, value);
                    if (problem != null) {
                        Ui.toast(problem);
                        return;
                    }
                    LockInput again = new LockInput(first.type, "Once more", null);
                    javafx.scene.layout.StackPane confirm = new javafx.scene.layout.StackPane(again);
                    confirm.setMinHeight(LockInput.PATTERN.equals(first.type) ? 300 : 80);
                    Ui.dialog().title("Once more, to confirm").content(confirm).width(440)
                            .positive("Lock", () -> {
                                if (!value.equals(again.value())) {
                                    Ui.toast("Those didn't match. Try again.");
                                    return;
                                }
                                slow(() -> NoteLock.newKey(first.type, value), this::applyLock, e -> Ui.toast("Couldn't lock it"));
                            })
                            .negative("Cancel", null).show();
                    again.focus();
                })
                .negative("Cancel", null).show();
        inputs[0].focus();
    }

    private void applyLock(NoteLock.Key k) {
        if (note == null) return;
        lock = k;
        NoteLock.opened(note.getUuid(), k);
        NoteLock.remember(note.getUuid(), k);
        savedState = null; // Written now, sealed.
        save(false);
        showLock();
        Ui.toast("Locked. It opens only with this " + NoteLock.typeWord(k.type) + ", on every device.");
    }

    private void lockedOptions() {
        int minutes = NoteLock.timeoutMinutes();
        String stays = minutes < 0 ? "until Notes+ closes" : minutes == 0 ? "only while it's on screen"
                : "for " + minutes + (minutes == 1 ? " minute" : " minutes") + " after you leave it";
        boolean linked = FileLinks.pathOf(note.getUuid()) != null;
        Ui.dialog().title("Unlocked for now")
                .message("It stays open " + stays + ", then needs its " + NoteLock.typeWord(lock.type) + " again. "
                        + (linked ? "Its file stays encrypted all along, as do " : "Stays encrypted all along: ")
                        + "the copies in your account and on your other devices. To store it unencrypted, use ⋮ → Decrypt.")
                .positive("Lock now", () -> {
                    NoteEntity n = note;
                    NotesApp.back();
                    if (n != null) NoteLock.forget(n.getUuid());
                })
                .negative("Close", null).width(460).show();
    }

    private void confirmDecrypt() {
        java.nio.file.Path linked = FileLinks.pathOf(note.getUuid());
        Ui.dialog().title("Decrypt this note?")
                .message("Its text will be stored unencrypted: here, in your account and on your other devices"
                        + (linked != null ? ", and in its file " + linked.getFileName() : "") + ". "
                        + "Click the lock any time to encrypt it again with the same lock.")
                .positive("Decrypt", this::removeLock)
                .negative("Cancel", null).width(460).show();
    }

    /** Decrypts the note for good; its lock is remembered, so the lock button puts it back. */
    private void removeLock() {
        if (note == null || lock == null) return;
        lock = null;
        savedState = null;
        save(false);
        showLock();
        Ui.toast("Decrypted. Click the lock to encrypt it again.");
    }

    /** Enters an existing lock of {@code type}: a field, or a pattern. */
    static void askLock(String type, String title, String message, Consumer<String> onValue, Runnable onCancel) {
        Ui.Modal[] m = new Ui.Modal[1];
        LockInput input = new LockInput(type, null, v -> {
            if (m[0] != null) m[0].dismiss();
            onValue.accept(v);
        });
        javafx.scene.layout.StackPane slot = new javafx.scene.layout.StackPane(input);
        slot.setMinHeight(LockInput.PATTERN.equals(type) ? 300 : 80);
        Ui.DialogBuilder d = Ui.dialog().title(title).message(message).content(slot).width(420)
                .negative("Cancel", onCancel);
        if (!LockInput.PATTERN.equals(type)) d.positive("Open", () -> onValue.accept(input.value()));
        m[0] = d.show();
        input.focus();
    }

    /** PBKDF2 off the FX thread. */
    private static <T> void slow(java.util.concurrent.Callable<T> work, Consumer<T> ok, Consumer<Exception> failed) {
        Thread t = new Thread(() -> {
            try {
                T r = work.call();
                javafx.application.Platform.runLater(() -> ok.accept(r));
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> failed.accept(e));
            }
        }, "note-lock");
        t.setDaemon(true);
        t.start();
    }

    /** Whether long lines wrap, for the note types that offer the choice; null for the rest. */
    protected Boolean wrapText() {
        return null;
    }

    protected void setWrapText(boolean wrap) {
    }

    /** Saves first and lets the write land, so what is shared is what's on screen. */
    private void afterSave(java.util.function.IntConsumer then) {
        save(false);
        if (note == null) return;
        if (note.getId() > 0) {
            NoteStore.load(s -> note.getId(), then::accept);
            return;
        }
        withFolder(dir -> then.accept(note.getId()));
    }

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

    /**
     * "Unchanged" is what this editor makes of the note once it is on screen, not the stored
     * text: editors normalise as they load, and that alone must never count as an edit, or
     * leaving an untouched note pushes a stale copy over a newer one.
     */
    private void markBaseline() {
        javafx.application.Platform.runLater(() -> {
            if (note == null) return;
            NoteEntity probe = note.copy();
            collect(probe);
            savedState = state(probe);
        });
    }

    private static String state(NoteEntity n) {
        return n.getTitle() + "\u0000" + n.getContent() + "\u0000" + n.getData() + "\u0000"
                + n.getColor() + "\u0000" + n.isPinned();
    }
}
