package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.lifecycle.Observer;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GallerySettingsActivity extends VaultActivity {
    private static final int PICK_VAULT_IMAGE = 1001;
    private static final int PICK_VAULT_VIDEO = 1002;
    private static final int PICK_LOCK_IMAGE = 1003;
    private static final int PICK_LOCK_VIDEO = 1004;
    private static final int CREATE_FILE_BACKUP = 1005;
    private static final int RC_SIGN_IN = 1006;
    private static final int PICK_BACKUP = 2001;
    private static final int PICK_BACKUP_TO_CHECK = 2002;

    private RecyclerView recyclerViewVaults;
    private VaultSettingsAdapter adapter;
    private AppDatabase database;
    private List<VaultEntity> vaults = new ArrayList<>();
    private VaultEntity currentVault;
    private boolean isMainVaultSettings = true;

    private android.widget.CompoundButton checkLockBgBlur, checkLockBgDim;
    private View layoutLockBgOptions;
    private TextView textUpdateStatus;

    // Google Drive views
    private TextView textDriveAccount, textDriveLastSync, textDriveStored, textDriveToUpload;
    private TextView textSyncStage, textRestorePointsHeader, textNoRestorePoints, textRetentionNote;
    private View layoutDriveStats, layoutSyncProgress;
    private com.google.android.material.button.MaterialButton btnDriveAction, btnDriveSyncNow;
    private com.google.android.material.progressindicator.LinearProgressIndicator syncProgress;
    private com.google.android.material.materialswitch.MaterialSwitch switchAutoSync, switchWifiOnly;
    private RecyclerView recyclerRestorePoints;
    private RestorePointAdapter restorePointAdapter;
    private boolean driveConnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery_settings);

        database = AppDatabase.getInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().mutate().setTint(ContextCompat.getColor(this, R.color.text_primary));
        }

        recyclerViewVaults = findViewById(R.id.recyclerViewVaults);
        recyclerViewVaults.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VaultSettingsAdapter();
        recyclerViewVaults.setAdapter(adapter);

        findViewById(R.id.btnAddVault).setOnClickListener(v -> showCreateVaultDialog());
        findViewById(R.id.optionChangeLock).setOnClickListener(v -> {
            if (currentVault != null) showEditVaultDialog(currentVault);
        });

        findViewById(R.id.optionCreateBackup).setOnClickListener(v -> showBackupExplainDialog());
        findViewById(R.id.optionRestoreData).setOnClickListener(v -> showRestoreFlowDialog());
        findViewById(R.id.optionBackupPassword).setOnClickListener(v -> showBackupPasswordDialog());

        setupAutoLock();
        setupBackgroundSettings();
        setupUpdateSettings();
        setupDriveSettings();
        checkVaultAccess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUpdateStatus();
        refreshBackupPasswordState();
    }

    private void refreshBackupPasswordState() {
        TextView state = findViewById(R.id.textBackupPasswordState);
        AppDatabase.IO.execute(() -> {
            boolean set = VaultCrypto.hasBackupPassword(this);
            runOnUiThread(() -> state.setText(set
                    ? "On · backups open only with this password"
                    : "Recommended: PINs are easy to guess offline"));
        });
    }

    private void setupAutoLock() {
        com.google.android.material.button.MaterialButtonToggleGroup group =
                findViewById(R.id.radioGroupAutoLock);
        long currentDelay = SecurityHelper.getAutoLockDelay(this);
        group.check(currentDelay == 30000 ? R.id.radio30s
                : currentDelay == 60000 ? R.id.radio1m : R.id.radioImmediately);

        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            long delay = 0;
            if (checkedId == R.id.radio30s) delay = 30000;
            else if (checkedId == R.id.radio1m) delay = 60000;
            SecurityHelper.setAutoLockDelay(this, delay);
            checkAndUpdateDriveSettings();
        });
    }

    private void setupBackgroundSettings() {
        findViewById(R.id.btnVaultBgDefault).setOnClickListener(v -> updateVaultBg("DEFAULT", null));
        findViewById(R.id.btnVaultBgColor).setOnClickListener(v -> showColorPickerDialog(true));
        findViewById(R.id.btnVaultBgImage).setOnClickListener(v -> pickMedia("image/*", PICK_VAULT_IMAGE));
        findViewById(R.id.btnVaultBgVideo).setOnClickListener(v -> pickMedia("video/*", PICK_VAULT_VIDEO));
        findViewById(R.id.btnVaultBgUrl).setOnClickListener(v -> showUrlInputDialog(true));

        findViewById(R.id.btnLockBgDefault).setOnClickListener(v -> updateLockBg("DEFAULT", null));
        findViewById(R.id.btnLockBgColor).setOnClickListener(v -> showColorPickerDialog(false));
        findViewById(R.id.btnLockBgImage).setOnClickListener(v -> pickMedia("image/*", PICK_LOCK_IMAGE));
        findViewById(R.id.btnLockBgVideo).setOnClickListener(v -> pickMedia("video/*", PICK_LOCK_VIDEO));
        findViewById(R.id.btnLockBgUrl).setOnClickListener(v -> showUrlInputDialog(false));

        layoutLockBgOptions = findViewById(R.id.layoutLockBgOptions);
        checkLockBgBlur = findViewById(R.id.checkLockBgBlur);
        checkLockBgDim = findViewById(R.id.checkLockBgDim);

        layoutLockBgOptions.setVisibility(View.VISIBLE);

        checkLockBgBlur.setChecked(SecurityHelper.getAppPrefs(this).getBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, false));
        checkLockBgDim.setChecked(SecurityHelper.getAppPrefs(this).getBoolean(SecurityHelper.KEY_LOCK_BG_DIM, false));

        checkLockBgBlur.setOnCheckedChangeListener((v, checked) -> SecurityHelper.getAppPrefs(this).edit().putBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, checked).apply());
        checkLockBgDim.setOnCheckedChangeListener((v, checked) -> SecurityHelper.getAppPrefs(this).edit().putBoolean(SecurityHelper.KEY_LOCK_BG_DIM, checked).apply());
    }

    private void setupUpdateSettings() {
        TextView version = findViewById(R.id.textAppVersion);
        try {
            version.setText("Notes+ " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
            version.setText("Notes+");
        }
        textUpdateStatus = findViewById(R.id.textUpdateStatus);
        findViewById(R.id.btnCheckUpdates).setOnClickListener(v -> performUpdateCheck());
        refreshUpdateStatus();
    }

    private void performUpdateCheck() {
        if (Updater.isReadyToInstall(this)) {
            Updater.install(this);
            return;
        }
        textUpdateStatus.setText("Checking for updates...");
        Updater.check(this, (release, error) -> {
            if (error != null) {
                textUpdateStatus.setText("Couldn't check for updates");
            } else if (release == null) {
                textUpdateStatus.setText("App is up to date");
            } else {
                textUpdateStatus.setText("Version " + release.versionName + " is available");
                Updater.showReleaseDialog(this, release);
            }
        });
    }

    private void refreshUpdateStatus() {
        if (textUpdateStatus == null) return;
        textUpdateStatus.setText(Updater.isReadyToInstall(this)
                ? "Update downloaded. Tap Check for Updates to install."
                : "Tap to check for updates");
    }

    private void pickMedia(String type, int requestCode) {
        expectExternalActivity();
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType(type);
        startActivityForResult(intent, requestCode);
    }

    private void showUrlInputDialog(boolean forVault) {
        Prompts.text(this, "Wallpaper from a link", "An image, GIF or video on the web.",
                "https://…", null, Prompts.URL, "Use it", url -> {
                    if (forVault) updateVaultBg("URL", url);
                    else updateLockBg("URL", url);
                }, null);
    }

    private void showColorPickerDialog(boolean forVault) {
        Prompts.text(this, "Colour", "A hex colour, like #1C1C1E.", "#RRGGBB", "#", Prompts.TEXT, "Use it", color -> {
            try {
                Color.parseColor(color);
                if (forVault) updateVaultBg("COLOR", color);
                else updateLockBg("COLOR", color);
            } catch (Exception e) {
                Toast.makeText(this, "That isn't a colour like #RRGGBB", Toast.LENGTH_SHORT).show();
            }
        }, null);
    }

    private void updateVaultBg(String type, String value) {
        if (currentVault == null) return;
        currentVault.setBgType(type);
        currentVault.setBgValue(value);
        AppDatabase.IO.execute(() -> database.vaultDao().update(currentVault));
        checkAndUpdateDriveSettings();
        Toast.makeText(this, "Vault background updated", Toast.LENGTH_SHORT).show();
    }

    private void updateLockBg(String type, String value) {
        SecurityHelper.getAppPrefs(this).edit()
                .putString(SecurityHelper.KEY_LOCK_BG_TYPE, type)
                .putString(SecurityHelper.KEY_LOCK_BG_VALUE, value)
                .apply();
        checkAndUpdateDriveSettings();
        Toast.makeText(this, "Lock screen background updated", Toast.LENGTH_SHORT).show();
    }

    private void showBackupExplainDialog() {
        String defaultPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath() + "/Notes-Plus/Backups/";
        
        new MaterialAlertDialogBuilder(this)
                .setTitle("Local Backup")
                .setMessage("All vault media and settings will be saved as an encrypted .bak file that can only be read by this app.\n\nDefault location:\n" + defaultPath)
                .setPositiveButton("Save to Default", (d, w) -> {
                    File dir = new File(defaultPath);
                    if (!dir.exists()) dir.mkdirs();
                    File target = new File(dir, "NotesPlus_Backup_" + System.currentTimeMillis() + ".bak");
                    startBackup(target.getAbsolutePath(), target.getAbsolutePath());
                })
                .setNeutralButton("Pick Location", (d, w) -> {
                    expectExternalActivity();
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, "NotesPlus_Backup_" + System.currentTimeMillis() + ".bak");
                    startActivityForResult(intent, CREATE_FILE_BACKUP);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Runs the backup as a background job: it keeps going with a progress notification if
     * the user leaves, which a multi-gigabyte vault needs.
     */
    private void startBackup(String target, String displayPath) {
        askForNotifications();
        UUID job = SyncWorker.startLocalBackup(this, target);
        SyncWorker.watch(this, job, "Creating backup", info -> {
            if (info.getState() == WorkInfo.State.SUCCEEDED) {
                showBackupCompleteDialog(displayPath != null ? displayPath : "the location you chose");
            } else {
                String message = info.getOutputData().getString(SyncWorker.RESULT_MESSAGE);
                Toast.makeText(this, message != null ? message : "Backup failed", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Progress for long jobs lives in a notification, which Android 13+ asks permission for. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    /**
     * The backup password replaces the vault locks as the key to everything that leaves the
     * phone. A 4-digit PIN falls to an offline guesser in minutes; a passphrase does not.
     */
    private void showBackupPasswordDialog() {
        boolean isSet = VaultCrypto.hasBackupPassword(this);
        com.google.android.material.textfield.TextInputLayout field =
                Prompts.field(this, "At least 10 characters", null, Prompts.PASSWORD);
        com.google.android.material.textfield.TextInputEditText input =
                (com.google.android.material.textfield.TextInputEditText) field.getEditText();
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Backup Password")
                .setMessage((isSet ? "A backup password is set. " : "")
                        + "When set, local and Google Drive backups open only with this password, "
                        + "not with your vault PINs, which are short enough to be guessed. "
                        + "Keep it somewhere safe: without it a backup cannot be opened.")
                .setView(Prompts.padded(this, field))
                .setPositiveButton(isSet ? "Change" : "Set", (d, w) -> {
                    String password = Prompts.textOf(input);
                    if (password.length() < 10) {
                        Toast.makeText(this, "Use at least 10 characters", Toast.LENGTH_LONG).show();
                        return;
                    }
                    saveBackupPassword(password);
                })
                .setNegativeButton("Cancel", null);
        if (isSet) builder.setNeutralButton("Remove", (d, w) -> saveBackupPassword(null));
        AlertDialog dialog = builder.create();
        Prompts.wire(dialog, input);
        dialog.show();
    }

    private void saveBackupPassword(String password) {
        AppDatabase.IO.execute(() -> {
            try {
                VaultCrypto.setBackupPassword(this, password);
                if (driveConnected) SyncWorker.requestKeyringRepublish(this);
                runOnUiThread(() -> {
                    Toast.makeText(this, password == null
                            ? "Backup password removed" : "Backup password saved", Toast.LENGTH_SHORT).show();
                    refreshBackupPasswordState();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't save the password", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showBackupCompleteDialog(String path) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Backup Successful")
                .setMessage("Saved to: " + path)
                .setPositiveButton("OK", null)
                .setNeutralButton("Share", (d, w) -> {
                    File file = new File(path);
                    if (file.exists()) {
                        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("application/octet-stream");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "Share Backup File"));
                    } else {
                        Toast.makeText(this, "File not found for sharing. It might be in a protected location.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** Debounced: a burst of settings edits collapses into one background backup. */
    private void checkAndUpdateDriveSettings() {
        if (SecurityHelper.isAutoSyncEnabled(this)) {
            SyncWorker.requestBackup(this);
        }
    }

    private void setupDriveSettings() {
        textDriveAccount = findViewById(R.id.textDriveAccount);
        textDriveLastSync = findViewById(R.id.textDriveLastSync);
        textDriveStored = findViewById(R.id.textDriveStored);
        textDriveToUpload = findViewById(R.id.textDriveToUpload);
        textSyncStage = findViewById(R.id.textSyncStage);
        textRestorePointsHeader = findViewById(R.id.textRestorePointsHeader);
        textNoRestorePoints = findViewById(R.id.textNoRestorePoints);
        textRetentionNote = findViewById(R.id.textRetentionNote);
        layoutDriveStats = findViewById(R.id.layoutDriveStats);
        layoutSyncProgress = findViewById(R.id.layoutSyncProgress);
        syncProgress = findViewById(R.id.syncProgress);
        btnDriveAction = findViewById(R.id.btnDriveAction);
        btnDriveSyncNow = findViewById(R.id.btnDriveSyncNow);
        switchAutoSync = findViewById(R.id.switchAutoSync);
        switchWifiOnly = findViewById(R.id.switchWifiOnly);

        recyclerRestorePoints = findViewById(R.id.recyclerRestorePoints);
        recyclerRestorePoints.setLayoutManager(new LinearLayoutManager(this));
        restorePointAdapter = new RestorePointAdapter();
        recyclerRestorePoints.setAdapter(restorePointAdapter);

        btnDriveAction.setOnClickListener(v -> {
            if (GoogleSignIn.getLastSignedInAccount(this) == null) {
                expectExternalActivity();
                startActivityForResult(DriveStore.signInClient(this).getSignInIntent(), RC_SIGN_IN);
            } else {
                DriveStore.signInClient(this).signOut().addOnCompleteListener(task -> {
                    // Forget what this device believed was already uploaded, so reconnecting
                    // to a different account asks rather than assuming.
                    CloudSync.clearBaseline(this);
                    updateDriveUI(null);
                });
            }
        });

        btnDriveSyncNow.setOnClickListener(v -> triggerDriveSync());

        attachSyncSwitchListeners();

        updateDriveUI(GoogleSignIn.getLastSignedInAccount(this));
    }

    /** Kept separate so state can be restored with the listeners detached. */
    private void attachSyncSwitchListeners() {
        switchAutoSync.setOnCheckedChangeListener((v, checked) -> {
            SecurityHelper.setAutoSyncEnabled(this, checked);
            switchWifiOnly.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) SyncWorker.requestBackup(this);
        });

        switchWifiOnly.setOnCheckedChangeListener((v, checked) ->
                SecurityHelper.setSyncNetwork(this, checked));
    }

    private void updateDriveUI(GoogleSignInAccount account) {
        boolean connected = account != null;
        driveConnected = connected;

        int visibility = connected ? View.VISIBLE : View.GONE;
        layoutDriveStats.setVisibility(visibility);
        btnDriveSyncNow.setVisibility(visibility);
        switchAutoSync.setVisibility(visibility);
        textRestorePointsHeader.setVisibility(visibility);
        textRetentionNote.setVisibility(visibility);

        if (!connected) {
            textDriveAccount.setText("Not connected");
            textDriveLastSync.setText("Back up every vault, encrypted, to your Drive");
            btnDriveAction.setText("Connect Google Drive");
            switchWifiOnly.setVisibility(View.GONE);
            recyclerRestorePoints.setVisibility(View.GONE);
            textNoRestorePoints.setVisibility(View.GONE);
            layoutSyncProgress.setVisibility(View.GONE);
            return;
        }

        textDriveAccount.setText(account.getEmail());
        btnDriveAction.setText("Disconnect");

        // setChecked() fires the change listener, so restoring the saved state would look
        // like the user just toggled it and would enqueue a backup on every visit here.
        boolean auto = SecurityHelper.isAutoSyncEnabled(this);
        switchAutoSync.setOnCheckedChangeListener(null);
        switchWifiOnly.setOnCheckedChangeListener(null);
        switchAutoSync.setChecked(auto);
        switchWifiOnly.setChecked(SecurityHelper.isSyncWifiOnly(this));
        switchWifiOnly.setVisibility(auto ? View.VISIBLE : View.GONE);
        attachSyncSwitchListeners();

        long lastSync = SecurityHelper.getAppPrefs(this).getLong("drive_last_sync", 0);
        textDriveLastSync.setText(lastSync > 0
                ? "Last backup: " + new java.text.SimpleDateFormat("d MMM, HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(lastSync))
                : "Last backup: Never");

        refreshDriveStatus(account);
    }

    /**
     * Fills in what is stored and what is still to upload.
     *
     * The "to upload" figure is the point of the object store: it is the whole cost of the
     * next backup, not the size of the vault. Five gigabytes already up and three hundred
     * megabytes new reads as exactly that.
     */
    private void refreshDriveStatus(GoogleSignInAccount account) {
        textDriveStored.setText("Backed up: checking\u2026");
        textDriveToUpload.setText("");

        AppDatabase.IO.execute(() -> {
            try {
                CloudSync sync = new CloudSync(this, account);
                CloudSync.Plan plan = sync.plan();
                List<Snapshot.Summary> points = sync.listRestorePoints();

                runOnUiThread(() -> {
                    textDriveStored.setText("Backed up: " + plan.storedCount + " items \u00b7 "
                            + formatBytes(plan.storedBytes));
                    if (plan.toUpload.isEmpty()) {
                        textDriveToUpload.setText("Everything is backed up.");
                    } else {
                        textDriveToUpload.setText("To upload: " + plan.toUpload.size()
                                + " items \u00b7 " + formatBytes(plan.uploadBytes));
                    }

                    restorePointAdapter.setPoints(points);
                    recyclerRestorePoints.setVisibility(points.isEmpty() ? View.GONE : View.VISIBLE);
                    textNoRestorePoints.setVisibility(points.isEmpty() ? View.VISIBLE : View.GONE);

                    if (plan.needsChoice) {
                        textDriveToUpload.setText("This phone and Drive don\u2019t match \u2014 tap Back up now.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> textDriveStored.setText(SyncChoiceActivity.readable(e)));
            }
        });
    }

    private void triggerDriveSync() {
        if (!driveConnected) return;

        // A device that has never reconciled with an existing backup must be asked first.
        // Guessing here is what used to delete the whole thing.
        if (SecurityHelper.getAppPrefs(this).getBoolean(SyncWorker.PREF_NEEDS_CHOICE, false)) {
            startActivity(SyncChoiceActivity.intent(this, vaultId));
            return;
        }

        observeSyncProgress(SyncWorker.requestBackupNow(this).getId());
    }

    /**
     * Reports what the worker is actually doing. The previous version spun an icon that was
     * unconnected to any real progress, because the worker published none.
     */
    private void observeSyncProgress(java.util.UUID workId) {
        layoutSyncProgress.setVisibility(View.VISIBLE);
        syncProgress.setIndeterminate(true);
        textSyncStage.setText("Starting\u2026");
        btnDriveSyncNow.setEnabled(false);

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId)
                .observe(this, workInfo -> {
                    if (workInfo == null) return;

                    Data progress = workInfo.getProgress();
                    String stage = progress.getString(SyncWorker.PROGRESS_STAGE);
                    int done = progress.getInt(SyncWorker.PROGRESS_DONE, -1);
                    int total = progress.getInt(SyncWorker.PROGRESS_TOTAL, -1);
                    long bytesDone = progress.getLong(SyncWorker.PROGRESS_BYTES_DONE, -1);
                    long bytesTotal = progress.getLong(SyncWorker.PROGRESS_BYTES_TOTAL, -1);

                    if (done >= 0 && total > 0) {
                        syncProgress.setIndeterminate(false);
                        syncProgress.setMax(total);
                        syncProgress.setProgress(done);
                        String label = "Uploading " + done + " of " + total;
                        if (bytesTotal > 0) {
                            label += " \u00b7 " + formatBytes(bytesDone) + " of "
                                    + formatBytes(bytesTotal);
                        }
                        textSyncStage.setText(label);
                    } else if (stage != null) {
                        textSyncStage.setText(stage);
                    }

                    if (workInfo.getState().isFinished()) {
                        boolean ok = workInfo.getState() == WorkInfo.State.SUCCEEDED;
                        layoutSyncProgress.setVisibility(View.GONE);
                        btnDriveSyncNow.setEnabled(true);

                        if (!ok) {
                            String message = workInfo.getOutputData()
                                    .getString(SyncWorker.RESULT_MESSAGE);
                            if (message != null) {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                            }
                            if (SecurityHelper.getAppPrefs(this)
                                    .getBoolean(SyncWorker.PREF_NEEDS_CHOICE, false)) {
                                startActivity(SyncChoiceActivity.intent(this, vaultId));
                            }
                        } else {
                            Toast.makeText(this, "Backed up to Google Drive",
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateDriveUI(GoogleSignIn.getLastSignedInAccount(this));
                    }
                });
    }

    /** Sizes read by a person, not a machine. */
    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.getDefault(),
                value >= 10 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    /** Restore points, newest first, with a pin toggle that exempts one from cleanup. */
    private class RestorePointAdapter extends RecyclerView.Adapter<RestorePointAdapter.Holder> {
        private List<Snapshot.Summary> points = new ArrayList<>();

        void setPoints(List<Snapshot.Summary> points) {
            this.points = points == null ? new ArrayList<>() : points;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_restore_point, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Snapshot.Summary point = points.get(position);
            holder.date.setText(point.displayName());

            String detail = point.itemCount + " items";
            if (point.device != null) detail += " \u00b7 " + point.device;
            holder.detail.setText(detail);

            holder.pin.setImageResource(point.pinned
                    ? R.drawable.ic_heart_filled : R.drawable.ic_heart_empty);
            holder.pin.setContentDescription(point.pinned
                    ? "Stop keeping this restore point" : "Keep this restore point");

            holder.pin.setOnClickListener(v -> togglePin(point));
            holder.restore.setOnClickListener(v -> {
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(
                        GallerySettingsActivity.this);
                if (account == null) return;
                confirmDriveRestore(new CloudSync(GallerySettingsActivity.this, account), point);
            });
        }

        @Override
        public int getItemCount() {
            return points.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            TextView date, detail;
            ImageButton pin;
            com.google.android.material.button.MaterialButton restore;

            Holder(@NonNull View itemView) {
                super(itemView);
                date = itemView.findViewById(R.id.textRestorePointDate);
                detail = itemView.findViewById(R.id.textRestorePointDetail);
                pin = itemView.findViewById(R.id.btnRestorePointPin);
                restore = itemView.findViewById(R.id.btnRestorePointRestore);
            }
        }
    }

    private void togglePin(Snapshot.Summary point) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) return;
        final boolean pinned = !point.pinned;

        AppDatabase.IO.execute(() -> {
            try {
                new CloudSync(this, account).setPinned(point.id, pinned);
                runOnUiThread(() -> {
                    point.pinned = pinned;
                    restorePointAdapter.notifyDataSetChanged();
                    Toast.makeText(this, pinned
                                    ? "This restore point will be kept"
                                    : "This restore point can now be cleaned up",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        SyncChoiceActivity.readable(e), Toast.LENGTH_LONG).show());
            }
        });
    }


    private void showRestoreFlowDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Restore")
                .setItems(new String[]{"From a backup file", "From Google Drive", "Check a backup file"},
                        (dialog, which) -> {
                            if (which == 0) pickLocalBackupForRestore();
                            else if (which == 1) restoreFromDrive();
                            else pickBackupToCheck();
                        })
                .show();
    }

    private void pickBackupToCheck() {
        expectExternalActivity();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, PICK_BACKUP_TO_CHECK);
    }

    /**
     * Reads a .bak end to end, authenticating every byte, without restoring anything: the way
     * to be sure a backup is good before relying on it.
     */
    private void checkBackup(Uri backup, String password) {
        askForNotifications();
        UUID job = SyncWorker.startBackupCheck(this, backup, password);
        SyncWorker.watch(this, job, "Checking backup", info -> {
            String message = info.getOutputData().getString(SyncWorker.RESULT_MESSAGE);
            if (info.getState() == WorkInfo.State.SUCCEEDED) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("This backup is good")
                        .setMessage(message + ". Every byte was decrypted and checked; it will restore.")
                        .setPositiveButton("OK", null)
                        .show();
            } else if (info.getOutputData().getBoolean(SyncWorker.RESULT_WRONG_PASSWORD, false)) {
                UnlockActivity.askForBackupKey(this, p -> checkBackup(backup, p), null);
            } else if (info.getOutputData().getBoolean(SyncWorker.RESULT_DAMAGED, false)) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("This backup has a problem")
                        .setMessage((message == null ? "It could not be read." : message)
                                + "\n\nDon't rely on it. Make a new backup.")
                        .setPositiveButton("OK", null)
                        .show();
            } else if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void pickLocalBackupForRestore() {
        expectExternalActivity();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_BACKUP);
    }

    /**
     * Lists restore points and lets the user pick one.
     *
     * The old version downloaded settings.json into "drive_restore.bak" and then never read
     * it, calling showRestoreOptionsDialog(null) instead. The download was pure latency.
     */
    private void restoreFromDrive() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            Toast.makeText(this, "Please connect Google Drive first", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressBar pb = new ProgressBar(this);
        pb.setPadding(40, 40, 40, 40);
        AlertDialog loading = new MaterialAlertDialogBuilder(this)
                .setTitle("Google Drive")
                .setMessage("Looking for your backups...")
                .setView(pb)
                .setCancelable(false)
                .show();

        AppDatabase.IO.execute(() -> {
            try {
                CloudSync sync = new CloudSync(this, account);
                List<Snapshot.Summary> points = sync.listRestorePoints();
                runOnUiThread(() -> {
                    loading.dismiss();
                    if (points.isEmpty()) {
                        Toast.makeText(this, "No backups found on Drive yet",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    showRestorePointPicker(sync, points);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    Toast.makeText(this, SyncChoiceActivity.readable(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Restore points are cheap to keep, so the user picks which one to go back to. */
    private void showRestorePointPicker(CloudSync sync, List<Snapshot.Summary> points) {
        String[] labels = new String[points.size()];
        for (int i = 0; i < points.size(); i++) {
            Snapshot.Summary point = points.get(i);
            labels[i] = point.displayName() + "\n" + point.itemCount + " items"
                    + (point.device == null ? "" : " \u00b7 " + point.device)
                    + (point.pinned ? "  (kept)" : "");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Choose a restore point")
                .setItems(labels, (d, which) -> confirmDriveRestore(sync, points.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** One question, instead of the old settings-then-media two-dialog chain. */
    private void confirmDriveRestore(CloudSync sync, Snapshot.Summary point) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(point.displayName())
                .setMessage("Restore " + point.itemCount + " items from this point?\n\n"
                        + "Only items missing from this phone are downloaded.")
                .setPositiveButton("Add to what is here",
                        (d, w) -> runDriveRestore(sync, point.id, false))
                .setNeutralButton("Replace everything",
                        (d, w) -> runDriveRestore(sync, point.id, true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runDriveRestore(CloudSync sync, String snapshotId, boolean replaceLocal) {
        runDriveRestore(sync, snapshotId, replaceLocal, null);
    }

    private void runDriveRestore(CloudSync sync, String snapshotId, boolean replaceLocal,
                                 String password) {
        askForNotifications();
        UUID job = SyncWorker.startDriveRestore(this, snapshotId, password, replaceLocal);
        SyncWorker.watch(this, job, "Restoring from Google Drive", info -> finishRestore(info, replaceLocal,
                p -> runDriveRestore(sync, snapshotId, replaceLocal, p)));
    }

    /** Shared ending for both restores: retry on a wrong password, restart if settings changed. */
    private void finishRestore(WorkInfo info, boolean replaceLocal, java.util.function.Consumer<String> retry) {
        Data out = info.getOutputData();
        if (out.getBoolean(SyncWorker.RESULT_WRONG_PASSWORD, false)) {
            UnlockActivity.askForBackupKey(this, retry, null);
            return;
        }
        String message = out.getString(SyncWorker.RESULT_MESSAGE);
        Toast.makeText(this, message != null ? message : "Restore finished", Toast.LENGTH_LONG).show();
        if (info.getState() == WorkInfo.State.SUCCEEDED && replaceLocal) restartApp();
    }

    private void confirmLocalRestore(Uri backup) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Restore this backup?")
                .setMessage("Items already on this phone are kept. Only what's missing is added.")
                .setPositiveButton("Add to what is here", (d, w) -> runLocalRestore(backup, false, null))
                .setNeutralButton("Also restore vault settings", (d, w) -> runLocalRestore(backup, true, null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runLocalRestore(Uri backup, boolean replaceLocal, String password) {
        askForNotifications();
        UUID job = SyncWorker.startLocalRestore(this, backup, password, replaceLocal);
        SyncWorker.watch(this, job, "Restoring backup", info -> finishRestore(info, replaceLocal,
                p -> runLocalRestore(backup, replaceLocal, p)));
    }

    /**
     * The job may outlive this screen, or even this process, so the grant to the picked file
     * is made persistent rather than left tied to the activity.
     */
    private void keepAccess(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            // Not persistable from this provider; the job still works while we are alive.
        }
    }

    private void restartApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            // resultCode is checked because a cancelled sign-in delivers a null-ish intent
            // that getSignedInAccountFromIntent would otherwise turn into a bogus failure.
            if (resultCode != RESULT_OK || data == null) return;
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                updateDriveUI(account);
                // Ask the one setup question, then start the first backup.
                startActivity(DriveSetupActivity.intent(this));
            } catch (ApiException e) {
                Toast.makeText(this, "Sign in failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == PICK_BACKUP_TO_CHECK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            checkBackup(data.getData(), null);
        } else if (requestCode == PICK_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            keepAccess(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            confirmLocalRestore(data.getData());
        } else if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (requestCode == PICK_VAULT_IMAGE || requestCode == PICK_VAULT_VIDEO) {
                saveBgMedia(uri, true, requestCode == PICK_VAULT_VIDEO);
            } else if (requestCode == PICK_LOCK_IMAGE || requestCode == PICK_LOCK_VIDEO) {
                saveBgMedia(uri, false, requestCode == PICK_LOCK_VIDEO);
            } else if (requestCode == CREATE_FILE_BACKUP && uri != null) {
                keepAccess(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startBackup(uri.toString(), null);
            }
        }
    }

    private void saveBgMedia(Uri uri, boolean forVault, boolean isVideo) {
        AppDatabase.IO.execute(() -> {
            try {
                File dir = forVault ? new File(getFilesDir(), "bg_vault_" + vaultId) : new File(getFilesDir(), "bg_lock");
                if (!dir.exists()) dir.mkdirs();
                
                String ext = isVideo ? ".mp4" : ".jpg";
                String fileName = "bg_" + System.currentTimeMillis() + ext;
                File file = new File(dir, fileName);
                
                File[] oldFiles = dir.listFiles();
                if (oldFiles != null) {
                    for (File f : oldFiles) f.delete();
                }

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[16384];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                }

                runOnUiThread(() -> {
                    String type = isVideo ? "VIDEO" : "IMAGE";
                    if (forVault) updateVaultBg(type, file.getAbsolutePath());
                    else updateLockBg(type, file.getAbsolutePath());
                });
            } catch (IOException e) { e.printStackTrace(); }
        });
    }

    private void checkVaultAccess() {
        AppDatabase.IO.execute(() -> {
            currentVault = database.vaultDao().getVaultById(vaultId);
            isMainVaultSettings = (currentVault != null && currentVault.isMain());
            runOnUiThread(() -> {
                // Vaults and backups span every vault, so only the main vault manages them.
                int adminOnly = isMainVaultSettings ? View.VISIBLE : View.GONE;
                findViewById(R.id.sectionVaults).setVisibility(adminOnly);
                findViewById(R.id.headerLocalBackup).setVisibility(adminOnly);
                findViewById(R.id.cardLocalBackup).setVisibility(adminOnly);
                findViewById(R.id.footerLocalBackup).setVisibility(adminOnly);
                TextView optionTitle = findViewById(R.id.optionChangeLockTitle);
                optionTitle.setText(isMainVaultSettings ? "Change main vault lock" : "Change lock");
                if (isMainVaultSettings) loadVaults();
            });
        });
    }

    private void loadVaults() {
        database.vaultDao().getAllVaults().observe(this, entities -> {
            if (isMainVaultSettings) {
                vaults.clear();
                vaults.addAll(entities);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void showCreateVaultDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_vault, null);
        EditText nameInput = dialogView.findViewById(R.id.editVaultName);
        EditText pinInput = dialogView.findViewById(R.id.editVaultPin);
        EditText passInput = dialogView.findViewById(R.id.editVaultPassword);
        PatternView patternInput = dialogView.findViewById(R.id.patternVaultSetup);
        TabLayout tabs = dialogView.findViewById(R.id.tabLayoutLockType);
        
        final String[] currentLockType = {"PIN"};
        final String[] currentLockValue = {""};

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                pinInput.setVisibility(View.GONE);
                passInput.setVisibility(View.GONE);
                patternInput.setVisibility(View.GONE);
                if (tab.getPosition() == 0) {
                    currentLockType[0] = "PIN";
                    pinInput.setVisibility(View.VISIBLE);
                } else if (tab.getPosition() == 1) {
                    currentLockType[0] = "PASSWORD";
                    passInput.setVisibility(View.VISIBLE);
                } else {
                    currentLockType[0] = "PATTERN";
                    patternInput.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        patternInput.setOnPatternListener(pattern -> currentLockValue[0] = pattern);

        new MaterialAlertDialogBuilder(this)
                .setTitle("New Secret Vault")
                .setView(dialogView)
                .setPositiveButton("Create", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "New Vault";
                    
                    String value = currentLockValue[0];
                    if (currentLockType[0].equals("PIN")) value = pinInput.getText().toString();
                    else if (currentLockType[0].equals("PASSWORD")) value = passInput.getText().toString();

                    String problem = lockProblem(currentLockType[0], value);
                    if (problem != null) {
                        Toast.makeText(this, problem, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String finalName = name;
                    String finalValue = value;
                    AppDatabase.IO.execute(() -> {
                        if (lockInUse(currentLockType[0], finalValue, -1)) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    "Another vault already uses that lock", Toast.LENGTH_LONG).show());
                            return;
                        }
                        VaultEntity newVault = new VaultEntity(finalName, currentLockType[0], finalValue, false, vaults.size());
                        database.vaultDao().insert(newVault);
                        runOnUiThread(this::checkAndUpdateDriveSettings);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditVaultDialog(VaultEntity vault) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_vault, null);
        EditText nameInput = dialogView.findViewById(R.id.editVaultName);
        EditText pinInput = dialogView.findViewById(R.id.editVaultPin);
        EditText passInput = dialogView.findViewById(R.id.editVaultPassword);
        PatternView patternInput = dialogView.findViewById(R.id.patternVaultSetup);
        TabLayout tabs = dialogView.findViewById(R.id.tabLayoutLockType);
        
        nameInput.setText(vault.getName());
        final String[] currentLockType = {vault.getLockType()};
        // Left blank, not prefilled: the current lock is never shown on screen. Blank = keep.
        final String[] currentLockValue = {""};

        pinInput.setVisibility(View.GONE);
        passInput.setVisibility(View.GONE);
        patternInput.setVisibility(View.GONE);

        if ("PIN".equals(vault.getLockType())) {
            tabs.getTabAt(0).select();
            pinInput.setVisibility(View.VISIBLE);
            pinInput.setHint("New PIN (leave blank to keep)");
        } else if ("PASSWORD".equals(vault.getLockType())) {
            tabs.getTabAt(1).select();
            passInput.setVisibility(View.VISIBLE);
            passInput.setHint("New password (leave blank to keep)");
        } else {
            tabs.getTabAt(2).select();
            patternInput.setVisibility(View.VISIBLE);
        }

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                pinInput.setVisibility(View.GONE);
                passInput.setVisibility(View.GONE);
                patternInput.setVisibility(View.GONE);
                if (tab.getPosition() == 0) {
                    currentLockType[0] = "PIN";
                    pinInput.setVisibility(View.VISIBLE);
                } else if (tab.getPosition() == 1) {
                    currentLockType[0] = "PASSWORD";
                    passInput.setVisibility(View.VISIBLE);
                } else {
                    currentLockType[0] = "PATTERN";
                    patternInput.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        patternInput.setOnPatternListener(pattern -> currentLockValue[0] = pattern);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit Vault Settings")
                .setView(dialogView)
                .setPositiveButton("Save", (d, w) -> {
                    String value = currentLockValue[0];
                    if (currentLockType[0].equals("PIN")) value = pinInput.getText().toString();
                    else if (currentLockType[0].equals("PASSWORD")) value = passInput.getText().toString();

                    boolean typeChanged = !currentLockType[0].equals(vault.getLockType());
                    if (value.isEmpty() && !typeChanged) value = vault.getLockValue();
                    String problem = lockProblem(currentLockType[0], value);
                    if (problem != null) {
                        Toast.makeText(this, problem, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean lockChanged = typeChanged || !value.equals(vault.getLockValue());

                    // Old-format backups were encrypted with the lock value itself, so saving
                    // a new lock while any remain would orphan them permanently. Offer the
                    // one-time migration instead of silently destroying them.
                    if (lockChanged && VaultCrypto.hasUnmigratedLegacyPayloads(this)) {
                        promptBackupMigration();
                        return;
                    }

                    String newName = nameInput.getText().toString().trim();
                    String newType = currentLockType[0];
                    String newValue = value;

                    AppDatabase.IO.execute(() -> {
                        if (lockChanged && lockInUse(newType, newValue, vault.getId())) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    "Another vault already uses that lock", Toast.LENGTH_LONG).show());
                            return;
                        }
                        vault.setName(newName.isEmpty() ? vault.getName() : newName);
                        vault.setLockType(newType);
                        vault.setLockValue(newValue);
                        database.vaultDao().update(vault);
                        runOnUiThread(() -> {
                            // A lock change now costs one small keyring rewrite. The media
                            // itself is encrypted with the vault data key and never moves.
                            if (lockChanged) SyncWorker.requestKeyringRepublish(this);
                            checkAndUpdateDriveSettings();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Explains why the lock change was refused and offers the ways out.
     *
     * Deliberately not a silent block: the user asked to do something reasonable, and the
     * reason it is unsafe right now is fixable in a couple of taps.
     */
    private void promptBackupMigration() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Update your backups first")
                .setMessage("Your older backups were locked with your current PIN. Changing it "
                        + "now would make them impossible to open. This only needs doing once.")
                .setPositiveButton("Show me how",
                        (d, w) -> startActivity(BackupMigrationActivity.intent(this)))
                .setNegativeButton("Not now", null)
                .setCancelable(false)
                .show();
    }

    private void deleteVault(VaultEntity vault) {
        if (vault.isMain()) {
            Toast.makeText(this, "Cannot delete Main Vault", Toast.LENGTH_SHORT).show();
            return;
        }

        AppDatabase.IO.execute(() -> {
            int count = database.vaultDao().getSecretCountForVault(vault.getId());
            runOnUiThread(() -> {
                if (count > 0) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Delete " + vault.getName() + "?")
                            .setMessage("There are " + count + " items still inside. What would you like to do?")
                            .setNeutralButton("Cancel", null)
                            .setNegativeButton("Delete Everything", (d, w) -> {
                                AppDatabase.IO.execute(() -> {
                                    for (SecretEntity s : database.secretDao().getSecretsForVaultDirect(vault.getId())) {
                                        VaultMedia.remove(this, s);
                                    }
                                    deleteVaultResources(vault);
                                    database.vaultDao().delete(vault);
                                });
                            })
                            .setPositiveButton("Restore then Delete", (d, w) -> {
                                AppDatabase.IO.execute(() -> {
                                    int failed = 0;
                                    for (SecretEntity s : database.secretDao().getSecretsForVaultDirect(vault.getId())) {
                                        try {
                                            VaultMedia.restoreToDevice(this, s);
                                        } catch (Exception e) {
                                            failed++;
                                        }
                                    }
                                    // Items that could not be restored would be stranded with no
                                    // vault to open them from, so the vault stays until they are out.
                                    final int stuck = failed;
                                    if (stuck == 0) {
                                        deleteVaultResources(vault);
                                        database.vaultDao().delete(vault);
                                    }
                                    runOnUiThread(() -> Toast.makeText(this, stuck == 0
                                            ? "Vault deleted"
                                            : stuck + " item(s) couldn't be restored, so the vault was kept",
                                            Toast.LENGTH_LONG).show());
                                });
                            })
                            .show();
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Delete Vault?")
                            .setMessage("Are you sure you want to delete this empty vault?")
                            .setPositiveButton("Delete", (d, w) -> {
                                AppDatabase.IO.execute(() -> {
                                    deleteVaultResources(vault);
                                    database.vaultDao().delete(vault);
                                });
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
        });
    }

    private void deleteVaultResources(VaultEntity vault) {
        File dir = new File(getFilesDir(), "bg_vault_" + vault.getId());
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            dir.delete();
        }
        database.albumDao().deleteAlbumsForVault(vault.getId());
    }

    /** Null when the lock is acceptable, otherwise what is wrong with it. */
    private static String lockProblem(String type, String value) {
        if (value == null || value.isEmpty()) return "Lock cannot be empty";
        if ("PIN".equals(type) && value.length() < 4) return "PIN must be at least 4 digits";
        if ("PATTERN".equals(type) && value.length() < 4) return "Pattern must connect at least 4 dots";
        return null;
    }

    /**
     * Unlock opens the first vault whose lock matches, so two vaults sharing a lock would make
     * one of them, and everything in it, unreachable. Runs on a background thread.
     */
    private boolean lockInUse(String type, String value, int exceptVaultId) {
        for (VaultEntity v : database.vaultDao().getAllVaultsDirect()) {
            if (v.getId() != exceptVaultId && type.equals(v.getLockType()) && value.equals(v.getLockValue())) {
                return true;
            }
        }
        return false;
    }

    private class VaultSettingsAdapter extends RecyclerView.Adapter<VaultSettingsAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vault_setting, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VaultEntity v = vaults.get(position);
            holder.name.setText(v.isMain() ? v.getName() + " (Main)" : v.getName());
            holder.type.setText("PIN".equals(v.getLockType()) ? "Opens with a PIN"
                    : "PATTERN".equals(v.getLockType()) ? "Opens with a pattern" : "Opens with a password");
            holder.itemView.setOnClickListener(view -> showEditVaultDialog(v));
            holder.btnDelete.setVisibility(v.isMain() ? View.GONE : View.VISIBLE);
            holder.btnDelete.setOnClickListener(view -> deleteVault(v));
        }

        @Override
        public int getItemCount() { return vaults.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, type;
            ImageButton btnDelete;
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.textVaultName);
                type = itemView.findViewById(R.id.textVaultType);
                btnDelete = itemView.findViewById(R.id.btnDeleteVault);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}