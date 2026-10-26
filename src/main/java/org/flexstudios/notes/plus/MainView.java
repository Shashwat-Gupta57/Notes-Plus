package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The notes app: a grid of cards, filters by type, search, and the "New" sheet. The phone's
 * MainActivity, minus the hidden spaces' gestures.
 *
 * Desktop extras: Ctrl+N opens "New", Ctrl+F searches, right-click on a card is the phone's
 * long press.
 */
final class MainView extends Screen {

    private final BorderPane root = new BorderPane();
    private final MasonryPane grid = new MasonryPane();
    private final ScrollPane scroll = new ScrollPane(grid);
    private final VBox emptyState = new VBox();
    private final Label emptyText = new Label();
    private final HBox toolbar = new HBox();
    private final Label title = new Label("Notes");
    private final TextField search = new TextField();
    private final Button fab;
    private boolean searching;

    private List<NoteEntity> allNotes = new ArrayList<>();
    private final Map<Integer, NoteCard> cards = new HashMap<>();
    private String filterType;
    /** The "Shared with me" filter: notes sent to me by others. */
    private static final String SHARED = "SHARED";
    /** The archive: notes put away, shown nowhere else. */
    private static final String ARCHIVED = "ARCHIVED";
    /** A folder's filter: this prefix and its name. */
    private static final String FOLDER = "F:";
    private final HBox chips = new HBox(8);
    /** The folders the chips show now, to rebuild them only when that changes. */
    private List<String> shownFolders;
    private String query = "";
    private double lastScrollY;
    /** The Editor tab, made the first time it is shown. */
    private TextEditorView editor;
    private boolean editorShown;
    private VBox notesTop;
    private StackPane notesArea;
    /** The title rows, each with the Notes | Editor switch centred over it. */
    private final StackPane notesBar = new StackPane(), editorBar = new StackPane();
    private ModeSwitch modes;

