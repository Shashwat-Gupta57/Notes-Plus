package org.flexstudios.notes.plus;

import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locked notes, the phone's NoteLock byte for byte: a note's text and data encrypted under its
 * own PIN, password or pattern, so only that lock opens it, on any device.
 *
 * <pre>
 * note.data    = {"lock":1, "type":"PIN|PASSWORD|PATTERN", "iter":210000, "salt":b64, "check":hex,
 *                 "iv":b64, "ct":b64}
 * note.content = ""
 * ct           = AES-256-GCM(key, iv, {"content":…, "data":…}), AAD "notes-plus/note-lock/v1"
 * key          = PBKDF2-HMAC-SHA256(lock value, salt, iter)
 * check        = HMAC-SHA256(key, "notes-plus/note-lock/check")
 * </pre>
 *
 * The title stays readable. Unlocking keeps the key in memory for the time set on the Account
 * screen, counted from leaving the note; each note's last lock is remembered here (type, salt,
 * check; never the key), so locking it again is one click or one entry.
 */
final class NoteLock {

    private NoteLock() {}

    static final int ITERATIONS = 210_000;
    private static final byte[] AAD = "notes-plus/note-lock/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECK = "notes-plus/note-lock/check".getBytes(StandardCharsets.UTF_8);
    private static final String PREF_LAST = "note_lock_last_";
    static final String PREF_TIMEOUT = "note_unlock_minutes";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class WrongLock extends Exception {
        WrongLock() {
            super("That isn't this note's lock");
        }
    }

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
            return HexFormat.of().formatHex(mac.doFinal(CHECK));
        }
    }

    // ================================================================ format

    static boolean isLocked(String data) {
        if (data == null || !data.startsWith("{") || !data.contains("\"lock\"")) return false;
        try {
            JSONObject o = new JSONObject(data);
            return o.optInt("lock") == 1 && o.has("ct");
        } catch (Exception e) {
            return false;
        }
    }

    static String typeOf(String data) {
        try {
            return new JSONObject(data).optString("type", "PASSWORD");
        } catch (Exception e) {
            return "PASSWORD";
        }
    }

    static boolean lockable(String type) {
        return !NoteData.SKETCH.equals(type) && !NoteData.AUDIO.equals(type);
    }

    static Key newKey(String type, String value) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return derive(type, value, salt, ITERATIONS);
    }

    private static Key derive(String type, String value, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), salt, iterations, 256);
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new Key(type, salt, iterations, new SecretKeySpec(raw, "AES"));
    }

    static Key open(String envelope, String value) throws Exception {
        JSONObject o = new JSONObject(envelope);
        Key k = derive(o.optString("type", "PASSWORD"), value, b64(o.getString("salt")), o.optInt("iter", ITERATIONS));
        if (!MessageDigest.isEqual(k.check().getBytes(StandardCharsets.US_ASCII),
                o.optString("check").getBytes(StandardCharsets.US_ASCII))) {
            throw new WrongLock();
        }
        return k;
    }

    static boolean fits(Key k, String envelope) {
        try {
            return new JSONObject(envelope).optString("check").equals(k.check());
        } catch (Exception e) {
            return false;
        }
    }

    static String seal(Key k, String content, String data) throws Exception {
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

    /** {content, data (may be null)}. */
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
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] b64(String s) {
        return Base64.getDecoder().decode(s);
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

    /** 0: locks on leaving; -1: open until the app closes; else minutes after leaving. */
    static int timeoutMinutes() {
        String v = Prefs.get(PREF_TIMEOUT);
        try {
            return v == null ? 5 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    static void setTimeoutMinutes(int minutes) {
        Prefs.put(PREF_TIMEOUT, String.valueOf(minutes));
        if (minutes == 0) SESSION.entrySet().removeIf(e -> !e.getValue().inUse);
    }

    static Key session(String uuid) {
        Open o = uuid == null ? null : SESSION.get(uuid);
        if (o == null) return null;
        int minutes = timeoutMinutes();
        if (!o.inUse && minutes >= 0 && System.currentTimeMillis() - o.lastUse > minutes * 60_000L) {
            SESSION.remove(uuid);
            return null;
        }
        return o.key;
    }

    static void opened(String uuid, Key k) {
        Open o = new Open(k);
        o.inUse = true;
        SESSION.put(uuid, o);
    }

    static void left(String uuid) {
        Open o = uuid == null ? null : SESSION.get(uuid);
        if (o == null) return;
        if (timeoutMinutes() == 0) {
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

    static void remember(String uuid, Key k) {
        try {
            Prefs.put(PREF_LAST + uuid, new JSONObject().put("type", k.type).put("salt", b64(k.salt))
                    .put("iter", k.iterations).put("check", k.check()).toString());
        } catch (Exception ignored) {
            // Then it asks for a new lock next time.
        }
    }

    static String lastType(String uuid) {
        String s = uuid == null ? null : Prefs.get(PREF_LAST + uuid);
        if (s == null) return null;
        try {
            return new JSONObject(s).optString("type", null);
        } catch (Exception e) {
            return null;
        }
    }

    static Key lastKey(String uuid, String value) throws Exception {
        String s = Prefs.get(PREF_LAST + uuid);
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
