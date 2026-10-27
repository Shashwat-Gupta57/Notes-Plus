package org.flexstudios.notes.plus;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The windows. Each is a stack of {@link Screen}s (the notes grid at the bottom, an editor on
 * top) with its own {@link Ui#layer} for dialogs and snackbars. There is one process and one
 * {@link NoteStore}, so every window sees every write the moment it lands: ten windows on the
 * grid all move a note that was just edited, synced or recoloured.
 *
 * More windows come from "New window" (Ctrl+Shift+N), an editor's pop-out button, and a file
 * opened from Explorer while a note is open. The hidden spaces open in the window whose notes
 * were swiped.
 *
 * Editors save when they are left, when their window loses focus and when it closes: the
 * phone's "save on pause". Closing the last window waits for those writes to land.
 */
public class NotesApp extends Application {

    private static NotesApp app;

    /** One window: its stage, its screens and its floating layer. */
    static final class Win {
        private static int nextWinId = 1;
        final int id = nextWinId++;
        final Stage stage;
        private final StackPane screens = new StackPane();
        private final Deque<Screen> stack = new ArrayDeque<>();
        private final StackPane layer = new StackPane();
        private final Pane underlines = new Pane();

        Win(Stage stage) {
            this.stage = stage;
            layer.setPickOnBounds(false);
            StackPane root = new StackPane(screens, underlines, layer);
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
            AppLog.windowOpened(id, stage.getTitle() != null ? stage.getTitle() : "Notes+", WINDOWS.size() + 1);
            // Leaving the window is the phone's "app to the background": save, then sync.
            stage.focusedProperty().addListener((o, was, is) -> {
                if (is) {
                    use(this);
                    AppLog.windowFocused(id, stage.getTitle() != null ? stage.getTitle() : "Notes+");
                }
                if (!is && top() != null) top().onPause();
                if (!is) NoteStore.write(s -> NoteSync.schedule());
                if (!is) {
                    Vault.windowLeft();
                    ChatSpace.windowLeft();
                } else {
                    Vault.windowReturned();
                    ChatSpace.windowReturned();
                    FileLinks.syncLater(); // Files edited elsewhere while away: their notes take them.
                }
            });
            stage.setOnCloseRequest(this::closing);
        }

        Screen top() {
            return stack.peek();
        }

        /** The notes grid at the bottom of this window, if it has one. */
        MainView home() {
            return stack.peekLast() instanceof MainView m ? m : null;
        }

        /**
         * Closing leaves every screen, as Back would: a recording in progress is kept, an empty
         * new note is thrown away. The last window also lets the writes land before the end.
         */
        private void closing(WindowEvent e) {
            // Unsaved text in the Editor: ask first; closing again after the answer.
            MainView home = home();
            if (home != null && !home.mayClose(() -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)))) {
                e.consume();
                return;
            }
            Ui.closeSnack(false);
            boolean hidden = holdsHiddenSpace();
            for (Screen s : stack) {
                s.onPause();
                s.onDestroy();
            }
            stack.clear();
            WINDOWS.remove(this);
            AppLog.windowClosed(id, WINDOWS.size());
            if (active == this) active = WINDOWS.isEmpty() ? null : WINDOWS.get(WINDOWS.size() - 1);
            if (active != null) use(active);
            if (hidden || WINDOWS.isEmpty()) {
                if (Vault.isOpen()) Vault.close();
                if (ChatSpace.isOpen()) ChatSpace.close();
            }
            if (WINDOWS.isEmpty()) {
                NoteSync.stopLive();
                NoteStore.drain();
            }
        }

        /** A vault or chat screen is in this window (they sit over the notes, never under an editor). */
        private boolean holdsHiddenSpace() {
            for (Screen s : stack) if (!(s instanceof MainView) && !(s instanceof NoteEditor)) return true;
            return false;
        }

        private void onKey(KeyEvent e) {
            if (Ui.hasModal()) {
                if (Ui.isBack(e) || (e.isShortcutDown() && e.getCode() == KeyCode.W)) {
                    Ui.dismissTop();
                    e.consume();
                }
                return;
            }
            Screen top = top();
            if (top == null) return;
            if (e.isShortcutDown() && e.isShiftDown() && e.getCode() == KeyCode.N) {
                e.consume();
                newWindow();
                return;
            }
            // Ctrl+W: closes an open note/editor or screen, or its window if pop-out.
            if (e.isShortcutDown() && e.getCode() == KeyCode.W) {
                if (top instanceof NoteEditor || top instanceof ViewerView || stack.size() > 1 || home() == null) {
                    e.consume();
                    back(this);
                    return;
                }
            }
            // A note in a window of its own closes the window when it is left.
            if (Ui.isBack(e) && (stack.size() > 1 || home() == null)) {
                e.consume();
                back(this);
                return;
            }
            top.onKey(e);
        }
    }

    private static final List<Win> WINDOWS = new ArrayList<>();
    /** The window last focused: where screens open, and where dialogs and toasts show. */
    private static Win active;

    private static void use(Win w) {
        active = w;
        Ui.layer = w.layer;
        Links.useOverlay(w.underlines);
    }

    /** A new window, offset a little from the current one, with {@code first} as its only screen. */
    private static Win window(Screen first) {
        Win w = new Win(new Stage());
        if (active != null) {
            w.stage.setX(active.stage.getX() + 32);
            w.stage.setY(active.stage.getY() + 32);
            w.stage.setWidth(active.stage.getWidth());
            w.stage.setHeight(active.stage.getHeight());
        }
        WINDOWS.add(w);
        use(w);
        push(w, first, false);
        w.stage.show();
        w.stage.toFront();
        AppLog.info("[MULTI-WINDOW] Created window #" + w.id + " with screen: " + first.getClass().getSimpleName() + ". Total active windows: " + WINDOWS.size());
        return w;
    }

    /** Another window on the notes. */
    static void newWindow() {
        window(new MainView());
    }

    /** {@code editor} in a window of its own. */
    static void popOut(Screen editor) {
        Win w = window(editor);
        w.stage.setWidth(Math.min(w.stage.getWidth(), 900));
    }

    @Override
    public void start(Stage stage) {
        app = this;
        for (String face : new String[]{"Regular", "Medium", "Bold", "Light"}) {
            try (InputStream in = NotesApp.class.getResourceAsStream("fonts/Roboto-" + face + ".ttf")) {
                if (in != null) Font.loadFont(in, 14);
            } catch (Exception ignored) {
                // Falls back to the system font.
            }
        }
        Win main = new Win(stage);
        WINDOWS.add(main);
        use(main);

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
        Updates.checkSoon();
        // A text file double-clicked in Explorer (or "Open with Notes+").
        for (String arg : getParameters().getRaw()) {
            java.nio.file.Path p = java.nio.file.Path.of(arg);
            if (TextEditorView.openable(p)) openFile(p);
        }
        Instance.listen(NotesApp::openFile, NotesApp::newWindow);
        FileLinks.syncLater();
    }

    /** The current window's stage (for file choosers and the like). */
    static Stage stage() {
        return active.stage;
    }

    /**
     * Opens a text file in the Editor, in a window showing the notes. If a note is open in
     * every window, the file gets a new window, so no note is closed to make room for it.
     */
    static void openFile(java.nio.file.Path file) {
        if (app == null || active == null) return;
        if (!Vault.isSetupDone()) return; // The first run's lock comes first.
        Win target = active.top() instanceof MainView ? active : null;
        for (int i = WINDOWS.size() - 1; target == null && i >= 0; i--) {
            if (WINDOWS.get(i).top() instanceof MainView) target = WINDOWS.get(i);
        }
        if (target == null) target = window(new MainView());
        Stage s = target.stage;
        if (s.isIconified()) s.setIconified(false);
        s.show();
        s.toFront();
        s.requestFocus();
        use(target);
        if (NoteExport.isExport(file)) NoteExport.importFile(file); // An exported note comes back as a note.
        else target.home().showEditor(file);
    }

    /** The screen on top of the current window, or null. */
    static Screen current() {
        return active == null ? null : active.top();
    }

    /**
     * Opens {@code url} in the default browser, dispatched to the FX thread for HostServices
     * and proper Windows shell integration.
     */
    static void openInBrowser(String url) {
        javafx.application.Platform.runLater(() -> {
            try {
                if (app != null && app.getHostServices() != null) {
                    app.getHostServices().showDocument(url);
                    return;
                }
            } catch (Exception ignored) {
                // Fallback below.
            }
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
                    new ProcessBuilder("cmd", "/c", "start", "", url).start();
                    return;
                }
            } catch (Exception ignored) {
                // Next way.
            }
            try {
                if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                    new ProcessBuilder("open", url).start();
                    return;
                }
            } catch (Exception ignored) {
                // Next way.
            }
            try {
                new ProcessBuilder("xdg-open", url).start();
            } catch (Exception ignored) {
                // Out of ways.
            }
        });
    }

    /** Puts {@code screen} on top of the current window, pausing the one below. */
    static void open(Screen screen) {
        push(active, screen, true);
    }

    private static void push(Win w, Screen screen, boolean animate) {
        Screen below = w.top();
        if (below != null) below.onPause();
        Ui.closeSnack(false);
        w.stack.push(screen);
        Node view = screen.view();
        w.screens.getChildren().setAll(view);
        if (below != null && animate) enter(view);
        screen.onResume();
    }

    /** Closes the top screen of the current window (asking it first) and resumes the one below. */
    static void back() {
        back(active);
    }

    /** Leaves {@code screen}, in whichever window it is on top of. */
    static void leave(Screen screen) {
        for (Win w : WINDOWS) {
            if (w.top() == screen) {
                back(w);
                return;
            }
        }
    }

    /** Closes an open note immediately across any window if its access was withdrawn or it was deleted remotely. */
    static void forceCloseNote(int noteId, String reason) {
        javafx.application.Platform.runLater(() -> {
            for (Win w : new java.util.ArrayList<>(WINDOWS)) {
                for (Screen s : new java.util.ArrayList<>(w.stack)) {
                    if (s instanceof NoteEditor ed && ed.note != null && ed.note.getId() == noteId) {
                        ed.forceClose();
                        if (w.stack.size() <= 1 && w.home() == null) {
                            w.stage.close();
                        } else {
                            leave(ed);
                        }
                        if (reason != null) Ui.toast(reason);
                    }
                }
            }
        });
    }

    /** Returns the open editor for this note id if any, else null. */
    static NoteEditor findOpenEditor(int noteId) {
        for (Win w : WINDOWS) {
            for (Screen s : w.stack) {
                if (s instanceof NoteEditor ed && ed.note != null && ed.note.getId() == noteId) {
                    return ed;
                }
            }
        }
        return null;
    }

    private static void back(Win w) {
        if (w == null || w.top() == null) return;
        if (w.stack.size() <= 1) {
            // A note in a window of its own: leaving it closes the window.
            if (w.home() == null && !w.top().onBack()) w.stage.fireEvent(new WindowEvent(w.stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return;
        }
        Screen top = w.stack.peek();
        if (top.onBack()) return;
        w.stack.pop();
        top.onPause();
        Ui.closeSnack(false);
        Screen below = w.stack.peek();
        Node topView = top.view();
        Node belowView = below.view();
        w.screens.getChildren().setAll(belowView, topView);
        exit(topView, () -> {
            top.onDestroy();
            w.screens.getChildren().setAll(belowView);
            below.onResume();
        });
    }

    /** Exit transition: a smooth fade and slide down. */
    private static void exit(Node view, Runnable onFinished) {
        FadeTransition fade = new FadeTransition(Duration.millis(160), view);
        fade.setToValue(0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), view);
        slide.setToY(20);
        ParallelTransition anim = new ParallelTransition(fade, slide);
        anim.setOnFinished(e -> onFinished.run());
        anim.play();
    }

    /**
     * Closes the hidden space's screens (the vault's, when it locks) down to the notes, in the
     * window that holds them, and makes that window the current one.
     */
    static void backToNotes() {
        Win holder = null;
        for (Win w : WINDOWS) if (w.holdsHiddenSpace()) holder = w;
        if (holder == null) holder = active;
        if (holder == null || holder.top() == null) return;
        use(holder);
        while (holder.stack.size() > 1) {
            Screen top = holder.stack.pop();
            top.onPause();
            top.onDestroy();
        }
        Ui.closeSnack(false);
        while (Ui.hasModal()) Ui.dismissTop();
        Screen notes = holder.stack.peek();
        holder.screens.getChildren().setAll(notes.view());
        notes.onResume();
    }

    /** Replaces the top screen of the current window (the lock screen by the vault it opened). */
    static void replace(Screen screen) {
        Win w = active;
        Screen top = w.stack.pop();
        top.onPause();
        top.onDestroy();
        w.stack.push(screen);
        Node view = screen.view();
        w.screens.getChildren().setAll(view);
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