    MainView() {
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");

        // Toolbar: the title, or the search field while searching.
        title.getStyleClass().add("toolbar-title");
        search.getStyleClass().setAll("text-input", "text-field", "search-field");
        search.setPromptText("Search notes");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((o, a, b) -> {
            query = b == null ? "" : b.trim();
            applyFilter();
        });
        toolbar.getStyleClass().add("toolbar");
        showToolbar();

        // Filter chips.
        chips.setPadding(new Insets(0, 12, 6, 12));
        buildChips();
        ScrollPane chipScroll = new ScrollPane(chips);
        chipScroll.getStyleClass().add("no-bars");
        chipScroll.setFitToHeight(true);
        chipScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setOnScroll(e -> {
            if (e.getDeltaX() == 0 && chips.getWidth() > chipScroll.getWidth()) {
                chipScroll.setHvalue(chipScroll.getHvalue() - e.getDeltaY() / (chips.getWidth() - chipScroll.getWidth()));
            }
        });
        modes = new ModeSwitch(this::showMode);
        notesBar.getChildren().setAll(toolbar, modes.bar());
        notesTop = new VBox(notesBar, chipScroll);
        Label editorTitle = new Label("Editor");
        editorTitle.getStyleClass().add("toolbar-title");
        HBox editorToolbar = new HBox(editorTitle);
        editorToolbar.getStyleClass().add("toolbar");
        editorToolbar.setAlignment(Pos.CENTER_LEFT);
        editorBar.getChildren().setAll(editorToolbar);
        root.setTop(notesTop);

        // The grid, its empty state, and "New".
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Label emptyIcon = new Label();
        emptyIcon.setGraphic(Ui.icon(Icons.EMPTY_NOTES, 80, "text_secondary"));
        emptyText.getStyleClass().add("secondary");
        emptyText.setStyle("-fx-font-size: 16px;");
        emptyText.setWrapText(true);
        emptyState.getChildren().addAll(emptyIcon, emptyText);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setSpacing(16);
        emptyState.setMouseTransparent(true);
        emptyState.setVisible(false);

        fab = Ui.extendedFab("New", Icons.ADD, this::showNewSheet);
        Ui.tooltip(fab, "New (Ctrl+N)");
        StackPane.setAlignment(fab, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(fab, new Insets(20));
        scroll.vvalueProperty().addListener((o, a, b) -> {
            double content = grid.getHeight() - scroll.getViewportBounds().getHeight();
            double y = b.doubleValue() * Math.max(0, content);
            double dy = y - lastScrollY;
            lastScrollY = y;
            if (dy > 8) shrinkFab(true);
            else if (dy < -8) shrinkFab(false);
        });
        notesArea = new StackPane(scroll, emptyState, fab);
        root.setCenter(notesArea);
        setUpSecretGesture();

        NoteStore.get().observe(reloader);
        Account.observe(this::showToolbar);
        reload();
    }

    /** Every write, from any window or from sync, reaches every grid at once. */
    private final Runnable reloader = this::reload;

    @Override
    Node view() {
        return root;
    }

    @Override
    void onDestroy() {
        NoteStore.get().unobserve(reloader);
    }

    /**
     * Back from an editor: rebuild the cards once its last writes have landed, so pictures it
     * rewrote (a sketch's preview, a pasted photo) show without the note's text changing.
     */
    @Override
    void onResume() {
        Platform.runLater(grid::requestFocus);
        NoteStore.load(NoteStore::all, notes -> {
            allNotes = notes;
            cards.clear();
            applyFilter();
        });
    }

    @Override
    void onKey(KeyEvent e) {
        if (editorShown) {
            editor.onKey(e);
            return;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.N) {
            e.consume();
            showNewSheet();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
            e.consume();
            startSearch();
        } else if (e.getCode() == KeyCode.ESCAPE && searching) {
            e.consume();
            stopSearch();
        }
    }

    // ============================================================ notes | editor

    /** Shows the Editor tab, opening {@code file} in it when given. */
    void showEditor(java.nio.file.Path file) {
        modes.select(1);
        if (file != null) editor.openPath(file);
    }

    /** The Editor's unsaved text, before the window closes: true when it may close now. */
    boolean mayClose(Runnable closeAgain) {
        if (editor == null || !editor.dirty()) return true;
        modes.select(1);
        editor.confirmLeave(() -> {
            editor.discard(); // "Don't save" (or saved): the next close request goes through.
            closeAgain.run();
        });
        return false;
    }

    private void showMode(int mode) {
        editorShown = mode == 1;
        if (editorShown) {
            if (editor == null) editor = new TextEditorView();
            if (editorBar.getChildren().size() < 2) editorBar.getChildren().add(modes.bar());
            root.setTop(editorBar);
            root.setCenter(editor.view());
            editor.focus();
        } else {
            if (notesBar.getChildren().size() < 2) notesBar.getChildren().add(modes.bar());
            root.setTop(notesTop);
            root.setCenter(notesArea);
            Platform.runLater(grid::requestFocus);
        }
    }

    /**
     * The Notes | Editor switch under the top edge: a pill whose yellow block glides to the
     * side that is showing.
     */
    private static final class ModeSwitch {
        private final StackPane pill = new StackPane();
        private final javafx.scene.layout.Region block = new javafx.scene.layout.Region();
        private final List<Label> labels = new ArrayList<>();
        private final java.util.function.IntConsumer onSelect;
        private int selected;

        ModeSwitch(java.util.function.IntConsumer onSelect) {
            this.onSelect = onSelect;
            HBox segments = new HBox();
            String[] names = {"Notes", "Editor"};
            Icons.Icon[] icons = {Icons.NOTE_TEXT, Icons.EDIT};
            for (int i = 0; i < names.length; i++) {
                int index = i;
                Label l = new Label(names[i], Ui.icon(icons[i], 18, "text_secondary"));
                l.getStyleClass().add("mode-segment");
                l.setGraphicTextGap(8);
                l.setAlignment(Pos.CENTER);
                l.setMinWidth(112);
                l.setPrefWidth(112);
                l.setOnMouseClicked(e -> select(index));
                labels.add(l);
                segments.getChildren().add(l);
            }
            block.getStyleClass().add("mode-block");
            block.setManaged(false);
            pill.getChildren().addAll(block, segments);
            pill.getStyleClass().add("mode-switch");
            pill.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
            StackPane.setAlignment(pill, Pos.CENTER);
            pill.layoutBoundsProperty().addListener((o, a, b) -> place(false));
            for (Label l : labels) l.widthProperty().addListener((o, a, b) -> place(false));
            Platform.runLater(() -> place(false));
            paint();
        }

        /** The pill itself, laid over a title row. */
        Node bar() {
            return pill;
        }

        void select(int index) {
            if (index == selected) {
                onSelect.accept(index);
                return;
            }
            selected = index;
            paint();
            place(true);
            onSelect.accept(index);
        }

        private void place(boolean animate) {
            Label target = labels.get(selected);
            double x = target.getBoundsInParent().getMinX() + 3, w = target.getWidth(), h = pill.getHeight() - 6;
            if (w <= 0 || h <= 0) return;
            block.resize(w, h);
            block.setLayoutY(3);
            if (!animate) {
                block.setLayoutX(x);
                return;
            }
            double from = block.getLayoutX();
            Ui.animate(220, javafx.animation.Interpolator.SPLINE(0.2, 0.8, 0.2, 1), f -> block.setLayoutX(from + (x - from) * f));
        }

        private void paint() {
            for (int i = 0; i < labels.size(); i++) {
                boolean on = i == selected;
                Label l = labels.get(i);
                l.setStyle("-fx-text-fill: " + Theme.ref(on ? "black" : "text_secondary") + ";"
                        + (on ? " -fx-font-weight: bold;" : ""));
                ((Ui.IconView) l.getGraphic()).color(on ? "black" : "text_secondary");
            }
        }
    }

    // ================================================================ toolbar

    private void showToolbar() {
        if (searching) {
            Button close = Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Close search", this::stopSearch);
            Button clear = Ui.iconButton(Icons.CLOSE, "text_secondary", "Clear", () -> search.setText(""));
            clear.visibleProperty().bind(search.textProperty().isNotEmpty());
            toolbar.getChildren().setAll(close, search, clear);
            if (modes != null) modes.bar().setVisible(false);
        } else {
            if (modes != null) modes.bar().setVisible(true);
            toolbar.getChildren().setAll(title, Ui.spacer(),
                    Ui.iconButton(Icons.SEARCH, "text_primary", "Search (Ctrl+F)", this::startSearch),
                    gap(4),
                    Ui.iconButton(Icons.WINDOWS, "text_primary", "New window (Ctrl+Shift+N)", NotesApp::newWindow),
                    gap(8),
                    accountButton());
        }
    }

    /** The account button: signed in, its photo (custom, else Google's); otherwise the person icon. */
    private Button accountButton() {
        Button b = Ui.iconButton(Icons.PERSON, Account.signedIn() ? "brand_yellow" : "text_primary", "Account",
                () -> NotesApp.open(new AccountView()));
        if (Account.uid() == null) return b;
        Account.BG.execute(() -> {
            String json;
            try {
                json = Account.loadProfile().photoJson;
            } catch (Exception e) {
                return; // Offline: the icon stays.
            }
            Platform.runLater(() -> ChatMedia.loadAvatar(json, img -> {
                if (img == null) return;
                javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(img);
                view.setFitWidth(30);
                view.setFitHeight(30);
                view.setClip(new javafx.scene.shape.Circle(15, 15, 15));
                Runnable show = () -> {
                    if (!img.isError()) b.setGraphic(view);
                };
                if (img.getProgress() >= 1) show.run();
                else img.progressProperty().addListener((o, x, y) -> {
                    if (y.doubleValue() >= 1) show.run();
                });
            }));
        });
        return b;
    }

    private static javafx.scene.layout.Region gap(double w) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinWidth(w);
        return r;
    }

