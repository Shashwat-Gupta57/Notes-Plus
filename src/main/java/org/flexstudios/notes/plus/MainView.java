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
    private String query = "";
    private double lastScrollY;

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
        HBox chips = new HBox(8);
        chips.setPadding(new Insets(0, 12, 6, 12));
        ToggleGroup group = new ToggleGroup();
        chips.getChildren().add(chip(group, "All", null, true));
        for (NoteData.Kind kind : NoteData.KINDS) chips.getChildren().add(chip(group, kind.plural(), kind.type(), false));
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) a.setSelected(true); // selectionRequired
        });
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
        root.setTop(new VBox(toolbar, chipScroll));

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
        root.setCenter(new StackPane(scroll, emptyState, fab));
        setUpSecretGesture();

        NoteStore.get().observe(this::reload);
        Account.observe(this::showToolbar);
        reload();
    }

    @Override
    Node view() {
        return root;
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

    // ================================================================ toolbar

    private void showToolbar() {
        if (searching) {
            Button close = Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Close search", this::stopSearch);
            Button clear = Ui.iconButton(Icons.CLOSE, "text_secondary", "Clear", () -> search.setText(""));
            clear.visibleProperty().bind(search.textProperty().isNotEmpty());
            toolbar.getChildren().setAll(close, search, clear);
        } else {
            toolbar.getChildren().setAll(title, Ui.spacer(),
                    Ui.iconButton(Icons.SEARCH, "text_primary", "Search (Ctrl+F)", this::startSearch),
                    gap(12),
                    Ui.iconButton(Icons.PERSON, Account.signedIn() ? "brand_yellow" : "text_primary", "Account",
                            () -> NotesApp.open(new AccountView())));
        }
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

    private ToggleButton chip(ToggleGroup group, String label, String type, boolean selected) {
        ToggleButton chip = new ToggleButton(label);
        chip.getStyleClass().setAll("chip");
        chip.setToggleGroup(group);
        chip.setSelected(selected);
        chip.setFocusTraversable(false);
        if (type != null) {
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
            applyFilter();
        });
    }

    private void applyFilter() {
        List<NoteEntity> shown = new ArrayList<>();
        String q = query.toLowerCase(Locale.getDefault());
        for (NoteEntity n : allNotes) {
            if (filterType != null && !filterType.equals(n.getType())) continue;
            if (!q.isEmpty()) {
                String hay = ((n.getTitle() == null ? "" : n.getTitle()) + "\n"
                        + (n.getContent() == null ? "" : n.getContent())).toLowerCase(Locale.getDefault());
                if (!hay.contains(q)) continue;
            }
            shown.add(n);
        }

        // Unchanged notes keep their card (and its loaded pictures); the rest are rebuilt.
        Map<Integer, NoteCard> next = new HashMap<>();
        List<Node> nodes = new ArrayList<>();
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

        emptyState.setVisible(shown.isEmpty());
        emptyText.setText(!q.isEmpty() ? "Nothing matches “" + query + "”"
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
        Ui.dialog().title(name).items(
                List.of(note.isPinned() ? "Unpin" : "Pin to top", "Colour", "Delete"),
                List.of(() -> NoteStore.write(s -> s.setPinned(note.getId(), !note.isPinned())),
                        () -> pickColor(note),
                        () -> deleteWithUndo(note))).show();
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
