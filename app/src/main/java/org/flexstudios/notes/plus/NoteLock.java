package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Locked notes: a note's text and data encrypted under its own PIN, password or pattern, so
 * only that lock opens it, on any device. The desktop's NoteLock is byte-compatible.
 *
 * <pre>
 * note.data    = {"lock":1, "type":"PIN|PASSWORD|PATTERN", "iter":210000, "salt":b64, "check":hex,
 *                 "iv":b64, "ct":b64}
 * note.content = ""   (nothing readable in search, on the card or in the account's database)
 * ct           = AES-256-GCM(key, iv, {"content":…, "data":…}), AAD "notes-plus/note-lock/v1"
 * key          = PBKDF2-HMAC-SHA256(lock value, salt, iter)
 * check        = HMAC-SHA256(key, "notes-plus/note-lock/check"): tells a wrong lock at once
 * </pre>
 *
 * The title stays readable so a locked note can be found. Files (pictures, recordings) are
 * not encrypted; sketches and audio notes can't be locked.
 *
 * Unlocking keeps the key in memory for the time set on the account screen (like a vault's
 * auto-lock), counted from leaving the note. The last lock of each note is remembered on this
 * device (type, salt, check; never the key), so locking it again is one tap, or one entry.
 */
final class NoteLock {

    private NoteLock() {}

    static final int ITERATIONS = 210_000;
    private static final byte[] AAD = "notes-plus/note-lock/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECK = "notes-plus/note-lock/check".getBytes(StandardCharsets.UTF_8);
    private static final String PREFS = "note_locks";
    static final String PREF_TIMEOUT = "note_unlock_minutes";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Wrong lock. */
    static final class WrongLock extends Exception {
        WrongLock() {
            super("That isn't this note's lock");
        }
    }

    /** A derived key and what it came from. */
    static final class Key {
        final String type;
        final byte[] salt;
        final int iterations;
        final SecretKey key;

        Key(String type, byte[] salt, int iterations, SecretKey key) {
            this.type = type;
            this.salt = salt;
            this.iterations = iterations;
            this.key = key;
        }

        String check() throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return VaultCrypto.bytesToHex(mac.doFinal(CHECK));
        }
    }

    // ================================================================ format

    /** True when a note's data is a lock envelope. */
    static boolean isLocked(@Nullable String data) {
        if (data == null || !data.startsWith("{") || !data.contains("\"lock\"")) return false;
        try {
            JSONObject o = new JSONObject(data);
            return o.optInt("lock") == 1 && o.has("ct");
        } catch (Exception e) {
            return false;
        }
    }

    /** PIN, PASSWORD or PATTERN. */
    static String typeOf(String data) {
        try {
            return new JSONObject(data).optString("type", "PASSWORD");
        } catch (Exception e) {
            return "PASSWORD";
        }
    }

    /** Locks can be put on these kinds; sketches and recordings live in files. */
    static boolean lockable(String type) {
        return !NoteData.SKETCH.equals(type) && !NoteData.AUDIO.equals(type);
    }

    /** A fresh key for a new lock. Slow (PBKDF2): off the main thread. */
    static Key newKey(String type, String value) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return derive(type, value, salt, ITERATIONS);
    }

    private static Key derive(String type, String value, byte[] salt, int iterations) throws Exception {
        return new Key(type, salt, iterations, new SecretKeySpec(VaultCrypto.pbkdf2(value, salt, iterations), "AES"));
    }

    /** The key of a locked note, from its lock value. Slow: off the main thread. */
    static Key open(String envelope, String value) throws Exception {
        JSONObject o = new JSONObject(envelope);
        Key k = derive(o.optString("type", "PASSWORD"), value, b64(o.getString("salt")), o.optInt("iter", ITERATIONS));
        if (!MessageDigest.isEqual(k.check().getBytes(StandardCharsets.US_ASCII),
                o.optString("check").getBytes(StandardCharsets.US_ASCII))) {
            throw new WrongLock();
        }
        return k;
    }

    /** Whether {@code k} is the key of this envelope (same salt and check). */
    static boolean fits(Key k, String envelope) {
        try {
            JSONObject o = new JSONObject(envelope);
            return o.optString("check").equals(k.check());
        } catch (Exception e) {
            return false;
        }
    }

    /** The envelope for a note's content and data. */
    static String seal(Key k, String content, @Nullable String data) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        JSONObject plain = new JSONObject().put("content", content == null ? "" : content);
        if (data != null) plain.put("data", data);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k.key, new GCMParameterSpec(128, iv));
        c.updateAAD(AAD);
        byte[] ct = c.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("lock", 1).put("type", k.type).put("iter", k.iterations)
                .put("salt", b64(k.salt)).put("check", k.check()).put("iv", b64(iv)).put("ct", b64(ct)).toString();
    }

    /** {content, data (may be null)} of an envelope. */
    static String[] unseal(Key k, String envelope) throws Exception {
        JSONObject o = new JSONObject(envelope);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k.key, new GCMParameterSpec(128, b64(o.getString("iv"))));
        c.updateAAD(AAD);
        JSONObject plain;
        try {
            plain = new JSONObject(new String(c.doFinal(b64(o.getString("ct"))), StandardCharsets.UTF_8));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongLock();
        }
        return new String[]{plain.optString("content", ""), plain.has("data") ? plain.optString("data") : null};
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] b64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

    // ================================================================ session

    private static final class Open {
        final Key key;
        volatile long lastUse = System.currentTimeMillis();
        volatile boolean inUse;

        Open(Key key) {
            this.key = key;
        }
    }

    private static final Map<String, Open> SESSION = new ConcurrentHashMap<>();

    /** Minutes a note stays unlocked after leaving it: 0 locks on leaving, -1 keeps it open until the app closes. */
    static int timeoutMinutes(Context context) {
        return SecurityHelper.getAppPrefs(context).getInt(PREF_TIMEOUT, 5);
    }

    static void setTimeoutMinutes(Context context, int minutes) {
        SecurityHelper.getAppPrefs(context).edit().putInt(PREF_TIMEOUT, minutes).apply();
        if (minutes == 0) SESSION.entrySet().removeIf(e -> !e.getValue().inUse);
    }

    /** The key of a note unlocked recently enough, or null. */
    @Nullable
    static Key session(Context context, String uuid) {
        Open o = uuid == null ? null : SESSION.get(uuid);
        if (o == null) return null;
        int minutes = timeoutMinutes(context);
        if (!o.inUse && minutes >= 0 && System.currentTimeMillis() - o.lastUse > minutes * 60_000L) {
            SESSION.remove(uuid);
            return null;
        }
        return o.key;
    }

    /** An editor has the note open with this key. */
    static void opened(String uuid, Key k) {
        Open o = new Open(k);
        o.inUse = true;
        SESSION.put(uuid, o);
    }

    /** The editor left: the time starts now (or, set to 0, it locks now). */
    static void left(Context context, String uuid) {
        Open o = SESSION.get(uuid);
        if (o == null) return;
        if (timeoutMinutes(context) == 0) {
            SESSION.remove(uuid);
            return;
        }
        o.inUse = false;
        o.lastUse = System.currentTimeMillis();
    }

    static void forget(String uuid) {
        if (uuid != null) SESSION.remove(uuid);
    }

    // ============================================================ last lock

    private static SharedPreferences last(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Remembers a note's lock (never its key), for locking it again with one entry. */
    static void remember(Context context, String uuid, Key k) {
        try {
            last(context).edit().putString(uuid, new JSONObject().put("type", k.type).put("salt", b64(k.salt))
                    .put("iter", k.iterations).put("check", k.check()).toString()).apply();
        } catch (Exception ignored) {
            // Then it asks for a new lock next time.
        }
    }

    /** The type of this note's last lock here, or null. */
    @Nullable
    static String lastType(Context context, String uuid) {
        String s = uuid == null ? null : last(context).getString(uuid, null);
        if (s == null) return null;
        try {
            return new JSONObject(s).optString("type", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The last lock's key from its value. Slow: off the main thread. */
    static Key lastKey(Context context, String uuid, String value) throws Exception {
        String s = last(context).getString(uuid, null);
        if (s == null) throw new WrongLock();
        JSONObject o = new JSONObject(s);
        Key k = derive(o.optString("type", "PASSWORD"), value, b64(o.getString("salt")), o.optInt("iter", ITERATIONS));
        if (!k.check().equals(o.optString("check"))) throw new WrongLock();
        return k;
    }

    static String typeWord(String type) {
        return "PIN".equals(type) ? "PIN" : "PATTERN".equals(type) ? "pattern" : "password";
    }
}
