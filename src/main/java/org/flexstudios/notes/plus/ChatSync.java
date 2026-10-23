package org.flexstudios.notes.plus;

import javafx.application.Platform;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves chat messages between this computer and the Realtime Database: the phone's ChatSync,
 * over REST, writing exactly what the phone writes.
 *
 * <p><b>Addressing.</b> Every chat vault has a 4-character ID. A conversation joins one person's
 * vault with another's: {@code chats/{chatId}}, the id being both {@code uid_ID} endpoints,
 * sorted. An empty ID means "their main vault", resolved through {@code chatHome/{uid}}.
 *
 * <p><b>Delivery.</b> A message is stored here first (waiting) under a push key made offline,
 * then sent by the sync job. The recipient marks it {@code received} when it fetches it and
 * {@code seen} when the conversation is on screen. {@code chatIndex/{uid}} says which
 * conversations have news.
 *
 * <p><b>Size.</b> Only the newest {@value #KEEP} messages stay online; the sender trims.
 *
 * <p>No notifications, ever.
 */
final class ChatSync {

    private static final System.Logger LOG = System.getLogger("ChatSync");
    private static final String PREF_HOME = "chat_home_published_";
    /** Set when the main chat vault was created with the lock it started with. */
    static final String PREF_INHERITED_LOCK = "chat_lock_inherited";
    /** The last run was refused by the server at every step. */
    static final String PREF_REFUSED = "chat_server_refused";
    static final int KEEP = 100;
    static final int MAX_GROUP = 20;
    static final int MAX_TEXT = 4000;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final ScheduledExecutorService SYNC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-sync");
        t.setDaemon(true);
        return t;
    });
    /** Live updates, apart from the job so a long send never holds up a message arriving. */
    private static final ScheduledExecutorService LIVE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-live");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static int failures;

    private ChatSync() {}

    // ============================================================ addressing

    /** Upper-cases a typed vault ID; "" stays "" (main vault), anything malformed is null. */
    static String normaliseCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) return "";
        return code.matches("^[A-Z0-9]{4}$") ? code : null;
    }

    static boolean isGroup(String chatId) {
        return chatId.startsWith("g_");
    }

    /** Where a conversation lives online. */
    static String path(String chatId) {
        return isGroup(chatId) ? "groupChats/" + chatId.substring(2) : "chats/" + chatId;
    }

    static String chatId(String uidA, String codeA, String uidB, String codeB) {
        String a = uidA + "_" + codeA, b = uidB + "_" + codeB;
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    static final class Contact {
        String uid, name, code;
    }

    /** Finds {@code @username} and their vault (their main vault for ""). Blocking; the message says what's wrong. */
    static Contact resolve(String username, String code) throws IOException {
        String me = Account.uid();
        String name = Account.normaliseUsername(username);
        String vault = normaliseCode(code);
        if (me == null) throw new IOException("Sign in to your account first");
        if (name == null) throw new IOException("That isn't a username");
        if (vault == null) throw new IOException("A vault ID is 4 letters or numbers");
        Object uid;
        try {
            uid = Firebase.get("usernames/" + name, null);
        } catch (IOException e) {
            throw new IOException("No connection. Try again.");
        }
        if (!(uid instanceof String peer)) throw new IOException("No one is called @" + name);
        if (peer.equals(me)) throw new IOException("That's you");
        if (vault.isEmpty()) {
            Object home;
            try {
                home = Firebase.get("chatHome/" + peer, null);
            } catch (IOException e) {
                throw new IOException("No connection. Try again.");
            }
            if (!(home instanceof String h)) throw new IOException("@" + name + " hasn't started chatting yet");
            vault = h;
        }
        Contact c = new Contact();
        c.uid = peer;
        c.name = name;
        c.code = vault;
        return c;
    }

    /** "nancy, alice/AB12, @bob": an optional /ID picks a vault. The error names who it was about. Blocking. */
    static List<Contact> resolveAll(String raw) throws IOException {
        List<Contact> done = new ArrayList<>();
        for (String token : raw.split("[,;\n]+")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("[/\\s]+", 2);
            Contact contact;
            try {
                contact = resolve(parts[0], parts.length > 1 ? parts[1] : "");
            } catch (IOException e) {
                throw new IOException(parts[0] + ": " + e.getMessage());
            }
            if (done.stream().noneMatch(c -> c.uid.equals(contact.uid))) done.add(contact);
        }
        if (done.isEmpty()) throw new IOException("Enter at least one username");
        return done;
    }

    /** The conversation between {@code vaultId} and {@code contact}, made if new (moved here if filed elsewhere). Blocking. */
    static int open(int vaultId, Contact contact) {
        ChatStore store = ChatStore.get();
        ChatStore.Vault vault = store.vault(vaultId);
        String me = Account.uid();
        if (vault == null || me == null) return -1;
        String chatId = chatId(me, vault.code, contact.uid, contact.code);
        ChatStore.Conversation existing = store.conversationByChatId(chatId);
        if (existing != null) {
            if (existing.vaultId != vaultId) ChatVaults.move(existing, vault);
            return existing.id;
        }
        ChatStore.Conversation c = new ChatStore.Conversation();
        c.vaultId = vaultId;
        c.chatId = chatId;
        c.peerUid = contact.uid;
        c.peerName = contact.name;
        c.peerVault = contact.code;
        c.lastAt = System.currentTimeMillis();
        return store.insertConversation(c);
    }

    // ============================================================== sending

    /** Stores the message as waiting and queues the send. Blocking. */
    static void send(int conversationId, String text) {
        send(conversationId, text, null, PushId.next());
    }

    /** A photo or video, with an optional caption; {@code once}: view once. Blocking. */
    static void sendMedia(int conversationId, Path source, String caption, boolean once) throws IOException {
        String key = PushId.next();
        ChatMedia.Meta meta = ChatMedia.prepare(source, key);
        meta.once = once && !meta.isAudio();
        send(conversationId, caption, meta.toJson().toString(), key);
    }

    /** A pasted picture. Blocking. */
    static void sendPicture(int conversationId, byte[] png, String caption, boolean once) throws IOException {
        String key = PushId.next();
        ChatMedia.Meta meta = ChatMedia.prepareBytes(png, "Pasted image.png", key);
        meta.once = once;
        send(conversationId, caption, meta.toJson().toString(), key);
    }

    /** A recorded voice note with its waveform; the recording itself is removed. Blocking. */
    static void sendVoice(int conversationId, Path recording, long durationMs, float[] levels) throws IOException {
        String key = PushId.next();
        try {
            ChatMedia.Meta meta = ChatMedia.prepareVoice(recording, durationMs, levels, key);
            send(conversationId, "", meta.toJson().toString(), key);
        } finally {
            Files.deleteIfExists(recording);
        }
    }

    private static void send(int conversationId, String text, String media, String key) {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        if (c == null) return;
        ChatStore.Message m = new ChatStore.Message();
        m.threadId = conversationId;
        m.msgKey = key;
        m.mine = true;
        String t = text == null ? "" : text;
        m.text = t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
        m.media = media;
        m.at = System.currentTimeMillis();
        m.status = ChatStore.WAITING;
        store.insertMessage(m);
        c.lastText = preview(m.text, media);
        c.lastAt = m.at;
        store.updateConversation(c);
        schedule();
    }

    /** What the conversation list shows for a message. */
    static String preview(String text, String media) {
        ChatMedia.Meta meta = ChatMedia.Meta.parse(media);
        String kind = meta == null ? null : meta.isAudio() ? "Voice message"
                : (meta.once ? "View-once " : "") + (meta.isVideo() ? (meta.once ? "video" : "Video") : (meta.once ? "photo" : "Photo"));
        if (kind == null) return text;
        return text == null || text.isEmpty() ? kind : kind + " · " + text;
    }

    static void retry(ChatStore.Message message) {
        ChatStore.write(s -> {
            s.setStatus(message.msgKey, ChatStore.WAITING);
            schedule();
        });
    }

    private static void sendWaiting(String uid) throws Exception {
        ChatStore store = ChatStore.get();
        List<ChatStore.Message> waiting = store.waiting();
        if (waiting.isEmpty()) return;
        Drive drive = null;
        String myName = Firebase.get("users/" + uid + "/profile/username", null) instanceof String s ? s : null;
        for (ChatStore.Message m : waiting) {
            ChatStore.Conversation c = store.conversation(m.threadId);
            ChatStore.Vault vault = c == null ? null : store.vault(c.vaultId);
            if (vault == null || myName == null) {
                // No username yet: nobody could reply. "Not sent" until one is chosen.
                store.setStatus(m.msgKey, ChatStore.FAILED);
                continue;
            }
            String chat = path(c.chatId);
            JSONObject update = new JSONObject();
            if (!c.established) {
                c.established = readable(chat + "/members/" + uid);
                if (!c.established) {
                    String address = ChatVaults.addressOf(c.chatId, uid);
                    update.put(chat + "/members/" + uid, address == null ? vault.code : address);
                    update.put(chat + "/members/" + c.peerUid, c.peerVault);
                    if (c.shownAs != null) update.put(chat + "/alias/" + uid, c.shownAs);
                }
            }
            JSONObject message = new JSONObject().put("from", uid).put("sentAt", Firebase.now());
            if (m.text != null && !m.text.isEmpty()) message.put("text", m.text);
            ChatMedia.Meta meta = ChatMedia.Meta.parse(m.media);
            if (meta != null) {
                if (meta.id == null) {
                    if (drive == null) drive = Account.drive();
                    if (drive == null) {
                        // Media goes through the sender's own Google Drive.
                        store.setStatus(m.msgKey, ChatStore.FAILED);
                        continue;
                    }
                    try {
                        meta.id = ChatMedia.upload(drive, m.msgKey);
                    } catch (Drive.AuthException | Drive.QuotaException e) {
                        LOG.log(System.Logger.Level.WARNING, "Media refused by Drive", e);
                        store.setStatus(m.msgKey, ChatStore.FAILED);
                        continue;
                    }
                    m.media = meta.toJson().toString();
                    store.setMedia(m.msgKey, m.media);
                    // View once: not even the sender keeps a copy to look at again.
                    if (meta.once) ChatMedia.forget(m.msgKey);
                }
                message.put("media", meta.toJson());
            }
            update.put(chat + "/messages/" + m.msgKey, message);
            if (c.isGroup) {
                JSONObject members = json(c.members);
                for (String member : members.keySet()) {
                    JSONObject info = members.optJSONObject(member);
                    if (info != null) update.put("chatIndex/" + member + "/" + c.chatId, groupIndex(c.chatId, c.peerName, info.optString("code")));
                }
            } else {
                // Index entries name the IDs the conversation was made with (its address).
                String myAddress = ChatVaults.addressOf(c.chatId, uid);
                if (myAddress == null) myAddress = vault.code;
                update.put("chatIndex/" + c.peerUid + "/" + c.chatId, index(uid, myName, c.peerVault, myAddress));
                update.put("chatIndex/" + uid + "/" + c.chatId, index(c.peerUid, c.peerName, myAddress, c.peerVault));
            }
            try {
                Firebase.patch("", update);
            } catch (Firebase.Error e) {
                if (!e.refused()) throw e;
                // Refused: an earlier attempt landed (its answer was lost), or it never can.
                if (!landed(chat, m.msgKey, uid)) {
                    LOG.log(System.Logger.Level.WARNING, "Message refused", e);
                    store.setStatus(m.msgKey, ChatStore.FAILED);
                    continue;
                }
            }
            c.established = true;
            store.updateConversation(c);
            store.advance(m.msgKey, ChatStore.SENT);
            trim(chat, uid);
        }
    }

    /** True if this location exists and is readable (a conversation not made yet refuses). */
    private static boolean readable(String path) throws IOException {
        try {
            return Firebase.get(path, null) != null;
        } catch (Firebase.Error e) {
            if (e.refused()) return false;
            throw e;
        }
    }

    static boolean refused(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof Firebase.Error f && f.refused()) return true;
        return false;
    }

    private static boolean landed(String chat, String key, String uid) throws IOException {
        try {
            return uid.equals(Firebase.get(chat + "/messages/" + key + "/from", null));
        } catch (Firebase.Error e) {
            if (e.refused()) return false;
            throw e;
        }
    }

    private static JSONObject groupIndex(String chatId, String name, String vault) {
        return new JSONObject().put("group", true).put("gid", chatId.substring(2)) // The rules can't cut it out of the key.
                .put("name", name == null ? "Group" : name).put("vault", vault).put("at", Firebase.now());
    }

    private static JSONObject index(String peer, String peerName, String vault, String peerVault) {
        return new JSONObject().put("peer", peer).put("peerName", peerName).put("vault", vault)
                .put("peerVault", peerVault).put("at", Firebase.now());
    }

    static JSONObject json(String s) {
        try {
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Counts the message just sent and drops the oldest beyond {@link #KEEP} (and my files of them from my Drive). */
    private static void trim(String chat, String uid) {
        try {
            long count = increment(chat + "/count", 1);
            if (count <= KEEP) return;
            Object old = Firebase.get(chat + "/messages", "orderBy=" + enc("\"$key\"") + "&limitToFirst=" + (count - KEEP));
            if (!(old instanceof JSONObject messages) || messages.isEmpty()) return;
            JSONObject drop = new JSONObject();
            Drive drive = null;
            for (String key : messages.keySet()) {
                drop.put(key, JSONObject.NULL);
                JSONObject m = messages.optJSONObject(key);
                JSONObject media = m == null ? null : m.optJSONObject("media");
                String fileId = media == null ? null : media.optString("id", null);
                if (fileId == null || !uid.equals(m.optString("from"))) continue;
                if (drive == null) drive = Account.drive();
                if (drive == null) continue;
                try {
                    drive.delete(fileId);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "Could not remove an old attachment", e);
                }
            }
            Firebase.patch(chat + "/messages", drop);
            increment(chat + "/count", -drop.length());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Could not trim a conversation", e); // Again after the next send.
        }
    }

    /** A counter transaction, by ETag. */
    private static long increment(String path, long by) throws IOException {
        Firebase.Tagged now = Firebase.getTagged(path);
        for (int attempt = 0; attempt < 10; attempt++) {
            long value = now.value() instanceof Number n ? n.longValue() : 0;
            long next = Math.max(0, value + by);
            Firebase.Tagged conflict = Firebase.putIf(path, next, now.etag());
            if (conflict == null) return next;
            now = conflict;
        }
        throw new IOException("The counter is too busy");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ============================================================== groups

    /** A group chat in {@code vaultId} with these people (up to {@link #MAX_GROUP} with me). Blocking. */
    static int createGroup(int vaultId, String name, List<Contact> people) throws IOException {
        ChatStore store = ChatStore.get();
        String uid = Account.uid();
        ChatStore.Vault vault = store.vault(vaultId);
        if (uid == null || vault == null) throw new IOException("Sign in first");
        if (people.size() + 1 > MAX_GROUP) throw new IOException("A group has at most " + MAX_GROUP + " members");
        String myName = Firebase.get("users/" + uid + "/profile/username", null) instanceof String s ? s : null;
        if (myName == null) throw new IOException("Choose a username in your account first");
        String gid = PushId.next();
        String chatId = "g_" + gid;
        JSONObject members = new JSONObject();
        members.put(uid, new JSONObject().put("code", vault.code).put("name", myName));
        for (Contact p : people) members.put(p.uid, new JSONObject().put("code", p.code).put("name", p.name));
        JSONObject update = new JSONObject();
        update.put("groupChats/" + gid + "/meta", new JSONObject().put("name", name).put("owner", uid).put("createdAt", Firebase.now()));
        update.put("groupChats/" + gid + "/size", members.length());
        for (String member : members.keySet()) {
            JSONObject info = members.getJSONObject(member);
            update.put("groupChats/" + gid + "/members/" + member, info);
            update.put("chatIndex/" + member + "/" + chatId, groupIndex(chatId, name, info.getString("code")));
        }
        Firebase.patch("", update);
        ChatStore.Conversation c = new ChatStore.Conversation();
        c.vaultId = vaultId;
        c.chatId = chatId;
        c.isGroup = true;
        c.peerName = name;
        c.members = members.toString();
        c.established = true;
        c.lastAt = System.currentTimeMillis();
        c.lastText = "Group created";
        return store.insertConversation(c);
    }

    /** Adds people to a group, up to {@link #MAX_GROUP} members. Blocking. */
    static void addToGroup(int conversationId, List<Contact> people) throws IOException {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        if (c == null || !c.isGroup) return;
        JSONObject members = membersOf(Firebase.get(path(c.chatId) + "/members", null));
        int size = members.length();
        if (size == 0) throw new IOException("This group no longer exists");
        JSONObject update = new JSONObject();
        boolean added = false;
        for (Contact p : people) {
            if (members.has(p.uid)) continue;
            JSONObject info = new JSONObject().put("code", p.code).put("name", p.name);
            members.put(p.uid, info);
            update.put(path(c.chatId) + "/members/" + p.uid, info);
            update.put("chatIndex/" + p.uid + "/" + c.chatId, groupIndex(c.chatId, c.peerName, p.code));
            added = true;
        }
        if (members.length() > MAX_GROUP) throw new IOException("A group has at most " + MAX_GROUP + " members");
        if (!added) return;
        update.put(path(c.chatId) + "/size", members.length());
        Firebase.patch("", update);
        c.members = members.toString();
        store.updateConversation(c);
    }

    /** Leaves a group: gone from its members, and from this vault. Blocking. */
    static void leaveGroup(int conversationId) throws IOException {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        String uid = Account.uid();
        if (c == null || !c.isGroup || uid == null) return;
        try {
            long size = Firebase.get(path(c.chatId) + "/size", null) instanceof Number n ? n.longValue() : 1;
            JSONObject update = new JSONObject()
                    .put(path(c.chatId) + "/members/" + uid, JSONObject.NULL)
                    .put(path(c.chatId) + "/size", Math.max(0, size - 1))
                    .put("chatIndex/" + uid + "/" + c.chatId, JSONObject.NULL);
            Firebase.patch("", update);
        } catch (Firebase.Error e) {
            if (!e.refused()) throw e; // Already removed: leaving here is all that's left.
        }
        store.deleteConversation(c.id);
    }

    /** The group's owner, or null if it can't be read. Blocking. */
    static String groupOwner(int conversationId) {
        ChatStore.Conversation c = ChatStore.get().conversation(conversationId);
        if (c == null || !c.isGroup) return null;
        try {
            return Firebase.get(path(c.chatId) + "/meta/owner", null) instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The owner removes someone; what their device already has stays there. Blocking. */
    static void removeFromGroup(int conversationId, String member) throws IOException {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        if (c == null || !c.isGroup) return;
        JSONObject members = membersOf(Firebase.get(path(c.chatId) + "/members", null));
        if (!members.has(member)) return;
        members.remove(member);
        Firebase.patch("", new JSONObject().put(path(c.chatId) + "/members/" + member, JSONObject.NULL)
                .put(path(c.chatId) + "/size", members.length()));
        c.members = members.toString();
        store.updateConversation(c);
    }

    /** Renames a group for everyone. Blocking. */
    static void renameGroup(int conversationId, String name) throws IOException {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        if (c == null || !c.isGroup) return;
        Firebase.put(path(c.chatId) + "/meta/name", name);
        c.peerName = name;
        store.updateConversation(c);
    }

    /** {uid: {"code","name"}} from a members value. */
    private static JSONObject membersOf(Object value) {
        JSONObject out = new JSONObject();
        if (!(value instanceof JSONObject members)) return out;
        for (String uid : members.keySet()) {
            JSONObject m = members.optJSONObject(uid);
            if (m == null) continue;
            out.put(uid, new JSONObject().put("code", m.optString("code", null)).put("name", m.optString("name", null)));
        }
        return out;
    }

    // ============================================================ receiving

    /** Every conversation of every vault here, brought up to date. */
    private static void receiveAll(String uid) throws Exception {
        Map<String, String> places = ChatVaults.placements(uid);
        JSONObject index = Firebase.getObject("chatIndex/" + uid);
        if (index != null) {
            for (String chatId : index.keySet()) {
                JSONObject entry = index.optJSONObject(chatId);
                if (entry != null) receive(uid, chatId, entry, places.get(chatId));
            }
        }
        applyNicknames(uid);
        applyPhotos();
    }

    /** Their photo: the photo of the vault I'm talking to, else their account's. */
    private static void applyPhotos() {
        ChatStore store = ChatStore.get();
        Map<String, String> accountPhotos = new HashMap<>();
        for (ChatStore.Conversation c : store.allConversations()) {
            if (c.peerUid == null) continue;
            String code = c.peerShown != null ? c.peerShown : c.peerVault;
            String photo = code == null ? null : quietly("vaultPhotos/" + c.peerUid + "/" + code);
            if (photo == null) photo = accountPhotos.computeIfAbsent(c.peerUid, p -> quietly("profiles/" + p + "/photo"));
            if (!Objects.equals(photo, c.peerPhoto)) {
                c.peerPhoto = photo;
                store.updateConversation(c);
            }
        }
    }

    /** A photo is decoration: one that can't be read leaves the initial, never fails a sync. */
    private static String quietly(String path) {
        try {
            return Firebase.get(path, null) instanceof JSONObject o ? o.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyNicknames(String uid) throws IOException {
        ChatStore store = ChatStore.get();
        JSONObject nicks = Firebase.getObject("chatNick/" + uid);
        if (nicks == null) return;
        for (String chatId : nicks.keySet()) {
            ChatStore.Conversation c = store.conversationByChatId(chatId);
            String nick = nicks.opt(chatId) instanceof String s ? s : null;
            if (c != null && !Objects.equals(nick, c.nickname)) {
                c.nickname = nick;
                store.updateConversation(c);
            }
        }
    }

    /** null or "" clears it. Blocking. */
    static void setNickname(int conversationId, String nickname) {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        String uid = Account.uid();
        if (c == null) return;
        c.nickname = nickname == null || nickname.isBlank() ? null : nickname.trim();
        store.updateConversation(c);
        if (uid != null) {
            try {
                Firebase.put("chatNick/" + uid + "/" + c.chatId, c.nickname == null ? JSONObject.NULL : c.nickname);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Nickname not saved online", e);
            }
        }
    }

    /** A shared background colour ("#RRGGBB"), or null for the default. Both members see it. Blocking. */
    static void setThemeColor(int conversationId, String color) {
        setTheme(conversationId, color == null ? null : new JSONObject().put("kind", "color").put("value", color));
    }

    /** A shared background photo: encrypted, on my Drive, opened by link. Blocking; needs Drive. */
    static void setThemeImage(int conversationId, Path source) throws IOException {
        Drive drive = Account.drive();
        if (drive == null) throw new IOException("Connect Google Drive in your Notes+ account to use a photo");
        String temp = "theme_" + PushId.next();
        ChatMedia.Meta meta = ChatMedia.prepare(source, temp);
        meta.id = ChatMedia.upload(drive, temp);
        Files.move(ChatMedia.cached(temp), ChatMedia.cached(themeTag(meta.id)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        setTheme(conversationId, new JSONObject().put("kind", "image").put("media", meta.toJson()));
    }

    static String themeTag(String driveId) {
        return "t_" + driveId;
    }

    private static void setTheme(int conversationId, JSONObject theme) {
        ChatStore store = ChatStore.get();
        ChatStore.Conversation c = store.conversation(conversationId);
        if (c == null) return;
        c.theme = theme == null ? null : theme.toString();
        store.updateConversation(c);
        if (c.established) {
            try {
                Firebase.put(path(c.chatId) + "/theme", theme == null ? JSONObject.NULL : theme);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Background not shared", e);
            }
        }
    }

    /** One chatIndex entry: filed under the vault it belongs to, made if new, then fetched. */
    private static void receive(String uid, String chatId, JSONObject entry, String placed) throws Exception {
        ChatStore store = ChatStore.get();
        String code = entry.optString("vault", null);
        ChatStore.Vault vault = placed == null ? null : store.vaultByCode(placed);
        if (vault == null) vault = ChatVaults.reached(code);
        if (vault == null) return; // Deleted, or not on this computer yet.
        ChatStore.Conversation c = store.conversationByChatId(chatId);
        if (c != null && c.vaultId != vault.id && !c.moved) {
            c.vaultId = vault.id; // Moved on another device.
            store.updateConversation(c);
        }
        boolean group = isGroup(chatId);
        String peerName = entry.optString(group ? "name" : "peerName", null);
        if (c == null) {
            c = new ChatStore.Conversation();
            c.vaultId = vault.id;
            c.chatId = chatId;
            c.isGroup = group;
            c.peerUid = group ? null : entry.optString("peer", null);
            c.peerName = peerName;
            c.peerVault = entry.optString("peerVault", null);
            c.established = true;
            if (code != null && !code.equals(vault.code)) {
                // Written to an ID this vault had before: file it here, show them the new one.
                c.shownAs = vault.code;
                c.moved = true;
            }
            store.insertConversation(c);
        } else if (!group && peerName != null && !peerName.equals(c.peerName)) {
            c.peerName = peerName; // They changed their username.
            store.updateConversation(c);
        }
        try {
            fetch(uid, c, true);
        } catch (Firebase.Error e) {
            // A group I was removed from refuses me now; keep what's here, stop asking.
            if (!group || !e.refused()) throw e;
        }
    }

    /** New messages in, status of mine that can still change refreshed; theirs marked received. */
    private static void fetch(String uid, ChatStore.Conversation c, boolean receipts) throws Exception {
        ChatStore store = ChatStore.get();
        String chat = path(c.chatId);
        String from = c.cursor;
        String open = store.oldestOpen(c.id);
        if (open != null && (from == null || open.compareTo(from) < 0)) from = open;
        String query = "orderBy=" + enc("\"$key\"") + (from == null ? "" : "&startAt=" + enc("\"" + from + "\"")) + "&limitToLast=" + KEEP;
        Object messages = Firebase.get(chat + "/messages", query);
        Object theme = Firebase.get(chat + "/theme", null);
        c.theme = theme instanceof JSONObject t ? t.toString() : null;
        if (c.isGroup) {
            c.members = membersOf(Firebase.get(chat + "/members", null)).toString();
            if (Firebase.get(chat + "/meta/name", null) instanceof String name) c.peerName = name;
        }
        if (c.peerUid != null) {
            c.peerShown = Firebase.get(chat + "/alias/" + c.peerUid, null) instanceof String s ? s : null;
        }
        List<String> unacknowledged = new ArrayList<>();
        if (messages instanceof JSONObject all) {
            List<String> keys = new ArrayList<>(all.keySet());
            keys.sort(null);
            for (String key : keys) {
                JSONObject m = all.optJSONObject(key);
                if (m != null && apply(uid, c, key, m)) unacknowledged.add(key);
            }
        }
        store.updateConversation(c);
        if (receipts && !unacknowledged.isEmpty()) {
            JSONObject update = new JSONObject();
            for (String key : unacknowledged) update.put(key + receiptPath(c, "received", uid), Firebase.now());
            Firebase.patch(chat + "/messages", update);
        }
    }

    /** Two people: one "received"/"seen" each. A group: one per member ("rcv"/"seen" by uid). */
    private static String receiptPath(ChatStore.Conversation c, String kind, String uid) {
        if (!c.isGroup) return "/" + kind;
        return "/" + ("received".equals(kind) ? "rcv" : "seen") + "/" + uid;
    }

    /** True if every other member is in a group message's receipt map. */
    private static boolean everyone(ChatStore.Conversation c, String uid, JSONObject receipts) {
        if (receipts == null) return false;
        int others = 0;
        for (String m : json(c.members).keySet()) {
            if (m.equals(uid)) continue;
            others++;
            if (!receipts.has(m)) return false;
        }
        return others > 0;
    }

    /** Stores or updates one message. True if it is theirs and they don't know it arrived. */
    private static boolean apply(String uid, ChatStore.Conversation c, String key, JSONObject s) {
        ChatStore store = ChatStore.get();
        String from = s.optString("from", null);
        String text = s.optString("text", null);
        JSONObject mediaValue = s.optJSONObject("media");
        String media = mediaValue == null ? null : mediaValue.toString();
        if (from == null || (text == null && media == null)) return false;
        if (text == null) text = "";
        boolean mine = uid.equals(from);
        boolean received, seen, opened;
        if (!c.isGroup) {
            received = s.has("received");
            seen = s.has("seen");
            opened = s.has("opened");
        } else if (mine) {
            received = everyone(c, uid, s.optJSONObject("rcv"));
            seen = everyone(c, uid, s.optJSONObject("seen"));
            opened = everyone(c, uid, s.optJSONObject("opened"));
        } else {
            received = has(s.optJSONObject("rcv"), uid);
            seen = has(s.optJSONObject("seen"), uid);
            opened = has(s.optJSONObject("opened"), uid);
        }
        ChatStore.Message local = store.message(key);
        if (local == null) {
            ChatStore.Message m = new ChatStore.Message();
            m.threadId = c.id;
            m.msgKey = key;
            m.mine = mine;
            m.text = text;
            m.media = opened ? flag(media, "opened") : media;
            if (c.isGroup && !mine) {
                m.sender = from;
                JSONObject info = json(c.members).optJSONObject(from);
                m.senderName = info == null ? null : info.optString("name", null);
            }
            m.at = s.has("sentAt") ? s.optLong("sentAt") : System.currentTimeMillis();
            // Mine: how far it got. Theirs: here now, and seen only if I saw it elsewhere.
            m.status = mine ? (seen ? ChatStore.SEEN : received ? ChatStore.RECEIVED : ChatStore.SENT)
                    : (seen ? ChatStore.SEEN : ChatStore.RECEIVED);
            store.insertMessage(m);
            if (!mine && !seen) c.unread++;
            if (c.cursor == null || key.compareTo(c.cursor) >= 0) {
                c.lastText = (m.senderName != null ? m.senderName + ": " : "") + preview(text, media);
                c.lastAt = m.at;
            }
        } else {
            if (mine) store.advance(key, seen ? ChatStore.SEEN : received ? ChatStore.RECEIVED : ChatStore.SENT);
            ChatMedia.Meta meta = ChatMedia.Meta.parse(local.media);
            if (meta != null && meta.once && opened && !hasFlag(local.media, "opened")) {
                store.setMedia(key, flag(local.media, "opened"));
                // Opened by everyone it was for: its file leaves the sender's Drive for good.
                Drive drive = mine && meta.id != null ? Account.drive() : null;
                if (drive != null) {
                    try {
                        drive.delete(meta.id);
                    } catch (IOException e) {
                        LOG.log(System.Logger.Level.WARNING, "Couldn't remove an opened view-once file", e);
                    }
                }
            }
        }
        if (c.cursor == null || key.compareTo(c.cursor) > 0) c.cursor = key;
        return !mine && !received;
    }

    private static boolean has(JSONObject o, String key) {
        return o != null && o.has(key);
    }

    /** Local-only marks on a message's media JSON (e.g. "opened"). */
    static String flag(String media, String name) {
        if (media == null) return null;
        try {
            return new JSONObject(media).put(name, true).toString();
        } catch (Exception e) {
            return media;
        }
    }

    static boolean hasFlag(String media, String name) {
        if (media == null) return false;
        try {
            return new JSONObject(media).optBoolean(name, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** A view-once photo or video was just looked at: its copy here goes, and the sender hears so. */
    static void markOpened(String msgKey) {
        LIVE.execute(() -> {
            ChatStore store = ChatStore.get();
            ChatStore.Message m = store.message(msgKey);
            if (m == null) return;
            store.setMedia(msgKey, flag(m.media, "opened"));
            ChatMedia.forget(msgKey);
            ChatStore.Conversation c = store.conversation(m.threadId);
            String uid = Account.uid();
            if (c == null || uid == null) return;
            try {
                Firebase.put(path(c.chatId) + "/messages/" + msgKey + "/opened" + (c.isGroup ? "/" + uid : ""), Firebase.now());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Couldn't say it was opened", e);
            }
        });
    }

    /** The conversation is on screen: tell them it was seen. */
    static void markSeen(int conversationId) {
        LIVE.execute(() -> {
            ChatStore store = ChatStore.get();
            ChatStore.Conversation c = store.conversation(conversationId);
            if (c == null) return;
            if (c.unread > 0) store.markRead(conversationId);
            List<String> keys = store.unseen(conversationId);
            if (keys.isEmpty()) return; // No write, so observers don't wake for nothing.
            store.markSeen(conversationId);
            String uid = Account.uid();
            if (uid == null) return;
            JSONObject update = new JSONObject();
            for (String key : keys) update.put(key + receiptPath(c, "seen", uid), Firebase.now());
            try {
                Firebase.patch(path(c.chatId) + "/messages", update);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Couldn't mark seen", e);
            }
        });
    }

    // ================================================================= live

    /** Live while a chat screen is visible: new conversations and messages. Returns the stopper. */
    static Runnable watchIndex() {
        String uid = Account.uid();
        if (uid == null) return () -> {};
        AtomicBoolean pending = new AtomicBoolean();
        Firebase.Listener l = Firebase.listen("chatIndex/" + uid, null, (event, data) -> {
            String p = data.optString("path", "/");
            String chatId = p.equals("/") ? null : p.substring(1).split("/")[0];
            if (chatId == null) {
                if (pending.compareAndSet(false, true)) background(() -> {
                    pending.set(false);
                    receiveAll(uid);
                });
                return;
            }
            background(() -> {
                JSONObject entry = Firebase.getObject("chatIndex/" + uid + "/" + chatId);
                if (entry == null) return;
                String placed = Firebase.get("chatPlace/" + uid + "/" + chatId, null) instanceof String s ? s : null;
                receive(uid, chatId, entry, placed);
            });
        });
        return l::close;
    }

    /** Live messages and background of one conversation. Returns the stopper. */
    static Runnable watchConversation(int conversationId) {
        String uid = Account.uid();
        if (uid == null) return () -> {};
        ChatStore.Conversation c = ChatStore.get().conversation(conversationId);
        if (c == null || !c.established) return () -> {};
        AtomicBoolean pending = new AtomicBoolean();
        Runnable refresh = () -> {
            if (pending.compareAndSet(false, true)) background(() -> {
                pending.set(false);
                ChatStore.Conversation now = ChatStore.get().conversation(conversationId);
                if (now != null) fetch(uid, now, true);
            });
        };
        String chat = path(c.chatId);
        Firebase.Listener messages = Firebase.listen(chat + "/messages",
                "orderBy=" + enc("\"$key\"") + "&limitToLast=" + KEEP, (event, data) -> refresh.run());
        Firebase.Listener theme = Firebase.listen(chat + "/theme", null, (event, data) -> refresh.run());
        return () -> {
            messages.close();
            theme.close();
        };
    }

    /** Read-only polling: everything, not live. */
    static void pollAll() {
        String uid = Account.uid();
        if (uid != null) background(() -> receiveAll(uid));
    }

    /** Read-only polling of one open conversation (no receipts: another device is the writer). */
    static void pollConversation(int conversationId) {
        String uid = Account.uid();
        if (uid == null) return;
        background(() -> {
            ChatStore.Conversation c = ChatStore.get().conversation(conversationId);
            if (c != null) fetch(uid, c, false);
        });
    }

    private interface Step {
        void run() throws Exception;
    }

    /** One at a time with the job; if the job runs, it'll pick this up, so queue another run instead. */
    private static void background(Step step) {
        LIVE.execute(() -> {
            if (!LOCK.tryLock()) {
                schedule();
                return;
            }
            try {
                step.run();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Live update failed", e);
            } finally {
                LOCK.unlock();
            }
        });
    }

    // ================================================================== job

    /** Sends what waits and fetches what's new, soon. No-op signed out. */
    static void schedule() {
        schedule(300);
    }

    private static void schedule(long delayMs) {
        if (Account.uid() == null || !QUEUED.compareAndSet(false, true)) return;
        SYNC.schedule(ChatSync::job, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void job() {
        QUEUED.set(false);
        try {
            run();
            failures = 0;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Chat sync failed; will retry", e);
            if (++failures <= 8) schedule(10_000L * failures);
        }
    }

    /** Messages arrive (and are marked received) while the app is open, chat space or not. */
    static void startPeriodic() {
        SYNC.scheduleAtFixedRate(() -> schedule(0), 15, 15, TimeUnit.MINUTES);
    }

    static void run() throws Exception {
        String uid = Account.uid();
        if (uid == null) return;
        if (!ChatVaults.boundTo(uid)) return; // This computer's chats belong to another account.
        if (ChatStore.get().mainVault() == null) {
            // Chat not opened here yet: still bring the account's vaults, ready for the first time.
            ChatVaults.sync(uid);
            return;
        }
        LOCK.lock();
        try {
            // Each stage on its own: one failing must not hold messages back.
            Exception failure = null;
            Step[] stages = {() -> publishHome(uid), () -> ChatVaults.sync(uid), () -> sendWaiting(uid), () -> receiveAll(uid)};
            boolean refusedAll = true;
            for (Step stage : stages) {
                try {
                    stage.run();
                    refusedAll = false;
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "Chat sync stage failed", e);
                    if (!refused(e)) refusedAll = false;
                    if (failure == null) failure = e;
                }
            }
            // Every step turned away: the server's rules don't allow chat. Say so on screen.
            Prefs.put(PREF_REFUSED, String.valueOf(refusedAll));
            if (failure != null) throw failure;
        } finally {
            LOCK.unlock();
            Platform.runLater(() -> {});
        }
    }

    /** Tells others which vault an address without an ID reaches: the account's main vault. */
    private static void publishHome(String uid) throws IOException {
        ChatStore.Vault main = ChatStore.get().mainVault();
        if (main == null || main.code.equals(Prefs.get(PREF_HOME + uid))) return;
        Firebase.put("chatHome/" + uid, main.code);
        Prefs.put(PREF_HOME + uid, main.code);
    }
}
