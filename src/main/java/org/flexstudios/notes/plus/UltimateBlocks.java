package org.flexstudios.notes.plus;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The elements of an Ultimate note (see {@link Ultimate}), shown in {@link NoteEditorView} under its title
 * and formatting row as one page, without frames: words ({@link RichBody}), checklists, mind maps
 * ({@link MindMapCanvas}, taller or shorter by dragging under them), voice recordings and days. Any element
 * can sit inside another: nested under a list item or words ({@code indent}), or inside a mind map's idea
 * (its {@code blocks}, opened in a panel that is itself such a page). "/" alone on a line, the bar along
 * the bottom ({@link #addBar}), a list item's + and an idea's menu put things in; the handle beside an
 * element (on hover) nests, moves or removes it. The web's ed-ultimate.js, the phone's UltimateBlocks.
 */
final class UltimateBlocks extends VBox {

    interface Host {
        void withFolder(Consumer<Path> then);

        /** The note's folder, or null while it has none (a new note). */
        Path folder();

        int noteId();

        boolean readOnly();

        Supplier<List<NoteEntity>> linkNotes();

        ScrollPane scroll();

        /** Something changed: saved a moment later. */
        void changed();

        /** The caret went into these words: the formatting row works on them now. */
        void textFocused(RichBody body);
    }

    private static final String[] MOODS = {"😄", "🙂", "😐", "😔", "😢", "😡", "😴", "🤩", "❤️"};

    private record Kind(String kind, String label, Icons.Icon icon, String accent) {}

    private static final Kind[] KINDS = {
            new Kind(Ultimate.TEXT, "Text", Icons.NOTE_TEXT, "tile_orange"),
            new Kind(Ultimate.LIST, "Checklist", Icons.NOTE_LIST, "tile_green"),
            new Kind(Ultimate.MINDMAP, "Mind map", Icons.NOTE_MINDMAP, "tile_blue"),
            new Kind(Ultimate.AUDIO, "Audio", Icons.NOTE_AUDIO, "tile_teal"),
            new Kind(Ultimate.DAY, "Day", Icons.NOTE_JOURNAL, "tile_purple"),
    };

    private static Kind kindOf(String kind) {
        for (Kind k : KINDS) if (k.kind.equals(kind)) return k;
        return null;
    }

    private static final double STEP = 28; // px per level of nesting

    private final Host host;
    /** The note's own page (its first element is always words); otherwise what's inside an idea. */
    private final boolean root;
    private final List<Block> blocks = new ArrayList<>();
    private Block focused;

    UltimateBlocks(Host host) {
        this(host, true);
    }

    UltimateBlocks(Host host, boolean root) {
        this.host = host;
        this.root = root;
    }

    // ================================================================ the note

    void load(String data, String content) {
        for (Block b : blocks) b.destroy();
        blocks.clear();
        getChildren().clear();
        focused = null;
        List<JSONObject> stored = Ultimate.blocks(data);
        if (stored.isEmpty() || (root && !Ultimate.TEXT.equals(Ultimate.kind(stored.get(0))))) {
            stored.add(0, new JSONObject().put("id", Ultimate.newId()).put("kind", Ultimate.TEXT)
                    .put("md", stored.isEmpty() && content != null ? content : ""));
        }
        for (JSONObject b : stored) {
            Block block = make(b);
            blocks.add(block);
            getChildren().add(block);
        }
        refreshChrome();
    }

    /** What is kept: every element, but words left empty (except the page's first). */
    List<JSONObject> kept() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject b = valueOf(blocks.get(i));
            if ((i > 0 || !root) && Ultimate.TEXT.equals(Ultimate.kind(b)) && b.optString("md").isEmpty()) continue;
            out.add(b);
        }
        return out;
    }

    /** Fills the note: its blocks and their words. Whether there's anything to keep. */
    boolean collect(NoteEntity note) {
        List<JSONObject> out = kept();
        note.setData(Ultimate.blocksJson(out));
        note.setContent(Ultimate.content(out));
        boolean canvases = out.stream().anyMatch(b -> !Ultimate.TEXT.equals(Ultimate.kind(b)));
        return canvases || !note.getContent().trim().isEmpty() || !note.getTitle().isEmpty();
    }

    void focusStart() {
        if (!blocks.isEmpty()) blocks.get(0).focusIn(false);
    }

    void destroy() {
        for (Block b : blocks) b.destroy();
    }

    /** The bar that adds canvases, for the bottom of the editor. */
    Node addBar() {
        HBox bar = new HBox(2);
        for (Kind k : KINDS) {
            Button b = new Button(k.label, Ui.icon(k.icon, 18, k.accent));
            b.getStyleClass().add("ult-add");
            b.setFocusTraversable(false);
            Ui.tooltip(b, "Add " + k.label.toLowerCase(Locale.ROOT));
            b.setOnAction(e -> insertKind(k.kind));
            bar.getChildren().add(b);
        }
        Button pic = new Button("Picture", Ui.icon(Icons.PHOTO, 18, "tile_red"));
        pic.getStyleClass().add("ult-add");
        pic.setFocusTraversable(false);
        Ui.tooltip(pic, "Add pictures");
        pic.setOnAction(e -> addPictures());
        bar.getChildren().add(pic);
        bar.getStyleClass().add("ult-bar");
        bar.setAlignment(Pos.CENTER);
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bar.setPickOnBounds(false);
        return bar;
    }

    // ================================================================ arrange

    private Block make(JSONObject b) {
        return switch (Ultimate.kind(b)) {
            case Ultimate.TEXT -> new TextBlock(b);
            case Ultimate.LIST -> new ListBlock(b);
            case Ultimate.MINDMAP -> new MapBlock(b);
            case Ultimate.AUDIO -> new AudioBlock(b);
            case Ultimate.DAY -> new DayBlock(b);
            default -> new UnknownBlock(b);
        };
    }

    private static JSONObject blank(String kind, int indent) {
        JSONObject b = new JSONObject().put("id", Ultimate.newId()).put("kind", kind);
        switch (kind) {
            case Ultimate.TEXT -> b.put("md", "");
            case Ultimate.LIST -> b.put("items", new org.json.JSONArray().put(new JSONObject().put("text", "").put("done", false)));
            case Ultimate.MINDMAP -> b.put("h", Ultimate.MAP_H);
            case Ultimate.DAY -> b.put("day", Ultimate.dayStart(System.currentTimeMillis())).put("mood", "");
            default -> {}
        }
        if (indent > 0) b.put("indent", Math.min(4, indent));
        return b;
    }

    /** As stored: its data, and how far in it sits. */
    private static JSONObject valueOf(Block b) {
        JSONObject v = b.value();
        if (b.indent > 0) v.put("indent", b.indent);
        else v.remove("indent");
        return v;
    }

    private Block put(int at, JSONObject b) {
        Block block = make(b);
        blocks.add(at, block);
        getChildren().add(at, block);
        return block;
    }

    private void drop(Block b) {
        b.destroy();
        blocks.remove(b);
        getChildren().remove(b);
        if (focused == b) focused = null;
    }

    private void arrived(Block made) {
        focused = made;
        refreshChrome();
        host.changed();
        javafx.application.Platform.runLater(() -> {
            reveal(made);
            made.focusIn(true);
        });
    }

    /** A new element after the one in use (or at the end), as far in; it takes the caret. */
    private Block add(String kind) {
        int at = focused != null && blocks.contains(focused) ? blocks.indexOf(focused) + 1 : blocks.size();
        Block made = put(at, blank(kind, focused != null ? focused.indent : 0));
        arrived(made);
        return made;
    }

    /** The bar's choice: at the caret in the words in use (splitting them), or after the element in use. */
    private void insertKind(String kind) {
        if (focused instanceof TextBlock t && blocks.contains(t) && t.body.active() != null) splitInsert(t, kind, t.body.split());
        else add(kind);
    }

    /** Words split at the caret around a new element: before, the element, then the rest. */
    private void splitInsert(TextBlock t, String kind, String[] parts) {
        int i = blocks.indexOf(t);
        JSONObject b = valueOf(t);
        drop(t);
        int at = i;
        if (!parts[0].isEmpty() || (root && i == 0)) put(at++, b.put("md", parts[0]));
        Block made = put(at++, blank(kind, t.indent));
        if (!parts[1].isEmpty() || at >= blocks.size()) put(at, blank(Ultimate.TEXT, t.indent).put("md", parts[1]));
        arrived(made);
    }

    /** Something new inside a list item: the list splits after it, and the new element sits under it. */
    private void nestUnderItem(ListBlock l, int i, String kind) {
        List<NoteData.TodoItem> items = new ArrayList<>(l.items);
        int at = blocks.indexOf(l), indent = l.indent;
        JSONObject b = valueOf(l);
        drop(l);
        JSONObject head = Ultimate.block(b.optString("id"), Ultimate.LIST, NoteData.todosJson(new ArrayList<>(items.subList(0, i + 1))));
        if (indent > 0) head.put("indent", indent);
        put(at, head);
        boolean picture = "picture".equals(kind);
        Block made = put(at + 1, blank(picture ? Ultimate.TEXT : kind, indent + 1));
        if (items.size() > i + 1) {
            JSONObject tail = Ultimate.block(Ultimate.newId(), Ultimate.LIST, NoteData.todosJson(new ArrayList<>(items.subList(i + 1, items.size()))));
            if (indent > 0) tail.put("indent", indent);
            put(at + 2, tail);
        }
        arrived(made);
        if (picture) javafx.application.Platform.runLater(this::addPictures);
    }

    /** Two neighbours of the same kind at the same depth read as one: they become one. */
    private void mergeAt(int i) {
        if (i <= 0 || i >= blocks.size()) return;
        Block a = blocks.get(i - 1), c = blocks.get(i);
        if (a.indent != c.indent || !a.kind.equals(c.kind)) return;
        JSONObject b;
        if (a instanceof ListBlock la && c instanceof ListBlock lc) {
            List<NoteData.TodoItem> all = new ArrayList<>(la.items);
            all.addAll(lc.items);
            b = Ultimate.block(a.id, Ultimate.LIST, NoteData.todosJson(all));
        } else if (a instanceof TextBlock ta && c instanceof TextBlock tc) {
            String x = ta.body.getContent().trim(), y = tc.body.getContent().trim();
            b = new JSONObject().put("id", a.id).put("kind", Ultimate.TEXT).put("md", !x.isEmpty() && !y.isEmpty() ? x + "\n" + y : x + y);
        } else {
            return;
        }
        if (a.indent > 0) b.put("indent", a.indent);
        drop(a);
        drop(c);
        put(i - 1, b);
    }

    private void setIndent(Block b, int indent) {
        b.indent = Math.max(0, Math.min(4, indent));
        b.paintIndent();
        host.changed();
    }

    /** "What goes here?": the kinds (and pictures, when offered), at a point of the window. */
    private void kindMenu(double sceneX, double sceneY, boolean picture, Consumer<String> pick) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String[] hints = {"Just words", "Things to tick off", "Ideas and how they connect", "Record and play back", "A date and a mood"};
        for (int i = 0; i < KINDS.length; i++) {
            Kind k = KINDS[i];
            labels.add(k.label + "  ·  " + hints[i]);
            actions.add(() -> pick.accept(k.kind));
        }
        if (picture) {
            labels.add("Picture  ·  From your files");
            actions.add(() -> pick.accept("picture"));
        }
        Ui.menuAt(sceneX, sceneY, "Put here", labels, actions);
    }

    /** The handle's menu: add below, nest, bring out, move, remove. */
    private void options(Block b, double sceneX, double sceneY) {
        int i = blocks.indexOf(b);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Add below…");
        actions.add(() -> kindMenu(sceneX, sceneY, true, kind -> {
            focused = b;
            if ("picture".equals(kind)) addPictures();
            else add(kind);
        }));
        if (b.indent < 4 && i > 0) {
            labels.add("Nest further in  →");
            actions.add(() -> setIndent(b, b.indent + 1));
        }
        if (b.indent > 0) {
            labels.add("←  Bring out");
            actions.add(() -> setIndent(b, b.indent - 1));
        }
        if (i > 0) {
            labels.add("Move up");
            actions.add(() -> move(b, -1));
        }
        if (i < blocks.size() - 1) {
            labels.add("Move down");
            actions.add(() -> move(b, 1));
        }
        labels.add("Remove");
        actions.add(() -> remove(b));
        Ui.menuAt(sceneX, sceneY, null, labels, actions);
    }

    private void addPictures() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add pictures");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures",
                "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp"));
        List<File> files = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        // Into the words in use, or words of their own right after the element in use.
        TextBlock target = focused instanceof TextBlock t && blocks.contains(t) ? t : (TextBlock) add(Ultimate.TEXT);
        target.body.addPictures(files);
    }

    private void move(Block b, int dir) {
        int i = blocks.indexOf(b), j = i + dir;
        if (j < 0 || j >= blocks.size()) return;
        blocks.remove(i);
        blocks.add(j, b);
        getChildren().remove(b);
        getChildren().add(j, b);
        // Where it left and where it landed, like neighbours merge (two lists meeting become one).
        mergeAt(Math.min(i, j) + (dir > 0 ? 0 : 1));
        if (blocks.contains(b)) {
            mergeAt(blocks.indexOf(b));
            if (blocks.contains(b)) mergeAt(blocks.indexOf(b) + 1);
        }
        refreshChrome();
        host.changed();
        javafx.application.Platform.runLater(() -> reveal(b));
    }

    private void remove(Block b) {
        Runnable go = () -> {
            int i = blocks.indexOf(b);
            drop(b);
            if (blocks.stream().noneMatch(x -> x instanceof TextBlock) || (root && !(blocks.get(0) instanceof TextBlock))) put(0, blank(Ultimate.TEXT, 0));
            mergeAt(i);
            refreshChrome();
            host.changed();
        };
        Kind k = kindOf(b.kind);
        boolean empty = !(b instanceof DayBlock) && Ultimate.content(List.of(b.value())).trim().isEmpty();
        if (empty) go.run();
        else Ui.confirm("Remove this " + (k == null ? "part" : k.label.toLowerCase(Locale.ROOT)) + " and what's in it?", "Remove", go);
    }

    /** The first canvas never shows "move up"; the first words never show their buttons at all. */
    private void refreshChrome() {
        for (int i = 0; i < blocks.size(); i++) blocks.get(i).chrome(i == 0, i == blocks.size() - 1);
    }

    private void reveal(Node n) {
        ScrollPane scroll = host.scroll();
        Node content = scroll.getContent();
        if (content == null) return;
        double y = n.localToScene(0, 0).getY() - content.localToScene(0, 0).getY();
        double h = content.getBoundsInLocal().getHeight(), view = scroll.getViewportBounds().getHeight();
        if (h <= view) return;
        double top = (y - 40) / (h - view);
        double bottom = (y + n.getBoundsInLocal().getHeight() - view + 120) / (h - view);
        double now = scroll.getVvalue();
        if (now > top) scroll.setVvalue(Math.max(0, top));
        else if (now < bottom) scroll.setVvalue(Math.min(1, bottom));
    }

    // ================================================================= blocks

    /**
     * One element of the page: [how far in][its thread, when nested][its handle, on hover][itself]. No frame:
     * it reads as part of the page.
     */
    private abstract class Block extends HBox {
        final String id, kind;
        int indent;
        private final Region spacer = new Region();
        private final Region thread = new Region();
        private final Label handle = new Label("⠿");
        final VBox content = new VBox();

        Block(JSONObject b) {
            id = b.optString("id");
            kind = Ultimate.kind(b);
            indent = Ultimate.indent(b);
            thread.getStyleClass().add("ult-thread");
            thread.setMinWidth(2);
            thread.setMaxWidth(2);
            HBox.setMargin(thread, new Insets(6, 12, 6, 0));
            handle.getStyleClass().add("ult-handle");
            handle.setMinWidth(20);
            handle.setPrefWidth(20);
            handle.setPadding(new Insets(5, 0, 0, 0));
            handle.setAlignment(Pos.TOP_CENTER);
            handle.opacityProperty().bind(javafx.beans.binding.Bindings.when(hoverProperty()).then(0.75).otherwise(0.0));
            Ui.tooltip(handle, "Nest, move or remove");
            handle.setOnMouseClicked(e -> {
                if (!host.readOnly()) options(this, e.getSceneX(), e.getSceneY());
            });
            if (host.readOnly()) handle.setVisible(false);
            HBox.setHgrow(content, Priority.ALWAYS);
            getChildren().addAll(spacer, thread, handle, content);
            setPadding(new Insets(2, 20, 2, 0));
            paintIndent();
            addEventFilter(MouseEvent.MOUSE_PRESSED, e -> focused = this);
            addEventFilter(KeyEvent.KEY_PRESSED, e -> focused = this);
        }

        void paintIndent() {
            spacer.setMinWidth(indent * STEP);
            spacer.setPrefWidth(indent * STEP);
            thread.setVisible(indent > 0);
            thread.setManaged(indent > 0);
        }

        void chrome(boolean first, boolean last) {}

        /** Its own content, in the flow. */
        void canvas(Node body) {
            content.getChildren().setAll(body);
        }

        abstract JSONObject value();

        void focusIn(boolean isNew) {
            requestFocus();
        }

        void destroy() {}
    }

    // ------------------------------------------------------------------ words

    private final class TextBlock extends Block {
        final RichBody body = new RichBody(host::withFolder, host.scroll());

        TextBlock(JSONObject b) {
            super(b);
            body.setLinkNotes(host.linkNotes());
            body.setContent(b.optString("md"), host.folder());
            body.setOnChange(host::changed);
            body.setOnCaret(() -> host.textFocused(body));
            if (host.readOnly()) {
                body.locked = true;
                body.setEditable(false);
            }
            body.setPadding(Insets.EMPTY);
            // "/" alone on a line: what goes there, splitting the words around it.
            body.setOnSlash(at -> {
                String[] parts = body.split();
                kindMenu(at.getX(), at.getY(), true, kind -> {
                    if ("picture".equals(kind)) {
                        focused = this;
                        addPictures();
                    } else {
                        splitInsert(this, kind, parts);
                    }
                });
            });
            canvas(body);
        }

        @Override
        void chrome(boolean first, boolean last) {
            body.setPrompt(first && root ? "Start writing. Type / for a checklist, a mind map, voice…" : "");
            body.setRoom(first && last ? 160 : 30); // At least a line: an area never measured stays 0 tall.
        }

        @Override
        JSONObject value() {
            return new JSONObject().put("id", id).put("kind", Ultimate.TEXT).put("md", body.getContent().trim());
        }

        @Override
        void focusIn(boolean isNew) {
            body.focusStart();
        }
    }

    // -------------------------------------------------------------- checklist

    private final class ListBlock extends Block {
        final List<NoteData.TodoItem> items = new ArrayList<>();
        private final VBox rows = new VBox();
        private final Label progress = Ui.label("", 12, "secondary");
        /** An item is being typed in: "Add item" shows (it takes no room otherwise). */
        private final javafx.beans.property.BooleanProperty inUse = new javafx.beans.property.SimpleBooleanProperty();

        ListBlock(JSONObject b) {
            super(b);
            items.addAll(NoteData.todos(b.toString()));
            if (items.isEmpty()) items.add(new NoteData.TodoItem("", false));
            for (NoteData.TodoItem item : items) rows.getChildren().add(row(item));
            HBox add = new HBox(Ui.icon(Icons.ADD, 22, "tile_green"), Ui.label("Add item", 15, "secondary"));
            add.getStyleClass().add("add-row");
            add.setOnMouseClicked(e -> {
                if (!host.readOnly()) addAt(items.size());
            });
            add.setMinHeight(0);
            add.setPrefHeight(34);
            add.visibleProperty().bind(inUse.and(new javafx.beans.property.SimpleBooleanProperty(!host.readOnly())));
            add.managedProperty().bind(add.visibleProperty());
            showProgress();
            canvas(new VBox(rows, add));
        }

        private Node row(NoteData.TodoItem item) {
            StackPane box = new StackPane(Ui.icon(Icons.CHECK, 14, "white"));
            box.getStyleClass().add("check");
            box.setMinSize(18, 18);
            box.setMaxSize(18, 18);
            StackPane target = new StackPane(box);
            target.setMinSize(40, 40);
            target.setStyle("-fx-cursor: hand;");
            TextArea text = new TextArea(item.text);
            text.setPrefRowCount(1);
            text.setWrapText(true);
            text.getStyleClass().addAll("bare", "item-text");
            text.setStyle("-fx-font-size: 16px;");
            text.setPromptText("Item");
            text.setEditable(!host.readOnly());
            Ui.autoGrow(text, 0, 0);
            Links.install(text);
            if (!host.readOnly()) LinkSuggest.attach(text, host.linkNotes());
            HBox.setHgrow(text, Priority.ALWAYS);
            Button remove = Ui.iconButton(Icons.CLOSE, 30, 16, "text_secondary", "Delete item", () -> {
                removeAt(items.indexOf(item));
                if (items.isEmpty()) addAt(0);
            });
            // Something inside this item (another list, a map, voice…): it sits under it.
            Button[] inside = new Button[1];
            inside[0] = Ui.iconButton(Icons.ADD, 30, 16, "tile_indigo", "Put something inside this item", () -> {
                javafx.geometry.Bounds at = inside[0].localToScene(inside[0].getBoundsInLocal());
                kindMenu(at.getMinX(), at.getMaxY(), true, kind -> nestUnderItem(this, items.indexOf(item), kind));
            });
            text.focusedProperty().addListener((o, was, is) -> {
                if (is) inUse.set(true);
                else javafx.application.Platform.runLater(() -> inUse.set(rows.getChildren().stream()
                        .anyMatch(r -> r.getUserData() instanceof TextArea t && t.isFocused())));
            });
            HBox row = new HBox(target, text, inside[0], remove);
            row.setAlignment(Pos.CENTER_LEFT);
            inside[0].visibleProperty().bind(row.hoverProperty().or(text.focusedProperty()));
            remove.visibleProperty().bind(row.hoverProperty().or(text.focusedProperty()));
            if (host.readOnly()) row.getChildren().removeAll(inside[0], remove);
            Runnable paint = () -> {
                box.getStyleClass().remove("on");
                if (item.done) box.getStyleClass().add("on");
                box.getChildren().get(0).setVisible(item.done);
                text.setOpacity(item.done ? 0.55 : 1);
            };
            paint.run();
            target.setOnMouseClicked(e -> {
                if (host.readOnly()) return;
                item.done = !item.done;
                paint.run();
                showProgress();
                host.changed();
            });
            text.textProperty().addListener((o, a, v) -> {
                item.text = v.replace('\n', ' ');
                showProgress();
                host.changed();
            });
            text.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                int i = items.indexOf(item);
                if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                    e.consume();
                    target.getOnMouseClicked().handle(null);
                } else if (e.getCode() == KeyCode.ENTER) {
                    e.consume();
                    addAt(i + 1);
                } else if (e.getCode() == KeyCode.BACK_SPACE && text.getText().isEmpty() && items.size() > 1) {
                    e.consume();
                    removeAt(i);
                    focusItem(Math.max(0, i - 1));
                }
            });
            row.setUserData(text);
            return row;
        }

        private void addAt(int i) {
            NoteData.TodoItem item = new NoteData.TodoItem("", false);
            items.add(i, item);
            rows.getChildren().add(i, row(item));
            showProgress();
            focusItem(i);
            host.changed();
        }

        private void removeAt(int i) {
            if (i < 0 || i >= items.size()) return;
            items.remove(i);
            rows.getChildren().remove(i);
            showProgress();
            host.changed();
        }

        private void focusItem(int i) {
            if (i < 0 || i >= rows.getChildren().size()) return;
            TextArea t = (TextArea) rows.getChildren().get(i).getUserData();
            javafx.application.Platform.runLater(() -> {
                t.requestFocus();
                t.end();
            });
        }

        private void showProgress() {
            long all = items.stream().filter(x -> !x.text.trim().isEmpty()).count();
            long done = items.stream().filter(x -> x.done && !x.text.trim().isEmpty()).count();
            progress.setText(all == 0 ? "" : done == all ? "All done" : done + " of " + all + " done");
            progress.setManaged(all > 0);
        }

        @Override
        JSONObject value() {
            List<NoteData.TodoItem> kept = new ArrayList<>();
            for (NoteData.TodoItem i : items) if (!i.text.trim().isEmpty()) kept.add(i);
            return Ultimate.block(id, Ultimate.LIST, NoteData.todosJson(kept));
        }

        @Override
        void focusIn(boolean isNew) {
            focusItem(0);
        }
    }

    // --------------------------------------------------------------- mind map

    private final class MapBlock extends Block {
        private final MindMapCanvas map = new MindMapCanvas(true);
        private final StackPane stage;
        private double height;

        MapBlock(JSONObject b) {
            super(b);
            height = Ultimate.mapHeight(b);
            NoteData.MindMap loaded = NoteData.mindMap(b.toString());
            if (loaded.nodes.isEmpty()) loaded.nodes.add(new NoteData.MindNode(1, -1, "", 0, 0));
            map.setMap(loaded);
            map.locked = host.readOnly();
            map.embedded = true;
            map.insideLabel = n -> Ultimate.insideSummary(Ultimate.inside(n));
            if (host.folder() != null) map.setFolder(host.folder());
            map.listener = new MindMapCanvas.Listener() {
                @Override
                public void onRename(NoteData.MindNode node) {
                    name(node, false);
                }

                @Override
                public void onOptions(NoteData.MindNode node) {
                    options(node);
                }

                @Override
                public void onOpenLink(NoteData.MindNode node) {}

                @Override
                public void onConnected() {
                    host.changed();
                }

                @Override
                public void onStateChanging() {
                    host.changed();
                }

                @Override
                public void onOpenInside(NoteData.MindNode node) {
                    openInside(MapBlock.this, node, null);
                }
            };
            map.addEventFilter(KeyEvent.KEY_PRESSED, this::keys);

            HBox tools = new HBox(8,
                    Ui.miniFab(Icons.CENTER, "Show everything", () -> map.fit(true)),
                    Ui.miniFab(Icons.NOTE_MINDMAP, "Tidy up the layout", () -> {
                        if (!host.readOnly()) {
                            map.tidy();
                            host.changed();
                        }
                    }));
            Button addIdea = Ui.extendedFab("Add idea", Icons.ADD, () -> {
                NoteData.MindNode parent = map.selected();
                addChild(parent != null ? parent : map.root());
            });
            Ui.tooltip(addIdea, "Add idea (Tab)");
            HBox fabs = new HBox(10, tools, addIdea);
            fabs.setAlignment(Pos.CENTER_RIGHT);
            fabs.visibleProperty().bind(hoverProperty().or(map.focusedProperty()));
            fabs.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            fabs.setPickOnBounds(false);
            if (host.readOnly()) fabs.getChildren().setAll(tools.getChildren().get(0));
            StackPane.setAlignment(fabs, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(fabs, new Insets(12));
            stage = new StackPane(map, fabs);
            stage.getStyleClass().add("ult-map");
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
            clip.setArcWidth(20);
            clip.setArcHeight(20);
            clip.widthProperty().bind(stage.widthProperty());
            clip.heightProperty().bind(stage.heightProperty());
            stage.setClip(clip);
            size();

            // Its bottom edge makes it taller or shorter.
            Region bar = new Region();
            bar.getStyleClass().add("ult-grip-bar");
            StackPane grip = new StackPane(bar);
            grip.getStyleClass().add("ult-grip");
            grip.setMinHeight(16);
            grip.setCursor(Cursor.N_RESIZE);
            Ui.tooltip(grip, "Drag to make the map taller or shorter");
            double[] start = new double[2];
            grip.setOnMousePressed(e -> {
                start[0] = e.getScreenY();
                start[1] = height;
            });
            grip.setOnMouseDragged(e -> {
                height = Math.max(Ultimate.MAP_MIN_H, Math.min(Ultimate.MAP_MAX_H, Math.round(start[1] + e.getScreenY() - start[0])));
                size();
            });
            grip.setOnMouseReleased(e -> {
                if (height != start[1]) host.changed();
            });
            grip.opacityProperty().bind(javafx.beans.binding.Bindings.when(hoverProperty()).then(1.0).otherwise(0.0));
            VBox body = new VBox(stage);
            if (!host.readOnly()) body.getChildren().add(grip);
            canvas(body);
            javafx.application.Platform.runLater(() -> map.fit(false));
        }

        private void size() {
            stage.setMinHeight(height);
            stage.setPrefHeight(height);
            stage.setMaxHeight(height);
        }

        private void keys(KeyEvent e) {
            if (host.readOnly()) return;
            NoteData.MindNode s = map.selected();
            if (e.getCode() == KeyCode.TAB) {
                e.consume();
                addChild(s != null ? s : map.root());
            } else if (e.getCode() == KeyCode.ENTER && s != null) {
                e.consume();
                NoteData.MindNode p = map.find(s.parent);
                if (p != null) addChild(p);
                else name(s, false);
            } else if (e.getCode() == KeyCode.F2 && s != null) {
                e.consume();
                name(s, false);
            } else if ((e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) && s != null && map.canRemove(s)) {
                e.consume();
                map.removeBranch(s);
                host.changed();
            }
        }

        private void addChild(NoteData.MindNode parent) {
            if (host.readOnly()) return;
            if (parent.collapsed) map.toggleCollapsed(parent);
            name(map.addChild(parent), true);
            host.changed();
        }

        private void name(NoteData.MindNode node, boolean isNew) {
            boolean root = node.parent < 0;
            Runnable discard = () -> {
                if (isNew && !root && node.text.isEmpty()) map.removeBranch(node);
                map.requestFocus();
            };
            Consumer<String> apply = text -> {
                node.text = text;
                map.select(node);
                map.refresh();
                map.requestFocus();
                host.changed();
            };
            Ui.prompt(root ? "Central idea" : isNew ? "New idea" : "Rename", null,
                    root ? "What is this map about?" : "An idea", node.text, "Done", apply, discard,
                    isNew && !root ? "Next idea" : null, text -> {
                        apply.accept(text);
                        NoteData.MindNode parent = map.find(node.parent);
                        if (parent != null) name(map.addChild(parent), true);
                    });
        }

        private void options(NoteData.MindNode node) {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add("Rename");
            actions.add(() -> name(node, false));
            labels.add("Add idea");
            actions.add(() -> addChild(node));
            labels.add(node.sub.isEmpty() ? "Add sub-text" : "Edit sub-text");
            actions.add(() -> Ui.prompt("Sub-text", null, "More about this idea", node.sub, "Done", text -> {
                node.sub = text.trim();
                map.refresh();
                host.changed();
            }, null, null, null));
            if (map.hasChildren(node)) {
                labels.add(node.collapsed ? "Unfold branch" : "Fold branch");
                actions.add(() -> {
                    map.toggleCollapsed(node);
                    host.changed();
                });
            }
            if (node.blocks != null) {
                labels.add("Open what's inside");
                actions.add(() -> openInside(this, node, null));
            }
            labels.add("Put inside this idea…");
            actions.add(() -> {
                javafx.geometry.Point2D c = map.localToScene(map.getWidth() / 2, map.getHeight() / 3);
                kindMenu(c.getX(), c.getY(), false, kind -> openInside(this, node, kind));
            });
            labels.add("Add another central idea");
            actions.add(() -> {
                NoteData.MindNode r = map.addRoot();
                if (r != null) name(r, true);
                host.changed();
            });
            if (map.canRemove(node)) {
                labels.add(node.parent < 0 ? "Delete this central idea and its ideas" : "Delete branch");
                actions.add(() -> {
                    map.removeBranch(node);
                    host.changed();
                });
            }
            Ui.dialog().title(node.text.isEmpty() ? "Idea" : node.text).items(labels, actions).show();
        }

        @Override
        JSONObject value() {
            return Ultimate.block(id, Ultimate.MINDMAP, NoteData.mindMapJson(map.model)).put("h", (int) height);
        }

        @Override
        void focusIn(boolean isNew) {
            map.requestFocus();
            if (isNew && !host.readOnly()) name(map.root(), true);
        }
    }

    // ------------------------------------------------------------------ audio

    private final class AudioBlock extends Block {
        private final List<NoteData.Recording> recordings = new ArrayList<>();
        private final VBox list = new VBox(8);
        private final Label empty = Ui.label("", 1);
        private final Ui.IconView recordIcon = Ui.icon(Icons.MIC, 18, "tile_red");
        private Button recordButton;
        private final Label timer = Ui.label("", 14, "bold");
        private final WaveformView live = new WaveformView(36);
        private final List<Float> amplitudes = new ArrayList<>();
        private final Timeline sampler = new Timeline(new KeyFrame(Duration.millis(60), e -> sample()));
        private AudioRecorder recorder;
        private Path file;
        private long started;
        private MediaPlayer player;
        private NoteData.Recording playing;
        private final List<Runnable> painters = new ArrayList<>();
        private final AnimationTimer progress = new AnimationTimer() {
            @Override
            public void handle(long now) {
                painters.forEach(Runnable::run);
            }
        };

        AudioBlock(JSONObject b) {
            super(b);
            recordings.addAll(NoteData.recordings(b.toString()));
            sampler.setCycleCount(Timeline.INDEFINITE);
            Button record = new Button("Record", recordIcon);
            recordIcon.setIcon(Icons.MIC);
            record.getStyleClass().setAll("ult-record");
            record.setFocusTraversable(false);
            recordButton = record;
            Ui.tooltip(record, "Record");
            record.setOnAction(e -> {
                if (recorder != null) stop();
                else start();
            });
            HBox.setHgrow(live, Priority.ALWAYS);
            HBox recRow = new HBox(12, record, timer, live);
            recRow.setAlignment(Pos.CENTER_LEFT);
            show(timer, false);
            show(live, false);
            VBox.setMargin(recRow, new Insets(4, 0, 4, 0));
            rebuild();
            VBox body = new VBox(empty, list);
            if (!host.readOnly()) body.getChildren().add(recRow);
            canvas(body);
        }

        private void start() {
            stopPlayback();
            host.withFolder(dir -> {
                file = dir.resolve("rec_" + System.currentTimeMillis() + ".wav");
                AudioRecorder r = new AudioRecorder();
                try {
                    r.start(file);
                } catch (Exception e) {
                    Ui.toast("The microphone isn't available");
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                        // Nothing was written.
                    }
                    return;
                }
                recorder = r;
                started = System.nanoTime();
                amplitudes.clear();
                recordIcon.setIcon(Icons.STOP);
                recordButton.setText("Stop");
                show(timer, true);
                show(live, true);
                timer.setText("0:00");
                rebuild();
                sampler.play();
            });
        }

        private void sample() {
            if (recorder == null) return;
            amplitudes.add((float) Math.min(1, Math.sqrt(recorder.takePeak() / 32767.0)));
            live.setLive(amplitudes);
            timer.setText(NoteData.formatDuration((System.nanoTime() - started) / 1_000_000));
        }

        private void stop() {
            if (recorder == null) return;
            sampler.stop();
            long duration = (System.nanoTime() - started) / 1_000_000;
            recorder.stop();
            recorder = null;
            recordIcon.setIcon(Icons.MIC);
            recordButton.setText("Record");
            show(timer, false);
            show(live, false);
            if (duration < 500) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // Gone already.
                }
                rebuild();
                return;
            }
            float[] wave = new float[48];
            for (int i = 0; i < wave.length && !amplitudes.isEmpty(); i++) {
                int from = i * amplitudes.size() / wave.length, to = Math.max(from + 1, (i + 1) * amplitudes.size() / wave.length);
                for (int k = from; k < to && k < amplitudes.size(); k++) wave[i] = Math.max(wave[i], amplitudes.get(k));
            }
            recordings.add(0, new NoteData.Recording(file.getFileName().toString(), "Recording " + (recordings.size() + 1),
                    duration, System.currentTimeMillis(), wave));
            rebuild();
            host.changed();
        }

        private void rebuild() {
            painters.clear();
            list.getChildren().clear();
            for (NoteData.Recording r : recordings) list.getChildren().add(row(r));
            show(empty, recordings.isEmpty() && recorder == null);
        }

        private Node row(NoteData.Recording r) {
            Ui.IconView glyph = Ui.icon(Icons.PLAY_BIG, 22, "white");
            StackPane play = new StackPane(glyph);
            play.setStyle("-fx-background-color: " + Theme.ref("tile_teal") + "; -fx-background-radius: 8; -fx-cursor: hand;");
            play.setMinSize(42, 42);
            play.setMaxSize(42, 42);
            play.setOnMouseClicked(e -> toggle(r));
            Label label = Ui.label(r.label.isEmpty() ? "Recording" : r.label, 15, "bold");
            label.setStyle("-fx-cursor: hand;");
            label.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY || host.readOnly()) return;
                Ui.prompt("Name this recording", null, "Name", r.label, "Save", v -> {
                    r.label = v.trim();
                    rebuild();
                    host.changed();
                }, null, null, null);
            });
            WaveformView wave = new WaveformView(24);
            Label meta = Ui.label(NoteData.formatDuration(r.durationMs) + "  ·  "
                    + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(r.createdAt)), 12, "secondary");
            VBox texts = new VBox(2, label, wave, meta);
            HBox.setHgrow(texts, Priority.ALWAYS);
            HBox row = new HBox(12, play, texts);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("ult-rec-row");
            if (!host.readOnly()) row.getChildren().add(Ui.iconButton(Icons.CLOSE, 30, 16, "text_secondary", "Delete recording",
                    () -> Ui.confirm("Delete \"" + label.getText() + "\"?", "Delete", () -> {
                        if (playing == r) stopPlayback();
                        recordings.remove(r);
                        rebuild();
                        host.changed();
                    })));
            Runnable paint = () -> {
                boolean on = playing == r && player != null;
                double played = on && player.getTotalDuration() != null && player.getTotalDuration().toMillis() > 0
                        ? player.getCurrentTime().toMillis() / player.getTotalDuration().toMillis() : 0;
                wave.setWave(r.wave, played);
                glyph.setIcon(on && player.getStatus() == MediaPlayer.Status.PLAYING ? Icons.PAUSE : Icons.PLAY_BIG);
            };
            painters.add(paint);
            paint.run();
            return row;
        }

        private void toggle(NoteData.Recording r) {
            if (playing == r && player != null) {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
                else player.play();
                return;
            }
            stopPlayback();
            Path f = host.folder() == null ? null : host.folder().resolve(r.file);
            if (f == null || !Files.exists(f)) {
                fetchThenPlay(r);
                return;
            }
            try {
                player = new MediaPlayer(new Media(f.toUri().toString()));
            } catch (Exception e) {
                Ui.toast("Couldn't play that recording");
                return;
            }
            player.setOnError(() -> {
                Ui.toast("Couldn't play that recording");
                stopPlayback();
            });
            player.setOnEndOfMedia(this::stopPlayback);
            playing = r;
            player.play();
            progress.start();
        }

        /** A recording from another device that hasn't come down yet: fetch it now, then play it. */
        private void fetchThenPlay(NoteData.Recording r) {
            int id = host.noteId();
            if (id <= 0) return;
            Ui.toast("Getting this recording…");
            Account.BG.execute(() -> {
                String problem;
                try {
                    problem = NoteSync.fetch(id, r.file) ? null : Account.drive() == null
                            ? "Connect Google Drive in your account to get recordings from your other devices"
                            : "This recording hasn't been uploaded from its device yet";
                } catch (Exception e) {
                    problem = "Couldn't download this recording. Check your connection";
                }
                String message = problem;
                javafx.application.Platform.runLater(() -> {
                    if (message != null) Ui.toast(message);
                    else if (host.folder() != null && Files.exists(host.folder().resolve(r.file))) toggle(r);
                });
            });
        }

        private void stopPlayback() {
            progress.stop();
            if (player != null) {
                player.dispose();
                player = null;
            }
            playing = null;
            painters.forEach(Runnable::run);
        }

        @Override
        JSONObject value() {
            return Ultimate.block(id, Ultimate.AUDIO, NoteData.recordingsJson(recordings));
        }

        /** Leaving finishes a recording in progress (and keeps it). */
        @Override
        void destroy() {
            stop();
            stopPlayback();
        }
    }

    // -------------------------------------------------------------------- day

    private final class DayBlock extends Block {
        private long day;
        private String mood;
        private final Label num = new Label(), weekday = new Label(), month = new Label();
        private final HBox moods = new HBox(6);
        private final StackPane moodNow = new StackPane();

        DayBlock(JSONObject b) {
            super(b);
            day = (long) b.optDouble("day", 0);
            if (day == 0) day = Ultimate.dayStart(System.currentTimeMillis());
            mood = b.optString("mood", "");
            num.getStyleClass().add("light");
            num.setStyle("-fx-font-size: 40px; -fx-text-fill: " + Theme.ref("tile_purple") + ";");
            weekday.getStyleClass().add("bold");
            weekday.setStyle("-fx-font-size: 16px;");
            month.getStyleClass().add("secondary");
            month.setStyle("-fx-font-size: 16px;");
            HBox date = new HBox(10, num, weekday, month);
            date.setAlignment(Pos.BASELINE_LEFT);
            date.setMaxWidth(Region.USE_PREF_SIZE);
            date.setStyle("-fx-cursor: hand;");
            Ui.tooltip(date, "Change the day");
            date.setOnMouseClicked(e -> {
                if (host.readOnly()) return;
                Ui.pickDate(day, d -> {
                    day = Ultimate.dayStart(d);
                    showDay();
                    host.changed();
                });
            });
            for (String m : MOODS) {
                StackPane chip = new StackPane(Emoji.view(m, 26));
                chip.setMinSize(42, 42);
                chip.setMaxSize(42, 42);
                chip.setUserData(m);
                chip.setOnMouseClicked(e -> {
                    if (host.readOnly()) return;
                    mood = m.equals(mood) ? "" : m;
                    showMoods();
                    moods.setVisible(false);
                    moods.setManaged(false);
                    host.changed();
                });
                moods.getChildren().add(chip);
            }
            // Its mood beside it; a click shows the moods to choose from.
            moodNow.setStyle("-fx-cursor: hand; -fx-padding: 0 6 0 6;");
            moodNow.setOnMouseClicked(e -> {
                if (host.readOnly()) return;
                moods.setVisible(!moods.isVisible());
                moods.setManaged(moods.isVisible());
            });
            moods.setVisible(false);
            moods.setManaged(false);
            showDay();
            showMoods();
            VBox.setMargin(moods, new Insets(2, 0, 0, 0));
            HBox line = new HBox(4, date, moodNow);
            line.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(line, moods);
            box.setPadding(new Insets(10, 0, 4, 0));
            canvas(box);
        }

        private void showDay() {
            Date d = new Date(day);
            num.setText(new SimpleDateFormat("d", Locale.getDefault()).format(d));
            weekday.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(d));
            month.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d));
        }

        private void showMoods() {
            moodNow.getChildren().setAll(mood.isEmpty() ? Ui.label("☺", 20, "secondary") : Emoji.view(mood, 24));
            moodNow.setOpacity(mood.isEmpty() && !host.readOnly() ? 0.5 : 1);
            for (Node chip : moods.getChildren()) {
                boolean on = chip.getUserData().equals(mood);
                ((Region) chip).setStyle("-fx-cursor: hand; -fx-background-radius: 100; -fx-background-color: "
                        + (on ? Theme.ref("note_purple") : "transparent") + ";");
                chip.setOpacity(mood.isEmpty() || on ? 1 : 0.45);
            }
        }

        @Override
        JSONObject value() {
            return new JSONObject().put("id", id).put("kind", Ultimate.DAY).put("day", day).put("mood", mood);
        }
    }

    // ---------------------------------------------------------------- unknown

    /** Made by a newer version: shown as such and kept exactly as it is. */
    private final class UnknownBlock extends Block {
        private final JSONObject raw;

        UnknownBlock(JSONObject b) {
            super(b);
            raw = b;
            canvas(Ui.label("This part was made by a newer Notes+. Update to see it here.", 14, "secondary"));
        }

        @Override
        JSONObject value() {
            return raw;
        }
    }

    /** The page's elements as a JSON array (what's inside an idea), or null when there's nothing. */
    private String array() {
        List<JSONObject> out = kept();
        if (out.isEmpty()) return null;
        org.json.JSONArray a = new org.json.JSONArray();
        for (JSONObject b : out) a.put(b);
        return a.toString();
    }

    /**
     * What's inside an idea: a panel over the page holding a page of its own (to any depth). Every change
     * goes into the idea at once, and its badge follows.
     */
    private void openInside(MapBlock owner, NoteData.MindNode node, String kind) {
        ScrollPane pane = new ScrollPane();
        UltimateBlocks[] inner = new UltimateBlocks[1];
        Runnable sync = () -> {
            node.blocks = inner[0].array();
            owner.map.refresh();
        };
        inner[0] = new UltimateBlocks(new Host() {
            @Override public void withFolder(Consumer<Path> then) { host.withFolder(then); }
            @Override public Path folder() { return host.folder(); }
            @Override public int noteId() { return host.noteId(); }
            @Override public boolean readOnly() { return host.readOnly(); }
            @Override public Supplier<List<NoteEntity>> linkNotes() { return host.linkNotes(); }
            @Override public ScrollPane scroll() { return pane; }

            @Override
            public void changed() {
                host.changed();
                javafx.application.Platform.runLater(sync);
            }

            @Override public void textFocused(RichBody body) { host.textFocused(body); }
        }, false);
        inner[0].load("{\"blocks\":" + (node.blocks == null ? "[]" : node.blocks) + "}", null);
        inner[0].setPadding(new Insets(4, 0, 12, 4));
        pane.setContent(inner[0]);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setPrefSize(600, 460);
        pane.getStyleClass().add("ult-inside");
        VBox box = new VBox(6, Ui.label("INSIDE THE IDEA", 11, "bold"), pane);
        box.getChildren().get(0).setStyle("-fx-text-fill: " + Theme.ref("tile_indigo") + ";");
        if (!host.readOnly()) box.getChildren().add(inner[0].addBar());
        box.setAlignment(Pos.TOP_CENTER);
        Ui.dialog().title(node.text.isEmpty() ? "Idea" : node.text).content(box).width(660)
                .positive("Done", () -> {
                    sync.run();
                    inner[0].destroy();
                    host.changed();
                })
                .show();
        if (kind != null) javafx.application.Platform.runLater(() -> inner[0].add(kind));
        else javafx.application.Platform.runLater(inner[0]::focusStart);
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
