package org.flexstudios.notes.plus;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {
    private static final String TAG = "UpdateManager";
    private static final String VERSION_URL = "https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json";
    private static final String PREFS_NAME = "update_prefs";
    public static final String KEY_PENDING_APK = "pending_apk_path";
    public static final String KEY_FORCED = "is_forced_update";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface UpdateCallback {
        void onUpdateFound(int versionCode, String versionName, boolean forced, String changelog);
        void onNoUpdate();
        void onError(Exception e);
    }

    public UpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private long getLocalVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return PackageInfoCompat.getLongVersionCode(pInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public void checkForUpdates(UpdateCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(VERSION_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setUseCaches(false);
                conn.setConnectTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                int remoteVersionCode = json.getInt("versionCode");
                String versionName = json.getString("versionName");
                boolean forced = json.getBoolean("forced");
                String changelog = json.getString("changelog");
                String apkUrl = json.getString("apkUrl");

                if (remoteVersionCode > getLocalVersionCode()) {
                    mainHandler.post(() -> callback.onUpdateFound(remoteVersionCode, versionName, forced, changelog));
                    startDownload(apkUrl, versionName, forced);
                } else {
                    mainHandler.post(callback::onNoUpdate);
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void startDownload(String urlString, String versionName, boolean forced) {
        String encodedUrl = urlString.replace("+", "%2B");
        File oldFile = new File(context.getExternalFilesDir(null), "update.apk");
        if (oldFile.exists()) oldFile.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(encodedUrl));
        request.setTitle("Notes+ v" + versionName);
        request.setDescription("Downloading update...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, null, "update.apk");

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            dm.enqueue(request);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putBoolean(KEY_FORCED, forced).apply();
        }
    }

    public static void handleInstallation(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apkPath = prefs.getString(KEY_PENDING_APK, null);
        
        if (apkPath == null) return;
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) return;

        // Check for "Install Unknown Apps" permission on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }
        }

        // Trigger the actual installation
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apkFile);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        context.startActivity(intent);
        prefs.edit().remove(KEY_PENDING_APK).apply();
    }
}