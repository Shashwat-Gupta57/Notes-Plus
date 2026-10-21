package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen viewer over the same items, in the same order, the gallery was showing.
 *
 * Tap hides every control and the system bars; swipe down (when not zoomed) drags the photo
 * away and closes; delete and "move back to gallery" can be undone for a few seconds.
 */
public class MediaPagerActivity extends VaultActivity implements MediaPagerAdapter.Host {
    private static final String KEY_CURRENT_POS = "current_pos";
    private static final String EXTRA_INITIAL_FILE = "initial_file";
    private static final String EXTRA_ALBUM_ID = "album_id";
    private static final String EXTRA_FAVOURITES = "favourites";
    private static final int UNDO_MS = 4000;

    private ViewPager2 viewPager;
    private MediaPagerAdapter adapter;
    private final List<SecretItem> items = new ArrayList<>();
    private AppDatabase database;
    private int savedPosition = -1;
    private View root, topBar, bottomBar;
    private TextView title, subtitle;
    private ImageButton btnFavourite;
    private WindowInsetsControllerCompat systemBars;
    private boolean chromeVisible = true;

    /** One item hidden from the pager whose delete can still be undone. */
    private SecretItem pendingDelete;
    private int pendingDeleteIndex;
    private Snackbar undoBar;

    // Swipe-to-dismiss tracking.
    private float downX, downY;
    private boolean dismissing;
    private VelocityTracker velocity;
    private int touchSlop;

