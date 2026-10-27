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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
    private static final String PRIMARY_VERSION_URL =
            "https://releases.talken.in/version.json";
    private static final String FALLBACK_VERSION_URL =
            "https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json";
    private static final String PREFS = "update_prefs";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_EXPECTED_CODE = "expected_version_code";
    private static final String KEY_EXPECTED_SHA = "expected_sha256";
    private static final String KEY_READY = "ready_apk";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long AUTO_CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(5);
    private static boolean checkedOnStartThisSession;
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
    /**
     * Checks for updates 5 seconds after startup on a delayed handler, and notifies the user if an update is found.
     */
    public static void checkOnStartup(Activity activity) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (isReadyToInstall(activity)) {
                promptInstallNow(activity, null);
                return;
            }
            check(activity, (release, error) -> {
                if (release != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    showReleaseDialog(activity, release);
                }
            });
        }, 5000);
    }

    public static void onForeground(Activity activity) {
        if (isReadyToInstall(activity)) {
            if (!promptedThisSession) {
                promptedThisSession = true;
                promptInstallNow(activity, null);
            }
            return;
        }
        long last = prefs(activity).getLong(KEY_LAST_CHECK, 0);
        long now = System.currentTimeMillis();
        if (!checkedOnStartThisSession || now - last >= AUTO_CHECK_INTERVAL) {
            checkedOnStartThisSession = true;
            check(activity, (release, error) -> {
                if (release != null && !activity.isFinishing()) showReleaseDialog(activity, release);
            });
        }
    }

    public static void showReleaseDialog(Activity activity, Release release) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle("Update available (v" + release.versionName + ")")
                .setMessage(release.changelog
                        + (release.forced ? "\n\nThis update is required to keep using the app." : ""))
                .setPositiveButton("Download", (d, w) -> {
                    download(activity, release);
                })
                .setCancelable(!release.forced);
        if (!release.forced) builder.setNegativeButton("Later", null);
        builder.show();
    }

    private static Release fetch() throws Exception {
        Exception lastErr = null;
        for (String url : new String[]{PRIMARY_VERSION_URL, FALLBACK_VERSION_URL}) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url + "?t=" + System.currentTimeMillis())
                        .openConnection();
                try {
                    conn.setUseCaches(false);
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setRequestProperty("User-Agent", "NotesPlus-Android/15.5");
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
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "Failed to fetch update from " + url + ": " + e.getMessage());
            }
        }
        if (lastErr != null) throw lastErr;
        throw new java.io.IOException("Failed to fetch version info");
    }

    // --------------------------------------------------------------- download

    public static void download(Context context, Release release) {
        if (context instanceof Activity) {
            download((Activity) context, release);
            return;
        }
        enqueueDownload(context, release);
    }

    private static long enqueueDownload(Context context, Release release) {
        SharedPreferences prefs = prefs(context);
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return -1;

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
        return id;
    }

    public static void download(Activity activity, Release release) {
        long id = enqueueDownload(activity, release);
        if (id == -1) {
            Toast.makeText(activity, "DownloadManager unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(activity);
        label.setText("Downloading Notes+ v" + release.versionName + "…");
        label.setTextSize(15);
        label.setPadding(0, 0, 0, (int) (12 * activity.getResources().getDisplayMetrics().density));
        layout.addView(label);

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        layout.addView(bar);

        TextView details = new TextView(activity);
        details.setText("Connecting…");
        details.setTextSize(12);
        details.setTextColor(0xFF888888);
        details.setPadding(0, (int) (8 * activity.getResources().getDisplayMetrics().density), 0, 0);
        layout.addView(details);

        AlertDialog progressDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Downloading Update")
                .setView(layout)
                .setPositiveButton("Background", (d, w) -> {
                    Toast.makeText(activity, "Download continuing in background. You will be notified when ready.", Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    if (dm != null) dm.remove(id);
                    prefs(activity).edit().remove(KEY_DOWNLOAD_ID).apply();
                })
                .setCancelable(false)
                .show();

        Handler handler = new Handler(Looper.getMainLooper());
        final long[] lastBytes = {0};
        final long[] lastTime = {System.currentTimeMillis()};
        final double[] smoothedSpeed = {0.0};

        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || !progressDialog.isShowing()) return;
                try (Cursor cursor = dm != null ? dm.query(new DownloadManager.Query().setFilterById(id)) : null) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int status = statusIdx >= 0 ? cursor.getInt(statusIdx) : -1;
                        int dlIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int totIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        long downloaded = dlIdx >= 0 ? cursor.getLong(dlIdx) : 0;
                        long total = totIdx >= 0 ? cursor.getLong(totIdx) : -1;

                        long now = System.currentTimeMillis();
                        long dt = now - lastTime[0];
                        if (dt >= 200) {
                            long db = downloaded - lastBytes[0];
                            if (db >= 0 && lastBytes[0] > 0) {
                                double instantSpeed = (db / (1024.0 * 1024.0)) / (dt / 1000.0);
                                smoothedSpeed[0] = smoothedSpeed[0] <= 0 ? instantSpeed : (smoothedSpeed[0] * 0.7 + instantSpeed * 0.3);
                            }
                            lastBytes[0] = downloaded;
                            lastTime[0] = now;
                        }

                        if (total > 0) {
                            bar.setIndeterminate(false);
                            int pct = (int) Math.min(100, Math.max(0, (downloaded * 100) / total));
                            bar.setProgress(pct);
                            if (smoothedSpeed[0] > 0.01) {
                                details.setText(String.format(java.util.Locale.US, "%d%% • %.1f MB / %.1f MB (%.2f MB/s)",
                                        pct, downloaded / (1024.0 * 1024.0), total / (1024.0 * 1024.0), smoothedSpeed[0]));
                            } else {
                                details.setText(String.format(java.util.Locale.US, "%d%% • %.1f MB / %.1f MB",
                                        pct, downloaded / (1024.0 * 1024.0), total / (1024.0 * 1024.0)));
                            }
                        } else if (downloaded > 0) {
                            if (smoothedSpeed[0] > 0.01) {
                                details.setText(String.format(java.util.Locale.US, "%.1f MB (%.2f MB/s)",
                                        downloaded / (1024.0 * 1024.0), smoothedSpeed[0]));
                            } else {
                                details.setText(String.format(java.util.Locale.US, "%.1f MB",
                                        downloaded / (1024.0 * 1024.0)));
                            }
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            progressDialog.dismiss();
                            File target = apkFile(activity);
                            AppDatabase.IO.execute(() -> {
                                boolean ok = verify(activity, target);
                                activity.runOnUiThread(() -> {
                                    if (activity.isFinishing()) return;
                                    if (ok) {
                                        prefs(activity).edit().putString(KEY_READY, target.getAbsolutePath()).apply();
                                        promptInstallNow(activity, release);
                                    } else {
                                        Toast.makeText(activity, "Update verification failed", Toast.LENGTH_LONG).show();
                                    }
                                });
                            });
                            return;
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            progressDialog.dismiss();
                            Toast.makeText(activity, "Update download failed", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Polling error", e);
                }
                handler.postDelayed(this, 300);
            }
        };
        handler.postDelayed(poll, 300);
    }

    public static void promptInstallNow(Activity activity, Release release) {
        if (activity.isFinishing()) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Update Ready to Install")
                .setMessage("Notes+ v" + (release != null ? release.versionName : "") + " has been downloaded.\n\nInstall it now to enjoy the latest features?")
                .setPositiveButton("Install Now", (d, w) -> install(activity))
                .setNegativeButton("Later", null)
                .setCancelable(false)
                .show();
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
