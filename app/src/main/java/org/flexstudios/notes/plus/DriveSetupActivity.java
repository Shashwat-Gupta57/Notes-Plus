package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

/**
 * The one question the backup asks up front, shown once right after an account is connected.
 *
 * Defaulting to Wi-Fi matters here: a vault can be several gigabytes, and the person this app
 * is built for will not think about that before tapping something labelled "back up".
 */
public class DriveSetupActivity extends VaultActivity {

    public static Intent intent(Context context) {
        return new Intent(context, DriveSetupActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive_setup);

        Toolbar toolbar = findViewById(R.id.toolbarDriveSetup);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView account = findViewById(R.id.textDriveSetupAccount);
        GoogleSignInAccount signedIn = GoogleSignIn.getLastSignedInAccount(this);
        if (signedIn != null && signedIn.getEmail() != null) {
            account.setText("Connected as " + signedIn.getEmail()
                    + ". Your photos stay locked — Google can't open them, only your lock can.");
        }

        RadioGroup group = findViewById(R.id.radioGroupBackupWhen);
        // Reflect whatever is already set, so revisiting this screen is not confusing.
        if (!SecurityHelper.isAutoSyncEnabled(this)) {
            group.check(R.id.radioManualOnly);
        } else if (SecurityHelper.isSyncWifiOnly(this)) {
            group.check(R.id.radioWifi);
        } else {
            group.check(R.id.radioAnyNetwork);
        }

        findViewById(R.id.btnDriveSetupSkip).setOnClickListener(v -> finish());
        findViewById(R.id.btnDriveSetupStart).setOnClickListener(v -> {
            int checked = group.getCheckedRadioButtonId();
            if (checked == R.id.radioManualOnly) {
                SecurityHelper.setAutoSyncEnabled(this, false);
            } else {
                SecurityHelper.setAutoSyncEnabled(this, true);
                SecurityHelper.setSyncNetwork(this, checked == R.id.radioWifi);
                // Kick off the first backup straight away so "Start" visibly does something.
                SyncWorker.requestBackupNow(this);
            }
            setResult(RESULT_OK);
            finish();
        });
    }
}
