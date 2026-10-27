package org.flexstudios.notes.plus;

import javafx.application.Application;

/**
 * Launcher. A plain class, so the app starts from a fat jar or the classpath without the
 * JavaFX modules on the module path.
 */
public class Main {
    public static void main(String[] args) {
        AppLog.init("notes");
        // Notes+ already open: it opens the file (or, started plainly, a new window), and this
        // second start ends here, so every window shares one process and sees every change.
        if (Instance.handOff(args)) {
            AppLog.info("[INSTANCE] Handoff sent to running instance. Exiting secondary process.");
            return;
        }
        Application.launch(NotesApp.class, args);
    }
}
