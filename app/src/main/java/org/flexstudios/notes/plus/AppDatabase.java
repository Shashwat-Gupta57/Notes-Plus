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

@Database(entities = {NoteEntity.class, SecretEntity.class, VaultEntity.class, AlbumEntity.class}, version = 5, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    public abstract NoteDao noteDao();
    public abstract SecretDao secretDao();
    public abstract VaultDao vaultDao();
    public abstract AlbumDao albumDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "notes_database")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .addCallback(new Callback() {
                        @Override
                        public void onOpen(@NonNull SupportSQLiteDatabase db) {
                            super.onOpen(db);
                            // Initial seeding of main vault from prefs
                            final Context appContext = context.getApplicationContext();
                            Executors.newSingleThreadExecutor().execute(() -> {
                                seedMainVault(appContext);
                            });
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }

    private static void seedMainVault(Context context) {
        VaultDao dao = getInstance(context).vaultDao();
        VaultEntity mainVault = dao.getMainVault();
        if (mainVault == null) {
            SharedPreferences prefs = SecurityHelper.getEncryptedPrefs(context);
            String lockType = prefs.getString(SecurityHelper.KEY_LOCK_TYPE, "PIN");
            String lockValue = prefs.getString(SecurityHelper.KEY_LOCK_VALUE, "");
            
            // Seed vault 1 as the main vault
            VaultEntity vault = new VaultEntity("Main Vault", lockType, lockValue, true, 0);
            vault.setId(1); // Force ID 1 to match existing secrets default
            dao.insert(vault);
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
}