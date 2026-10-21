package org.flexstudios.notes.plus;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base for every screen inside a vault.
 *
 * Owns the three things each of them must do and previously did inconsistently: block
 * screenshots and the recents thumbnail, know which vault is open, and lock again after the
 * auto-lock delay. Only the gallery used to check the delay, so leaving the app from the
 * viewer or settings and coming back an hour later showed the vault without asking.
 */
public abstract class VaultActivity extends AppCompatActivity {

    /** "Immediately" still tolerates rotation and screen-to-screen hops. */
    private static final long MIN_LOCK_DELAY_MS = 2_000;
    /** Longest trip to a picker or share sheet that does not count as leaving the vault. */
    private static final long EXTERNAL_GRACE_MS = 10 * 60_000;

    protected int vaultId = 1;
    private boolean externalActivityExpected;

    /** Which hidden space this screen belongs to: "" (gallery) or {@link UnlockActivity#REALM_CHAT}. */
    protected String realm() {
        return "";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        vaultId = getIntent().getIntExtra(UnlockActivity.EXTRA_VAULT_ID, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        long away = System.currentTimeMillis() - SecurityHelper.getLastActiveTime(this, realm());
        long limit = Math.max(SecurityHelper.getAutoLockDelay(this, realm()), MIN_LOCK_DELAY_MS);
        if (externalActivityExpected) limit = Math.max(limit, EXTERNAL_GRACE_MS);
        externalActivityExpected = false;

        // A clock set backwards makes "away" negative; treat that as suspicious, not recent.
        if (away > limit || away < 0) {
            relock();
            return;
        }
        SecurityHelper.updateLastActiveTime(this, realm());
    }

    @Override
    protected void onPause() {
        super.onPause();
        SecurityHelper.updateLastActiveTime(this, realm());
    }

    /**
     * Call right before handing off to another app (picker, share sheet, sign-in), so the
     * time spent there is not mistaken for the user walking away.
     */
    protected void expectExternalActivity() {
        externalActivityExpected = true;
    }

    /** Drops every vault screen and returns to the notes list, which then asks for the lock. */
    protected void relock() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_RELOCK, true)
                .putExtra(UnlockActivity.EXTRA_REALM, realm()));
        finish();
        overridePendingTransition(0, 0);
    }
}
