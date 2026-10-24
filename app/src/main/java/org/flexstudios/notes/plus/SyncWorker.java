package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Every long data job in the app, run by WorkManager so it survives the user leaving:
 *
 * <ul>
 *   <li><b>Drive backup</b>: debounced and unique ({@code np-drive-sync}). Fifty imports
 *       coalesce into one run that reads the database once.</li>
 *   <li><b>Local backup, local restore, Drive restore</b>: unique {@code np-data-job}, one at
 *       a time, started from Settings or setup and watched with {@link #watch}.</li>
 * </ul>
 *
 * Each runs as a foreground job with a progress notification and a Cancel action, because a
 * large vault takes far longer than WorkManager's ten-minute budget. The notification never
 * mentions a vault: it is visible to anyone holding the phone. Passwords are handed over in
 * memory ({@link #SECRETS}), never through WorkManager's input data, which is written to disk.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";
    private static final String UNIQUE_SYNC = "np-drive-sync";
    private static final String UNIQUE_JOB = "np-data-job";

    /** Quiet period before a triggered backup starts, so a burst of imports is one run. */
    private static final long DEBOUNCE_SECONDS = 30;
    private static final long NOTIFY_INTERVAL_MS = 500;

    public static final String ACTION_BACKUP = "backup";
    /** Pull, then push: see {@link CloudSync#pullThenPush}. */
    public static final String ACTION_PULL_PUSH = "pull_push";
    public static final String ACTION_REPUBLISH_KEYRING = "republish_keyring";
    public static final String ACTION_LOCAL_BACKUP = "local_backup";
    public static final String ACTION_LOCAL_RESTORE = "local_restore";
    public static final String ACTION_DRIVE_RESTORE = "drive_restore";
    public static final String ACTION_CHECK_BACKUP = "check_backup";

    private static final String KEY_ACTION = "action";
    private static final String KEY_TARGET = "target";
    private static final String KEY_REPLACE_LOCAL = "replace_local";
    private static final String KEY_SNAPSHOT_ID = "snapshot_id";

    // Progress and result channel.
    public static final String PROGRESS_STAGE = "stage";
    public static final String PROGRESS_DONE = "done";
    public static final String PROGRESS_TOTAL = "total";
    public static final String PROGRESS_BYTES_DONE = "bytes_done";
    public static final String PROGRESS_BYTES_TOTAL = "bytes_total";
    public static final String RESULT_MESSAGE = "message";
    public static final String RESULT_WRONG_PASSWORD = "wrong_password";
    /** A backup check finished and found the file damaged (not cancelled, not a wrong key). */
    public static final String RESULT_DAMAGED = "damaged";

    /** Set when the store already holds data this device has never reconciled with. */
    public static final String PREF_NEEDS_CHOICE = "sync_needs_choice";

    private static final String CHANNEL_ID = "sync";
    private static final String CHANNEL_DONE = "sync_done";
    private static final int NOTIFICATION_PROGRESS = 7301;
    private static final int NOTIFICATION_DONE = 7302;

    /** Passwords for queued jobs, by work id. Memory only; gone if the process dies. */
    private static final Map<UUID, String> SECRETS = new ConcurrentHashMap<>();

    private long lastNotified;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    // ================================================================ scheduling

    /** Debounced background backup. Repeated calls collapse into the one pending run. */
    public static void requestBackup(Context context) {
        scheduleSync(context, ACTION_BACKUP, false);
    }

    /** User tapped "Back up now": skip the quiet period. */
    public static OneTimeWorkRequest requestBackupNow(Context context) {
        return scheduleSync(context, ACTION_BACKUP, true);
    }

    /** "Sync now", and every start when that's switched on: pull, then push. */
    public static OneTimeWorkRequest requestPullThenPush(Context context) {
        return scheduleSync(context, ACTION_PULL_PUSH, true);
    }

    /** At app start, if this device syncs its vaults at every start and Drive is connected. */
    public static void syncOnStart(Context context) {
        if (!SecurityHelper.getAppPrefs(context).getBoolean(PREF_SYNC_ON_START, false)) return;
        if (GoogleSignIn.getLastSignedInAccount(context) == null) return;
        requestPullThenPush(context);
    }

    public static final String PREF_SYNC_ON_START = "vault_sync_on_start";

    /** After a lock change, republish the keyring. Tiny, and the only thing that has to move. */
    public static void requestKeyringRepublish(Context context) {
        scheduleSync(context, ACTION_REPUBLISH_KEYRING, true);
    }

    private static OneTimeWorkRequest scheduleSync(Context context, String action, boolean immediate) {
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInputData(new Data.Builder().putString(KEY_ACTION, action).build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(SecurityHelper.isSyncWifiOnly(context)
                                ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS);
        if (!immediate) builder.setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS);
        OneTimeWorkRequest request = builder.build();
        // KEEP for the debounced case so a burst of imports queues one run; an explicit tap
        // replaces whatever is waiting. CloudSync checkpoints, so a replaced run loses little.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_SYNC,
                immediate ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP, request);
        return request;
    }

    /** Writes a .bak to a SAF document uri or a file path. */
    public static UUID startLocalBackup(Context context, String target) {
        return startJob(context, new Data.Builder()
                .putString(KEY_ACTION, ACTION_LOCAL_BACKUP)
                .putString(KEY_TARGET, target).build(), null, false);
    }

    public static UUID startLocalRestore(Context context, Uri backup, String password, boolean replaceLocal) {
        return startJob(context, new Data.Builder()
                .putString(KEY_ACTION, ACTION_LOCAL_RESTORE)
                .putString(KEY_TARGET, backup.toString())
                .putBoolean(KEY_REPLACE_LOCAL, replaceLocal).build(), password, false);
    }

    /** Reads a .bak end to end without restoring anything; see {@link BackupManager#verify}. */
    public static UUID startBackupCheck(Context context, Uri backup, String password) {
        return startJob(context, new Data.Builder()
                .putString(KEY_ACTION, ACTION_CHECK_BACKUP)
                .putString(KEY_TARGET, backup.toString()).build(), password, false);
    }

    public static UUID startDriveRestore(Context context, String snapshotId, String password,
                                         boolean replaceLocal) {
        return startJob(context, new Data.Builder()
                .putString(KEY_ACTION, ACTION_DRIVE_RESTORE)
                .putString(KEY_SNAPSHOT_ID, snapshotId)
                .putBoolean(KEY_REPLACE_LOCAL, replaceLocal).build(), password, true);
    }

    private static UUID startJob(Context context, Data input, String password, boolean needsNetwork) {
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInputData(input);
        if (needsNetwork) {
            builder.setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build());
        }
        OneTimeWorkRequest request = builder.build();
        if (password != null) SECRETS.put(request.getId(), password);
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_JOB, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        return request.getId();
    }

    // ======================================================================= run

    @NonNull
    @Override
    public Result doWork() {
        String action = getInputData().getString(KEY_ACTION);
        try {
            if (ACTION_LOCAL_BACKUP.equals(action)) return runLocalBackup();
            if (ACTION_LOCAL_RESTORE.equals(action)) return runLocalRestore();
            if (ACTION_DRIVE_RESTORE.equals(action)) return runDriveRestore();
            if (ACTION_CHECK_BACKUP.equals(action)) return runBackupCheck();
            return runDriveSync(action);
        } finally {
            SECRETS.remove(getId());
            CloudSync.clearStaging(getApplicationContext());
        }
    }

    private Result runDriveSync(String action) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
        if (account == null) return Result.failure(message("Connect Google Drive to back up."));

        CloudSync sync = new CloudSync(getApplicationContext(), account);
        try {
            if (ACTION_REPUBLISH_KEYRING.equals(action)) {
                sync.republishKeyring();
                return Result.success();
            }
            if (ACTION_PULL_PUSH.equals(action)) {
                notifyProgress("Syncing", 0, 0, true);
                sync.pullThenPush(cloudProgress("Syncing"));
                SecurityHelper.getAppPrefs(getApplicationContext()).edit().putBoolean(PREF_NEEDS_CHOICE, false).apply();
                return Result.success();
            }
            CloudSync.Plan plan = sync.plan();
            if (plan.needsChoice) {
                // Never guess here: choosing wrong is what used to delete the whole backup.
                SecurityHelper.getAppPrefs(getApplicationContext()).edit()
                        .putBoolean(PREF_NEEDS_CHOICE, true).apply();
                return Result.failure(message("This phone and Google Drive don't match. Open Settings to choose."));
            }
            if (plan.toUpload.isEmpty()) {
                sync.backup(plan, CloudSync.Resolution.MERGE, null);
                return Result.success();
            }
            notifyProgress("Syncing", 0, plan.uploadBytes, true);
            sync.backup(plan, CloudSync.Resolution.MERGE, cloudProgress("Syncing"));
            // Anything imported while that run was in flight gets its own follow-up.
            if (!sync.plan().toUpload.isEmpty()) requestBackup(getApplicationContext());
            return Result.success();
        } catch (DriveStore.AuthException | DriveStore.QuotaException e) {
            return Result.failure(message(e.getMessage()));
        } catch (VaultCrypto.WrongPasswordException e) {
            // The Drive backup is under another installation's key: unlocking it is a question
            // for the person, asked from Settings, never guessed here.
            return Result.failure(message("Your Drive backup needs unlocking. Open Settings, Restore from Drive."));
        } catch (IOException e) {
            Log.w(TAG, "Backup interrupted; will retry", e);
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            return Result.failure(message("Backup failed. We'll try again later."));
        }
    }

    private Result runLocalBackup() {
        Context context = getApplicationContext();
        String target = getInputData().getString(KEY_TARGET);
        notifyProgress("Saving backup", 0, 0, true);
        try {
            BackupManager.Result result;
            try (OutputStream out = openTarget(context, target)) {
                result = BackupManager.create(context, out, jobProgress("Saving backup"));
            }
            // Read the file back, decrypting every byte. A backup is only reported as saved
            // once it has been proven to restore.
            notifyProgress("Verifying backup", 0, 0, true);
            BackupManager.Result checked;
            try (InputStream in = openForReading(context, target)) {
                checked = BackupManager.verify(context, in, -1, password(), jobProgress("Verifying backup"));
            }
            if (checked.items != result.items) {
                throw new IOException("The saved file holds " + checked.items + " of " + result.items + " items");
            }
            notifyDone("Backup saved and verified",
                    result.items + " items · " + GallerySettingsActivity.formatBytes(result.bytes));
            return Result.success(message("Backup saved and verified · " + result.items + " items"));
        } catch (CancellationException e) {
            discardTarget(context, target);
            return Result.failure(message("Backup cancelled"));
        } catch (Exception e) {
            Log.e(TAG, "Local backup failed", e);
            discardTarget(context, target);
            notifyDone("Backup failed", readable(e));
            return Result.failure(message("Backup failed: " + readable(e)));
        }
    }

    private Result runLocalRestore() {
        Context context = getApplicationContext();
        Uri uri = Uri.parse(getInputData().getString(KEY_TARGET));
        notifyProgress("Restoring backup", 0, 0, true);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open that file");
            BackupManager.Result result = BackupManager.restore(context, in, sizeOf(context, uri),
                    password(), getInputData().getBoolean(KEY_REPLACE_LOCAL, false),
                    jobProgress("Restoring backup"));
            notifyDone("Backup restored", result.items + " items added");
            return Result.success(message("Restored " + result.items + " items"));
        } catch (VaultCrypto.WrongPasswordException e) {
            return Result.failure(new Data.Builder().putBoolean(RESULT_WRONG_PASSWORD, true).build());
        } catch (CancellationException e) {
            return Result.failure(message("Restore cancelled"));
        } catch (Exception e) {
            Log.e(TAG, "Local restore failed", e);
            notifyDone("Restore failed", readable(e));
            return Result.failure(message("Restore failed: " + readable(e)));
        }
    }

    private Result runBackupCheck() {
        Context context = getApplicationContext();
        Uri uri = Uri.parse(getInputData().getString(KEY_TARGET));
        notifyProgress("Checking backup", 0, 0, true);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open that file");
            BackupManager.Result result = BackupManager.verify(context, in, sizeOf(context, uri), password(),
                    jobProgress("Checking backup"));
            String summary = result.items + " items, " + GallerySettingsActivity.formatBytes(result.bytes);
            notifyDone("Backup checked: it's good", summary);
            return Result.success(message(summary));
        } catch (VaultCrypto.WrongPasswordException e) {
            return Result.failure(new Data.Builder().putBoolean(RESULT_WRONG_PASSWORD, true).build());
        } catch (CancellationException e) {
            return Result.failure(message("Check cancelled"));
        } catch (Exception e) {
            Log.e(TAG, "Backup check failed", e);
            notifyDone("This backup has a problem", readable(e));
            return Result.failure(new Data.Builder().putBoolean(RESULT_DAMAGED, true)
                    .putString(RESULT_MESSAGE, readable(e)).build());
        }
    }

    private Result runDriveRestore() {
        Context context = getApplicationContext();
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) return Result.failure(message("Connect Google Drive first."));
        notifyProgress("Restoring from Drive", 0, 0, true);
        try {
            new CloudSync(context, account).restore(getInputData().getString(KEY_SNAPSHOT_ID),
                    getInputData().getBoolean(KEY_REPLACE_LOCAL, false), password(),
                    cloudProgress("Restoring from Drive"));
            notifyDone("Restored from Google Drive", "Everything is back on this phone");
            return Result.success(message("Restored from Drive"));
        } catch (VaultCrypto.WrongPasswordException e) {
            return Result.failure(new Data.Builder().putBoolean(RESULT_WRONG_PASSWORD, true)
                    .putString(RESULT_MESSAGE, e.getMessage()).build());
        } catch (CancellationException e) {
            return Result.failure(message("Restore cancelled"));
        } catch (Exception e) {
            Log.e(TAG, "Drive restore failed", e);
            String text = SyncChoiceActivity.readable(e);
            notifyDone("Restore incomplete", text);
            return Result.failure(message(text));
        }
    }

    /** The password handed over in memory, or the main vault's lock as the natural first try. */
    private String password() {
        String given = SECRETS.get(getId());
        if (given != null) return given;
        VaultEntity main = AppDatabase.getInstance(getApplicationContext()).vaultDao().getMainVault();
        return main == null ? "" : main.getLockValue();
    }

    // ================================================================= progress

    private BackupManager.Progress jobProgress(String title) {
        return new BackupManager.Progress() {
            @Override
            public void update(long done, long total, String stage) {
                publish(stage, -1, -1, done, total);
                notifyProgress(title, done, total, false);
            }

            @Override
            public boolean isStopped() {
                return SyncWorker.this.isStopped();
            }
        };
    }

    private CloudSync.Progress cloudProgress(String title) {
        return new CloudSync.Progress() {
            // Progress data is replaced wholesale, so a stage update repeats the last figures.
            private int lastDone = -1, lastTotal = -1;
            private long lastBytes = -1, lastBytesTotal = -1;

            @Override
            public void onStage(String stage) {
                publish(stage, lastDone, lastTotal, lastBytes, lastBytesTotal);
            }

            @Override
            public void onItem(int done, int total, long bytesDone, long bytesTotal) {
                lastDone = done;
                lastTotal = total;
                lastBytes = bytesDone;
                lastBytesTotal = bytesTotal;
                publish(null, done, total, bytesDone, bytesTotal);
                notifyProgress(title, bytesTotal > 0 ? bytesDone : done,
                        bytesTotal > 0 ? bytesTotal : total, false);
            }

            @Override
            public boolean isStopped() {
                return SyncWorker.this.isStopped();
            }
        };
    }

    private void publish(String stage, int done, int total, long bytesDone, long bytesTotal) {
        Data.Builder data = new Data.Builder()
                .putInt(PROGRESS_DONE, done).putInt(PROGRESS_TOTAL, total)
                .putLong(PROGRESS_BYTES_DONE, bytesDone).putLong(PROGRESS_BYTES_TOTAL, bytesTotal);
        if (stage != null) data.putString(PROGRESS_STAGE, stage);
        setProgressAsync(data.build());
    }

    /**
     * The ongoing notification, which is also what lifts the ten-minute limit. Best effort:
     * Android 12+ refuses foreground starts from the background in some states, and every
     * job here checkpoints or restarts cleanly without it.
     */
    private void notifyProgress(String title, long done, long total, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotified < NOTIFY_INTERVAL_MS) return;
        lastNotified = now;
        Context context = getApplicationContext();
        ensureChannels(context);

        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        String text = total > 0
                ? GallerySettingsActivity.formatBytes(done) + " of " + GallerySettingsActivity.formatBytes(total)
                + "  ·  " + percent + "%"
                : "Preparing…";
        PendingIntent cancel = WorkManager.getInstance(context).createCancelPendingIntent(getId());
        android.app.Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(openApp(context))
                .addAction(0, "Cancel", cancel)
                .build();
        try {
            ForegroundInfo info = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new ForegroundInfo(NOTIFICATION_PROGRESS, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    : new ForegroundInfo(NOTIFICATION_PROGRESS, notification);
            setForegroundAsync(info);
        } catch (Exception e) {
            Log.i(TAG, "Running without foreground promotion", e);
        }
    }

    /** A dismissible notice when a long job ends, since the user has probably left. */
    private void notifyDone(String title, String text) {
        Context context = getApplicationContext();
        ensureChannels(context);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_DONE,
                    new NotificationCompat.Builder(context, CHANNEL_DONE)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle(title)
                            .setContentText(text)
                            .setAutoCancel(true)
                            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                            .setContentIntent(openApp(context))
                            .build());
        } catch (SecurityException noPermission) {
            // POST_NOTIFICATIONS was declined; the screen still shows the result.
        }
    }

    private static PendingIntent openApp(Context context) {
        // Opens the notes list, never the vault: the notification may be tapped by anyone.
        return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_DONE) != null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Backup progress",
                NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Backup results",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    // ================================================================== helpers

    private static OutputStream openTarget(Context context, String target) throws IOException {
        if (target.startsWith("content://")) {
            OutputStream out = context.getContentResolver().openOutputStream(Uri.parse(target), "wt");
            if (out == null) throw new IOException("Could not write there");
            return out;
        }
        File file = new File(target);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        return new FileOutputStream(file);
    }

    private static InputStream openForReading(Context context, String target) throws IOException {
        if (target.startsWith("content://")) {
            InputStream in = context.getContentResolver().openInputStream(Uri.parse(target));
            if (in == null) throw new IOException("Could not read the backup back");
            return in;
        }
        return new FileInputStream(target);
    }

    /** Removes a half-written backup, so a cancelled or failed one is never mistaken for good. */
    private static void discardTarget(Context context, String target) {
        try {
            if (target.startsWith("content://")) {
                DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(target));
            } else if (!new File(target).delete()) {
                Log.w(TAG, "Could not remove the partial backup");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not remove the partial backup", e);
        }
    }

    private static long sizeOf(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
            // Unknown size: progress becomes indeterminate.
        }
        return -1;
    }

    private static String readable(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Data message(String text) {
        return new Data.Builder().putString(RESULT_MESSAGE, text == null ? "Backup failed." : text).build();
    }

    // =================================================================== watching

    /** What a screen does when the job it started finishes while it is still showing. */
    public interface Watcher {
        void onFinished(WorkInfo info);
    }

    /**
     * Shows a job's live progress in a dialog the user can hide. Hiding (or leaving the
     * screen) changes nothing: the job carries on in the background with its notification.
     */
    public static void watch(AppCompatActivity activity, UUID id, String title, Watcher watcher) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        LinearProgressIndicator bar = new LinearProgressIndicator(activity);
        bar.setIndeterminate(true);
        bar.setTrackCornerRadius(Math.round(3 * density));
        box.addView(bar);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage("Starting…")
                .setView(box)
                .setPositiveButton("Run in background", null)
                .setNegativeButton("Cancel", (d, w) -> WorkManager.getInstance(activity).cancelWorkById(id))
                .setCancelable(false)
                .show();

        WorkManager.getInstance(activity).getWorkInfoByIdLiveData(id).observe(activity, info -> {
            if (info == null) return;
            Data p = info.getProgress();
            long done = p.getLong(PROGRESS_BYTES_DONE, -1), total = p.getLong(PROGRESS_BYTES_TOTAL, -1);
            String stage = p.getString(PROGRESS_STAGE);
            if (total > 0 && done >= 0) {
                bar.setIndeterminate(false);
                bar.setProgressCompat((int) Math.min(100, done * 100 / total), true);
                dialog.setMessage((stage == null ? "" : stage + "\n")
                        + GallerySettingsActivity.formatBytes(done) + " of " + GallerySettingsActivity.formatBytes(total));
            } else if (stage != null) {
                dialog.setMessage(stage);
            }
            if (info.getState().isFinished()) {
                if (dialog.isShowing()) dialog.dismiss();
                watcher.onFinished(info);
            }
        });
    }
}
