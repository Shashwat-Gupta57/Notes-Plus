package org.flexstudios.notes.plus;

import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The written note types, the phone's NoteEditorActivity:
 * <ul>
 *   <li><b>Text</b>: a title and words.</li>
 *   <li><b>List</b>: items to tick off; Enter adds the next one, the handle reorders,
 *       Backspace on an empty item (or its ×) deletes it.</li>
 *   <li><b>Journal</b>: a day, with a big date, a mood, words, and photos or videos.</li>
 * </ul>
 */
final class NoteEditorView extends NoteEditor {
    private static final String[] MOODS = {"😄", "🙂", "😐", "😔", "😢", "😡", "😴", "🤩", "❤️"};

    private final VBox column = new VBox();
    private final ScrollPane scroll;
    private final TextArea title = new TextArea();
    private RichBody body;
    private final Label footer = new Label();

    // List
    private final List<NoteData.TodoItem> items = new ArrayList<>();
    private final VBox itemRows = new VBox();
    private final Label listProgress = Ui.label("", 13, "secondary");
    private final Region progressFill = new Region();
    private double progress;

    // Journal
    private NoteData.Journal journal;
    private final HBox moodRow = new HBox(6);
    private final HBox mediaStrip = new HBox(8);
    private final ScrollPane mediaScroll = new ScrollPane(mediaStrip);
    private final Label journalDay = new Label(), journalWeekday = new Label(), journalMonth = new Label();

