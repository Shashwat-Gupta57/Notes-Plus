package org.flexstudios.notes.plus;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.skin.TextInputControlSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * Typing "[[" in a note offers the titles of other notes, under the caret: ↑↓ pick, Enter or Tab
 * (or a click) writes "[[Title]]", Esc closes. It narrows as more is typed and goes away at "]]",
 * a new line, or no match. Works in the formatted words ({@link RichArea}) and in plain fields
 * (list items).
 */
final class LinkSuggest {

    private static final int MAX = 8;

    /** What a place to type gives the list: its words up to the caret, where the caret is on screen, and a way to write. */
    private interface Place {
        String beforeCaret();
        int caret();
        Point2D caretOnScreen();
        void replace(int from, int to, String text);
        String after(int at, int length);
        Node node();
    }

    private final Place place;
    private final Supplier<List<NoteEntity>> notes;
    private final Popup popup = new Popup();
    private final VBox list = new VBox();
    private final List<String> shown = new ArrayList<>();
    private int selected, from;

    private LinkSuggest(Place place, Supplier<List<NoteEntity>> notes) {
        this.place = place;
        this.notes = notes;
        list.getStyleClass().add("link-pop");
        popup.getContent().add(list);
        popup.setAutoHide(true);
        place.node().addEventFilter(KeyEvent.KEY_PRESSED, this::keys);
        place.node().focusedProperty().addListener((o, a, b) -> {
            if (!b) popup.hide();
        });
    }

    static void attach(RichArea area, Supplier<List<NoteEntity>> notes) {
        LinkSuggest s = new LinkSuggest(new Place() {
            public String beforeCaret() {
                return area.getParagraph(area.getCurrentParagraph()).getText().substring(0, area.getCaretColumn());
            }
            public int caret() { return area.getCaretPosition(); }
            public Point2D caretOnScreen() {
                return area.getCaretBounds().map(b -> new Point2D(b.getMinX(), b.getMaxY())).orElse(null);
            }
            public void replace(int f, int t, String text) { area.replaceText(f, t, text); }
            public String after(int at, int length) {
                return at + length <= area.getLength() ? area.getText(at, at + length) : "";
            }
            public Node node() { return area; }
        }, notes);
        area.textProperty().addListener((o, a, b) -> s.changed());
        area.caretPositionProperty().addListener((o, a, b) -> {
            if (s.popup.isShowing()) s.changed();
        });
    }

    static void attach(TextInputControl field, Supplier<List<NoteEntity>> notes) {
        LinkSuggest s = new LinkSuggest(new Place() {
            public String beforeCaret() {
                String t = field.getText() == null ? "" : field.getText();
                int c = Math.min(field.getCaretPosition(), t.length());
                return t.substring(t.lastIndexOf('\n', Math.max(0, c - 1)) + 1, c);
            }
            public int caret() { return field.getCaretPosition(); }
            public Point2D caretOnScreen() {
                if (!(field.getSkin() instanceof TextInputControlSkin<?> skin)) return null;
                int c = Math.max(0, field.getCaretPosition() - 1);
                Rectangle2D r = skin.getCharacterBounds(c);
                if (r == null) return null;
                return field.localToScreen(r.getMinX(), r.getMaxY());
            }
            public void replace(int f, int t, String text) { field.replaceText(f, t, text); }
            public String after(int at, int length) {
                String t = field.getText() == null ? "" : field.getText();
                return at + length <= t.length() ? t.substring(at, at + length) : "";
            }
            public Node node() { return field; }
        }, notes);
        field.textProperty().addListener((o, a, b) -> s.changed());
    }

    private void changed() {
        if (!place.node().isFocused()) {
            popup.hide();
            return;
        }
        Matcher m = NoteLinks.TYPING.matcher(place.beforeCaret());
        if (!m.find()) {
            popup.hide();
            return;
        }
        List<String> titles = NoteLinks.suggestions(m.group(1), notes.get(), MAX, -1);
        if (titles.isEmpty()) {
            popup.hide();
            return;
        }
        from = place.caret() - m.group(1).length();
        shown.clear();
        shown.addAll(titles);
        selected = 0;
        list.getChildren().clear();
        for (int i = 0; i < titles.size(); i++) {
            int index = i;
            Label row = new Label(titles.get(i), Ui.icon(Icons.NOTE_TEXT, 16, "accent_blue"));
            row.getStyleClass().add("link-pop-row");
            row.setMaxWidth(Double.MAX_VALUE);
            row.setOnMouseClicked(e -> choose(index));
            list.getChildren().add(row);
        }
        paint();
        Point2D at = place.caretOnScreen();
        if (at == null) return;
        if (popup.isShowing()) {
            popup.setX(at.getX() - 8);
            popup.setY(at.getY() + 4);
        } else {
            popup.show(place.node(), at.getX() - 8, at.getY() + 4);
        }
    }

    private void paint() {
        for (int i = 0; i < list.getChildren().size(); i++) {
            Node row = list.getChildren().get(i);
            if (i == selected) {
                if (!row.getStyleClass().contains("selected")) row.getStyleClass().add("selected");
            } else row.getStyleClass().remove("selected");
        }
    }

    private void keys(KeyEvent e) {
        if (!popup.isShowing()) return;
        if (e.getCode() == KeyCode.DOWN) {
            selected = Math.min(shown.size() - 1, selected + 1);
            paint();
            e.consume();
        } else if (e.getCode() == KeyCode.UP) {
            selected = Math.max(0, selected - 1);
            paint();
            e.consume();
        } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.TAB) {
            choose(selected);
            e.consume();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            popup.hide();
            e.consume();
        }
    }

    /** Writes "Title]]" over what was typed after "[[" (and past a "]]" already there). */
    private void choose(int index) {
        if (index < 0 || index >= shown.size()) return;
        String title = shown.get(index);
        int caret = place.caret();
        int end = place.after(caret, 2).equals("]]") ? caret + 2 : caret;
        popup.hide();
        place.replace(from, end, title + "]]");
    }
}
