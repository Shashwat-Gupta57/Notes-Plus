package org.flexstudios.notes.plus;

import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.Executors;

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

        SecurityHelper.setSetupDone(this, true);
        showRestoreChoiceDialog(lockValue);
    }

    private void showRestoreChoiceDialog(String password) {
        new AlertDialog.Builder(this)
                .setTitle("Setup Complete")
                .setMessage("Would you like to restore data from a previous backup?")
                .setPositiveButton("Restore Data", (d, w) -> {
                    new AlertDialog.Builder(this)
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

    private void pickLocalBackup(String password) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 2001); // RC_PICK_BACKUP
    }

    private static final int RC_SIGN_IN = 2002;
    private String pendingPassword;

    private void restoreFromDrive(String password) {
        pendingPassword = password;
        if (GoogleSignIn.getLastSignedInAccount(this) == null) {
            startActivityForResult(GoogleDriveManager.getSignInClient(this).getSignInIntent(), RC_SIGN_IN);
        } else {
            fetchBackupFromDrive(GoogleSignIn.getLastSignedInAccount(this), password);
        }
    }

    private void fetchBackupFromDrive(GoogleSignInAccount account, String password) {
        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle("Connecting to Drive")
                .setMessage("Fetching settings...")
                .setCancelable(false)
                .show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File tempFile = new File(getCacheDir(), "drive_restore_setup.bak");
                new GoogleDriveManager(this, account).downloadSettingsDirect(tempFile);
                runOnUiThread(() -> {
                    loading.dismiss();
                    performRestore(tempFile, password);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    Toast.makeText(this, "Failed to fetch backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == 2001 && data.getData() != null) { // Local backup
                try (InputStream is = getContentResolver().openInputStream(data.getData())) {
                    File tempFile = new File(getCacheDir(), "local_restore_setup.bak");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        if (is != null) {
                            while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                        }
                    }
                    SharedPreferences prefs = SecurityHelper.getEncryptedPrefs(this);
                    performRestore(tempFile, prefs.getString(SecurityHelper.KEY_LOCK_VALUE, ""));
                } catch (Exception e) {
                    Toast.makeText(this, "Restore failed", Toast.LENGTH_SHORT).show();
                }
            } else if (requestCode == RC_SIGN_IN) {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    fetchBackupFromDrive(account, pendingPassword);
                } catch (ApiException e) {
                    Toast.makeText(this, "Sign in failed", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void performRestore(File backupFile, String password) {
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Restoring Data")
                .setMessage("Starting...")
                .setCancelable(false)
                .show();

        try {
            InputStream is = new FileInputStream(backupFile);
            new BackupManager(this, new BackupManager.BackupListener() {
                @Override
                public void onProgress(int progress, String message) {
                    runOnUiThread(() -> progressDialog.setMessage(message));
                }

                @Override
                public void onComplete(String msg) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(SetupLockActivity.this, "Restored Successfully", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(SetupLockActivity.this, MainActivity.class));
                        finish();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(SetupLockActivity.this, error, Toast.LENGTH_LONG).show();
                        startActivity(new Intent(SetupLockActivity.this, MainActivity.class));
                        finish();
                    });
                }
            }).restoreBackup(is, password, true, true);
        } catch (Exception e) {
            progressDialog.dismiss();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }
}