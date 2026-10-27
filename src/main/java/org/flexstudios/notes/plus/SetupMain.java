package org.flexstudios.notes.plus;

import javafx.application.Application;

/**
 * Launcher of "Notes+ Setup.exe" and "unins.exe" (Setup with {@code --uninstall}). A plain
 * class, like {@link Main}, so it starts without the JavaFX modules on the module path.
 */
public class SetupMain {
    public static void main(String[] args) {
        AppLog.init("setup");
        Application.launch(Setup.class, args);
    }
}
