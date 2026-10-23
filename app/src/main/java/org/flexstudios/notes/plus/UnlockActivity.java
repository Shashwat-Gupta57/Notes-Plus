package org.flexstudios.notes.plus;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.List;

import jp.wasabeef.glide.transformations.BlurTransformation;

public class UnlockActivity extends AppCompatActivity implements UnlockPagerAdapter.OnUnlockAttemptListener {
    public static final String EXTRA_VERIFY_ONLY = "verify_only";
    public static final String EXTRA_UNLOCKED_FLAG = "is_unlocked";
    public static final String EXTRA_VAULT_ID = "vault_id";
    /**
     * Which hidden space this lock screen opens. The screen looks identical for both, and a
     * lock only ever matches vaults of its own space; failed attempts count against one
     * shared lockout, so two screens never mean twice the guesses.
     */
    public static final String EXTRA_REALM = "realm";
    public static final String REALM_CHAT = "_chat";
    
    /** Free attempts before the first lockout. */
    private static final int FREE_ATTEMPTS = 5;
    private static final long FIRST_LOCKOUT_MS = 30_000;
    private static final long MAX_LOCKOUT_MS = 60 * 60_000;
    private static final String KEY_FAILURES = "unlock_failures";
    private static final String KEY_LOCKED_UNTIL = "unlock_locked_until";

    private ViewPager2 viewPager;
    private TextView errorText;
    private CountDownTimer lockoutTimer;
    private boolean verifyOnly = false;
    private List<VaultEntity> allVaults;
    private List<ChatStore.Vault> chatVaults;
    private boolean chat, checking;

    private ImageView lockBackground;
    private View lockDimOverlay, unlockRoot;
    private PlayerView videoBgView;
    private ExoPlayer exoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chat = REALM_CHAT.equals(getIntent().getStringExtra(EXTRA_REALM));
        SecurityHelper.applyScreenProtection(getWindow(), this, chat ? REALM_CHAT : "");
        setContentView(R.layout.activity_unlock);

        verifyOnly = getIntent().getBooleanExtra(EXTRA_VERIFY_ONLY, false);
        viewPager = findViewById(R.id.unlockViewPager);
        errorText = findViewById(R.id.errorText);
        lockBackground = findViewById(R.id.lockBackground);
        lockDimOverlay = findViewById(R.id.lockDimOverlay);
        unlockRoot = findViewById(R.id.unlockRoot);
        videoBgView = findViewById(R.id.videoBgView);

        UnlockPagerAdapter adapter = new UnlockPagerAdapter(this);
        viewPager.setAdapter(adapter);
        
