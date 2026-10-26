package org.flexstudios.notes.plus;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.work.WorkManager;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;

/**
 * The notes account: sign in (Google or email), verify an email, then a profile screen with
 * sync status, the Drive connection for attachments, username, sign out and delete.
 *
 * All auth goes through {@link Account}; this screen only shows state and asks questions.
 */
public class AccountActivity extends AppCompatActivity {

    private View panelSignIn, panelVerify, panelAccount;
    private LinearProgressIndicator progress;
    private TextInputEditText inputEmail, inputPassword;
    private MaterialButton btnEmail, btnToggleMode;
    private boolean creating;
    private Account.Profile profile;

    private final ActivityResultLauncher<IntentSenderRequest> driveConsent = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getData() == null) {
                    busy(false);
                    return;
                }
                try {
                    driveAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.getData()));
                } catch (ApiException e) {
                    busy(false);
                    toast("Couldn't connect Google Drive");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        Toolbar toolbar = findViewById(R.id.toolbarAccount);
        toolbar.setNavigationOnClickListener(v -> finish());

        panelSignIn = findViewById(R.id.panelSignIn);
        panelVerify = findViewById(R.id.panelVerify);
        panelAccount = findViewById(R.id.panelAccount);
        progress = findViewById(R.id.progressAccount);
        inputEmail = findViewById(R.id.inputEmail);
        inputPassword = findViewById(R.id.inputPassword);
        btnEmail = findViewById(R.id.btnEmail);
        btnToggleMode = findViewById(R.id.btnToggleMode);

        findViewById(R.id.btnGoogle).setOnClickListener(v -> {
            busy(true);
            Account.signInWithGoogle(this, this::afterSignIn);
        });
        btnEmail.setOnClickListener(v -> emailSubmit());
        inputPassword.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            emailSubmit();
            return true;
        });
        btnToggleMode.setOnClickListener(v -> {
            creating = !creating;
            btnEmail.setText(creating ? "Create account" : "Sign in");
            btnToggleMode.setText(creating ? "Have an account? Sign in" : "New here? Create an account");
        });
        findViewById(R.id.btnForgot).setOnClickListener(v -> forgotPassword());

        findViewById(R.id.btnVerified).setOnClickListener(v -> {
            busy(true);
            Account.checkVerified(this::afterSignIn);
        });
        findViewById(R.id.btnResend).setOnClickListener(v -> {
            busy(true);
            Account.resendVerification(error -> {
                busy(false);
                toast(error == null ? "Sent. Check your inbox (and spam)." : error);
            });
        });
        findViewById(R.id.btnOtherAccount).setOnClickListener(v -> Account.signOut(this, true, this::refresh));

        findViewById(R.id.rowSync).setOnClickListener(v -> {
            NoteSync.schedule(this);
            toast("Syncing…");
        });
        findViewById(R.id.rowDrive).setOnClickListener(v -> driveOptions());
        com.google.android.material.materialswitch.MaterialSwitch switchFA = findViewById(R.id.switchFoldersArchive);
        if (switchFA != null) {
            switchFA.setOnCheckedChangeListener((btn, isChecked) -> {
                if (btn.isPressed()) {
                    Account.setSyncFoldersArchive(this, isChecked);
                    showFoldersArchiveStatus(isChecked);
                }
            });
            findViewById(R.id.rowFoldersArchive).setOnClickListener(v -> switchFA.toggle());
        }
        findViewById(R.id.rowUsername).setOnClickListener(v -> askUsername(false));
        findViewById(R.id.imgAvatar).setOnClickListener(v -> choosePhoto());
        findViewById(R.id.rowSignOut).setOnClickListener(v -> confirmSignOut());
        findViewById(R.id.rowDelete).setOnClickListener(v -> confirmDelete());
        updateStatus = findViewById(R.id.txtUpdateStatus);
        updateStatus.setText(Updater.isReadyToInstall(this) ? "Update downloaded. Tap to install" : "You're on " + versionName());
        findViewById(R.id.rowUpdate).setOnClickListener(v -> checkForUpdate());
        showNoteLockTime();
        findViewById(R.id.rowNoteLock).setOnClickListener(v -> chooseNoteLockTime());
        ((TextView) findViewById(R.id.txtTheme)).setText(AppTheme.NAMES[AppTheme.index(this)]);
        findViewById(R.id.rowTheme).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(AppTheme.NAMES, AppTheme.index(this), (d, which) -> {
                    d.dismiss();
                    AppTheme.set(this, AppTheme.MODES[which]); // Screens redraw in the new look.
                })
                .setNegativeButton("Cancel", null)
                .show());
        ((TextView) findViewById(R.id.txtAboutVersion)).setText("Version " + versionName());
        findViewById(R.id.rowAbout).setOnClickListener(v -> showAbout());

        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(NoteSync.UNIQUE)
                .observe(this, infos -> showSyncStatus());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSyncStatus();
    }

    // ================================================================= state

    private void refresh() {
        busy(false);
        FirebaseUser user = Account.user();
        boolean verified = Account.uid() != null;
        panelSignIn.setVisibility(user == null ? View.VISIBLE : View.GONE);
        panelVerify.setVisibility(user != null && !verified ? View.VISIBLE : View.GONE);
        panelAccount.setVisibility(verified ? View.VISIBLE : View.GONE);
        if (user != null && !verified) {
            ((TextView) findViewById(R.id.txtVerify)).setText("We sent a link to " + user.getEmail()
                    + ". Tap it to confirm it's you, then come back here.");
        }
        if (!verified) return;
        ((TextView) findViewById(R.id.txtEmail)).setText(user.getEmail());
        Account.loadProfile(p -> {
            if (isFinishing() || isDestroyed() || p == null) return;
            profile = p;
            ((TextView) findViewById(R.id.txtName)).setText(p.displayName == null || p.displayName.isEmpty()
                    ? (p.username != null ? p.username : p.email) : p.displayName);
            ((TextView) findViewById(R.id.txtHandle)).setText(p.username == null ? "" : "@" + p.username);
            ((TextView) findViewById(R.id.txtUsername)).setText(p.username == null
                    ? "Choose one so people can find you" : "@" + p.username);
            showPhoto(p.photoUrl);
            showFoldersArchiveStatus(p.syncFoldersArchive);
        });
        showSyncStatus();
        showDriveStatus();
        showFoldersArchiveStatus(Account.isSyncFoldersArchive(this));
    }

    private void showFoldersArchiveStatus(boolean on) {
        com.google.android.material.materialswitch.MaterialSwitch s = findViewById(R.id.switchFoldersArchive);
        if (s != null && s.isChecked() != on) s.setChecked(on);
        TextView tv = findViewById(R.id.txtFoldersArchiveStatus);
        if (tv != null) {
            tv.setText(on ? "Sync across devices" : "Stored on this phone only");
        }
    }

    private void showSyncStatus() {
        String uid = Account.uid();
        if (uid == null) return;
        TextView status = findViewById(R.id.txtSyncStatus);
        AppDatabase.IO.execute(() -> {
            int waiting = AppDatabase.getInstance(this).noteDao().countDirty(uid);
            long last = NoteSync.lastSynced(this, uid);
            runOnUiThread(() -> status.setText(
                    (last == 0 ? "Not synced yet" : "Synced " + DateUtils.getRelativeTimeSpanString(last,
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                            + (waiting > 0 ? " · " + waiting + " waiting" : "")));
        });
    }

    private void showDriveStatus() {
        String email = Account.driveEmail(this);
        String error = NoteSync.driveError(this);
        ((TextView) findViewById(R.id.txtDriveStatus)).setText(email == null
                ? "Not connected: they stay on this phone"
                : error != null ? error + " · tap to fix" : "Google Drive · " + email);
    }

    private void busy(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        btnEmail.setEnabled(!on);
        findViewById(R.id.btnGoogle).setEnabled(!on);
    }

    // ================================================================ sign in

    private void emailSubmit() {
        String email = text(inputEmail), password = text(inputPassword);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("Enter a valid email");
            return;
        }
        if (password.length() < 6) {
            toast("The password needs at least 6 characters");
            return;
        }
        busy(true);
        if (creating) Account.signUp(email, password, this::afterSignIn);
        else Account.signIn(email, password, this::afterSignIn);
    }

    private void forgotPassword() {
        Prompts.text(this, "Reset password", "We'll email you a link to choose a new one.", "Email",
                text(inputEmail), Prompts.TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "Send link",
                email -> Account.resetPassword(email, error -> toast(error == null
                        ? "Sent. Check your inbox." : error)), null);
    }

    /** Every sign-in path ends here: then the questions a new account needs answered. */
    private void afterSignIn(@Nullable String error) {
        refresh();
        if (error != null) {
            if (!error.isEmpty()) toast(error);
            return;
        }
        String uid = Account.uid();
        if (uid == null) return; // Waiting for the email link; the verify panel says so.
        NoteSync.schedulePeriodic(this);
        ChatSync.schedule(this);
        AppDatabase.IO.execute(() -> {
            int outside = AppDatabase.getInstance(this).noteDao().countOutside(uid);
            runOnUiThread(() -> {
                if (outside > 0) offerAdopt(uid, outside);
                else afterAdopt();
            });
        });
    }

    private void offerAdopt(String uid, int count) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add your notes to this account?")
                .setMessage("You have " + count + (count == 1 ? " note" : " notes")
                        + " on this phone. Add them to your account so they're backed up and on your other devices.")
                .setPositiveButton("Add them", (d, w) -> AppDatabase.IO.execute(() -> {
                    AppDatabase.getInstance(this).noteDao().adopt(uid);
                    NoteSync.schedule(this);
                }))
                .setNegativeButton("Keep on this phone only", null)
                .setOnDismissListener(d -> afterAdopt())
                .show();
    }

    private void afterAdopt() {
        NoteSync.schedule(this);
        Account.loadProfile(p -> {
            if (isFinishing() || isDestroyed() || p == null) return;
            profile = p;
            if (p.username == null) askUsername(true);
            else if (Account.driveEmail(this) == null) offerDrive();
        });
    }

    // ================================================================= photo

    private final ActivityResultLauncher<String[]> pickPhoto = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) savePhoto(uri);
            });

    /** The photo others see: a custom one if chosen, else the Google one. */
    private void showPhoto(@Nullable String googleUrl) {
        ImageView avatar = findViewById(R.id.imgAvatar);
        Account.loadPhoto(json -> {
            if (isDestroyed()) return;
            String shown = json != null ? json : googleUrl == null ? null : "{\"url\":" + org.json.JSONObject.quote(googleUrl) + "}";
            if (shown == null) return;
            // The tile behind the placeholder icon would frame the round photo.
            avatar.setBackground(null);
            avatar.setPadding(0, 0, 0, 0);
            avatar.setImageTintList(null);
            ChatMedia.loadAvatar(this, avatar, shown, () -> {});
        });
    }

    private void choosePhoto() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Profile photo")
                .setItems(new String[]{"Choose a photo", "Use my Google photo"}, (d, which) -> {
                    if (which == 1) savePhoto(null);
                    else if (Account.driveEmail(this) == null) offerDrive();
                    else pickPhoto.launch(new String[]{"image/*"});
                })
                .show();
    }

    private void savePhoto(@Nullable android.net.Uri uri) {
        busy(true);
        AppDatabase.IO.execute(() -> {
            String error = null;
            try {
                Account.setPhoto(this, uri);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Couldn't change the photo" : e.getMessage();
            }
            String message = error;
            runOnUiThread(() -> {
                busy(false);
                if (message != null) toast(message);
                refresh();
            });
        });
    }

    // =============================================================== username

    private void askUsername(boolean onboarding) {
        String current = profile == null ? null : profile.username;
        Prompts.text(this, current == null ? "Choose a username" : "Change username",
                "3–20 letters, numbers, dots or underscores. It's how people will find you; you never sign in with it.",
                "username", current, Prompts.TEXT, "Save", raw -> {
                    String name = Account.normaliseUsername(raw);
                    if (name == null) {
                        toast("Use 3–20 letters, numbers, . or _");
                        askUsername(onboarding);
                        return;
                    }
                    busy(true);
                    Account.isUsernameFree(name, free -> {
                        if (free == null || !free) {
                            busy(false);
                            toast(free == null ? "No connection. Try again." : "@" + name + " is taken. Try another.");
                            askUsername(onboarding);
                            return;
                        }
                        Account.claimUsername(name, current, error -> {
                            if (error != null) toast(error);
                            refresh();
                            if (error == null && onboarding && Account.driveEmail(this) == null) offerDrive();
                        });
                    });
                }, () -> {
                    if (onboarding && Account.driveEmail(this) == null) offerDrive();
                });
    }

    // ================================================================== Drive

    private void offerDrive() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Back up photos and recordings?")
                .setMessage("Photos, videos and recordings in your notes go to your own Google Drive, so only you can ever open them. Notes+ can only see the files it puts there.")
                .setPositiveButton("Connect Google Drive", (d, w) -> connectDrive())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void driveOptions() {
        String email = Account.driveEmail(this);
        if (email == null || NoteSync.driveError(this) != null) {
            connectDrive();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Google Drive")
                .setMessage("Photos and recordings sync through " + email + ".")
                .setPositiveButton("OK", null)
                .setNegativeButton("Disconnect", (d, w) -> {
                    Account.disconnectDrive(this);
                    showDriveStatus();
                })
                .show();
    }

    private void connectDrive() {
        FirebaseUser user = Account.user();
        String email = user != null && Account.isGoogle(user) ? user.getEmail() : null;
        busy(true);
        Account.authorizeDrive(this, email)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        driveConsent.launch(new IntentSenderRequest.Builder(
                                result.getPendingIntent().getIntentSender()).build());
                    } else {
                        driveAuthorized(result);
                    }
                })
                .addOnFailureListener(e -> {
                    busy(false);
                    toast("Couldn't connect Google Drive");
                });
    }

    private void driveAuthorized(AuthorizationResult result) {
        busy(false);
        FirebaseUser user = Account.user();
        String fallback = user != null && Account.isGoogle(user) ? user.getEmail() : null;
        if (!Account.driveAuthorized(this, result, fallback)) toast("Couldn't tell which Google account was used");
        showDriveStatus();
    }

    // ======================================================= sign out / delete

    private void confirmSignOut() {
        String uid = Account.uid();
        AppDatabase.IO.execute(() -> {
            int waiting = uid == null ? 0 : AppDatabase.getInstance(this).noteDao().countDirty(uid);
            runOnUiThread(() -> {
                MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this).setTitle("Sign out?");
                if (waiting > 0) {
                    // Removing them now would lose edits the account has never seen.
                    dialog.setMessage(waiting + (waiting == 1 ? " note hasn't" : " notes haven't")
                                    + " synced yet, so your notes will stay on this phone.")
                            .setPositiveButton("Sign out", (d, w) -> signOut(true));
                } else {
                    dialog.setMessage("Your notes are safe in your account. Keep a copy on this phone too?")
                            .setPositiveButton("Keep on this phone", (d, w) -> signOut(true))
                            .setNeutralButton("Remove from phone", (d, w) -> signOut(false));
                }
                dialog.setNegativeButton("Cancel", null).show();
            });
        });
    }

    private void signOut(boolean keep) {
        busy(true);
        Account.signOut(this, keep, () -> {
            profile = null;
            refresh();
            toast("Signed out");
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete your account?")
                .setMessage("This removes your notes from the cloud, your username, and the photos and recordings Notes+ put in your Google Drive. Notes on this phone stay here. It can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    FirebaseUser user = Account.user();
                    if (user != null && Account.isGoogle(user)) {
                        deleteAccount(null);
                    } else {
                        Prompts.text(this, "Confirm it's you", null, "Password", null, Prompts.PASSWORD,
                                "Delete account", this::deleteAccount, null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAccount(@Nullable String password) {
        busy(true);
        Account.deleteAccount(this, password, error -> {
            if (error != null) {
                busy(false);
                if (!error.isEmpty()) toast(error);
                return;
            }
            profile = null;
            refresh();
            toast("Account deleted");
        });
    }

    // ================================================================ helpers

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ================================================================ the app

    private TextView updateStatus;

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkForUpdate() {
        if (Updater.isReadyToInstall(this)) {
            Updater.install(this);
            return;
        }
        updateStatus.setText("Checking…");
        Updater.check(this, (release, error) -> {
            if (isFinishing()) return;
            if (error != null) {
                updateStatus.setText("Couldn't check. Are you online?");
            } else if (release == null) {
                updateStatus.setText("Up to date (" + versionName() + ")");
            } else {
                updateStatus.setText("Version " + release.versionName + " is available");
                Updater.showReleaseDialog(this, release);
            }
        });
    }

    private static final int[] LOCK_MINUTES = {0, 1, 5, 15, 30, 60, -1};

    private static String lockTimeLabel(int minutes) {
        return minutes == 0 ? "Lock again as soon as I leave the note"
                : minutes < 0 ? "Stay open until the app closes"
                : "Lock again " + (minutes == 60 ? "1 hour" : minutes + (minutes == 1 ? " minute" : " minutes")) + " after leaving";
    }

    private void showNoteLockTime() {
        ((TextView) findViewById(R.id.txtNoteLock)).setText(lockTimeLabel(NoteLock.timeoutMinutes(this)));
    }

    /** How long an unlocked note stays open after leaving it, like a vault's auto-lock. */
    private void chooseNoteLockTime() {
        String[] labels = new String[LOCK_MINUTES.length];
        int current = 0;
        for (int i = 0; i < LOCK_MINUTES.length; i++) {
            labels[i] = lockTimeLabel(LOCK_MINUTES[i]);
            if (LOCK_MINUTES[i] == NoteLock.timeoutMinutes(this)) current = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Locked notes")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    NoteLock.setTimeoutMinutes(this, LOCK_MINUTES[which]);
                    showNoteLockTime();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Notes+")
                .setMessage("Version " + versionName() + " (" + versionCode() + ")\n\n"
                        + "Notes, lists, mind maps, sketches and recordings, synced with your other devices.\n\n"
                        + "Flex Studios · notes.talken.in")
                .setPositiveButton("OK", null)
                .setNeutralButton("Website", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://notes.talken.in")));
                    } catch (Exception e) {
                        Toast.makeText(this, "No browser to open it", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
