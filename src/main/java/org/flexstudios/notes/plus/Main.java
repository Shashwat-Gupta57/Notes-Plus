package org.flexstudios.notes.plus;

import javafx.application.Application;

/**
 * Launcher. A plain class, so the app starts from a fat jar or the classpath without the
 * JavaFX modules on the module path.
 */
public class Main {
    public static void main(String[] args) {
        // Notes+ already open: it opens the file, and this second start ends here.
        if (args.length > 0 && Instance.handOff(args)) return;
        Application.launch(NotesApp.class, args);
    }
}
