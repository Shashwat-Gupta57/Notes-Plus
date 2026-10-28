package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Ctrl+F in a note: a find row under the toolbar, "2 of 7", Enter / F3 for the next match and
 * Shift for the previous one. Finding only selects; nothing in the note changes.
 *
 * Text editors search their fields in reading order ({@link #inFields}); others (the mind map)
 * hand over their own matches.
 */
final class FindBar extends HBox {

    /** One match: where it is, and how to show it. */
    record Match(Object target, int start, int end) {}

    interface Searcher {
        List<Match> matches(String needle);

        void show(Match m);
    }

    private final TextField field = new TextField();
    private final Label count = new Label();
    private final Searcher searcher;
    private List<Match> matches = List.of();
    private int current = -1;

    FindBar(Searcher searcher) {
        this.searcher = searcher;
        field.setPromptText("Find in this note");
        field.getStyleClass().setAll("text-input", "text-field", "search-field");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.textProperty().addListener((o, a, b) -> {
            current = -1;
            refresh();
            if (!matches.isEmpty()) step(true);
        });
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.F3) {
                e.consume();
                step(!e.isShiftDown());
            }
        });
        count.getStyleClass().add("secondary");
        count.setMinWidth(70);
        getChildren().setAll(field, count,
                Ui.iconButton(Icons.ARROW_BACK, 36, 18, "text_secondary", "Previous (Shift+Enter)", () -> step(false)),
                Ui.textButton("Next", () -> step(true)),
                Ui.iconButton(Icons.CLOSE, 36, 18, "text_secondary", "Close (Esc)", this::close));
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("editor-find");
        setStyle("-fx-padding: 4 16 8 16;");
        setVisible(false);
        setManaged(false);
    }

    boolean isOpen() {
        return isVisible();
    }

    void open(String selected) {
        setVisible(true);
        setManaged(true);
        if (selected != null && !selected.isEmpty() && !selected.contains("\n")) field.setText(selected);
        else refresh();
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
    }

    void close() {
        setVisible(false);
        setManaged(false);
    }

    /** Next (or previous) match, wrapping around. */
    void step(boolean forward) {
        if (!isOpen()) {
            open(null);
            return;
        }
        refresh();
        if (matches.isEmpty()) return;
        current = current < 0 ? (forward ? 0 : matches.size() - 1)
                : Math.floorMod(current + (forward ? 1 : -1), matches.size());
        showCount();
        searcher.show(matches.get(current));
    }

    private void refresh() {
        String q = field.getText();
        matches = q == null || q.isEmpty() ? List.of() : searcher.matches(q);
        if (current >= matches.size()) current = -1;
        showCount();
    }

    private void showCount() {
        String q = field.getText();
        count.setText(q == null || q.isEmpty() ? "" : matches.isEmpty() ? "No matches"
                : (current < 0 ? matches.size() + (matches.size() == 1 ? " match" : " matches")
                : (current + 1) + " of " + matches.size()));
    }

    // ================================================================== text

    /** Every occurrence, case-insensitive, of the needle in {@code text}. */
    static List<int[]> occurrences(String text, String needle) {
        List<int[]> out = new ArrayList<>();
        if (text == null || needle.isEmpty()) return out;
        String hay = text.toLowerCase(Locale.ROOT), n = needle.toLowerCase(Locale.ROOT);
        for (int at = hay.indexOf(n); at >= 0; at = hay.indexOf(n, at + Math.max(1, n.length()))) {
            out.add(new int[]{at, at + n.length()});
        }
        return out;
    }

    /** A searcher over text fields, in the order given, selecting a match and scrolling to it. */
    /** Something with words to search that isn't a text field: a note's formatted text. */
    interface Target {
        String findText();

        /** Selects {@code start..end} and brings it into view. */
        void showMatch(int start, int end);
    }

    static Searcher inFields(Supplier<List<Node>> fields, ScrollPane scroll) {
        return new Searcher() {
            @Override
            public List<Match> matches(String needle) {
                List<Match> out = new ArrayList<>();
                for (Node f : fields.get()) {
                    String text = f instanceof TextInputControl t ? t.getText() : f instanceof Target t ? t.findText() : null;
                    for (int[] o : occurrences(text, needle)) out.add(new Match(f, o[0], o[1]));
                }
                return out;
            }

            @Override
            public void show(Match m) {
                if (m.target() instanceof Target t) {
                    t.showMatch(m.start(), m.end());
                    return;
                }
                TextInputControl f = (TextInputControl) m.target();
                f.selectRange(m.start(), m.end());
                reveal(f, m.start(), scroll);
            }
        };
    }

    /** Scrolls the page so the character at {@code index} of {@code f} is in view. */
    static void reveal(TextInputControl f, int index, ScrollPane scroll) {
        Platform.runLater(() -> {
            if (scroll == null || scroll.getContent() == null) return;
            Bounds target = f.getBoundsInLocal();
            if (f instanceof TextArea && f.getSkin() instanceof javafx.scene.control.skin.TextAreaSkin skin) {
                javafx.geometry.Rectangle2D c = skin.getCharacterBounds(Math.max(0, Math.min(f.getLength(), index)));
                if (c != null) target = new javafx.geometry.BoundingBox(c.getMinX(), c.getMinY(), 1, c.getHeight());
            }
            Node content = scroll.getContent();
            Bounds in = content.sceneToLocal(f.localToScene(target));
            if (in == null) return;
            double viewH = scroll.getViewportBounds().getHeight(), total = content.getLayoutBounds().getHeight();
            if (total <= viewH) return;
            double top = scroll.getVvalue() * (total - viewH);
            if (in.getMinY() < top + 24 || in.getMaxY() > top + viewH - 24) {
                scroll.setVvalue(Math.max(0, Math.min(1, (in.getMinY() - viewH / 3) / (total - viewH))));
            }
        });
    }

    /** Text fields (and formatted text) under {@code root}, in layout order. */
    static List<Node> fieldsUnder(Node root, Function<Node, Boolean> keep) {
        List<Node> out = new ArrayList<>();
        collect(root, keep, out);
        return out;
    }

    private static void collect(Node n, Function<Node, Boolean> keep, List<Node> out) {
        if (!n.isVisible()) return;
        if (n instanceof TextInputControl || n instanceof Target) {
            if (keep.apply(n)) out.add(n);
            return;
        }
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) collect(c, keep, out);
    }
}
