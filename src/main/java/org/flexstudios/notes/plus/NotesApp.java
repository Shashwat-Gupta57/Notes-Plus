package org.flexstudios.notes.plus;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The window. One stage, one stack of {@link Screen}s (the notes grid at the bottom, an editor
 * on top), and {@link Ui#layer} over them for dialogs and snackbars.
 *
 * Editors save when they are left, when the window loses focus and when it closes: the
 * phone's "save on pause". Closing waits for those writes to land.
 */
public class NotesApp extends Application {

    private static NotesApp app;

    private Stage stage;
    private final StackPane screens = new StackPane();
    private final Deque<Screen> stack = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        app = this;
        this.stage = stage;
        for (String face : new String[]{"Regular", "Medium", "Bold", "Light"}) {
            try (InputStream in = NotesApp.class.getResourceAsStream("fonts/Roboto-" + face + ".ttf")) {
                if (in != null) Font.loadFont(in, 14);
            } catch (Exception ignored) {
                // Falls back to the system font.
            }
        }

        Ui.layer = new StackPane();
        Ui.layer.setPickOnBounds(false);
        javafx.scene.layout.Pane underlines = new javafx.scene.layout.Pane();
        StackPane root = new StackPane(screens, underlines, Ui.layer);
        Scene scene = new Scene(root, 1120, 780);
        scene.getStylesheets().add(NotesApp.class.getResource("styles.css").toExternalForm());
        Theme.attach(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        Links.attach(scene, underlines);

        stage.setTitle("Notes+");
        stage.setMinWidth(420);
        stage.setMinHeight(560);
        try (InputStream icon = NotesApp.class.getResourceAsStream("icon.png")) {
            if (icon != null) stage.getIcons().add(new Image(icon));
        } catch (Exception ignored) {
            // No icon: the default one.
        }
        stage.setScene(scene);
        // Leaving the window is the phone's "app to the background": save, then sync.
        stage.focusedProperty().addListener((o, was, is) -> {
            if (!is && top() != null) top().onPause();
            if (!is) NoteStore.write(s -> NoteSync.schedule());
            if (!is) {
                Vault.windowLeft();
                ChatSpace.windowLeft();
            } else {
                Vault.windowReturned();
                ChatSpace.windowReturned();
            }
        });
        // Closing leaves every screen, as Back would: a recording in progress is kept, an empty
        // new note is thrown away, and the writes land before the process ends.
        stage.setOnCloseRequest(e -> {
            Ui.closeSnack(false);
            for (Screen s : stack) {
                s.onPause();
                s.onDestroy();
            }
            NoteSync.stopLive();
            if (Vault.isOpen()) Vault.close();
            if (ChatSpace.isOpen()) ChatSpace.close();
            NoteStore.drain();
        });

        NoteStore.write(NoteStore::sweepTombstones);
        VaultStore.IO.execute(VaultVideo::sweep); // A crash may have left a playback copy.
        if (Account.uid() != null) NoteSync.startLive();
        NoteSync.startPeriodic();
        ChatStore.IO.execute(ChatMedia::sweepPlain); // A crash may have left a playing voice note.
        ChatSync.startPeriodic();
        ChatSync.schedule();
        open(new MainView());
        // The first run asks for the app's lock, as the phone does.
        if (!Vault.isSetupDone()) open(new SetupLockView());
        stage.show();
    }

    private void onKey(KeyEvent e) {
        if (Ui.hasModal()) {
            if (Ui.isBack(e)) {
                Ui.dismissTop();
                e.consume();
            }
            return;
        }
        Screen top = top();
        if (top == null) return;
        if (Ui.isBack(e) && stack.size() > 1) {
            e.consume();
            back();
            return;
        }
        top.onKey(e);
    }

    private Screen top() {
        return stack.peek();
    }

    static Stage stage() {
        return app.stage;
    }

    /** The screen on top, or null. */
    static Screen current() {
        return app == null ? null : app.top();
    }

    /** Opens a web address in the system's browser. */
    /**
     * Opens {@code url} in the default browser, from any thread. JavaFX's HostServices does
     * nothing when called off the FX thread on Windows, so the desktop's own handler goes first.
     */
    static void openInBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // Next way.
        }
        try {
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                return;
            }
        } catch (Exception ignored) {
            // Next way.
        }
        javafx.application.Platform.runLater(() -> app.getHostServices().showDocument(url));
    }

    /** Puts {@code screen} on top, pausing the one below. */
    static void open(Screen screen) {
        Screen below = app.top();
        if (below != null) below.onPause();
        Ui.closeSnack(false);
        app.stack.push(screen);
        Node view = screen.view();
        app.screens.getChildren().setAll(view);
        if (below != null) enter(view);
        screen.onResume();
    }

    /** Closes the top screen (asking it first) and resumes the one below. */
    static void back() {
        if (app.stack.size() <= 1) return;
        Screen top = app.stack.peek();
        if (top.onBack()) return;
        app.stack.pop();
        top.onPause();
        top.onDestroy();
        Ui.closeSnack(false);
        Screen below = app.stack.peek();
        app.screens.getChildren().setAll(below.view());
        below.onResume();
    }

    /** Closes every screen above the notes grid (the vault's, when it locks). */
    static void backToNotes() {
        while (app.stack.size() > 1) {
            Screen top = app.stack.pop();
            top.onPause();
            top.onDestroy();
        }
        Ui.closeSnack(false);
        while (Ui.hasModal()) Ui.dismissTop();
        Screen notes = app.stack.peek();
        app.screens.getChildren().setAll(notes.view());
        notes.onResume();
    }

    /** Replaces the top screen (the lock screen by the vault it opened). */
    static void replace(Screen screen) {
        Screen top = app.stack.pop();
        top.onPause();
        top.onDestroy();
        app.stack.push(screen);
        Node view = screen.view();
        app.screens.getChildren().setAll(view);
        enter(view);
        screen.onResume();
    }

    /** The phone's activity transition: a short fade and rise. */
    private static void enter(Node view) {
        view.setOpacity(0);
        view.setTranslateY(24);
        FadeTransition fade = new FadeTransition(Duration.millis(180), view);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(220), view);
        rise.setToY(0);
        new ParallelTransition(fade, rise).play();
    }
}
