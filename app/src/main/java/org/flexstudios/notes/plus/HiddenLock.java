package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The lock on hidden notes. Like the chat space's: the first one is the app's main lock at that
 * moment (the gallery's main vault), after that it's their own, changed from the hidden view.
 *
 * Stored only as {@code h1:<salt hex>:<hex PBKDF2-HMAC-SHA256(type + ":" + value, salt, 210000, 32)>},
 * here (app prefs) and online at {@code users/{uid}/hiddenLock} = {type, hash, at}, so the desktop and
 * the web check the same lock; the newest {@code at} wins. Signed out, it stays on this phone.
 */
final class HiddenLock {

    private HiddenLock() {}

    private static final String TYPE = "hidden_lock_type", HASH = "hidden_lock_hash", AT = "hidden_lock_at";
    /** Set while the lock is still the one it started with (the main lock): suggest one of its own, once. */
    static final String INHERITED = "hidden_lock_inherited";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static SharedPreferences prefs(Context c) {
        return SecurityHelper.getAppPrefs(c);
    }

    @Nullable
    static String type(Context c) {
        return prefs(c).getString(TYPE, null);
    }

    static boolean isSet(Context c) {
        return prefs(c).getString(HASH, null) != null;
    }

    /** The hash of a lock, with a new salt. Slow on purpose. */
    static String hash(String type, String value) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String s = hex(salt);
        return "h1:" + s + ":" + hex(VaultCrypto.pbkdf2(type + ":" + value, salt, VaultCrypto.PBKDF2_ITERATIONS));
    }

    /** Whether {@code type}/{@code value} opens hidden notes. Background thread. */
    static boolean matches(Context c, String type, String value) throws Exception {
        String stored = prefs(c).getString(HASH, null);
        return stored != null && type.equals(type(c)) && check(stored, type, value);
    }

    /** Whether {@code stored} ("h1:salt:hash") is this lock's. The same check on the desktop and the web. */
    static boolean check(String stored, String type, String value) throws Exception {
        String[] parts = stored.split(":");
        if (parts.length != 3 || !"h1".equals(parts[0])) return false;
        byte[] got = VaultCrypto.pbkdf2(type + ":" + value, unhex(parts[1]), VaultCrypto.PBKDF2_ITERATIONS);
        return MessageDigest.isEqual(got, unhex(parts[2]));
    }

    /** A new lock of their own (from "Change lock"); sent to the account's other devices. Background thread. */
    static void set(Context c, String type, String value) throws Exception {
        store(c, type, hash(type, value), System.currentTimeMillis());
        prefs(c).edit().remove(INHERITED).apply();
        push(c);
    }

    private static void store(Context c, String type, String hash, long at) {
        prefs(c).edit().putString(TYPE, type).putString(HASH, hash).putLong(AT, at).apply();
    }

    /**
     * Before the lock screen (background thread): the account's lock if it's newer; with none
     * anywhere, the main lock as it is now. False when there's no lock to use at all.
     */
    static boolean prepare(Context c) {
        pull(c);
        if (isSet(c)) return true;
        VaultEntity main = AppDatabase.getInstance(c).vaultDao().getMainVault();
        if (main == null || main.getLockType() == null || main.getLockValue() == null) return false;
        try {
            store(c, main.getLockType(), hash(main.getLockType(), main.getLockValue()), System.currentTimeMillis());
        } catch (Exception e) {
            return false;
        }
        prefs(c).edit().putBoolean(INHERITED, true).apply();
        push(c);
        return true;
    }

    /** The account's lock, when it's newer than this phone's. */
    private static void pull(Context c) {
        String uid = Account.uid();
        if (uid == null) return;
        try {
            DataSnapshot s = Tasks.await(Account.root().child("users").child(uid).child("hiddenLock").get(), 5, TimeUnit.SECONDS);
            Object type = s.child("type").getValue(), hash = s.child("hash").getValue(), at = s.child("at").getValue();
            if (!(type instanceof String) || !(hash instanceof String) || !(at instanceof Number)) {
                if (isSet(c)) push(c); // Online has none yet: this phone's goes up.
                return;
            }
            long remoteAt = ((Number) at).longValue();
            if (remoteAt > prefs(c).getLong(AT, 0)) {
                store(c, (String) type, (String) hash, remoteAt);
                prefs(c).edit().remove(INHERITED).apply(); // Chosen elsewhere: no suggestion here.
            } else if (remoteAt < prefs(c).getLong(AT, 0)) {
                push(c);
            }
        } catch (Exception ignored) {
            // Offline: this phone's lock (if any) still opens them.
        }
    }

    private static void push(Context c) {
        String uid = Account.uid(), hash = prefs(c).getString(HASH, null), type = type(c);
        if (uid == null || hash == null || type == null) return;
        Map<String, Object> v = new HashMap<>();
        v.put("type", type);
        v.put("hash", hash);
        v.put("at", prefs(c).getLong(AT, System.currentTimeMillis()));
        Account.root().child("users").child(uid).child("hiddenLock").setValue(v);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
