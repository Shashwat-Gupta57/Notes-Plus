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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The gallery vault's records: the phone's {@code vaults}, {@code secrets} and {@code albums}
 * tables, in their own database file ({@code vault/vault.db}) so nothing that reads the
 * notes can ever return a vault row.
 *
 * One difference from the phone, on purpose: a vault's lock is stored as a hash, never as
 * the PIN itself (see {@link VaultCrypto#hashLock}).
 */
final class VaultStore {

    /** One thread for the vault's database and its files. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vault-io");
        t.setDaemon(true);
        return t;
    });

    private static VaultStore instance;
    private final Connection db;

    // ============================================================== records

    static final class Vault {
        int id;
        String name, lockType, lockHash, uuid;
        boolean main;
        int sortOrder;
        /** "DEFAULT", "COLOR", "IMAGE", "VIDEO" or "URL". */
        String bgType = "DEFAULT";
        String bgValue;
    }

    /** One item, as the grid and the viewer draw it. */
    record Item(String fileName, boolean video, long dateTaken, boolean favourite, long durationMs) {
        Item withFavourite(boolean f) {
            return new Item(fileName, video, dateTaken, f, durationMs);
        }
    }

    static final class Secret {
        int id, vaultId;
        String fileName, originalPath;
        long dateTaken, dateAdded, durationMs;
        boolean video, favourite;
        Integer albumId;
    }

    static final class Album {
        int id, vaultId;
        String name, uuid;
    }

    // ================================================================= open

    private VaultStore() throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + VaultCrypto.dir().resolve("vault.db"));
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("CREATE TABLE IF NOT EXISTS vaults (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, "
                    + "lockType TEXT, lockHash TEXT, isMain INTEGER NOT NULL DEFAULT 0, sortOrder INTEGER NOT NULL DEFAULT 0, "
                    + "uuid TEXT, bgType TEXT, bgValue TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS secrets (id INTEGER PRIMARY KEY AUTOINCREMENT, fileName TEXT, "
                    + "originalPath TEXT, dateTaken INTEGER NOT NULL, dateAdded INTEGER NOT NULL, isVideo INTEGER NOT NULL, "
                    + "vaultId INTEGER NOT NULL, isFavourite INTEGER NOT NULL DEFAULT 0, albumId INTEGER, "
                    + "durationMs INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS index_secrets_vaultId_dateTaken_dateAdded ON secrets (vaultId, dateTaken, dateAdded)");
            s.execute("CREATE INDEX IF NOT EXISTS index_secrets_fileName ON secrets (fileName)");
            s.execute("CREATE INDEX IF NOT EXISTS index_secrets_albumId ON secrets (albumId)");
            s.execute("CREATE TABLE IF NOT EXISTS albums (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, "
                    + "vaultId INTEGER NOT NULL, coverFileName TEXT, uuid TEXT)");
        }
    }

    static synchronized VaultStore get() {
        if (instance == null) {
            try {
                instance = new VaultStore();
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot open the vault database", e);
            }
        }
        return instance;
    }

    static <T> void load(Function<VaultStore, T> query, Consumer<T> then) {
        IO.execute(() -> {
            T result;
            try {
                result = query.apply(get());
            } catch (RuntimeException e) {
                System.getLogger("Vault").log(System.Logger.Level.WARNING, "Vault query failed", e);
                result = null;
            }
            T r = result;
            Platform.runLater(() -> then.accept(r));
        });
    }

    static void write(Consumer<VaultStore> work) {
        IO.execute(() -> {
            try {
                work.accept(get());
            } catch (RuntimeException e) {
                System.getLogger("Vault").log(System.Logger.Level.WARNING, "Vault write failed", e);
            }
        });
    }

    // =============================================================== vaults

    synchronized List<Vault> vaults() {
        List<Vault> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT * FROM vaults ORDER BY sortOrder ASC, id ASC");
             ResultSet r = p.executeQuery()) {
            while (r.next()) out.add(vault(r));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    synchronized Vault vault(int id) {
        for (Vault v : vaults()) if (v.id == id) return v;
        return null;
    }

    synchronized Vault vaultByUuid(String uuid) {
        for (Vault v : vaults()) if (uuid.equals(v.uuid)) return v;
        return null;
    }

    synchronized List<Album> allAlbums() {
        List<Album> out = new ArrayList<>();
        for (Vault v : vaults()) out.addAll(albums(v.id));
        return out;
    }

    synchronized List<Secret> allSecrets() {
        return secrets("SELECT * FROM secrets");
    }

    synchronized Vault mainVault() {
        for (Vault v : vaults()) if (v.main) return v;
        return null;
    }

    synchronized int insertVault(Vault v) {
        if (v.uuid == null) v.uuid = UUID.randomUUID().toString();
        v.id = insert("INSERT INTO vaults (name, lockType, lockHash, isMain, sortOrder, uuid, bgType, bgValue) VALUES (?,?,?,?,?,?,?,?)",
                v.name, v.lockType, v.lockHash, v.main, v.sortOrder, v.uuid, v.bgType, v.bgValue);
        return v.id;
    }

    synchronized void updateVault(Vault v) {
        update("UPDATE vaults SET name = ?, lockType = ?, lockHash = ?, isMain = ?, sortOrder = ?, bgType = ?, bgValue = ? WHERE id = ?",
                v.name, v.lockType, v.lockHash, v.main, v.sortOrder, v.bgType, v.bgValue, v.id);
    }

    synchronized void deleteVault(int id) {
        update("DELETE FROM albums WHERE vaultId = ?", id);
        update("DELETE FROM vaults WHERE id = ?", id);
    }

    synchronized int countFor(int vaultId) {
        return count("SELECT COUNT(*) FROM secrets WHERE vaultId = ?", vaultId);
    }

    private static Vault vault(ResultSet r) throws SQLException {
        Vault v = new Vault();
        v.id = r.getInt("id");
        v.name = r.getString("name");
        v.lockType = r.getString("lockType");
        v.lockHash = r.getString("lockHash");
        v.main = r.getInt("isMain") != 0;
        v.sortOrder = r.getInt("sortOrder");
        v.uuid = r.getString("uuid");
        v.bgType = r.getString("bgType") == null ? "DEFAULT" : r.getString("bgType");
        v.bgValue = r.getString("bgValue");
        return v;
    }

    // ================================================================ items

    private static final String ROW = "SELECT fileName, isVideo, dateTaken, isFavourite, durationMs FROM secrets ";
    private static final String NEWEST_FIRST = " ORDER BY dateTaken DESC, dateAdded DESC";

    synchronized List<Item> galleryRows(int vaultId) {
        return items(ROW + "WHERE vaultId = ?" + NEWEST_FIRST, vaultId);
    }

    synchronized List<Item> favouriteRows(int vaultId) {
        return items(ROW + "WHERE vaultId = ? AND isFavourite = 1" + NEWEST_FIRST, vaultId);
    }

    synchronized List<Item> albumRows(int vaultId, int albumId) {
        return items(ROW + "WHERE vaultId = ? AND albumId = ?" + NEWEST_FIRST, vaultId, albumId);
    }

    synchronized int insertSecret(Secret s) {
        s.id = insert("INSERT INTO secrets (fileName, originalPath, dateTaken, dateAdded, isVideo, vaultId, isFavourite, albumId, durationMs) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", s.fileName, s.originalPath, s.dateTaken, s.dateAdded, s.video, s.vaultId,
                s.favourite, s.albumId, s.durationMs);
        return s.id;
    }

    synchronized Secret secret(String fileName) {
        List<Secret> found = secrets("SELECT * FROM secrets WHERE fileName = ? LIMIT 1", fileName);
        return found.isEmpty() ? null : found.get(0);
    }

    synchronized List<Secret> secretsFor(int vaultId) {
        return secrets("SELECT * FROM secrets WHERE vaultId = ?", vaultId);
    }

    synchronized void deleteSecret(String fileName) {
        update("DELETE FROM secrets WHERE fileName = ?", fileName);
    }

    synchronized void setFavourite(List<String> names, boolean favourite) {
        for (String n : names) update("UPDATE secrets SET isFavourite = ? WHERE fileName = ?", favourite, n);
    }

    synchronized void setAlbum(List<String> names, Integer albumId) {
        for (String n : names) update("UPDATE secrets SET albumId = ? WHERE fileName = ?", albumId, n);
    }

    synchronized void setDuration(String name, long ms) {
        update("UPDATE secrets SET durationMs = ? WHERE fileName = ?", ms, name);
    }

    // =============================================================== albums

    synchronized List<Album> albums(int vaultId) {
        List<Album> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT * FROM albums WHERE vaultId = ? ORDER BY id DESC")) {
            p.setInt(1, vaultId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Album a = new Album();
                    a.id = r.getInt("id");
                    a.vaultId = r.getInt("vaultId");
                    a.name = r.getString("name");
                    a.uuid = r.getString("uuid");
                    out.add(a);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    synchronized Album insertAlbum(String name, int vaultId) {
        return insertAlbum(name, vaultId, UUID.randomUUID().toString());
    }

    synchronized Album insertAlbum(String name, int vaultId, String uuid) {
        Album a = new Album();
        a.name = name;
        a.vaultId = vaultId;
        a.uuid = uuid;
        a.id = insert("INSERT INTO albums (name, vaultId, uuid) VALUES (?,?,?)", name, vaultId, a.uuid);
        return a;
    }

    synchronized void renameAlbum(int id, String name) {
        update("UPDATE albums SET name = ? WHERE id = ?", name, id);
    }

    /** The album goes; its items stay in the vault. */
    synchronized void deleteAlbum(int id) {
        update("UPDATE secrets SET albumId = NULL WHERE albumId = ?", id);
        update("DELETE FROM albums WHERE id = ?", id);
    }

    synchronized int albumCount(int albumId) {
        return count("SELECT COUNT(*) FROM secrets WHERE albumId = ?", albumId);
    }

    synchronized int favouriteCount(int vaultId) {
        return count("SELECT COUNT(*) FROM secrets WHERE isFavourite = 1 AND vaultId = ?", vaultId);
    }

    synchronized Secret latestInAlbum(int albumId) {
        List<Secret> s = secrets("SELECT * FROM secrets WHERE albumId = ? ORDER BY id DESC LIMIT 1", albumId);
        return s.isEmpty() ? null : s.get(0);
    }

    synchronized Secret latestFavourite(int vaultId) {
        List<Secret> s = secrets("SELECT * FROM secrets WHERE isFavourite = 1 AND vaultId = ? ORDER BY id DESC LIMIT 1", vaultId);
        return s.isEmpty() ? null : s.get(0);
    }

    // ============================================================== helpers

    private List<Item> items(String sql, Object... args) {
        List<Item> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new Item(r.getString(1), r.getInt(2) != 0, r.getLong(3), r.getInt(4) != 0, r.getLong(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private List<Secret> secrets(String sql, Object... args) {
        List<Secret> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Secret s = new Secret();
                    s.id = r.getInt("id");
                    s.fileName = r.getString("fileName");
                    s.originalPath = r.getString("originalPath");
                    s.dateTaken = r.getLong("dateTaken");
                    s.dateAdded = r.getLong("dateAdded");
                    s.video = r.getInt("isVideo") != 0;
                    s.vaultId = r.getInt("vaultId");
                    s.favourite = r.getInt("isFavourite") != 0;
                    int album = r.getInt("albumId");
                    s.albumId = r.wasNull() ? null : album;
                    s.durationMs = r.getLong("durationMs");
                    out.add(s);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private int count(String sql, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int insert(String sql, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, args);
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(String sql, Object... args) {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            bind(p, args);
            p.executeUpdate();
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
