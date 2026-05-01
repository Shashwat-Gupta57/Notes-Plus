package org.flexstudios.notes.plus;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Data;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";
    public static final String ACTION_UPLOAD = "upload";
    public static final String ACTION_DELETE = "delete";
    public static final String ACTION_SYNC_ALL = "sync_all";
    public static final String ACTION_UPLOAD_SETTINGS = "upload_settings";

    public static final String KEY_ACTION = "action";
    public static final String KEY_FILE_NAME = "file_name";
    public static final String KEY_VAULT_ID = "vault_id";

    public static void enqueue(Context context, String action, String fileName, int vaultId) {
        Data inputData = new Data.Builder()
                .putString(KEY_ACTION, action)
                .putString(KEY_FILE_NAME, fileName)
                .putInt(KEY_VAULT_ID, vaultId)
                .build();
        
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInputData(inputData)
                .build();
        
        WorkManager.getInstance(context).enqueue(work);
    }

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String action = getInputData().getString(KEY_ACTION);
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
        
        if (account == null) {
            Log.e(TAG, "No Google account found for sync");
            return Result.failure();
        }

        GoogleDriveManager manager = new GoogleDriveManager(getApplicationContext(), account);

        try {
            if (ACTION_UPLOAD.equals(action)) {
                String fileName = getInputData().getString(KEY_FILE_NAME);
                int vaultId = getInputData().getInt(KEY_VAULT_ID, -1);
                if (fileName != null && vaultId != -1) {
                    java.io.File file = new java.io.File(getApplicationContext().getFilesDir(), "vault/" + fileName);
                    if (file.exists()) {
                        manager.uploadSingleFileDirect(file, vaultId);
                    }
                }
            } else if (ACTION_DELETE.equals(action)) {
                String fileName = getInputData().getString(KEY_FILE_NAME);
                int vaultId = getInputData().getInt(KEY_VAULT_ID, -1);
                if (fileName != null && vaultId != -1) {
                    manager.deleteFileDirect(fileName, vaultId);
                }
            } else if (ACTION_SYNC_ALL.equals(action)) {
                manager.syncUnsyncedFilesDirect();
            } else if (ACTION_UPLOAD_SETTINGS.equals(action)) {
                manager.uploadSettingsDirect();
            }
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Sync failed: " + action, e);
            return Result.retry();
        }
    }
}
