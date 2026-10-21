package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The chat vaults themselves, across every phone of the account.
 *
 * <ul>
 *   <li><b>Locks</b> are stored as salted PBKDF2 hashes ({@link #hashLock}), here and online,
 *       never as the lock itself.</li>
 *   <li><b>Definitions</b> (ID, name, lock, main) sync through {@code chatVaults/{uid}/{ID}}:
 *       the newest edit wins and deletions are tombstones. Two phones that each made a main
 *       vault before meeting settle on the older one; the other's conversations move into it.</li>
 *   <li><b>Moves.</b> A conversation keeps its address online (the other person still writes to
 *       the vault they wrote to), but can live in any of my vaults: {@code chatPlace/{uid}/{chatId}}
 *       says which, so every phone files it in the same vault.</li>
 *   <li><b>One writer.</b> {@link Session}: the phone that has a vault open holds a lease on it;
 *       any other phone that opens it reads only, until the lease is free.</li>
 * </ul>
 */
final class ChatVaults {

    private static final String TAG = "ChatVaults";
    /** The account this phone's chat vaults belong to. Set by the first sync. */
    private static final String PREF_ACCOUNT = "chat_account";
    private static final String PREF_SALT = "chat_lock_salt";
    private static final String PREF_DEVICE = "chat_device_id";
    private static final String HASH_PREFIX = "h1:";
    private static final long TIMEOUT_S = 15;
    /** No 0/O or 1/I: IDs are read aloud and typed. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChatVaults() {}

    // ================================================================= locks

    /** "h1:salt:hash" of a lock, with this phone's salt. Slow on purpose (PBKDF2). */
    static String hashLock(Context context, String type, String value) throws Exception {
        String salt = prefs(context).getString(PREF_SALT, null);
        if (salt == null) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            salt = hex(bytes);
            prefs(context).edit().putString(PREF_SALT, salt).apply();
        }
        return HASH_PREFIX + salt + ":" + derive(salt, type, value);
    }

    @VisibleForTesting
    static String derive(String salt, String type, String value) throws Exception {
        return hex(VaultCrypto.pbkdf2(type + ":" + value, unhex(salt), VaultCrypto.PBKDF2_ITERATIONS));
    }

    /**
     * The vault this lock opens, or null. Each salt costs one derivation, and vaults made on
     * one phone share a salt, so this is usually one. Background thread.
     */
    @Nullable
    static ChatStore.Vault find(Context context, String type, String value) throws Exception {
        return match(AppDatabase.getInstance(context).chatDao().vaults(), type, value, -1);
    }

    /** Whether another vault already opens with this lock (it would hide one of them). */
    static boolean lockTaken(Context context, String type, String value, int exceptId) throws Exception {
        return match(AppDatabase.getInstance(context).chatDao().vaults(), type, value, exceptId) != null;
    }

    @Nullable
    private static ChatStore.Vault match(List<ChatStore.Vault> vaults, String type, String value, int exceptId)
            throws Exception {
        Map<String, String> derived = new HashMap<>();
        for (ChatStore.Vault v : vaults) {
            if (v.id == exceptId || !type.equals(v.lockType) || v.lockValue == null) continue;
            if (!v.lockValue.startsWith(HASH_PREFIX)) {
                if (MessageDigest.isEqual(v.lockValue.getBytes(), value.getBytes())) return v; // Not upgraded yet.
                continue;
            }
            String[] parts = v.lockValue.substring(HASH_PREFIX.length()).split(":");
            if (parts.length != 2) continue;
            String hash = derived.get(parts[0]);
            if (hash == null) {
                hash = derive(parts[0], type, value);
                derived.put(parts[0], hash);
            }
            if (MessageDigest.isEqual(hash.getBytes(), parts[1].getBytes())) return v;
        }
        return null;
    }

    // ============================================================== vaults

    /**
     * Before the chat lock screen: makes sure this phone has the account's vaults, and a main
     * vault at all. A first visit on a phone whose account has none yet creates one with the
     * main gallery vault's lock at that moment; from then on the two are independent.
     */
    static synchronized void ensureMain(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        ChatStore.Dao dao = db.chatDao();
        String uid = Account.uid();
        if (dao.mainVault() == null && uid != null && boundTo(context, uid)) {
            try {
                sync(context, uid);
            } catch (Exception e) {
                Log.w(TAG, "Could not fetch the account's vaults first", e);
            }
        }
        try {
            upgradeLocks(context);
            if (dao.mainVault() != null) return;
            VaultEntity first = db.vaultDao().getMainVault();
            if (first == null) return;
            ChatStore.Vault main = new ChatStore.Vault();
            main.name = "Main";
            main.lockType = first.getLockType();
            main.lockValue = hashLock(context, first.getLockType(), first.getLockValue());
            main.code = newCode(dao);
            main.isMain = true;
            main.createdAt = main.updatedAt = System.currentTimeMillis();
            dao.insertVault(main);
            prefs(context).edit().putBoolean(ChatSync.PREF_INHERITED_LOCK, true).apply();
        } catch (Exception e) {
            Log.e(TAG, "Could not prepare the main chat vault", e);
        }
    }

    /** Locks stored plainly by an earlier version become hashes. */
    private static void upgradeLocks(Context context) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        for (ChatStore.Vault v : dao.vaults()) {
            if (v.lockValue == null || v.lockValue.startsWith(HASH_PREFIX)) continue;
            v.lockValue = hashLock(context, v.lockType, v.lockValue);
            touch(v);
            dao.updateVault(v);
        }
    }

    static String newCode(ChatStore.Dao dao) {
        while (true) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 4; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            if (dao.vaultByCodeAny(code.toString()) == null) return code.toString();
        }
    }

    /** Marks a local edit, to be pushed by the next sync. */
    static void touch(ChatStore.Vault v) {
        v.updatedAt = Math.max(System.currentTimeMillis(), v.updatedAt + 1);
        v.dirty = true;
    }

    static void save(Context context, ChatStore.Vault v) {
        touch(v);
        AppDatabase.getInstance(context).chatDao().updateVault(v);
        ChatSync.schedule(context);
    }

    /** New vault with its own lock and a fresh ID. Blocking. */
    static ChatStore.Vault create(Context context, String name, String type, String value) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Vault v = new ChatStore.Vault();
        v.name = name;
        v.lockType = type;
        v.lockValue = hashLock(context, type, value);
        v.code = newCode(dao);
        v.createdAt = v.updatedAt = System.currentTimeMillis();
        v.id = (int) dao.insertVault(v);
        ChatSync.schedule(context);
        return v;
    }

    /** This vault's own profile photo (null: the account's photo shows). Blocking. */
    static void setPhoto(Context context, ChatStore.Vault v, @Nullable ChatMedia.Meta photo) {
        v.photo = photo == null ? null : photo.toJson().toString();
        save(context, v);
    }

    /** Deletes a (non-main) vault everywhere: a tombstone online, its conversations here. */
    static void delete(Context context, ChatStore.Vault v) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        dao.deleteMessagesOfVault(v.id);
        dao.deleteConversationsOf(v.id);
        v.deleted = true;
        save(context, v);
    }

    /**
     * Gives a vault a new ID. The old ID is retired (a tombstone, so it can't be reused), and
     * its conversations stay in this vault: online they keep their old address, so each is
     * recorded as moved here.
     */
    static void changeCode(Context context, ChatStore.Vault v, String code) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Vault retired = new ChatStore.Vault();
        retired.name = v.name;
        retired.lockType = v.lockType;
        retired.code = v.code;
        retired.createdAt = v.createdAt;
        retired.deleted = true;
        retired.renamedTo = code;
        retired.photo = null; // Pushing the tombstone clears the photo under the old ID.
        touch(retired);
        v.code = code;
        save(context, v);
        dao.insertVault(retired);
        for (ChatStore.Conversation c : dao.conversationsIn(v.id)) {
            c.moved = true;
            c.shownAs = code; // The other person's phone shows the new ID.
            dao.updateConversation(c);
        }
        ChatSync.schedule(context);
    }

    /** Files a conversation under another vault, here and (next sync) on every phone. */
    static void move(Context context, ChatStore.Conversation c, ChatStore.Vault target) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        c.vaultId = target.id;
        c.moved = true;
        dao.updateConversation(c);
        ChatSync.schedule(context);
    }

    /**
     * The live vault an address reaches: the vault with that ID, or the one a retired ID
     * became (following renames). Null if it was deleted. Blocking.
     */
    @Nullable
    static ChatStore.Vault reached(ChatStore.Dao dao, @Nullable String code) {
        for (int hops = 0; code != null && hops < 8; hops++) {
            ChatStore.Vault v = dao.vaultByCodeAny(code);
            if (v == null) return null;
            if (!v.deleted) return v;
            code = v.renamedTo;
        }
        return null;
    }

    /** My vault ID inside a conversation key ({@code uidA_IDA_uidB_IDB}): its address. */
    @Nullable
    @VisibleForTesting
    static String addressOf(String chatId, String uid) {
        String[] p = chatId.split("_");
        if (p.length != 4) return null;
        return uid.equals(p[0]) ? p[1] : uid.equals(p[2]) ? p[3] : null;
    }

    // ================================================================ sync

    /**
     * The chat vaults on this phone belong to one account. Signing in with another one must
     * not hand them over, so chat stays local until the original account is back.
     */
    static boolean boundTo(Context context, String uid) {
        SharedPreferences prefs = prefs(context);
        String bound = prefs.getString(PREF_ACCOUNT, null);
        if (bound == null) {
            prefs.edit().putString(PREF_ACCOUNT, uid).apply();
            return true;
        }
        return bound.equals(uid);
    }

    /** Vault definitions both ways, one main vault, and recorded moves out. Blocking. */
    static synchronized void sync(Context context, String uid) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        upgradeLocks(context); // A lock only ever leaves this phone as a hash.
        DatabaseReference mine = Account.root().child("chatVaults").child(uid);
        for (DataSnapshot r : await(mine.get()).getChildren()) {
            String code = r.getKey();
            Long updatedAt = r.child("updatedAt").getValue(Long.class);
            if (code == null || updatedAt == null) continue;
            ChatStore.Vault local = dao.vaultByCodeAny(code);
            if (local != null && local.updatedAt >= updatedAt) continue; // Ours is newer, or the same.
            boolean deleted = Boolean.TRUE.equals(r.child("deleted").getValue(Boolean.class));
            if (local == null && deleted) continue;
            ChatStore.Vault v = local != null ? local : new ChatStore.Vault();
            v.code = code;
            v.name = r.child("name").getValue(String.class);
            v.lockType = r.child("lockType").getValue(String.class);
            v.lockValue = r.child("lock").getValue(String.class);
            v.isMain = Boolean.TRUE.equals(r.child("main").getValue(Boolean.class));
            v.renamedTo = r.child("renamedTo").getValue(String.class);
            v.photo = ChatSync.jsonOf(r.child("photo").getValue());
            Long createdAt = r.child("createdAt").getValue(Long.class);
            v.createdAt = createdAt == null ? updatedAt : createdAt;
            v.updatedAt = updatedAt;
            v.dirty = false;
            if (deleted && !v.deleted) {
                dao.deleteMessagesOfVault(v.id);
                dao.deleteConversationsOf(v.id);
            }
            v.deleted = deleted;
            if (local == null) dao.insertVault(v);
            else dao.updateVault(v);
        }
        settleMain(dao);

        for (ChatStore.Vault v : dao.dirtyVaults()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", v.name == null ? "" : v.name);
            if (v.lockType != null) m.put("lockType", v.lockType);
            if (v.lockValue != null && v.lockValue.startsWith(HASH_PREFIX)) m.put("lock", v.lockValue);
            m.put("main", v.isMain);
            m.put("createdAt", v.createdAt);
            m.put("updatedAt", v.updatedAt);
            if (v.deleted) m.put("deleted", true);
            if (v.renamedTo != null) m.put("renamedTo", v.renamedTo);
            ChatMedia.Meta photo = v.deleted ? null : ChatMedia.Meta.parse(v.photo);
            if (photo != null) m.put("photo", ChatSync.toMap(photo.toJson()));
            await(mine.child(v.code).setValue(m));
            // What people talking to this vault see; a retired ID shows nothing any more.
            await(Account.root().child("vaultPhotos").child(uid).child(v.code)
                    .setValue(photo == null ? null : ChatSync.toMap(photo.toJson())));
            v.dirty = false;
            dao.updateVault(v);
        }

        Map<String, Object> places = new HashMap<>();
        List<ChatStore.Conversation> moved = dao.movedConversations();
        for (ChatStore.Conversation c : moved) {
            ChatStore.Vault home = dao.vault(c.vaultId);
            String address = addressOf(c.chatId, uid);
            if (home == null) continue;
            places.put(c.chatId, home.code.equals(address) ? null : home.code);
        }
        if (!places.isEmpty()) await(Account.root().child("chatPlace").child(uid).updateChildren(places));
        // A vault whose ID changed tells each conversation's other side its new ID to show.
        Map<String, Object> aliases = new HashMap<>();
        for (ChatStore.Conversation c : moved) {
            if (c.established && c.shownAs != null) aliases.put("chats/" + c.chatId + "/alias/" + uid, c.shownAs);
        }
        if (!aliases.isEmpty()) await(Account.root().updateChildren(aliases));
        for (ChatStore.Conversation c : moved) {
            c.moved = false;
            dao.updateConversation(c);
        }
    }

    /**
     * Exactly one main vault. If two phones each made one before they met, the older wins and
     * the other's conversations move into it; with none left, the oldest vault becomes main.
     */
    private static void settleMain(ChatStore.Dao dao) {
        ChatStore.Vault keep = null;
        List<ChatStore.Vault> extra = new ArrayList<>();
        for (ChatStore.Vault v : dao.vaults()) {
            if (!v.isMain) continue;
            if (keep == null || v.createdAt < keep.createdAt
                    || (v.createdAt == keep.createdAt && v.code.compareTo(keep.code) < 0)) {
                if (keep != null) extra.add(keep);
                keep = v;
            } else {
                extra.add(v);
            }
        }
        if (keep == null) {
            List<ChatStore.Vault> all = dao.vaults();
            if (all.isEmpty()) return;
            keep = all.get(0);
            for (ChatStore.Vault v : all) if (v.createdAt < keep.createdAt) keep = v;
            keep.isMain = true;
            touch(keep);
            dao.updateVault(keep);
        }
        for (ChatStore.Vault v : extra) {
            for (ChatStore.Conversation c : dao.conversationsIn(v.id)) {
                c.vaultId = keep.id;
                c.moved = true;
                dao.updateConversation(c);
            }
            v.isMain = false;
            v.deleted = true;
            touch(v);
            dao.updateVault(v);
        }
    }

    /** Which of my vaults each conversation is filed under, where that isn't its address. */
    static Map<String, String> placements(String uid) throws Exception {
        Map<String, String> out = new HashMap<>();
        for (DataSnapshot p : await(Account.root().child("chatPlace").child(uid).get()).getChildren()) {
            String code = p.getValue(String.class);
            if (p.getKey() != null && code != null) out.put(p.getKey(), code);
        }
        return out;
    }

    // ============================================================= session

    /**
     * One writer per vault across the account's phones. The phone showing a vault holds a lease
     * ({@code chatSessions/{uid}/{ID}}) and renews it every 15 s; a phone that gets there second
     * reads only, and takes over by itself once the lease is released or 45 s stale (a phone
     * that crashed, or lost its connection). Offline, a phone writes: messages only queue then.
     */
    static final class Session {

        interface Listener {
            /** Main thread. {@code holder} names the phone that has it, when read-only. */
            void onChange(boolean writable, @Nullable String holder);
        }

        private static final long LEASE_MS = 45_000, RENEW_MS = 15_000, RETRY_MS = 5_000, RELEASE_DELAY_MS = 2_000;
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final List<Listener> listeners = new ArrayList<>();
        private static Context app;
        private static String code;
        private static boolean writable = true;
        private static String holder;
        private static long serverOffset;
        private static boolean offsetWatched;
        private static final Runnable TICK = Session::tick;
        private static final Runnable RELEASE = Session::release;

        private Session() {}

        /** A chat screen of vault {@code vaultCode} came on screen. Main thread. */
        static void enter(Context context, String vaultCode, Listener listener) {
            app = context.getApplicationContext();
            MAIN.removeCallbacks(RELEASE);
            if (!vaultCode.equals(code)) {
                release();
                code = vaultCode;
                writable = true;
                holder = null;
            }
            if (!listeners.contains(listener)) listeners.add(listener);
            listener.onChange(writable, holder);
            watchOffset();
            MAIN.removeCallbacks(TICK);
            tick();
        }

        /** It went off screen. The lease goes a moment after the last one leaves. */
        static void leave(Listener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                MAIN.removeCallbacks(TICK);
                MAIN.postDelayed(RELEASE, RELEASE_DELAY_MS);
            }
        }

        static boolean writable() {
            return writable;
        }

        private static void watchOffset() {
            if (offsetWatched) return;
            offsetWatched = true;
            com.google.firebase.database.FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
                    .addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            Long offset = snapshot.getValue(Long.class);
                            serverOffset = offset == null ? 0 : offset;
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {}
                    });
        }

        @Nullable
        private static DatabaseReference ref() {
            String uid = Account.uid();
            return uid == null || code == null ? null
                    : Account.root().child("chatSessions").child(uid).child(code);
        }

        private static void tick() {
            DatabaseReference ref = ref();
            if (ref == null) {
                set(true, null); // Not signed in: nothing to share it with.
                return;
            }
            String me = deviceId(app);
            String myName = Build.MODEL == null ? "another phone" : Build.MODEL;
            ref.runTransaction(new Transaction.Handler() {
                @NonNull
                @Override
                public Transaction.Result doTransaction(@NonNull MutableData current) {
                    String device = current.child("device").getValue(String.class);
                    Long at = current.child("at").getValue(Long.class);
                    long now = System.currentTimeMillis() + serverOffset;
                    boolean free = device == null || device.equals(me) || at == null || now - at > LEASE_MS;
                    if (!free) return Transaction.abort();
                    Map<String, Object> lease = new HashMap<>();
                    lease.put("device", me);
                    lease.put("name", myName.length() > 64 ? myName.substring(0, 64) : myName);
                    lease.put("at", ServerValue.TIMESTAMP);
                    current.setValue(lease);
                    return Transaction.success(current);
                }

                @Override
                public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot snapshot) {
                    if (error != null) {
                        set(true, null); // Can't reach the database: typing only queues anyway.
                    } else if (committed) {
                        set(true, null);
                    } else {
                        String name = snapshot == null ? null : snapshot.child("name").getValue(String.class);
                        set(false, name == null ? "another phone" : name);
                    }
                    if (!listeners.isEmpty()) {
                        MAIN.removeCallbacks(TICK);
                        MAIN.postDelayed(TICK, writable ? RENEW_MS : RETRY_MS);
                    }
                }
            }, false);
        }

        private static void set(boolean canWrite, @Nullable String by) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN.post(() -> set(canWrite, by));
                return;
            }
            boolean changed = canWrite != writable || !java.util.Objects.equals(by, holder);
            writable = canWrite;
            holder = by;
            if (changed) for (Listener l : new ArrayList<>(listeners)) l.onChange(writable, holder);
        }

        /** Gives the lease back, if it's ours. */
        private static void release() {
            DatabaseReference ref = ref();
            if (ref == null || !writable || app == null) return;
            String me = deviceId(app);
            ref.runTransaction(new Transaction.Handler() {
                @NonNull
                @Override
                public Transaction.Result doTransaction(@NonNull MutableData current) {
                    if (!me.equals(current.child("device").getValue(String.class))) return Transaction.abort();
                    current.setValue(null);
                    return Transaction.success(current);
                }

                @Override
                public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot snapshot) {}
            }, false);
        }
    }

    // ============================================================= helpers

    static String deviceId(Context context) {
        SharedPreferences prefs = prefs(context);
        String id = prefs.getString(PREF_DEVICE, null);
        if (id == null) {
            byte[] bytes = new byte[12];
            RANDOM.nextBytes(bytes);
            id = hex(bytes);
            prefs.edit().putString(PREF_DEVICE, id).apply();
        }
        return id;
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static <T> T await(com.google.android.gms.tasks.Task<T> task) throws Exception {
        return Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static SharedPreferences prefs(Context context) {
        return SecurityHelper.getAppPrefs(context);
    }
}
