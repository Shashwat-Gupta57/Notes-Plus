package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;

public class AppLifecycleObserver implements DefaultLifecycleObserver {
    private final Context context;

    public AppLifecycleObserver(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // App goes to background or closes
        installPendingUpdate();
    }

    private void installPendingUpdate() {
        SharedPreferences prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
        String apkPath = prefs.getString("pending_apk_path", null);
        
        if (apkPath != null) {
            File apkFile = new File(apkPath);
            if (apkFile.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apkFile);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                
                context.startActivity(intent);
                
                // Clear path so we don't trigger again
                prefs.edit().remove("pending_apk_path").apply();
            }
        }
    }
}