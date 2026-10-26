package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * In-app updates from the GitHub release feed.
 *
 * <ol>
 *   <li>{@link #check} reads {@code version.json} on {@code master}.</li>
 *   <li>The user agrees in {@link #showReleaseDialog}; only then does {@link #download}
 *       start. (The old code downloaded before asking, so "Later" did nothing.)</li>
 *   <li>{@link DownloadReceiver} accepts only our own download id and verifies the APK: same
 *       package, the advertised version, the same signing certificate as the installed app,
 *       and the SHA-256 from {@code version.json} when one is given.</li>
 *   <li>{@link #install} runs from a visible activity. The old code launched the installer
 *       when the app went to the background, which Android 10+ silently blocks.</li>
 * </ol>
 *
 * {@code version.json}: {@code versionCode, versionName, forced, changelog, apkUrl}, and
 * optionally {@code sha256} (hex) of the APK.
 */
public final class Updater {

    private static final String TAG = "Updater";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json";
    private static final String PREFS = "update_prefs";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_EXPECTED_CODE = "expected_version_code";
    private static final String KEY_EXPECTED_SHA = "expected_sha256";
    private static final String KEY_READY = "ready_apk";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long AUTO_CHECK_INTERVAL = TimeUnit.HOURS.toMillis(12);
    private static final int TIMEOUT_MS = 15_000;

    /** Once per process, so the "ready to install" prompt doesn't nag on every resume. */
    private static boolean promptedThisSession;

    private Updater() {}

    public static final class Release {
        public int versionCode;
        public String versionName;
        public String changelog;
        public String apkUrl;
        public String sha256;
        public boolean forced;
    }

    public interface Callback {
        /** Main thread. {@code release} is null when the app is up to date. */
        void onResult(Release release, Exception error);
    }

    // ---------------------------------------------------------------- checking

    public static void check(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        AppDatabase.IO.execute(() -> {
            try {
                Release release = fetch();
                prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                boolean newer = release.versionCode > installedVersion(app);
                main.post(() -> callback.onResult(newer ? release : null, null));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                main.post(() -> callback.onResult(null, e));
            }
        });
    }

    /**
     * What the notes screen calls on every resume: offers a downloaded update, and checks for
     * a new one at most every twelve hours.
     */
    public static void onForeground(Activity activity) {
        if (isReadyToInstall(activity)) {
            if (!promptedThisSession) {
                promptedThisSession = true;
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Update ready")
                        .setMessage("A new version of Notes+ has been downloaded.")
                        .setPositiveButton("Install", (d, w) -> install(activity))
                        .setNegativeButton("Later", null)
                        .show();
            }
            return;
        }
        long last = prefs(activity).getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL) return;
        check(activity, (release, error) -> {
            if (release != null && !activity.isFinishing()) showReleaseDialog(activity, release);
        });
    }

    public static void showReleaseDialog(Activity activity, Release release) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle("Update available (v" + release.versionName + ")")
                .setMessage(release.changelog
                        + (release.forced ? "\n\nThis update is required to keep using the app." : ""))
                .setPositiveButton("Download", (d, w) -> {
                    download(activity, release);
                    Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show();
                })
                .setCancelable(!release.forced);
        if (!release.forced) builder.setNegativeButton("Later", null);
        builder.show();
    }

    private static Release fetch() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_URL + "?t=" + System.currentTimeMillis())
                .openConnection();
        try {
            conn.setUseCaches(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + status);
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(VaultCrypto.readAll(in), java.nio.charset.StandardCharsets.UTF_8);
            }
            JSONObject json = new JSONObject(body);
            Release release = new Release();
            release.versionCode = json.getInt("versionCode");
            release.versionName = json.optString("versionName", String.valueOf(release.versionCode));
            release.changelog = json.optString("changelog", "");
            release.apkUrl = json.getString("apkUrl");
            release.forced = json.optBoolean("forced", false);
            String sha = json.optString("sha256", "");
            release.sha256 = sha.isEmpty() ? null : sha.toLowerCase(java.util.Locale.US);
            if (!release.apkUrl.startsWith("https://")) throw new SecurityException("Insecure update URL");
            return release;
        } finally {
            conn.disconnect();
        }
    }

    // --------------------------------------------------------------- download

    public static void download(Context context, Release release) {
        SharedPreferences prefs = prefs(context);
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;

        long previous = prefs.getLong(KEY_DOWNLOAD_ID, -1);
        if (previous != -1) dm.remove(previous);
        File target = apkFile(context);
        if (target.exists() && !target.delete()) Log.w(TAG, "Could not remove the old download");

        DownloadManager.Request request = new DownloadManager.Request(
                Uri.parse(release.apkUrl.replace("+", "%2B")))
                .setTitle("Notes+ v" + release.versionName)
                .setDescription("Downloading update…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, target.getName());
        long id = dm.enqueue(request);
        prefs.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putInt(KEY_EXPECTED_CODE, release.versionCode)
                .putString(KEY_EXPECTED_SHA, release.sha256)
                .remove(KEY_READY)
                .apply();
    }

    /** Receives DownloadManager's completion broadcast; acts only on our own download. */
    public static class DownloadReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            SharedPreferences prefs = prefs(context);
            if (id == -1 || id != prefs.getLong(KEY_DOWNLOAD_ID, -2)) return;

            PendingResult pending = goAsync();
            AppDatabase.IO.execute(() -> {
                try {
                    boolean ok = downloadSucceeded(context, id) && verify(context, apkFile(context));
                    SharedPreferences.Editor editor = prefs.edit().remove(KEY_DOWNLOAD_ID);
                    if (ok) {
                        editor.putString(KEY_READY, apkFile(context).getAbsolutePath());
                    } else {
                        File apk = apkFile(context);
                        if (apk.exists() && !apk.delete()) Log.w(TAG, "Could not remove rejected APK");
                    }
                    editor.apply();
                } finally {
                    pending.finish();
                }
            });
        }
    }

    private static boolean downloadSucceeded(Context context, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return false;
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return index >= 0 && cursor.getInt(index) == DownloadManager.STATUS_SUCCESSFUL;
        }
    }

    // ---------------------------------------------------------------- install

    public static boolean isReadyToInstall(Context context) {
        SharedPreferences p = prefs(context);
        String path = p.getString(KEY_READY, null);
        File target = apkFile(context);
        int expected = p.getInt(KEY_EXPECTED_CODE, 0);
        if (expected > 0 && installedVersion(context) >= expected) {
            // Already updated: delete leftover APK and clear update prefs to stop the loop.
            if (path != null) {
                File ready = new File(path);
                if (ready.exists() && !ready.delete()) Log.w(TAG, "Could not delete installed update APK");
            }
            if (target.exists() && !target.delete()) Log.w(TAG, "Could not delete installed target APK");
            p.edit().remove(KEY_READY).remove(KEY_EXPECTED_CODE).remove(KEY_EXPECTED_SHA).apply();
            return false;
        }
        if (path == null) return false;
        File apk = new File(path);
        if (apk.exists()) return true;
        p.edit().remove(KEY_READY).apply();
        return false;
    }

    /** Hands the verified APK to the system installer. Call from a visible activity. */
    public static void install(Activity activity) {
        if (!isReadyToInstall(activity)) return;
        File apk = new File(prefs(activity).getString(KEY_READY, ""));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow Notes+ to install updates, then try again",
                    Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        // External storage is writable by other apps on old Android: check again right
        // before handing it over, not just when the download finished.
        AppDatabase.IO.execute(() -> {
            boolean ok = verify(activity, apk);
            activity.runOnUiThread(() -> {
                if (!ok) {
                    prefs(activity).edit().remove(KEY_READY).apply();
                    Toast.makeText(activity, "The downloaded update failed verification",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
                activity.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            });
        });
    }

    // ----------------------------------------------------------- verification

    /** Same package, the advertised version, our signing certificate, and the hash if given. */
    static boolean verify(Context context, File apk) {
        try {
            if (!apk.exists() || apk.length() == 0) return false;
            SharedPreferences prefs = prefs(context);
            PackageManager pm = context.getPackageManager();

            String expectedSha = prefs.getString(KEY_EXPECTED_SHA, null);
            if (expectedSha != null && !expectedSha.equals(sha256(apk))) {
                Log.w(TAG, "Update rejected: SHA-256 mismatch");
                return false;
            }

            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null || !context.getPackageName().equals(archive.packageName)) {
                Log.w(TAG, "Update rejected: not this app");
                return false;
            }
            long expectedCode = prefs.getInt(KEY_EXPECTED_CODE, -1);
            if (PackageInfoCompat.getLongVersionCode(archive) != expectedCode) {
                Log.w(TAG, "Update rejected: unexpected version");
                return false;
            }
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            if (!signers(installed).equals(signers(archive)) || signers(archive).isEmpty()) {
                Log.w(TAG, "Update rejected: signed by a different key");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Update verification failed", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> out = new HashSet<>();
        if (signatures == null) return out;
        for (Signature s : signatures) {
            out.add(VaultCrypto.bytesToHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        }
        return out;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(new FileInputStream(file), digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (in.read(buffer) != -1) {
                // Reading updates the digest.
            }
        }
        return VaultCrypto.bytesToHex(digest.digest());
    }

    // ------------------------------------------------------------------ helpers

    private static long installedVersion(Context context) {
        try {
            return PackageInfoCompat.getLongVersionCode(
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static File apkFile(Context context) {
        return new File(context.getExternalFilesDir(null), "update.apk");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
