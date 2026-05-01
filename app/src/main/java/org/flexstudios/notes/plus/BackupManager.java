package org.flexstudios.notes.plus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

public class BackupManager {
    private final Context context;
    private final BackupListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface BackupListener {
        void onProgress(int progress, String message);
        void onComplete(String message);
        void onError(String error);
    }

    public BackupManager(Context context, BackupListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void createBackup(OutputStream outputStream, String password) {
        new Thread(() -> {
            File tempZip = new File(context.getCacheDir(), "backup_temp.zip");
            try {
                updateProgress(10, "Gathering data...");
                AppDatabase db = AppDatabase.getInstance(context);
                List<VaultEntity> vaults = db.vaultDao().getAllVaultsDirect();
                List<SecretEntity> secrets = db.secretDao().getAllSecretsDirect();
                List<AlbumEntity> albums = db.albumDao().getAllAlbumsDirect();

                // Build settings JSON
                JSONObject settings = new JSONObject();
                settings.put("auto_lock_time", SecurityHelper.getAutoLockDelay(context));
                settings.put("lock_setup_done", SecurityHelper.isSetupDone(context));
                
                JSONArray vaultsArray = new JSONArray();
                for (VaultEntity v : vaults) {
                    JSONObject vo = new JSONObject();
                    vo.put("id", v.getId());
                    vo.put("name", v.getName());
                    vo.put("lockType", v.getLockType());
                    vo.put("lockValue", v.getLockValue());
                    vo.put("isMain", v.isMain());
                    vo.put("sortOrder", v.getSortOrder());
                    vo.put("bgType", v.getBgType());
                    vo.put("bgValue", v.getBgValue());
                    vaultsArray.put(vo);
                }
                settings.put("vaults", vaultsArray);

                JSONArray secretsArray = new JSONArray();
                for (SecretEntity s : secrets) {
                    JSONObject so = new JSONObject();
                    so.put("fileName", s.getFileName());
                    so.put("originalPath", s.getOriginalPath());
                    so.put("dateTaken", s.getDateTaken());
                    so.put("dateAdded", s.getDateAdded());
                    so.put("isVideo", s.isVideo());
                    so.put("vaultId", s.getVaultId());
                    so.put("isFavourite", s.isFavourite());
                    so.put("albumId", s.getAlbumId());
                    secretsArray.put(so);
                }
                settings.put("secrets", secretsArray);

                JSONArray albumsArray = new JSONArray();
                for (AlbumEntity a : albums) {
                    JSONObject ao = new JSONObject();
                    ao.put("id", a.getId());
                    ao.put("name", a.getName());
                    ao.put("vaultId", a.getVaultId());
                    ao.put("coverFileName", a.getCoverFileName());
                    albumsArray.put(ao);
                }
                settings.put("albums", albumsArray);

                updateProgress(20, "Zipping media files...");
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
                    ZipEntry settingsEntry = new ZipEntry("settings.json");
                    zos.putNextEntry(settingsEntry);
                    zos.write(settings.toString().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();

                    File vaultDir = new File(context.getFilesDir(), "vault");
                    File[] files = vaultDir.listFiles();
                    if (files != null) {
                        int count = 0;
                        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                        for (File f : files) {
                            if (f.isFile() && !f.getName().equals(".nomedia")) {
                                try {
                                    ZipEntry entry = new ZipEntry("media/" + f.getName());
                                    zos.putNextEntry(entry);
                                    
                                    EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                                            f, context, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                                    ).build();

                                    try (InputStream is = encryptedStorage.openFileInput()) {
                                        byte[] buffer = new byte[16384];
                                        int len;
                                        while ((len = is.read(buffer)) > 0) zos.write(buffer, 0, len);
                                    }
                                    zos.closeEntry();
                                } catch (Exception e) {
                                    Log.e("BackupManager", "Skipping corrupt file: " + f.getName(), e);
                                }
                                count++;
                                int p = 20 + (int) ((count / (float) files.length) * 50);
                                updateProgress(p, "Zipping: " + f.getName());
                            }
                        }
                    }
                }

                updateProgress(80, "Encrypting backup...");
                encryptToStream(tempZip, outputStream, password);

                db.secretDao().markAllAsSynced();
                
                if (tempZip.exists()) tempZip.delete();
                updateProgress(100, "Done");
                mainHandler.post(() -> listener.onComplete("Backup Saved Successfully"));

            } catch (Exception e) {
                e.printStackTrace();
                if (tempZip.exists()) tempZip.delete();
                mainHandler.post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }

    public void restoreBackup(InputStream inputStream, String password, boolean replaceSettings, boolean replaceMedia) {
        new Thread(() -> {
            File tempZip = new File(context.getCacheDir(), "restore_temp.zip");
            File vaultDir = new File(context.getFilesDir(), "vault");
            File backupVaultDir = new File(context.getFilesDir(), "vault_backup");
            AppDatabase db = AppDatabase.getInstance(context);

            try {
                updateProgress(10, "Decrypting backup...");
                decryptToFile(inputStream, tempZip, password);

                updateProgress(30, "Extracting settings...");
                JSONObject settings = null;
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.getName().equals("settings.json")) {
                            StringBuilder sb = new StringBuilder();
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                            }
                            settings = new JSONObject(sb.toString());
                            break;
                        }
                    }
                }

                if (settings == null) throw new Exception("Invalid backup: Missing settings.json");

                // Start Transaction/Rollback point
                if (replaceMedia && vaultDir.exists()) {
                    if (backupVaultDir.exists()) deleteRecursive(backupVaultDir);
                    vaultDir.renameTo(backupVaultDir);
                }
                if (!vaultDir.exists()) vaultDir.mkdirs();

                updateProgress(50, "Restoring media...");
                String restoreKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.getName().startsWith("media/")) {
                            String fileName = entry.getName().substring(6);
                            File target = new File(vaultDir, fileName);
                            
                            if (!replaceMedia && target.exists()) continue; // Merge: skip existing

                            if (target.exists()) target.delete();

                            EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                                    target, context, restoreKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                            ).build();

                            try (OutputStream os = encryptedStorage.openFileOutput()) {
                                byte[] buffer = new byte[16384];
                                int len;
                                while ((len = zis.read(buffer)) > 0) os.write(buffer, 0, len);
                            }
                        }
                    }
                }

                updateProgress(80, "Updating database...");
                // Note: Direct DB manipulation in a background thread for restore
                if (replaceSettings) {
                    db.clearAllTables();
                    
                    JSONArray vaults = settings.getJSONArray("vaults");
                    for (int i = 0; i < vaults.length(); i++) {
                        JSONObject vo = vaults.getJSONObject(i);
                        VaultEntity v = new VaultEntity(vo.getString("name"), vo.getString("lockType"), 
                                vo.getString("lockValue"), vo.getBoolean("isMain"), vo.getInt("sortOrder"));
                        v.setId(vo.getInt("id"));
                        v.setBgType(vo.optString("bgType", "DEFAULT"));
                        v.setBgValue(vo.optString("bgValue", null));
                        db.vaultDao().insert(v);
                    }
                    SecurityHelper.setAutoLockDelay(context, settings.getLong("auto_lock_time"));
                }

                if (replaceMedia) {
                    // If we replace media, we should probably only keep secrets from backup
                    // If we merge, we add new ones.
                    if (!replaceSettings) {
                        // Merge logic: delete only existing secrets that are being replaced? 
                        // Actually the user said "Replace all current media" or "Merge"
                    }
                }

                JSONArray secrets = settings.getJSONArray("secrets");
                int totalSecrets = secrets.length();
                
                // Optimized duplicate detection
                List<SecretEntity> allExistingSecrets = db.secretDao().getAllSecretsDirect();
                java.util.Map<String, SecretEntity> existingMap = new java.util.HashMap<>();
                for (SecretEntity es : allExistingSecrets) {
                    if (es.getOriginalPath() != null) {
                        String origName = new File(es.getOriginalPath()).getName();
                        existingMap.put(es.getVaultId() + "_" + origName, es);
                    }
                }

                for (int i = 0; i < totalSecrets; i++) {
                    JSONObject so = secrets.getJSONObject(i);
                    SecretEntity s = new SecretEntity(so.getString("fileName"), so.getString("originalPath"),
                            so.getLong("dateTaken"), so.getLong("dateAdded"), so.getBoolean("isVideo"), so.getInt("vaultId"));
                    s.setFavourite(so.getBoolean("isFavourite"));
                    if (!so.isNull("albumId")) s.setAlbumId(so.getInt("albumId"));
                    
                    updateProgress(80, "Updating DB: " + (i + 1) + "/" + totalSecrets);

                    SecretEntity existing = null;
                    if (s.getOriginalPath() != null) {
                        String originalFileName = new File(s.getOriginalPath()).getName();
                        String key = s.getVaultId() + "_" + originalFileName;
                        existing = existingMap.get(key);
                    }

                    if (existing == null) {
                        db.secretDao().insert(s);
                    } else if (replaceMedia) {
                        // In replace mode, the file on disk is the restored one.
                        // We just need to update the database record.
                        db.secretDao().delete(existing);
                        db.secretDao().insert(s);
                    }
                }

                if (settings.has("albums")) {
                    JSONArray albums = settings.getJSONArray("albums");
                    for (int i = 0; i < albums.length(); i++) {
                        JSONObject ao = albums.getJSONObject(i);
                        AlbumEntity a = new AlbumEntity(ao.getString("name"), ao.getInt("vaultId"), ao.optString("coverFileName", null));
                        a.setId(ao.getInt("id"));
                        // Use insert or update
                        if (db.albumDao().getAlbumById(a.getId()) == null) {
                            db.albumDao().insert(a);
                        } else {
                            db.albumDao().update(a);
                        }
                    }
                }

                if (backupVaultDir.exists()) deleteRecursive(backupVaultDir);
                
                // Clear cache to force thumbnail regeneration
                deleteRecursive(context.getCacheDir());
                context.getCacheDir().mkdirs();

                updateProgress(100, "Restore Complete");
                mainHandler.post(() -> listener.onComplete("Restored successfully"));

            } catch (Exception e) {
                e.printStackTrace();
                // Rollback
                if (replaceMedia && backupVaultDir.exists()) {
                    if (vaultDir.exists()) deleteRecursive(vaultDir);
                    backupVaultDir.renameTo(vaultDir);
                }
                mainHandler.post(() -> listener.onError("Restore failed: " + e.getMessage()));
            } finally {
                if (tempZip.exists()) tempZip.delete();
            }
        }).start();
    }

    private void decryptToFile(InputStream is, File outputFile, String password) throws Exception {
        byte[] salt = new byte[16];
        if (readFully(is, salt) < 16) throw new Exception("Invalid backup file");
        byte[] iv = new byte[12];
        if (readFully(is, iv) < 12) throw new Exception("Invalid backup file");

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secret, gcmSpec);

        try (CipherInputStream cis = new CipherInputStream(is, cipher);
             FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[16384];
            int len;
            while ((len = cis.read(buffer)) > 0) fos.write(buffer, 0, len);
        }
    }

    private int readFully(InputStream is, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = is.read(buffer, offset, buffer.length - offset);
            if (read == -1) break;
            offset += read;
        }
        return offset;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private void encryptToStream(File inputFile, OutputStream outputStream, String password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secret, gcmSpec);

        try (OutputStream os = outputStream) {
            os.write(salt);
            os.write(iv);
            try (CipherOutputStream cos = new CipherOutputStream(os, cipher);
                 InputStream is = new FileInputStream(inputFile)) {
                byte[] buffer = new byte[16384];
                int len;
                while ((len = is.read(buffer)) > 0) cos.write(buffer, 0, len);
                cos.flush();
            }
        }
    }

    private void updateProgress(int progress, String message) {
        mainHandler.post(() -> listener.onProgress(progress, message));
    }
}
