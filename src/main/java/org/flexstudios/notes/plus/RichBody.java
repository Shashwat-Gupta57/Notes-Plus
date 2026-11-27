package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of a text or journal note, formatted as they read (see {@link RichArea}), with
 * pictures between them: paste an image (or drop one) and it lands where the caret was, at its
 * own size (never wider than the page), like Word or Microsoft To Do.
 *
 * It is a column of blocks: rich text, and pictures between them. In the note's text each
 * picture is a line of its own, {@code ![[IMG_….png]]} (Obsidian's embed), and the file sits
 * in the note's folder; everything else is Markdown. Backspace at the start of the text after
 * a picture (or Delete before it) removes the picture; so does its right-click.
 */
final class RichBody extends VBox {

    /** A note sent to me: read only, nothing pasted or dropped in. */
    boolean locked;

    /** A picture line (group 1: its name), maybe with a size and place: see {@link PictureLine}. */
    static final Pattern EMBED = Pattern.compile("^!\\[\\[([^\\]|/\\\\]+)(?:\\|[^\\]]*)?\\]\\]$");

    /** Asks the editor for the note's folder (creating the note first if need be). */
    interface Folder {
        void with(Consumer<Path> then);
    }

    private final Folder folder;
    private final ScrollPane scroll;
    /** Every note, for "[[": set by the editor. */
    private java.util.function.Supplier<List<NoteEntity>> linkNotes = List::of;
    private Path dir;
    private String prompt = "Start writing";
    private Runnable onChange = () -> {};
    private Runnable onCaret = () -> {};
    private RichArea active;

    RichBody(Folder folder, ScrollPane scroll) {
        this.folder = folder;
        this.scroll = scroll;
        setPadding(new Insets(0, 20, 0, 20));
        setOnDragOver(e -> {
            if (locked) return;
            if (moving != null && e.getDragboard().hasString() && e.getDragboard().getString().startsWith(MOVE)) {
                e.acceptTransferModes(TransferMode.MOVE);
            } else if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream().anyMatch(RichBody::isPicture)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragDropped(this::dropped);
        // A click anywhere else lets go of a selected picture.
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (selected != null && !(e.getTarget() instanceof Node n && isInside(n, selected))) deselect();
            });
        });
    }

    private static boolean isInside(Node n, Node parent) {
        for (Node x = n; x != null; x = x.getParent()) if (x == parent) return true;
        return false;
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    /** Told when the caret moves or the formatting under it changes (the toolbar follows). */
    void setOnCaret(Runnable r) {
        onCaret = r;
    }

    /** The writing room under the last words (a text note's page); less where other things follow. */
    private double room = 240;

    void setRoom(double px) {
        room = px;
        refreshPrompts();
    }

    void setPrompt(String p) {
        prompt = p;
        refreshPrompts();
    }

    // ================================================================ content

    /** Lays out {@code content}; {@code noteDir} is where its pictures are (null for a new note). */
    void setContent(String content, Path noteDir) {
        dir = noteDir;
        Diag.textReport("Note opened", content);
        getChildren().clear();
        StringBuilder text = new StringBuilder();
        boolean pending = false;
        for (String line : (content == null ? "" : content).split("\n", -1)) {
            PictureLine pic = PictureLine.parse(line);
            if (pic != null) {
                getChildren().add(text(text.toString()));
                getChildren().add(new Picture(pic));
                text.setLength(0);
                pending = false;
            } else {
                if (pending) text.append('\n');
                text.append(line);
                pending = true;
            }
        }
        getChildren().add(text(text.toString()));
        refreshPrompts();
    }

    /** The note's text as Markdown, pictures as embed lines. */
    String getContent() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < getChildren().size(); i++) {
            if (i > 0) out.append('\n');
            Node n = getChildren().get(i);
            if (n instanceof RichArea a) out.append(a.markdown());
            else if (n instanceof Picture p) out.append(p.line.format());
        }
        return out.toString();
    }

    /** The Markdown before and after the caret, pictures included (where something else goes in). */
    String[] split() {
        RichArea a = active();
        if (a == null) return new String[]{getContent(), ""};
        int at = getChildren().indexOf(a), caret = Math.min(a.getCaretPosition(), a.getLength());
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        for (int i = 0; i < at; i++) line(before, getChildren().get(i));
        String head = a.markdown(0, caret), rest = a.markdown(caret, a.getLength());
        if (!head.isEmpty()) before.append(before.length() > 0 ? "\n" : "").append(head);
        if (!rest.isEmpty()) after.append(rest);
        for (int i = at + 1; i < getChildren().size(); i++) line(after, getChildren().get(i));
        return new String[]{before.toString().replaceAll("\n+$", ""), after.toString().replaceAll("^\n+", "")};
    }

    private static void line(StringBuilder out, Node n) {
        String s = n instanceof RichArea a ? a.markdown() : n instanceof Picture p ? p.line.format() : "";
        if (s.isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(s);
    }

    /** "/" typed alone on a line (Ultimate notes): told where, after the "/" is taken away. */
    private java.util.function.Consumer<javafx.geometry.Point2D> onSlash;

    void setOnSlash(java.util.function.Consumer<javafx.geometry.Point2D> r) {
        onSlash = r;
    }

    /** Only the words, for counting. */
    String words() {
        StringBuilder out = new StringBuilder();
        for (Node n : getChildren()) if (n instanceof RichArea a) out.append(a.getText()).append('\n');
        return out.toString().trim();
    }

    boolean hasPictures() {
        for (Node n : getChildren()) if (n instanceof Picture) return true;
        return false;
    }

    void focusStart() {
        RichArea first = firstText();
        if (first != null) {
            first.requestFocus();
            first.moveTo(0);
        }
    }

    void focusEnd() {
        RichArea last = lastText();
        if (last != null) {
            last.requestFocus();
            last.moveTo(last.getLength());
        }
    }

    private RichArea firstText() {
        for (Node n : getChildren()) if (n instanceof RichArea a) return a;
        return null;
    }

    private RichArea lastText() {
        RichArea last = null;
        for (Node n : getChildren()) if (n instanceof RichArea a) last = a;
        return last;
    }

    /** The block being written in (or the last one): where the toolbar's formatting goes. */
    RichArea active() {
        if (active != null && active.getScene() != null && getChildren().contains(active)) return active;
        return lastText();
    }

    /** Only the first block shows the prompt, and only while the body is empty. */
    private void refreshPrompts() {
        boolean empty = getChildren().size() == 1;
        RichArea first = firstText();
        for (Node n : getChildren()) if (n instanceof RichArea a) a.setPrompt(empty && a == first ? prompt : null);
        // Sections between pictures stay small; the last one is the page's writing room.
        for (int i = 0; i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof RichArea a) a.setMinRoom(i == getChildren().size() - 1 ? room : 0);
        }
    }

    /** Where "[[" finds the notes it offers. */
    void setLinkNotes(java.util.function.Supplier<List<NoteEntity>> notes) {
        linkNotes = notes;
    }

    /** Makes every block read-only (a note sent to me) or editable again. */
    void setEditable(boolean editable) {
        for (Node n : getChildren()) if (n instanceof RichArea a) a.setEditable(editable);
    }

    // ================================================================= blocks

    private RichArea text(String markdown) {
        RichArea area = new RichArea(scroll);
        area.setMarkdown(markdown);
        area.setEditable(!locked);
        area.setOnChange(() -> onChange.run());
        area.setOnCaret(() -> {
            if (area.isFocused()) active = area;
            onCaret.run();
        });
        area.focusedProperty().addListener((o, was, is) -> {
            if (is) {
                active = area;
                onCaret.run();
            }
        });
        area.zoom(Zoom.level());
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> keys(area, e));
        area.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            if (onSlash == null || locked || !"/".equals(e.getCharacter())) return;
            Platform.runLater(() -> {
                int p = area.getCurrentParagraph();
                if (!area.getParagraph(p).getText().trim().equals("/")) return;
                int caret = area.getCaretPosition();
                if (caret > 0) area.deleteText(caret - 1, caret);
                active = area;
                javafx.geometry.Bounds b = area.getCaretBounds().orElse(area.localToScreen(area.getBoundsInLocal()));
                javafx.geometry.Point2D at = area.getScene() == null ? null : area.getScene().getRoot().screenToLocal(b.getMinX(), b.getMaxY());
                if (at != null) onSlash.accept(area.getScene().getRoot().localToScene(at));
            });
        });
        LinkSuggest.attach(area, () -> linkNotes.get());
        return area;
    }

    private static final KeyCombination PASTE = KeyCombination.keyCombination("Shortcut+V");
    private static final KeyCombination PASTE_INSERT = KeyCombination.keyCombination("Shift+Insert");

    private void keys(RichArea area, KeyEvent e) {
        if (locked || e.isConsumed()) return;
        int index = getChildren().indexOf(area);
        boolean none = area.getSelection().getLength() == 0;
        if (PASTE.match(e) || PASTE_INSERT.match(e)) {
            if (pasteImage(area)) e.consume();
        } else if (e.getCode() == KeyCode.BACK_SPACE && none && area.getCaretPosition() == 0
                && index > 0 && getChildren().get(index - 1) instanceof Picture p) {
            e.consume();
            remove(p);
        } else if (e.getCode() == KeyCode.DELETE && none && area.getCaretPosition() == area.getLength()
                && index + 1 < getChildren().size() && getChildren().get(index + 1) instanceof Picture p) {
            e.consume();
            remove(p);
        } else if (e.getCode() == KeyCode.UP && area.getCurrentParagraph() == 0 && index > 1) {
            e.consume();
            RichArea prev = (RichArea) getChildren().get(index - 2);
            prev.requestFocus();
            prev.moveTo(prev.getLength());
        } else if (e.getCode() == KeyCode.DOWN && area.getCurrentParagraph() == area.getParagraphs().size() - 1
                && index + 2 < getChildren().size()) {
            e.consume();
            RichArea next = (RichArea) getChildren().get(index + 2);
            next.requestFocus();
            next.moveTo(0);
        }
    }

    /** A picture goes, and the text either side of it joins up again. */
    private void remove(Picture p) {
        int i = getChildren().indexOf(p);
        if (i < 1 || i + 1 >= getChildren().size()) return;
        RichArea before = (RichArea) getChildren().get(i - 1);
        RichArea after = (RichArea) getChildren().get(i + 1);
        int caret = before.getLength();
        String a = before.markdown(), b = after.markdown();
        getChildren().remove(i, i + 2);
        before.setMarkdown(a.isEmpty() || b.isEmpty() ? a + b : a + "\n" + b);
        refreshPrompts();
        before.requestFocus();
        before.moveTo(Math.min(caret, before.getLength()));
        onChange.run();
        // The file goes with the save: until then Undo-by-retyping is not a thing, so delete now.
        Path file = dir == null ? null : dir.resolve(p.line.name);
        if (file != null) NoteStore.write(s -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // A stray file in the note's folder is harmless.
            }
        });
    }

    // ================================================================ pasting

    /** Pastes the clipboard's picture at the caret; false when the clipboard holds no picture. */
    private boolean pasteImage(RichArea area) {
        Clipboard cb = Clipboard.getSystemClipboard();
        File file = cb.hasFiles() ? cb.getFiles().stream().filter(RichBody::isPicture).findFirst().orElse(null) : null;
        Image image = file == null && cb.hasImage() ? cb.getImage() : null;
        if (file == null && image == null) return false;
        if (area.getSelection().getLength() > 0) area.replaceSelection("");
        insert(area, area.getCaretPosition(), file, image);
        return true;
    }

    private void dropped(DragEvent e) {
        if (moving != null && e.getDragboard().hasString() && e.getDragboard().getString().startsWith(MOVE)) {
            dropMoving(e);
            return;
        }
        List<File> files = new ArrayList<>();
        if (e.getDragboard().hasFiles()) for (File f : e.getDragboard().getFiles()) if (isPicture(f)) files.add(f);
        if (files.isEmpty()) return;
        RichArea target = null;
        for (Node n : getChildren()) if (n instanceof RichArea a && a.isFocused()) target = a;
        if (target == null) target = lastText();
        if (target == null) return;
        int caret = target.isFocused() ? target.getCaretPosition() : target.getLength();
        for (File f : files) insert(target, caret, f, null);
        e.setDropCompleted(true);
        e.consume();
    }

    /** Pictures chosen from a file picker: where the caret is, or after the words. */
    void addPictures(List<File> files) {
        RichArea target = active();
        if (target == null) return;
        int caret = target.isFocused() ? target.getCaretPosition() : target.getLength();
        for (File f : files) if (isPicture(f)) insert(target, caret, f, null);
    }

    /** Writes the picture into the note's folder, then splits {@code area} at {@code caret} around it. */
    private void insert(RichArea area, int caret, File file, Image image) {
        String ext = file == null ? ".png" : extension(file.getName());
        String name = "IMG_" + System.currentTimeMillis() + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF)) + ext;
        var buffered = image == null ? null : SwingFXUtils.fromFXImage(image, null);
        folder.with(noteDir -> {
            dir = noteDir;
            NoteStore.load(s -> {
                try {
                    Path target = noteDir.resolve(name);
                    if (file != null) Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                    else ImageIO.write(buffered, "png", target.toFile());
                    return true;
                } catch (Exception ex) {
                    System.getLogger("RichBody").log(System.Logger.Level.WARNING, "Could not keep the picture", ex);
                    return false;
                }
            }, ok -> {
                if (!ok) {
                    Ui.toast("Couldn't add that picture");
                    return;
                }
                int at = getChildren().indexOf(area);
                if (at < 0) return;
                int cut = Math.min(caret, area.getLength());
                String before = area.markdown(0, cut), after = area.markdown(cut, area.getLength());
                // A line break right at the cut belongs to the picture's own line.
                if (before.endsWith("\n")) before = before.substring(0, before.length() - 1);
                if (after.startsWith("\n")) after = after.substring(1);
                area.setMarkdown(before);
                RichArea rest = text(after);
                getChildren().add(at + 1, new Picture(new PictureLine(name, 0, PictureLine.LEFT)));
                getChildren().add(at + 2, rest);
                refreshPrompts();
                onChange.run();
                Platform.runLater(() -> {
                    rest.requestFocus();
                    rest.moveTo(0);
                });
            });
        });
    }

    /**
     * Keeps a pasted or dropped picture in the note's folder under a new name, off the FX thread;
     * {@code then} gets the name, or null when it couldn't be written.
     */
    static void savePicture(Path noteDir, File file, Image image, Consumer<String> then) {
        String ext = file == null ? ".png" : extension(file.getName());
        String name = "IMG_" + System.currentTimeMillis() + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF)) + ext;
        var buffered = image == null ? null : SwingFXUtils.fromFXImage(image, null);
        NoteStore.load(s -> {
            try {
                Path target = noteDir.resolve(name);
                if (file != null) Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                else ImageIO.write(buffered, "png", target.toFile());
                return name;
            } catch (Exception ex) {
                System.getLogger("RichBody").log(System.Logger.Level.WARNING, "Could not keep the picture", ex);
                return null;
            }
        }, then);
    }

    /** The clipboard's picture: an image file copied in Explorer, or an image. Null for neither. */
    static Object clipboardPicture() {
        Clipboard cb = Clipboard.getSystemClipboard();
        File file = cb.hasFiles() ? cb.getFiles().stream().filter(RichBody::isPicture).findFirst().orElse(null) : null;
        if (file != null) return file;
        return cb.hasImage() ? cb.getImage() : null;
    }

    static boolean isPicture(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".png" : name.substring(dot).toLowerCase(Locale.US);
    }

    // ============================================================= pictures
    // Click a picture to select it: handles on its corners and sides resize it (a share of the
    // page, in steps of 5%), and a bar (or its right-click menu) sizes, places, moves, views or
    // removes it; dragging it into the words moves it there. Double-click views it. The line keeps
    // it all: ![[name|60%|center]] ({@link PictureLine}), the same on the phone and the web.

    private static final String MOVE = "notesplus-picture:";
    private Picture selected, moving;

    /** Lets go of a selected picture; true when one was (Esc goes no further). */
    boolean deselect() {
        if (selected == null) return false;
        Picture p = selected;
        selected = null;
        p.showChrome(false);
        return true;
    }

    private void select(Picture p) {
        if (selected == p) return;
        deselect();
        selected = p;
        p.showChrome(true);
        p.requestFocus();
    }

    /** The note's lines now, every block as it stands. */
    private List<String> lines() {
        return new ArrayList<>(List.of(getContent().split("\n", -1)));
    }

    /** Draws the note afresh from its lines; a picture called {@code keep} stays selected. */
    private void relayout(List<String> lines, String keep) {
        selected = null;
        setContent(String.join("\n", lines), dir);
        for (Node n : getChildren()) if (n instanceof Picture p && p.line.name.equals(keep)) select(p);
        onChange.run();
    }

    /** Up or down past the next line of words (or picture). */
    private void move(Picture p, int dir) {
        List<String> all = lines();
        int i = -1;
        for (int k = 0; k < all.size(); k++) {
            PictureLine q = PictureLine.parse(all.get(k));
            if (q != null && q.name.equals(p.line.name)) { i = k; break; }
        }
        int j = i + dir;
        while (j >= 0 && j < all.size() && all.get(j).trim().isEmpty()) j += dir;
        if (i < 0 || j < 0 || j >= all.size()) return;
        String line = all.remove(i);
        all.add(j, line);
        relayout(all, p.line.name);
    }

    /** A picture dragged into the words: before the paragraph dropped on (its top half) or after it. */
    private void dropMoving(DragEvent e) {
        Picture p = moving;
        moving = null;
        RichArea target = null;
        for (Node n : getChildren()) {
            if (n instanceof RichArea a && a.localToScene(a.getBoundsInLocal()).contains(e.getSceneX(), e.getSceneY())) target = a;
        }
        if (target == null) return;
        javafx.geometry.Point2D at = target.sceneToLocal(e.getSceneX(), e.getSceneY());
        int index = target.hit(at.getX(), at.getY()).getInsertionIndex();
        int par = target.offsetToPosition(index, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        int cut = target.getAbsolutePosition(par, 0);
        var bounds = target.getParagraphBoundsOnScreen(par);
        if (bounds.isPresent() && e.getScreenY() > bounds.get().getMinY() + bounds.get().getHeight() / 2) {
            cut += target.getParagraph(par).length();
        }
        String before = target.markdown(0, cut), after = target.markdown(cut, target.getLength());
        if (before.endsWith("\n")) before = before.substring(0, before.length() - 1);
        if (after.startsWith("\n")) after = after.substring(1);
        List<String> out = new ArrayList<>();
        for (Node n : getChildren()) {
            if (n == p) continue;
            if (n == target) {
                if (!before.isEmpty()) out.add(before);
                out.add(p.line.format());
                if (!after.isEmpty()) out.add(after);
            } else if (n instanceof RichArea a) {
                if (!a.markdown().isEmpty()) out.add(a.markdown());
            } else if (n instanceof Picture q) {
                out.add(q.line.format());
            }
        }
        e.setDropCompleted(true);
        e.consume();
        relayout(out, p.line.name);
    }

    /** A picture in the text, at its own size (or its share of the page), placed left, centre or right. */
    private final class Picture extends StackPane {
        final PictureLine line;
        private final ImageView view = new ImageView();
        private final Rectangle clip = new Rectangle();
        private final javafx.scene.layout.Pane chrome = new javafx.scene.layout.Pane();
        private final javafx.scene.control.Label size = new javafx.scene.control.Label();
        private final List<javafx.scene.shape.Circle> handles = new ArrayList<>();
        private final HBox bar = new HBox(2);

        Picture(PictureLine line) {
            this.line = line;
            setPadding(new Insets(6, 0, 6, 0));
            setFocusTraversable(false);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            getChildren().addAll(view, chrome);
            chrome.setPickOnBounds(false);
            chrome.setManaged(false);
            chrome.setVisible(false);
            place();
            setStyle("-fx-cursor: hand;");
            Path file = dir == null ? null : dir.resolve(line.name);
            if (file != null) {
                Images.load(file, 2048, img -> {
                    if (img == null) return;
                    view.setImage(img);
                    fit();
                });
            }
            widthProperty().addListener((o, a, b) -> fit());
            clip.setArcWidth(16);
            clip.setArcHeight(16);
            view.setClip(clip);
            Runnable open = () -> {
                if (file != null) MediaViewer.open(file, false);
            };
            if (locked) {
                setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) open.run(); });
                Ui.tooltip(this, "Click to view");
                return;
            }
            view.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                if (e.getClickCount() >= 2) open.run();
                else select(this);
            });
            setOnContextMenuRequested(e -> {
                select(this);
                Ui.menuAt(e.getSceneX(), e.getSceneY(), "Picture",
                        List.of("Small (25%)", "Medium (50%)", "Large (75%)", "Full width", "As it comes",
                                "Left", "Centre", "Right", "Move up", "Move down", "View", "Remove picture"),
                        List.of(() -> set(25, null), () -> set(50, null), () -> set(75, null), () -> set(100, null), () -> set(0, null),
                                () -> set(-1, PictureLine.LEFT), () -> set(-1, PictureLine.CENTER), () -> set(-1, PictureLine.RIGHT),
                                () -> move(this, -1), () -> move(this, 1), open, () -> remove(this)));
                e.consume();
            });
            setOnKeyPressed(e -> {
                if (selected != this) return;
                if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                    e.consume();
                    deselect();
                    remove(this);
                }
            });
            // Dragged into the words: moved there.
            view.setOnDragDetected(e -> {
                moving = this;
                var board = view.startDragAndDrop(TransferMode.MOVE);
                var content = new javafx.scene.input.ClipboardContent();
                content.putString(MOVE + line.name);
                board.setContent(content);
                if (view.getImage() != null) board.setDragView(view.snapshot(null, null), 0, 0);
                e.consume();
            });
            view.setOnDragDone(e -> moving = null);
            Ui.tooltip(view, "Click to select · double-click to view · right-click for more");
            buildChrome(open);
        }

        private void buildChrome(Runnable open) {
            String[][] sizes = {{"S", "25"}, {"M", "50"}, {"L", "75"}, {"Full", "100"}, {"Auto", "0"}};
            for (String[] s : sizes) {
                int w = Integer.parseInt(s[1]);
                javafx.scene.control.Button b = new javafx.scene.control.Button(s[0]);
                b.getStyleClass().add("pic-chip");
                b.setUserData(w);
                b.setOnAction(e -> set(w, null));
                bar.getChildren().add(b);
            }
            bar.getChildren().add(sep());
            Object[][] places = {{Icons.ALIGN_LEFT, PictureLine.LEFT, "Left"}, {Icons.ALIGN_CENTER, PictureLine.CENTER, "Centre"},
                    {Icons.ALIGN_RIGHT, PictureLine.RIGHT, "Right"}};
            for (Object[] p : places) {
                String a = (String) p[1];
                javafx.scene.control.Button b = Ui.iconButton((Icons.Icon) p[0], 32, 18, "text_primary", (String) p[2], () -> set(-1, a));
                b.setUserData(a);
                bar.getChildren().add(b);
            }
            bar.getChildren().add(sep());
            bar.getChildren().addAll(
                    Ui.iconButton(Icons.ARROW_UP, 32, 18, "text_primary", "Move up", () -> move(this, -1)),
                    Ui.iconButton(Icons.ARROW_DOWN, 32, 18, "text_primary", "Move down", () -> move(this, 1)),
                    Ui.iconButton(Icons.PHOTO, 32, 18, "text_primary", "View (double-click)", open),
                    Ui.iconButton(Icons.DELETE, 32, 18, "destructive", "Remove picture (Delete)", () -> remove(this)));
            bar.getStyleClass().add("pic-bar");
            bar.setAlignment(Pos.CENTER_LEFT);
            size.getStyleClass().add("pic-size");
            chrome.getChildren().addAll(bar, size);
            String[] corners = {"nw", "ne", "sw", "se", "w", "e"};
            for (String c : corners) {
                javafx.scene.shape.Circle h = new javafx.scene.shape.Circle(7);
                h.getStyleClass().add("pic-handle");
                h.setCursor(c.equals("w") || c.equals("e") ? javafx.scene.Cursor.H_RESIZE
                        : c.equals("nw") || c.equals("se") ? javafx.scene.Cursor.NW_RESIZE : javafx.scene.Cursor.NE_RESIZE);
                h.setUserData(c);
                double[] start = new double[2];
                h.setOnMousePressed(e -> {
                    start[0] = e.getScreenX();
                    start[1] = view.getBoundsInParent().getWidth();
                    e.consume();
                });
                h.setOnMouseDragged(e -> {
                    double room = room();
                    int sign = c.contains("w") ? -1 : 1, both = PictureLine.CENTER.equals(line.align) ? 2 : 1;
                    double w = start[1] + sign * both * (e.getScreenX() - start[0]);
                    line.width = Math.max(10, PictureLine.snap(w / room * 100));
                    fit();
                    e.consume();
                });
                h.setOnMouseReleased(e -> {
                    paint();
                    onChange.run();
                    e.consume();
                });
                handles.add(h);
                chrome.getChildren().add(h);
            }
        }

        private javafx.scene.control.Button chip(String text, String tip, Runnable r) {
            javafx.scene.control.Button b = new javafx.scene.control.Button(text);
            b.getStyleClass().add("pic-chip");
            Ui.tooltip(b, tip);
            b.setOnAction(e -> r.run());
            return b;
        }

        private javafx.scene.layout.Region sep() {
            javafx.scene.layout.Region r = new javafx.scene.layout.Region();
            r.getStyleClass().add("pic-sep");
            return r;
        }

        /** {@code width} (-1: as it is) and {@code align} (null: as it is). */
        private void set(int width, String align) {
            if (width >= 0) line.width = PictureLine.snap(width);
            if (align != null) line.align = align;
            place();
            fit();
            paint();
            onChange.run();
        }

        private void place() {
            Pos pos = PictureLine.CENTER.equals(line.align) ? Pos.TOP_CENTER : PictureLine.RIGHT.equals(line.align) ? Pos.TOP_RIGHT : Pos.TOP_LEFT;
            StackPane.setAlignment(view, pos);
        }

        void showChrome(boolean on) {
            chrome.setVisible(on);
            setStyle(on ? "-fx-cursor: default;" : "-fx-cursor: hand;");
            paint();
            requestLayout();
        }

        private void paint() {
            size.setText(line.width > 0 ? line.width + "%" : "As it comes");
            for (Node n : bar.getChildren()) {
                Object d = n.getUserData();
                boolean on = (d instanceof Integer w && w == line.width) || (d instanceof String a && a.equals(line.align));
                if (on) { if (!n.getStyleClass().contains("on")) n.getStyleClass().add("on"); } else n.getStyleClass().remove("on");
            }
        }

        private double room() {
            return Math.max(40, getWidth() > 0 ? getWidth() : 700);
        }

        private void fit() {
            Image img = view.getImage();
            if (img == null) return;
            double room = room();
            double w = line.width > 0 ? room * line.width / 100.0 : Math.min(img.getWidth(), room);
            view.setFitWidth(w);
            clip.setWidth(w);
            clip.setHeight(w * img.getHeight() / Math.max(1, img.getWidth()));
            paint();
            requestLayout();
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            if (!chrome.isVisible()) return;
            var b = view.getBoundsInParent();
            chrome.resizeRelocate(0, 0, getWidth(), getHeight());
            double[][] at = {{b.getMinX(), b.getMinY()}, {b.getMaxX(), b.getMinY()}, {b.getMinX(), b.getMaxY()},
                    {b.getMaxX(), b.getMaxY()}, {b.getMinX(), b.getMinY() + b.getHeight() / 2}, {b.getMaxX(), b.getMinY() + b.getHeight() / 2}};
            for (int i = 0; i < handles.size(); i++) {
                handles.get(i).setCenterX(at[i][0]);
                handles.get(i).setCenterY(at[i][1]);
            }
            bar.autosize();
            bar.relocate(Math.max(0, Math.min(b.getMinX() + 8, getWidth() - bar.getWidth())), b.getMinY() + 8);
            size.autosize();
            size.relocate(b.getMaxX() - size.getWidth() - 8, b.getMaxY() - size.getHeight() - 8);
        }
    }

    // ============================================================= formatting

    void toggleBold() {
        withActive(a -> a.toggle(Markdown.Style::bold, Markdown.Style::bold));
    }

    void toggleItalic() {
        withActive(a -> a.toggle(Markdown.Style::italic, Markdown.Style::italic));
    }

    void toggleUnderline() {
        withActive(a -> a.toggle(Markdown.Style::underline, Markdown.Style::underline));
    }

    void toggleStrike() {
        withActive(a -> a.toggle(Markdown.Style::strike, Markdown.Style::strike));
    }

    void toggleCode() {
        withActive(a -> a.toggle(Markdown.Style::code, Markdown.Style::code));
    }

    void color(String hex) {
        withActive(a -> a.color(hex));
    }

    void block(Markdown.Block b) {
        withActive(a -> a.block(b));
    }

    /** Line spacing for every note: a view choice, like zoom. */
    void lineSpacing(double px) {
        RichArea.setLineSpacing(px);
        for (Node n : getChildren()) if (n instanceof RichArea a) a.restyleParagraphs();
    }

    void kerning(double px) {
        RichArea.setKerning(px);
        for (Node n : getChildren()) if (n instanceof RichArea a) a.restyleParagraphs();
    }

    private void withActive(Consumer<RichArea> f) {
        if (locked) return;
        RichArea a = active();
        if (a == null) return;
        f.accept(a);
        a.requestFocus();
    }
}
