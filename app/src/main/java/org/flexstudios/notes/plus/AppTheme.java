package org.flexstudios.notes.plus;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * The whole app's look, chosen on the Account screen: the phone's setting, light or dark
 * ({@code app_theme} in the app prefs). Applied once at start and whenever it changes.
 */
final class AppTheme {

    private AppTheme() {}

    static final String[] MODES = {"system", "light", "dark"};
    static final String[] NAMES = {"Same as the phone", "Light", "Dark"};
    private static final String PREF = "app_theme";

    static String mode(Context context) {
        String m = SecurityHelper.getAppPrefs(context).getString(PREF, "system");
        return m == null ? "system" : m;
    }

    static int index(Context context) {
        return Math.max(0, java.util.Arrays.asList(MODES).indexOf(mode(context)));
    }

    static void apply(Context context) {
        String m = mode(context);
        AppCompatDelegate.setDefaultNightMode(m.equals("light") ? AppCompatDelegate.MODE_NIGHT_NO
                : m.equals("dark") ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    static void set(Context context, String m) {
        SecurityHelper.getAppPrefs(context).edit().putString(PREF, m).apply();
        apply(context);
    }
}
