package org.flexstudios.notes.plus;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.tabs.TabLayout;

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
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}