    private void startSearch() {
        if (!searching) {
            searching = true;
            showToolbar();
        }
        Platform.runLater(search::requestFocus);
    }

    private void stopSearch() {
        searching = false;
        search.setText("");
        showToolbar();
    }

    /** All, the folders (right-click: rename or remove), Shared with me, Archived, the types, and "New folder". */
    private void buildChips() {
        List<String> folders = NoteFolders.all(allNotes);
        if (folders.equals(shownFolders)) return;
        shownFolders = folders;
        if (filterType != null && filterType.startsWith(FOLDER) && !folders.contains(filterType.substring(FOLDER.length()))) {
            filterType = null; // Its folder went (renamed or removed).
        }
        ToggleGroup group = new ToggleGroup();
        chips.getChildren().clear();
        chips.getChildren().add(chip(group, "All", null, filterType == null));
        for (String f : folders) chips.getChildren().add(chip(group, f, FOLDER + f, (FOLDER + f).equals(filterType)));
        chips.getChildren().add(chip(group, "Shared with me", SHARED, SHARED.equals(filterType)));
        chips.getChildren().add(chip(group, "Archived", ARCHIVED, ARCHIVED.equals(filterType)));
        for (NoteData.Kind kind : NoteData.KINDS) {
            chips.getChildren().add(chip(group, kind.plural(), kind.type(), kind.type().equals(filterType)));
        }
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) a.setSelected(true); // selectionRequired
        });
        Button add = new Button("New folder", Ui.icon(Icons.ADD, 18, "text_secondary"));
        add.getStyleClass().setAll("chip", "with-icon");
        add.setFocusTraversable(false);
        add.setOnAction(e -> NoteFolders.create(name -> {
            filterType = FOLDER + name;
            shownFolders = null;
            buildChips();
            applyFilter();
        }));
        chips.getChildren().add(add);
    }

    private ToggleButton chip(ToggleGroup group, String label, String type, boolean selected) {
        ToggleButton chip = new ToggleButton(label);
        chip.getStyleClass().setAll("chip");
        chip.setToggleGroup(group);
        chip.setSelected(selected);
        chip.setFocusTraversable(false);
        if (SHARED.equals(type)) {
            chip.setGraphic(Ui.icon(Icons.PERSON, 18, "tile_teal"));
            chip.getStyleClass().add("with-icon");
        } else if (ARCHIVED.equals(type)) {
            chip.setGraphic(Ui.icon(Icons.ARCHIVE, 18, "text_secondary"));
            chip.getStyleClass().add("with-icon");
        } else if (type != null && type.startsWith(FOLDER)) {
            chip.setGraphic(Ui.icon(Icons.FOLDER, 18, "brand_yellow"));
            chip.getStyleClass().add("with-icon");
            String name = type.substring(FOLDER.length());
            Ui.tooltip(chip, "Right-click to rename or remove");
            chip.setOnContextMenuRequested(e -> NoteFolders.manage(name, () -> {
                shownFolders = null;
                buildChips();
                applyFilter();
            }));
        } else if (type != null) {
            NoteData.Kind kind = NoteData.kind(type);
            chip.setGraphic(Ui.icon(kind.icon(), 18, kind.accent()));
            chip.getStyleClass().add("with-icon");
        }
        chip.setOnAction(e -> {
            if (!chip.isSelected()) return;
            filterType = type;
            applyFilter();
        });
        return chip;
    }

    private void shrinkFab(boolean shrink) {
        boolean shrunk = fab.getStyleClass().contains("shrunk");
        if (shrink == shrunk) return;
        if (shrink) {
            fab.getStyleClass().add("shrunk");
            fab.setText("");
        } else {
            fab.getStyleClass().remove("shrunk");
            fab.setText("New");
        }
    }

    // ================================================================ content

    private void reload() {
        NoteStore.load(NoteStore::all, notes -> {
            allNotes = notes;
            buildChips();
            applyFilter();
        });
    }

    private void applyFilter() {
        List<NoteEntity> shown = new ArrayList<>();
        String q = query.toLowerCase(Locale.getDefault());
        String folder = filterType != null && filterType.startsWith(FOLDER) ? filterType.substring(FOLDER.length()) : null;
        for (NoteEntity n : allNotes) {
            if (ARCHIVED.equals(filterType)) {
                if (!n.isArchived()) continue;
            } else if (n.isArchived()) {
                continue; // Put away: only under Archived.
            } else if (folder != null) {
                if (!folder.equals(n.getFolder())) continue;
            } else if (SHARED.equals(filterType) ? !(n.isReceived() || n.getGroupId() != null) : filterType != null && !filterType.equals(n.getType())) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = ((n.getTitle() == null ? "" : n.getTitle()) + "\n"
                        + (n.getContent() == null ? "" : n.getContent())).toLowerCase(Locale.getDefault());
                if (!hay.contains(q)) continue;
            }
            shown.add(n);
        }

        // All, not searching: a folder's notes are one tile (opening the folder), before the rest.
        List<Node> nodes = new ArrayList<>();
        if (filterType == null && q.isEmpty()) {
            Map<String, List<NoteEntity>> byFolder = new java.util.LinkedHashMap<>();
            for (String f : NoteFolders.all(allNotes)) byFolder.put(f, new ArrayList<>());
            List<NoteEntity> loose = new ArrayList<>();
            for (NoteEntity n : shown) {
                List<NoteEntity> in = n.getFolder() == null ? null : byFolder.get(n.getFolder());
                if (in != null) in.add(n);
                else loose.add(n);
            }
            shown = loose;
            for (Map.Entry<String, List<NoteEntity>> f : byFolder.entrySet()) {
                String name = f.getKey();
                nodes.add(new FolderTile(name, f.getValue(), () -> {
                    filterType = FOLDER + name;
                    shownFolders = null;
                    buildChips();
                    applyFilter();
                }, () -> NoteFolders.manage(name, () -> {
                    shownFolders = null;
                    buildChips();
                    applyFilter();
                })));
            }
        }

        // Unchanged notes keep their card (and its loaded pictures); the rest are rebuilt.
        Map<Integer, NoteCard> next = new HashMap<>();
        NoteCard.Listener listener = new NoteCard.Listener() {
            @Override
            public void onOpen(NoteEntity note) {
                NotesApp.open(NoteData.editor(note.getType(), note.getId()));
            }

            @Override
            public void onOptions(NoteEntity note) {
                showNoteOptions(note);
            }
        };
        for (NoteEntity n : shown) {
            NoteCard card = cards.get(n.getId());
            if (card == null || !card.note.sameContentAs(n)) card = new NoteCard(n, listener);
            next.put(n.getId(), card);
            nodes.add(card);
        }
        cards.clear();
        cards.putAll(next);
        grid.getChildren().setAll(nodes);

        emptyState.setVisible(nodes.isEmpty());
        emptyText.setText(!q.isEmpty() ? "Nothing matches “" + query + "”"
                : SHARED.equals(filterType) ? "Nothing shared with you yet. When someone shares or sends you a note, it shows up here."
                : ARCHIVED.equals(filterType) ? "Nothing archived. Archive a note from its options (right-click) to put it away here."
                : folder != null ? "Nothing in “" + folder + "” yet. Move notes here from their options (right-click)."
                : filterType != null ? "No " + NoteData.kind(filterType).plural().toLowerCase(Locale.getDefault()) + " yet"
                : "No notes yet. Click New to start.");
    }

    // ========================================================= secret gesture

    private final javafx.animation.PauseTransition secretTimer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
    private double touchX, touchY;
    private boolean secretTriggering, secretToChat;

    /**
     * The ways into the hidden spaces, as on the phone: press on the notes, swipe right to left
     * (gallery) or left to right (chat) and keep holding for two seconds. Nothing on screen
     * hints at either.
     */
    private void setUpSecretGesture() {
        secretTimer.setOnFinished(e -> {
            secretTriggering = false;
            if (secretToChat) openChat();
            else openVault();
        });
        scroll.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            touchX = e.getSceneX();
            touchY = e.getSceneY();
            secretTriggering = false;
            secretTimer.stop();
        });
        scroll.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            double dx = touchX - e.getSceneX(), dy = Math.abs(touchY - e.getSceneY());
            if (!secretTriggering && Math.abs(dx) > 50 && Math.abs(dx) > dy * 2) {
                secretTriggering = true;
                secretToChat = dx < 0;
                secretTimer.playFromStart();
            }
        });
        scroll.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            secretTimer.stop();
            secretTriggering = false;
        });
    }

    private void openVault() {
        NotesApp.open(Vault.isSetupDone() ? new UnlockView() : new SetupLockView());
    }

    /** The account's chat vaults first (bounded), then the chat lock, or its first-time setup. */
    private void openChat() {
        ChatStore.load(s -> {
            ChatVaults.ensureMain();
            return s.mainVault() != null;
        }, ready -> NotesApp.open(Boolean.TRUE.equals(ready)
                ? new UnlockView(UnlockView.REALM_CHAT) : new SetupLockView(UnlockView.REALM_CHAT)));
    }

    // ================================================================ actions

    /** The "New" sheet: one row per note type. */
    private void showNewSheet() {
        VBox content = new VBox();
        content.setPadding(new Insets(8, 0, 24, 0));
        Label heading = Ui.label("New", 22, "bold");
        heading.setPadding(new Insets(8, 24, 12, 24));
        content.getChildren().add(heading);
        Ui.Modal[] sheet = new Ui.Modal[1];
        for (NoteData.Kind kind : NoteData.KINDS) {
            StackPane tile = new StackPane(Ui.icon(kind.icon(), 24, "white"));
            tile.setStyle("-fx-background-color: " + Theme.ref(kind.accent()) + "; -fx-background-radius: 8;");
            tile.setMinSize(42, 42);
            tile.setMaxSize(42, 42);
            Label label = Ui.label(kind.label(), 17, "bold");
            Label description = Ui.label(kind.description(), 13, "secondary");
            HBox row = new HBox(tile, new VBox(label, description));
            row.getStyleClass().add("sheet-item");
            row.setOnMouseClicked(e -> {
                sheet[0].dismiss();
                NotesApp.open(NoteData.editor(kind.type(), -1));
            });
            content.getChildren().add(row);
        }
        sheet[0] = Ui.sheet(content);
    }

    private void showNoteOptions(NoteEntity note) {
        String name = note.getTitle() == null || note.getTitle().isEmpty()
                ? NoteData.kind(note.getType()).label() : note.getTitle();
        java.util.List<String> labels = new java.util.ArrayList<>(List.of(note.isPinned() ? "Unpin" : "Pin to top", "Colour"));
        java.util.List<Runnable> actions = new java.util.ArrayList<>(List.of(
                () -> NoteStore.write(s -> s.setPinned(note.getId(), !note.isPinned())), () -> pickColor(note)));
        labels.add("Open in a new window");
        actions.add(() -> NotesApp.popOut(NoteData.editor(note.getType(), note.getId())));
        labels.add("Move to folder…");
        actions.add(() -> NoteFolders.move(note, NoteFolders.all(allNotes)));
        labels.add(note.isArchived() ? "Unarchive" : "Archive");
        actions.add(() -> NoteFolders.setArchived(note, !note.isArchived()));
        labels.add("Duplicate");
        actions.add(() -> duplicateNote(note));
        if (note.isReceived()) {
            // Someone else's note: mine to pin, colour, copy or put away, not to change.
            labels.add("Save a copy");
            actions.add(() -> SharingUi.saveCopy(note, false));
            labels.add("Remove");
            actions.add(() -> SharingUi.confirmRemoveReceived(note, null));
        } else {
            boolean signedIn = Account.uid() != null;
            if (signedIn && note.getGroupId() == null) {
                int sent = SentNotes.count(note.getSentTo());
                labels.add(sent > 0 ? "Sent to " + sent + "…" : "Send to people…");
                actions.add(() -> SharingUi.sendSheet(note.getId()));
            }
            if (signedIn && !NoteData.AUDIO.equals(note.getType()) && note.getSentTo() == null) {
                labels.add(note.getGroupId() != null ? "People…" : "Share with people…");
                actions.add(() -> SharingUi.peopleSheet(note.getId()));
            }
            if (signedIn && !NoteData.AUDIO.equals(note.getType())) {
                labels.add(note.getGroupId() != null ? "Invite link…" : "Share link…");
                actions.add(() -> {
                    if (note.getGroupId() != null) ShareLinks.inviteSheet(note);
                    else ShareLinks.sheet(note.getId());
                });
            }
            labels.add("Delete");
            actions.add(() -> deleteWithUndo(note));
        }
        Ui.dialog().title(name).items(labels, actions).show();
    }

    private void duplicateNote(NoteEntity note) {
        NoteStore.load(s -> {
            String title = note.getTitle().isEmpty()
                    ? NoteData.kind(note.getType()).label() + " (Copy)"
                    : note.getTitle() + " (Copy)";
            NoteEntity copy = new NoteEntity(title, note.getContent(), System.currentTimeMillis());
            copy.setType(note.getType());
            copy.setData(note.getData());
            copy.setColor(note.getColor());
            copy.setPinned(false);
            copy.setFolder(note.getFolder());
            copy.setAccount(Account.uid());
            copy.setRev(0);
            copy.setDirty(true);
            int newId = s.insert(copy);
            copy.setId(newId);
            try {
                java.nio.file.Path srcDir = NoteData.dir(note.getId());
                java.nio.file.Path dstDir = NoteData.dir(newId);
                if (java.nio.file.Files.exists(srcDir)) {
                    java.nio.file.Files.createDirectories(dstDir);
                    try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(srcDir)) {
                        for (java.nio.file.Path p : stream.toList()) {
                            java.nio.file.Files.copy(p, dstDir.resolve(p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (Exception ignored) {}
            return newId;
        }, newId -> {
            Ui.toast("Note duplicated");
            NoteSync.schedule();
        });
    }

    private void pickColor(NoteEntity note) {
        Ui.dialog().title("Colour")
                .singleChoice(NoteData.COLOR_NAMES, note.getColor(),
                        which -> NoteStore.write(s -> s.setColor(note.getId(), which)))
                .show();
    }

    /** Hidden now; its files go only once Undo has expired. */
    private void deleteWithUndo(NoteEntity note) {
        // Held back from sync while Undo is on screen, so a quick sync can't make it final.
        NoteSync.OPEN.add(note.getId());
        NoteStore.write(s -> s.setDeleted(note.getId(), true));
        Ui.snackbar("Note deleted", Ui.LONG, "Undo",
                () -> NoteStore.write(s -> s.setDeleted(note.getId(), false)),
                byAction -> {
                    NoteSync.OPEN.remove(note.getId());
                    if (!byAction) NoteStore.write(s -> s.forget(note.getId()));
                });
    }
}
