package org.flexstudios.notes.plus;

import javafx.application.Platform;

/**
 * The open gallery vault, if any, and its rules: the phone's VaultActivity and SecurityHelper.
 *
 * <ul>
 *   <li>Entering a vault wipes leftover shared copies and half-written files.</li>
 *   <li>Leaving it (back to the notes) locks the data key away and empties the thumbnail cache.</li>
 *   <li>Auto-lock: once the window has been left for longer than the chosen delay ("At once",
 *       30 s, 1 min), coming back shows the lock screen again over the notes. A file picker the
 *       vault itself opened ({@link #expectExternal}) doesn't count as leaving.</li>
 *   <li>Screenshots: off unless allowed, through {@link Capture}.</li>
 * </ul>
 */
final class Vault {

    private Vault() {}

    static final String KEY_AUTO_LOCK = "auto_lock_time";
    static final String KEY_SCREENSHOTS = "allow_screenshots";
    static final String KEY_SETUP_DONE = "lock_setup_done";
    static final String KEY_LOCK_BG_TYPE = "lock_bg_type", KEY_LOCK_BG_VALUE = "lock_bg_value";
    static final String KEY_LOCK_BG_BLUR = "lock_bg_blur", KEY_LOCK_BG_DIM = "lock_bg_dim";

    private static int openId = -1;
    private static boolean expectingExternal;
    private static long awayAt;

    static boolean isSetupDone() {
        return "true".equals(Prefs.get(KEY_SETUP_DONE)) && VaultCrypto.hasKeyring();
    }

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

    /** A vault was unlocked. */
    static void opened(int vaultId) {
        openId = vaultId;
        VaultStore.IO.execute(() -> {
            VaultCrypto.wipeShareDir();
            VaultVideo.sweep();
            VaultCrypto.removeIncompleteWrites();
        });
        Capture.protect(!screenshotsAllowed());
    }

    /** Back to the notes: the vault closes and its key leaves memory. */
    static void close() {
        openId = -1;
        VaultCrypto.lock();
        Thumbs.clear();
        VaultStore.IO.execute(() -> {
            VaultCrypto.wipeShareDir();
            VaultVideo.sweep();
        });
        Capture.protect(false);
    }

    /** About to open a file picker or the like: losing focus to it isn't leaving. */
    static void expectExternal() {
        expectingExternal = true;
    }

    private static int busy;

    /** A long job (a backup) is running: auto-lock waits for it. */
    static void busy(boolean on) {
        busy = Math.max(0, busy + (on ? 1 : -1));
    }

    /** The window lost focus. */
    static void windowLeft() {
        if (isOpen() && !expectingExternal) awayAt = System.currentTimeMillis();
    }

    /** The window came back: lock again if it was away for longer than the delay. */
    static void windowReturned() {
        boolean wasAway = awayAt > 0 && System.currentTimeMillis() - awayAt >= autoLockDelay();
        awayAt = 0;
        expectingExternal = false;
        if (isOpen() && wasAway && busy == 0) Platform.runLater(Vault::relock);
    }

    /** Shows the lock screen again over the notes. */
    static void relock() {
        NotesApp.backToNotes();
        close();
        NotesApp.open(new UnlockView());
    }
}
