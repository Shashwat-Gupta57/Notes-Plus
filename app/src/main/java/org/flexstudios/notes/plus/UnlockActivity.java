package org.flexstudios.notes.plus;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class UnlockActivity extends AppCompatActivity {
    public static final String EXTRA_VERIFY_ONLY = "verify_only";
    public static final String EXTRA_UNLOCKED_FLAG = "is_unlocked";
    
    private EditText unlockPin, unlockPassword;
    private PatternView unlockPattern;
    private TextView errorText, unlockMessage;
    private String savedLockType, savedLockValue;
    private String currentPattern = "";
    private int failedAttempts = 0;
    private boolean isLockedOut = false;
    private boolean verifyOnly = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_unlock);

        verifyOnly = getIntent().getBooleanExtra(EXTRA_VERIFY_ONLY, false);

        unlockPin = findViewById(R.id.unlockPin);
        unlockPassword = findViewById(R.id.unlockPassword);
        unlockPattern = findViewById(R.id.unlockPattern);
        errorText = findViewById(R.id.errorText);
        unlockMessage = findViewById(R.id.unlockMessage);
        Button buttonUnlock = findViewById(R.id.buttonUnlock);

        SharedPreferences encryptedPrefs = SecurityHelper.getEncryptedPrefs(this);
        savedLockType = encryptedPrefs.getString(SecurityHelper.KEY_LOCK_TYPE, "");
        savedLockValue = encryptedPrefs.getString(SecurityHelper.KEY_LOCK_VALUE, "");

        setupUI();

        buttonUnlock.setOnClickListener(v -> checkUnlock());
        unlockPattern.setOnPatternListener(pattern -> {
            currentPattern = pattern;
            if (!isLockedOut) checkUnlock();
        });
    }

    private void setupUI() {
        unlockPin.setVisibility(View.GONE);
        unlockPassword.setVisibility(View.GONE);
        unlockPattern.setVisibility(View.GONE);

        if ("PIN".equals(savedLockType)) {
            unlockPin.setVisibility(View.VISIBLE);
        } else if ("PASSWORD".equals(savedLockType)) {
            unlockPassword.setVisibility(View.VISIBLE);
        } else if ("PATTERN".equals(savedLockType)) {
            unlockPattern.setVisibility(View.VISIBLE);
        }
    }

    private void checkUnlock() {
        if (isLockedOut) return;

        String enteredValue = "";
        if ("PIN".equals(savedLockType)) {
            enteredValue = unlockPin.getText().toString();
        } else if ("PASSWORD".equals(savedLockType)) {
            enteredValue = unlockPassword.getText().toString();
        } else if ("PATTERN".equals(savedLockType)) {
            enteredValue = currentPattern;
        }

        if (savedLockValue.equals(enteredValue)) {
            SecurityHelper.updateLastActiveTime(this); // Reset timer on successful unlock
            if (verifyOnly) {
                setResult(RESULT_OK);
            } else {
                Intent intent = new Intent(this, SecretGalleryActivity.class);
                intent.putExtra(EXTRA_UNLOCKED_FLAG, true);
                startActivity(intent);
            }
            finish();
        } else {
            handleFailedAttempt();
        }
    }

    private void handleFailedAttempt() {
        failedAttempts++;
        Animation shake = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        View activeView = getActiveView();
        if (activeView != null) activeView.startAnimation(shake);

        errorText.setText("Incorrect lock entry");
        errorText.setVisibility(View.VISIBLE);

        if (failedAttempts >= 5) {
            startLockout();
        }
    }

    private View getActiveView() {
        if ("PIN".equals(savedLockType)) return unlockPin;
        if ("PASSWORD".equals(savedLockType)) return unlockPassword;
        if ("PATTERN".equals(savedLockType)) return unlockPattern;
        return null;
    }

    private void startLockout() {
        isLockedOut = true;
        new CountDownTimer(30000, 1000) {
            public void onTick(long millisUntilFinished) {
                errorText.setText("Locked out for " + (millisUntilFinished / 1000) + "s");
            }
            public void onFinish() {
                isLockedOut = false;
                failedAttempts = 0;
                errorText.setVisibility(View.INVISIBLE);
            }
        }.start();
    }
}