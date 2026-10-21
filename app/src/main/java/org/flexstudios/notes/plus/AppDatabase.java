package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.Executors;

@Database(entities = {NoteEntity.class, SecretEntity.class, VaultEntity.class, AlbumEntity.class}, version = 12, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    /**
     * The app's one background pool for database and file work. Screens used to create a new
     * executor per click (dozens of them, never shut down); everything goes through here now.
     */
    public static final java.util.concurrent.ExecutorService IO =
            Executors.newFixedThreadPool(4);

    public abstract NoteDao noteDao();
    public abstract SecretDao secretDao();
    public abstract VaultDao vaultDao();
    public abstract AlbumDao albumDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "notes_database")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .addCallback(new Callback() {
                        @Override
                        public void onOpen(@NonNull SupportSQLiteDatabase db) {
                            super.onOpen(db);
                            // Initial seeding of main vault from prefs
                            final Context appContext = context.getApplicationContext();
                            IO.execute(() -> {
                                seedMainVault(appContext);
                                backfillUuids(appContext);
                                detectLegacyBackups(appContext);
                            });
                        }
                    })
                    // Only the pre-vault schema may be dropped. Any other missing migration must
                    // crash in testing, not silently wipe someone's vault in the field.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build();
        }
        return instance;
    }

    /** Creates vault 1 from the lock chosen at setup, if it does not exist yet. Idempotent. */
    static synchronized void ensureMainVault(Context context) {
        seedMainVault(context);
    }

    private static void seedMainVault(Context context) {
        VaultDao dao = getInstance(context).vaultDao();
        VaultEntity existingMain = dao.getMainVault();
        VaultEntity vault1 = dao.getVaultById(1);

        if (existingMain == null && vault1 == null) {
            SharedPreferences prefs = SecurityHelper.getEncryptedPrefs(context);
            String lockType = prefs.getString(SecurityHelper.KEY_LOCK_TYPE, "PIN");
            String lockValue = prefs.getString(SecurityHelper.KEY_LOCK_VALUE, "");
            
            if (lockValue != null && !lockValue.isEmpty()) {
                VaultEntity vault = new VaultEntity("Main Vault", lockType, lockValue, true, 0);
                vault.setId(1);
                dao.insert(vault);
            }
        }
    }

    /**
     * Gives vaults and albums an identity that survives a restore.
     *
     * Row ids are reassigned when a backup is applied, and the old cloud code matched vaults
     * across devices by display name, so duplicates mapped to the wrong vault and a rename
     * mapped to nothing at all. contentHash is recorded opportunistically while a file
     * uploads, so it stays null for pre-existing rows rather than forcing a full re-read.
     */
    /**
     * Indexes for the gallery's hot paths. Without them every page of the grid, every count
     * and every lookup by file name was a full table scan, which is what made opening a vault
     * of thousands of items slow.
     */
    /** Note types: lists, mind maps, journals, sketches and audio, plus colour and pinning. */
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `notes` ADD COLUMN `type` TEXT DEFAULT 'TEXT'");
            database.execSQL("ALTER TABLE `notes` ADD COLUMN `data` TEXT");
            database.execSQL("ALTER TABLE `notes` ADD COLUMN `color` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_pinned_timestamp` ON `notes` (`pinned`, `timestamp`)");
        }
    };

    /** Video length for the gallery's duration badge; measured lazily for existing rows. */
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_secrets_vaultId_dateTaken_dateAdded` ON `secrets` (`vaultId`, `dateTaken`, `dateAdded`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_secrets_fileName` ON `secrets` (`fileName`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_secrets_albumId` ON `secrets` (`albumId`)");
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `vaults` ADD COLUMN `uuid` TEXT");
            database.execSQL("ALTER TABLE `albums` ADD COLUMN `uuid` TEXT");
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `contentHash` TEXT");
        }
    };

    /**
     * Distinguishes a fresh install from one upgrading across the key-hierarchy change.
     *
     * An install that already holds media may have backups encrypted directly with a lock
     * value, which a lock change would orphan; a brand-new install cannot, and must not be
     * sent through a migration it has nothing to migrate.
     */
    private static void detectLegacyBackups(Context context) {
        SharedPreferences prefs = SecurityHelper.getAppPrefs(context);
        if (prefs.contains(KEY_SCHEMA_SEEN)) return;

        boolean preExisting = false;
        try {
            preExisting = !getInstance(context).secretDao().getAllSecretsDirect().isEmpty();
        } catch (Exception e) {
            android.util.Log.e("AppDatabase", "Could not inspect existing media", e);
        }
        if (preExisting) VaultCrypto.markLegacyPayloadsPossible(context);
        prefs.edit().putInt(KEY_SCHEMA_SEEN, 9).apply();
    }

    /** Marks that this install has been observed by the current schema. */
    private static final String KEY_SCHEMA_SEEN = "app_schema_version";

    /** Fills in identities for rows that predate MIGRATION_8_9. */
    private static void backfillUuids(Context context) {
        try {
            AppDatabase db = getInstance(context);
            for (VaultEntity vault : db.vaultDao().getAllVaultsDirect()) {
                if (vault.getUuid() == null || vault.getUuid().isEmpty()) {
                    vault.setUuid(java.util.UUID.randomUUID().toString());
                    db.vaultDao().update(vault);
                }
            }
            for (AlbumEntity album : db.albumDao().getAllAlbumsDirect()) {
                if (album.getUuid() == null || album.getUuid().isEmpty()) {
                    album.setUuid(java.util.UUID.randomUUID().toString());
                    db.albumDao().update(album);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("AppDatabase", "Could not backfill identities", e);
        }
    }

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `vaults` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `lockType` TEXT, `lockValue` TEXT, `isMain` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)");
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `vaultId` INTEGER NOT NULL DEFAULT 1");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `vaults` ADD COLUMN `bgType` TEXT DEFAULT 'DEFAULT'");
            database.execSQL("ALTER TABLE `vaults` ADD COLUMN `bgValue` TEXT");
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Add isFavourite and albumId to secrets
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `isFavourite` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `albumId` INTEGER");
            
            // Create albums table
            database.execSQL("CREATE TABLE IF NOT EXISTS `albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `vaultId` INTEGER NOT NULL, `coverFileName` TEXT)");
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `syncedLocally` INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `secrets` ADD COLUMN `syncedToDrive` INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Remove syncedToDrive column
            database.execSQL("CREATE TABLE IF NOT EXISTS `secrets_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileName` TEXT, `originalPath` TEXT, `dateTaken` INTEGER NOT NULL, `dateAdded` INTEGER NOT NULL, `isVideo` INTEGER NOT NULL, `vaultId` INTEGER NOT NULL, `isFavourite` INTEGER NOT NULL, `albumId` INTEGER, `syncedLocally` INTEGER NOT NULL)");
            database.execSQL("INSERT INTO `secrets_new` (`id`, `fileName`, `originalPath`, `dateTaken`, `dateAdded`, `isVideo`, `vaultId`, `isFavourite`, `albumId`, `syncedLocally`) SELECT `id`, `fileName`, `originalPath`, `dateTaken`, `dateAdded`, `isVideo`, `vaultId`, `isFavourite`, `albumId`, `syncedLocally` FROM `secrets` ");
            database.execSQL("DROP TABLE `secrets` ");
            database.execSQL("ALTER TABLE `secrets_new` RENAME TO `secrets` ");
        }
    };
}