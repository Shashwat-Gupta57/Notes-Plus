package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/**
 * One-time migration off the old backup format, offered when the user tries to change a lock.
 *
 * In the old format the lock value was itself the encryption key for every backed-up file, so
 * changing a PIN made thousands of files unreadable with no warning and no way back. Rather
 * than silently blocking the lock change, the user is offered three ways out; all of them end
 * with no old-format payload reachable, after which a lock change only rewrites a small
 * keyring and is free forever.
 */
public class BackupMigrationActivity extends VaultActivity {

    private static final int PICK_BACKUP = 4101;
    private static final int SAVE_BACKUP = 4102;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView textRekeyDriveDetail;
    private File pendingInput;
    private String pendingOldLock;

    public static Intent intent(Context context) {
        return new Intent(context, BackupMigrationActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup_migration);

        Toolbar toolbar = findViewById(R.id.toolbarMigration);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        textRekeyDriveDetail = findViewById(R.id.textRekeyDriveDetail);

        findViewById(R.id.cardRekeyDrive).setOnClickListener(v -> rekeyDrive());
        findViewById(R.id.cardRekeyFile).setOnClickListener(v -> pickBackupFile());
        findViewById(R.id.cardFreshBackup).setOnClickListener(v -> confirmFreshBackup());

        countDriveWork();
    }

    // --------------------------------------------------------- route 1: Drive

    private void countDriveWork() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            textRekeyDriveDetail.setText("Google Drive isn't connected.");
            return;
        }
        executor.execute(() -> {
            try {
                List<String> candidates = new CloudSync(this, account).legacyCandidates();
                runOnUiThread(() -> textRekeyDriveDetail.setText(candidates.isEmpty()
                        ? "Nothing to update. Tap to confirm."
                        : candidates.size() + " items need updating."));
            } catch (Exception e) {
                runOnUiThread(() -> textRekeyDriveDetail.setText(SyncChoiceActivity.readable(e)));
            }
        });
    }

    private void rekeyDrive() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            Toast.makeText(this, "Connect Google Drive first", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = progress("Updating your Drive backup");
        executor.execute(() -> {
            try {
                // The lock has not changed yet, which is exactly why it still opens the old
                // payloads: that is what this screen is protecting.
                VaultEntity main = AppDatabase.getInstance(this).vaultDao().getMainVault();
                String currentLock = main == null ? "" : main.getLockValue();

                new CloudSync(this, account).rekeyLegacyObjects(currentLock, stageOnly(dialog));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    done();
                });
            } catch (Exception e) {
                fail(dialog, e);
            } finally {
                CloudSync.clearStaging(this);
            }
        });
    }

    // ---------------------------------------------------- route 2: chosen file

    private void pickBackupFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        expectExternalActivity();
        startActivityForResult(intent, PICK_BACKUP);
    }

    /** The chosen file was written with whatever lock was current at the time. */
    private void askOldLock(File input) {
        Prompts.text(this, "Unlock that backup",
                "Enter the PIN, password or pattern that was set when this backup was made.",
                "Lock used for that backup", null, Prompts.PASSWORD, "Continue", lock -> {
                    pendingInput = input;
                    pendingOldLock = lock;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE,
                            "NotesPlus_Backup_" + System.currentTimeMillis() + ".bak");
                    expectExternalActivity();
                    startActivityForResult(intent, SAVE_BACKUP);
                }, null);
    }

    private void rewriteBackupFile(Uri output) {
        AlertDialog dialog = progress("Updating backup file");
        executor.execute(() -> {
            try {
                // Old lock in, current format out, streamed: nothing decrypted touches disk.
                try (InputStream in = new FileInputStream(pendingInput);
                     OutputStream out = getContentResolver().openOutputStream(output)) {
                    if (out == null) throw new java.io.IOException("Could not write there");
                    BackupManager.convertLegacy(this, in, pendingOldLock, out);
                }

                runOnUiThread(() -> {
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Backup updated")
                            .setMessage("The new copy opens with your current lock, and will "
                                    + "keep working if you change it. You can delete the old "
                                    + "file now.")
                            .setPositiveButton("Done", (d, w) -> done())
                            .setCancelable(false)
                            .show();
                });
            } catch (Exception e) {
                fail(dialog, e);
            } finally {
                if (pendingInput != null && pendingInput.exists() && !pendingInput.delete()) {
                    pendingInput.deleteOnExit();
                }
                pendingOldLock = null;
            }
        });
    }

    // ------------------------------------------------------ route 3: start over

    private void confirmFreshBackup() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Start a fresh backup?")
                .setMessage("Your current Google Drive backup will be removed and uploaded "
                        + "again from scratch. Nothing on this phone is deleted.")
                .setPositiveButton("Start fresh", (d, w) -> freshBackup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void freshBackup() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            Toast.makeText(this, "Connect Google Drive first", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = progress("Starting a fresh backup");
        executor.execute(() -> {
            try {
                CloudSync sync = new CloudSync(this, account);
                sync.discardStore(stageOnly(dialog));
                sync.backup(sync.plan(), CloudSync.Resolution.UPLOAD, stageOnly(dialog));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    done();
                });
            } catch (Exception e) {
                fail(dialog, e);
            } finally {
                CloudSync.clearStaging(this);
            }
        });
    }

    // ------------------------------------------------------------------ shared

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        if (requestCode == PICK_BACKUP) {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                File copy = new File(getCacheDir(), "migrate_input.bak");
                try (FileOutputStream out = new FileOutputStream(copy)) {
                    byte[] buffer = new byte[65536];
                    int len;
                    if (in != null) while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                }
                askOldLock(copy);
            } catch (Exception e) {
                Toast.makeText(this, "Could not read that file", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == SAVE_BACKUP) {
            rewriteBackupFile(data.getData());
        }
    }

    private void done() {
        VaultCrypto.markLegacyPayloadsCleared(this, true);
        Toast.makeText(this, "Backups updated. You can change your lock now.",
                Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    private void fail(AlertDialog dialog, Exception e) {
        runOnUiThread(() -> {
            dialog.dismiss();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Couldn't finish")
                    .setMessage(SyncChoiceActivity.readable(e))
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    private AlertDialog progress(String title) {
        return Prompts.progress(this, title);
    }

    private CloudSync.Progress stageOnly(AlertDialog dialog) {
        return new CloudSync.Progress() {
            @Override
            public void onStage(String stage) {
                runOnUiThread(() -> dialog.setMessage(stage));
            }

            @Override
            public void onItem(int done, int total, long bytesDone, long bytesTotal) {
                runOnUiThread(() -> dialog.setMessage(done + " of " + total));
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