    NoteEditorView(String type, int noteId) {
        super(type, noteId);
        title.getStyleClass().addAll("bare", "bold");
        title.setStyle("-fx-font-size: 26px;");
        title.setWrapText(true);
        Ui.autoGrow(title, 0, 0);
        Links.install(title);
        // Enter in the title moves on to the words, as the next field would on a keyboard.
        title.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                if (NoteData.LIST.equals(type)) focusItem(0, false);
                else body.focusStart();
            }
        });

        column.setMaxWidth(780);
        column.setPadding(new Insets(0, 0, 24, 0));
        StackPane page = new StackPane(column);
        StackPane.setAlignment(column, Pos.TOP_CENTER);
        scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // A click below the words continues them, like tapping a page.
        page.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getTarget() == page && !NoteData.LIST.equals(type)) {
                body.focusEnd();
            }
        });
        body = new RichBody(this::withFolder, scroll);
        body.setOnChange(this::updateFooter);

        footer.getStyleClass().add("secondary");
        footer.setStyle("-fx-font-size: 12px;");
        footer.setPadding(new Insets(8));
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);

        javafx.scene.layout.BorderPane content = new javafx.scene.layout.BorderPane(scroll);
        content.setBottom(footer);
        setUpNote(content);
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        column.getChildren().clear();
        if (NoteData.JOURNAL.equals(type)) column.getChildren().add(journalHeader(note));
        title.setText(note.getTitle());
        title.setPromptText(NoteData.JOURNAL.equals(type) ? "Give the day a title"
                : NoteData.LIST.equals(type) ? "List name" : "Title");
        column.getChildren().add(padded(title, new Insets(12, 20, 8, 20)));
        if (NoteData.LIST.equals(type)) {
            column.getChildren().add(listSection(note));
        } else {
            body.setContent(note.getContent(), note.getId() > 0 ? NoteData.dir(note.getId()) : null);
            if (NoteData.JOURNAL.equals(type)) body.setPrompt("What happened today?");
            column.getChildren().add(body);
        }
        updateFooter();
        if (note.getId() <= 0) {
            Platform.runLater(() -> {
                if (NoteData.JOURNAL.equals(type)) body.focusStart();
                else title.requestFocus();
            });
        }
    }

    @Override
    protected boolean collect(NoteEntity note) {
        String t = title.getText().trim();
        note.setTitle(t);
        if (NoteData.LIST.equals(type)) {
            StringBuilder text = new StringBuilder();
            List<NoteData.TodoItem> kept = new ArrayList<>();
            for (NoteData.TodoItem item : items) {
                if (item.text.trim().isEmpty()) continue;
                kept.add(item);
                text.append(item.text.trim()).append('\n');
            }
            note.setData(NoteData.todosJson(kept, wrapItems));
            note.setContent(text.toString().trim());
            return !kept.isEmpty() || !t.isEmpty();
        }
        String words = body.getContent().trim();
        note.setContent(words);
        if (NoteData.JOURNAL.equals(type)) {
            note.setData(NoteData.journalJson(journal));
            return !words.isEmpty() || !t.isEmpty() || !journal.media.isEmpty() || !journal.mood.isEmpty();
        }
        return !words.isEmpty() || !t.isEmpty();
    }

    private void updateFooter() {
        if (note == null) return;
        String edited = note.getId() > 0
                ? "Edited " + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(note.getTimestamp()))
                : "New " + NoteData.kind(type).label().toLowerCase(Locale.getDefault());
        if (NoteData.LIST.equals(type)) {
            footer.setText(edited);
            return;
        }
        String text = body.words();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        footer.setText(edited + "  ·  " + words + (words == 1 ? " word" : " words"));
    }

    private static Node padded(Node node, Insets insets) {
        VBox box = new VBox(node);
        box.setPadding(insets);
        return box;
    }

    // ===================================================================== list

    /** List items wrap onto more lines (the default) or each stays on one line. */
    private boolean wrapItems = true;

    @Override
    protected Boolean wrapText() {
        return NoteData.LIST.equals(type) ? wrapItems : null;
    }

    @Override
    protected void setWrapText(boolean wrap) {
        wrapItems = wrap;
        itemRows.getChildren().clear();
        for (NoteData.TodoItem item : items) itemRows.getChildren().add(new ItemRow(item));
    }

    private Node listSection(NoteEntity note) {
        items.clear();
        items.addAll(NoteData.todos(note.getData()));
        wrapItems = NoteData.wraps(note.getData());
        if (items.isEmpty()) items.add(new NoteData.TodoItem("", false));

        StackPane track = new StackPane(progressFill);
        track.getStyleClass().add("progress-track");
        track.setMinHeight(4);
        track.setMaxHeight(4);
        progressFill.getStyleClass().add("progress-fill");
        progressFill.setMaxWidth(0);
        StackPane.setAlignment(progressFill, Pos.CENTER_LEFT);
        track.widthProperty().addListener((o, a, b) -> progressFill.setMaxWidth(b.doubleValue() * progress));
        VBox.setMargin(track, new Insets(0, 20, 0, 20));
        VBox.setMargin(listProgress, new Insets(6, 0, 0, 20));
        VBox.setMargin(itemRows, new Insets(8, 0, 0, 0));

        itemRows.getChildren().clear();
        for (NoteData.TodoItem item : items) itemRows.getChildren().add(new ItemRow(item));

        HBox add = new HBox(Ui.icon(Icons.ADD, 24, "tile_green"), Ui.label("Add item", 17, "secondary"));
        add.getStyleClass().add("add-row");
        add.setOnMouseClicked(e -> {
            if (!readOnly) addItemAt(items.size());
        });

        updateProgress();
        return new VBox(track, listProgress, itemRows, add);
    }

    private void addItemAt(int position) {
        NoteData.TodoItem item = new NoteData.TodoItem("", false);
        items.add(position, item);
        itemRows.getChildren().add(position, new ItemRow(item));
        updateProgress();
        focusItem(position, false);
    }

    private void removeItemAt(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        itemRows.getChildren().remove(position);
        updateProgress();
    }

    private void focusItem(int position, boolean atEnd) {
        if (position < 0 || position >= itemRows.getChildren().size()) return;
        javafx.scene.control.TextInputControl field = ((ItemRow) itemRows.getChildren().get(position)).text;
        Platform.runLater(() -> {
            field.requestFocus();
            if (atEnd) field.end();
            else field.selectAll();
        });
    }

    private void updateProgress() {
        int done = 0, total = 0;
        for (NoteData.TodoItem item : items) {
            if (item.text.trim().isEmpty()) continue;
            total++;
            if (item.done) done++;
        }
        boolean all = total > 0 && done == total;
        listProgress.setText(total == 0 ? "Nothing yet" : all ? "All done" : done + " of " + total + " done");
        listProgress.setGraphic(all ? Emoji.view("🎉", 15) : null);
        listProgress.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        double from = progress, to = total == 0 ? 0 : (double) done / total;
        progress = to;
        Region track = (Region) progressFill.getParent();
        if (track == null) return;
        Ui.animate(300, Interpolator.EASE_OUT, f -> progressFill.setMaxWidth(track.getWidth() * (from + (to - from) * f)));
    }

    /** One list item: drag handle, tick box, the text, and × on hover. */
    private final class ItemRow extends HBox {
        final NoteData.TodoItem item;
        /** A growing text area when the list wraps, otherwise a one-line field. */
        final javafx.scene.control.TextInputControl text;
        private final StackPane box = new StackPane();
        private final Ui.IconView tick = Ui.icon(Icons.CHECK, 16, "white");

        ItemRow(NoteData.TodoItem item) {
            this.item = item;
            if (wrapItems) {
                TextArea area = new TextArea();
                area.setPrefRowCount(1);
                text = area;
            } else {
                text = new TextField();
            }
            setAlignment(Pos.CENTER_LEFT);
            setMinHeight(48);
            setPadding(new Insets(0, 16, 0, 4));

            StackPane handle = new StackPane(Ui.icon(Icons.NOTE_LIST, 16, "divider"));
            handle.getStyleClass().add("list-handle");
            handle.setMinSize(32, 40);
            Ui.tooltip(handle, "Drag to reorder");
            dragToReorder(handle);

            box.getStyleClass().add("check");
            box.setMinSize(18, 18);
            box.setMaxSize(18, 18);
            box.getChildren().add(tick);
            StackPane target = new StackPane(box);
            target.setMinSize(44, 44);
            target.setStyle("-fx-cursor: hand;");
            target.setOnMouseClicked(e -> {
                if (!readOnly) setDone(!item.done);
            });

            text.getStyleClass().addAll("bare");
            text.setStyle("-fx-font-size: 17px; -fx-text-fill: " + Theme.ref("text_body") + ";");
            text.setPromptText("Item");
            text.setText(item.text);
            if (text instanceof TextArea area) Ui.autoGrow(area, 0, 0);
            Links.install(text);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.textProperty().addListener((o, a, b) -> {
                item.text = b;
                updateProgress();
            });
            text.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                int index = itemRows.getChildren().indexOf(this);
                if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                    e.consume();
                    setDone(!item.done);
                } else if (e.getCode() == KeyCode.ENTER) {
                    e.consume();
                    addItemAt(index + 1);
                } else if (e.getCode() == KeyCode.BACK_SPACE && text.getText().isEmpty() && items.size() > 1) {
                    e.consume();
                    removeItemAt(index);
                    focusItem(Math.max(0, index - 1), true);
                } else if (e.getCode() == KeyCode.UP) {
                    e.consume();
                    focusItem(index - 1, true);
                } else if (e.getCode() == KeyCode.DOWN) {
                    e.consume();
                    focusItem(index + 1, true);
                }
            });

            Button remove = Ui.iconButton(Icons.CLOSE, 32, 18, "text_secondary", "Delete item", () -> {
                int index = itemRows.getChildren().indexOf(this);
                removeItemAt(index);
                if (items.isEmpty()) addItemAt(0);
            });
            remove.visibleProperty().bind(hoverProperty().or(text.focusedProperty()));

            getChildren().addAll(handle, target, text, remove);
            styleDone();
        }

        private void setDone(boolean done) {
            item.done = done;
            styleDone();
            updateProgress();
            if (done) {
                ScaleTransition pop = new ScaleTransition(Duration.millis(90), box);
                pop.setToX(1.2);
                pop.setToY(1.2);
                pop.setAutoReverse(true);
                pop.setCycleCount(2);
                pop.play();
            }
        }

        private void styleDone() {
            if (item.done) {
                if (!box.getStyleClass().contains("on")) box.getStyleClass().add("on");
                if (!text.getStyleClass().contains("done")) text.getStyleClass().add("done");
            } else {
                box.getStyleClass().remove("on");
                text.getStyleClass().remove("done");
            }
            tick.setVisible(item.done);
            text.setOpacity(item.done ? 0.5 : 1);
        }

        /** Drags the row by its handle; the others make room as it passes their middle. */
        private void dragToReorder(Node handle) {
            handle.setOnMousePressed(e -> {
                setOpacity(0.7);
                e.consume();
            });
            handle.setOnMouseDragged(e -> {
                double y = itemRows.sceneToLocal(e.getSceneX(), e.getSceneY()).getY();
                int from = itemRows.getChildren().indexOf(this);
                int to = from;
                for (int i = 0; i < itemRows.getChildren().size(); i++) {
                    Node row = itemRows.getChildren().get(i);
                    double mid = row.getBoundsInParent().getMinY() + row.getBoundsInParent().getHeight() / 2;
                    if (i < from && y < mid) {
                        to = i;
                        break;
                    }
                    if (i > from && y > mid) to = i;
                }
                if (to != from) {
                    itemRows.getChildren().remove(this);
                    itemRows.getChildren().add(to, this);
                    items.remove(item);
                    items.add(to, item);
                }
            });
            handle.setOnMouseReleased(e -> setOpacity(1));
        }
    }

    // ================================================================== journal

    private Node journalHeader(NoteEntity note) {
        journal = NoteData.journal(note.getData(), startOfDay(note.getTimestamp()));
        body.setPrompt("What happened today?");

        journalDay.getStyleClass().add("light");
        journalDay.setStyle("-fx-font-size: 64px; -fx-text-fill: " + Theme.ref("tile_purple") + ";");
        journalWeekday.getStyleClass().add("bold");
        journalWeekday.setStyle("-fx-font-size: 20px;");
        journalMonth.getStyleClass().add("secondary");
        journalMonth.setStyle("-fx-font-size: 15px;");
        VBox words = new VBox(journalWeekday, journalMonth);
        HBox date = new HBox(14, journalDay, words);
        date.setAlignment(Pos.CENTER_LEFT);
        date.setPadding(new Insets(4, 8, 4, 0));
        date.setMaxWidth(Region.USE_PREF_SIZE);
        date.setStyle("-fx-cursor: hand; -fx-background-radius: 12;");
        Ui.tooltip(date, "Change the day");
        date.setOnMouseClicked(e -> {
            if (readOnly) return;
            Ui.pickDate(journal.day, day -> {
                journal.day = day;
                showDay();
            });
        });
        showDay();

        Label ask = Ui.label("How was it?", 13, "secondary");
        VBox.setMargin(ask, new Insets(14, 0, 0, 0));
        moodRow.getChildren().clear();
        for (String mood : MOODS) {
            StackPane chip = new StackPane(Emoji.view(mood, 28));
            chip.setMinSize(46, 46);
            chip.setMaxSize(46, 46);
            chip.setUserData(mood);
            chip.setStyle("-fx-cursor: hand;");
            chip.setOnMouseClicked(e -> {
                if (readOnly) return;
                journal.mood = mood.equals(journal.mood) ? "" : mood;
                showMoods();
                ScaleTransition pop = new ScaleTransition(Duration.millis(90), chip);
                pop.setToX(1.2);
                pop.setToY(1.2);
                pop.setAutoReverse(true);
                pop.setCycleCount(2);
                pop.play();
            });
            moodRow.getChildren().add(chip);
        }
        VBox.setMargin(moodRow, new Insets(6, 0, 0, 0));
        showMoods();

        mediaScroll.setFitToHeight(true);
        mediaScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        mediaScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        mediaScroll.setMinHeight(132 + 14);
        VBox.setMargin(mediaScroll, new Insets(16, 0, 0, 0));
        showMedia();

        Button add = new Button("Add photos or videos", Ui.icon(Icons.PHOTO, 18, "m3_on_secondary_container"));
        add.getStyleClass().setAll("tonal-button");
        add.setOnAction(e -> pickMedia());
        add.getStyleClass().add("add-media");
        VBox.setMargin(add, new Insets(8, 0, 0, 0));

        VBox header = new VBox(date, ask, moodRow, mediaScroll, add);
        header.setPadding(new Insets(0, 20, 0, 20));
        return header;
    }

    private void showMoods() {
        for (Node chip : moodRow.getChildren()) {
            boolean selected = chip.getUserData().equals(journal.mood);
            ((Region) chip).setStyle("-fx-cursor: hand; -fx-background-radius: 100; -fx-background-color: "
                    + (selected ? Theme.ref("note_purple") : "transparent") + ";");
            chip.setOpacity(journal.mood.isEmpty() || selected ? 1 : 0.45);
        }
    }

    private void showDay() {
        Date d = new Date(journal.day);
        journalDay.setText(new SimpleDateFormat("d", Locale.getDefault()).format(d));
        journalWeekday.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(d));
        journalMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d));
    }

    private void pickMedia() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add photos or videos");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Photos and videos",
                        "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.mp4", "*.m4v", "*.mov"),
                new FileChooser.ExtensionFilter("Photos", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"),
                new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.m4v", "*.mov"));
        List<File> files = chooser.showOpenMultipleDialog(NotesApp.stage());
        if (files == null || files.isEmpty()) return;
        withFolder(dir -> NoteStore.load(s -> {
            List<String> added = new ArrayList<>();
            for (File f : files) {
                boolean video = NoteData.isVideo(f.getName());
                String ext = video ? ".mp4" : extension(f.getName());
                String name = (video ? "VID_" : "IMG_") + System.currentTimeMillis() + "_" + added.size() + ext;
                try {
                    Files.copy(f.toPath(), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    added.add(name);
                } catch (Exception e) {
                    System.getLogger("NoteEditor").log(System.Logger.Level.WARNING, "Could not add " + f, e);
                }
            }
            return added;
        }, added -> {
            journal.media.addAll(added);
            showMedia();
            if (added.size() < files.size()) Ui.toast("Some items couldn't be added");
        }));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".jpg" : name.substring(dot).toLowerCase(Locale.US);
    }

    private void showMedia() {
        mediaStrip.getChildren().clear();
        mediaScroll.setVisible(!journal.media.isEmpty());
        mediaScroll.setManaged(!journal.media.isEmpty());
        if (note == null || journal.media.isEmpty()) return;
        Path dir = NoteData.dir(note.getId());
        for (String name : List.copyOf(journal.media)) {
            Path file = dir.resolve(name);
            boolean video = NoteData.isVideo(name);
            StackPane tile = new StackPane();
            tile.getStyleClass().add("media-tile");
            tile.setMinSize(132, 132);
            tile.setMaxSize(132, 132);
            Rectangle clip = new Rectangle(132, 132);
            clip.setArcWidth(24);
            clip.setArcHeight(24);
            tile.setClip(clip);
            if (video) {
                tile.getChildren().add(Ui.icon(Icons.PLAY_BIG, 36, "text_secondary"));
            } else {
                Images.load(file, 264, img -> {
                    if (img != null) tile.getChildren().add(0, NoteCard.picture(img, true));
                });
            }
            tile.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) MediaViewer.open(file, video);
            });
            tile.setOnContextMenuRequested(e -> {
                if (readOnly) return;
                Ui.confirm("Remove from this entry?", "Remove", () -> {
                    journal.media.remove(name);
                    showMedia();
                    NoteStore.write(s -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (Exception ex) {
                            System.getLogger("NoteEditor").log(System.Logger.Level.WARNING, "Could not delete " + file, ex);
                        }
                    });
                });
            });
            Ui.tooltip(tile, readOnly ? "Click to view" : "Click to view · right-click to remove");
            mediaStrip.getChildren().add(tile);
        }
    }

    /** A note sent to me: nothing to add, tick, reorder, re-date or paste in. */
    @Override
    protected void lockForReading() {
        super.lockForReading();
        if (body != null) body.locked = true;
        for (String cls : new String[]{".add-row", ".add-media", ".list-handle"}) {
            for (Node n : root.lookupAll(cls)) {
                n.setVisible(false);
                n.setManaged(!cls.equals(".add-media") && !cls.equals(".add-row"));
            }
        }
    }

    // ================================================================== helpers

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
