package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The app's small settings, the phone's SharedPreferences: one JSON file in the data folder,
 * written whole (to a temporary file, then moved over) on every change.
 *
 * {@link #secret} values (the account's refresh tokens) are kept in a separate file so the
 * ordinary one can be read or shared without them.
 */
final class Prefs {

    private Prefs() {}

    private static JSONObject plain, secret;

    private static Path file(boolean isSecret) {
        return Storage.root().resolve(isSecret ? "account.json" : "prefs.json");
    }

    private static synchronized JSONObject load(boolean isSecret) {
        JSONObject cached = isSecret ? secret : plain;
        if (cached != null) return cached;
        JSONObject o;
        try {
            Path f = file(isSecret);
            o = Files.exists(f) ? new JSONObject(Files.readString(f, StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Exception e) {
            o = new JSONObject();
        }
        if (isSecret) secret = o;
        else plain = o;
        return o;
    }

    private static synchronized void save(boolean isSecret) {
        JSONObject o = load(isSecret);
        try {
            Path f = file(isSecret);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.getLogger("Prefs").log(System.Logger.Level.WARNING, "Could not save settings", e);
        }
    }

    static synchronized String get(String key) {
        return load(false).optString(key, null);
    }

    static synchronized long getLong(String key) {
        return load(false).optLong(key, 0);
    }

    static synchronized void put(String key, Object value) {
        if (value == null) load(false).remove(key);
        else load(false).put(key, value);
        save(false);
    }

    /** The account's session: separate file. */
    static synchronized String secret(String key) {
        return load(true).optString(key, null);
    }

    static synchronized void putSecret(String key, String value) {
        if (value == null) load(true).remove(key);
        else load(true).put(key, value);
        save(true);
    }

    /** Forgets every key starting with {@code prefix}. */
    static synchronized void removePrefix(String prefix) {
        JSONObject o = load(false);
        for (String k : o.keySet().toArray(new String[0])) if (k.startsWith(prefix)) o.remove(k);
        save(false);
    }
}
