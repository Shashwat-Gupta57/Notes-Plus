package org.flexstudios.notes.plus;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;

/**
 * A chat photo (zoomable) or video, full screen. The file is downloaded if this phone doesn't
 * have it yet, decrypted (pictures into memory, a video into a temporary file deleted on
 * close), and never saved anywhere readable.
 */
public class ChatMediaActivity extends VaultActivity {

    static final String EXTRA_TAG = "media_tag";
    static final String EXTRA_META = "media_meta";

    private ExoPlayer player;
    private File plain;

    @Override
    protected String realm() {
        return UnlockActivity.REALM_CHAT;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        ProgressBar spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.setOnClickListener(v -> finish());

        String tag = getIntent().getStringExtra(EXTRA_TAG);
        ChatMedia.Meta meta = ChatMedia.Meta.parse(getIntent().getStringExtra(EXTRA_META));
        if (tag == null || meta == null) {
            finish();
            return;
        }
        AppDatabase.IO.execute(() -> {
            try {
                if (meta.isVideo()) {
                    File file = ChatMedia.decryptToTemp(this, tag, meta);
                    runOnUiThread(() -> {
                        plain = file;
                        root.removeView(spinner);
                        play(root, file);
                    });
                } else {
                    byte[] bytes = ChatMedia.decryptBytes(this, tag, meta);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    int screen = Math.max(getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    options.inSampleSize = 1;
                    while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= screen * 2) {
                        options.inSampleSize *= 2;
                    }
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    runOnUiThread(() -> {
                        root.removeView(spinner);
                        PhotoView photo = new PhotoView(this);
                        photo.setImageBitmap(bitmap);
                        photo.setOnViewTapListener((v, x, y) -> finish());
                        root.addView(photo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    root.removeView(spinner);
                    TextView error = new TextView(this);
                    error.setTextColor(Color.WHITE);
                    error.setGravity(Gravity.CENTER);
                    error.setPadding(48, 48, 48, 48);
                    error.setText("Couldn't open this.\n" + (e.getMessage() == null ? "" : e.getMessage()));
                    root.addView(error, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                });
            }
        });
    }

    private void play(FrameLayout root, File file) {
        player = new ExoPlayer.Builder(this).build();
        PlayerView view = new PlayerView(this);
        view.setPlayer(player);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
        player.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) player.release();
        if (plain != null) VaultCrypto.deleteRecursive(plain);
    }
}
