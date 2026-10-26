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
        syncStatus.addListener((o, a, b) -> updateFooter());
        setUpNote(content);
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        column.getChildren().clear();
        if (NoteData.JOURNAL.equals(type)) column.getChildren().add(journalHeader(note));
        title.setText(note.getTitle());
        title.textProperty().addListener((o, a, b) -> { markDirty(); updateFooter(); });
        body.setOnChange(() -> { markDirty(); updateFooter(); });
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

    /** Ctrl+F: the title, the words (or the items), in reading order. */
    @Override
    protected FindBar.Searcher searcher() {
        return FindBar.inFields(() -> FindBar.fieldsUnder(column, f -> true), scroll);
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
        String status = syncStatus.get();
        String statusText = status != null && !status.isEmpty() ? "  ·  " + status : "";
        if (NoteData.LIST.equals(type)) {
            footer.setText(edited + statusText);
            return;
        }
        String text = body.words();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        footer.setText(edited + "  ·  " + words + (words == 1 ? " word" : " words") + statusText);
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
        itemRows.setOnDragOver(e -> {
            if (!readOnly && e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream().anyMatch(RichBody::isPicture)) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            e.consume();
        });
        itemRows.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) {
                for (File f : e.getDragboard().getFiles()) if (RichBody.isPicture(f)) addPicture(items.size(), f);
            }
            e.setDropCompleted(true);
            e.consume();
        });

        HBox add = new HBox(Ui.icon(Icons.ADD, 24, "tile_green"), Ui.label("Add item", 17, "secondary"));
        add.getStyleClass().add("add-row");
        add.setOnMouseClicked(e -> {
            if (!readOnly) addItemAt(items.size());
        });

        updateProgress();
        return new VBox(track, listProgress, itemRows, add);
    }

    private final java.util.ArrayDeque<String> listUndo = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> listRedo = new java.util.ArrayDeque<>();

    private void pushListUndo() {
        if (!NoteData.LIST.equals(type) || readOnly) return;
        String json = NoteData.todosJson(items);
        if (!listUndo.isEmpty() && listUndo.peek().equals(json)) return;
        listUndo.push(json);
        if (listUndo.size() > 50) listUndo.removeLast();
        listRedo.clear();
        markDirty();
        updateFooter();
    }

    @Override
    protected void undo() {
        if (NoteData.LIST.equals(type) && !listUndo.isEmpty()) {
            String cur = NoteData.todosJson(items);
            listRedo.push(cur);
            String prev = listUndo.pop();
            items.clear();
            items.addAll(NoteData.todos(prev));
            itemRows.getChildren().clear();
            for (NoteData.TodoItem item : items) itemRows.getChildren().add(new ItemRow(item));
            updateProgress();
            Ui.toast("Undone");
        }
    }

    @Override
    protected void redo() {
        if (NoteData.LIST.equals(type) && !listRedo.isEmpty()) {
            String cur = NoteData.todosJson(items);
            listUndo.push(cur);
            String next = listRedo.pop();
            items.clear();
            items.addAll(NoteData.todos(next));
            itemRows.getChildren().clear();
            for (NoteData.TodoItem item : items) itemRows.getChildren().add(new ItemRow(item));
            updateProgress();
            Ui.toast("Redone");
        }
    }

    private void addItemAt(int position) {
        pushListUndo();
        NoteData.TodoItem item = new NoteData.TodoItem("", false);
        items.add(position, item);
        itemRows.getChildren().add(position, new ItemRow(item));
        updateProgress();
        focusItem(position, false);
    }

    /**
     * A picture between the items, at {@code position}: an image file or an image (pasted). It is
     * kept in the note's folder and stored as an item of its own, {@code ![[name]]}.
     */
    private void addPicture(int position, Object picture) {
        pushListUndo();
        withFolder(dir -> RichBody.savePicture(dir, picture instanceof File f ? f : null,
                picture instanceof javafx.scene.image.Image i ? i : null, name -> {
                    if (name == null) {
                        Ui.toast("Couldn't add that picture");
                        return;
                    }
                    int at = Math.max(0, Math.min(position, items.size()));
                    NoteData.TodoItem item = new NoteData.TodoItem("![[" + name + "]]", false);
                    items.add(at, item);
                    itemRows.getChildren().add(at, new ItemRow(item));
                    updateProgress();
                }));
    }

    private void removeItemAt(int position) {
        if (position < 0 || position >= items.size()) return;
        pushListUndo();
        items.remove(position);
        itemRows.getChildren().remove(position);
        updateProgress();
    }

    private void focusItem(int position, boolean atEnd) {
        if (position < 0 || position >= itemRows.getChildren().size()) return;
        if (items.get(position).picture() != null) {
            // A picture has nothing to type in: the item past it.
            int next = atEnd ? position - 1 : position + 1;
            if (next != position && next >= 0 && next < items.size()) focusItem(next, atEnd);
            return;
        }
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
            if (item.text.trim().isEmpty() || item.picture() != null) continue;
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
            String picture = item.picture();
            if (picture != null) {
                text = new TextField(); // Never shown: a picture has no words.
                pictureRow(picture);
                return;
            }
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
                if (!readOnly) {
                    pushListUndo();
                    setDone(!item.done);
                }
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
                markDirty();
            });
            text.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                int index = itemRows.getChildren().indexOf(this);
                boolean paste = (e.isShortcutDown() && e.getCode() == KeyCode.V) || (e.isShiftDown() && e.getCode() == KeyCode.INSERT);
                Object pasted = paste && !readOnly ? RichBody.clipboardPicture() : null;
                if (pasted != null) {
                    // A picture pasted into an item lands under it (or in its place, if it's empty).
                    e.consume();
                    boolean empty = text.getText().trim().isEmpty();
                    if (empty && items.size() > 1) removeItemAt(index);
                    addPicture(empty && items.size() >= 1 ? index : index + 1, pasted);
                } else if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                    e.consume();
                    pushListUndo();
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

        /** The row of a picture item: its handle, the picture (click to view it big), and ×. */
        private void pictureRow(String name) {
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(4, 16, 4, 4));
            StackPane handle = new StackPane(Ui.icon(Icons.NOTE_LIST, 16, "divider"));
            handle.getStyleClass().add("list-handle");
            handle.setMinSize(32, 40);
            Ui.tooltip(handle, "Drag to reorder");
            dragToReorder(handle);
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView();
            view.setPreserveRatio(true);
            view.setSmooth(true);
            Rectangle clip = new Rectangle();
            clip.setArcWidth(16);
            clip.setArcHeight(16);
            view.setClip(clip);
            Path file = note != null && note.getId() > 0 ? NoteData.dir(note.getId()).resolve(name) : null;
            if (file != null) {
                Images.load(file, 2048, img -> {
                    if (img == null) return;
                    double w = Math.min(img.getWidth(), 520);
                    view.setImage(img);
                    view.setFitWidth(w);
                    clip.setWidth(w);
                    clip.setHeight(w * img.getHeight() / Math.max(1, img.getWidth()));
                });
                view.setStyle("-fx-cursor: hand;");
                view.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) MediaViewer.open(file, false);
                });
            }
            StackPane picture = new StackPane(view);
            picture.setAlignment(Pos.CENTER_LEFT);
            picture.setPadding(new Insets(4, 0, 4, 12));
            HBox.setHgrow(picture, Priority.ALWAYS);
            Button remove = Ui.iconButton(Icons.CLOSE, 32, 18, "text_secondary", "Remove picture", () -> {
                int index = itemRows.getChildren().indexOf(this);
                removeItemAt(index);
                if (items.isEmpty()) addItemAt(0);
            });
            remove.visibleProperty().bind(hoverProperty());
            if (readOnly) {
                handle.setVisible(false);
                remove.setVisible(false);
            }
            getChildren().addAll(handle, picture, remove);
        }

        private void setDone(boolean done) {
            item.done = done;
            styleDone();
            updateProgress();
            markDirty();
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
        /**
         * Picks the row up by its handle: it follows the pointer freely, the others glide out of
         * its way, and it settles where it's let go. The page scrolls when it's held near an edge.
         */
        private void dragToReorder(Node handle) {
            double[] grab = new double[2];
            int[] slots = {-1, -1}; // from, to
            java.util.Map<Node, javafx.animation.TranslateTransition> glides = new java.util.HashMap<>();
            handle.setOnMousePressed(e -> {
                if (readOnly) return;
                grab[0] = e.getSceneY();
                grab[1] = 0;
                slots[0] = slots[1] = itemRows.getChildren().indexOf(this);
                setViewOrder(-1);
                setStyle("-fx-background-color: " + Theme.ref(background()) + "; -fx-background-radius: 12;"
                        + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 18, 0.2, 0, 4);");
                setScaleX(1.015);
                setScaleY(1.015);
                e.consume();
            });
            handle.setOnMouseDragged(e -> {
                if (slots[0] < 0) return;
                // Near the top or bottom of the page: scroll, and the row stays under the pointer.
                javafx.geometry.Bounds view = scroll.localToScene(scroll.getLayoutBounds());
                double edge = 48, step = 0;
                if (e.getSceneY() < view.getMinY() + edge) step = -1;
                else if (e.getSceneY() > view.getMaxY() - edge) step = 1;
                if (step != 0) {
                    double content = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
                    if (content > 0) {
                        double before = scroll.getVvalue();
                        scroll.setVvalue(Math.max(0, Math.min(1, before + step * 14 / content)));
                        grab[1] += (scroll.getVvalue() - before) * content;
                    }
                }
                double dy = e.getSceneY() - grab[0] + grab[1];
                setTranslateY(dy);
                List<Node> rows = itemRows.getChildren();
                double h = getHeight(), mid = getLayoutY() + h / 2 + dy;
                int to = 0;
                for (int i = 0; i < rows.size(); i++) {
                    Node r = rows.get(i);
                    if (r != this && r.getLayoutY() + r.getLayoutBounds().getHeight() / 2 < mid) to++;
                }
                slots[1] = to;
                int from = slots[0];
                for (int i = 0; i < rows.size(); i++) {
                    Node r = rows.get(i);
                    if (r == this) continue;
                    double target = i > from && i <= to ? -h : i < from && i >= to ? h : 0;
                    javafx.animation.TranslateTransition t = glides.get(r);
                    if (t != null && t.getToY() == target) continue;
                    if (t != null) t.stop();
                    t = new javafx.animation.TranslateTransition(Duration.millis(140), r);
                    t.setToY(target);
                    t.setInterpolator(Interpolator.EASE_OUT);
                    t.play();
                    glides.put(r, t);
                }
            });
            handle.setOnMouseReleased(e -> {
                if (slots[0] < 0) return;
                int from = slots[0], to = slots[1];
                slots[0] = -1;
                for (javafx.animation.TranslateTransition t : glides.values()) t.stop();
                glides.clear();
                for (Node r : itemRows.getChildren()) r.setTranslateY(0);
                setViewOrder(0);
                setStyle(null);
                setScaleX(1);
                setScaleY(1);
                if (to != from && to >= 0) {
                    itemRows.getChildren().remove(this);
                    itemRows.getChildren().add(to, this);
                    items.remove(item);
                    items.add(to, item);
                    markDirty();
                }
            });
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
