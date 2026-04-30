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

    public static SharedPreferences getEncryptedPrefs(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREF_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getLong(KEY_AUTO_LOCK_TIME, 0); // Default 0: Immediately
    }
    
    public static void setAutoLockDelay(Context context, long delay) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AUTO_LOCK_TIME, delay)
                .apply();
    }

    public static void updateLastActiveTime(Context context) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
                .apply();
    }

    public static long getLastActiveTime(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getLong(KEY_LAST_ACTIVE_TIME, 0);
    }

    public static SharedPreferences getAppPrefs(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }
}