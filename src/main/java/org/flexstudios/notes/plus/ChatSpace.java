package org.flexstudios.notes.plus;

import javafx.application.Platform;

/**
 * The open chat vault, if any, and its rules: {@link Vault}'s twin for the chat realm. Nothing is
 * shared with the gallery but the lock screen's look and its lockout: the auto-lock delay and
 * the screenshot switch have keys of their own (suffixed "_chat", as on the phone).
 */
final class ChatSpace {

    private ChatSpace() {}

    static final String KEY_AUTO_LOCK = "auto_lock_time_chat";
    static final String KEY_SCREENSHOTS = "allow_screenshots_chat";

    private static int openId = -1;
    private static boolean expectingExternal;
    private static long awayAt;
    private static Runnable stopIndex;

    static boolean isOpen() {
        return openId >= 0;
    }

    static int openId() {
        return openId;
    }

    static long autoLockDelay() {
        return Prefs.getLong(KEY_AUTO_LOCK);
    }

    static void setAutoLockDelay(long ms) {
        Prefs.put(KEY_AUTO_LOCK, ms);
    }

    static boolean screenshotsAllowed() {
        return "true".equals(Prefs.get(KEY_SCREENSHOTS));
    }

    static void setScreenshotsAllowed(boolean allowed) {
        Prefs.put(KEY_SCREENSHOTS, String.valueOf(allowed));
        Capture.protect(isOpen() && !allowed);
    }

    /** A chat vault was unlocked. */
    static void opened(int vaultId) {
        openId = vaultId;
        ChatStore.IO.execute(ChatMedia::sweepPlain);
        Capture.protect(!screenshotsAllowed());
        if (stopIndex == null) stopIndex = ChatSync.watchIndex();
        ChatSync.schedule();
    }

    /** Back to the notes. */
    static void close() {
        openId = -1;
        if (stopIndex != null) {
            stopIndex.run();
            stopIndex = null;
        }
        ChatStore.IO.execute(ChatMedia::sweepPlain);
        Capture.protect(false);
    }

    static void expectExternal() {
        expectingExternal = true;
    }

    static void windowLeft() {
        if (isOpen() && !expectingExternal) awayAt = System.currentTimeMillis();
    }

    static void windowReturned() {
        boolean wasAway = awayAt > 0 && System.currentTimeMillis() - awayAt >= autoLockDelay();
        awayAt = 0;
        expectingExternal = false;
        if (isOpen() && wasAway) Platform.runLater(ChatSpace::relock);
    }

    static void relock() {
        NotesApp.backToNotes();
        close();
        NotesApp.open(new UnlockView(UnlockView.REALM_CHAT));
    }
}