    public static Intent intent(Context context, int vaultId, String initialFileName,
                                Integer albumId, boolean favourites) {
        return new Intent(context, MediaPagerActivity.class)
                .putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId)
                .putExtra(EXTRA_INITIAL_FILE, initialFileName)
                .putExtra(EXTRA_ALBUM_ID, albumId == null ? -1 : albumId)
                .putExtra(EXTRA_FAVOURITES, favourites);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_pager);
        database = AppDatabase.getInstance(this);
        if (savedInstanceState != null) savedPosition = savedInstanceState.getInt(KEY_CURRENT_POS, -1);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        root = findViewById(R.id.viewerRoot);
        viewPager = findViewById(R.id.mediaViewPager);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        title = findViewById(R.id.viewerTitle);
        subtitle = findViewById(R.id.viewerSubtitle);
        btnFavourite = findViewById(R.id.actionFavourite);

        // The backdrop fades during swipe-to-dismiss; mutate so the shared drawable isn't touched.
        root.setBackground(root.getBackground().mutate());
        drawEdgeToEdge();

        findViewById(R.id.pagerBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.actionShare).setOnClickListener(v -> shareCurrent());
        findViewById(R.id.actionInfo).setOnClickListener(v -> showInfo());
        findViewById(R.id.btnRestore).setOnClickListener(v -> moveCurrentToGallery());
        findViewById(R.id.btnDelete).setOnClickListener(v -> deleteCurrent());
        btnFavourite.setOnClickListener(v -> toggleFavourite());
        findViewById(R.id.actionAddToAlbum).setOnClickListener(v -> onAddToAlbumClicked());

        viewPager.setOffscreenPageLimit(1); // Neighbours are decoded before the swipe lands.
        viewPager.setPageTransformer(new MarginPageTransformer(
                Math.round(16 * getResources().getDisplayMetrics().density)));
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (adapter != null) adapter.onPageSelected(position);
                updateForPosition(position);
            }
        });

        loadItems();
    }

    private void drawEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        systemBars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        systemBars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        systemBars.setAppearanceLightStatusBars(false);
        systemBars.setAppearanceLightNavigationBars(false);
        int topBase = topBar.getPaddingTop(), bottomBase = bottomBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(topBar.getPaddingLeft(), topBase + bars.top, topBar.getPaddingRight(),
                    topBar.getPaddingBottom());
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), bottomBase + bars.bottom);
            return insets;
        });
    }

    private void loadItems() {
        int albumId = getIntent().getIntExtra(EXTRA_ALBUM_ID, -1);
        boolean favourites = getIntent().getBooleanExtra(EXTRA_FAVOURITES, false);
        String initialFile = getIntent().getStringExtra(EXTRA_INITIAL_FILE);
        AppDatabase.IO.execute(() -> {
            SecretDao dao = database.secretDao();
            List<SecretItem> rows = favourites ? dao.favouriteRows(vaultId)
                    : albumId >= 0 ? dao.albumRows(vaultId, albumId)
                    : dao.galleryRows(vaultId);
            int initial = 0;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).fileName.equals(initialFile)) {
                    initial = i;
                    break;
                }
            }
            final int start = savedPosition >= 0 ? savedPosition : initial;
            runOnUiThread(() -> {
                items.clear();
                items.addAll(rows);
                if (items.isEmpty()) {
                    finish();
                    return;
                }
                adapter = new MediaPagerAdapter(this, items, AppDatabase.IO, this);
                int position = Math.min(start, items.size() - 1);
                adapter.onPageSelected(position);
                viewPager.setAdapter(adapter);
                viewPager.setCurrentItem(position, false);
                updateForPosition(position);
            });
        });
    }

    private SecretItem current() {
        int pos = viewPager.getCurrentItem();
        return pos >= 0 && pos < items.size() ? items.get(pos) : null;
    }

    private void updateForPosition(int position) {
        if (position < 0 || position >= items.size()) return;
        SecretItem item = items.get(position);
        Date date = new Date(item.dateTaken);
        title.setText(new SecretGalleryAdapter.Labeler(System.currentTimeMillis())
                .label(startOfDay(item.dateTaken)));
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        long duration = item.durationMs > 0 ? item.durationMs
                : ThumbnailStore.get(this).peekDuration(item.fileName);
        subtitle.setText(item.isVideo && duration > 0
                ? time + "  ·  " + SecretGalleryAdapter.formatDuration(duration) : time);
        btnFavourite.setImageResource(item.isFavourite ? R.drawable.ic_heart_filled : R.drawable.ic_heart_empty);
    }

    private static long startOfDay(long millis) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // ================================================================= chrome

    @Override
    public void onPageTapped() {
        setChromeVisible(!chromeVisible);
    }

    @Override
    public void onControllerVisibility(boolean visible) {
        // The video controller and the bars appear and disappear together.
        if (visible != chromeVisible) setChromeVisible(visible);
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        float shift = visible ? 0 : 1;
        topBar.animate().alpha(visible ? 1 : 0).translationY(-topBar.getHeight() * shift * 0.5f)
                .setDuration(200).start();
        bottomBar.animate().alpha(visible ? 1 : 0).translationY(bottomBar.getHeight() * shift * 0.5f)
                .setDuration(200).start();
        topBar.setClickable(visible);
        bottomBar.setClickable(visible);
        setBarsEnabled(visible);
        if (visible) systemBars.show(WindowInsetsCompat.Type.systemBars());
        else systemBars.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void setBarsEnabled(boolean enabled) {
        int[] ids = {R.id.pagerBackButton, R.id.actionInfo, R.id.actionShare, R.id.actionFavourite,
                R.id.actionAddToAlbum, R.id.btnRestore, R.id.btnDelete};
        for (int id : ids) findViewById(id).setEnabled(enabled);
    }

    // ========================================================= swipe dismiss

    /**
     * A vertical drag on an unzoomed page carries the whole pager with the finger and fades
     * the backdrop; let go far enough (or fast enough) and the viewer closes.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                dismissing = false;
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (dismissing) return true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!dismissing && e.getPointerCount() == 1 && Math.abs(dy) > touchSlop * 2
                        && Math.abs(dy) > Math.abs(dx) * 1.6f && adapter != null
                        && !adapter.isZoomed(viewPager.getCurrentItem())) {
                    dismissing = true;
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    if (chromeVisible) setChromeVisible(false);
                }
                if (dismissing) {
                    followFinger(dy);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dismissing) {
                    float drag = e.getRawY() - downY;
                    float speed = 0;
                    if (velocity != null) {
                        velocity.addMovement(e);
                        velocity.computeCurrentVelocity(1000);
                        speed = velocity.getYVelocity();
                    }
                    finishDrag(drag, speed);
                    recycleVelocity();
                    dismissing = false;
                    return true;
                }
                recycleVelocity();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    private void followFinger(float dy) {
        float progress = Math.min(1f, Math.abs(dy) / (root.getHeight() * 0.5f));
        viewPager.setTranslationY(dy);
        float scale = 1f - 0.15f * progress;
        viewPager.setScaleX(scale);
        viewPager.setScaleY(scale);
        root.getBackground().setAlpha((int) (255 * (1f - progress)));
    }

    private void finishDrag(float dy, float speed) {
        boolean far = Math.abs(dy) > root.getHeight() * 0.18f;
        boolean flung = Math.abs(speed) > 1800 && Math.signum(speed) == Math.signum(dy);
        if (far || flung) {
            viewPager.animate().translationY(Math.signum(dy) * root.getHeight()).alpha(0f)
                    .setDuration(180).withEndAction(() -> {
                        finish();
                        overridePendingTransition(0, android.R.anim.fade_out);
                    }).start();
            return;
        }
        viewPager.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        root.getBackground().setAlpha(255);
        setChromeVisible(true);
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    // ================================================================ actions

    private void toggleFavourite() {
        SecretItem item = current();
        if (item == null) return;
        boolean favourite = !item.isFavourite;
        int pos = viewPager.getCurrentItem();
        items.set(pos, new SecretItem(item.fileName, item.isVideo, item.dateTaken, favourite, item.durationMs));
        AppDatabase.IO.execute(() ->
                database.secretDao().setFavourite(Collections.singletonList(item.fileName), favourite));
        updateForPosition(pos);
        btnFavourite.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction(() ->
                btnFavourite.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
        btnFavourite.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void onAddToAlbumClicked() {
        SecretItem item = current();
        if (item == null) return;
        AppDatabase.IO.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.fileName);
            List<AlbumEntity> albums = database.albumDao().getAlbumsForVaultDirect(vaultId);
            Integer currentAlbum = entity == null ? null : entity.getAlbumId();
            runOnUiThread(() -> {
                List<String> labels = new ArrayList<>();
                labels.add("+ New album");
                for (AlbumEntity album : albums) {
                    labels.add(album.getId() == (currentAlbum == null ? -1 : currentAlbum)
                            ? album.getName() + "  ✓" : album.getName());
                }
                if (currentAlbum != null) labels.add("Remove from album");
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Album")
                        .setItems(labels.toArray(new String[0]), (d, which) -> {
                            if (which == 0) showCreateAlbumDialog(item);
                            else if (which <= albums.size()) setAlbum(item, albums.get(which - 1));
                            else setAlbum(item, null);
                        })
                        .show();
            });
        });
    }

    private void showCreateAlbumDialog(SecretItem item) {
        Prompts.text(this, "New album", null, "Album name", null, Prompts.TEXT, "Create", name ->
                AppDatabase.IO.execute(() -> {
                    AlbumEntity album = new AlbumEntity(name, vaultId, null);
                    album.setId((int) database.albumDao().insert(album));
                    runOnUiThread(() -> setAlbum(item, album));
                }), null);
    }

    private void setAlbum(SecretItem item, AlbumEntity album) {
        AppDatabase.IO.execute(() -> database.secretDao().setAlbum(
                Collections.singletonList(item.fileName), album == null ? null : album.getId()));
        Snackbar.make(root, album == null ? "Removed from album" : "Added to " + album.getName(),
                Snackbar.LENGTH_SHORT).show();
    }

    private void shareCurrent() {
        SecretItem item = current();
        if (item == null) return;
        expectExternalActivity();
        AppDatabase.IO.execute(() -> {
            try {
                SecretEntity entity = database.secretDao().getSecretByFileName(item.fileName);
                if (entity == null) return;
                File exported = VaultMedia.exportForShare(this, entity);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", exported);
                Intent intent = new Intent(Intent.ACTION_SEND)
                        .setType(item.isVideo ? "video/*" : "image/*")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(intent, "Share via")));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to share", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void moveCurrentToGallery() {
        SecretItem item = current();
        if (item == null) return;
        int position = viewPager.getCurrentItem();
        AppDatabase.IO.execute(() -> {
            try {
                SecretEntity entity = database.secretDao().getSecretByFileName(item.fileName);
                if (entity == null) return;
                VaultMedia.restoreToDevice(this, entity);
                runOnUiThread(() -> {
                    removePage(position, item);
                    Snackbar.make(root, "Moved back to your gallery", Snackbar.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't move it back", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Removes the page now; the file is erased only when the undo bar goes away. */
    private void deleteCurrent() {
        SecretItem item = current();
        if (item == null) return;
        commitPendingDelete();
        int position = viewPager.getCurrentItem();
        pendingDelete = item;
        pendingDeleteIndex = position;
        removePage(position, item);
        if (isFinishing()) return; // That was the last item; onStop commits it.

        undoBar = Snackbar.make(root, "Deleted", UNDO_MS)
                .setAction("Undo", v -> {
                    if (pendingDelete != item) return;
                    pendingDelete = null;
                    int at = Math.min(pendingDeleteIndex, items.size());
                    items.add(at, item);
                    adapter.notifyItemInserted(at);
                    viewPager.setCurrentItem(at, true);
                    updateForPosition(at);
                })
                .addCallback(new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar bar, int event) {
                        if (event != DISMISS_EVENT_ACTION && pendingDelete == item) commitPendingDelete();
                    }
                });
        undoBar.show();
    }

    private void commitPendingDelete() {
        final SecretItem item = pendingDelete;
        if (item == null) return;
        pendingDelete = null;
        AppDatabase.IO.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.fileName);
            if (entity != null) VaultMedia.remove(getApplicationContext(), entity);
        });
    }

    private void removePage(int position, SecretItem item) {
        if (position < 0 || position >= items.size() || items.get(position) != item) return;
        items.remove(position);
        if (items.isEmpty()) {
            finish();
            return;
        }
        adapter.notifyItemRemoved(position);
        int now = viewPager.getCurrentItem();
        adapter.onPageSelected(now);
        updateForPosition(now);
    }

    private void showInfo() {
        SecretItem item = current();
        if (item == null) return;
        AppDatabase.IO.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.fileName);
            File file = new File(VaultMedia.vaultDir(this), item.fileName);
            if (entity == null) return;
            SimpleDateFormat full = new SimpleDateFormat("EEEE, d MMMM yyyy  ·  HH:mm", Locale.getDefault());
            StringBuilder info = new StringBuilder();
            info.append(item.isVideo ? "Video" : "Photo");
            if (item.isVideo && item.durationMs > 0) {
                info.append("  ·  ").append(SecretGalleryAdapter.formatDuration(item.durationMs));
            }
            info.append("  ·  ").append(GallerySettingsActivity.formatBytes(file.length()));
            info.append("\n\nTaken\n").append(full.format(new Date(entity.getDateTaken())));
            info.append("\n\nAdded to vault\n").append(full.format(new Date(entity.getDateAdded())));
            if (entity.getOriginalPath() != null) {
                info.append("\n\nOriginally\n").append(entity.getOriginalPath());
            }
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                    .setTitle("Details")
                    .setMessage(info.toString())
                    .setPositiveButton("Done", null)
                    .show());
        });
    }

    // ============================================================= lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        if (adapter != null) adapter.pauseAll();
    }

    @Override
    protected void onStop() {
        super.onStop();
        commitPendingDelete(); // Leaving ends the undo window.
        if (undoBar != null) undoBar.dismiss();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (viewPager != null) outState.putInt(KEY_CURRENT_POS, viewPager.getCurrentItem());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recycleVelocity();
        if (adapter != null) adapter.releaseAll();
    }
}
