package org.flexstudios.notes.plus;

import javafx.scene.Node;
import javafx.scene.input.KeyEvent;

/**
 * One screen of the window, the desktop's activity: the notes grid or an editor. Screens are
 * stacked by {@link NotesApp#open} and {@link NotesApp#back}, with the phone's lifecycle.
 */
abstract class Screen {

    /** The screen's root node, built once. */
    abstract Node view();

    /** On top and visible again. */
    void onResume() {}

    /**
     * Covered by another screen, the window lost focus, or the app is closing: the phone's
     * onPause, and where editors save.
     */
    void onPause() {}

    /** Removed for good. */
    void onDestroy() {}

    /** Back was asked for (Escape, the arrow). Return true to stay. */
    boolean onBack() {
        return false;
    }

    /** Keys pressed while this screen is on top and no dialog is open. */
    void onKey(KeyEvent e) {}
}
