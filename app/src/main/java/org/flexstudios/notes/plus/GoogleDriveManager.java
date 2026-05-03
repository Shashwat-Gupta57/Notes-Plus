package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

public class GoogleDriveManager {
    private static final String TAG = "GoogleDriveManager";
    public static final String GOOGLE_DRIVE_CLIENT_ID = "YOUR_CLIENT_ID_HERE.apps.googleusercontent.com";

    private final Context context;
    private final Drive driveService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface SyncCallback {
        void onProgress(int progress, String message);
        void onComplete();
        void onError(String error);
    }

    public GoogleDriveManager(Context context, GoogleSignInAccount account) {
        this.context = context;
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE_FILE));
        credential.setSelectedAccount(account.getAccount());
        HttpTransport transport = new NetHttpTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        driveService = new Drive.Builder(transport, jsonFactory, credential).setApplicationName("Notes+").build();
    }

    public static GoogleSignInClient getSignInClient(Context context) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail().requestScopes(new Scope(DriveScopes.DRIVE_FILE)).build();
        return GoogleSignIn.getClient(context, gso);
    }

    public void syncUnsyncedFiles(SyncCallback callback) {
        executor.execute(() -> {
            java.io.File vaultDir = new java.io.File(context.getFilesDir(), "vault");
            java.io.File vaultBackupDir = new java.io.File(context.getFilesDir(), "vault_backup");

            try {
                syncUnsyncedFilesDirect(callback);
                mainHandler.post(callback::onComplete);
            } catch (Exception e) {
                Log.e(TAG, "Sync failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void syncUnsyncedFilesDirect(SyncCallback callback) throws Exception {
        updateProgress(callback, 5, "Reconciling Drive state...");
        String backupFolderId = getOrCreateFolderPath("Notes-plus/Saved-Notes/Cloud/Remote/Backup");
        AppDatabase db = AppDatabase.getInstance(context);
        
        VaultEntity mainVault = db.vaultDao().getMainVault();
        if (mainVault == null) return;
        String masterPassword = mainVault.getLockValue();

        // 1. Reconcile Secrets
        List<VaultEntity> allVaults = db.vaultDao().getAllVaultsDirect();
        for (VaultEntity vault : allVaults) {
            String vaultFolderId = getOrCreateFolder("vault_" + vault.getId(), backupFolderId);
            reconcileFolder(vaultFolderId, db.secretDao().getSecretsForVaultDirect(vault.getId()));
        }

        // 2. Reconcile Backgrounds
        String bgRootId = getOrCreateFolder("backgrounds", backupFolderId);
        java.io.File[] localFiles = context.getFilesDir().listFiles();
        if (localFiles != null) {
            for (java.io.File dir : localFiles) {
                if (dir.isDirectory() && dir.getName().startsWith("bg_")) {
                    String remoteDirId = getOrCreateFolder(dir.getName(), bgRootId);
                    reconcileBackgroundFolder(remoteDirId, dir);
                }
            }
        }

        updateProgress(callback, 20, "Uploading settings...");
        uploadSettings(backupFolderId);

        updateProgress(callback, 30, "Syncing files...");
        List<SecretEntity> unsynced = db.secretDao().getUnsyncedToDriveSecrets();
        int total = unsynced.size();
        List<Integer> successfullySyncedIds = new ArrayList<>();
        
        for (int i = 0; i < total; i++) {
            SecretEntity secret = unsynced.get(i);
            updateProgress(callback, 30 + (int)((i/(float)total)*60), "Syncing: " + secret.getFileName());
            String vaultFolderId = getOrCreateFolder("vault_" + secret.getVaultId(), backupFolderId);
            java.io.File localFile = new java.io.File(context.getFilesDir(), "vault/" + secret.getFileName());
            if (localFile.exists()) {
                uploadFileWithPassword(localFile, vaultFolderId, masterPassword);
                successfullySyncedIds.add(secret.getId());
            }
        }

        if (!successfullySyncedIds.isEmpty()) {
            db.secretDao().markBatchAsSyncedToDrive(successfullySyncedIds);
        }

        // Sync Backgrounds (Final push for missing ones)
        if (localFiles != null) {
            for (java.io.File dir : localFiles) {
                if (dir.isDirectory() && dir.getName().startsWith("bg_")) {
                    String remoteSubFolderId = getOrCreateFolder(dir.getName(), bgRootId);
                    java.io.File[] bgFiles = dir.listFiles();
                    if (bgFiles != null) {
                        for (java.io.File f : bgFiles) {
                            if (f.isFile() && !f.getName().endsWith(".mp4")) {
                                updateOrUploadFile(f, f.getName(), "application/octet-stream", remoteSubFolderId, false);
                            }
                        }
                    }
                }
            }
        }
        updateLastSyncTime();
    }

    private void reconcileFolder(String folderId, List<SecretEntity> localSecrets) throws IOException {
        List<File> driveFiles = listAllFiles("'" + folderId + "' in parents and trashed = false", "id, name");
        Map<String, SecretEntity> localMap = new HashMap<>();
        for (SecretEntity s : localSecrets) localMap.put(s.getFileName(), s);
        
        AppDatabase db = AppDatabase.getInstance(context);

        if (driveFiles != null) {
            for (File df : driveFiles) {
                if (!localMap.containsKey(df.getName()) && !df.getName().equals("settings.json.enc")) {
                    driveService.files().delete(df.getId()).execute();
                }
            }
        }

        for (SecretEntity s : localSecrets) {
            boolean found = false;
            if (driveFiles != null) {
                for (File df : driveFiles) {
                    if (df.getName().equals(s.getFileName())) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                s.setSyncedToDrive(false);
                db.secretDao().update(s);
            }
        }
    }

    private void reconcileBackgroundFolder(String folderId, java.io.File localDir) throws IOException {
        List<File> driveFiles = listAllFiles("'" + folderId + "' in parents and trashed = false", "id, name");
        java.io.File[] localFiles = localDir.listFiles();
        Map<String, java.io.File> localMap = new HashMap<>();
        if (localFiles != null) {
            for (java.io.File f : localFiles) localMap.put(f.getName(), f);
        }

        if (driveFiles != null) {
            for (File df : driveFiles) {
                if (!localMap.containsKey(df.getName())) {
                    driveService.files().delete(df.getId()).execute();
                }
            }
        }
    }

    public void uploadSettingsDirect() throws Exception {
        String backupFolderId = getOrCreateFolderPath("Notes-plus/Saved-Notes/Cloud/Remote/Backup");
        uploadSettings(backupFolderId);
    }

    private void uploadSettings(String parentId) throws Exception {
        AppDatabase db = AppDatabase.getInstance(context);
        List<VaultEntity> vaults = db.vaultDao().getAllVaultsDirect();
        List<SecretEntity> secrets = db.secretDao().getAllSecretsDirect();
        List<AlbumEntity> albums = db.albumDao().getAllAlbumsDirect();

        JSONObject settings = new JSONObject();
        settings.put("auto_lock_time", SecurityHelper.getAutoLockDelay(context));
        
        SharedPreferences appPrefs = SecurityHelper.getAppPrefs(context);
        settings.put("lock_bg_type", appPrefs.getString(SecurityHelper.KEY_LOCK_BG_TYPE, "DEFAULT"));
        settings.put("lock_bg_value", appPrefs.getString(SecurityHelper.KEY_LOCK_BG_VALUE, null));
        settings.put("lock_bg_blur", appPrefs.getBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, false));
        settings.put("lock_bg_dim", appPrefs.getBoolean(SecurityHelper.KEY_LOCK_BG_DIM, false));

        JSONArray vaultsArray = new JSONArray();
        String masterPassword = "";
        for (VaultEntity v : vaults) {
            JSONObject vo = new JSONObject();
            vo.put("id", v.getId()); vo.put("name", v.getName()); vo.put("lockType", v.getLockType());
            vo.put("lockValue", v.getLockValue()); vo.put("isMain", v.isMain());
            vo.put("sortOrder", v.getSortOrder());
            vo.put("bgType", v.getBgType());
            vo.put("bgValue", v.getBgValue());
            if (v.isMain()) masterPassword = v.getLockValue();
            vaultsArray.put(vo);
        }
        
        if (masterPassword.isEmpty()) {
            masterPassword = SecurityHelper.getEncryptedPrefs(context).getString(SecurityHelper.KEY_LOCK_VALUE, "");
        }
        
        if (masterPassword.isEmpty()) {
            Log.w(TAG, "No main vault or lock value found, skipping settings upload");
            return;
        }

        settings.put("vaults", vaultsArray);

        JSONArray secretsArray = new JSONArray();
        for (SecretEntity s : secrets) {
            JSONObject so = new JSONObject();
            so.put("fileName", s.getFileName()); so.put("originalPath", s.getOriginalPath());
            so.put("dateTaken", s.getDateTaken()); so.put("dateAdded", s.getDateAdded());
            so.put("isVideo", s.isVideo()); so.put("vaultId", s.getVaultId());
            so.put("isFavourite", s.isFavourite()); so.put("albumId", s.getAlbumId());
            secretsArray.put(so);
        }
        settings.put("secrets", secretsArray);

        JSONArray albumsArray = new JSONArray();
        for (AlbumEntity a : albums) {
            JSONObject ao = new JSONObject();
            ao.put("id", a.getId()); ao.put("name", a.getName()); ao.put("vaultId", a.getVaultId());
            ao.put("coverFileName", a.getCoverFileName());
            albumsArray.put(ao);
        }
        settings.put("albums", albumsArray);

        java.io.File tempFile = new java.io.File(context.getCacheDir(), "settings.json.enc");
        encryptString(settings.toString(), tempFile, masterPassword);
        updateOrUploadFile(tempFile, "settings.json.enc", "application/octet-stream", parentId, true);
        tempFile.delete();
    }

    private void uploadFileWithPassword(java.io.File localFile, String parentId, String password) throws Exception {
        java.io.File tempCloud = new java.io.File(context.getCacheDir(), "cloud_" + localFile.getName());
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                localFile, context, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build();
        try (InputStream in = encryptedStorage.openFileInput(); FileOutputStream out = new FileOutputStream(tempCloud)) {
            encryptToStream(in, out, password);
        }
        updateOrUploadFile(tempCloud, localFile.getName(), "application/octet-stream", parentId, false);
        tempCloud.delete();
    }

    private void encryptToStream(InputStream is, OutputStream os, String password) throws Exception {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, iv));
        os.write(salt); os.write(iv);
        try (CipherOutputStream cos = new CipherOutputStream(os, cipher)) {
            byte[] buffer = new byte[16384];
            int len;
            while ((len = is.read(buffer)) > 0) cos.write(buffer, 0, len);
            cos.flush();
        }
    }

    public void uploadSingleFileDirect(java.io.File localFile, int vaultId) throws Exception {
        AppDatabase db = AppDatabase.getInstance(context);
        VaultEntity mainVault = db.vaultDao().getMainVault();
        if (mainVault == null) return;
        String backupFolderId = getOrCreateFolderPath("Notes-plus/Saved-Notes/Cloud/Remote/Backup");
        String vaultFolderId = getOrCreateFolder("vault_" + vaultId, backupFolderId);
        uploadFileWithPassword(localFile, vaultFolderId, mainVault.getLockValue());
        SecretEntity entity = db.secretDao().getSecretByFileName(localFile.getName());
        if (entity != null) db.secretDao().markAsSyncedToDrive(entity.getId());
    }

    public void deleteFileDirect(String fileName, int vaultId) throws IOException {
        String backupFolderId = getOrCreateFolderPath("Notes-plus/Saved-Notes/Cloud/Remote/Backup");
        String vaultFolderId = getOrCreateFolder("vault_" + vaultId, backupFolderId);
        String fileId = findFileId(fileName, vaultFolderId);
        if (fileId != null) driveService.files().delete(fileId).execute();
    }

    public void downloadSettingsDirect(java.io.File targetFile) throws Exception {
        String backupFolderId = getOrCreateFolderPath("Notes-plus/Saved-Notes/Cloud/Remote/Backup");
        String fileId = findFileId("settings.json.enc", backupFolderId);
        if (fileId == null) throw new Exception("Settings file not found on Drive");
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            driveService.files().get(fileId).executeMediaAndDownloadTo(fos);
        }
    }

    public void restoreFromDrive(String masterPassword, boolean replaceSettings, boolean replaceMedia, SyncCallback callback) {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(context);
            List<VaultEntity> vaultSnapshot = null;
            List<AlbumEntity> albumSnapshot = null;
            long autoLockSnapshot = SecurityHelper.getAutoLockDelay(context);

            java.io.File vaultDir = new java.io.File(context.getFilesDir(), "vault");
            java.io.File vaultBackupDir = new java.io.File(context.getFilesDir(), "vault_backup");

            try {
                String backupFolderId = getOrCreateFolderPath("Notes-plus/Saved-Notes/Cloud/Remote/Backup");
                updateProgress(callback, 10, "Downloading settings...");
                java.io.File tempSettings = new java.io.File(context.getCacheDir(), "settings_download.json.enc");
                String settingsFileId = findFileId("settings.json.enc", backupFolderId);
                if (settingsFileId == null) throw new Exception("Backup not found on Google Drive");
                try (FileOutputStream fos = new FileOutputStream(tempSettings)) {
                    driveService.files().get(settingsFileId).executeMediaAndDownloadTo(fos);
                }
                String settingsJson = decryptString(tempSettings, masterPassword);
                JSONObject settings = new JSONObject(settingsJson);
                
                // Take DB snapshot
                vaultSnapshot = db.vaultDao().getAllVaultsDirect();
                albumSnapshot = db.albumDao().getAllAlbumsDirect();

                if (replaceMedia && vaultDir.exists()) {
                    if (vaultBackupDir.exists()) deleteRecursive(vaultBackupDir);
                    vaultDir.renameTo(vaultBackupDir);
                }
                if (!vaultDir.exists()) vaultDir.mkdirs();

                if (replaceSettings) {
                    updateProgress(callback, 20, "Updating configurations...");
                    db.runInTransaction(() -> {
                        db.vaultDao().deleteAllVaults();
                        db.albumDao().deleteAllAlbums();
                        try {
                            JSONArray vaults = settings.getJSONArray("vaults");
                            for (int i = 0; i < vaults.length(); i++) {
                                JSONObject vo = vaults.getJSONObject(i);
                                VaultEntity v = new VaultEntity(vo.getString("name"), vo.getString("lockType"), vo.getString("lockValue"), vo.getBoolean("isMain"), vo.optInt("sortOrder", i));
                                v.setId(vo.getInt("id")); 
                                v.setBgType(vo.optString("bgType", "DEFAULT"));
                                // Path will be updated later after background download
                                v.setBgValue(vo.optString("bgValue", null));
                                db.vaultDao().insert(v);

                                if (v.isMain()) {
                                    SecurityHelper.getEncryptedPrefs(context).edit()
                                            .putString(SecurityHelper.KEY_LOCK_TYPE, v.getLockType())
                                            .putString(SecurityHelper.KEY_LOCK_VALUE, v.getLockValue())
                                            .apply();
                                    SecurityHelper.setSetupDone(context, true);
                                }
                            }
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });

                    // Restore core settings
                    SecurityHelper.setAutoLockDelay(context, settings.optLong("auto_lock_time", 0));
                    
                    SharedPreferences.Editor editor = SecurityHelper.getAppPrefs(context).edit();
                    editor.putString(SecurityHelper.KEY_LOCK_BG_TYPE, settings.optString("lock_bg_type", "DEFAULT"));
                    editor.putBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, settings.optBoolean("lock_bg_blur", false));
                    editor.putBoolean(SecurityHelper.KEY_LOCK_BG_DIM, settings.optBoolean("lock_bg_dim", false));
                    
                    String lockBgValue = settings.optString("lock_bg_value", null);
                    // Remap path if it was a local file
                    if (lockBgValue != null && lockBgValue.contains("/files/bg_lock")) {
                        String fileName = new java.io.File(lockBgValue).getName();
                        lockBgValue = new java.io.File(new java.io.File(context.getFilesDir(), "bg_lock"), fileName).getAbsolutePath();
                    }
                    editor.putString(SecurityHelper.KEY_LOCK_BG_VALUE, lockBgValue);
                    editor.apply();
                }

                // 3. Download Backgrounds (ALWAYS do this if they exist)
                updateProgress(callback, 35, "Restoring background media...");
                String bgFolderIdOnDrive = findFileId("backgrounds", backupFolderId);
                if (bgFolderIdOnDrive != null) {
                    List<File> subFolders = listAllFiles("'" + bgFolderIdOnDrive + "' in parents and trashed = false", "id, name");
                    for (File subFolder : subFolders) {
                        java.io.File localDir = new java.io.File(context.getFilesDir(), subFolder.getName());
                        if (!localDir.exists()) localDir.mkdirs();

                        List<File> bgFiles = listAllFiles("'" + subFolder.getId() + "' in parents and trashed = false", "id, name");
                        for (File bgFile : bgFiles) {
                            java.io.File targetFile = new java.io.File(localDir, bgFile.getName());
                            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                                driveService.files().get(bgFile.getId()).executeMediaAndDownloadTo(fos);
                            }
                        }
                    }
                }

                // Remap vault background paths in DB
                List<VaultEntity> restoredVaults = db.vaultDao().getAllVaultsDirect();
                for (VaultEntity v : restoredVaults) {
                    String bgValue = v.getBgValue();
                    if (bgValue != null && bgValue.contains("/files/bg_vault_")) {
                        String fileName = new java.io.File(bgValue).getName();
                        String dirName = "bg_vault_" + v.getId();
                        java.io.File newFile = new java.io.File(new java.io.File(context.getFilesDir(), dirName), fileName);
                        if (newFile.exists()) {
                            v.setBgValue(newFile.getAbsolutePath());
                            db.vaultDao().update(v);
                        }
                    }
                }

                updateProgress(callback, 45, "Restoring media...");
                JSONArray secrets = settings.getJSONArray("secrets");
                int total = secrets.length();
                List<SecretEntity> allExisting = db.secretDao().getAllSecretsDirect();
                Map<String, SecretEntity> existingMap = new HashMap<>();
                for (SecretEntity es : allExisting) {
                    if (es.getOriginalPath() != null) {
                        existingMap.put(es.getVaultId() + "_" + new java.io.File(es.getOriginalPath()).getName(), es);
                    }
                }

                for (int i = 0; i < total; i++) {
                    JSONObject so = secrets.getJSONObject(i);
                    String fileName = so.getString("fileName"); int vaultId = so.getInt("vaultId"); String originalPath = so.getString("originalPath");
                    updateProgress(callback, 45 + (int)((i / (float)total) * 45), "Restoring: " + fileName);
                    
                    SecretEntity existing = null;
                    if (originalPath != null) {
                        existing = existingMap.get(vaultId + "_" + new java.io.File(originalPath).getName());
                    }
                    java.io.File localFile = new java.io.File(vaultDir, fileName);
                    if (existing != null && !replaceMedia) continue;

                    String vaultFolderId = getOrCreateFolder("vault_" + vaultId, backupFolderId);
                    String driveFileId = findFileId(fileName, vaultFolderId);
                    if (driveFileId != null) {
                        java.io.File tempCloud = new java.io.File(context.getCacheDir(), "restore_" + fileName);
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempCloud)) { 
                            driveService.files().get(driveFileId).executeMediaAndDownloadTo(fos); 
                        } catch (IOException e) {
                            Log.e(TAG, "Download failed for: " + fileName, e);
                            continue;
                        }
                        
                        if (localFile.exists()) localFile.delete();
                        try {
                            decryptFromCloudAndSaveToKeystore(tempCloud, localFile, masterPassword);
                        } catch (Exception e) {
                            Log.e(TAG, "Decryption failed for: " + fileName, e);
                        }
                        tempCloud.delete();
                    }

                    SecretEntity s = new SecretEntity(fileName, originalPath, so.getLong("dateTaken"), so.getLong("dateAdded"), so.optBoolean("isVideo", false), vaultId);
                    s.setFavourite(so.optBoolean("isFavourite", false)); if (!so.isNull("albumId")) s.setAlbumId(so.getInt("albumId"));
                    s.setSyncedToDrive(true);

                    if (existing == null) db.secretDao().insert(s);
                    else if (replaceMedia) { 
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
                        if (db.albumDao().getAlbumById(a.getId()) == null) db.albumDao().insert(a);
                        else db.albumDao().update(a);
                    }
                }

                if (vaultBackupDir.exists()) deleteRecursive(vaultBackupDir);
                deleteRecursive(context.getCacheDir());
                context.getCacheDir().mkdirs();

                updateProgress(callback, 100, "Done");
                mainHandler.post(callback::onComplete);

            } catch (Exception e) {
                Log.e(TAG, "Restore failed", e);
                if (replaceMedia && vaultDir.exists()) {
                    deleteRecursive(vaultDir);
                    vaultBackupDir.renameTo(vaultDir);
                }
                if (vaultSnapshot != null) {
                    final List<VaultEntity> finalVaultSnapshot = vaultSnapshot;
                    final List<AlbumEntity> finalAlbumSnapshot = albumSnapshot;
                    db.runInTransaction(() -> {
                        db.vaultDao().deleteAllVaults();
                        db.albumDao().deleteAllAlbums();
                        for (VaultEntity v : finalVaultSnapshot) db.vaultDao().insert(v);
                        for (AlbumEntity a : finalAlbumSnapshot) db.albumDao().insert(a);
                    });
                    SecurityHelper.setAutoLockDelay(context, autoLockSnapshot);
                }
                mainHandler.post(() -> callback.onError("Restore failed: " + e.getMessage()));
            }
        });
    }

    private void decryptFromCloudAndSaveToKeystore(java.io.File cloudFile, java.io.File targetFile, String password) throws Exception {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        EncryptedFile encryptedStorage = new EncryptedFile.Builder(targetFile, context, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();

        try (InputStream is = new java.io.FileInputStream(cloudFile)) {
            byte[] salt = new byte[16]; if (readFully(is, salt) < 16) throw new Exception("Invalid cloud file");
            byte[] iv = new byte[12]; if (readFully(is, iv) < 12) throw new Exception("Invalid cloud file");

            int encryptedLength = (int) (cloudFile.length() - 28);
            byte[] encryptedData = new byte[encryptedLength];
            if (readFully(is, encryptedData) < encryptedLength) throw new Exception("Invalid cloud file data");

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKey tmp = factory.generateSecret(spec);
            SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, iv));

            byte[] decryptedData = cipher.doFinal(encryptedData);

            try (OutputStream os = encryptedStorage.openFileOutput()) {
                os.write(decryptedData);
            }
        }
    }

    private void updateOrUploadFile(java.io.File localFile, String remoteName, String mimeType, String parentId, boolean force) throws IOException {
        String existingId = findFileId(remoteName, parentId);
        File metadata = new File().setName(remoteName);
        FileContent content = new FileContent(mimeType, localFile);

        if (existingId != null) {
            if (!force) {
                File existingMetadata = driveService.files().get(existingId).setFields("size").execute();
                if (existingMetadata.getSize() != null && existingMetadata.getSize() == localFile.length()) {
                    return;
                }
            }
            driveService.files().update(existingId, null, content).execute();
        } else {
            metadata.setParents(Collections.singletonList(parentId));
            driveService.files().create(metadata, content).execute();
        }
    }

    private String getOrCreateFolderPath(String path) throws IOException {
        String parentId = "root"; String[] parts = path.split("/");
        for (String part : parts) parentId = getOrCreateFolder(part, parentId);
        return parentId;
    }

    private String getOrCreateFolder(String name, String parentId) throws IOException {
        String existingId = findFileId(name, parentId); if (existingId != null) return existingId;
        File metadata = new File().setName(name).setMimeType("application/vnd.google-apps.folder").setParents(Collections.singletonList(parentId));
        return driveService.files().create(metadata).setFields("id").execute().getId();
    }

    private String findFileId(String name, String parentId) throws IOException {
        String query = "name = '" + name + "' and '" + parentId + "' in parents and trashed = false";
        List<File> files = listAllFiles(query, "id, name, createdTime");
        if (files == null || files.isEmpty()) return null;
        
        if (files.size() > 1) {
            files.sort((f1, f2) -> f2.getCreatedTime().toString().compareTo(f1.getCreatedTime().toString()));
            for (int i = 1; i < files.size(); i++) driveService.files().delete(files.get(i).getId()).execute();
        }
        return files.get(0).getId();
    }

    private List<File> listAllFiles(String query, String fields) throws IOException {
        List<File> allFiles = new ArrayList<>();
        String pageToken = null;
        do {
            FileList result = driveService.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(" + fields + ")")
                    .setPageToken(pageToken)
                    .execute();
            if (result.getFiles() != null) {
                allFiles.addAll(result.getFiles());
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);
        return allFiles;
    }

    private void encryptString(String data, java.io.File outputFile, String password) throws Exception {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Encryption password cannot be empty");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        SecretKey secret = new SecretKeySpec(factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 65536, 256)).getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        try (FileOutputStream fos = new FileOutputStream(outputFile)) { fos.write(salt); fos.write(iv); fos.write(encrypted); }
    }

    private String decryptString(java.io.File inputFile, String password) throws Exception {
        try (InputStream fis = new FileInputStream(inputFile)) {
            byte[] salt = new byte[16]; if (readFully(fis, salt) < 16) throw new Exception("Invalid file");
            byte[] iv = new byte[12]; if (readFully(fis, iv) < 12) throw new Exception("Invalid file");
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey secret = new SecretKeySpec(factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 65536, 256)).getEncoded(), "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, iv));
            byte[] encrypted = new byte[(int) (inputFile.length() - 28)];
            if (readFully(fis, encrypted) < encrypted.length) throw new Exception("Invalid file");
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
    }

    private int readFully(InputStream is, byte[] buffer) throws IOException {
        int offset = 0; while (offset < buffer.length) {
            int read = is.read(buffer, offset, buffer.length - offset);
            if (read == -1) break; offset += read;
        }
        return offset;
    }

    private void deleteRecursive(java.io.File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            java.io.File[] children = fileOrDirectory.listFiles();
            if (children != null) for (java.io.File child : children) deleteRecursive(child);
        }
        fileOrDirectory.delete();
    }

    private void updateProgress(SyncCallback callback, int progress, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(progress, message));
        }
    }

    public void updateLastSyncTime() {
        SecurityHelper.getAppPrefs(context).edit().putLong("drive_last_sync", System.currentTimeMillis()).apply();
    }
}
