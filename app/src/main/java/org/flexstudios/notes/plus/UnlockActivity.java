package org.flexstudios.notes.plus;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import java.util.List;
import java.util.concurrent.Executors;

public class UnlockActivity extends AppCompatActivity implements UnlockPagerAdapter.OnUnlockAttemptListener {
    public static final String EXTRA_VERIFY_ONLY = "verify_only";
    public static final String EXTRA_UNLOCKED_FLAG = "is_unlocked";
    public static final String EXTRA_VAULT_ID = "vault_id";
    
    private ViewPager2 viewPager;
    private TextView errorText;
    private int failedAttempts = 0;
    private boolean isLockedOut = false;
    private boolean verifyOnly = false;
    private List<VaultEntity> allVaults;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_unlock);

        verifyOnly = getIntent().getBooleanExtra(EXTRA_VERIFY_ONLY, false);
        viewPager = findViewById(R.id.unlockViewPager);
        errorText = findViewById(R.id.errorText);

        UnlockPagerAdapter adapter = new UnlockPagerAdapter(this);
        viewPager.setAdapter(adapter);
        
        loadVaults();
    }

    private void loadVaults() {
        Executors.newSingleThreadExecutor().execute(() -> {
            allVaults = AppDatabase.getInstance(this).vaultDao().getAllVaultsDirect();
            VaultEntity mainVault = AppDatabase.getInstance(this).vaultDao().getMainVault();
            
            runOnUiThread(() -> {
                if (mainVault != null) {
                    String type = mainVault.getLockType();
                    if ("PIN".equals(type)) viewPager.setCurrentItem(0, false);
                    else if ("PASSWORD".equals(type)) viewPager.setCurrentItem(1, false);
                    else if ("PATTERN".equals(type)) viewPager.setCurrentItem(2, false);
                }
            });
        });
    }

    @Override
    public void onUnlockAttempt(String type, String value) {
        if (isLockedOut || allVaults == null) return;

        VaultEntity matchedVault = null;
        for (VaultEntity v : allVaults) {
            if (type.equals(v.getLockType()) && value.equals(v.getLockValue())) {
                matchedVault = v;
                break;
            }
        }

        if (matchedVault != null) {
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

    private void handleFailedAttempt() {
        failedAttempts++;
        errorText.setText("Incorrect"); // Generic message
        errorText.setVisibility(View.VISIBLE);

        if (failedAttempts >= 5) {
            startLockout();
        }
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

    public void setSwipeEnabled(boolean enabled) {
        viewPager.setUserInputEnabled(enabled);
    }
}