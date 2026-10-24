package org.flexstudios.notes.plus;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;

/**
 * Ctrl+= / Ctrl+- / Ctrl+0 in a note: how big its words look, like a text editor's zoom. Only
 * the view changes; nothing is written to the note. One setting for every note, kept between
 * runs.
 *
 * Each label and text field under the zoomed node keeps the size it was made with (seen at
 * its first layout) and is shown at that size times the zoom, so rows added later follow too.
 */
final class Zoom {

    private Zoom() {}

    private static final String BASE = "np-zoom-base", STYLE = "np-zoom-style";
    private static double level = -1;

    static double level() {
        if (level < 0) {
            try {
                String v = Prefs.get("note_zoom");
                level = v == null ? 1 : Math.max(0.5, Math.min(3, Double.parseDouble(v)));
            } catch (NumberFormatException e) {
                level = 1;
            }
        }
        return level;
    }

    /** Steps the zoom ({@code by} = 0 resets it). Returns the new level. */
    static double step(double by) {
        level = by == 0 ? 1 : Math.max(0.5, Math.min(3, Math.round((level() + by) * 10) / 10.0));
        Prefs.put("note_zoom", String.valueOf(level));
        return level;
    }

    /** Sizes every label and field under {@code root} for the current zoom. Cheap when nothing changes. */
    static void apply(Node root) {
        double z = level();
        walk(root, z);
    }

    private static void walk(Node n, double z) {
        if (n instanceof Labeled || n instanceof TextInputControl) {
            size(n, z);
            return; // Their insides are the control's own.
        }
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) walk(c, z);
    }

    private static void size(Node n, double z) {
        javafx.scene.text.Font font = n instanceof Labeled l ? l.getFont() : ((TextInputControl) n).getFont();
        if (font == null) return;
        double size = font.getSize();
        String style = n.getStyle() == null ? "" : n.getStyle();
        // Ours while its style is still the one set here; otherwise its own code set a new
        // one (or it is new), and the size it has now is its size at zoom 1.
        boolean ours = style.equals(n.getProperties().get(STYLE));
        double base;
        if (ours && n.getProperties().get(BASE) instanceof double[] seen) {
            if (Math.abs(seen[1] - size) > 0.01) return; // The style is set; CSS hasn't caught up yet.
            base = seen[0];
        } else {
            if (z == 1) return;
            base = size;
        }
        double want = Math.round(base * z * 2) / 2.0;
        if (ours && Math.abs(size - want) < 0.01) return;
        String own = style.replaceAll("-fx-font-size:[^;]*;?", "").trim();
        String next = own + (own.isEmpty() || own.endsWith(";") ? "" : ";") + "-fx-font-size: " + want + "px;";
        n.getProperties().put(BASE, new double[]{base, want});
        n.getProperties().put(STYLE, next);
        n.setStyle(next);
    }
}
