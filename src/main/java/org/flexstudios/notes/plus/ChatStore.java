package org.flexstudios.notes.plus;

import javafx.application.Platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Local storage of the chat space, the phone's ChatStore: its vaults, conversations and
 * messages, in a database of their own ({@code chat/chat.db}), so nothing that reads another
 * part of the app can ever return a chat row. Messages live here in full; online only the
 * latest 100 per conversation are kept.
 */
final class ChatStore {

    /** Message states, in the order they advance. Failed is "not sent". */
    static final int FAILED = -1, WAITING = 0, SENT = 1, RECEIVED = 2, SEEN = 3;

    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-io");
        t.setDaemon(true);
        return t;
    });

    /** One chat vault: its own lock (hashed), and the 4-character ID people add to its address. */
    static final class Vault {
        int id;
        String name, lockType, lockValue, code = "", photo, background, renamedTo;
        boolean isMain, dirty = true, deleted;
        long createdAt, updatedAt;
    }

    /** One conversation: this vault with one person's vault, or a group ("g_" + its id). */
    static final class Conversation {
        int id, vaultId, unread;
        String chatId = "", peerUid, peerName, peerVault, lastText, cursor, nickname, theme, shownAs, peerShown,
                peerPhoto, members;
        long lastAt;
        boolean established, moved, isGroup;
    }

    static final class Message {
        int id, threadId, status;
        String msgKey = "", text, media, sender, senderName;
        boolean mine;
        long at;
    }

    private static ChatStore instance;
    private final Connection db;
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();

    private ChatStore() throws SQLException {
        java.nio.file.Path dir = Storage.root().resolve("chat");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new SQLException(e);
        }
        db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("chat.db"));
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("CREATE TABLE IF NOT EXISTS chat_vaults (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lockType TEXT, "
                    + "lockValue TEXT, code TEXT NOT NULL UNIQUE, isMain INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, "
                    + "updatedAt INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, deleted INTEGER NOT NULL DEFAULT 0, "
                    + "photo TEXT, background TEXT, renamedTo TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS chat_threads (id INTEGER PRIMARY KEY AUTOINCREMENT, vaultId INTEGER NOT NULL, "
                    + "chatId TEXT NOT NULL, peerUid TEXT, peerName TEXT, peerVault TEXT, lastText TEXT, lastAt INTEGER NOT NULL DEFAULT 0, "
                    + "unread INTEGER NOT NULL DEFAULT 0, cursor TEXT, established INTEGER NOT NULL DEFAULT 0, moved INTEGER NOT NULL DEFAULT 0, "
                    + "nickname TEXT, theme TEXT, shownAs TEXT, peerShown TEXT, peerPhoto TEXT, isGroup INTEGER NOT NULL DEFAULT 0, members TEXT)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_threads_chat ON chat_threads (vaultId, chatId)");
            s.execute("CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, threadId INTEGER NOT NULL, "
                    + "msgKey TEXT NOT NULL UNIQUE, mine INTEGER NOT NULL, text TEXT, at INTEGER NOT NULL, status INTEGER NOT NULL, "
                    + "media TEXT, sender TEXT, senderName TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS index_messages_thread ON chat_messages (threadId, msgKey)");
        }
    }

    static synchronized ChatStore get() {
        if (instance == null) {
            try {
                instance = new ChatStore();
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot open the chat database", e);
            }
        }
        return instance;
    }

    /** Runs {@code onChange} on the FX thread after every write: the phone's LiveData. */
    void observe(Runnable onChange) {
        observers.add(onChange);
    }

    void unobserve(Runnable onChange) {
        observers.remove(onChange);
    }

    private void changed() {
        Platform.runLater(() -> observers.forEach(Runnable::run));
    }

    static <T> void load(Function<ChatStore, T> query, Consumer<T> then) {
        IO.execute(() -> {
            T r;
            try {
                r = query.apply(get());
            } catch (RuntimeException e) {
                System.getLogger("Chat").log(System.Logger.Level.WARNING, "Chat query failed", e);
                r = null;
            }
            T result = r;
            Platform.runLater(() -> then.accept(result));
        });
    }

    static void write(Consumer<ChatStore> work) {
        IO.execute(() -> {
            try {
                work.accept(get());
            } catch (RuntimeException e) {
                System.getLogger("Chat").log(System.Logger.Level.WARNING, "Chat write failed", e);
            }
        });
    }

    // =============================================================== vaults

    synchronized List<Vault> vaults() {
        return vaults("SELECT * FROM chat_vaults WHERE deleted = 0 ORDER BY isMain DESC, createdAt");
    }

    synchronized Vault vault(int id) {
        List<Vault> v = vaults("SELECT * FROM chat_vaults WHERE id = ? AND deleted = 0", id);
        return v.isEmpty() ? null : v.get(0);
    }

    synchronized Vault vaultByCode(String code) {
        List<Vault> v = vaults("SELECT * FROM chat_vaults WHERE code = ? AND deleted = 0", code);
        return v.isEmpty() ? null : v.get(0);
    }

    /** Tombstones included: an ID once used stays taken. */
    synchronized Vault vaultByCodeAny(String code) {
        List<Vault> v = vaults("SELECT * FROM chat_vaults WHERE code = ?", code);
        return v.isEmpty() ? null : v.get(0);
    }

    synchronized List<Vault> dirtyVaults() {
        return vaults("SELECT * FROM chat_vaults WHERE dirty = 1");
    }

    synchronized Vault mainVault() {
        List<Vault> v = vaults("SELECT * FROM chat_vaults WHERE isMain = 1 AND deleted = 0 ORDER BY createdAt LIMIT 1");
        return v.isEmpty() ? null : v.get(0);
    }

    synchronized int insertVault(Vault v) {
        v.id = insert("INSERT INTO chat_vaults (name, lockType, lockValue, code, isMain, createdAt, updatedAt, dirty, deleted, photo, background, renamedTo) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", v.name, v.lockType, v.lockValue, v.code, v.isMain, v.createdAt, v.updatedAt,
                v.dirty, v.deleted, v.photo, v.background, v.renamedTo);
        return v.id;
    }

    synchronized void updateVault(Vault v) {
        update("UPDATE chat_vaults SET name = ?, lockType = ?, lockValue = ?, code = ?, isMain = ?, createdAt = ?, updatedAt = ?, "
                        + "dirty = ?, deleted = ?, photo = ?, background = ?, renamedTo = ? WHERE id = ?", v.name, v.lockType, v.lockValue,
                v.code, v.isMain, v.createdAt, v.updatedAt, v.dirty, v.deleted, v.photo, v.background, v.renamedTo, v.id);
    }

    // ========================================================= conversations

    synchronized List<Conversation> conversations(int vaultId) {
        return threads("SELECT * FROM chat_threads WHERE vaultId = ? ORDER BY lastAt DESC", vaultId);
    }

    synchronized Conversation conversation(int id) {
        List<Conversation> c = threads("SELECT * FROM chat_threads WHERE id = ?", id);
        return c.isEmpty() ? null : c.get(0);
    }

    /** A conversation lives in exactly one vault here, wherever it was moved. */
    synchronized Conversation conversationByChatId(String chatId) {
        List<Conversation> c = threads("SELECT * FROM chat_threads WHERE chatId = ? LIMIT 1", chatId);
        return c.isEmpty() ? null : c.get(0);
    }

    synchronized List<Conversation> conversationsIn(int vaultId) {
        return threads("SELECT * FROM chat_threads WHERE vaultId = ?", vaultId);
    }

    synchronized List<Conversation> movedConversations() {
        return threads("SELECT * FROM chat_threads WHERE moved = 1");
    }

    synchronized List<Conversation> allConversations() {
        return threads("SELECT * FROM chat_threads");
    }

    synchronized int insertConversation(Conversation c) {
        c.id = insert("INSERT INTO chat_threads (vaultId, chatId, peerUid, peerName, peerVault, lastText, lastAt, unread, cursor, established, "
                        + "moved, nickname, theme, shownAs, peerShown, peerPhoto, isGroup, members) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.vaultId, c.chatId, c.peerUid, c.peerName, c.peerVault, c.lastText, c.lastAt, c.unread, c.cursor, c.established,
                c.moved, c.nickname, c.theme, c.shownAs, c.peerShown, c.peerPhoto, c.isGroup, c.members);
        return c.id;
    }

    synchronized void updateConversation(Conversation c) {
        update("UPDATE chat_threads SET vaultId = ?, chatId = ?, peerUid = ?, peerName = ?, peerVault = ?, lastText = ?, lastAt = ?, "
                        + "unread = ?, cursor = ?, established = ?, moved = ?, nickname = ?, theme = ?, shownAs = ?, peerShown = ?, peerPhoto = ?, "
                        + "isGroup = ?, members = ? WHERE id = ?", c.vaultId, c.chatId, c.peerUid, c.peerName, c.peerVault, c.lastText,
                c.lastAt, c.unread, c.cursor, c.established, c.moved, c.nickname, c.theme, c.shownAs, c.peerShown, c.peerPhoto,
                c.isGroup, c.members, c.id);
    }

    synchronized void markRead(int id) {
        update("UPDATE chat_threads SET unread = 0 WHERE id = ?", id);
    }

    synchronized void deleteConversation(int id) {
        update("DELETE FROM chat_messages WHERE threadId = ?", id);
        update("DELETE FROM chat_threads WHERE id = ?", id);
    }

    synchronized void deleteConversationsOf(int vaultId) {
        update("DELETE FROM chat_messages WHERE threadId IN (SELECT id FROM chat_threads WHERE vaultId = ?)", vaultId);
        update("DELETE FROM chat_threads WHERE vaultId = ?", vaultId);
    }

    // ============================================================== messages

    /** Ignored when the key is already here: a message is stored once. */
    synchronized void insertMessage(Message m) {
        update("INSERT OR IGNORE INTO chat_messages (threadId, msgKey, mine, text, at, status, media, sender, senderName) VALUES (?,?,?,?,?,?,?,?,?)",
                m.threadId, m.msgKey, m.mine, m.text, m.at, m.status, m.media, m.sender, m.senderName);
    }

    synchronized List<Message> messages(int threadId) {
        return messages("SELECT * FROM chat_messages WHERE threadId = ? ORDER BY msgKey", threadId);
    }

    synchronized Message message(String key) {
        List<Message> m = messages("SELECT * FROM chat_messages WHERE msgKey = ?", key);
        return m.isEmpty() ? null : m.get(0);
    }

    synchronized List<Message> waiting() {
        return messages("SELECT * FROM chat_messages WHERE mine = 1 AND status = 0 ORDER BY msgKey");
    }

    /** Moves a message forward only: a late "sent" never undoes "seen". */
    synchronized void advance(String key, int status) {
        update("UPDATE chat_messages SET status = ? WHERE msgKey = ? AND status < ?", status, key, status);
    }

    synchronized void setStatus(String key, int status) {
        update("UPDATE chat_messages SET status = ? WHERE msgKey = ?", status, key);
    }

    synchronized void setMedia(String key, String media) {
        update("UPDATE chat_messages SET media = ? WHERE msgKey = ?", media, key);
    }

    /** The oldest message of mine whose status can still change. */
    synchronized String oldestOpen(int threadId) {
        try (PreparedStatement p = db.prepareStatement(
                "SELECT MIN(msgKey) FROM chat_messages WHERE threadId = ? AND mine = 1 AND status IN (1, 2)")) {
            p.setInt(1, threadId);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized List<String> unseen(int threadId) {
        List<String> out = new ArrayList<>();
        for (Message m : messages("SELECT * FROM chat_messages WHERE threadId = ? AND mine = 0 AND status < 3", threadId)) out.add(m.msgKey);
        return out;
    }

    synchronized void markSeen(int threadId) {
        update("UPDATE chat_messages SET status = 3 WHERE threadId = ? AND mine = 0", threadId);
    }

    // =============================================================== helpers

    private List<Vault> vaults(String sql, Object... args) {
        List<Vault> out = new ArrayList<>();
        query(sql, r -> {
            Vault v = new Vault();
            v.id = r.getInt("id");
            v.name = r.getString("name");
            v.lockType = r.getString("lockType");
            v.lockValue = r.getString("lockValue");
            v.code = r.getString("code");
            v.isMain = r.getInt("isMain") != 0;
            v.createdAt = r.getLong("createdAt");
            v.updatedAt = r.getLong("updatedAt");
            v.dirty = r.getInt("dirty") != 0;
            v.deleted = r.getInt("deleted") != 0;
            v.photo = r.getString("photo");
            v.background = r.getString("background");
            v.renamedTo = r.getString("renamedTo");
            out.add(v);
        }, args);
        return out;
    }

    private List<Conversation> threads(String sql, Object... args) {
        List<Conversation> out = new ArrayList<>();
        query(sql, r -> {
            Conversation c = new Conversation();
            c.id = r.getInt("id");
            c.vaultId = r.getInt("vaultId");
            c.chatId = r.getString("chatId");
            c.peerUid = r.getString("peerUid");
            c.peerName = r.getString("peerName");
            c.peerVault = r.getString("peerVault");
            c.lastText = r.getString("lastText");
            c.lastAt = r.getLong("lastAt");
            c.unread = r.getInt("unread");
            c.cursor = r.getString("cursor");
            c.established = r.getInt("established") != 0;
            c.moved = r.getInt("moved") != 0;
            c.nickname = r.getString("nickname");
            c.theme = r.getString("theme");
            c.shownAs = r.getString("shownAs");
            c.peerShown = r.getString("peerShown");
            c.peerPhoto = r.getString("peerPhoto");
            c.isGroup = r.getInt("isGroup") != 0;
            c.members = r.getString("members");
            out.add(c);
        }, args);
        return out;
    }

    private List<Message> messages(String sql, Object... args) {
        List<Message> out = new ArrayList<>();
        query(sql, r -> {
            Message m = new Message();
            m.id = r.getInt("id");
            m.threadId = r.getInt("threadId");
            m.msgKey = r.getString("msgKey");
            m.mine = r.getInt("mine") != 0;
            m.text = r.getString("text");
            m.at = r.getLong("at");
            m.status = r.getInt("status");
            m.media = r.getString("media");
            m.sender = r.getString("sender");
            m.senderName = r.getString("senderName");
            out.add(m);
        }, args);
        return out;
    }

    private interface Row {
        void read(ResultSet r) throws SQLException;
    }

    private void query(String sql, Row row, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) row.read(r);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int insert(String sql, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, args);
            p.executeUpdate();
            try (ResultSet k = p.getGeneratedKeys()) {
                changed();
                return k.next() ? k.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(String sql, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            if (p.executeUpdate() > 0) changed();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Boolean b) p.setInt(i + 1, b ? 1 : 0);
            else p.setObject(i + 1, a);
        }
    }
}
