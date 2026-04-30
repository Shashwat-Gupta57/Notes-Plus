package org.flexstudios.notes.plus;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

import java.io.File;

public class UpdateDownloadReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE_DOWNLOADED = "org.flexstudios.notes.plus.ACTION_UPDATE_DOWNLOADED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            
            try (Cursor cursor = dm.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status = cursor.getInt(statusIndex);
                    
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // Use the known fixed path instead of dm path which can be inconsistent
                        File apkFile = new File(context.getExternalFilesDir(null), "update.apk");
                        
                        if (apkFile.exists() && apkFile.length() > 0) {
                            SharedPreferences prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
                            prefs.edit().putString("pending_apk_path", apkFile.getAbsolutePath()).apply();
                            
                            Log.d("UpdateReceiver", "Update verified at: " + apkFile.getAbsolutePath());
                            
                            Intent notifyIntent = new Intent(ACTION_UPDATE_DOWNLOADED);
                            notifyIntent.setPackage(context.getPackageName());
                            context.sendBroadcast(notifyIntent);
                        }
                    }
                }
            }
        }
    }
}