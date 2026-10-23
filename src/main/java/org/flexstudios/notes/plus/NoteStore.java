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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The notes database: SQLite with the phone's {@code notes} table, column for column, so
 * account sync can move rows between the two unchanged.
 *
 * Every read and write runs on {@link #IO}, one thread, so an insert always lands before the
 * update that follows it (the phone needs a separate writer for that; here it comes free).
 * Screens hop back with {@link Platform#runLater}. Each write tells the {@link #observe}rs,
 * which is how the grid stays current, like the phone's LiveData.
 */
final class NoteStore {

    private static final System.Logger LOG = System.getLogger("NoteStore");

    /** The one background thread for the database and for note files. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notes-io");
        t.setDaemon(true);
        return t;
    });

    private static NoteStore instance;

    private final Connection db;
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();

    private NoteStore() throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + Storage.database());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("CREATE TABLE IF NOT EXISTS notes ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT, content TEXT, "
                    + "timestamp INTEGER NOT NULL, type TEXT, data TEXT, color INTEGER NOT NULL DEFAULT 0, "
                    + "pinned INTEGER NOT NULL DEFAULT 0, uuid TEXT, account TEXT, rev INTEGER NOT NULL DEFAULT 0, "
                    + "dirty INTEGER NOT NULL DEFAULT 1, deleted INTEGER NOT NULL DEFAULT 0, media TEXT, "
                    + "groupId TEXT, groupMembers TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS index_notes_pinned_timestamp ON notes (pinned, timestamp)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_notes_uuid ON notes (uuid)");
            int version;
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
                version = r.next() ? r.getInt(1) : 0;
            }
            if (version < 2) {
                // Notes sent to people (mine), and notes sent to me (theirs, read-only).
                for (String column : new String[]{"sentTo", "sentMedia", "sharedFrom"}) {
                    try {
                        s.execute("ALTER TABLE notes ADD COLUMN " + column + " TEXT");
                    } catch (SQLException exists) {
                        // Added by an interrupted earlier start.
                    }
                }
            }
            s.execute("PRAGMA user_version = 2");
        }
    }

    static synchronized NoteStore get() {
        if (instance == null) {
            try {
                instance = new NoteStore();
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot open " + Storage.database(), e);
            }
        }
        return instance;
    }

    // ================================================================= observe

    /** Runs {@code onChange} on the FX thread after every write. */
    void observe(Runnable onChange) {
        observers.add(onChange);
    }

    private void changed() {
        Platform.runLater(() -> observers.forEach(Runnable::run));
    }

    /** Tells the observers something they show changed outside the table (a file arrived). */
    void notifyChanged() {
        changed();
    }

    /** Runs {@code query} on {@link #IO} and hands the result to {@code then} on the FX thread. */
    static <T> void load(java.util.function.Function<NoteStore, T> query, Consumer<T> then) {
        IO.execute(() -> {
            T result = query.apply(get());
            Platform.runLater(() -> then.accept(result));
        });
    }

    /** Runs {@code write} on {@link #IO}. */
    static void write(Consumer<NoteStore> write) {
        IO.execute(() -> write.accept(get()));
    }

    /** Lets queued writes land before the app exits. */
    static void drain() {
        IO.shutdown();
        try {
            if (!IO.awaitTermination(10, TimeUnit.SECONDS)) LOG.log(System.Logger.Level.WARNING, "Writes still pending");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =================================================================== reads

    /** Pinned first, then newest. Tombstones hidden. */
    synchronized List<NoteEntity> all() {
        return query("SELECT * FROM notes WHERE deleted = 0 ORDER BY pinned DESC, timestamp DESC");
    }

    synchronized NoteEntity byId(int id) {
        List<NoteEntity> found = query("SELECT * FROM notes WHERE id = ?", id);
        return found.isEmpty() ? null : found.get(0);
    }

    synchronized NoteEntity byUuid(String uuid) {
        List<NoteEntity> found = query("SELECT * FROM notes WHERE uuid = ?", uuid);
        return found.isEmpty() ? null : found.get(0);
    }

    // ================================================================== writes

    synchronized int insert(NoteEntity n) {
        String sql = "INSERT INTO notes (title, content, timestamp, type, data, color, pinned, uuid, account, rev, "
                + "dirty, deleted, media, groupId, groupMembers, sentTo, sentMedia, sharedFrom) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement p = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, n.getTitle(), n.getContent(), n.getTimestamp(), n.getType(), n.getData(), n.getColor(),
                    n.isPinned(), n.getUuid(), n.getAccount(), n.getRev(), n.isDirty(), n.isDeleted(), n.getMedia(),
                    n.getGroupId(), n.getGroupMembers(), n.getSentTo(), n.getSentMedia(), n.getSharedFrom());
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                int id = keys.next() ? keys.getInt(1) : 0;
                changed();
                return id;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Insert failed", e);
        }
    }

    /**
     * An editor's save: content only, so it can never overwrite sync state that changed while
     * the editor was open.
     */
    synchronized void saveContent(int id, String title, String content, String data, int color, boolean pinned, long timestamp) {
        update("UPDATE notes SET title = ?, content = ?, data = ?, color = ?, pinned = ?, timestamp = ?, dirty = 1 "
                + "WHERE id = ?", title, content, data, color, pinned, timestamp, id);
    }

    synchronized void setPinned(int id, boolean pinned) {
        update("UPDATE notes SET pinned = ?, dirty = 1 WHERE id = ?", pinned, id);
    }

    synchronized void setColor(int id, int color) {
        update("UPDATE notes SET color = ?, dirty = 1 WHERE id = ?", color, id);
    }

    /** Hidden at once; removed for good with {@link #forget} once Undo has expired. */
    synchronized void setDeleted(int id, boolean deleted) {
        update("UPDATE notes SET deleted = ?, dirty = 1 WHERE id = ?", deleted, id);
    }

    /**
     * The end of a deleted note: its files go, and the row too unless the account still has
     * to hear about it (then the next sync sends the tombstone and purges it).
     */
    synchronized void forget(int id) {
        NoteEntity n = byId(id);
        if (n == null || !n.isDeleted()) return;
        NoteData.deleteFiles(id);
        // In an account, the next sync tells it and purges the row; otherwise it goes now.
        if (n.getAccount() == null || n.getRev() == 0) update("DELETE FROM notes WHERE id = ?", id);
        else NoteSync.schedule();
    }

    /** Sweeps deletes that never completed (the app closed with Undo on screen). */
    synchronized void sweepTombstones() {
        for (NoteEntity n : query("SELECT * FROM notes WHERE deleted = 1 AND account IS NULL")) forget(n.getId());
    }

    // ==================================================================== sync

    /** A whole row, as sync writes it. */
    synchronized void update(NoteEntity n) {
        update("UPDATE notes SET title = ?, content = ?, timestamp = ?, type = ?, data = ?, color = ?, pinned = ?, "
                        + "uuid = ?, account = ?, rev = ?, dirty = ?, deleted = ?, media = ?, groupId = ?, groupMembers = ?, "
                        + "sentTo = ?, sentMedia = ?, sharedFrom = ? WHERE id = ?", n.getTitle(), n.getContent(), n.getTimestamp(),
                n.getType(), n.getData(), n.getColor(), n.isPinned(), n.getUuid(), n.getAccount(), n.getRev(), n.isDirty(),
                n.isDeleted(), n.getMedia(), n.getGroupId(), n.getGroupMembers(), n.getSentTo(), n.getSentMedia(),
                n.getSharedFrom(), n.getId());
    }

    /** Received notes are someone else's: never pushed from here. */
    synchronized List<NoteEntity> dirtyNotes(String account) {
        return query("SELECT * FROM notes WHERE account = ? AND dirty = 1 AND sharedFrom IS NULL", account);
    }

    synchronized int countDirty(String account) {
        return dirtyNotes(account).size();
    }

    /** Notes here outside {@code account}: offered when signing in. */
    synchronized int countOutside(String account) {
        return query("SELECT * FROM notes WHERE deleted = 0 AND (account IS NULL OR account != ?)", account).size();
    }

    /** Brings every other note here into {@code account}, as new notes there. */
    synchronized void adopt(String account) {
        update("UPDATE notes SET account = ?, rev = 0, dirty = 1, media = NULL, groupId = NULL, groupMembers = NULL "
                + "WHERE account IS NULL OR account != ?", account, account);
    }

    /** Leaving an account but keeping its notes: they become this computer's own again. */
    synchronized void release(String account) {
        update("UPDATE notes SET account = NULL, rev = 0, dirty = 1, media = NULL, groupId = NULL, groupMembers = NULL, "
                + "sentTo = NULL, sentMedia = NULL WHERE account = ? AND sharedFrom IS NULL", account);
    }

    synchronized List<NoteEntity> notesOf(String account) {
        return query("SELECT * FROM notes WHERE account = ?", account);
    }

    /** Deleted notes nobody else needs to hear about. */
    synchronized List<NoteEntity> localTombstones() {
        return query("SELECT * FROM notes WHERE deleted = 1 AND account IS NULL");
    }

    /** The server took {@code rev}; still dirty if the note was edited again meanwhile. */
    synchronized void markSynced(int id, long rev, long timestamp) {
        update("UPDATE notes SET rev = ?, dirty = CASE WHEN timestamp = ? THEN 0 ELSE dirty END WHERE id = ?",
                rev, timestamp, id);
    }

    /** A new Drive manifest, which the account has to hear about. */
    synchronized void setMedia(int id, String media) {
        update("UPDATE notes SET media = ?, dirty = 1 WHERE id = ?", media, id);
    }

    synchronized void purge(int id) {
        update("DELETE FROM notes WHERE id = ?", id);
    }

    /** Mine, sent to people. */
    synchronized List<NoteEntity> sentNotes(String account) {
        return query("SELECT * FROM notes WHERE account = ? AND sentTo IS NOT NULL AND sharedFrom IS NULL", account);
    }

    /** Theirs, sent to me. */
    synchronized List<NoteEntity> receivedNotes(String account) {
        return query("SELECT * FROM notes WHERE account = ? AND sharedFrom IS NOT NULL", account);
    }

    synchronized void setSentTo(int id, String sentTo) {
        update("UPDATE notes SET sentTo = ? WHERE id = ?", sentTo, id);
    }

    synchronized void setSentMedia(int id, String sentMedia) {
        update("UPDATE notes SET sentMedia = ? WHERE id = ?", sentMedia, id);
    }

    /** Now shared: synced through groupNotes/{groupId}, at its first revision there. */
    synchronized void makeGroup(int id, String groupId, String members, long rev, boolean dirty) {
        update("UPDATE notes SET groupId = ?, groupMembers = ?, rev = ?, media = NULL, dirty = ? WHERE id = ?",
                groupId, members, rev, dirty, id);
    }

    synchronized List<NoteEntity> groupNotes(String account) {
        return query("SELECT * FROM notes WHERE account = ? AND groupId IS NOT NULL", account);
    }

    synchronized void setGroupMembers(int id, String members) {
        update("UPDATE notes SET groupMembers = ? WHERE id = ?", members, id);
    }

    /** No longer shared with me: it stays, as my own note again. */
    synchronized void leaveGroup(int id) {
        update("UPDATE notes SET groupId = NULL, groupMembers = NULL, rev = 0, media = NULL, dirty = 1 WHERE id = ?", id);
    }

    // ================================================================= helpers

    private void update(String sql, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            p.executeUpdate();
            changed();
        } catch (SQLException e) {
            throw new IllegalStateException("Update failed: " + sql, e);
        }
    }

    private List<NoteEntity> query(String sql, Object... args) {
        List<NoteEntity> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(read(r));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
        return out;
    }

    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Boolean b) p.setInt(i + 1, b ? 1 : 0);
            else p.setObject(i + 1, a);
        }
    }

    private static NoteEntity read(ResultSet r) throws SQLException {
        NoteEntity n = new NoteEntity(r.getString("title"), r.getString("content"), r.getLong("timestamp"));
        n.setId(r.getInt("id"));
        n.setType(r.getString("type"));
        n.setData(r.getString("data"));
        n.setColor(r.getInt("color"));
        n.setPinned(r.getInt("pinned") != 0);
        n.setUuid(r.getString("uuid"));
        n.setAccount(r.getString("account"));
        n.setRev(r.getLong("rev"));
        n.setDirty(r.getInt("dirty") != 0);
        n.setDeleted(r.getInt("deleted") != 0);
        n.setMedia(r.getString("media"));
        n.setGroupId(r.getString("groupId"));
        n.setGroupMembers(r.getString("groupMembers"));
        n.setSentTo(r.getString("sentTo"));
        n.setSentMedia(r.getString("sentMedia"));
        n.setSharedFrom(r.getString("sharedFrom"));
        return n;
    }
}
