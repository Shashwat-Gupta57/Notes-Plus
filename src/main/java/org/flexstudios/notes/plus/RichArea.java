package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.TextFlow;
import org.fxmisc.richtext.StyledTextArea;
import org.fxmisc.richtext.TextExt;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words as they look, the way WordPad shows them: bold is bold, a heading is big, a list has
 * its bullets, and no mark is ever on screen. What is kept is Markdown ({@link Markdown}), so the
 * phone and the web read the same note.
 *
 * Each paragraph has a kind ({@link Markdown.Block}: heading, quote, bullet, number, task) and
 * each run of characters a {@link Markdown.Style}. Bullets, numbers, tick boxes and the quote
 * bar are drawn beside the paragraph, never typed. Word's habits apply: Enter continues a list
 * (and ends it on an empty item), Backspace at the start of a heading or list item makes it a
 * plain paragraph, and typing "# ", "- ", "1. ", "[] " or "&gt; " at the start of a line turns it
 * into one; **bold**, *italic*, ~~strike~~ and `code` typed with their marks become formatted.
 *
 * The area grows with its text (the note's page scrolls, not the area), and wheel scrolling
 * is handed to that page.
 */
final class RichArea extends StyledTextArea<Markdown.Block, Markdown.Style> implements FindBar.Target {

    private static final double BASE_SIZE = 17;
    /** Space between a paragraph's lines, from the toolbar's "Line spacing" (a view choice). */
    private static double lineSpacing = readSpacing();
    private static double kerning = readKerning();

    private final ScrollPane page;
    private double zoom = 1;
    private double minHeight;
    private Runnable onChange = () -> {};
    private Runnable onCaret = () -> {};
    private int paragraphs = 1;
    private boolean typing;

    RichArea(ScrollPane page) {
        super(Markdown.Block.NORMAL, RichArea::paragraphStyle, Markdown.Style.PLAIN, RichArea::textStyle);
        this.page = page;
        getStyleClass().add("rich-area");
        setWrapText(true);
        setUseInitialStyleForInsertion(false);
        setParagraphGraphicFactory(this::marker);
        setPadding(new Insets(0, 0, 0, 0));
        applySize();

        totalHeightEstimateProperty().addListener((o, a, b) -> fit());
        plainTextChanges().subscribe(c -> {
            onChange.run();
            if (getParagraphs().size() != paragraphs) {
                paragraphs = getParagraphs().size();
                refreshMarkers(); // Numbers below an inserted or removed paragraph move on.
            }
        });
        caretPositionProperty().addListener((o, a, b) -> {
            if (!typing) setTextInsertionStyle(null); // A style picked with nothing selected lasts until the caret moves.
            onCaret.run();
            followCaret();
        });
        selectionProperty().addListener((o, a, b) -> onCaret.run());

        addEventFilter(KeyEvent.KEY_PRESSED, this::keys);
        addEventFilter(KeyEvent.KEY_TYPED, this::typed);
        addEventFilter(ScrollEvent.SCROLL, this::scrollPage);
        addEventFilter(MouseEvent.MOUSE_CLICKED, this::clicked);
        setContextMenu(menu());
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    /** Told when the caret or the selection moves (the toolbar shows what's on there). */
    void setOnCaret(Runnable r) {
        onCaret = r;
    }

    /** At least this tall (the last block of a note is its writing room). */
    void setMinRoom(double h) {
        minHeight = h;
        fit();
    }

    void setPrompt(String prompt) {
        if (prompt == null) {
            setPlaceholder(null);
            return;
        }
        Label l = new Label(prompt);
        l.getStyleClass().add("rich-prompt");
        l.setStyle("-fx-font-size: " + BASE_SIZE * zoom + "px;");
        setPlaceholder(l, Pos.TOP_LEFT);
    }

    // ================================================================ content

    /** Shows Markdown {@code text} formatted. Undo starts afresh. */
    void setMarkdown(String text) {
        List<Markdown.Para> paras = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        String[] lines = Markdown.repair(text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Markdown.Para p = Markdown.parse(lines[i]);
            paras.add(p);
            if (i > 0) plain.append('\n');
            plain.append(p.text());
        }
        replaceText(plain.toString());
        apply(0, 0, paras);
        paragraphs = getParagraphs().size();
        refreshMarkers();
        getUndoManager().forgetHistory();
        moveTo(0);
        requestFollowCaret();
    }

    /** Paragraph kinds and styles of {@code paras}, laid over the text from paragraph {@code first}. */
    private void apply(int first, int firstColumn, List<Markdown.Para> paras) {
        int offset = getAbsolutePosition(first, 0) + firstColumn;
        for (int i = 0; i < paras.size(); i++) {
            Markdown.Para p = paras.get(i);
            if (i > 0 || firstColumn == 0) setParagraphStyle(first + i, p.block());
            String t = p.text();
            if (!t.isEmpty()) {
                StyleSpansBuilder<Markdown.Style> spans = new StyleSpansBuilder<>();
                for (Markdown.Run r : p.runs()) spans.add(r.style(), r.text().length());
                setStyleSpans(offset, spans.create());
            }
            offset += t.length() + 1;
        }
    }

    /** The whole text as Markdown. */
    String markdown() {
        return markdown(0, getLength());
    }

    /** The text from {@code start} to {@code end} as Markdown (paragraph kinds included). */
    String markdown(int start, int end) {
        StringBuilder out = new StringBuilder();
        TwoDimensional.Position a = offsetToPosition(start, TwoDimensional.Bias.Forward);
        TwoDimensional.Position b = offsetToPosition(end, TwoDimensional.Bias.Backward);
        int number = 0;
        for (int par = 0; par <= b.getMajor() && par < getParagraphs().size(); par++) {
            Markdown.Block block = getParagraph(par).getParagraphStyle();
            number = block == Markdown.Block.NUMBER ? number + 1 : 0;
            if (par < a.getMajor()) continue;
            int from = par == a.getMajor() ? a.getMinor() : 0;
            int to = par == b.getMajor() ? b.getMinor() : getParagraph(par).length();
            if (par > a.getMajor()) out.append('\n');
            List<Markdown.Run> runs = new ArrayList<>();
            if (to > from) {
                String text = getText(getAbsolutePosition(par, from), getAbsolutePosition(par, to));
                int at = 0;
                for (StyleSpan<Markdown.Style> span : getStyleSpans(par, from, to)) {
                    int len = Math.min(span.getLength(), text.length() - at);
                    if (len > 0) runs.add(new Markdown.Run(text.substring(at, at + len), span.getStyle()));
                    at += span.getLength();
                }
            }
            // A part of one paragraph is just its words; whole paragraphs keep their kind.
            boolean whole = from == 0 && (to == getParagraph(par).length() || par < b.getMajor());
            Markdown.Block kind = whole || a.getMajor() != b.getMajor() ? block : Markdown.Block.NORMAL;
            out.append(Markdown.write(new Markdown.Para(kind, runs), Math.max(1, number)));
        }
        return out.toString();
    }

    /** Puts Markdown {@code text} in place of the selection, formatted. */
    void insertMarkdown(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Markdown.Para> paras = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            Markdown.Para p = lines.length == 1 ? new Markdown.Para(Markdown.Block.NORMAL, Markdown.inline(lines[i])) : Markdown.parse(lines[i]);
            paras.add(p);
            if (i > 0) plain.append('\n');
            plain.append(p.text());
        }
        int start = getSelection().getStart();
        TwoDimensional.Position at = offsetToPosition(start, TwoDimensional.Bias.Forward);
        replaceSelection(plain.toString());
        // The first line joins the paragraph it lands in; it takes its kind only at a line's start.
        if (paras.get(0).block() != Markdown.Block.NORMAL && at.getMinor() == 0) setParagraphStyle(at.getMajor(), paras.get(0).block());
        apply(at.getMajor(), at.getMinor(), paras);
        refreshMarkers();
    }

    // ============================================================== formatting

    /** What the caret is on (or what typing would make), for the toolbar. */
    Markdown.Style currentStyle() {
        if (getTextInsertionStyle() != null) return getTextInsertionStyle();
        int sel = getSelection().getStart();
        if (getSelection().getLength() > 0) return getStyleOfChar(sel);
        return getCaretPosition() > 0 ? getStyleOfChar(getCaretPosition() - 1) : getStyleAtPosition(getCaretPosition());
    }

    Markdown.Block currentBlock() {
        return getParagraph(getCurrentParagraph()).getParagraphStyle();
    }

    /**
     * Turns a style on or off: over the selection (on unless all of it has it already), or, with
     * nothing selected, for what is typed next.
     */
    void toggle(java.util.function.Predicate<Markdown.Style> has, java.util.function.BiFunction<Markdown.Style, Boolean, Markdown.Style> set) {
        if (getSelection().getLength() == 0) {
            Markdown.Style now = currentStyle();
            typingStyle(set.apply(now, !has.test(now)));
            return;
        }
        int start = getSelection().getStart(), end = getSelection().getEnd();
        boolean all = true;
        for (StyleSpan<Markdown.Style> s : getStyleSpans(start, end)) {
            if (s.getLength() > 0 && !has.test(s.getStyle())) {
                all = false;
                break;
            }
        }
        boolean on = !all;
        restyle(start, end, s -> set.apply(s, on));
    }

    /** A colour ("#RRGGBB", or null for the default) over the selection or for what's typed next. */
    void color(String hex) {
        if (getSelection().getLength() == 0) {
            typingStyle(currentStyle().color(hex));
            return;
        }
        restyle(getSelection().getStart(), getSelection().getEnd(), s -> s.color(hex));
    }

    private void typingStyle(Markdown.Style s) {
        typing = true;
        setTextInsertionStyle(s);
        typing = false;
        onCaret.run();
    }

    private void restyle(int start, int end, UnaryOperator<Markdown.Style> change) {
        StyleSpans<Markdown.Style> spans = getStyleSpans(start, end).mapStyles(change);
        setStyleSpans(start, spans);
        onChange.run();
        onCaret.run();
    }

    /** The selected paragraphs (or the caret's) become {@code block}, or plain again if they all are already. */
    void block(Markdown.Block block) {
        int first = offsetToPosition(getSelection().getStart(), TwoDimensional.Bias.Forward).getMajor();
        int last = offsetToPosition(getSelection().getEnd(), TwoDimensional.Bias.Backward).getMajor();
        boolean all = true;
        for (int p = first; p <= last; p++) if (getParagraph(p).getParagraphStyle() != block) all = false;
        for (int p = first; p <= last; p++) setParagraphStyle(p, all ? Markdown.Block.NORMAL : block);
        refreshMarkers();
        onChange.run();
        onCaret.run();
    }

    // ================================================================ keyboard

    private void keys(KeyEvent e) {
        if (!isEditable()) return;
        boolean ctrl = e.isShortcutDown();
        KeyCode k = e.getCode();
        if (ctrl && !e.isAltDown() && k == KeyCode.B) {
            e.consume();
            toggle(Markdown.Style::bold, Markdown.Style::bold);
        } else if (ctrl && !e.isAltDown() && k == KeyCode.I) {
            e.consume();
            toggle(Markdown.Style::italic, Markdown.Style::italic);
        } else if (ctrl && !e.isAltDown() && k == KeyCode.U) {
            e.consume();
            toggle(Markdown.Style::underline, Markdown.Style::underline);
        } else if (ctrl && e.isShiftDown() && k == KeyCode.X) {
            e.consume();
            toggle(Markdown.Style::strike, Markdown.Style::strike);
        } else if (ctrl && e.isShiftDown() && k == KeyCode.L) {
            e.consume();
            block(Markdown.Block.BULLET);
        } else if (ctrl && e.isAltDown() && (k == KeyCode.DIGIT1 || k == KeyCode.DIGIT2 || k == KeyCode.DIGIT3)) {
            e.consume();
            block(k == KeyCode.DIGIT1 ? Markdown.Block.H1 : k == KeyCode.DIGIT2 ? Markdown.Block.H2 : Markdown.Block.H3);
        } else if (ctrl && e.isAltDown() && k == KeyCode.DIGIT0) {
            e.consume();
            block(Markdown.Block.NORMAL);
        } else if (k == KeyCode.ENTER && !ctrl && getSelection().getLength() == 0) {
            enter(e);
        } else if (k == KeyCode.BACK_SPACE && !ctrl && getSelection().getLength() == 0 && getCaretColumn() == 0
                && currentBlock() != Markdown.Block.NORMAL) {
            // Word's way: the first Backspace takes the bullet (or heading) off, the next joins lines.
            e.consume();
            setParagraphStyle(getCurrentParagraph(), Markdown.Block.NORMAL);
            refreshMarkers();
            onChange.run();
            onCaret.run();
        }
    }

    /** Enter: a list goes on (and ends on an empty item); after a heading comes a plain paragraph. */
    private void enter(KeyEvent e) {
        Markdown.Block block = currentBlock();
        int par = getCurrentParagraph();
        if (block.isList() || block == Markdown.Block.QUOTE) {
            if (getParagraph(par).length() == 0) {
                e.consume();
                setParagraphStyle(par, Markdown.Block.NORMAL);
                refreshMarkers();
                onChange.run();
                return;
            }
        }
        e.consume();
        boolean atStart = getCaretColumn() == 0 && getParagraph(par).length() > 0;
        Markdown.Style keep = currentStyle();
        replaceSelection("\n");
        Markdown.Block next = switch (block) {
            case H1, H2, H3 -> atStart ? block : Markdown.Block.NORMAL;
            case TASK_DONE -> Markdown.Block.TASK;
            default -> e.isShiftDown() ? Markdown.Block.NORMAL : block;
        };
        if (atStart && block.isList()) next = block;
        if (atStart) setParagraphStyle(par, block.isList() || block == Markdown.Block.QUOTE ? block : Markdown.Block.NORMAL);
        setParagraphStyle(par + 1, next);
        if (getParagraph(par + 1).length() == 0) typingStyle(keep.code(false));
        refreshMarkers();
    }

    private static final Pattern INLINE_MARK = Pattern.compile("(\\*\\*([^*]+)\\*\\*|(?<![*\\w])\\*([^*\\s][^*]*)\\*|~~([^~]+)~~|`([^`]+)`)$");

    /** Markdown typed as Markdown: block marks at a line's start, inline marks when closed. */
    private void typed(KeyEvent e) {
        if (!isEditable() || e.getCharacter().isEmpty() || e.isShortcutDown()) return;
        String ch = e.getCharacter();
        int par = getCurrentParagraph(), col = getCaretColumn();
        String before = getParagraph(par).getText().substring(0, Math.min(col, getParagraph(par).length()));
        if (ch.equals(" ") && getSelection().getLength() == 0 && currentBlock() == Markdown.Block.NORMAL) {
            Markdown.Block b = switch (before) {
                case "#" -> Markdown.Block.H1;
                case "##" -> Markdown.Block.H2;
                case "###" -> Markdown.Block.H3;
                case "-", "*", "•" -> Markdown.Block.BULLET;
                case ">" -> Markdown.Block.QUOTE;
                case "[]", "[ ]", "- [ ]", "-[]" -> Markdown.Block.TASK;
                case "[x]", "- [x]" -> Markdown.Block.TASK_DONE;
                default -> before.matches("\\d+[.)]") ? Markdown.Block.NUMBER : null;
            };
            if (b != null) {
                e.consume();
                int start = getAbsolutePosition(par, 0);
                deleteText(start, start + before.length());
                setParagraphStyle(par, b);
                refreshMarkers();
                onChange.run();
                onCaret.run();
                return;
            }
        }
        if ((ch.equals("*") || ch.equals("~") || ch.equals("`")) && getSelection().getLength() == 0) {
            Matcher m = INLINE_MARK.matcher(before + ch);
            if (m.find()) {
                e.consume();
                String inner = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
                int lineStart = getAbsolutePosition(par, 0);
                int from = lineStart + m.start(), to = lineStart + before.length();
                Markdown.Style base = getStyleOfChar(Math.max(lineStart, to - 1));
                Markdown.Style styled = m.group(2) != null ? base.bold(true) : m.group(3) != null ? base.italic(true)
                        : m.group(4) != null ? base.strike(true) : base.code(true);
                replaceText(from, to, inner);
                setStyle(from, from + inner.length(), styled);
                moveTo(from + inner.length());
                typingStyle(base); // What follows is plain again.
                onChange.run();
            }
        }
    }

    // ============================================================ clipboard

    /** Copies Markdown: pasted into a note it comes back formatted; anywhere else it's readable text. */
    @Override
    public void copy() {
        if (getSelection().getLength() == 0) return;
        ClipboardContent c = new ClipboardContent();
        c.putString(markdown(getSelection().getStart(), getSelection().getEnd()));
        Clipboard.getSystemClipboard().setContent(c);
    }

    @Override
    public void cut() {
        if (!isEditable() || getSelection().getLength() == 0) return;
        copy();
        replaceSelection("");
    }

    /** Pasted text is read as Markdown, so formatting copied from a note (or a .md file) survives. */
    @Override
    public void paste() {
        if (!isEditable()) return;
        Clipboard cb = Clipboard.getSystemClipboard();
        if (!cb.hasString()) return;
        insertMarkdown(cb.getString());
        onChange.run();
    }

    private ContextMenu menu() {
        MenuItem cut = new MenuItem("Cut"), copy = new MenuItem("Copy"), paste = new MenuItem("Paste"), all = new MenuItem("Select all");
        MenuItem bold = new MenuItem("Bold"), italic = new MenuItem("Italic"), underline = new MenuItem("Underline");
        cut.setOnAction(e -> cut());
        copy.setOnAction(e -> copy());
        paste.setOnAction(e -> paste());
        all.setOnAction(e -> selectAll());
        bold.setOnAction(e -> toggle(Markdown.Style::bold, Markdown.Style::bold));
        italic.setOnAction(e -> toggle(Markdown.Style::italic, Markdown.Style::italic));
        underline.setOnAction(e -> toggle(Markdown.Style::underline, Markdown.Style::underline));
        ContextMenu m = new ContextMenu(cut, copy, paste, new SeparatorMenuItem(), bold, italic, underline, new SeparatorMenuItem(), all);
        m.setOnShowing(e -> {
            boolean sel = getSelection().getLength() > 0;
            cut.setDisable(!sel || !isEditable());
            copy.setDisable(!sel);
            paste.setDisable(!isEditable() || !Clipboard.getSystemClipboard().hasString());
            bold.setDisable(!isEditable());
            italic.setDisable(!isEditable());
            underline.setDisable(!isEditable());
        });
        return m;
    }

    // ================================================================= links

    private static final Pattern URL = Pattern.compile("(https?://|www\\.)[^\\s<>\"]+");

    /** Ctrl+click on a web address opens it, as in Word. */
    private void clicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || !e.isShortcutDown()) return;
        int at = hit(e.getX(), e.getY()).getInsertionIndex();
        TwoDimensional.Position p = offsetToPosition(at, TwoDimensional.Bias.Forward);
        String line = getParagraph(p.getMajor()).getText();
        Matcher m = URL.matcher(line);
        while (m.find()) {
            if (p.getMinor() >= m.start() && p.getMinor() <= m.end()) {
                String url = m.group().replaceAll("[.,;:)]+$", "");
                NotesApp.openInBrowser(url.startsWith("www.") ? "https://" + url : url);
                e.consume();
                return;
            }
        }
    }

    // ============================================================== looks

    /** How a paragraph looks: its size and weight for headings, a slant for quotes. */
    private static void paragraphStyle(TextFlow flow, Markdown.Block b) {
        String css = "-fx-line-spacing: " + lineSpacing + "px;" + (kerning != 0 ? " -fx-letter-spacing: " + kerning + "px;" : "") + switch (b) {
            case H1 -> "-fx-font-size: 1.75em; -fx-font-weight: bold; -fx-padding: 0.45em 0 0.1em 0;";
            case H2 -> "-fx-font-size: 1.4em; -fx-font-weight: bold; -fx-padding: 0.4em 0 0.1em 0;";
            case H3 -> "-fx-font-size: 1.18em; -fx-font-weight: bold; -fx-padding: 0.3em 0 0.05em 0;";
            case QUOTE -> "-fx-font-style: italic; -fx-padding: 0.15em 0 0.15em 0;";
            case TASK_DONE -> "-fx-opacity: 0.5; -fx-padding: 0.1em 0 0.1em 0;";
            case BULLET, NUMBER, TASK -> "-fx-padding: 0.1em 0 0.1em 0;";
            case NORMAL -> "-fx-padding: 0.12em 0 0.12em 0;";
        };
        flow.setStyle(css);
    }

    /** How a run looks. Only what it changes is set, so a heading's size and weight show through. */
    private static void textStyle(TextExt t, Markdown.Style s) {
        StringBuilder css = new StringBuilder();
        if (s.bold()) css.append("-fx-font-weight: bold;");
        if (s.italic()) css.append("-fx-font-style: italic;");
        if (s.underline()) css.append("-fx-underline: true;");
        if (s.strike()) css.append("-fx-strikethrough: true;");
        if (s.code()) css.append("-fx-font-family: 'Consolas', 'Cascadia Mono', monospace; -rtfx-background-color: rgba(128,128,128,0.2);");
        if (s.color() != null) css.append("-fx-fill: ").append(s.color()).append(';');
        t.setStyle(css.toString());
    }

    /** The bullet, number, tick box or quote bar beside paragraph {@code par}. */
    private Node marker(int par) {
        if (par < 0 || par >= getParagraphs().size()) return gap(0);
        Markdown.Block b = getParagraph(par).getParagraphStyle();
        double size = BASE_SIZE * zoom;
        switch (b) {
            case BULLET -> {
                // A drawn dot, centred on the first line: fonts' bullet glyphs sit high and small.
                javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(size * 0.16);
                dot.getStyleClass().add("rich-dot");
                StackPane slot = new StackPane(dot);
                slot.setAlignment(Pos.TOP_CENTER);
                slot.setMinWidth(size * 1.6);
                slot.setPrefWidth(size * 1.6);
                slot.setPadding(new Insets(size * 0.97, 0, 0, 0));
                return slot;
            }
            case NUMBER -> {
                int n = 1;
                for (int p = par - 1; p >= 0 && getParagraph(p).getParagraphStyle() == Markdown.Block.NUMBER; p--) n++;
                return mark(n + ".", size, size * 1.9, size * 0.5);
            }
            case TASK, TASK_DONE -> {
                boolean done = b == Markdown.Block.TASK_DONE;
                StackPane box = new StackPane();
                box.getStyleClass().addAll("check", "rich-check");
                if (done) {
                    box.getStyleClass().add("on");
                    box.getChildren().add(Ui.icon(Icons.CHECK, size * 0.75, "white"));
                }
                double s = size * 0.95;
                box.setMinSize(s, s);
                box.setMaxSize(s, s);
                box.setPrefSize(s, s);
                StackPane slot = new StackPane(box);
                slot.setMinWidth(size * 1.7);
                slot.setPrefWidth(size * 1.7);
                slot.setAlignment(Pos.TOP_LEFT);
                slot.setPadding(new Insets(size * 0.5, 0, 0, size * 0.25));
                slot.setStyle("-fx-cursor: hand;");
                slot.setOnMouseClicked(e -> {
                    if (!isEditable()) return;
                    setParagraphStyle(par, done ? Markdown.Block.TASK : Markdown.Block.TASK_DONE);
                    refreshMarkers();
                    onChange.run();
                    e.consume();
                });
                return slot;
            }
            case QUOTE -> {
                Region bar = new Region();
                bar.getStyleClass().add("rich-quote-bar");
                bar.setMinWidth(3);
                bar.setMaxWidth(3);
                StackPane slot = new StackPane(bar);
                slot.setPadding(new Insets(2, size * 0.6, 2, size * 0.2));
                slot.setAlignment(Pos.CENTER_LEFT);
                return slot;
            }
            default -> {
                return gap(0);
            }
        }
    }

    /** A bullet or number, on the first line's baseline ({@code top}: the paragraph's own top padding). */
    private static Node mark(String text, double fontSize, double width, double top) {
        Label l = new Label(text);
        l.getStyleClass().add("rich-marker");
        l.setStyle("-fx-font-size: " + fontSize + "px; -fx-padding: " + top + " 0 0 0;");
        l.setMinWidth(width);
        l.setPrefWidth(width);
        l.setAlignment(Pos.TOP_CENTER);
        return l;
    }

    private static Region gap(double w) {
        Region r = new Region();
        r.setMinWidth(w);
        r.setPrefWidth(w);
        r.setMaxWidth(w);
        return r;
    }

    /** Redraws every bullet and number (numbers shift when items come and go). */
    void refreshMarkers() {
        setParagraphGraphicFactory(null);
        setParagraphGraphicFactory(this::marker);
    }

    /** The view's zoom (Ctrl+= / Ctrl+-): the words, headings and bullets all scale together. */
    void zoom(double z) {
        if (Math.abs(z - zoom) < 0.001) return;
        zoom = z;
        applySize();
        refreshMarkers();
        if (getPlaceholder() != null) getPlaceholder().setStyle("-fx-font-size: " + BASE_SIZE * zoom + "px;");
    }

    private void applySize() {
        setStyle("-fx-font-size: " + BASE_SIZE * zoom + "px;");
    }

    /** Line spacing for every note (a view choice, never saved in a note). */
    static void setLineSpacing(double px) {
        lineSpacing = px;
        Prefs.put("note_line_spacing", String.valueOf(px));
    }

    static double getLineSpacing() {
        return lineSpacing;
    }

    private static double readSpacing() {
        try {
            String v = Prefs.get("note_line_spacing");
            return v == null ? 4.5 : Double.parseDouble(v);
        } catch (Exception e) {
            return 4.5;
        }
    }

    static void setKerning(double px) {
        kerning = px;
        Prefs.put("note_kerning", String.valueOf(px));
    }

    static double getKerning() {
        return kerning;
    }

    private static double readKerning() {
        try {
            String v = Prefs.get("note_kerning");
            return v == null ? 0.0 : Double.parseDouble(v);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** Re-applies paragraph looks (after the line spacing changed). */
    void restyleParagraphs() {
        for (int p = 0; p < getParagraphs().size(); p++) setParagraphStyle(p, getParagraph(p).getParagraphStyle());
    }

    // ============================================================== the page

    /** As tall as its text (or its writing room), so the page scrolls rather than the area. */
    private void fit() {
        Double estimate = totalHeightEstimateProperty().getValue();
        double h = Math.max(minHeight, Math.ceil((estimate == null ? 0 : estimate) + 4));
        if (Math.abs(h - getPrefHeight()) < 0.5) return;
        setMinHeight(h);
        setPrefHeight(h);
        setMaxHeight(h);
    }

    /** The wheel scrolls the note's page; the area itself never scrolls. */
    private void scrollPage(ScrollEvent e) {
        if (page == null || page.getContent() == null) return;
        e.consume();
        double content = page.getContent().getLayoutBounds().getHeight() - page.getViewportBounds().getHeight();
        if (content <= 0) return;
        page.setVvalue(Math.max(0, Math.min(1, page.getVvalue() - e.getDeltaY() / content)));
    }

    /** Keeps the caret in the page's view while typing or moving. */
    private void followCaret() {
        if (page == null) return;
        Platform.runLater(() -> getCaretBounds().ifPresent(this::reveal));
    }

    private void reveal(Bounds screen) {
        Node content = page.getContent();
        if (content == null) return;
        Bounds in = content.screenToLocal(screen);
        if (in == null) return;
        double viewH = page.getViewportBounds().getHeight(), total = content.getLayoutBounds().getHeight();
        if (total <= viewH) return;
        double top = page.getVvalue() * (total - viewH);
        if (in.getMaxY() + 24 > top + viewH) page.setVvalue(Math.min(1, (in.getMaxY() + 24 - viewH) / (total - viewH)));
        else if (in.getMinY() - 12 < top) page.setVvalue(Math.max(0, (in.getMinY() - 12) / (total - viewH)));
    }

    // ================================================================= find

    @Override
    public String findText() {
        return getText();
    }

    @Override
    public void showMatch(int start, int end) {
        selectRange(start, end);
        followCaret();
    }
}
