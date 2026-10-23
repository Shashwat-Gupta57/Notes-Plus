package org.flexstudios.notes.plus;

import javafx.application.Platform;
import org.json.JSONObject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The chat vaults themselves, across every device of the account: the phone's ChatVaults.
 *
 * <ul>
 *   <li><b>Locks</b> are salted PBKDF2 hashes ({@code h1:salt:hash}, the phone's exact scheme),
 *       here and online, so a lock set on the phone opens the same vault here.</li>
 *   <li><b>Definitions</b> sync through {@code chatVaults/{uid}/{ID}}: newest edit wins,
 *       deletions and retired IDs are tombstones, and exactly one vault is main.</li>
 *   <li><b>Moves</b>: {@code chatPlace/{uid}/{chatId}} files a conversation under another vault.</li>
 *   <li><b>One writer</b> ({@link Session}): the device showing a vault holds a lease; any other
 *       reads only until it is free.</li>
 * </ul>
 */
final class ChatVaults {

    private ChatVaults() {}

    private static final String PREF_ACCOUNT = "chat_account", PREF_SALT = "chat_lock_salt", PREF_DEVICE = "chat_device_id";
    /** The lock the first chat vault starts with: the gallery's main lock when it was set ({type, hash}). */
    static final String PREF_INITIAL_LOCK = "chat_initial_lock";
    private static final String HASH_PREFIX = "h1:";
    /** No 0/O or 1/I: IDs are read aloud and typed. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // ================================================================= locks

    /** "h1:salt:hash" of a lock, with this device's salt. Slow on purpose. */
    static String hashLock(String type, String value) {
        String salt = Prefs.get(PREF_SALT);
        if (salt == null) {
            byte[] b = new byte[16];
            RANDOM.nextBytes(b);
            salt = HexFormat.of().formatHex(b);
            Prefs.put(PREF_SALT, salt);
        }
        return HASH_PREFIX + salt + ":" + derive(salt, type, value);
    }

    /** The phone's derivation: PBKDF2-SHA256(type + ":" + value, salt, 210,000), 32 bytes, hex. */
    static String derive(String salt, String type, String value) {
        try {
            PBEKeySpec spec = new PBEKeySpec((type + ":" + value).toCharArray(), HexFormat.of().parseHex(salt), 210_000, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The vault this lock opens, or null. One derivation per salt. Background thread. */
    static ChatStore.Vault find(String type, String value) {
        return match(ChatStore.get().vaults(), type, value, -1);
    }

    /** Whether another vault already opens with this lock (it would hide one of them). */
    static boolean lockTaken(String type, String value, int exceptId) {
        return match(ChatStore.get().vaults(), type, value, exceptId) != null;
    }

    private static ChatStore.Vault match(List<ChatStore.Vault> vaults, String type, String value, int exceptId) {
        Map<String, String> derived = new HashMap<>();
        for (ChatStore.Vault v : vaults) {
            if (v.id == exceptId || !type.equals(v.lockType) || v.lockValue == null || !v.lockValue.startsWith(HASH_PREFIX)) continue;
            String[] parts = v.lockValue.substring(HASH_PREFIX.length()).split(":");
            if (parts.length != 2) continue;
            String hash = derived.computeIfAbsent(parts[0], salt -> derive(salt, type, value));
            if (MessageDigest.isEqual(hash.getBytes(), parts[1].getBytes())) return v;
        }
        return null;
    }

    /**
     * Remembers the gallery's main lock as the chat space's starting lock, as the phone's chat
     * vault starts with it. Only until the chat space exists; then the two are independent.
     */
    static void rememberInitialLock(String type, String value) {
        ChatStore.IO.execute(() -> {
            if (ChatStore.get().mainVault() != null) return;
            Prefs.put(PREF_INITIAL_LOCK, new JSONObject().put("type", type).put("hash", hashLock(type, value)).toString());
        });
    }

    // ================================================================ vaults

    /**
     * Before the chat lock screen: the account's vaults first (bounded, so an offline device
     * isn't held up), then a main vault if there is none yet. Blocking.
     */
    static void ensureMain() {
        String uid = Account.uid();
        if (uid != null && boundTo(uid)) {
            Thread fetch = new Thread(() -> {
                try {
                    sync(uid);
                } catch (Exception e) {
                    System.getLogger("Chat").log(System.Logger.Level.WARNING, "Could not fetch the account's vaults first", e);
                }
            }, "chat-vault-sync");
            fetch.setDaemon(true);
            fetch.start();
            try {
                fetch.join(ChatStore.get().mainVault() == null ? 10_000 : 4_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        createMainIfMissing();
    }

    private static synchronized void createMainIfMissing() {
        ChatStore store = ChatStore.get();
        if (store.mainVault() != null) return;
        String initial = Prefs.get(PREF_INITIAL_LOCK);
        if (initial == null) return;
        JSONObject lock = new JSONObject(initial);
        ChatStore.Vault main = new ChatStore.Vault();
        main.name = "Main";
        main.lockType = lock.getString("type");
        main.lockValue = lock.getString("hash");
        main.code = newCode();
        main.isMain = true;
        main.createdAt = main.updatedAt = System.currentTimeMillis();
        store.insertVault(main);
        Prefs.put(PREF_INITIAL_LOCK, null);
        Prefs.put(ChatSync.PREF_INHERITED_LOCK, "true");
    }

    /** The first chat vault, with a lock chosen for it. Blocking. */
    static synchronized ChatStore.Vault createMain(String type, String value) {
        ChatStore store = ChatStore.get();
        ChatStore.Vault main = store.mainVault();
        if (main != null) return main;
        main = create("Main", type, value);
        main.isMain = true;
        store.updateVault(main);
        Prefs.put(PREF_INITIAL_LOCK, null);
        return main;
    }

    static String newCode() {
        while (true) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 4; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            if (ChatStore.get().vaultByCodeAny(code.toString()) == null) return code.toString();
        }
    }

    /** Marks a local edit, to be pushed by the next sync. */
    static void touch(ChatStore.Vault v) {
        v.updatedAt = Math.max(System.currentTimeMillis(), v.updatedAt + 1);
        v.dirty = true;
    }

    static void save(ChatStore.Vault v) {
        touch(v);
        ChatStore.get().updateVault(v);
        ChatSync.schedule();
    }

    /** New vault with its own lock and a fresh ID. Blocking. */
    static ChatStore.Vault create(String name, String type, String value) {
        ChatStore.Vault v = new ChatStore.Vault();
        v.name = name;
        v.lockType = type;
        v.lockValue = hashLock(type, value);
        v.code = newCode();
        v.createdAt = v.updatedAt = System.currentTimeMillis();
        ChatStore.get().insertVault(v);
        ChatSync.schedule();
        return v;
    }

    static void setPhoto(ChatStore.Vault v, ChatMedia.Meta photo) {
        v.photo = photo == null ? null : photo.toJson().toString();
        save(v);
    }

    /** Deletes a (non-main) vault everywhere: a tombstone online, its conversations here. */
    static void delete(ChatStore.Vault v) {
        ChatStore.get().deleteConversationsOf(v.id);
        v.deleted = true;
        save(v);
    }

    /**
     * Gives a vault a new ID. The old one is retired (a tombstone pointing at the new one, so
     * messages to it still arrive), and its conversations are recorded as moved here.
     */
    static void changeCode(ChatStore.Vault v, String code) {
        ChatStore store = ChatStore.get();
        ChatStore.Vault retired = new ChatStore.Vault();
        retired.name = v.name;
        retired.lockType = v.lockType;
        retired.code = v.code;
        retired.createdAt = v.createdAt;
        retired.deleted = true;
        retired.renamedTo = code;
        touch(retired);
        v.code = code;
        save(v);
        store.insertVault(retired);
        for (ChatStore.Conversation c : store.conversationsIn(v.id)) {
            c.moved = true;
            c.shownAs = code;
            store.updateConversation(c);
        }
        ChatSync.schedule();
    }

    static void move(ChatStore.Conversation c, ChatStore.Vault target) {
        c.vaultId = target.id;
        c.moved = true;
        ChatStore.get().updateConversation(c);
        ChatSync.schedule();
    }

    /** The live vault an address reaches, following renames. Null if deleted. */
    static ChatStore.Vault reached(String code) {
        for (int hops = 0; code != null && hops < 8; hops++) {
            ChatStore.Vault v = ChatStore.get().vaultByCodeAny(code);
            if (v == null) return null;
            if (!v.deleted) return v;
            code = v.renamedTo;
        }
        return null;
    }

    /** My vault ID inside a conversation key ({@code uidA_IDA_uidB_IDB}). */
    static String addressOf(String chatId, String uid) {
        String[] p = chatId.split("_");
        if (p.length != 4) return null;
        return uid.equals(p[0]) ? p[1] : uid.equals(p[2]) ? p[3] : null;
    }

    // ================================================================== sync

    /** This device's chat vaults belong to the first account they synced with. */
    static boolean boundTo(String uid) {
        String bound = Prefs.get(PREF_ACCOUNT);
        if (bound == null) {
            Prefs.put(PREF_ACCOUNT, uid);
            return true;
        }
        return bound.equals(uid);
    }

    /** Vault definitions both ways, one main vault, and recorded moves. Blocking. */
    static synchronized void sync(String uid) throws Exception {
        ChatStore store = ChatStore.get();
        JSONObject mine = Firebase.getObject("chatVaults/" + uid);
        if (mine != null) {
            for (String code : mine.keySet()) {
                JSONObject r = mine.optJSONObject(code);
                if (r == null || !r.has("updatedAt")) continue;
                long updatedAt = r.optLong("updatedAt");
                ChatStore.Vault local = store.vaultByCodeAny(code);
                if (local != null && local.updatedAt >= updatedAt) continue;
                boolean deleted = r.optBoolean("deleted");
                if (local == null && deleted) continue;
                ChatStore.Vault v = local != null ? local : new ChatStore.Vault();
                v.code = code;
                v.name = r.optString("name", null);
                v.lockType = r.optString("lockType", null);
                v.lockValue = r.optString("lock", null);
                v.isMain = r.optBoolean("main");
                v.renamedTo = r.optString("renamedTo", null);
                JSONObject photo = r.optJSONObject("photo");
                v.photo = photo == null ? null : photo.toString();
                v.createdAt = r.optLong("createdAt", updatedAt);
                v.updatedAt = updatedAt;
                v.dirty = false;
                if (deleted && !v.deleted && v.id > 0) store.deleteConversationsOf(v.id);
                v.deleted = deleted;
                if (local == null) store.insertVault(v);
                else store.updateVault(v);
            }
        }
        settleMain(store);

        for (ChatStore.Vault v : store.dirtyVaults()) {
            JSONObject m = new JSONObject().put("name", v.name == null ? "" : v.name).put("main", v.isMain)
                    .put("createdAt", v.createdAt).put("updatedAt", v.updatedAt);
            if (v.lockType != null) m.put("lockType", v.lockType);
            if (v.lockValue != null && v.lockValue.startsWith(HASH_PREFIX)) m.put("lock", v.lockValue);
            if (v.deleted) m.put("deleted", true);
            if (v.renamedTo != null) m.put("renamedTo", v.renamedTo);
            ChatMedia.Meta photo = v.deleted ? null : ChatMedia.Meta.parse(v.photo);
            if (photo != null) m.put("photo", photo.toJson());
            Firebase.put("chatVaults/" + uid + "/" + v.code, m);
            Firebase.put("vaultPhotos/" + uid + "/" + v.code, photo == null ? JSONObject.NULL : photo.toJson());
            v.dirty = false;
            store.updateVault(v);
        }

        JSONObject places = new JSONObject();
        List<ChatStore.Conversation> moved = store.movedConversations();
        for (ChatStore.Conversation c : moved) {
            ChatStore.Vault home = store.vault(c.vaultId);
            String address = addressOf(c.chatId, uid);
            if (home == null) continue;
            places.put(c.chatId, home.code.equals(address) ? JSONObject.NULL : home.code);
        }
        if (!places.isEmpty()) Firebase.patch("chatPlace/" + uid, places);
        JSONObject aliases = new JSONObject();
        for (ChatStore.Conversation c : moved) {
            if (c.established && c.shownAs != null) aliases.put(ChatSync.path(c.chatId) + "/alias/" + uid, c.shownAs);
        }
        if (!aliases.isEmpty()) Firebase.patch("", aliases);
        for (ChatStore.Conversation c : moved) {
            c.moved = false;
            store.updateConversation(c);
        }
    }

    /** Exactly one main vault: the older wins, the other's conversations move into it. */
    private static void settleMain(ChatStore store) {
        ChatStore.Vault keep = null;
        List<ChatStore.Vault> extra = new ArrayList<>();
        for (ChatStore.Vault v : store.vaults()) {
            if (!v.isMain) continue;
            if (keep == null || v.createdAt < keep.createdAt || (v.createdAt == keep.createdAt && v.code.compareTo(keep.code) < 0)) {
                if (keep != null) extra.add(keep);
                keep = v;
            } else {
                extra.add(v);
            }
        }
        if (keep == null) {
            List<ChatStore.Vault> all = store.vaults();
            if (all.isEmpty()) return;
            keep = all.get(0);
            for (ChatStore.Vault v : all) if (v.createdAt < keep.createdAt) keep = v;
            keep.isMain = true;
            touch(keep);
            store.updateVault(keep);
        }
        for (ChatStore.Vault v : extra) {
            for (ChatStore.Conversation c : store.conversationsIn(v.id)) {
                c.vaultId = keep.id;
                c.moved = true;
                store.updateConversation(c);
            }
            v.isMain = false;
            v.deleted = true;
            touch(v);
            store.updateVault(v);
        }
    }

    /** Which of my vaults each conversation is filed under, where that isn't its address. */
    static Map<String, String> placements(String uid) throws Exception {
        Map<String, String> out = new HashMap<>();
        JSONObject p = Firebase.getObject("chatPlace/" + uid);
        if (p != null) for (String k : p.keySet()) if (p.opt(k) instanceof String code) out.put(k, code);
        return out;
    }

    static String deviceId() {
        String id = Prefs.get(PREF_DEVICE);
        if (id == null) {
            byte[] b = new byte[12];
            RANDOM.nextBytes(b);
            id = HexFormat.of().formatHex(b);
            Prefs.put(PREF_DEVICE, id);
        }
        return id;
    }

    // =============================================================== session

    /**
     * One writer per vault across the account's devices: the one showing a vault holds a lease
     * ({@code chatSessions/{uid}/{ID}}), renewed every 15 s; another reads only, and takes over
     * once the lease is released or 45 s stale. Offline, a device writes (messages just queue).
     */
    static final class Session {

        interface Listener {
            /** FX thread. {@code holder} names the device that has it, when read-only. */
            void onChange(boolean writable, String holder);
        }

        private static final long LEASE_MS = 45_000, RENEW_MS = 15_000, RETRY_MS = 5_000, RELEASE_DELAY_MS = 2_000;
        private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-session");
            t.setDaemon(true);
            return t;
        });
        private static final List<Listener> listeners = new ArrayList<>();
        private static String code;
        private static boolean writable = true;
        private static String holder;
        private static ScheduledFuture<?> next, release;

        private Session() {}

        /** A chat screen of vault {@code vaultCode} came on screen. FX thread. */
        static synchronized void enter(String vaultCode, Listener listener) {
            if (release != null) release.cancel(false);
            if (!vaultCode.equals(code)) {
                releaseNow();
                code = vaultCode;
                writable = true;
                holder = null;
            }
            if (!listeners.contains(listener)) listeners.add(listener);
            listener.onChange(writable, holder);
            reschedule(0);
        }

        static synchronized void leave(Listener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                if (next != null) next.cancel(false);
                release = TIMER.schedule(Session::releaseNow, RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }

        static boolean writable() {
            return writable;
        }

        private static void reschedule(long delay) {
            if (next != null) next.cancel(false);
            next = TIMER.schedule(Session::tick, delay, TimeUnit.MILLISECONDS);
        }

        private static void tick() {
            String uid = Account.uid();
            String c;
            synchronized (Session.class) {
                c = code;
            }
            if (uid == null || c == null) {
                set(true, null);
                return;
            }
            String path = "chatSessions/" + uid + "/" + c;
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    Firebase.Tagged now = Firebase.getTagged(path);
                    JSONObject lease = now.value() instanceof JSONObject o ? o : null;
                    String device = lease == null ? null : lease.optString("device", null);
                    long at = lease == null ? 0 : lease.optLong("at");
                    boolean free = device == null || device.equals(deviceId()) || System.currentTimeMillis() - at > LEASE_MS;
                    if (!free) {
                        set(false, lease.optString("name", "another device"));
                        break;
                    }
                    JSONObject mine = new JSONObject().put("device", deviceId()).put("name", NoteSync.device())
                            .put("at", Firebase.now());
                    if (Firebase.putIf(path, mine, now.etag()) == null) {
                        set(true, null);
                        break;
                    }
                }
            } catch (Exception e) {
                set(true, null); // Can't reach the database: sending only queues anyway.
            }
            synchronized (Session.class) {
                if (!listeners.isEmpty()) reschedule(writable ? RENEW_MS : RETRY_MS);
            }
        }

        private static void set(boolean canWrite, String by) {
            boolean changed;
            List<Listener> copy;
            synchronized (Session.class) {
                changed = canWrite != writable || !java.util.Objects.equals(by, holder);
                writable = canWrite;
                holder = by;
                copy = new ArrayList<>(listeners);
            }
            if (changed) Platform.runLater(() -> copy.forEach(l -> l.onChange(canWrite, by)));
        }

        /** Gives the lease back, if it's ours. */
        private static void releaseNow() {
            String uid = Account.uid();
            String c;
            synchronized (Session.class) {
                c = code;
            }
            if (uid == null || c == null || !writable) return;
            try {
                String path = "chatSessions/" + uid + "/" + c;
                Firebase.Tagged now = Firebase.getTagged(path);
                if (now.value() instanceof JSONObject o && deviceId().equals(o.optString("device"))) {
                    Firebase.putIf(path, JSONObject.NULL, now.etag());
                }
            } catch (Exception ignored) {
                // It goes stale by itself.
            }
        }
    }
}
