package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
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
 * The words of a text or journal note, with pictures between them: paste an image (or drop
 * one) and it lands where the caret was, at its own size (never wider than the page), like
 * Obsidian or Microsoft To Do.
 *
 * It is a column of blocks: text areas, and pictures between them. In the note's text each
 * picture is a line of its own, {@code ![[IMG_….png]]} (Obsidian's embed), and the file sits
 * in the note's folder, so the content stays plain, searchable text. Backspace at the start of
 * the text after a picture (or Delete before it) removes the picture; so does its right-click.
 */
final class RichBody extends VBox {

    /** A note sent to me: read only, nothing pasted or dropped in. */
    boolean locked;

    static final Pattern EMBED = Pattern.compile("^!\\[\\[([^\\]/\\\\]+)\\]\\]$");
    private static final double SPACING = 4.5;

    /** Asks the editor for the note's folder (creating the note first if need be). */
    interface Folder {
        void with(Consumer<Path> then);
    }

    private final Folder folder;
    private final ScrollPane scroll;
    private Path dir;
    private String prompt = "Start writing";
    private Runnable onChange = () -> {};

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

    void setPrompt(String p) {
        prompt = p;
        TextArea first = firstText();
        if (first != null) first.setPromptText(p);
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

    /** The note's text, pictures as embed lines. */
    String getContent() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < getChildren().size(); i++) {
            if (i > 0) out.append('\n');
            Node n = getChildren().get(i);
            if (n instanceof TextArea t) out.append(t.getText());
            else if (n instanceof Picture p) out.append("![[").append(p.name).append("]]");
        }
        return out.toString();
    }

    /** Only the words, for counting. */
    String words() {
        StringBuilder out = new StringBuilder();
        for (Node n : getChildren()) if (n instanceof TextArea t) out.append(t.getText()).append('\n');
        return out.toString().trim();
    }

    boolean hasPictures() {
        for (Node n : getChildren()) if (n instanceof Picture) return true;
        return false;
    }

    void focusStart() {
        TextArea first = firstText();
        if (first != null) {
            first.requestFocus();
            first.home();
        }
    }

    void focusEnd() {
        TextArea last = null;
        for (Node n : getChildren()) if (n instanceof TextArea t) last = t;
        if (last != null) {
            last.requestFocus();
            last.end();
        }
    }

    private TextArea firstText() {
        for (Node n : getChildren()) if (n instanceof TextArea t) return t;
        return null;
    }

    /** Only the first text block shows the prompt, and only while the body is empty. */
    private void refreshPrompts() {
        boolean empty = getChildren().size() == 1;
        for (Node n : getChildren()) if (n instanceof TextArea t) t.setPromptText(null);
        TextArea first = firstText();
        if (first != null && empty) first.setPromptText(prompt);
        // Sections between pictures stay small; the last one is the page's writing room.
        for (int i = 0; i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof TextArea t) t.setUserData(i == getChildren().size() - 1 ? 240.0 : 0.0);
        }
        for (Node n : getChildren()) if (n instanceof TextArea t) grow(t);
    }

    // ================================================================= blocks

    private TextArea text(String value) {
        TextArea area = new TextArea(value);
        area.getStyleClass().addAll("bare", "body-input", "lined");
        area.setStyle("-fx-font-size: 18px;");
        area.setWrapText(true);
        area.setUserData(240.0);
        area.textProperty().addListener((o, a, b) -> {
            grow(area);
            onChange.run();
        });
        area.widthProperty().addListener((o, a, b) -> grow(area));
        area.fontProperty().addListener((o, a, b) -> grow(area));
        // The measure below can wrap a little differently from the area's own text (padding,
        // CSS applied late), which cut the last lines off: the real text's height also counts.
        area.skinProperty().addListener((o, a, b) -> Platform.runLater(() -> Ui.innerText(area, () -> grow(area))));
        area.focusedProperty().addListener((o, was, is) -> { if (is) activeArea = area; });
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> keys(area, e));
        Ui.logicalArrows(area);
        Links.install(area);
        if (scroll != null) Ui.followCaret(area, scroll);
        return area;
    }

    private final javafx.scene.text.Text measure = new javafx.scene.text.Text();

    private void grow(TextArea area) {
        double min = area.getUserData() instanceof Double d ? d : 0;
        measure.setFont(area.getFont());
        measure.setLineSpacing(SPACING);
        measure.setWrappingWidth(Math.max(1, area.getWidth() - 6));
        String t = area.getText() == null || area.getText().isEmpty() ? "X" : area.getText();
        measure.setText(t.endsWith("\n") ? t + "X" : t);
        double h = Math.max(min, Math.ceil(measure.getLayoutBounds().getHeight()) + 6);
        javafx.scene.text.Text inner = Ui.innerText(area, () -> grow(area));
        if (inner != null) h = Math.max(h, Math.ceil(inner.getLayoutBounds().getHeight()) + 12);
        if (Math.abs(h - area.getPrefHeight()) < 0.5) return;
        area.setMinHeight(h);
        area.setPrefHeight(h);
        area.setMaxHeight(h);
    }

    private static final KeyCombination PASTE = KeyCombination.keyCombination("Shortcut+V");
    private static final KeyCombination PASTE_INSERT = KeyCombination.keyCombination("Shift+Insert");

    private void keys(TextArea area, KeyEvent e) {
        if (locked) return;
        int index = getChildren().indexOf(area);
        if (e.isShortcutDown()) {
            if (e.getCode() == KeyCode.B) {
                e.consume();
                wrapSelection(area, "**", "**");
                return;
            } else if (e.getCode() == KeyCode.I) {
                e.consume();
                wrapSelection(area, "*", "*");
                return;
            } else if (e.getCode() == KeyCode.U) {
                e.consume();
                wrapSelection(area, "<u>", "</u>");
                return;
            } else if (e.getCode() == KeyCode.X && e.isShiftDown()) {
                e.consume();
                wrapSelection(area, "~~", "~~");
                return;
            }
        }
        if (PASTE.match(e) || PASTE_INSERT.match(e)) {
            if (pasteImage(area)) e.consume();
        } else if (e.getCode() == KeyCode.BACK_SPACE && area.getCaretPosition() == 0 && area.getSelection().getLength() == 0
                && index > 0 && getChildren().get(index - 1) instanceof Picture p) {
            e.consume();
            remove(p);
        } else if (e.getCode() == KeyCode.DELETE && area.getCaretPosition() == area.getLength()
                && area.getSelection().getLength() == 0
                && index + 1 < getChildren().size() && getChildren().get(index + 1) instanceof Picture p) {
            e.consume();
            remove(p);
        } else if (e.getCode() == KeyCode.SPACE && area.getSelection().getLength() == 0) {
            int caret = area.getCaretPosition();
            String full = area.getText();
            int lineStart = full.lastIndexOf('\n', caret - 1) + 1;
            String curLine = full.substring(lineStart, caret);
            if ("*".equals(curLine) || "-".equals(curLine)) {
                e.consume();
                area.replaceText(lineStart, caret, "• ");
                return;
            }
            if (curLine.matches("^\\d+$")) {
                e.consume();
                area.replaceText(lineStart, caret, curLine + ". ");
                return;
            }
        } else if (e.getCode() == KeyCode.ENTER && !e.isShiftDown() && area.getSelection().getLength() == 0) {
            int caret = area.getCaretPosition();
            String full = area.getText();
            int lineStart = full.lastIndexOf('\n', caret - 1) + 1;
            String curLine = full.substring(lineStart, caret);
            if (curLine.matches("^(\\s*)(?:•|[*–-])\\s*$")) {
                e.consume();
                area.deleteText(lineStart, caret);
                return;
            }
            Matcher bulletMatcher = Pattern.compile("^(\\s*)(?:•|[*–-])\\s+(.+)").matcher(curLine);
            if (bulletMatcher.matches()) {
                e.consume();
                String indent = bulletMatcher.group(1);
                area.insertText(caret, "\n" + indent + "• ");
                return;
            }
            if (curLine.matches("^(\\s*)(\\d+)[.)]\\s*$")) {
                e.consume();
                area.deleteText(lineStart, caret);
                return;
            }
            Matcher numMatcher = Pattern.compile("^(\\s*)(\\d+)([.)])\\s+(.+)").matcher(curLine);
            if (numMatcher.matches()) {
                e.consume();
                String indent = numMatcher.group(1);
                long nextNum = Long.parseLong(numMatcher.group(2)) + 1;
                String sep = numMatcher.group(3) + " ";
                area.insertText(caret, "\n" + indent + nextNum + sep);
                return;
            }
        } else if (e.getCode() == KeyCode.UP && !area.getText(0, area.getCaretPosition()).contains("\n") && index > 1) {
            e.consume();
            TextArea prev = (TextArea) getChildren().get(index - 2);
            prev.requestFocus();
            prev.end();
        } else if (e.getCode() == KeyCode.DOWN && !area.getText(area.getCaretPosition(), area.getLength()).contains("\n")
                && index + 2 < getChildren().size()) {
            e.consume();
            TextArea next = (TextArea) getChildren().get(index + 2);
            next.requestFocus();
            next.home();
        }
    }

    /** A picture goes, and the text either side of it joins up again. */
    private void remove(Picture p) {
        int i = getChildren().indexOf(p);
        if (i < 1 || i + 1 >= getChildren().size()) return;
        TextArea before = (TextArea) getChildren().get(i - 1);
        TextArea after = (TextArea) getChildren().get(i + 1);
        int caret = before.getLength();
        String joined = before.getText() + (before.getText().isEmpty() || after.getText().isEmpty() ? "" : "\n") + after.getText();
        getChildren().remove(i, i + 2);
        before.setText(joined);
        refreshPrompts();
        before.requestFocus();
        before.positionCaret(caret);
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
    private boolean pasteImage(TextArea area) {
        Clipboard cb = Clipboard.getSystemClipboard();
        File file = cb.hasFiles() ? cb.getFiles().stream().filter(RichBody::isPicture).findFirst().orElse(null) : null;
        Image image = file == null && cb.hasImage() ? cb.getImage() : null;
        if (file == null && image == null) return false;
        int caret = area.getCaretPosition();
        if (area.getSelection().getLength() > 0) {
            caret = area.getSelection().getStart();
            area.deleteText(area.getSelection());
        }
        insert(area, caret, file, image);
        return true;
    }

    private void dropped(DragEvent e) {
        List<File> files = new ArrayList<>();
        if (e.getDragboard().hasFiles()) for (File f : e.getDragboard().getFiles()) if (isPicture(f)) files.add(f);
        if (files.isEmpty()) return;
        TextArea target = null;
        for (Node n : getChildren()) if (n instanceof TextArea t && t.isFocused()) target = t;
        if (target == null) target = (TextArea) getChildren().get(getChildren().size() - 1);
        int caret = target.isFocused() ? target.getCaretPosition() : target.getLength();
        for (File f : files) insert(target, caret, f, null);
        e.setDropCompleted(true);
        e.consume();
    }

    /** Writes the picture into the note's folder, then splits {@code area} at {@code caret} around it. */
    private void insert(TextArea area, int caret, File file, Image image) {
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
                String before = area.getText(0, cut), after = area.getText(cut, area.getLength());
                // A line break right at the cut belongs to the picture's own line.
                if (before.endsWith("\n")) before = before.substring(0, before.length() - 1);
                if (after.startsWith("\n")) after = after.substring(1);
                area.setText(before);
                TextArea rest = text(after);
                getChildren().add(at + 1, new Picture(name));
                getChildren().add(at + 2, rest);
                refreshPrompts();
                onChange.run();
                Platform.runLater(() -> {
                    rest.requestFocus();
                    rest.home();
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

    private TextArea activeArea;

    TextArea getActiveArea() {
        if (activeArea != null && activeArea.getScene() != null) return activeArea;
        for (Node n : getChildren()) {
            if (n instanceof TextArea t && t.isFocused()) return t;
        }
        TextArea last = null;
        for (Node n : getChildren()) if (n instanceof TextArea t) last = t;
        return last;
    }

    void wrapSelection(String before, String after) {
        TextArea a = getActiveArea();
        if (a != null) wrapSelection(a, before, after);
    }

    static void wrapSelection(TextArea area, String before, String after) {
        javafx.scene.control.IndexRange sel = area.getSelection();
        int start = sel.getStart(), end = sel.getEnd();
        String text = area.getText();
        if (start != end) {
            String selected = text.substring(start, end);
            if (selected.startsWith(before) && selected.endsWith(after) && selected.length() >= before.length() + after.length()) {
                String unwrapped = selected.substring(before.length(), selected.length() - after.length());
                area.replaceText(start, end, unwrapped);
                area.selectRange(start, start + unwrapped.length());
            } else {
                area.replaceText(start, end, before + selected + after);
                area.selectRange(start, start + before.length() + selected.length() + after.length());
            }
        } else {
            area.insertText(start, before + after);
            area.positionCaret(start + before.length());
        }
        area.requestFocus();
    }

    void prefixLines(String prefix) {
        TextArea area = getActiveArea();
        if (area == null) return;
        javafx.scene.control.IndexRange sel = area.getSelection();
        int start = sel.getStart(), end = sel.getEnd();
        String text = area.getText();
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd == -1) lineEnd = text.length();
        String chunk = text.substring(lineStart, lineEnd);
        String[] lines = chunk.split("\n", -1);
        boolean allPrefixed = true;
        for (String l : lines) {
            if (!l.startsWith(prefix)) { allPrefixed = false; break; }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String l = lines[i];
            if (allPrefixed && !prefix.isEmpty()) {
                sb.append(l.substring(prefix.length()));
            } else {
                String cleaned = l.replaceFirst("^(?:•\\s*|\\d+[.)]\\s*|- \\[[ x]\\]\\s*|#+\\s*|> )", "");
                sb.append(prefix).append(cleaned);
            }
        }
        area.replaceText(lineStart, lineEnd, sb.toString());
        area.selectRange(lineStart, lineStart + sb.length());
        area.requestFocus();
    }

    void setLeading(double spacing) {
        for (Node n : getChildren()) {
            if (n instanceof TextArea t) {
                t.setStyle((t.getStyle() == null ? "" : t.getStyle()) + " -fx-line-spacing: " + spacing + "px;");
            }
        }
    }

    void setKerning(String kerning) {
        for (Node n : getChildren()) {
            if (n instanceof TextArea t) {
                t.setStyle((t.getStyle() == null ? "" : t.getStyle()) + " -fx-letter-spacing: " + kerning + ";");
            }
        }
    }

    void setFontColor(String color) {
        for (Node n : getChildren()) {
            if (n instanceof TextArea t) {
                t.setStyle((t.getStyle() == null ? "" : t.getStyle()) + " -fx-text-fill: " + color + ";");
            }
        }
    }
}
