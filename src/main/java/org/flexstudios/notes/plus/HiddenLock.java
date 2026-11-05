package org.flexstudios.notes.plus;

import org.json.JSONObject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The lock on hidden notes: the phone's HiddenLock. Its first lock is the main lock (remembered
 * when the gallery is opened here, as the first chat lock is) or the one set on another device;
 * after that it's their own, changed from the hidden view.
 *
 * Stored only as {@code h1:<salt hex>:<hex PBKDF2-HMAC-SHA256(type + ":" + value, salt, 210000, 32)>}
 * (the same on the phone and the web), here and at {@code users/{uid}/hiddenLock} = {type, hash, at};
 * the newest {@code at} wins.
 */
final class HiddenLock {

    private HiddenLock() {}

    private static final String STATE = "hidden_lock", CANDIDATE = "hidden_lock_first";
    static final String INHERITED = "hidden_lock_inherited";
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static JSONObject state() {
        String s = Prefs.get(STATE);
        try {
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static String type() {
        JSONObject s = state();
        return s == null ? null : s.optString("type", null);
    }

    static String hash(String type, String value) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "h1:" + HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(derive(type, value, salt));
    }

    private static byte[] derive(String type, String value, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec((type + ":" + value).toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether {@code stored} ("h1:salt:hash") is this lock's. The same check on the phone and the web. */
    static boolean check(String stored, String type, String value) {
        String[] parts = stored == null ? new String[0] : stored.split(":");
        if (parts.length != 3 || !"h1".equals(parts[0])) return false;
        return MessageDigest.isEqual(derive(type, value, HexFormat.of().parseHex(parts[1])), HexFormat.of().parseHex(parts[2]));
    }

    /** Whether this opens hidden notes. Off the FX thread (slow on purpose). */
    static boolean matches(String type, String value) {
        JSONObject s = state();
        return s != null && type.equals(s.optString("type")) && check(s.optString("hash"), type, value);
    }

    private static void store(String type, String hash, long at) {
        Prefs.put(STATE, new JSONObject().put("type", type).put("hash", hash).put("at", at).toString());
    }

    /** The main lock as just entered (the gallery opened here): hidden notes' first lock, if they have none yet. */
    static void rememberInitial(String type, String value) {
        if (state() != null || Prefs.get(CANDIDATE) != null) return;
        NoteStore.IO.execute(() -> Prefs.put(CANDIDATE, new JSONObject().put("type", type).put("hash", hash(type, value)).toString()));
    }

    /**
     * Before the lock screen (off the FX thread): the account's lock if it's newer; with none
     * anywhere, the main lock remembered here. False when there's no lock to use at all.
     */
    static boolean prepare() {
        pull();
        if (state() != null) return true;
        String c = Prefs.get(CANDIDATE);
        if (c == null) return false;
        JSONObject first = new JSONObject(c);
        store(first.getString("type"), first.getString("hash"), System.currentTimeMillis());
        Prefs.put(INHERITED, "true");
        push();
        return true;
    }

    /** A new lock of their own, sent to the account's other devices. Off the FX thread. */
    static void set(String type, String value) {
        store(type, hash(type, value), System.currentTimeMillis());
        Prefs.put(INHERITED, null);
        push();
    }

    private static void pull() {
        String uid = Account.uid();
        if (uid == null) return;
        try {
            JSONObject r = Firebase.getObject("users/" + uid + "/hiddenLock");
            JSONObject s = state();
            if (r == null || !r.has("hash")) {
                if (s != null) push();
                return;
            }
            long remoteAt = r.optLong("at"), localAt = s == null ? 0 : s.optLong("at");
            if (remoteAt > localAt) {
                store(r.getString("type"), r.getString("hash"), remoteAt);
                Prefs.put(INHERITED, null); // Chosen elsewhere: no suggestion here.
            } else if (remoteAt < localAt) {
                push();
            }
        } catch (Exception ignored) {
            // Offline: the lock kept here still opens them.
        }
    }

    private static void push() {
        String uid = Account.uid();
        JSONObject s = state();
        if (uid == null || s == null) return;
        try {
            Firebase.put("users/" + uid + "/hiddenLock", s);
        } catch (Exception ignored) {
            // Offline: sent the next time it's changed or checked.
        }
    }

    /** "Choose a lock": its kind, then once more to confirm. {@code after} runs once it's saved. */
    static void choose(String title, String message, Runnable after) {
        Ui.Tabs tabs = new Ui.Tabs("m3_primary", "PIN", "Password", "Pattern");
        LockInput[] inputs = new LockInput[3];
        for (int i = 0; i < 3; i++) inputs[i] = new LockInput(LockInput.TYPES[i], null, null);
        javafx.scene.layout.StackPane slot = new javafx.scene.layout.StackPane(inputs[0]);
        slot.setMinHeight(300);
        tabs.selected.addListener((o, a, b) -> slot.getChildren().setAll(inputs[b.intValue()]));
        Ui.DialogBuilder d = Ui.dialog().title(title);
        if (message != null) d.message(message);
        d.content(new javafx.scene.layout.VBox(12, tabs, slot)).width(440)
                .positive("Next", () -> {
                    LockInput first = inputs[tabs.selected.get()];
                    String value = first.value();
                    String problem = LockInput.problem(first.type, value);
                    if (problem != null) {
                        Ui.toast(problem);
                        return;
                    }
                    LockInput again = new LockInput(first.type, "Once more", null);
                    javafx.scene.layout.StackPane confirm = new javafx.scene.layout.StackPane(again);
                    confirm.setMinHeight(LockInput.PATTERN.equals(first.type) ? 300 : 80);
                    Ui.dialog().title("Once more, to confirm").content(confirm).width(440)
                            .positive("Save", () -> {
                                if (!value.equals(again.value())) {
                                    Ui.toast("Those didn't match. Try again.");
                                    return;
                                }
                                NoteStore.load(s -> {
                                    set(first.type, value);
                                    return true;
                                }, ok -> {
                                    Ui.toast("Lock saved");
                                    if (after != null) after.run();
                                });
                            })
                            .negative("Cancel", null).show();
                    again.focus();
                })
                .negative("Cancel", null).show();
        inputs[0].focus();
    }
}
