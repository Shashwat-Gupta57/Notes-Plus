package org.flexstudios.notes.plus;

import android.os.Bundle;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

public class VideoPlayerActivity extends AppCompatActivity {
    private static final String KEY_VIDEO_POS = "video_pos";
    private ExoPlayer player;
    private PlayerView playerView;
    private File tempFile;
    private long playbackPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_video_player);

        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong(KEY_VIDEO_POS, 0);
        }

        playerView = findViewById(R.id.playerView);
        String filePath = getIntent().getStringExtra("file_path");

        if (filePath != null) {
            decryptAndPlay(new File(filePath));
        }
    }

    private void decryptAndPlay(File encryptedFile) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                    encryptedFile, this, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build();

            tempFile = new File(getCacheDir(), "temp_play_" + encryptedFile.getName());
            if (!tempFile.exists()) {
                try (InputStream in = encryptedStorage.openFileInput();
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                }
            }

            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            MediaItem mediaItem = MediaItem.fromUri(tempFile.getAbsolutePath());
            player.setMediaItem(mediaItem);
            player.setPlayWhenReady(true);
            player.seekTo(playbackPosition);
            player.prepare();

        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            outState.putLong(KEY_VIDEO_POS, player.getCurrentPosition());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
        }
        // Only delete if we are actually finishing, not just rotating
        if (isFinishing() && tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }
}