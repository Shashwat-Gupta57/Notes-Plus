package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asks how a device and an existing Drive backup should be reconciled the first time they
 * meet.
 *
 * The old code answered this question silently and always in favour of the device: anything
 * on Drive that was not on the phone got deleted. On a fresh install that meant the first
 * sync destroyed the entire backup. The question is now put to the user, with the counts in
 * front of them, and the only destructive option states exactly what it will remove and asks
 * again before doing it.
 */
public class SyncChoiceActivity extends VaultActivity {


    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView textLocalCount, textCloudCount, textDownloadDetail, textUploadDetail;
    private RadioGroup radioGroup;
    private MaterialButton btnContinue;

    private CloudSync sync;
    private CloudSync.Plan plan;

    public static Intent intent(Context context, int vaultId) {
        Intent intent = new Intent(context, SyncChoiceActivity.class);
        intent.putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync_choice);


        Toolbar toolbar = findViewById(R.id.toolbarSyncChoice);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        textLocalCount = findViewById(R.id.textLocalCount);
        textCloudCount = findViewById(R.id.textCloudCount);
        textDownloadDetail = findViewById(R.id.textDownloadDetail);
        textUploadDetail = findViewById(R.id.textUploadDetail);
        radioGroup = findViewById(R.id.radioGroupResolution);
        btnContinue = findViewById(R.id.btnSyncChoiceContinue);

        findViewById(R.id.btnSyncChoiceCancel).setOnClickListener(v -> finish());
        btnContinue.setEnabled(false);
        btnContinue.setOnClickListener(v -> onContinue());

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            Toast.makeText(this, "Connect Google Drive first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        sync = new CloudSync(this, account);
        loadPlan();
    }

    private void loadPlan() {
        executor.execute(() -> {
            try {
                final CloudSync.Plan loaded = sync.plan();
                runOnUiThread(() -> {
                    plan = loaded;
                    textLocalCount.setText(String.valueOf(loaded.localCount));
                    textCloudCount.setText(String.valueOf(loaded.storedCount));
                    textDownloadDetail.setText(loaded.localCount == 0
                            ? "Brings " + loaded.storedCount + " items onto this phone."
                            : "Adds anything on Drive that's missing here.");
                    textUploadDetail.setText("Deletes " + loaded.storedCount
                            + " items from Drive that aren't on this phone.");
                    btnContinue.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, readable(e), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void onContinue() {
        int checked = radioGroup.getCheckedRadioButtonId();
        if (checked == R.id.radioUpload) {
            // The only path that removes anything. Confirm with the real number.
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Delete " + plan.storedCount + " items from Drive?")
                    .setMessage("Google Drive will be replaced with what's on this phone. "
                            + "Items only on Drive cannot be recovered afterwards.")
                    .setPositiveButton("Replace Drive",
                            (d, w) -> run(CloudSync.Resolution.UPLOAD))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else if (checked == R.id.radioDownload) {
            run(CloudSync.Resolution.DOWNLOAD);
        } else {
            run(CloudSync.Resolution.MERGE);
        }
    }

    private void run(CloudSync.Resolution resolution) {
        AlertDialog dialog = Prompts.progress(this, "Syncing with Google Drive");

        executor.execute(() -> {
            try {
                CloudSync.Progress progress = new CloudSync.Progress() {
                    @Override
                    public void onStage(String stage) {
                        runOnUiThread(() -> dialog.setMessage(stage));
                    }

                    @Override
                    public void onItem(int done, int total, long bytesDone, long bytesTotal) {
                        runOnUiThread(() -> dialog.setMessage(done + " of " + total));
                    }
                };

                if (resolution == CloudSync.Resolution.DOWNLOAD
                        || resolution == CloudSync.Resolution.MERGE) {
                    // Pull first so a merge never has to guess: whatever is on Drive comes
                    // down, then the union goes back up as a new restore point.
                    List<Snapshot.Summary> points = sync.listRestorePoints();
                    if (!points.isEmpty()) {
                        sync.restore(points.get(0).id, false, lockValue(), progress);
                    }
                }
                if (resolution != CloudSync.Resolution.DOWNLOAD) {
                    sync.backup(sync.plan(), resolution, progress);
                }

                SecurityHelper.getAppPrefs(this).edit()
                        .putBoolean(SyncWorker.PREF_NEEDS_CHOICE, false).apply();

                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "Google Drive is up to date", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Couldn't finish")
                            .setMessage(readable(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            } finally {
                CloudSync.clearStaging(this);
            }
        });
    }

    /** The lock that opens the backup keyring. The user is already inside this vault. */
    private String lockValue() {
        VaultEntity vault = AppDatabase.getInstance(this).vaultDao().getVaultById(vaultId);
        if (vault == null) vault = AppDatabase.getInstance(this).vaultDao().getMainVault();
        return vault == null ? "" : vault.getLockValue();
    }

    /** Plain language, never a raw exception message. */
    static String readable(Exception e) {
        if (e instanceof DriveStore.QuotaException || e instanceof DriveStore.AuthException) {
            return e.getMessage();
        }
        if (e instanceof VaultCrypto.WrongPasswordException) {
            String m = e.getMessage();
            return m != null && m.startsWith("This backup") ? m : "That lock doesn't open this backup.";
        }
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketException) {
            return "You're offline. We'll pick up where we left off.";
        }
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? "Something went wrong talking to Google Drive."
                : message;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
