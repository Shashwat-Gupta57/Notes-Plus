package org.flexstudios.notes.plus;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Reminders for list items with a day, while Notes+ is running: a Windows notification at the
 * item's time (9:00 on its day when it has none); clicking it opens the list. The phone's
 * Reminders, which also work with its app closed. Checked every 20 seconds against the store.
 *
 * Only my own lists on the grid: never a locked, hidden, archived or template one.
 */
final class Reminders {

    private Reminders() {}

    /** Up to when reminders have been shown, so none shows twice and none from before shows late. */
    private static final String SHOWN_UNTIL = "reminders_shown_until";
    private static final long LATE_OK = 12 * 60 * 60 * 1000L;
    private static java.awt.TrayIcon tray;
    /** The list the last notification was about, opened when it's clicked. */
    private static volatile int lastList = -1;

    private record Moment(long at, NoteEntity note, NoteData.TodoItem item) {}

    static long at(NoteData.TodoItem item) {
        int[] c = Due.civil(Due.day(item.due));
        int minute = Due.remindMinute(item);
        return LocalDate.of(c[0], c[1], c[2]).atTime(minute / 60, minute % 60)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static List<Moment> moments(List<NoteEntity> notes) {
        List<Moment> out = new ArrayList<>();
        for (NoteEntity n : notes) {
            if (n.isDeleted() || !NoteData.LIST.equals(n.getType()) || n.isHidden() || n.isArchived()
                    || n.isTemplate() || NoteLock.isLocked(n.getData())) continue;
            for (NoteData.TodoItem item : NoteData.todos(n.getData())) {
                if (Due.remindable(item)) out.add(new Moment(at(item), n, item));
            }
        }
        return out;
    }

    /** Starts checking (once, when the app starts). */
    static void start() {
        if (Prefs.get(SHOWN_UNTIL) == null) Prefs.put(SHOWN_UNTIL, System.currentTimeMillis()); // Nothing from before.
        Timeline check = new Timeline(new KeyFrame(Duration.seconds(20), e -> check()));
        check.setCycleCount(Timeline.INDEFINITE);
        check.play();
        check();
    }

    private static void check() {
        NoteStore.load(NoteStore::all, notes -> {
            long now = System.currentTimeMillis();
            long shownUntil = Prefs.getLong(SHOWN_UNTIL);
            if (shownUntil <= 0) shownUntil = now;
            List<Moment> due = new ArrayList<>();
            for (Moment m : moments(notes)) if (m.at() > shownUntil && m.at() <= now && now - m.at() < LATE_OK) due.add(m);
            Prefs.put(SHOWN_UNTIL, Math.max(shownUntil, now));
            for (Moment m : due) show(m);
        });
    }

    private static void show(Moment m) {
        String list = m.note().getTitle() == null || m.note().getTitle().trim().isEmpty() ? "List" : m.note().getTitle().trim();
        String time = Due.time(m.item().time);
        String words = NoteLinks.shown(m.item().text.trim());
        String body = list + "  ·  " + (time != null ? "Due " + time : "Due today");
        lastList = m.note().getId();
        Ui.toast("⏰ " + words + "  ·  " + body); // In the window too, in case Windows' notifications are off.
        // Windows' own notification, through the tray (AWT, off the JavaFX thread).
        new Thread(() -> {
            try {
                if (!java.awt.SystemTray.isSupported()) return;
                synchronized (Reminders.class) {
                    if (tray == null) {
                        java.awt.Image image;
                        try (var in = NotesApp.class.getResourceAsStream("icon.png")) {
                            image = in == null ? new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                    : javax.imageio.ImageIO.read(in);
                        }
                        tray = new java.awt.TrayIcon(image, "Notes+");
                        tray.setImageAutoSize(true);
                        tray.addActionListener(e -> Platform.runLater(Reminders::openLast));
                        java.awt.SystemTray.getSystemTray().add(tray);
                    }
                }
                tray.displayMessage(words, body, java.awt.TrayIcon.MessageType.INFO);
            } catch (Exception ignored) {
                // No tray (or notifications off): the in-window toast has said it.
            }
        }, "np-reminder").start();
    }

    /** On quitting: the tray icon goes, or it would keep the app running unseen. */
    static void stop() {
        synchronized (Reminders.class) {
            if (tray == null) return;
            java.awt.TrayIcon t = tray;
            tray = null;
            try {
                java.awt.SystemTray.getSystemTray().remove(t);
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    /** Clicking the notification: the window forward, on that list. */
    private static void openLast() {
        if (NotesApp.hiddenSpaceOpen()) return; // Never over one of the private spaces.
        javafx.stage.Stage stage = NotesApp.stage();
        if (stage != null) {
            stage.setIconified(false);
            stage.toFront();
        }
        if (lastList > 0) NotesApp.open(NoteData.editor(NoteData.LIST, lastList));
    }
}
