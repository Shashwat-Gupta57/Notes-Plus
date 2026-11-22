package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What this computer deleted from the vault (items, "album:" / "vault:" + uuid) or brought back
 * from an older restore point, kept until three Drive backups have carried it ({@link #CONFIRMATIONS}),
 * so the deletion reaches the backup and every other device instead of coming back. The phone's
 * VaultMarks and the web's vault-sync.js keep the same rules. File: vault/marks.json,
 * {"removed": {key: [at, n]}, "revived": {key: [at, n]}}.
 */
final class VaultMarks {

    static final int CONFIRMATIONS = 3;

    private VaultMarks() {}

    private static Path file() {
        return VaultCrypto.dir().resolve("marks.json");
    }

    private static JSONObject read() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                JSONObject d = new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
                if (d.optJSONObject("removed") == null) d.put("removed", new JSONObject());
                if (d.optJSONObject("revived") == null) d.put("revived", new JSONObject());
                return d;
            }
        } catch (Exception e) {
            System.getLogger("Vault").log(System.Logger.Level.WARNING, "Marks unreadable; starting afresh", e);
        }
        return new JSONObject().put("removed", new JSONObject()).put("revived", new JSONObject());
    }

    private static void write(JSONObject d) {
        try {
            Path f = file();
            Path tmp = f.resolveSibling("marks.json.part");
            Files.writeString(tmp, d.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.getLogger("Vault").log(System.Logger.Level.WARNING, "Couldn't save marks", e);
        }
    }

    /** Deleted here: the next backups carry it as deleted. */
    static synchronized void removed(String key) {
        JSONObject d = read();
        d.getJSONObject("revived").remove(key);
        d.getJSONObject("removed").put(key, new JSONArray().put(System.currentTimeMillis()).put(0));
        write(d);
    }

    /** Brought back here from an older restore point: the next backups un-delete it. */
    static synchronized void revived(String key) {
        JSONObject d = read();
        d.getJSONObject("removed").remove(key);
        d.getJSONObject("revived").put(key, new JSONArray().put(System.currentTimeMillis()).put(0));
        write(d);
    }

    static synchronized Map<String, Long> removedMarks() {
        Map<String, Long> out = new HashMap<>();
        JSONObject r = read().getJSONObject("removed");
        for (String k : r.keySet()) out.put(k, r.getJSONArray(k).optLong(0));
        return out;
    }

    static synchronized Set<String> revivedMarks() {
        return new HashSet<>(read().getJSONObject("revived").keySet());
    }

    /** A backup carrying the marks was written: each counts once more, and goes after three. */
    static synchronized void confirmed() {
        JSONObject d = read();
        for (String part : new String[] {"removed", "revived"}) {
            JSONObject o = d.getJSONObject(part);
            for (String k : new HashSet<>(o.keySet())) {
                JSONArray e = o.getJSONArray(k);
                int n = e.optInt(1) + 1;
                if (n >= CONFIRMATIONS) o.remove(k);
                else e.put(1, n);
            }
        }
        write(d);
    }
}
