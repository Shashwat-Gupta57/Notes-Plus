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
import javafx.scene.input.TransferMode;
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

    static final Pattern EMBED = Pattern.compile("^!\\[\\[([^\\]/\\\\]+)\\]\\]$");

    /** Asks the editor for the note's folder (creating the note first if need be). */
    interface Folder {
        void with(Consumer<Path> then);
    }

    private final Folder folder;
    private final ScrollPane scroll;
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
            if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream().anyMatch(RichBody::isPicture)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragDropped(this::dropped);
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    /** Told when the caret moves or the formatting under it changes (the toolbar follows). */
    void setOnCaret(Runnable r) {
        onCaret = r;
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
            Matcher m = EMBED.matcher(line.trim());
            if (m.matches()) {
                getChildren().add(text(text.toString()));
                getChildren().add(new Picture(m.group(1)));
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
            else if (n instanceof Picture p) out.append("![[").append(p.name).append("]]");
        }
        return out.toString();
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
            if (getChildren().get(i) instanceof RichArea a) a.setMinRoom(i == getChildren().size() - 1 ? 240 : 0);
        }
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
        Path file = dir == null ? null : dir.resolve(p.name);
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
                getChildren().add(at + 1, new Picture(name));
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

    /** A picture in the text: its own size, never wider than the page. Click to view it big. */
    private final class Picture extends StackPane {
        final String name;
        private final ImageView view = new ImageView();
        private final Rectangle clip = new Rectangle();

        Picture(String name) {
            this.name = name;
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(6, 0, 6, 0));
            view.setPreserveRatio(true);
            view.setSmooth(true);
            getChildren().add(view);
            setStyle("-fx-cursor: hand;");
            Path file = dir == null ? null : dir.resolve(name);
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
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && file != null) MediaViewer.open(file, false);
            });
            setOnContextMenuRequested(e -> Ui.dialog().items(List.of("View", "Remove picture"),
                    List.of(() -> {
                        if (file != null) MediaViewer.open(file, false);
                    }, () -> remove(this))).show());
            Ui.tooltip(this, "Click to view · right-click to remove");
        }

        private void fit() {
            Image img = view.getImage();
            if (img == null) return;
            double room = Math.max(40, getWidth() > 0 ? getWidth() : 700);
            double w = Math.min(img.getWidth(), room);
            view.setFitWidth(w);
            clip.setWidth(w);
            clip.setHeight(w * img.getHeight() / Math.max(1, img.getWidth()));
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
