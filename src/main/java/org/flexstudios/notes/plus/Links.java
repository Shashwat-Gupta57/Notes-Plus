package org.flexstudios.notes.plus;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.skin.TextInputControlSkin;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.text.HitInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links and IP addresses in any note, terminal style: hold Ctrl and they are underlined;
 * Ctrl+click a link to open it in the browser, Ctrl+click an IP address to copy it.
 *
 * Text fields and areas join with {@link #install}; the mind map asks {@link #find} itself.
 * The underlines are drawn on one overlay over the window, only while Ctrl is held, so typing
 * costs nothing.
 */
final class Links {

    private Links() {}

    /** True while a Ctrl key is held. */
    static final BooleanProperty ctrl = new SimpleBooleanProperty(false);

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'`]+");
    private static final Pattern IP = Pattern.compile(
            "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?::\\d{1,5})?(?![\\d.]\\d)");

    record Match(int start, int end, String text, boolean url) {}

    /** Every link and IP address in {@code text}, in order. */
    static List<Match> find(String text) {
        List<Match> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            int end = m.end();
            // Sentence punctuation after a link isn't part of it.
            while (end > m.start() && ".,;:!?)]}".indexOf(text.charAt(end - 1)) >= 0) end--;
            out.add(new Match(m.start(), end, text.substring(m.start(), end), true));
        }
        Matcher ip = IP.matcher(text);
        while (ip.find()) {
            boolean inLink = false;
            for (Match u : out) if (ip.start() >= u.start() && ip.start() < u.end()) inLink = true;
            if (!inLink) out.add(new Match(ip.start(), ip.end(), ip.group(), false));
        }
        out.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return out;
    }

    /** Opens a link in the browser, or copies an IP address. */
    static void activate(Match m) {
        if (m.url()) {
            String target = m.text().toLowerCase().startsWith("www.") ? "https://" + m.text() : m.text();
            NotesApp.openInBrowser(target);
        } else {
            ClipboardContent c = new ClipboardContent();
            c.putString(m.text());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            Ui.toast("Copied " + m.text());
        }
    }

    // ================================================================ text inputs

    private static final Set<TextInputControl> CONTROLS = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static Pane overlay;

    /** Makes a text field or area's links and IPs Ctrl+clickable and underlined under Ctrl. */
    static void install(TextInputControl control) {
        CONTROLS.add(control);
        control.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!e.isShortcutDown()) return;
            Match m = at(control, e.getX(), e.getY());
            if (m == null) return;
            e.consume();
            activate(m);
        });
        control.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            Match m = e.isShortcutDown() ? at(control, e.getX(), e.getY()) : null;
            control.setCursor(m != null ? Cursor.HAND : null);
        });
        control.textProperty().addListener((o, a, b) -> {
            if (ctrl.get()) redraw();
        });
    }

    /** The link or IP under a point of {@code control}, if any. */
    private static Match at(TextInputControl control, double x, double y) {
        if (!(control.getSkin() instanceof TextInputControlSkin<?> skin)) return null;
        HitInfo hit = hitInfo(skin, x, y);
        if (hit == null) return null;
        int index = hit.getCharIndex();
        for (Match m : find(control.getText())) {
            if (index >= m.start() && index < m.end()) {
                // The hit must be on the text itself, not in the empty space after a line.
                Rectangle2D c = skin.getCharacterBounds(index);
                if (c != null && x >= c.getMinX() - 2 && x <= c.getMaxX() + 2) return m;
            }
        }
        return null;
    }

    private static HitInfo hitInfo(TextInputControlSkin<?> skin, double x, double y) {
        if (skin instanceof javafx.scene.control.skin.TextAreaSkin a) return a.getIndex(x, y);
        if (skin instanceof javafx.scene.control.skin.TextFieldSkin f) return f.getIndex(x, y);
        return null;
    }

    /** Tracks Ctrl for the whole window and owns the underline overlay. */
    static void attach(Scene scene, Pane layer) {
        overlay = layer;
        overlay.setMouseTransparent(true);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.CONTROL || e.getCode() == KeyCode.COMMAND) ctrl.set(true);
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.CONTROL || e.getCode() == KeyCode.COMMAND) ctrl.set(false);
        });
        scene.addEventFilter(MouseEvent.ANY, e -> {
            if (e.isShortcutDown() != ctrl.get()) ctrl.set(e.isShortcutDown());
        });
        scene.addEventFilter(javafx.scene.input.ScrollEvent.ANY, e -> {
            if (ctrl.get()) javafx.application.Platform.runLater(Links::redraw);
        });
        scene.windowProperty().addListener((o, a, w) -> {
            if (w != null) w.focusedProperty().addListener((o2, was, is) -> {
                if (!is) ctrl.set(false);
            });
        });
        ctrl.addListener((o, a, b) -> redraw());
    }

    /** Underlines every visible link and IP while Ctrl is held. */
    static void redraw() {
        if (overlay == null) return;
        overlay.getChildren().clear();
        if (!ctrl.get()) return;
        for (TextInputControl c : CONTROLS) {
            if (c.getScene() == null || !c.isVisible() || !(c.getSkin() instanceof TextInputControlSkin<?> skin)) continue;
            Bounds visible = visibleArea(c);
            for (Match m : find(c.getText())) underline(c, skin, m, visible);
        }
        MindMapCanvas.redrawAll();
    }

    /** Where the control can be seen: its own bounds, cut to the page it scrolls in. */
    private static Bounds visibleArea(Node c) {
        Bounds own = c.localToScene(c.getLayoutBounds());
        for (Node n = c.getParent(); n != null; n = n.getParent()) {
            if (n instanceof javafx.scene.control.ScrollPane sp) {
                Bounds view = sp.localToScene(sp.getLayoutBounds());
                double minY = Math.max(own.getMinY(), view.getMinY()), maxY = Math.min(own.getMaxY(), view.getMaxY());
                return new javafx.geometry.BoundingBox(own.getMinX(), minY, own.getWidth(), Math.max(0, maxY - minY));
            }
        }
        return own;
    }

    /** One line under each wrapped piece of the match. */
    private static void underline(TextInputControl c, TextInputControlSkin<?> skin, Match m, Bounds visible) {
        Rectangle2D first = null, last = null;
        for (int i = m.start(); i < m.end(); i++) {
            Rectangle2D r = skin.getCharacterBounds(i);
            if (r == null) continue;
            if (first != null && Math.abs(r.getMaxY() - last.getMaxY()) > 2) {
                segment(c, first, last, visible);
                first = null;
            }
            if (first == null) first = r;
            last = r;
        }
        if (first != null) segment(c, first, last, visible);
    }

    private static void segment(Node c, Rectangle2D from, Rectangle2D to, Bounds visible) {
        Point2D a = overlay.sceneToLocal(c.localToScene(from.getMinX(), to.getMaxY() - 1));
        Point2D b = overlay.sceneToLocal(c.localToScene(to.getMaxX(), to.getMaxY() - 1));
        Point2D sa = c.localToScene(from.getMinX(), to.getMaxY() - 1);
        if (sa.getY() < visible.getMinY() + 4 || sa.getY() > visible.getMaxY() + 2) return;
        Line line = new Line(a.getX(), a.getY(), b.getX(), b.getY());
        line.setStrokeWidth(1.5);
        line.setStyle("-fx-stroke: " + Theme.ref("accent_blue") + ";");
        overlay.getChildren().add(line);
    }
}
