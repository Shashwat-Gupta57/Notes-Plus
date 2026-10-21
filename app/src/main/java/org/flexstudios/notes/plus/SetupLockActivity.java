package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.tabs.TabLayout;

import java.io.InputStream;
import java.util.UUID;

public class SetupLockActivity extends AppCompatActivity {
    private View layoutPin, layoutPassword, layoutPattern;
    private EditText editTextPin, editTextPassword;
    private PatternView patternView;
    private String currentPattern = "";
    private int currentTab = 0; // 0: PIN, 1: Password, 2: Pattern

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_lock);

        layoutPin = findViewById(R.id.layoutPin);
        layoutPassword = findViewById(R.id.layoutPassword);
        layoutPattern = findViewById(R.id.layoutPattern);
        editTextPin = findViewById(R.id.editTextPin);
        editTextPassword = findViewById(R.id.editTextPassword);
        patternView = findViewById(R.id.patternView);
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        Button buttonContinue = findViewById(R.id.buttonContinue);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                layoutPin.setVisibility(currentTab == 0 ? View.VISIBLE : View.GONE);
                layoutPassword.setVisibility(currentTab == 1 ? View.VISIBLE : View.GONE);
                layoutPattern.setVisibility(currentTab == 2 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        patternView.setOnPatternListener(pattern -> currentPattern = pattern);

        buttonContinue.setOnClickListener(v -> saveLockSetup());
    }

    private void saveLockSetup() {
        String lockType = "";
        String lockValue = "";

        if (currentTab == 0) {
            lockValue = editTextPin.getText().toString();
            if (lockValue.length() < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            lockType = "PIN";
        } else if (currentTab == 1) {
            lockValue = editTextPassword.getText().toString();
            if (lockValue.isEmpty()) {
                Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            lockType = "PASSWORD";
        } else if (currentTab == 2) {
            lockValue = currentPattern;
            if (lockValue.length() < 4) {
                Toast.makeText(this, "Pattern must connect at least 4 dots", Toast.LENGTH_SHORT).show();
                return;
            }
            lockType = "PATTERN";
        }

        SharedPreferences encryptedPrefs = SecurityHelper.getEncryptedPrefs(this);
        encryptedPrefs.edit()
                .putString(SecurityHelper.KEY_LOCK_TYPE, lockType)
                .putString(SecurityHelper.KEY_LOCK_VALUE, lockValue)
                .apply();

        String finalValue = lockValue;
        // Create the main vault now rather than leaving it to the database's open callback,
        // which runs asynchronously and could lose a race with the unlock screen or a restore.
        AppDatabase.IO.execute(() -> {
            AppDatabase.ensureMainVault(this);
            runOnUiThread(() -> {
                SecurityHelper.setSetupDone(this, true);
                showRestoreChoiceDialog(finalValue);
            });
        });
    }

    private void showRestoreChoiceDialog(String password) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Setup Complete")
                .setMessage("Would you like to restore data from a previous backup?")
                .setPositiveButton("Restore Data", (d, w) -> {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Restore Source")
                            .setItems(new String[]{"Local .bak File", "Google Drive"}, (dialog, which) -> {
                                if (which == 0) pickLocalBackup(password);
                                else restoreFromDrive(password);
                            })
                            .setNegativeButton("Cancel", (dialog, which) -> {
                                startActivity(new Intent(this, MainActivity.class));
                                finish();
                            })
                            .show();
                })
                .setNegativeButton("Skip", (d, w) -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private static final int PICK_BACKUP = 2001;

    private void pickLocalBackup(String password) {
        pendingPassword = password;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_BACKUP);
    }

    private static final int RC_SIGN_IN = 2002;
    private String pendingPassword;

    private void restoreFromDrive(String password) {
        pendingPassword = password;
        if (GoogleSignIn.getLastSignedInAccount(this) == null) {
            startActivityForResult(DriveStore.signInClient(this).getSignInIntent(), RC_SIGN_IN);
        } else {
            fetchBackupFromDrive(GoogleSignIn.getLastSignedInAccount(this), password);
        }
    }

    /**
     * Lists the restore points on Drive and applies the one the user picks.
     *
     * This path used to download settings.json, which is plain JSON, and hand it to
     * BackupManager.restoreBackup, which expects a salt+IV+AES-GCM archive. It therefore
     * failed with "Invalid backup file" every single time it was used.
     */
    private void fetchBackupFromDrive(GoogleSignInAccount account, String password) {
        AlertDialog loading = new MaterialAlertDialogBuilder(this)
                .setTitle("Connecting to Drive")
                .setMessage("Looking for your backups...")
                .setCancelable(false)
                .show();

        AppDatabase.IO.execute(() -> {
            try {
                CloudSync sync = new CloudSync(this, account);
                java.util.List<Snapshot.Summary> points = sync.listRestorePoints();
                runOnUiThread(() -> {
                    loading.dismiss();
                    if (points.isEmpty()) {
                        Toast.makeText(this, "No backups found on this Drive account",
                                Toast.LENGTH_LONG).show();
                        goToMain();
                        return;
                    }
                    showRestorePointPicker(sync, points, password);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    Toast.makeText(this, SyncChoiceActivity.readable(e), Toast.LENGTH_LONG).show();
                    goToMain();
                });
            }
        });
    }

    private void showRestorePointPicker(CloudSync sync, java.util.List<Snapshot.Summary> points,
                                        String password) {
        String[] labels = new String[points.size()];
        for (int i = 0; i < points.size(); i++) {
            Snapshot.Summary point = points.get(i);
            labels[i] = point.displayName() + "\n" + point.itemCount + " items"
                    + (point.device == null ? "" : " · " + point.device);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Choose a backup")
                .setItems(labels, (d, which) -> restoreFromPoint(sync, points.get(which).id, password))
                .setNegativeButton("Skip", (d, w) -> goToMain())
                .setCancelable(false)
                .show();
    }

    private void restoreFromPoint(CloudSync sync, String snapshotId, String password) {
        UUID job = SyncWorker.startDriveRestore(this, snapshotId, password, true);
        SyncWorker.watch(this, job, "Restoring from Google Drive", info -> finishRestore(info,
                p -> restoreFromPoint(sync, snapshotId, p)));
    }

    /** A wrong password asks again; anything else ends setup with an explanation. */
    private void finishRestore(androidx.work.WorkInfo info, java.util.function.Consumer<String> retry) {
        androidx.work.Data out = info.getOutputData();
        if (out.getBoolean(SyncWorker.RESULT_WRONG_PASSWORD, false)) {
            UnlockActivity.askForBackupKey(this, retry, this::goToMain);
        } else if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
            restoredNotice();
        } else {
            String message = out.getString(SyncWorker.RESULT_MESSAGE);
            Toast.makeText(this, message != null ? message : "Restore failed", Toast.LENGTH_LONG).show();
            goToMain();
        }
    }

    /** The restore brought the backup's vaults, with their locks; say so before it surprises. */
    private void restoredNotice() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Your vaults are back")
                .setMessage("Each vault now opens with the PIN, password or pattern it had when the "
                        + "backup was made. The lock you just chose was replaced by your old one.")
                .setPositiveButton("Got it", (d, w) -> goToMain())
                .setCancelable(false)
                .show();
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == PICK_BACKUP || requestCode == RC_SIGN_IN) goToMain();
            return;
        }
        if (requestCode == PICK_BACKUP && data.getData() != null) {
            restoreLocal(data.getData(), pendingPassword);
        } else if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                fetchBackupFromDrive(task.getResult(ApiException.class), pendingPassword);
            } catch (ApiException e) {
                Toast.makeText(this, "Sign in failed", Toast.LENGTH_SHORT).show();
                goToMain();
            }
        }
    }

    /** Streams the picked .bak straight from the provider as a background job. */
    private void restoreLocal(Uri backup, String password) {
        try {
            getContentResolver().takePersistableUriPermission(backup, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not persistable; fine while the app stays alive.
        }
        UUID job = SyncWorker.startLocalRestore(this, backup, password, true);
        SyncWorker.watch(this, job, "Restoring backup", info -> finishRestore(info,
                p -> restoreLocal(backup, p)));
    }
}
