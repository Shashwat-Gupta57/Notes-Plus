package org.flexstudios.notes.plus;

import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The gallery vault, the phone's SecretGalleryActivity: everything in it under day headers,
 * its albums (Favourites first), and the actions on a selection (share, move back out, add
 * to an album, delete with Undo).
 *
 * Desktop ways in for the phone's touches: right-click (or Ctrl+click) starts selecting,
 * Shift+click selects a range, dragging across cells while selecting selects them all, and
 * Ctrl+wheel changes the cell size like a pinch. Files dropped or pasted onto it are secured.
 */
final class GalleryView extends Screen {

    private static final String PREF_CELL = "gallery_cell";
    private static final double GAP = 2, SELECTED_SCALE = 0.86;
    private static final int UNDO_MS = 5000;

    final int vaultId;
    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private Backdrop backdrop;
    private final Region glow = new Region();
    private final Label title = new Label("Notes+");
    private final HBox toolbar = new HBox();
    private final Ui.Tabs tabs = new Ui.Tabs("brand_yellow", "All", "Albums");
    private final ListView<Row> grid = new ListView<>();
    private final TilePane albums = new TilePane();
    private final ScrollPane albumScroll = new ScrollPane(albums);
    private final VBox empty = new VBox(16);
    private final HBox selectionBar = new HBox();
    private final Button fab = new Button();

    private Integer filterAlbum;
    private boolean filterFavourites;
    private String filterTitle = "Notes+";
    private List<VaultStore.Item> items = new ArrayList<>();
    private final Map<Long, List<String>> namesByDay = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;
    private String anchor;
    private Boolean dragSelecting;
    private final Set<String> pendingDelete = new HashSet<>();
    private List<String> undoable;
    private int columns = 4;
    private double cell = 100;
    private int loadGeneration;

    /** A row of the grid: a day's header, or a line of cells. */
    sealed interface Row permits Header, Cells {}

    record Header(long day, String label) implements Row {}

    record Cells(List<VaultStore.Item> items) implements Row {}

