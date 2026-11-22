package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * What this phone deleted from the vault (items, "album:" / "vault:" + uuid) or brought back from
 * an older restore point, kept until three Drive backups have carried it ({@link #CONFIRMATIONS}),
 * so the deletion reaches the backup and every other device instead of coming back. The desktop's
 * VaultMarks and the web's vault-sync.js keep the same rules. App prefs "vault_marks":
 * {"removed": {key: [at, n]}, "revived": {key: [at, n]}}.
 */
final class VaultMarks {

    static final int CONFIRMATIONS = 3;
    private static final String PREF = "vault_marks";

    private VaultMarks() {}

    private static JSONObject read(Context context) {
        try {
            String raw = SecurityHelper.getAppPrefs(context).getString(PREF, null);
            JSONObject d = raw == null ? new JSONObject() : new JSONObject(raw);
            if (d.optJSONObject("removed") == null) d.put("removed", new JSONObject());
            if (d.optJSONObject("revived") == null) d.put("revived", new JSONObject());
            return d;
        } catch (Exception e) {
            try {
                return new JSONObject().put("removed", new JSONObject()).put("revived", new JSONObject());
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static void write(Context context, JSONObject d) {
        SharedPreferences.Editor e = SecurityHelper.getAppPrefs(context).edit();
        e.putString(PREF, d.toString()).commit();
    }

    /** Deleted here: the next backups carry it as deleted. */
    static synchronized void removed(Context context, String key) {
        mark(context, key, "removed", "revived");
    }

    /** Brought back here from an older restore point: the next backups un-delete it. */
    static synchronized void revived(Context context, String key) {
        mark(context, key, "revived", "removed");
    }

    private static void mark(Context context, String key, String into, String outOf) {
        try {
            JSONObject d = read(context);
            d.getJSONObject(outOf).remove(key);
            d.getJSONObject(into).put(key, new JSONArray().put(System.currentTimeMillis()).put(0));
            write(context, d);
        } catch (Exception e) {
            android.util.Log.w("VaultMarks", "Couldn't mark " + key, e);
        }
    }

    static synchronized Map<String, Long> removedMarks(Context context) {
        Map<String, Long> out = new HashMap<>();
        JSONObject r = read(context).optJSONObject("removed");
        for (Iterator<String> it = r.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray e = r.optJSONArray(k);
            out.put(k, e == null ? 0 : e.optLong(0));
        }
        return out;
    }

    static synchronized Set<String> revivedMarks(Context context) {
        Set<String> out = new HashSet<>();
        for (Iterator<String> it = read(context).optJSONObject("revived").keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /** A backup carrying the marks was written: each counts once more, and goes after three. */
    static synchronized void confirmed(Context context) {
        try {
            JSONObject d = read(context);
            for (String part : new String[] {"removed", "revived"}) {
                JSONObject o = d.getJSONObject(part);
                for (String k : new HashSet<>(keys(o))) {
                    JSONArray e = o.getJSONArray(k);
                    int n = e.optInt(1) + 1;
                    if (n >= CONFIRMATIONS) o.remove(k);
                    else e.put(1, n);
                }
            }
            write(context, d);
        } catch (Exception e) {
            android.util.Log.w("VaultMarks", "Couldn't count a backup", e);
        }
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> out = new HashSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }
}