        applyLockBackground();
        loadVaults();
    }

    private void applyLockBackground() {
        String type = SecurityHelper.getAppPrefs(this).getString(SecurityHelper.KEY_LOCK_BG_TYPE, "DEFAULT");
        String value = SecurityHelper.getAppPrefs(this).getString(SecurityHelper.KEY_LOCK_BG_VALUE, null);
        boolean blur = SecurityHelper.getAppPrefs(this).getBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, false);
        boolean dim = SecurityHelper.getAppPrefs(this).getBoolean(SecurityHelper.KEY_LOCK_BG_DIM, false);

        stopVideoBackground();

        if ("COLOR".equals(type) && value != null) {
            try {
                unlockRoot.setBackgroundColor(Color.parseColor(value));
                lockBackground.setVisibility(View.GONE);
                lockDimOverlay.setVisibility(View.GONE);
                videoBgView.setVisibility(View.GONE);
            } catch (Exception e) {
                restoreDefaultBackground();
            }
        } else if ("IMAGE".equals(type) && value != null) {
            File imgFile = new File(value);
            if (imgFile.exists() || value.startsWith("http")) {
                lockBackground.setVisibility(View.VISIBLE);
                lockDimOverlay.setVisibility(dim ? View.VISIBLE : View.GONE);
                unlockRoot.setBackgroundColor(Color.TRANSPARENT);
                videoBgView.setVisibility(View.GONE);

                RequestOptions options = new RequestOptions();
                if (blur) {
                    options = options.transform(new BlurTransformation(25, 3));
                }

                Glide.with(this)
                        .load(value.startsWith("http") ? value : imgFile)
                        .apply(options)
                        .into(lockBackground);
            } else {
                restoreDefaultBackground();
            }
        } else if (("VIDEO".equals(type) || "URL".equals(type)) && value != null) {
            lockBackground.setVisibility(View.GONE);
            lockDimOverlay.setVisibility(dim ? View.VISIBLE : View.GONE);
            unlockRoot.setBackgroundColor(Color.BLACK);
            videoBgView.setVisibility(View.VISIBLE);
            startVideoBackground(value);
        } else {
            restoreDefaultBackground();
        }
    }

    private void startVideoBackground(String source) {
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        exoPlayer = new ExoPlayer.Builder(this).build();
        videoBgView.setPlayer(exoPlayer);
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
        exoPlayer.setVolume(0f); // Mute audio
        
        Uri uri;
        if (source.startsWith("http")) {
            uri = Uri.parse(source);
        } else {
            uri = Uri.fromFile(new File(source));
        }
        
        MediaItem mediaItem = MediaItem.fromUri(uri);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();
    }

    private void stopVideoBackground() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    private void restoreDefaultBackground() {
        unlockRoot.setBackgroundColor(ContextCompat.getColor(this, R.color.app_background));
        lockBackground.setVisibility(View.GONE);
        lockDimOverlay.setVisibility(View.GONE);
        videoBgView.setVisibility(View.GONE);
        stopVideoBackground();
    }

    private void loadVaults() {
        AppDatabase.IO.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            String type;
            if (chat) {
                chatVaults = db.chatDao().vaults();
                allVaults = new java.util.ArrayList<>();
                ChatStore.Vault main = db.chatDao().mainVault();
                type = main == null ? null : main.lockType;
            } else {
                allVaults = db.vaultDao().getAllVaultsDirect();
                VaultEntity mainVault = db.vaultDao().getMainVault();
                type = mainVault == null ? null : mainVault.getLockType();
            }

            runOnUiThread(() -> {
                if (type != null) {
                    if ("PIN".equals(type)) viewPager.setCurrentItem(0, false);
                    else if ("PASSWORD".equals(type)) viewPager.setCurrentItem(1, false);
                    else if ("PATTERN".equals(type)) viewPager.setCurrentItem(2, false);
                }
            });
        });
    }

    @Override
    public void onUnlockAttempt(String type, String value) {
        if (allVaults == null) return;
        if (lockoutRemaining() > 0) {
            showLockout();
            return;
        }

        VaultEntity matchedVault = null;
        for (VaultEntity v : allVaults) {
            if (type.equals(v.getLockType()) && value.equals(v.getLockValue())) {
                matchedVault = v;
                break;
            }
        }

        if (chat) {
            // Chat locks are stored hashed (PBKDF2), so checking one takes a moment: off the
            // main thread, and one attempt at a time.
            if (checking) return;
            checking = true;
            AppDatabase.IO.execute(() -> {
                ChatStore.Vault match;
                try {
                    match = ChatVaults.find(this, type, value);
                } catch (Exception e) {
                    match = null;
                }
                ChatStore.Vault found = match;
                runOnUiThread(() -> {
                    checking = false;
                    if (found == null) {
                        handleFailedAttempt();
                        return;
                    }
                    SecurityHelper.getAppPrefs(this).edit().remove(KEY_FAILURES).remove(KEY_LOCKED_UNTIL).apply();
                    SecurityHelper.updateLastActiveTime(this, REALM_CHAT);
                    if (verifyOnly) setResult(RESULT_OK);
                    else startActivity(new Intent(this, ChatHomeActivity.class).putExtra(EXTRA_VAULT_ID, found.id));
                    finish();
                });
            });
            return;
        }

        if (matchedVault != null) {
            SecurityHelper.getAppPrefs(this).edit()
                    .remove(KEY_FAILURES).remove(KEY_LOCKED_UNTIL).apply();
            SecurityHelper.updateLastActiveTime(this);
            if (verifyOnly) {
                setResult(RESULT_OK);
            } else {
                Intent intent = new Intent(this, SecretGalleryActivity.class);
                intent.putExtra(EXTRA_UNLOCKED_FLAG, true);
                intent.putExtra(EXTRA_VAULT_ID, matchedVault.getId());
                startActivity(intent);
            }
            finish();
        } else {
            handleFailedAttempt();
        }
    }

    /**
     * Counts a failure and, past the free attempts, locks out for 30 s, doubling with every
     * further miss up to an hour. Stored, not held in memory: the old counter reset whenever
     * the screen was closed and reopened, so a 4-digit PIN could be walked in an afternoon.
     */
    private void handleFailedAttempt() {
        android.content.SharedPreferences prefs = SecurityHelper.getAppPrefs(this);
        int failures = prefs.getInt(KEY_FAILURES, 0) + 1;
        android.content.SharedPreferences.Editor editor = prefs.edit().putInt(KEY_FAILURES, failures);
        if (failures >= FREE_ATTEMPTS) {
            int doublings = Math.min(failures - FREE_ATTEMPTS, 20);
            long lockout = Math.min(FIRST_LOCKOUT_MS << doublings, MAX_LOCKOUT_MS);
            editor.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + lockout);
        }
        editor.apply();

        if (lockoutRemaining() > 0) {
            showLockout();
        } else {
            errorText.setText("Incorrect"); // Deliberately generic.
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private long lockoutRemaining() {
        long until = SecurityHelper.getAppPrefs(this).getLong(KEY_LOCKED_UNTIL, 0);
        long remaining = until - System.currentTimeMillis();
        // A clock moved backwards must not extend a lockout indefinitely.
        return remaining > MAX_LOCKOUT_MS ? MAX_LOCKOUT_MS : remaining;
    }

    private void showLockout() {
        if (lockoutTimer != null) lockoutTimer.cancel();
        errorText.setVisibility(View.VISIBLE);
        lockoutTimer = new CountDownTimer(lockoutRemaining(), 1000) {
            public void onTick(long millisUntilFinished) {
                errorText.setText("Try again in " + (millisUntilFinished / 1000 + 1) + "s");
            }

            public void onFinish() {
                errorText.setVisibility(View.INVISIBLE);
            }
        }.start();
    }

    /**
     * Asks for whatever opens a backup: the backup password, or any vault's PIN, password or
     * pattern. Patterns are drawn, not typed; a vault locked with one is otherwise impossible
     * to name in a text box.
     *
     * @param onCancel may be null
     */
    static void askForBackupKey(android.app.Activity activity, java.util.function.Consumer<String> onKey,
                                Runnable onCancel) {
        askForBackupKey(activity, "Enter the backup lock, or the lock of any vault in that backup.", onKey, onCancel);
    }

    /**
     * Unlock backup: a PIN, a password or a pattern, whichever the backup wants. {@code message}
     * says which (see {@link VaultCrypto#describeKeyring}).
     */
    static void askForBackupKey(android.app.Activity activity, String message, java.util.function.Consumer<String> onKey,
                                Runnable onCancel) {
        lockDialog(activity, "Unlock backup", message, null, "Open", onCancel == null ? "Cancel" : "Skip", onKey, onCancel);
    }

    /** One new lock of {@code type} (PIN, PASSWORD or PATTERN), checked like a vault's. */
    static void askForNewLock(android.app.Activity activity, String type, String title,
                              java.util.function.Consumer<String> onLock) {
        lockDialog(activity, title, null, type, "Next", "Cancel", value -> {
            String problem = value.isEmpty() ? "It can't be empty"
                    : "PIN".equals(type) && value.length() < 4 ? "A PIN needs at least 4 digits"
                    : "PASSWORD".equals(type) && value.length() < 6 ? "Use at least 6 characters"
                    : "PATTERN".equals(type) && value.length() < 4 ? "Connect at least 4 dots" : null;
            if (problem != null) {
                android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show();
                askForNewLock(activity, type, title, onLock);
                return;
            }
            onLock.accept(value);
        }, null);
    }

    /**
     * A lock entry dialog. {@code fixedType} null: PIN / Password / Pattern tabs to pick from;
     * otherwise just that kind.
     */
    /** Entering an existing lock of {@code type} (a locked note's): {@code onCancel} runs on Cancel. */
    static void askForLock(android.app.Activity activity, String type, String title, String message,
                           java.util.function.Consumer<String> onKey, Runnable onCancel) {
        lockDialog(activity, title, message, type, "Open", "Cancel", onKey, onCancel);
    }

    private static void lockDialog(android.app.Activity activity, String title, String message, String fixedType,
                                   String okLabel, String cancelLabel, java.util.function.Consumer<String> onKey,
                                   Runnable onCancel) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);

        com.google.android.material.tabs.TabLayout tabs = new com.google.android.material.tabs.TabLayout(activity);
        for (String t : new String[]{"PIN", "Password", "Pattern"}) tabs.addTab(tabs.newTab().setText(t));
        if (fixedType == null) box.addView(tabs);

        com.google.android.material.textfield.TextInputLayout field =
                Prompts.field(activity, "PIN", null, android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        com.google.android.material.textfield.TextInputEditText input =
                (com.google.android.material.textfield.TextInputEditText) field.getEditText();
        android.widget.LinearLayout.LayoutParams fieldLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = Math.round(12 * density);
        box.addView(field, fieldLp);

        PatternView pattern = new PatternView(activity, null);
        int size = Math.round(260 * density);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.round(8 * density);
        box.addView(pattern, lp);

        String[] kind = {fixedType != null ? fixedType : "PIN"};
        Runnable show = () -> {
            boolean drawing = "PATTERN".equals(kind[0]);
            pattern.setVisibility(drawing ? View.VISIBLE : View.GONE);
            field.setVisibility(drawing ? View.GONE : View.VISIBLE);
            boolean pin = "PIN".equals(kind[0]);
            field.setHint(pin ? "PIN" : "Password");
            if (input != null) {
                input.setInputType(pin
                        ? android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setText("");
            }
        };
        show.run();
        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                kind[0] = tab.getPosition() == 0 ? "PIN" : tab.getPosition() == 1 ? "PASSWORD" : "PATTERN";
                show.run();
            }

            @Override
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle(title)
                        .setView(box)
                        .setPositiveButton(okLabel, (d, w) -> onKey.accept(Prompts.textOf(input)))
                        .setNegativeButton(cancelLabel, (d, w) -> {
                            if (onCancel != null) onCancel.run();
                        })
                        .setCancelable(false);
        if (message != null) builder.setMessage(message);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        pattern.setOnPatternListener(value -> {
            dialog.dismiss();
            onKey.accept(value);
        });
        Prompts.wire(dialog, input);
        dialog.show();
    }

    public void setSwipeEnabled(boolean enabled) {
        viewPager.setUserInputEnabled(enabled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lockoutRemaining() > 0) showLockout();
        if (exoPlayer != null && !exoPlayer.isPlaying()) {
            exoPlayer.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lockoutTimer != null) lockoutTimer.cancel();
        stopVideoBackground();
    }
}