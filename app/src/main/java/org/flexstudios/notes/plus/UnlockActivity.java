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
import java.util.concurrent.Executors;

import jp.wasabeef.glide.transformations.BlurTransformation;

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

    private ImageView lockBackground;
    private View lockDimOverlay, unlockRoot;
    private PlayerView videoBgView;
    private ExoPlayer exoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
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
        unlockRoot.setBackgroundColor(Color.WHITE);
        lockBackground.setVisibility(View.GONE);
        lockDimOverlay.setVisibility(View.GONE);
        videoBgView.setVisibility(View.GONE);
        stopVideoBackground();
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

    @Override
    protected void onResume() {
        super.onResume();
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
        stopVideoBackground();
    }
}