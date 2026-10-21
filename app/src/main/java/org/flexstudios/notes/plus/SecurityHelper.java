package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class SecurityHelper {
    private static final String PREF_NAME = "secret_prefs";
    private static final String KEY_SETUP_DONE = "lock_setup_done";
    public static final String KEY_LOCK_TYPE = "secret_lock_type";
    public static final String KEY_LOCK_VALUE = "secret_lock_value";
    
    public static final String KEY_AUTO_LOCK_TIME = "auto_lock_time"; // in milliseconds
    public static final String KEY_LAST_ACTIVE_TIME = "last_active_time";

    // Universal Lock Screen Background
    public static final String KEY_LOCK_BG_TYPE = "lock_bg_type"; // "DEFAULT", "COLOR", "IMAGE"
    public static final String KEY_LOCK_BG_VALUE = "lock_bg_value";
    public static final String KEY_LOCK_BG_BLUR = "lock_bg_blur";
    public static final String KEY_LOCK_BG_DIM = "lock_bg_dim";

    public static final String KEY_SYNC_MODE = "sync_mode"; // "MANUAL", "AUTO"
    /** "WIFI" (default) or "ANY". Drives the WorkManager network constraint. */
    public static final String KEY_SYNC_NETWORK = "sync_network";

    public static boolean isAutoSyncEnabled(Context context) {
        return "AUTO".equals(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString(KEY_SYNC_MODE, "MANUAL"));
    }

    /**
     * Defaults to Wi-Fi only. A vault backup can be several gigabytes, so metered data is
     * never used unless the user asks for it.
     */
    public static boolean isSyncWifiOnly(Context context) {
        return !"ANY".equals(getAppPrefs(context).getString(KEY_SYNC_NETWORK, "WIFI"));
    }

    public static void setSyncNetwork(Context context, boolean wifiOnly) {
        getAppPrefs(context).edit().putString(KEY_SYNC_NETWORK, wifiOnly ? "WIFI" : "ANY").apply();
    }

    public static void setAutoSyncEnabled(Context context, boolean enabled) {
        getAppPrefs(context).edit().putString(KEY_SYNC_MODE, enabled ? "AUTO" : "MANUAL").apply();
    }

    private static volatile SharedPreferences encryptedPrefs;

    /**
     * The Keystore-backed store holding the vault data key. Opening it costs Keystore round
     * trips, so it is opened once per process.
     *
     * There is deliberately no fallback. The old code fell back to a plain prefs file of the
     * same name on any error, which made the next caller generate a fresh data key and write
     * it there in the clear. Failing loudly is the only safe answer.
     */
    public static SharedPreferences getEncryptedPrefs(Context context) {
        SharedPreferences prefs = encryptedPrefs;
        if (prefs != null) return prefs;
        synchronized (SecurityHelper.class) {
            if (encryptedPrefs == null) {
                try {
                    String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                    encryptedPrefs = EncryptedSharedPreferences.create(
                            PREF_NAME,
                            masterKeyAlias,
                            context.getApplicationContext(),
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );
                } catch (GeneralSecurityException | IOException e) {
                    throw new IllegalStateException("Secure storage is unavailable", e);
                }
            }
            return encryptedPrefs;
        }
    }

    public static boolean isSetupDone(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false);
    }

    public static void setSetupDone(Context context, boolean done) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SETUP_DONE, done)
                .apply();
    }
    
    public static long getAutoLockDelay(Context context) {
        return getAutoLockDelay(context, "");
    }

    public static void setAutoLockDelay(Context context, long delay) {
        setAutoLockDelay(context, "", delay);
    }

    public static void updateLastActiveTime(Context context) {
        updateLastActiveTime(context, "");
    }

    public static long getLastActiveTime(Context context) {
        return getLastActiveTime(context, "");
    }

    private static final String KEY_ALLOW_SCREENSHOTS = "allow_screenshots";

    /** Off by default: the hidden screens block capture and blank their recents thumbnail. */
    public static boolean screenshotsAllowed(Context context, String realm) {
        return getAppPrefs(context).getBoolean(KEY_ALLOW_SCREENSHOTS + realm, false);
    }

    public static void setScreenshotsAllowed(Context context, String realm, boolean allowed) {
        getAppPrefs(context).edit().putBoolean(KEY_ALLOW_SCREENSHOTS + realm, allowed).apply();
    }

    /** FLAG_SECURE on or off for {@code window}, per the realm's setting. */
    public static void applyScreenProtection(android.view.Window window, Context context, String realm) {
        if (screenshotsAllowed(context, realm)) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // Per realm ("" for the gallery, UnlockActivity.REALM_CHAT for chat): each hidden space
    // keeps its own auto-lock delay and its own idea of when it was last used.

    public static long getAutoLockDelay(Context context, String realm) {
        return getAppPrefs(context).getLong(KEY_AUTO_LOCK_TIME + realm, 0); // Default 0: Immediately
    }

    public static void setAutoLockDelay(Context context, String realm, long delay) {
        getAppPrefs(context).edit().putLong(KEY_AUTO_LOCK_TIME + realm, delay).apply();
    }

    public static void updateLastActiveTime(Context context, String realm) {
        getAppPrefs(context).edit().putLong(KEY_LAST_ACTIVE_TIME + realm, System.currentTimeMillis()).apply();
    }

    public static long getLastActiveTime(Context context, String realm) {
        return getAppPrefs(context).getLong(KEY_LAST_ACTIVE_TIME + realm, 0);
    }

    public static SharedPreferences getAppPrefs(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }
}