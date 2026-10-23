package org.flexstudios.notes.plus;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * The phone's FLAG_SECURE, on Windows: while a hidden space is on screen, the window is left
 * out of screenshots, recordings and screen sharing (SetWindowDisplayAffinity). Quietly does
 * nothing elsewhere or on Windows versions without it.
 */
final class Capture {

    private Capture() {}

    private static final int WDA_NONE = 0, WDA_MONITOR = 1, WDA_EXCLUDEFROMCAPTURE = 0x11;
    private static boolean current;

    private interface Affinity extends StdCallLibrary {
        Affinity INSTANCE = Native.load("user32", Affinity.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean SetWindowDisplayAffinity(WinDef.HWND window, int affinity);
    }

    /** Hidden from capture or not. */
    static synchronized void protect(boolean on) {
        if (on == current || !System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            WinDef.HWND window = ownWindow();
            if (window == null) return;
            boolean ok = Affinity.INSTANCE.SetWindowDisplayAffinity(window, on ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE);
            // Before Windows 10 2004 the window can only be blacked out in captures.
            if (!ok && on) ok = Affinity.INSTANCE.SetWindowDisplayAffinity(window, WDA_MONITOR);
            if (ok) current = on;
        } catch (Throwable ignored) {
            // No user32 (or no JNA): screenshots stay possible.
        }
    }

    /** The app's main window: this process's visible window with the stage's title. */
    private static WinDef.HWND ownWindow() {
        String title = NotesApp.stage().getTitle();
        int pid = (int) ProcessHandle.current().pid();
        WinDef.HWND[] found = new WinDef.HWND[1];
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
            if (owner.getValue() != pid || !User32.INSTANCE.IsWindowVisible(hwnd)) return true;
            char[] text = new char[256];
            User32.INSTANCE.GetWindowText(hwnd, text, text.length);
            if (Native.toString(text).equals(title)) {
                found[0] = hwnd;
                return false;
            }
            return true;
        }, null);
        return found[0];
    }
}