    GalleryView(int vaultId) {
        this.vaultId = vaultId;
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        glow.setStyle("-fx-background-color: radial-gradient(center 50% 50%, radius 50%, #FFCC00FF, #FFCC0000);");
        glow.setOpacity(0.2);
        glow.setMinSize(200, 200);
        glow.setMaxSize(200, 200);
        glow.setMouseTransparent(true);
        StackPane.setAlignment(glow, Pos.TOP_LEFT);
        StackPane.setMargin(glow, new Insets(160, 0, 0, -50));

        title.setStyle("-fx-font-size: 22px; -fx-text-fill: " + Theme.ref("m3_on_surface") + ";");
        toolbar.getStyleClass().add("toolbar");
        VBox appBar = new VBox(toolbar, tabs);
        appBar.setStyle("-fx-background-color: " + Theme.ref("m3_surface") + ";");
        tabs.selected.addListener((o, a, b) -> showTab());
        frame.setTop(appBar);

        setUpGrid();
        albums.setPadding(new Insets(8));
        albums.setHgap(16);
        albums.setVgap(16);
        albums.setPrefTileWidth(220);
        albumScroll.setFitToWidth(true);
        albumScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        albumScroll.setVisible(false);
        albumScroll.setPadding(new Insets(8));

        Label emptyIcon = new Label();
        emptyIcon.setGraphic(Ui.icon(Icons.EMPTY_GALLERY, 80, "text_secondary"));
        empty.getChildren().addAll(emptyIcon, Ui.label("No private media yet", 16, "secondary"));
        empty.setAlignment(Pos.CENTER);
        empty.setMouseTransparent(true);
        empty.setVisible(false);
        frame.setCenter(new StackPane(grid, albumScroll, empty));

        buildSelectionBar();
        fab.getStyleClass().setAll("round-fab");
        fab.setStyle("-fx-background-color: " + Theme.ref("brand_yellow") + ";");
        fab.setGraphic(Ui.icon(Icons.ADD, 24, "on_brand_yellow"));
        fab.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        fab.setMinSize(56, 56);
        fab.setMaxSize(56, 56);
        fab.setFocusTraversable(false);
        fab.setOnAction(e -> {
            if (tabs.selected.get() == 0 || filterAlbum != null || filterFavourites) pickFiles();
            else createAlbum(null);
        });
        StackPane.setAlignment(fab, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(fab, new Insets(24));
        StackPane.setAlignment(selectionBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(selectionBar, new Insets(16));

        root.getChildren().addAll(glow, frame, selectionBar, fab);
        root.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        root.setOnDragDropped(e -> {
            List<File> files = new ArrayList<>();
            if (e.getDragboard().hasFiles()) for (File f : e.getDragboard().getFiles()) if (VaultMedia.isMedia(f.getName())) files.add(f);
            e.setDropCompleted(!files.isEmpty());
            importFiles(files);
        });
        updateChrome();
    }

    @Override
    Node view() {
        return root;
    }

    @Override
    void onResume() {
        applyBackground();
        // The viewer or settings may have deleted, favourited or moved items.
        if (grid.isVisible()) loadGallery(false);
        else loadAlbums();
        Platform.runLater(grid::requestFocus);
    }

    @Override
    void onPause() {
        if (backdrop != null) backdrop.stop();
    }

    @Override
    void onDestroy() {
        commitPendingDelete();
        Ui.closeSnack(false);
    }

    /** Selection, then filter, then leave the vault for the notes. */
    @Override
    boolean onBack() {
        if (selecting) {
            setSelecting(false);
            return true;
        }
        if (filterAlbum != null || filterFavourites) {
            clearFilter();
            return true;
        }
        commitPendingDelete();
        Vault.close();
        return false;
    }

    @Override
    void onKey(KeyEvent e) {
        if (e.isShortcutDown() && e.getCode() == KeyCode.A && grid.isVisible()) {
            e.consume();
            if (!selecting) setSelecting(true);
            selectAll(selected.size() != items.size());
        } else if (e.getCode() == KeyCode.DELETE && selecting && !selected.isEmpty()) {
            e.consume();
            deleteWithUndo(new ArrayList<>(selected));
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.V) {
            List<File> files = new ArrayList<>();
            var cb = javafx.scene.input.Clipboard.getSystemClipboard();
            if (cb.hasFiles()) for (File f : cb.getFiles()) if (VaultMedia.isMedia(f.getName())) files.add(f);
            if (!files.isEmpty()) {
                e.consume();
                importFiles(files);
            }
        }
    }

    // =================================================================== grid

    private void setUpGrid() {
        cell = Prefs.getLong(PREF_CELL) > 0 ? Prefs.getLong(PREF_CELL) : 160;
        grid.getStyleClass().add("vault-grid");
        grid.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 2 2 96 2;");
        grid.setFocusTraversable(true);
        grid.setCellFactory(v -> new RowCell());
        grid.widthProperty().addListener((o, a, b) -> relayout());
        // Ctrl+wheel (or a pinch) changes the cell size, like the phone's pinch.
        grid.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!e.isShortcutDown() || e.getDeltaY() == 0) return;
            e.consume();
            resize(e.getDeltaY() > 0 ? 1.12 : 1 / 1.12);
        });
        grid.addEventFilter(javafx.scene.input.ZoomEvent.ZOOM, e -> {
            e.consume();
            resize(e.getZoomFactor());
        });
    }

    private void resize(double factor) {
        cell = Math.max(70, Math.min(420, cell * factor));
        Prefs.put(PREF_CELL, (long) cell);
        relayout();
    }

    /** Reads the current filter and lays it out under day headers. */
    private void loadGallery(boolean toTop) {
        int generation = ++loadGeneration;
        Integer album = filterAlbum;
        boolean favs = filterFavourites;
        Set<String> hidden = new HashSet<>(pendingDelete);
        VaultStore.load(s -> favs ? s.favouriteRows(vaultId) : album != null ? s.albumRows(vaultId, album) : s.galleryRows(vaultId),
                rows -> {
                    if (generation != loadGeneration || rows == null) return;
                    List<VaultStore.Item> visible = new ArrayList<>();
                    for (VaultStore.Item r : rows) if (!hidden.contains(r.fileName())) visible.add(r);
                    items = visible;
                    selected.removeIf(n -> visible.stream().noneMatch(i -> i.fileName().equals(n)));
                    relayout();
                    if (toTop && !grid.getItems().isEmpty()) grid.scrollTo(0);
                    empty.setVisible(grid.isVisible() && items.isEmpty());
                    updateChrome();
                });
    }

    /** Rows of cells for the current width and cell size. */
    private void relayout() {
        double width = grid.getWidth() - 4 - 12;
        if (width <= 0) width = 800;
        columns = Math.max(2, (int) Math.round(width / cell));
        double size = width / columns;
        namesByDay.clear();
        List<Row> rows = new ArrayList<>();
        Labeler labeler = new Labeler(System.currentTimeMillis());
        long day = Long.MIN_VALUE;
        List<VaultStore.Item> line = new ArrayList<>();
        for (VaultStore.Item item : items) {
            long d = startOfDay(item.dateTaken());
            if (d != day) {
                if (!line.isEmpty()) rows.add(new Cells(line));
                line = new ArrayList<>();
                day = d;
                rows.add(new Header(d, labeler.label(d)));
            }
            namesByDay.computeIfAbsent(d, k -> new ArrayList<>()).add(item.fileName());
            line.add(item);
            if (line.size() == columns) {
                rows.add(new Cells(line));
                line = new ArrayList<>();
            }
        }
        if (!line.isEmpty()) rows.add(new Cells(line));
        cellSize = size;
        grid.getItems().setAll(rows);
    }

    private double cellSize = 100;

    private final class RowCell extends ListCell<Row> {
        RowCell() {
            setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        }

        @Override
        protected void updateItem(Row row, boolean emptyRow) {
            super.updateItem(row, emptyRow);
            setText(null);
            if (emptyRow || row == null) {
                setGraphic(null);
                return;
            }
            if (row instanceof Header h) setGraphic(header(h));
            else {
                HBox line = new HBox();
                for (VaultStore.Item item : ((Cells) row).items()) line.getChildren().add(new Tile(item, cellSize));
                setGraphic(line);
            }
        }
    }

    private Node header(Header h) {
        HBox chip = new HBox(Ui.label(h.label(), 13));
        chip.getStyleClass().add("date-chip");
        HBox row = new HBox(chip, Ui.spacer());
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8));
        if (selecting) {
            List<String> names = namesByDay.getOrDefault(h.day(), List.of());
            boolean all = !names.isEmpty() && selected.containsAll(names);
            StackPane check = checkCircle(all);
            check.setStyle("-fx-cursor: hand;");
            check.setOnMouseClicked(e -> {
                if (all) selected.removeAll(names);
                else selected.addAll(names);
                refreshSelection();
            });
            row.getChildren().add(check);
        }
        return row;
    }

    private static StackPane checkCircle(boolean on) {
        StackPane c = on ? new StackPane(Ui.icon(Icons.CHECK_DISC, 24, "accent_blue"), Ui.icon(Icons.CHECK_TICK, 24, "white"))
                : new StackPane(Ui.icon(Icons.CHECK_CIRCLE_EMPTY, 24, "white"));
        c.setMinSize(24, 24);
        c.setMaxSize(24, 24);
        if (!on) c.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 3, 0, 0, 0);");
        return c;
    }

    /** One square cell: thumbnail, favourite heart, video length, selection. */
    private final class Tile extends StackPane {
        final VaultStore.Item item;
        private final StackPane content = new StackPane();

        Tile(VaultStore.Item item, double size) {
            this.item = item;
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
            double inner = size - GAP;
            content.getStyleClass().add("vault-cell");
            content.setMinSize(inner, inner);
            content.setMaxSize(inner, inner);
            Rectangle clip = new Rectangle(inner, inner);
            clip.setArcWidth(12);
            clip.setArcHeight(12);
            content.setClip(clip);
            ImageView image = new ImageView();
            image.setFitWidth(inner);
            image.setFitHeight(inner);
            image.setSmooth(true);
            content.getChildren().add(image);
            Thumbs.load(item, img -> {
                if (img == null) return;
                double side = Math.min(img.getWidth(), img.getHeight());
                image.setViewport(new Rectangle2D((img.getWidth() - side) / 2, (img.getHeight() - side) / 2, side, side));
                image.setImage(img);
                image.setOpacity(0);
                javafx.animation.FadeTransition in = new javafx.animation.FadeTransition(Duration.millis(140), image);
                in.setToValue(1);
                in.play();
            });
            if (item.favourite()) {
                Node heart = Ui.icon(Icons.HEART_FILLED, 16, "white");
                StackPane.setAlignment(heart, Pos.BOTTOM_LEFT);
                StackPane.setMargin(heart, new Insets(6));
                content.getChildren().add(heart);
            }
            if (item.video()) {
                long ms = item.durationMs() > 0 ? item.durationMs() : Thumbs.peekDuration(item.fileName());
                HBox badge = new HBox(2, Ui.icon(Icons.PLAY, 14, "white"));
                if (ms > 0) badge.getChildren().add(new Label(NoteData.formatDuration(ms)));
                badge.getStyleClass().add("duration-badge");
                badge.setAlignment(Pos.CENTER_LEFT);
                badge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
                StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
                StackPane.setMargin(badge, new Insets(5));
                content.getChildren().add(badge);
            }
            getChildren().add(content);
            boolean on = selected.contains(item.fileName());
            if (selecting) {
                if (on) {
                    Region scrim = new Region();
                    scrim.setStyle("-fx-background-color: #33000000;");
                    content.getChildren().add(scrim);
                }
                StackPane check = checkCircle(on);
                StackPane.setAlignment(check, Pos.TOP_LEFT);
                StackPane.setMargin(check, new Insets(6));
                getChildren().add(check);
            }
            content.setScaleX(on ? SELECTED_SCALE : 1);
            content.setScaleY(on ? SELECTED_SCALE : 1);

            setOnMouseClicked(this::clicked);
            setOnContextMenuRequested(e -> {
                if (!selecting) setSelecting(true);
                if (!selected.contains(item.fileName())) toggle(item.fileName(), false);
            });
            setOnDragDetected(e -> {
                if (!selecting || e.getButton() != MouseButton.PRIMARY) return;
                dragSelecting = !selected.contains(item.fileName());
                startFullDrag();
                apply(item.fileName());
            });
            setOnMouseDragEntered(e -> {
                if (dragSelecting != null) apply(item.fileName());
            });
            setOnMouseReleased(e -> dragSelecting = null);
        }

        private void apply(String name) {
            boolean has = selected.contains(name);
            if (dragSelecting && !has) selected.add(name);
            else if (!dragSelecting && has) selected.remove(name);
            else return;
            refreshSelection();
        }

        private void clicked(MouseEvent e) {
            if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress()) return;
            if (!selecting && e.isShortcutDown()) {
                setSelecting(true);
                toggle(item.fileName(), false);
            } else if (selecting && e.isShiftDown() && anchor != null) {
                selectRange(anchor, item.fileName());
            } else if (selecting) {
                toggle(item.fileName(), true);
            } else {
                openViewer(item);
            }
        }
    }

    private void toggle(String name, boolean animate) {
        if (!selected.remove(name)) selected.add(name);
        anchor = name;
        refreshSelection();
    }

    private void selectRange(String from, String to) {
        int a = -1, b = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).fileName().equals(from)) a = i;
            if (items.get(i).fileName().equals(to)) b = i;
        }
        if (a < 0 || b < 0) return;
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) selected.add(items.get(i).fileName());
        refreshSelection();
    }

    private void selectAll(boolean all) {
        selected.clear();
        if (all) for (VaultStore.Item i : items) selected.add(i.fileName());
        refreshSelection();
    }

    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) selected.clear();
        refreshSelection();
    }

    private void refreshSelection() {
        grid.refresh();
        updateChrome();
    }

    // ================================================================= chrome

    /** Title, navigation, selection bar and FAB, for the current mode. */
    private void updateChrome() {
        boolean filtered = filterAlbum != null || filterFavourites;
        toolbar.getChildren().clear();
        if (selecting) {
            toolbar.getChildren().add(Ui.iconButton(Icons.CLOSE, "m3_on_surface", "Done (Esc)", () -> setSelecting(false)));
            title.setText(selected.isEmpty() ? "Select items" : selected.size() + " selected");
        } else {
            // Inside an album: back to all. Otherwise: out of the vault, to the notes.
            toolbar.getChildren().add(filtered
                    ? Ui.iconButton(Icons.ARROW_BACK, "m3_on_surface", "Back (Esc)", this::clearFilter)
                    : Ui.iconButton(Icons.ARROW_BACK, "m3_on_surface", "Back to notes (Esc)", NotesApp::back));
            title.setText(filterTitle);
        }
        HBox.setMargin(title, new Insets(0, 0, 0, 4));
        toolbar.getChildren().addAll(title, Ui.spacer());
        if (selecting) {
            boolean all = !items.isEmpty() && selected.size() == items.size();
            Button selectAll = new Button(null, checkCircleTinted(all));
            selectAll.getStyleClass().setAll("icon-button");
            selectAll.setMinSize(48, 48);
            selectAll.setOnAction(e -> selectAll(!all));
            Ui.tooltip(selectAll, "Select all (Ctrl+A)");
            toolbar.getChildren().add(selectAll);
        } else {
            toolbar.getChildren().add(Ui.iconButton(Icons.GEAR, "m3_on_surface", "Settings",
                    () -> NotesApp.open(new VaultSettingsView(vaultId))));
        }
        tabs.setVisible(!filtered);
        tabs.setManaged(!filtered);
        slide(selectionBar, selecting);
        fab.setVisible(!selecting);
        Ui.tooltip(fab, tabs.selected.get() == 1 && !filtered ? "New album" : "Add photos or videos (or drop them here)");
    }

    private static Node checkCircleTinted(boolean on) {
        return on ? new StackPane(Ui.icon(Icons.CHECK_DISC, 24, "accent_blue"), Ui.icon(Icons.CHECK_TICK, 24, "white"))
                : new StackPane(Ui.icon(Icons.CHECK_CIRCLE_EMPTY, 24, "m3_on_surface"));
    }

    private void buildSelectionBar() {
        selectionBar.getStyleClass().add("selection-bar");
        selectionBar.setStyle("-fx-background-color: " + Theme.ref("m3_surface") + ";");
        selectionBar.setMaxHeight(Region.USE_PREF_SIZE);
        selectionBar.setMaxWidth(720);
        selectionBar.setAlignment(Pos.CENTER);
        Object[][] actions = {
                {Icons.SHARE, "m3_on_surface", "Share", (Runnable) () -> share(new ArrayList<>(selected))},
                {Icons.MOVE_TO_GALLERY, "m3_on_surface", "Move back to this computer", (Runnable) () -> moveOut(new ArrayList<>(selected))},
                {Icons.ALBUM, "m3_on_surface", "Add to album", (Runnable) () -> chooseAlbum(new ArrayList<>(selected))},
                {Icons.DELETE, "destructive", "Delete (Del)", (Runnable) () -> deleteWithUndo(new ArrayList<>(selected))}};
        for (Object[] a : actions) {
            Button b = Ui.iconButton((Icons.Icon) a[0], (String) a[1], (String) a[2], (Runnable) a[3]);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setPrefHeight(48);
            HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
            selectionBar.getChildren().add(b);
        }
        selectionBar.setVisible(false);
    }

    private static void slide(Region bar, boolean show) {
        if (show == bar.isVisible()) return;
        TranslateTransition t = new TranslateTransition(Duration.millis(show ? 220 : 180), bar);
        if (show) {
            bar.setVisible(true);
            bar.setTranslateY(120);
            t.setToY(0);
        } else {
            t.setToY(120);
            t.setOnFinished(e -> bar.setVisible(false));
        }
        t.play();
    }

    private void showTab() {
        boolean all = tabs.selected.get() == 0;
        if (selecting) setSelecting(false);
        grid.setVisible(all);
        albumScroll.setVisible(!all);
        if (all) loadGallery(false);
        else loadAlbums();
        updateChrome();
    }

    // ================================================================ actions

    private void openViewer(VaultStore.Item item) {
        int index = items.indexOf(item);
        NotesApp.open(new ViewerView(vaultId, new ArrayList<>(items), Math.max(0, index)));
    }

    /** Decrypted copies, to paste into any app or save in a folder. */
    private void share(List<String> names) {
        if (names.isEmpty()) return;
        Ui.dialog().title("Share " + names.size() + (names.size() == 1 ? " item" : " items"))
                .items(List.of("Copy, to paste into another app", "Save copies to a folder…"),
                        List.of(() -> exportTo(names, null), () -> {
                            DirectoryChooser chooser = new DirectoryChooser();
                            chooser.setTitle("Save copies to");
                            Vault.expectExternal();
                            File dir = chooser.showDialog(NotesApp.stage());
                            if (dir != null) exportTo(names, dir.toPath());
                        })).show();
    }

    /** {@code dir} null: a fresh share folder, and the files go on the clipboard. */
    static void exportTo(List<String> names, Path dir) {
        VaultStore.load(s -> {
            List<File> out = new ArrayList<>();
            Path target = dir;
            if (target == null) {
                VaultCrypto.wipeShareDir(); // Retire copies from an earlier share.
                target = VaultCrypto.shareDir();
            }
            for (String n : names) {
                VaultStore.Secret secret = s.secret(n);
                if (secret == null) continue;
                try {
                    out.add(VaultMedia.export(secret, target).toFile());
                } catch (Exception e) {
                    System.getLogger("Vault").log(System.Logger.Level.WARNING, "Export failed", e);
                }
            }
            return out;
        }, files -> {
            if (files == null || files.isEmpty()) {
                Ui.toast("Failed to share");
                return;
            }
            if (dir == null) {
                ClipboardContent c = new ClipboardContent();
                c.putFiles(files);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
                Ui.snackbar("Copied " + files.size() + (files.size() == 1 ? " item" : " items") + ". Paste it where you like.", Ui.LONG);
            } else {
                Ui.snackbar("Saved " + files.size() + (files.size() == 1 ? " copy" : " copies"), Ui.LONG);
            }
        });
    }

    private void moveOut(List<String> names) {
        if (names.isEmpty()) return;
        setSelecting(false);
        VaultStore.load(s -> {
            int moved = 0;
            for (String n : names) {
                VaultStore.Secret secret = s.secret(n);
                if (secret == null) continue;
                try {
                    VaultMedia.moveOut(secret);
                    moved++;
                } catch (Exception e) {
                    System.getLogger("Vault").log(System.Logger.Level.WARNING, "Move out failed", e);
                }
            }
            return moved;
        }, moved -> {
            Ui.snackbar(moved == names.size()
                    ? "Moved " + moved + (moved == 1 ? " item" : " items") + " back to this computer"
                    : "Moved " + moved + " of " + names.size() + " back to this computer", Ui.LONG);
            loadGallery(false);
        });
    }

    /** Hides the items now and erases them when the undo bar goes away. */
    private void deleteWithUndo(List<String> names) {
        if (names.isEmpty()) return;
        commitPendingDelete(); // One undoable batch at a time.
        setSelecting(false);
        undoable = names;
        pendingDelete.addAll(names);
        loadGallery(false);
        Ui.snackbar(names.size() == 1 ? "Item deleted" : names.size() + " items deleted", Duration.millis(UNDO_MS), "Undo", () -> {
            pendingDelete.removeAll(names);
            undoable = null;
            loadGallery(false);
        }, byAction -> {
            if (!byAction && undoable == names) commitPendingDelete();
        });
    }

    private void commitPendingDelete() {
        List<String> names = undoable;
        if (names == null) return;
        undoable = null;
        VaultStore.load(s -> {
            for (String n : names) {
                VaultStore.Secret secret = s.secret(n);
                if (secret != null) VaultMedia.remove(secret);
            }
            return true;
        }, ok -> pendingDelete.removeAll(names));
    }

    // ================================================================= import

    private void pickFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add photos or videos");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Photos and videos",
                "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp", "*.heic", "*.mp4", "*.m4v", "*.mov", "*.3gp",
                "*.mkv", "*.webm", "*.avi", "*.wmv"));
        Vault.expectExternal();
        List<File> files = chooser.showOpenMultipleDialog(NotesApp.stage());
        if (files != null) importFiles(files);
    }

    /**
     * Encrypts the files into the vault, then offers to put the originals in the Recycle Bin.
     * Each vault file is written whole before its row exists, and an original is only offered
     * for removal once its copy is safe.
     */
    private void importFiles(List<File> files) {
        if (files.isEmpty()) return;
        Integer album = filterAlbum;
        Label progress = Ui.label("0 of " + files.size(), 14, "secondary");
        Ui.Modal dialog = Ui.dialog().title("Securing items").content(progress).show();
        List<File> secured = new ArrayList<>();
        VaultStore.IO.execute(() -> {
            long lastRefresh = 0;
            for (int i = 0; i < files.size(); i++) {
                int n = i + 1;
                Platform.runLater(() -> progress.setText(n + " of " + files.size()));
                try {
                    VaultMedia.importFile(files.get(i).toPath(), vaultId, album);
                    secured.add(files.get(i));
                } catch (Exception e) {
                    System.getLogger("Vault").log(System.Logger.Level.WARNING, "Failed to secure " + files.get(i), e);
                }
                long now = System.currentTimeMillis();
                if (now - lastRefresh > 800) {
                    lastRefresh = now;
                    Platform.runLater(() -> loadGallery(false));
                }
            }
            Platform.runLater(() -> {
                dialog.dismiss();
                Ui.snackbar("Secured " + secured.size() + " of " + files.size() + " items", Ui.LONG);
                loadGallery(false);
                if (!secured.isEmpty()) offerRecycle(secured);
            });
        });
    }

    private void offerRecycle(List<File> originals) {
        Ui.dialog().title("Remove the originals?")
                .message(originals.size() + (originals.size() == 1 ? " item is" : " items are")
                        + " now in the vault. Move the originals on this computer to the Recycle Bin?")
                .positive("Move to Recycle Bin", () -> VaultStore.IO.execute(() -> {
                    int kept = 0;
                    for (File f : originals) {
                        try {
                            if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().moveToTrash(f)) kept++;
                        } catch (Exception e) {
                            kept++;
                        }
                    }
                    int left = kept;
                    if (left > 0) Platform.runLater(() -> Ui.toast(left + " original(s) couldn't be moved"));
                }))
                .negative("Keep them", null).width(420).show();
    }

    // ================================================================= albums

    private record AlbumTile(int id, String name, int count, VaultStore.Secret cover) {}

    private void loadAlbums() {
        VaultStore.load(s -> {
            List<AlbumTile> out = new ArrayList<>();
            int favs = s.favouriteCount(vaultId);
            if (favs > 0) out.add(new AlbumTile(-1, "Favourites", favs, s.latestFavourite(vaultId)));
            for (VaultStore.Album a : s.albums(vaultId)) out.add(new AlbumTile(a.id, a.name, s.albumCount(a.id), s.latestInAlbum(a.id)));
            return out;
        }, list -> {
            if (list == null) return;
            albums.getChildren().clear();
            for (AlbumTile a : list) albums.getChildren().add(albumCard(a));
            if (albumScroll.isVisible()) empty.setVisible(list.isEmpty());
        });
    }

    private Node albumCard(AlbumTile a) {
        StackPane cover = new StackPane();
        cover.setStyle("-fx-background-color: " + Theme.ref("thumb_placeholder") + "; -fx-background-radius: 12 12 0 0;");
        cover.setMinHeight(150);
        cover.setPrefHeight(150);
        if (a.cover() != null) {
            VaultStore.Secret c = a.cover();
            Thumbs.load(new VaultStore.Item(c.fileName, c.video, c.dateTaken, c.favourite, c.durationMs), img -> {
                if (img != null) cover.getChildren().add(NoteCard.picture(img, true));
            });
        }
        Rectangle clip = new Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        Label name = Ui.label(a.name(), 16, "bold");
        Label count = Ui.label(a.count() + (a.count() == 1 ? " item" : " items"), 14, "secondary");
        VBox text = new VBox(name, count);
        text.setPadding(new Insets(12));
        VBox card = new VBox(cover, text);
        card.getStyleClass().add("album-card");
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        cover.setClip(null);
        card.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (a.id() == -1) showFiltered(null, true, "Favourites");
            else showFiltered(a.id(), false, a.name());
        });
        card.setOnContextMenuRequested(e -> {
            if (a.id() != -1) albumOptions(a);
        });
        return card;
    }

    private void showFiltered(Integer albumId, boolean favourites, String name) {
        filterAlbum = albumId;
        filterFavourites = favourites;
        filterTitle = name;
        grid.setVisible(true);
        albumScroll.setVisible(false);
        empty.setVisible(false);
        updateChrome();
        loadGallery(true);
    }

    private void clearFilter() {
        filterAlbum = null;
        filterFavourites = false;
        filterTitle = "Notes+";
        grid.setVisible(false);
        albumScroll.setVisible(true);
        tabs.selected.set(1);
        loadAlbums();
        updateChrome();
    }

    private void albumOptions(AlbumTile a) {
        Ui.dialog().title(a.name()).items(List.of("Rename", "Delete Album"), List.of(
                () -> Ui.prompt("Rename album", "Album name", a.name(), "Save",
                        name -> VaultStore.load(s -> {
                            s.renameAlbum(a.id(), name);
                            return true;
                        }, ok -> loadAlbums())),
                () -> Ui.dialog().title("Delete Album?").message("Items inside will not be deleted.")
                        .positive("Delete", () -> VaultStore.load(s -> {
                            s.deleteAlbum(a.id());
                            return true;
                        }, ok -> loadAlbums()))
                        .negative("Cancel", null).show())).show();
    }

    private void chooseAlbum(List<String> names) {
        if (names.isEmpty()) return;
        VaultStore.load(s -> s.albums(vaultId), list -> {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add("+ New album");
            actions.add(() -> createAlbum(names));
            for (VaultStore.Album a : list) {
                labels.add(a.name);
                actions.add(() -> moveToAlbum(names, a));
            }
            Ui.dialog().title("Add to album").items(labels, actions).show();
        });
    }

    private void createAlbum(List<String> names) {
        Ui.prompt("New album", "Album name", null, "Create", name -> VaultStore.load(s -> s.insertAlbum(name, vaultId), album -> {
            if (names != null) moveToAlbum(names, album);
            else loadAlbums();
        }));
    }

    private void moveToAlbum(List<String> names, VaultStore.Album album) {
        setSelecting(false);
        VaultStore.load(s -> {
            s.setAlbum(names, album.id);
            return true;
        }, ok -> {
            Ui.snackbar("Added to " + album.name, Ui.SHORT);
            loadGallery(false);
        });
    }

    // ============================================================ background

    private void applyBackground() {
        VaultStore.load(s -> s.vault(vaultId), v -> {
            if (backdrop != null) {
                backdrop.stop();
                root.getChildren().remove(backdrop);
            }
            backdrop = v == null ? null : new Backdrop(v.bgType, v.bgValue, false, false);
            boolean custom = backdrop != null && !backdrop.isEmpty();
            glow.setVisible(!custom);
            if (custom) root.getChildren().add(0, backdrop);
        });
    }

    // ================================================================ helpers

    static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** "Today", "Yesterday", "Monday", "Tue, 12 March", "12 March 2023". */
    static final class Labeler {
        private final long today, yesterday, weekAgo;
        private final int thisYear;
        private final Calendar cal = Calendar.getInstance();
        private final SimpleDateFormat weekday = new SimpleDateFormat("EEEE", Locale.getDefault());
        private final SimpleDateFormat sameYear = new SimpleDateFormat("EEE, d MMMM", Locale.getDefault());
        private final SimpleDateFormat otherYear = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());

        Labeler(long now) {
            today = startOfDay(now);
            cal.setTimeInMillis(today);
            cal.add(Calendar.DAY_OF_YEAR, -1);
            yesterday = cal.getTimeInMillis();
            cal.setTimeInMillis(today);
            cal.add(Calendar.DAY_OF_YEAR, -6);
            weekAgo = cal.getTimeInMillis();
            cal.setTimeInMillis(now);
            thisYear = cal.get(Calendar.YEAR);
        }

        String label(long day) {
            if (day == today) return "Today";
            if (day == yesterday) return "Yesterday";
            Date date = new Date(day);
            if (day >= weekAgo && day < today) return weekday.format(date);
            cal.setTimeInMillis(day);
            return cal.get(Calendar.YEAR) == thisYear ? sameYear.format(date) : otherYear.format(date);
        }
    }
}
