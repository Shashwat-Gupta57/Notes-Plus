package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Moves chat messages between this phone and the Realtime Database.
 *
 * <p><b>Addressing.</b> Every chat vault has a 4-character ID. A conversation joins one person's
 * vault with another's: {@code chats/{chatId}} where the id is both {@code uid_ID} endpoints,
 * sorted, so both sides compute the same one. An empty ID means "their main vault", resolved
 * through {@code chatHome/{uid}} before anything is sent.
 *
 * <p><b>Delivery.</b> A message is written here first (waiting), then sent by a worker that
 * survives the app closing. The recipient's phone marks it {@code received} when it fetches it
 * and {@code seen} when the conversation is on screen. {@code chatIndex/{uid}} tells each phone
 * which conversations have news, and its {@code at} changes with every message.
 *
 * <p><b>Size.</b> Only the newest {@value #KEEP} messages of a conversation stay online; the
 * sender trims the oldest after each send. Phones keep their full history locally.
 *
 * <p>No notifications, ever: a chat arriving must not announce that the chat space exists.
 */
final class ChatSync {

    private static final String TAG = "ChatSync";
    static final String UNIQUE = "np-chat-sync";
    private static final String UNIQUE_PERIODIC = "np-chat-pull";
    private static final String PREF_HOME = "chat_home_published_";
    /** Set when the main chat vault was created with the lock it started with. */
    static final String PREF_INHERITED_LOCK = "chat_lock_inherited";
    static final int KEEP = 100;
    /** Members of a group chat, the creator included. */
    static final int MAX_GROUP = 20;
    /** The last run was refused by the server at every step. */
    static final String PREF_REFUSED = "chat_server_refused";
    static final int MAX_TEXT = 4000;
    private static final long TIMEOUT_S = 30;
    private static final ReentrantLock LOCK = new ReentrantLock();
    /** For Drive clean-up from inside apply(). Set by the first sync or live update. */
    private static volatile Context appContext;

    private ChatSync() {}

    // ============================================================ addressing

    /** Upper-cases a typed vault ID; "" stays "" (main vault), anything malformed is null. */
    @Nullable
    @VisibleForTesting
    static String normaliseCode(@Nullable String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) return "";
        return code.matches("^[A-Z0-9]{4}$") ? code : null;
    }

    /** Group chats are "g_" + their id; everything else is a two-person conversation. */
    static boolean isGroup(String chatId) {
        return chatId.startsWith("g_");
    }

    /** Where a conversation lives online. */
    static String path(String chatId) {
        return isGroup(chatId) ? "groupChats/" + chatId.substring(2) : "chats/" + chatId;
    }

    static DatabaseReference ref(String chatId) {
        return Account.root().child(path(chatId));
    }

    /** Both endpoints, sorted: each side of a conversation computes the same key. */
    @VisibleForTesting
    static String chatId(String uidA, String codeA, String uidB, String codeB) {
        String a = uidA + "_" + codeA, b = uidB + "_" + codeB;
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ========================================================== addressing

    static final class Contact {
        String uid, name, code;
    }

    /** Finds {@code @username} (and their vault, or their main vault when {@code code} is ""). */
    static void resolve(String username, String code, Consumer<Contact> found, Consumer<String> error) {
        String me = Account.uid();
        String name = Account.normaliseUsername(username);
        String vault = normaliseCode(code);
        if (me == null) {
            error.accept("Sign in to your account first");
            return;
        }
        if (name == null) {
            error.accept("That isn't a username");
            return;
        }
        if (vault == null) {
            error.accept("A vault ID is 4 letters or numbers");
            return;
        }
        Account.root().child("usernames").child(name).get().addOnCompleteListener(t -> {
            String uid = t.isSuccessful() ? t.getResult().getValue(String.class) : null;
            if (!t.isSuccessful()) {
                error.accept("No connection. Try again.");
            } else if (uid == null) {
                error.accept("No one is called @" + name);
            } else if (uid.equals(me)) {
                error.accept("That's you");
            } else if (!vault.isEmpty()) {
                found.accept(contact(uid, name, vault));
            } else {
                Account.root().child("chatHome").child(uid).get().addOnCompleteListener(h -> {
                    String home = h.isSuccessful() ? h.getResult().getValue(String.class) : null;
                    if (!h.isSuccessful()) error.accept("No connection. Try again.");
                    else if (home == null) error.accept("@" + name + " hasn't started chatting yet");
                    else found.accept(contact(uid, name, home));
                });
            }
        });
    }

    /**
     * People for a note (sending it, or a group note): "nancy, @bob" by username only. Unlike
     * {@link #resolveAll}, they don't need to have started chatting. Calls {@code error} with the
     * first problem, naming who it was about.
     */
    static void resolvePeople(String raw, Consumer<List<Contact>> found, Consumer<String> error) {
        String me = Account.uid();
        List<String> names = new ArrayList<>();
        for (String token : raw.split("[,;\\s]+")) {
            if (token.trim().isEmpty()) continue;
            String name = Account.normaliseUsername(token.split("/")[0]);
            if (name == null) {
                error.accept(token.trim() + ": that isn't a username");
                return;
            }
            if (!names.contains(name)) names.add(name);
        }
        if (me == null) {
            error.accept("Sign in to your account first");
            return;
        }
        if (names.isEmpty()) {
            error.accept("Enter at least one username");
            return;
        }
        resolveNextPerson(names, 0, new ArrayList<>(), me, found, error);
    }

    private static void resolveNextPerson(List<String> names, int index, List<Contact> done, String me,
                                          Consumer<List<Contact>> found, Consumer<String> error) {
        if (index == names.size()) {
            found.accept(done);
            return;
        }
        String name = names.get(index);
        Account.root().child("usernames").child(name).get().addOnCompleteListener(t -> {
            String uid = t.isSuccessful() ? t.getResult().getValue(String.class) : null;
            if (!t.isSuccessful()) error.accept("No connection. Try again.");
            else if (uid == null) error.accept("No one is called @" + name);
            else if (uid.equals(me)) error.accept("@" + name + " is you");
            else {
                done.add(contact(uid, name, ""));
                resolveNextPerson(names, index + 1, done, me, found, error);
            }
        });
    }

    /**
     * Resolves a list like "nancy, alice/AB12, @bob" (an optional /ID picks a vault; none means
     * their main vault). Calls {@code error} with the first problem, naming who it was about.
     */
    static void resolveAll(String raw, Consumer<List<Contact>> found, Consumer<String> error) {
        List<String[]> wanted = new ArrayList<>();
        for (String token : raw.split("[,;\n]+")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("[/\\s]+", 2);
            wanted.add(new String[]{parts[0], parts.length > 1 ? parts[1] : ""});
        }
        if (wanted.isEmpty()) {
            error.accept("Enter at least one username");
            return;
        }
        resolveNext(wanted, 0, new ArrayList<>(), found, error);
    }

    private static void resolveNext(List<String[]> wanted, int index, List<Contact> done,
                                    Consumer<List<Contact>> found, Consumer<String> error) {
        if (index == wanted.size()) {
            found.accept(done);
            return;
        }
        String[] w = wanted.get(index);
        resolve(w[0], w[1], contact -> {
            boolean duplicate = false;
            for (Contact c : done) duplicate |= c.uid.equals(contact.uid);
            if (!duplicate) done.add(contact);
            resolveNext(wanted, index + 1, done, found, error);
        }, problem -> error.accept(w[0] + ": " + problem));
    }

    private static Contact contact(String uid, String name, String code) {
        Contact c = new Contact();
        c.uid = uid;
        c.name = name;
        c.code = code;
        return c;
    }

    /**
     * The conversation between {@code vaultId} and {@code contact}, made if new. One that was
     * filed under another vault is moved here: starting it from here says where it belongs.
     * Blocking.
     */
    static int open(Context context, int vaultId, Contact contact) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Vault vault = dao.vault(vaultId);
        String me = Account.uid();
        if (vault == null || me == null) return -1;
        String chatId = chatId(me, vault.code, contact.uid, contact.code);
        ChatStore.Conversation existing = dao.conversationByChatId(chatId);
        if (existing != null) {
            if (existing.vaultId != vaultId) ChatVaults.move(context, existing, vault);
            return existing.id;
        }
        ChatStore.Conversation c = new ChatStore.Conversation();
        c.vaultId = vaultId;
        c.chatId = chatId;
        c.peerUid = contact.uid;
        c.peerName = contact.name;
        c.peerVault = contact.code;
        c.lastAt = System.currentTimeMillis();
        return (int) dao.insertConversation(c);
    }

    // ============================================================== sending

    /** Stores the message as waiting and queues the send. Blocking (call off the main thread). */
    static void send(Context context, int conversationId, String text) {
        send(context, conversationId, text, null, newKey());
    }

    /**
     * A photo or video, with an optional caption. The file is encrypted into this phone's
     * storage right away, so the send survives the app closing; the worker uploads it.
     * Blocking.
     */
    static void sendMedia(Context context, int conversationId, Uri source, String caption) throws Exception {
        sendMedia(context, conversationId, source, caption, false);
    }

    /** {@code once}: a view-once photo or video, gone after its first viewing. */
    static void sendMedia(Context context, int conversationId, Uri source, String caption, boolean once) throws Exception {
        String key = newKey();
        ChatMedia.Meta meta = ChatMedia.prepare(context, source, key);
        meta.once = once && !meta.isAudio();
        send(context, conversationId, caption, meta.toJson().toString(), key);
    }

    /** A recorded voice note, with its waveform. The recording itself is removed. Blocking. */
    static void sendVoice(Context context, int conversationId, File recording, long durationMs, float[] levels)
            throws Exception {
        String key = newKey();
        try {
            ChatMedia.Meta meta = ChatMedia.prepareVoice(context, recording, durationMs, levels, key);
            send(context, conversationId, "", meta.toJson().toString(), key);
        } finally {
            VaultCrypto.deleteRecursive(recording);
        }
    }

    /** Push keys sort by time and are made offline: a message's identity from its first moment. */
    private static String newKey() {
        return Account.root().child("chats").push().getKey();
    }

    private static void send(Context context, int conversationId, String text, @Nullable String media, String key) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        if (c == null) return;
        ChatStore.Message m = new ChatStore.Message();
        m.threadId = conversationId;
        m.msgKey = key;
        m.mine = true;
        m.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        m.media = media;
        m.at = System.currentTimeMillis();
        m.status = ChatStore.WAITING;
        dao.insertMessage(m);
        c.lastText = preview(m.text, media);
        c.lastAt = m.at;
        dao.updateConversation(c);
        schedule(context);
    }

    /** What the conversation list shows for a message. */
    static String preview(@Nullable String text, @Nullable String media) {
        ChatMedia.Meta meta = ChatMedia.Meta.parse(media);
        String kind = meta == null ? null : meta.isAudio() ? "Voice message"
                : (meta.once ? "View-once " : "") + (meta.isVideo() ? (meta.once ? "video" : "Video") : (meta.once ? "photo" : "Photo"));
        if (kind == null) return text;
        return text == null || text.isEmpty() ? kind : kind + " · " + text;
    }

    static void retry(Context context, ChatStore.Message message) {
        AppDatabase.IO.execute(() -> {
            AppDatabase.getInstance(context).chatDao().setStatus(message.msgKey, ChatStore.WAITING);
            schedule(context);
        });
    }

    private static void sendWaiting(Context context, String uid) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        List<ChatStore.Message> waiting = dao.waiting();
        if (waiting.isEmpty()) return;
        DriveStore drive = null;
        String myName = await(Account.root().child("users").child(uid).child("profile").child("username").get())
                .getValue(String.class);
        for (ChatStore.Message m : waiting) {
            ChatStore.Conversation c = dao.conversation(m.threadId);
            ChatStore.Vault vault = c == null ? null : dao.vault(c.vaultId);
            if (vault == null || myName == null) {
                // No username yet: nobody could reply. "Not sent" until one is chosen.
                dao.setStatus(m.msgKey, ChatStore.FAILED);
                continue;
            }
            DatabaseReference chat = ref(c.chatId);
            Map<String, Object> update = new HashMap<>();
            if (!c.established) {
                c.established = readable(chat.child("members").child(uid));
                if (!c.established) {
                    String address = ChatVaults.addressOf(c.chatId, uid);
                    update.put(path(c.chatId) + "/members/" + uid, address == null ? vault.code : address);
                    update.put(path(c.chatId) + "/members/" + c.peerUid, c.peerVault);
                    if (c.shownAs != null) update.put(path(c.chatId) + "/alias/" + uid, c.shownAs);
                }
            }
            Map<String, Object> message = new HashMap<>();
            message.put("from", uid);
            if (m.text != null && !m.text.isEmpty()) message.put("text", m.text);
            message.put("sentAt", ServerValue.TIMESTAMP);
            ChatMedia.Meta meta = ChatMedia.Meta.parse(m.media);
            if (meta != null) {
                if (meta.id == null) {
                    if (drive == null) drive = Account.drive(context);
                    if (drive == null) {
                        // Media goes through the sender's own Google Drive.
                        dao.setStatus(m.msgKey, ChatStore.FAILED);
                        continue;
                    }
                    try {
                        meta.id = ChatMedia.upload(context, drive, m.msgKey);
                    } catch (DriveStore.AuthException | DriveStore.QuotaException e) {
                        Log.w(TAG, "Media refused by Drive", e);
                        dao.setStatus(m.msgKey, ChatStore.FAILED);
                        continue;
                    }
                    m.media = meta.toJson().toString();
                    dao.setMedia(m.msgKey, m.media);
                    // View once: not even the sender keeps a copy to look at again.
                    if (meta.once) ChatMedia.forget(context, m.msgKey);
                }
                message.put("media", toMap(meta.toJson()));
            }
            update.put(path(c.chatId) + "/messages/" + m.msgKey, message);
            if (c.isGroup) {
                // Every member's index says this group has news.
                org.json.JSONObject members = json(c.members);
                for (java.util.Iterator<String> it = members.keys(); it.hasNext(); ) {
                    String member = it.next();
                    org.json.JSONObject info = members.optJSONObject(member);
                    if (info != null) update.put("chatIndex/" + member + "/" + c.chatId, groupIndex(c.chatId, c.peerName, info.optString("code")));
                }
            } else {
                // Index entries name the IDs the conversation was made with (its address), even if
                // this vault has a new ID since: the rules check them against the members.
                String myAddress = ChatVaults.addressOf(c.chatId, uid);
                if (myAddress == null) myAddress = vault.code;
                update.put("chatIndex/" + c.peerUid + "/" + c.chatId, index(uid, myName, c.peerVault, myAddress));
                update.put("chatIndex/" + uid + "/" + c.chatId, index(c.peerUid, c.peerName, myAddress, c.peerVault));
            }
            try {
                await(Account.root().updateChildren(update));
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof DatabaseException)) throw e;
                // Refused. Either an earlier attempt already landed (its answer was lost), or
                // the message can never be sent.
                if (!landed(chat, m.msgKey, uid)) {
                    Log.w(TAG, "Message refused", e);
                    dao.setStatus(m.msgKey, ChatStore.FAILED);
                    continue;
                }
            }
            c.established = true;
            dao.updateConversation(c);
            dao.advance(m.msgKey, ChatStore.SENT);
            trim(context, chat, uid);
        }
    }

    /**
     * True if this location exists and is readable. A conversation is readable only by its
     * members, so for one not created yet the database refuses rather than answers "empty".
     */
    private static boolean readable(DatabaseReference ref) throws Exception {
        try {
            return await(ref.get()).exists();
        } catch (ExecutionException e) {
            if (refused(e)) return false;
            throw e;
        }
    }

    /**
     * The database said no (rules), as opposed to "couldn't reach it". A read's refusal comes
     * as a plain Exception("Permission denied"), a write's as a DatabaseException.
     */
    static boolean refused(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DatabaseException) return true;
            if (t.getMessage() != null && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("permission denied")) {
                return true;
            }
        }
        return false;
    }

    /** Whether an earlier attempt at this message reached the database after all. */
    private static boolean landed(DatabaseReference chat, String key, String uid) throws Exception {
        try {
            return uid.equals(await(chat.child("messages").child(key).child("from").get()).getValue(String.class));
        } catch (ExecutionException e) {
            if (refused(e)) return false;
            throw e;
        }
    }

    private static Map<String, Object> groupIndex(String chatId, String name, String vault) {
        Map<String, Object> m = new HashMap<>();
        m.put("group", true);
        m.put("gid", chatId.substring(2)); // The rules can't cut it out of the key.
        m.put("name", name == null ? "Group" : name);
        m.put("vault", vault);
        m.put("at", ServerValue.TIMESTAMP);
        return m;
    }

    private static org.json.JSONObject json(@Nullable String s) {
        try {
            return s == null ? new org.json.JSONObject() : new org.json.JSONObject(s);
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    // ============================================================== groups

    /**
     * Makes a group chat in {@code vaultId} with these people (up to {@link #MAX_GROUP} with
     * me). Each member takes part from the vault their address names. Blocking.
     */
    static int createGroup(Context context, int vaultId, String name, List<Contact> people) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        String uid = Account.uid();
        ChatStore.Vault vault = dao.vault(vaultId);
        if (uid == null || vault == null) throw new IOException("Sign in first");
        if (people.size() + 1 > MAX_GROUP) throw new IOException("A group has at most " + MAX_GROUP + " members");
        String myName = await(Account.root().child("users").child(uid).child("profile").child("username").get())
                .getValue(String.class);
        if (myName == null) throw new IOException("Choose a username in your account first");
        String gid = Account.root().child("groupChats").push().getKey();
        String chatId = "g_" + gid;
        org.json.JSONObject members = new org.json.JSONObject();
        members.put(uid, new org.json.JSONObject().put("code", vault.code).put("name", myName));
        for (Contact p : people) members.put(p.uid, new org.json.JSONObject().put("code", p.code).put("name", p.name));
        Map<String, Object> update = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", name);
        meta.put("owner", uid);
        meta.put("createdAt", ServerValue.TIMESTAMP);
        update.put("groupChats/" + gid + "/meta", meta);
        update.put("groupChats/" + gid + "/size", members.length());
        for (java.util.Iterator<String> it = members.keys(); it.hasNext(); ) {
            String member = it.next();
            org.json.JSONObject info = members.getJSONObject(member);
            update.put("groupChats/" + gid + "/members/" + member, toMap(info));
            update.put("chatIndex/" + member + "/" + chatId, groupIndex(chatId, name, info.getString("code")));
        }
        await(Account.root().updateChildren(update));
        ChatStore.Conversation c = new ChatStore.Conversation();
        c.vaultId = vaultId;
        c.chatId = chatId;
        c.isGroup = true;
        c.peerName = name;
        c.members = members.toString();
        c.established = true;
        c.lastAt = System.currentTimeMillis();
        c.lastText = "Group created";
        return (int) dao.insertConversation(c);
    }

    /** Adds people to a group, up to {@link #MAX_GROUP} members. Blocking. */
    static void addToGroup(Context context, int conversationId, List<Contact> people) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        if (c == null || !c.isGroup) return;
        DatabaseReference group = ref(c.chatId);
        DataSnapshot online = await(group.child("members").get());
        org.json.JSONObject members = membersOf(online);
        int size = members.length();
        Map<String, Object> update = new HashMap<>();
        for (Contact p : people) {
            if (members.has(p.uid)) continue;
            members.put(p.uid, new org.json.JSONObject().put("code", p.code).put("name", p.name));
            update.put("members/" + p.uid, toMap(members.getJSONObject(p.uid)));
        }
        if (members.length() > MAX_GROUP) throw new IOException("A group has at most " + MAX_GROUP + " members");
        if (update.isEmpty()) return;
        update.put("size", members.length());
        Map<String, Object> all = new HashMap<>();
        for (Map.Entry<String, Object> e : update.entrySet()) all.put(path(c.chatId) + "/" + e.getKey(), e.getValue());
        for (Contact p : people) all.put("chatIndex/" + p.uid + "/" + c.chatId, groupIndex(c.chatId, c.peerName, p.code));
        if (size == 0) throw new IOException("This group no longer exists");
        await(Account.root().updateChildren(all));
        c.members = members.toString();
        dao.updateConversation(c);
    }

    /** Leaves a group: gone from its members, and from this vault. Blocking. */
    static void leaveGroup(Context context, int conversationId) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        String uid = Account.uid();
        if (c == null || !c.isGroup || uid == null) return;
        try {
            Long size = await(ref(c.chatId).child("size").get()).getValue(Long.class);
            Map<String, Object> update = new HashMap<>();
            update.put(path(c.chatId) + "/members/" + uid, null);
            update.put(path(c.chatId) + "/size", Math.max(0, (size == null ? 1 : size) - 1));
            update.put("chatIndex/" + uid + "/" + c.chatId, null);
            await(Account.root().updateChildren(update));
        } catch (ExecutionException e) {
            if (!refused(e)) throw e; // Already removed: leaving here is all that's left.
        }
        dao.deleteMessagesOf(c.id);
        dao.deleteConversation(c.id);
    }

    /** The group's owner, or null if it can't be read. Blocking. */
    @Nullable
    static String groupOwner(Context context, int conversationId) {
        ChatStore.Conversation c = AppDatabase.getInstance(context).chatDao().conversation(conversationId);
        if (c == null || !c.isGroup) return null;
        try {
            return await(ref(c.chatId).child("meta").child("owner").get()).getValue(String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The owner removes someone: out of the members, so the group stops sending them anything
     * and they can't read it any more. What their phone already has stays there. Blocking.
     */
    static void removeFromGroup(Context context, int conversationId, String member) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        if (c == null || !c.isGroup) return;
        org.json.JSONObject members = membersOf(await(ref(c.chatId).child("members").get()));
        if (!members.has(member)) return;
        members.remove(member);
        Map<String, Object> update = new HashMap<>();
        update.put(path(c.chatId) + "/members/" + member, null);
        update.put(path(c.chatId) + "/size", members.length());
        await(Account.root().updateChildren(update));
        c.members = members.toString();
        dao.updateConversation(c);
    }

    /** Renames a group for everyone. Blocking. */
    static void renameGroup(Context context, int conversationId, String name) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        if (c == null || !c.isGroup) return;
        await(ref(c.chatId).child("meta").child("name").setValue(name));
        c.peerName = name;
        dao.updateConversation(c);
    }

    /** {uid: {"code","name"}} from a members snapshot. */
    private static org.json.JSONObject membersOf(DataSnapshot members) throws Exception {
        org.json.JSONObject out = new org.json.JSONObject();
        for (DataSnapshot m : members.getChildren()) {
            if (m.getKey() == null) continue;
            out.put(m.getKey(), new org.json.JSONObject()
                    .put("code", m.child("code").getValue(String.class))
                    .put("name", m.child("name").getValue(String.class)));
        }
        return out;
    }

    private static Map<String, Object> index(String peer, String peerName, String vault, String peerVault) {
        Map<String, Object> m = new HashMap<>();
        m.put("peer", peer);
        m.put("peerName", peerName);
        m.put("vault", vault);
        m.put("peerVault", peerVault);
        m.put("at", ServerValue.TIMESTAMP);
        return m;
    }

    /**
     * Counts the message just sent and drops the oldest beyond {@link #KEEP}. Photos of mine
     * that drop out go from my Drive too; phones that already have them keep their copy.
     */
    private static void trim(Context context, DatabaseReference chat, String uid) {
        try {
            long count = increment(chat.child("count"), 1);
            if (count <= KEEP) return;
            DataSnapshot old = await(chat.child("messages").orderByKey().limitToFirst((int) (count - KEEP)).get());
            Map<String, Object> drop = new HashMap<>();
            DriveStore drive = null;
            for (DataSnapshot m : old.getChildren()) {
                drop.put(m.getKey(), null);
                String fileId = m.child("media").child("id").getValue(String.class);
                if (fileId == null || !uid.equals(m.child("from").getValue(String.class))) continue;
                if (drive == null) drive = Account.drive(context);
                if (drive == null) continue;
                try {
                    drive.deleteFile(fileId);
                } catch (IOException e) {
                    Log.w(TAG, "Could not remove an old attachment", e);
                }
            }
            if (drop.isEmpty()) return;
            await(chat.child("messages").updateChildren(drop));
            increment(chat.child("count"), -drop.size());
        } catch (Exception e) {
            Log.w(TAG, "Could not trim a conversation", e); // Tried again after the next send.
        }
    }

    private static long increment(DatabaseReference counter, long by) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        long[] result = new long[1];
        DatabaseError[] error = new DatabaseError[1];
        counter.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData current) {
                Long value = current.getValue(Long.class);
                current.setValue(Math.max(0, (value == null ? 0 : value) + by));
                return Transaction.success(current);
            }

            @Override
            public void onComplete(@Nullable DatabaseError e, boolean committed, @Nullable DataSnapshot snapshot) {
                error[0] = e;
                Long value = snapshot == null ? null : snapshot.getValue(Long.class);
                result[0] = value == null ? 0 : value;
                latch.countDown();
            }
        }, false);
        if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) throw new java.util.concurrent.TimeoutException();
        if (error[0] != null) throw error[0].toException();
        return result[0];
    }

    // ============================================================ receiving

    /** Every conversation of every vault on this phone, brought up to date. */
    private static void receiveAll(Context context, String uid) throws Exception {
        Map<String, String> places = ChatVaults.placements(uid);
        DataSnapshot index = await(Account.root().child("chatIndex").child(uid).get());
        for (DataSnapshot entry : index.getChildren()) receive(context, uid, entry, places.get(entry.getKey()));
        applyNicknames(context, uid);
        applyPhotos(context);
    }

    /**
     * Their photo in each conversation: the photo of the vault I'm talking to if it has one,
     * else their account's photo (their Google photo unless they chose another).
     */
    private static void applyPhotos(Context context) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        Map<String, String> accountPhotos = new HashMap<>();
        for (ChatStore.Conversation c : dao.allConversations()) {
            if (c.peerUid == null) continue;
            String code = c.peerShown != null ? c.peerShown : c.peerVault;
            String photo = code == null ? null : quietly(Account.root().child("vaultPhotos").child(c.peerUid).child(code));
            if (photo == null) {
                if (!accountPhotos.containsKey(c.peerUid)) {
                    accountPhotos.put(c.peerUid, quietly(Account.root().child("profiles").child(c.peerUid).child("photo")));
                }
                photo = accountPhotos.get(c.peerUid);
            }
            if (!java.util.Objects.equals(photo, c.peerPhoto)) {
                c.peerPhoto = photo;
                dao.updateConversation(c);
            }
        }
    }

    /** A photo is decoration: one that can't be read leaves the initial, never fails a sync. */
    @Nullable
    private static String quietly(DatabaseReference ref) {
        try {
            return jsonOf(await(ref.get()).getValue());
        } catch (Exception e) {
            Log.w(TAG, "No photo for " + ref.getKey(), e);
            return null;
        }
    }

    @Nullable
    static String jsonOf(@Nullable Object value) {
        return value instanceof Map ? new org.json.JSONObject((Map<?, ?>) value).toString() : null;
    }

    // ================================================== nicknames, background

    /** Nicknames live online too ({@code chatNick/{uid}/{chatId}}), so every phone shows them. */
    private static void applyNicknames(Context context, String uid) throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        for (DataSnapshot n : await(Account.root().child("chatNick").child(uid).get()).getChildren()) {
            ChatStore.Conversation c = n.getKey() == null ? null : dao.conversationByChatId(n.getKey());
            String nick = n.getValue(String.class);
            if (c != null && !java.util.Objects.equals(nick, c.nickname)) {
                c.nickname = nick;
                dao.updateConversation(c);
            }
        }
    }

    /** {@code null} or "" clears it. Blocking. */
    static void setNickname(Context context, int conversationId, @Nullable String nickname) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        String uid = Account.uid();
        if (c == null) return;
        c.nickname = nickname == null || nickname.trim().isEmpty() ? null : nickname.trim();
        dao.updateConversation(c);
        if (uid != null) Account.root().child("chatNick").child(uid).child(c.chatId).setValue(c.nickname);
    }

    /** A shared background colour ("#RRGGBB"), or null for the default. Both members see it. */
    static void setThemeColor(Context context, int conversationId, @Nullable String color) {
        Map<String, Object> theme = null;
        if (color != null) {
            theme = new HashMap<>();
            theme.put("kind", "color");
            theme.put("value", color);
        }
        setTheme(context, conversationId, theme);
    }

    /**
     * A shared background photo: encrypted, uploaded to my Drive and opened to anyone with the
     * link, like any chat photo. Blocking; needs Drive.
     */
    static void setThemeImage(Context context, int conversationId, Uri source) throws Exception {
        DriveStore drive = Account.drive(context);
        if (drive == null) throw new IOException("Connect Google Drive in your Notes+ account to use a photo");
        String temp = "theme_" + newKey();
        ChatMedia.Meta meta = ChatMedia.prepare(context, source, temp);
        meta.id = ChatMedia.upload(context, drive, temp);
        if (!ChatMedia.cached(context, temp).renameTo(ChatMedia.cached(context, themeTag(meta.id)))) {
            ChatMedia.forget(context, temp);
        }
        Map<String, Object> theme = new HashMap<>();
        theme.put("kind", "image");
        theme.put("media", toMap(meta.toJson()));
        setTheme(context, conversationId, theme);
    }

    /** Where a background photo is kept on a phone. */
    static String themeTag(String driveId) {
        return "t_" + driveId;
    }

    private static void setTheme(Context context, int conversationId, @Nullable Map<String, Object> theme) {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        ChatStore.Conversation c = dao.conversation(conversationId);
        if (c == null) return;
        c.theme = theme == null ? null : new org.json.JSONObject(theme).toString();
        dao.updateConversation(c);
        if (c.established) ref(c.chatId).child("theme").setValue(theme);
    }

    static Map<String, Object> toMap(org.json.JSONObject o) {
        Map<String, Object> m = new HashMap<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.opt(k));
        }
        return m;
    }

    /**
     * One {@code chatIndex} entry: files the conversation under the vault it belongs to (its
     * address, unless it was moved: {@code placed}), makes it if new, then fetches.
     */
    private static void receive(Context context, String uid, DataSnapshot entry, @Nullable String placed)
            throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        String chatId = entry.getKey();
        String code = entry.child("vault").getValue(String.class);
        ChatStore.Vault vault = placed == null ? null : dao.vaultByCode(placed);
        if (vault == null) vault = ChatVaults.reached(dao, code);
        // No such vault here: it was deleted, or doesn't exist yet on this phone.
        if (chatId == null || vault == null) return;
        ChatStore.Conversation c = dao.conversationByChatId(chatId);
        if (c != null && c.vaultId != vault.id && !c.moved) {
            c.vaultId = vault.id; // Moved on another phone.
            dao.updateConversation(c);
        }
        boolean group = isGroup(chatId);
        String peerName = entry.child(group ? "name" : "peerName").getValue(String.class);
        if (c == null) {
            c = new ChatStore.Conversation();
            c.vaultId = vault.id;
            c.chatId = chatId;
            c.isGroup = group;
            c.peerUid = group ? null : entry.child("peer").getValue(String.class);
            c.peerName = peerName;
            c.peerVault = entry.child("peerVault").getValue(String.class);
            c.established = true;
            if (code != null && !code.equals(vault.code)) {
                // Written to an ID this vault had before: file it here, show them the new one.
                c.shownAs = vault.code;
                c.moved = true;
            }
            c.id = (int) dao.insertConversation(c);
        } else if (!group && peerName != null && !peerName.equals(c.peerName)) {
            c.peerName = peerName; // They changed their username.
            dao.updateConversation(c);
        }
        try {
            fetch(context, uid, c);
        } catch (ExecutionException e) {
            // A group I was removed from refuses me now; keep what's here, stop asking.
            if (!group || !refused(e)) throw e;
        }
    }

    /**
     * Brings one conversation up to date: new messages in, and the status of mine that can
     * still change ("sent" or "received") refreshed. Marks theirs received.
     */
    private static void fetch(Context context, String uid, ChatStore.Conversation c) throws Exception {
        fetch(context, uid, c, true);
    }

    private static void fetch(Context context, String uid, ChatStore.Conversation c, boolean receipts)
            throws Exception {
        ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
        String from = c.cursor;
        String open = dao.oldestOpen(c.id);
        if (open != null && (from == null || open.compareTo(from) < 0)) from = open;
        Query query = ref(c.chatId).child("messages").orderByKey();
        if (from != null) query = query.startAt(from);
        DataSnapshot messages = await(query.limitToLast(KEEP).get());
        DataSnapshot theme = await(ref(c.chatId).child("theme").get());
        c.theme = themeJson(theme);
        if (c.isGroup) {
            c.members = membersOf(await(ref(c.chatId).child("members").get())).toString();
            String name = await(ref(c.chatId).child("meta").child("name").get()).getValue(String.class);
            if (name != null) c.peerName = name;
        }
        if (c.peerUid != null) {
            c.peerShown = await(ref(c.chatId).child("alias").child(c.peerUid).get())
                    .getValue(String.class);
        }
        List<String> unacknowledged = new ArrayList<>();
        for (DataSnapshot m : messages.getChildren()) {
            if (apply(dao, uid, c, m)) unacknowledged.add(m.getKey());
        }
        dao.updateConversation(c);
        if (receipts && !unacknowledged.isEmpty()) {
            Map<String, Object> update = new HashMap<>();
            for (String key : unacknowledged) update.put(key + receiptPath(c, "received", uid), ServerValue.TIMESTAMP);
            await(ref(c.chatId).child("messages").updateChildren(update));
        }
    }

    @Nullable
    static String themeJson(DataSnapshot theme) {
        Object value = theme.getValue();
        return value instanceof Map ? new org.json.JSONObject((Map<?, ?>) value).toString() : null;
    }

    /** Two people: one "received"/"seen" each. A group: one per member ("rcv"/"seen" by uid). */
    private static String receiptPath(ChatStore.Conversation c, String kind, String uid) {
        if (!c.isGroup) return "/" + kind;
        return "/" + ("received".equals(kind) ? "rcv" : "seen") + "/" + uid;
    }

    /** True if every other member is in a group message's receipt map. */
    private static boolean everyone(ChatStore.Conversation c, String uid, DataSnapshot receipts) {
        org.json.JSONObject members = json(c.members);
        int others = 0;
        for (java.util.Iterator<String> it = members.keys(); it.hasNext(); ) {
            String m = it.next();
            if (m.equals(uid)) continue;
            others++;
            if (!receipts.hasChild(m)) return false;
        }
        return others > 0;
    }

    /** Stores or updates one message. True if it is theirs and they don't know it arrived. */
    private static boolean apply(ChatStore.Dao dao, String uid, ChatStore.Conversation c, DataSnapshot s) {
        String key = s.getKey();
        String from = s.child("from").getValue(String.class);
        String text = s.child("text").getValue(String.class);
        Object mediaValue = s.child("media").getValue();
        String media = mediaValue instanceof Map ? new org.json.JSONObject((Map<?, ?>) mediaValue).toString() : null;
        if (key == null || from == null || (text == null && media == null)) return false;
        if (text == null) text = "";
        boolean mine = uid.equals(from);
        boolean received, seen;
        if (!c.isGroup) {
            received = s.hasChild("received");
            seen = s.hasChild("seen");
        } else if (mine) {
            received = everyone(c, uid, s.child("rcv"));
            seen = everyone(c, uid, s.child("seen"));
        } else {
            received = s.child("rcv").hasChild(uid);
            seen = s.child("seen").hasChild(uid);
        }
        boolean opened = !c.isGroup ? s.hasChild("opened")
                : mine ? everyone(c, uid, s.child("opened")) : s.child("opened").hasChild(uid);
        Long sentAt = s.child("sentAt").getValue(Long.class);
        ChatStore.Message local = dao.message(key);
        if (local == null) {
            ChatStore.Message m = new ChatStore.Message();
            m.threadId = c.id;
            m.msgKey = key;
            m.mine = mine;
            m.text = text;
            m.media = opened ? flag(media, "opened") : media;
            if (c.isGroup && !mine) {
                m.sender = from;
                org.json.JSONObject info = json(c.members).optJSONObject(from);
                m.senderName = info == null ? null : info.optString("name", null);
            }
            m.at = sentAt == null ? System.currentTimeMillis() : sentAt;
            // Mine: how far it got. Theirs: here now, and seen only if I saw it elsewhere.
            m.status = mine ? (seen ? ChatStore.SEEN : received ? ChatStore.RECEIVED : ChatStore.SENT)
                    : (seen ? ChatStore.SEEN : ChatStore.RECEIVED);
            dao.insertMessage(m);
            if (!mine && !seen) c.unread++;
            if (c.cursor == null || key.compareTo(c.cursor) >= 0) {
                c.lastText = (m.senderName != null ? m.senderName + ": " : "") + preview(text, media);
                c.lastAt = m.at;
            }
        } else {
            if (mine) dao.advance(key, seen ? ChatStore.SEEN : received ? ChatStore.RECEIVED : ChatStore.SENT);
            ChatMedia.Meta meta = ChatMedia.Meta.parse(local.media);
            if (meta != null && meta.once && opened && !hasFlag(local.media, "opened")) {
                dao.setMedia(key, flag(local.media, "opened"));
                // Opened by everyone it was for: its file leaves the sender's Drive for good.
                if (mine && meta.id != null && appContext != null) {
                    DriveStore drive = Account.drive(appContext);
                    if (drive != null) {
                        try {
                            drive.deleteFile(meta.id);
                        } catch (IOException e) {
                            Log.w(TAG, "Couldn't remove an opened view-once file", e);
                        }
                    }
                }
            }
        }
        if (c.cursor == null || key.compareTo(c.cursor) > 0) c.cursor = key;
        return !mine && !received;
    }

    /** Local-only marks on a message's media JSON (e.g. "opened"). */
    static String flag(@Nullable String media, String name) {
        if (media == null) return null;
        try {
            return new org.json.JSONObject(media).put(name, true).toString();
        } catch (Exception e) {
            return media;
        }
    }

    static boolean hasFlag(@Nullable String media, String name) {
        if (media == null) return false;
        try {
            return new org.json.JSONObject(media).optBoolean(name, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A view-once photo or video was just looked at: its copy here goes, and the sender hears
     * it was opened (their phone then deletes the file from their Drive).
     */
    static void markOpened(Context context, String msgKey) {
        Context app = context.getApplicationContext();
        AppDatabase.IO.execute(() -> {
            ChatStore.Dao dao = AppDatabase.getInstance(app).chatDao();
            ChatStore.Message m = dao.message(msgKey);
            if (m == null) return;
            dao.setMedia(msgKey, flag(m.media, "opened"));
            ChatMedia.forget(app, msgKey);
            ChatStore.Conversation c = dao.conversation(m.threadId);
            String uid = Account.uid();
            if (c == null || uid == null) return;
            DatabaseReference ref = ref(c.chatId).child("messages").child(msgKey).child("opened");
            (c.isGroup ? ref.child(uid) : ref).setValue(ServerValue.TIMESTAMP);
        });
    }

    /** The conversation is on screen: tell them it was seen. */
    static void markSeen(Context context, int conversationId) {
        AppDatabase.IO.execute(() -> {
            ChatStore.Dao dao = AppDatabase.getInstance(context).chatDao();
            ChatStore.Conversation c = dao.conversation(conversationId);
            if (c == null) return;
            if (c.unread > 0) dao.markRead(conversationId);
            List<String> keys = dao.unseen(conversationId);
            // Nothing new: no write, so the screen's observer doesn't wake for nothing.
            if (keys.isEmpty()) return;
            dao.markSeen(conversationId);
            if (Account.uid() == null) return;
            Map<String, Object> update = new HashMap<>();
            for (String key : keys) update.put(key + receiptPath(c, "seen", Account.uid()), ServerValue.TIMESTAMP);
            ref(c.chatId).child("messages").updateChildren(update);
        });
    }

    // ================================================================= live

    /** Live while a chat screen is visible. Returns the stopper. */
    static Runnable watchIndex(Context context) {
        String uid = Account.uid();
        if (uid == null) return () -> {};
        Context app = context.getApplicationContext();
        DatabaseReference ref = Account.root().child("chatIndex").child(uid);
        ChildEventListener listener = new Children(snapshot -> background(app, () -> receive(app, uid, snapshot,
                await(Account.root().child("chatPlace").child(uid).child(snapshot.getKey()).get()).getValue(String.class))));
        ref.addChildEventListener(listener);
        return () -> ref.removeEventListener(listener);
    }

    /** Live messages of one conversation; {@code onChange} runs after each is stored. */
    static Runnable watchConversation(Context context, int conversationId, Runnable onChange) {
        String uid = Account.uid();
        if (uid == null) return () -> {};
        Context app = context.getApplicationContext();
        ChatStore.Dao dao = AppDatabase.getInstance(app).chatDao();
        Query[] query = new Query[1];
        ChildEventListener listener = new Children(snapshot -> background(app, () -> {
            ChatStore.Conversation c = dao.conversation(conversationId);
            if (c == null) return;
            if (apply(dao, uid, c, snapshot)) {
                ref(c.chatId).child("messages").child(snapshot.getKey())
                        .child(receiptPath(c, "received", uid).substring(1)).setValue(ServerValue.TIMESTAMP);
            }
            dao.updateConversation(c);
            onChange.run();
        }));
        AppDatabase.IO.execute(() -> {
            ChatStore.Conversation c = dao.conversation(conversationId);
            if (c == null) return;
            synchronized (query) {
                query[0] = ref(c.chatId).child("messages").orderByKey().limitToLast(KEEP);
                query[0].addChildEventListener(listener);
            }
        });
        return () -> {
            synchronized (query) {
                if (query[0] != null) query[0].removeEventListener(listener);
            }
        };
    }

    /** The shared background, live while the conversation is open. {@code onChange} on main. */
    static Runnable watchTheme(Context context, int conversationId, Runnable onChange) {
        Context app = context.getApplicationContext();
        ChatStore.Dao dao = AppDatabase.getInstance(app).chatDao();
        com.google.firebase.database.ValueEventListener listener = new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String json = themeJson(snapshot);
                AppDatabase.IO.execute(() -> {
                    ChatStore.Conversation c = dao.conversation(conversationId);
                    if (c == null || java.util.Objects.equals(json, c.theme)) return;
                    c.theme = json;
                    dao.updateConversation(c);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(onChange);
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        DatabaseReference[] ref = new DatabaseReference[1];
        AppDatabase.IO.execute(() -> {
            ChatStore.Conversation c = dao.conversation(conversationId);
            if (c == null || !c.established || Account.uid() == null) return;
            synchronized (ref) {
                ref[0] = ref(c.chatId).child("theme");
                ref[0].addValueEventListener(listener);
            }
        });
        return () -> {
            synchronized (ref) {
                if (ref[0] != null) ref[0].removeEventListener(listener);
            }
        };
    }

    /** Read-only polling: everything that changed, on the account's schedule, not live. */
    static void pollAll(Context context) {
        String uid = Account.uid();
        if (uid == null) return;
        Context app = context.getApplicationContext();
        background(app, () -> receiveAll(app, uid));
    }

    /** Read-only polling of one open conversation. */
    static void pollConversation(Context context, int conversationId) {
        String uid = Account.uid();
        if (uid == null) return;
        Context app = context.getApplicationContext();
        background(app, () -> {
            ChatStore.Conversation c = AppDatabase.getInstance(app).chatDao().conversation(conversationId);
            if (c != null) fetch(app, uid, c, false);
        });
    }

    private interface Step {
        void run() throws Exception;
    }

    /** Off the main thread, one at a time with the worker; never parks a shared thread on it. */
    private static void background(Context context, Step step) {
        appContext = context.getApplicationContext();
        AppDatabase.IO.execute(() -> {
            if (!LOCK.tryLock()) {
                schedule(context);
                return;
            }
            try {
                step.run();
            } catch (Exception e) {
                Log.w(TAG, "Live update failed", e);
            } finally {
                LOCK.unlock();
            }
        });
    }

    private static final class Children implements ChildEventListener {
        private final Consumer<DataSnapshot> onData;

        Children(Consumer<DataSnapshot> onData) {
            this.onData = onData;
        }

        @Override
        public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previous) {
            onData.accept(snapshot);
        }

        @Override
        public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previous) {
            onData.accept(snapshot);
        }

        @Override
        public void onChildRemoved(@NonNull DataSnapshot snapshot) {}

        @Override
        public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previous) {}

        @Override
        public void onCancelled(@NonNull DatabaseError error) {
            Log.w(TAG, "Live chat stopped", error.toException());
        }
    }

    // ============================================================= worker

    /**
     * Sends what waits and fetches what's new, once there is a network. No-op signed out.
     *
     * Replaces whatever is queued: every run sends everything waiting, and appending used to
     * park new messages behind a failed run's growing retry delay.
     */
    static void schedule(Context context) {
        if (Account.uid() == null) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Job.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request);
    }

    /** Messages arrive (and are marked received) even when the space hasn't been opened. */
    static void schedulePeriodic(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(Job.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static final class Job extends Worker {
        public Job(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            try {
                run(getApplicationContext());
                return Result.success();
            } catch (Exception e) {
                Log.w(TAG, "Chat sync failed; will retry", e);
                return getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
            }
        }
    }

    static void run(Context context) throws Exception {
        appContext = context.getApplicationContext();
        String uid = Account.uid();
        if (uid == null) return;
        if (!ChatVaults.boundTo(context, uid)) return; // This phone's chats belong to another account.
        if (AppDatabase.getInstance(context).chatDao().mainVault() == null) {
            // Chat not opened on this phone yet: still bring the account's vaults (and their
            // locks) here, so they're ready the first time it is.
            ChatVaults.sync(context, uid);
            return;
        }
        LOCK.lock();
        try {
            // Each stage on its own: one failing (say, vault sync) must not hold messages back.
            Exception failure = null;
            Step[] stages = {
                    () -> publishHome(context, uid),
                    () -> ChatVaults.sync(context, uid),
                    () -> sendWaiting(context, uid),
                    () -> receiveAll(context, uid),
            };
            boolean refusedAll = true;
            for (Step stage : stages) {
                try {
                    stage.run();
                    refusedAll = false;
                } catch (Exception e) {
                    Log.w(TAG, "Chat sync stage failed", e);
                    if (!refused(e)) refusedAll = false;
                    if (failure == null) failure = e;
                }
            }
            // Every step turned away by the server: its rules don't allow chat. Say so on
            // screen instead of an endless clock; messages stay waiting and go once it's fixed.
            prefs(context).edit().putBoolean(PREF_REFUSED, refusedAll).apply();
            if (failure != null) throw failure;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Tells others which vault an address without an ID reaches: the account's main vault,
     * which {@link ChatVaults#sync} keeps the same on every phone.
     */
    private static void publishHome(Context context, String uid) throws Exception {
        ChatStore.Vault main = AppDatabase.getInstance(context).chatDao().mainVault();
        SharedPreferences prefs = prefs(context);
        if (main == null || main.code.equals(prefs.getString(PREF_HOME + uid, null))) return;
        await(Account.root().child("chatHome").child(uid).setValue(main.code));
        prefs.edit().putString(PREF_HOME + uid, main.code).apply();
    }

    // ============================================================= helpers

    private static <T> T await(com.google.android.gms.tasks.Task<T> task) throws Exception {
        return Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static SharedPreferences prefs(Context context) {
        return SecurityHelper.getAppPrefs(context);
    }
}
