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
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
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
        body.setLinkNotes(() -> linkNotes);
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
        column.getChildren().add(padded(title, new Insets(12, 20, 4, 20)));
        if (!NoteData.LIST.equals(type)) column.getChildren().add(padded(createFormatBar(), new Insets(0, 20, 6, 20)));
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
        // Repeating items whose next day has come show unticked; saved with the next edit, if any
        // (every device works out the same, so nothing needs pushing just for this).
        Due.rollover(items, Due.today());
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
        int overdue = Due.overdue(items, Due.today());
        listProgress.setText((total == 0 ? "Nothing yet" : all ? "All done" : done + " of " + total + " done")
                + (overdue > 0 ? "  ·  " + overdue + " overdue" : ""));
        listProgress.setGraphic(all ? Emoji.view("🎉", 15) : null);
        listProgress.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        double from = progress, to = total == 0 ? 0 : (double) done / total;
        progress = to;
        Region track = (Region) progressFill.getParent();
        if (track == null) return;
        Ui.animate(300, Interpolator.EASE_OUT, f -> progressFill.setMaxWidth(track.getWidth() * (from + (to - from) * f)));
    }

    // ================================================================== due dates

    /** A list item's day, time and repeat, dropping from its clock or pill. Every click applies at once. */
    /**
     * The item's day, time and repeat. Changes are made to a draft and only land on Apply (one step
     * for Ctrl+Z); Cancel, Escape or a click outside leave the item as it was.
     */
    private void showDuePopover(ItemRow row, Node anchor) {
        NoteData.TodoItem item = row.item;
        NoteData.TodoItem draft = new NoteData.TodoItem(item.text, item.done);
        draft.due = item.due;
        draft.time = item.time;
        draft.repeat = item.repeat;
        long today = Due.today();
        int wd = Due.weekday(today);
        long[] quick = {today, today + 1, wd >= 6 ? today : today + (6 - wd), today + (8 - wd)};
        String[] quickNames = {"Today", "Tomorrow", "This weekend", "Next week"};
        String[] times = {null, "09:00", "12:00", "18:00", "21:00"};
        String[] kinds = {null, "daily", "weekdays", "weekly", "monthly"};
        String[] kindNames = {"Never", "Daily", "Weekdays", "Weekly", "Monthly"};

        Runnable[] refresh = new Runnable[1];
        java.util.function.Consumer<Runnable> edit = change -> {
            change.run();
            refresh[0].run();
        };

        String words = item.text == null ? "" : item.text.trim();
        Label title = Ui.label(words.isEmpty() ? "This item" : words, 16, "medium");
        title.setMaxWidth(290);
        HBox header = new HBox(10, Ui.icon(Icons.CLOCK, 20, "tile_orange"), title);
        header.setAlignment(Pos.CENTER_LEFT);
        Label summary = Ui.label("", 13, "secondary");
        summary.setWrapText(true);

        FlowPane days = new FlowPane(6, 6);
        List<ToggleButton> dayChips = new ArrayList<>();
        for (int i = 0; i < quick.length; i++) {
            long d = quick[i];
            dayChips.add(dueChip(days, quickNames[i], () -> edit.accept(() -> Due.setDay(draft, d))));
        }
        ToggleButton pick = dueChip(days, "Pick a date…", () -> {
            refresh[0].run(); // Lit only once a day is picked.
            int[] c = Due.civil(Due.dated(draft) ? Due.day(draft.due) : today);
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            long from = java.time.LocalDate.of(c[0], c[1], c[2]).atStartOfDay(zone).toInstant().toEpochMilli();
            Ui.pickDate(from, ms -> {
                java.time.LocalDate d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
                edit.accept(() -> Due.setDay(draft, Due.day(d.getYear(), d.getMonthValue(), d.getDayOfMonth())));
            });
        });

        FlowPane timeRow = new FlowPane(6, 6);
        List<ToggleButton> timeChips = new ArrayList<>();
        for (String t : times) {
            timeChips.add(dueChip(timeRow, t == null ? "No time" : Due.time(t), () -> edit.accept(() -> {
                if (t != null && !Due.dated(draft)) Due.setDay(draft, today);
                draft.time = t;
            })));
        }
        // Any other time: an hour and a minute wheel, iOS style.
        Ui.Wheel[] wheels = new Ui.Wheel[2];
        Runnable wheelTime = () -> edit.accept(() -> {
            if (!Due.dated(draft)) Due.setDay(draft, today);
            draft.time = String.format(Locale.ROOT, "%02d:%02d", wheels[0].value(), wheels[1].value());
        });
        wheels[0] = new Ui.Wheel(0, 23, 9, v -> wheelTime.run());
        wheels[1] = new Ui.Wheel(0, 59, 0, v -> wheelTime.run());
        Label colon = Ui.label(":", 22, "medium");
        HBox clockFace = new HBox(4, wheels[0], colon, wheels[1]);
        clockFace.setAlignment(Pos.CENTER);
        clockFace.getStyleClass().add("wheel-face");
        Ui.tooltip(clockFace, "Scroll, drag or use the arrow keys");

        FlowPane repeatRow = new FlowPane(6, 6);
        List<ToggleButton> repeatChips = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            repeatChips.add(dueChip(repeatRow, kindNames[i], () -> edit.accept(() -> Due.setRepeat(draft, kind, today))));
        }
        HBox weekdays = new HBox(6);
        ToggleButton[] dayToggles = new ToggleButton[8];
        String[] letters = {"M", "T", "W", "T", "F", "S", "S"};
        for (int d = 1; d <= 7; d++) {
            int weekday = d;
            ToggleButton t = new ToggleButton(letters[d - 1]);
            t.getStyleClass().setAll("due-day");
            Ui.tooltip(t, Due.WEEKDAYS[d - 1]);
            t.setOnAction(e -> edit.accept(() -> Due.toggleWeekday(draft, weekday, today)));
            weekdays.getChildren().add(t);
            dayToggles[d] = t;
        }

        // What the buttons do is said on them: Clear empties the draft, Apply saves it.
        Ui.Modal[] modal = new Ui.Modal[1];
        Button clear = Ui.textButton("Clear", () -> edit.accept(() -> Due.clear(draft)));
        clear.setStyle("-fx-text-fill: " + Theme.ref("destructive") + ";");
        Button cancel = Ui.textButton("Cancel", () -> modal[0].dismiss());
        Button apply = new Button("Apply");
        apply.getStyleClass().setAll("filled-button", "compact");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            boolean changed = !java.util.Objects.equals(item.due, draft.due) || !java.util.Objects.equals(item.time, draft.time)
                    || !java.util.Objects.equals(item.repeat, draft.repeat);
            if (changed) {
                pushListUndo();
                item.due = draft.due;
                item.time = draft.time;
                item.repeat = draft.repeat;
                row.showDue(true);
                updateProgress();
                markDirty();
                Ui.toast(Due.dated(item) ? Due.summary(item, today) : "Date removed");
            }
            modal[0].dismiss();
        });
        HBox buttons = new HBox(8, clear, Ui.spacer(), cancel, apply);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox.setMargin(buttons, new Insets(12, 0, 0, 0));

        VBox box = new VBox(header, summary, section("Day"), days, section("Time"), timeRow, clockFace,
                section("Repeat"), repeatRow, weekdays, buttons);
        box.setSpacing(6);
        box.setPadding(new Insets(16, 18, 16, 18));
        box.setPrefWidth(380);
        VBox.setMargin(weekdays, new Insets(4, 0, 0, 0));
        VBox.setMargin(clockFace, new Insets(4, 0, 0, 0));

        refresh[0] = () -> {
            boolean dated = Due.dated(draft);
            long d = Due.day(draft.due);
            summary.setText(dated ? Due.summary(draft, today) : "No date: pick a day, a time or a repeat");
            clear.setDisable(!dated && draft.time == null && !Due.repeats(draft));
            int lit = -1; // One chip for the item's day ("Today" before "This weekend" on a Saturday).
            for (int i = 0; i < quick.length && lit < 0; i++) if (dated && d == quick[i]) lit = i;
            for (int i = 0; i < quick.length; i++) dayChips.get(i).setSelected(i == lit);
            pick.setSelected(dated && lit < 0);
            pick.setText(dated && lit < 0 ? Due.when(d, today) : "Pick a date…");
            for (int i = 0; i < times.length; i++) timeChips.get(i).setSelected(java.util.Objects.equals(times[i], draft.time));
            if (draft.time != null && draft.time.matches("\\d{2}:\\d{2}")) {
                wheels[0].set(Integer.parseInt(draft.time.substring(0, 2)));
                wheels[1].set(Integer.parseInt(draft.time.substring(3)));
            }
            clockFace.setOpacity(draft.time == null ? 0.55 : 1);
            String kind = !Due.repeats(draft) ? null : draft.repeat.startsWith("weekly:") ? "weekly"
                    : draft.repeat.startsWith("monthly:") ? "monthly" : draft.repeat;
            for (int i = 0; i < kinds.length; i++) repeatChips.get(i).setSelected(java.util.Objects.equals(kinds[i], kind));
            weekdays.setVisible("weekly".equals(kind));
            weekdays.setManaged("weekly".equals(kind));
            boolean[] on = Due.weekDays(draft.repeat);
            for (int w = 1; w <= 7; w++) dayToggles[w].setSelected(on[w]);
        };
        refresh[0].run();
        modal[0] = Ui.popover(anchor, box);
    }

    private static Label section(String name) {
        Label l = Ui.label(name.toUpperCase(Locale.ROOT), 11, "secondary");
        VBox.setMargin(l, new Insets(8, 0, 0, 0));
        return l;
    }

    /** A chip in the due popover; the popover sets whether it's lit. */
    private static ToggleButton dueChip(FlowPane row, String label, Runnable onClick) {
        ToggleButton chip = new ToggleButton(label);
        chip.getStyleClass().setAll("chip");
        chip.setFocusTraversable(false);
        chip.setOnAction(e -> onClick.run());
        row.getChildren().add(chip);
        return chip;
    }


    /** One list item: drag handle, tick box, the text (and its day), the clock, and × on hover. */
    private final class ItemRow extends HBox implements Zoom.Scaled {
        final NoteData.TodoItem item;
        /** A growing text area when the list wraps, otherwise a one-line field. */
        final javafx.scene.control.TextInputControl text;
        private final StackPane box = new StackPane();
        private final Ui.IconView tick = Ui.icon(Icons.CHECK, 16, "white");
        private StackPane handle, target;
        private Button remove;
        private javafx.scene.image.ImageView view;
        private Button clock;
        private Label pill;
        private final Rectangle clip = new Rectangle();
        private double zoomed = 1, pictureWidth, pictureRatio;

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

            handle = new StackPane(Ui.icon(Icons.NOTE_LIST, 16, "divider"));
            handle.getStyleClass().add("list-handle");
            handle.setMinSize(32, 40);
            Ui.tooltip(handle, "Drag to reorder");
            dragToReorder(handle);

            box.getStyleClass().add("check");
            box.setMinSize(18, 18);
            box.setMaxSize(18, 18);
            box.getChildren().add(tick);
            target = new StackPane(box);
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
            if (!readOnly) LinkSuggest.attach(text, () -> linkNotes);
            // Ctrl+click on "[[Title]]" opens that note (the click has put the caret inside it).
            text.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
                if (!e.isShortcutDown() || text.getText() == null) return;
                java.util.regex.Matcher link = NoteLinks.LINK.matcher(text.getText());
                int at = text.getCaretPosition();
                while (link.find()) {
                    if (at > link.start() && at < link.end()) {
                        e.consume();
                        NoteEditor.openLink(link.group(1));
                        return;
                    }
                }
            });
            // Its day under the words, coloured by how near it is; click it to change it.
            pill = new Label();
            pill.getStyleClass().add("due-pill");
            pill.setOnMouseClicked(e -> {
                if (!readOnly) showDuePopover(this, pill);
            });
            VBox column = new VBox(text, pill);
            column.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(column, Priority.ALWAYS);
            // The clock that gives it one: shown on the row under the pointer or being typed in.
            clock = Ui.iconButton(Icons.CLOCK, 32, 18, "text_secondary", "Due date  (Ctrl+D)", () -> showDuePopover(this, clock));
            hoverProperty().addListener((o, a, b) -> showDue(false));
            text.focusedProperty().addListener((o, a, b) -> showDue(false));
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
                } else if (e.getCode() == KeyCode.D && e.isShortcutDown() && !readOnly) {
                    e.consume();
                    showDuePopover(this, Due.dated(item) ? pill : clock);
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

            remove = Ui.iconButton(Icons.CLOSE, 32, 18, "text_secondary", "Delete item", () -> {
                int index = itemRows.getChildren().indexOf(this);
                removeItemAt(index);
                if (items.isEmpty()) addItemAt(0);
            });
            remove.visibleProperty().bind(hoverProperty().or(text.focusedProperty()));

            getChildren().addAll(handle, target, column, clock, remove);
            styleDone();
            showDue(false);
        }

        /** The row of a picture item: its handle, the picture (click to view it big), and ×. */
        private void pictureRow(String name) {
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(4, 16, 4, 4));
            handle = new StackPane(Ui.icon(Icons.NOTE_LIST, 16, "divider"));
            handle.getStyleClass().add("list-handle");
            handle.setMinSize(32, 40);
            Ui.tooltip(handle, "Drag to reorder");
            dragToReorder(handle);
            view = new javafx.scene.image.ImageView();
            view.setPreserveRatio(true);
            view.setSmooth(true);
            clip.setArcWidth(16);
            clip.setArcHeight(16);
            view.setClip(clip);
            Path file = note != null && note.getId() > 0 ? NoteData.dir(note.getId()).resolve(name) : null;
            if (file != null) {
                Images.load(file, 2048, img -> {
                    if (img == null) return;
                    pictureWidth = Math.min(img.getWidth(), 520);
                    pictureRatio = img.getHeight() / Math.max(1, img.getWidth());
                    view.setImage(img);
                    sizePicture();
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
            remove = Ui.iconButton(Icons.CLOSE, 32, 18, "text_secondary", "Remove picture", () -> {
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

        /** The item's pill (overdue, today, later) and, on the row in use, the clock. */
        void showDue(boolean animate) {
            if (pill == null) return; // A picture has no day.
            long today = Due.today();
            int s = Due.status(item, today);
            boolean was = pill.isVisible();
            pill.getStyleClass().removeAll("overdue", "today", "done");
            if (s == Due.NONE) {
                pill.setVisible(false);
                pill.setManaged(false);
            } else {
                pill.setText(Due.pill(item, today));
                pill.setGraphic(Ui.icon(Icons.CLOCK, 12, s == Due.OVERDUE ? "destructive" : s == Due.TODAY ? "tile_orange" : "text_secondary"));
                scaleGraphic(pill.getGraphic(), zoomed);
                if (s == Due.OVERDUE) pill.getStyleClass().add("overdue");
                else if (s == Due.TODAY) pill.getStyleClass().add("today");
                else if (s == Due.DONE) pill.getStyleClass().add("done");
                pill.setVisible(true);
                pill.setManaged(true);
                pill.setCursor(readOnly ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.HAND);
                if (animate && !was) {
                    ScaleTransition pop = new ScaleTransition(Duration.millis(180), pill);
                    pop.setFromX(0.6);
                    pop.setFromY(0.6);
                    pop.setToX(1);
                    pop.setToY(1);
                    pop.play();
                }
            }
            clock.setVisible(!readOnly && s == Due.NONE && (isHover() || text.isFocused()));
        }

        private void sizePicture() {
            double w = pictureWidth * zoomed;
            view.setFitWidth(w);
            clip.setWidth(w);
            clip.setHeight(w * pictureRatio);
        }

        /** The row's own sizes follow the view's zoom, so a list zoomed out fits more items. */
        @Override
        public void zoom(double z) {
            if (Math.abs(z - zoomed) < 0.001) return;
            zoomed = z;
            handle.setMinSize(32 * z, 40 * z);
            scaleGraphic(handle.getChildren().get(0), z);
            scaleGraphic(remove.getGraphic(), z);
            remove.setMinSize(32 * z, 32 * z);
            remove.setPrefSize(32 * z, 32 * z);
            remove.setMaxSize(32 * z, 32 * z);
            if (view != null) {
                setPadding(new Insets(4 * z, 16 * z, 4 * z, 4 * z));
                if (pictureWidth > 0) sizePicture();
                return;
            }
            setMinHeight(48 * z);
            setPadding(new Insets(0, 16 * z, 0, 4 * z));
            box.setMinSize(18 * z, 18 * z);
            box.setMaxSize(18 * z, 18 * z);
            scaleGraphic(tick, z);
            target.setMinSize(44 * z, 44 * z);
            clock.setMinSize(32 * z, 32 * z);
            clock.setPrefSize(32 * z, 32 * z);
            clock.setMaxSize(32 * z, 32 * z);
            scaleGraphic(clock.getGraphic(), z);
            if (pill.getGraphic() != null) scaleGraphic(pill.getGraphic(), z);
        }

        private static void scaleGraphic(Node n, double z) {
            n.setScaleX(z);
            n.setScaleY(z);
        }

        private void setDone(boolean done) {
            Due.tick(item, done, Due.today());
            styleDone();
            showDue(true);
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
        if (body != null) {
            body.locked = true;
            body.setEditable(false);
        }
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

    
    private static Region hgap(double width) {
        Region r = new Region();
        r.setMinWidth(width);
        r.setMaxWidth(width);
        return r;
    }

    // =============================================================== formatting

    private Button fmtBold, fmtItalic, fmtUnderline, fmtStrike, fmtCode, fmtBullet, fmtNumber, fmtTask, fmtParagraph;

    /**
     * Word's formatting row: bold, italic, underline, strike, code, colour, the paragraph's kind,
     * lists, tasks, and line spacing. Every button acts on the text itself (the selection, or what
     * is typed next); the buttons light up for what the caret is on. Nothing shows a mark.
     */
    private HBox createFormatBar() {
        fmtBold = Ui.iconButton(Icons.BOLD, 34, 18, "text_secondary", "Bold (Ctrl+B)", body::toggleBold);
        fmtItalic = Ui.iconButton(Icons.ITALIC, 34, 18, "text_secondary", "Italic (Ctrl+I)", body::toggleItalic);
        fmtUnderline = Ui.iconButton(Icons.UNDERLINE, 34, 18, "text_secondary", "Underline (Ctrl+U)", body::toggleUnderline);
        fmtStrike = Ui.iconButton(Icons.STRIKETHROUGH, 34, 18, "text_secondary", "Strikethrough (Ctrl+Shift+X)", body::toggleStrike);
        fmtCode = formatText("{ }", "Code", body::toggleCode);

        Button colorBtn = Ui.iconButton(Icons.PALETTE, 34, 18, "text_secondary", "Text colour", () -> {});
        colorBtn.setOnAction(e -> {
            List<String> names = List.of("Automatic", "Yellow", "Orange", "Red", "Pink", "Purple", "Blue", "Teal", "Green", "Grey");
            List<String> hexes = java.util.Arrays.asList(null, "#E6A700", "#FF9500", "#FF3B30", "#FF2D55", "#AF52DE", "#007AFF", "#30B0C7", "#34C759", "#8E8E93");
            List<Runnable> acts = new ArrayList<>();
            for (String hx : hexes) acts.add(() -> body.color(hx));
            Ui.menu(colorBtn, names, acts);
        });

        fmtParagraph = formatText("Normal text", "Paragraph style (Ctrl+Alt+1, 2, 3; Ctrl+Alt+0 for normal)", () -> {});
        fmtParagraph.setMinWidth(112);
        fmtParagraph.setOnAction(e -> Ui.menu(fmtParagraph,
                List.of("Normal text", "Heading 1", "Heading 2", "Heading 3", "Quote"),
                List.of(() -> body.block(Markdown.Block.NORMAL), () -> body.block(Markdown.Block.H1),
                        () -> body.block(Markdown.Block.H2), () -> body.block(Markdown.Block.H3), () -> body.block(Markdown.Block.QUOTE))));
        fmtBullet = formatText("•  List", "Bulleted list (Ctrl+Shift+L, or type \"- \")", () -> body.block(Markdown.Block.BULLET));
        fmtNumber = formatText("1.  List", "Numbered list (type \"1. \")", () -> body.block(Markdown.Block.NUMBER));
        fmtTask = formatText("☐  Tasks", "Tick boxes (type \"[] \")", () -> body.block(Markdown.Block.TASK));

        Button spacing = Ui.iconButton(Icons.FORMAT_LINE_SPACING, 34, 18, "text_secondary", "Kerning & Leading Sliders", () -> {});
        spacing.setOnAction(e -> {
            VBox box = new VBox(10);
            box.getStyleClass().add("menu-popup");
            box.setStyle("-fx-background-color: -color-bg-overlay; -fx-padding: 14px 18px; -fx-background-radius: 12px; -fx-border-color: -color-border-subtle; -fx-border-radius: 12px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 16, 0, 0, 6);");
            box.setPrefWidth(240);

            Label titleLbl = new Label("Typography & Spacing");
            titleLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -color-fg-primary;");

            double curLeading = RichArea.getLineSpacing();
            Label leadingLabel = new Label(String.format(Locale.US, "Leading (Line Spacing): %.1f px", curLeading));
            leadingLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-secondary;");
            javafx.scene.control.Slider leadingSlider = new javafx.scene.control.Slider(1.0, 16.0, curLeading);
            leadingSlider.valueProperty().addListener((obs, oldV, newV) -> {
                body.lineSpacing(newV.doubleValue());
                leadingLabel.setText(String.format(Locale.US, "Leading (Line Spacing): %.1f px", newV.doubleValue()));
            });

            double curKerning = RichArea.getKerning();
            Label kerningLabel = new Label(String.format(Locale.US, "Kerning (Letter Spacing): %.1f px", curKerning));
            kerningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-secondary;");
            javafx.scene.control.Slider kerningSlider = new javafx.scene.control.Slider(0.0, 6.0, curKerning);
            kerningSlider.valueProperty().addListener((obs, oldV, newV) -> {
                body.kerning(newV.doubleValue());
                kerningLabel.setText(String.format(Locale.US, "Kerning (Letter Spacing): %.1f px", newV.doubleValue()));
            });

            box.getChildren().addAll(titleLbl, leadingLabel, leadingSlider, kerningLabel, kerningSlider);

            javafx.stage.Popup popup = new javafx.stage.Popup();
            popup.setAutoHide(true);
            popup.getContent().add(box);
            javafx.geometry.Bounds bounds = spacing.localToScreen(spacing.getBoundsInLocal());
            if (bounds != null) {
                popup.show(spacing.getScene().getWindow(), bounds.getMinX(), bounds.getMaxY() + 4);
            }
        });

        HBox bar = new HBox(2, fmtParagraph, hgap(8), fmtBold, fmtItalic, fmtUnderline, fmtStrike, fmtCode, colorBtn,
                hgap(8), fmtBullet, fmtNumber, fmtTask, hgap(8), spacing);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 0, 6, 0));
        bar.getStyleClass().add("format-bar");
        body.setOnCaret(this::showFormat);
        return bar;
    }

    private static Button formatText(String text, String tip, Runnable action) {
        Button b = Ui.textButton(text, action);
        b.getStyleClass().add("format-text");
        b.setFocusTraversable(false);
        Ui.tooltip(b, tip);
        return b;
    }

    /** The buttons light up for what the caret is on. */
    private void showFormat() {
        RichArea a = body.active();
        if (a == null || fmtBold == null) return;
        Markdown.Style s = a.currentStyle();
        Markdown.Block b = a.currentBlock();
        on(fmtBold, s != null && s.bold());
        on(fmtItalic, s != null && s.italic());
        on(fmtUnderline, s != null && s.underline());
        on(fmtStrike, s != null && s.strike());
        on(fmtCode, s != null && s.code());
        on(fmtBullet, b == Markdown.Block.BULLET);
        on(fmtNumber, b == Markdown.Block.NUMBER);
        on(fmtTask, b == Markdown.Block.TASK || b == Markdown.Block.TASK_DONE);
        fmtParagraph.setText(switch (b) {
            case H1 -> "Heading 1";
            case H2 -> "Heading 2";
            case H3 -> "Heading 3";
            case QUOTE -> "Quote";
            default -> "Normal text";
        });
    }

    private static void on(Button b, boolean on) {
        if (on && !b.getStyleClass().contains("format-on")) b.getStyleClass().add("format-on");
        else if (!on) b.getStyleClass().remove("format-on");
    }
}
