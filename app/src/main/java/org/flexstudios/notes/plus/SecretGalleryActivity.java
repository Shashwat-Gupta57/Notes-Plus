package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The vault: a grid of everything in it, its albums, and the actions on a selection.
 *
 * The grid holds the whole filter as light rows and diffs every change (see
 * {@link SecretGalleryAdapter}); gestures live in {@link GalleryGestures}. Deletes are
 * reversible for a few seconds: items vanish at once, and are erased when the undo bar goes
 * away or the screen is left.
 */
public class SecretGalleryActivity extends VaultActivity {
    private static final String TAG = "SecretGallery";
    private static final int PICK_FILE = 100;
    private static final int DELETE_ORIGINALS = 101;
    private static final int WRITE_PERMISSION = 102;

    private static final String PREF_COLUMNS = "gallery_columns";
    private static final int DEFAULT_COLUMNS = 4;
    /** Rows of thumbnails warmed ahead of the scroll direction. */
    private static final int PREFETCH_ROWS = 4;
    private static final int UNDO_MS = 5000;

    private final ExecutorService executor = AppDatabase.IO;
    private AppDatabase database;
    private ThumbnailStore thumbnails;
    private File vaultDir;

    private SecretGalleryAdapter adapter;
    private AlbumAdapter albumAdapter;
    private GridLayoutManager galleryLayout;
    private GalleryGestures.DragSelect dragSelect;
    private RecyclerView recyclerViewGallery, recyclerViewAlbums;
    private TabLayout tabLayout;
    private Toolbar toolbar;
    private View selectionBottomBar;
    private FloatingActionButton fab;
    private LinearLayout emptyState;

    private ImageView vaultBackground;
    private View blurCircle, galleryRoot;
    private PlayerView videoBgView;
    private ExoPlayer exoPlayer;

    private Integer filterAlbumId = null;
    private boolean filterFavourites = false;
    private String filterTitle = "Notes+";
    private boolean isAllSelected;
    /** Bumped by every load, so an older result arriving late is dropped. */
    private int loadGeneration;
    private final Set<String> prefetched = new HashSet<>();

    /** Hidden from the grid while their undo bar is up; erased once it goes. */
    private final Set<String> pendingDelete = new HashSet<>();
    private List<String> undoable;
    private Snackbar undoBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_secret_gallery);

        database = AppDatabase.getInstance(this);
        thumbnails = ThumbnailStore.get(this);
        vaultDir = VaultMedia.vaultDir(this);
        // Clears share copies from the last session, plaintext residue from older versions,
        // and any half-written file a crash left behind.
        executor.execute(() -> {
            VaultCrypto.sweep(this);
            VaultCrypto.removeIncompleteWrites(vaultDir);
        });

        toolbar = findViewById(R.id.toolbarGallery);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());
        vaultBackground = findViewById(R.id.vaultBackground);
        blurCircle = findViewById(R.id.blurCircle);
        galleryRoot = findViewById(R.id.galleryRoot);
        videoBgView = findViewById(R.id.videoBgView);
        selectionBottomBar = findViewById(R.id.selectionBottomBar);
        emptyState = findViewById(R.id.emptyStateGallery);
        recyclerViewGallery = findViewById(R.id.recyclerViewGallery);
        recyclerViewAlbums = findViewById(R.id.recyclerViewAlbums);
        tabLayout = findViewById(R.id.tabLayoutGallery);
        fab = findViewById(R.id.fabAddSecret);

        setUpGrid();
        setUpAlbums();
        updateChrome();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                boolean grid = tab.getPosition() == 0;
                recyclerViewGallery.setVisibility(grid ? View.VISIBLE : View.GONE);
                recyclerViewAlbums.setVisibility(grid ? View.GONE : View.VISIBLE);
                if (grid) loadGallery(false);
                else loadAlbums();
                invalidateOptionsMenu();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) recyclerViewGallery.smoothScrollToPosition(0);
            }
        });

        fab.setOnClickListener(v -> {
            if (tabLayout.getSelectedTabPosition() == 0 || filterAlbumId != null || filterFavourites) {
                pickFile();
            } else {
                showCreateAlbumDialog(null);
            }
        });

        findViewById(R.id.btnSelectionShare).setOnClickListener(v -> share(adapter.selectedNames()));
        findViewById(R.id.btnSelectionRestore).setOnClickListener(v -> restore(adapter.selectedNames()));
        findViewById(R.id.btnSelectionDelete).setOnClickListener(v -> deleteWithUndo(adapter.selectedNames()));
        findViewById(R.id.btnSelectionMoveToAlbum).setOnClickListener(v -> showAlbumListDialog(adapter.selectedNames()));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        loadGallery(true);
    }

    // =================================================================== grid

    private void setUpGrid() {
        int columns = SecurityHelper.getAppPrefs(this).getInt(PREF_COLUMNS, DEFAULT_COLUMNS);
        galleryLayout = new GridLayoutManager(this, columns);
        galleryLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == SecretGalleryAdapter.TYPE_HEADER
                        ? galleryLayout.getSpanCount() : 1;
            }
        });
        recyclerViewGallery.setLayoutManager(galleryLayout);
        recyclerViewGallery.setHasFixedSize(true);
        recyclerViewGallery.setItemViewCacheSize(24);
        recyclerViewGallery.getRecycledViewPool().setMaxRecycledViews(SecretGalleryAdapter.TYPE_ITEM, 80);

        int gap = Math.round(2 * getResources().getDisplayMetrics().density);
        recyclerViewGallery.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                out.set(gap / 2, gap / 2, gap / 2, gap / 2);
            }
        });
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false); // Selection is animated by the cell itself.
        animator.setAddDuration(180);
        animator.setRemoveDuration(180);
        animator.setMoveDuration(220);
        recyclerViewGallery.setItemAnimator(animator);

        adapter = new SecretGalleryAdapter(thumbnails, new SecretGalleryAdapter.Listener() {
            @Override
            public void onItemClick(SecretItem item, View cell) {
                ActivityOptionsCompat zoom = ActivityOptionsCompat.makeScaleUpAnimation(
                        cell, 0, 0, cell.getWidth(), cell.getHeight());
                startActivity(MediaPagerActivity.intent(SecretGalleryActivity.this, vaultId,
                        item.fileName, filterAlbumId, filterFavourites), zoom.toBundle());
            }

            @Override
            public void onSelectionStarted(int position) {
                dragSelect.start(position);
            }

            @Override
            public void onSelectionChanged(int count, boolean allSelected) {
                isAllSelected = allSelected;
                updateChrome();
            }
        });
        recyclerViewGallery.setAdapter(adapter);

        recyclerViewGallery.addOnItemTouchListener(new GalleryGestures.PinchColumns(
                recyclerViewGallery, galleryLayout, columnsNow -> {
                    SecurityHelper.getAppPrefs(this).edit().putInt(PREF_COLUMNS, columnsNow).apply();
                    updateCellSize();
                }));
        dragSelect = new GalleryGestures.DragSelect(recyclerViewGallery, adapter);
        recyclerViewGallery.addOnItemTouchListener(dragSelect);
        GalleryGestures.FastScroller.attach(recyclerViewGallery, adapter::labelAt);

        recyclerViewGallery.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) updateCellSize();
        });
        recyclerViewGallery.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                prefetchAhead(dy);
            }
        });
    }

    /** Cells are square: their height follows the column width. */
    private void updateCellSize() {
        int width = recyclerViewGallery.getWidth() - recyclerViewGallery.getPaddingLeft()
                - recyclerViewGallery.getPaddingRight();
        if (width <= 0) return;
        int gap = Math.round(2 * getResources().getDisplayMetrics().density);
        adapter.setCellSize(width / galleryLayout.getSpanCount() - gap);
    }

    /** Decrypts thumbnails a few rows ahead, so a steady scroll never shows a blank cell. */
    private void prefetchAhead(int dy) {
        if (dy == 0) return;
        int span = galleryLayout.getSpanCount();
        int from = dy > 0 ? galleryLayout.findLastVisibleItemPosition() + 1
                : galleryLayout.findFirstVisibleItemPosition() - span * PREFETCH_ROWS;
        for (int i = Math.max(from, 0); i < from + span * PREFETCH_ROWS; i++) {
            Object row = adapter.rowAt(i);
            if (row instanceof SecretItem && prefetched.add(((SecretItem) row).fileName)) {
                thumbnails.prefetch((SecretItem) row);
            }
        }
    }

    /**
     * Reads the current filter in one indexed query and diffs it into the grid. Cheap enough
     * to run on every resume: an unchanged vault produces an empty diff and no redraw.
     */
    private void loadGallery(boolean scrollToTop) {
        final int generation = ++loadGeneration;
        final Integer albumFilter = filterAlbumId;
        final boolean favouritesFilter = filterFavourites;
        final Set<String> hidden = new HashSet<>(pendingDelete);
        executor.execute(() -> {
            SecretDao dao = database.secretDao();
            List<SecretItem> rows = favouritesFilter ? dao.favouriteRows(vaultId)
                    : albumFilter != null ? dao.albumRows(vaultId, albumFilter)
                    : dao.galleryRows(vaultId);
            if (!hidden.isEmpty()) {
                List<SecretItem> visible = new ArrayList<>(rows.size());
                for (SecretItem row : rows) if (!hidden.contains(row.fileName)) visible.add(row);
                rows = visible;
            }
            SecretGalleryAdapter.Model model = SecretGalleryAdapter.build(rows, System.currentTimeMillis());
            runOnUiThread(() -> {
                if (generation != loadGeneration) return;
                adapter.submit(model, () -> {
                    emptyState.setVisibility(model.items.isEmpty() ? View.VISIBLE : View.GONE);
                    if (scrollToTop) galleryLayout.scrollToPosition(0);
                    updateCellSize();
                });
            });
        });
    }

    // ================================================================= chrome

    /** Title, navigation icon, selection bar and FAB, for the current mode. */
    private void updateChrome() {
        boolean selecting = adapter != null && adapter.isSelectionMode();
        boolean filtered = filterAlbumId != null || filterFavourites;
        if (selecting) {
            int count = adapter.selectedCount();
            toolbar.setTitle(count == 0 ? "Select items" : count + " selected");
            toolbar.setNavigationIcon(R.drawable.ic_close);
        } else {
            toolbar.setTitle(filterTitle);
            if (filtered) toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            else toolbar.setNavigationIcon(null);
        }
        slide(selectionBottomBar, selecting);
        if (selecting) fab.hide();
        else fab.show();
        invalidateOptionsMenu();
    }

    private static void slide(View bar, boolean show) {
        if (show == (bar.getVisibility() == View.VISIBLE && bar.getTag() == null)) return;
        bar.animate().cancel();
        if (show) {
            bar.setTag(null);
            bar.setVisibility(View.VISIBLE);
            bar.setTranslationY(bar.getHeight() > 0 ? bar.getHeight() * 1.5f : 300f);
            bar.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            bar.setTag("hiding");
            bar.animate().translationY(bar.getHeight() * 1.5f).setDuration(180)
                    .withEndAction(() -> {
                        bar.setVisibility(View.GONE);
                        bar.setTag(null);
                    }).start();
        }
    }

    /** Selection, then filter, then leave the vault for the notes list. */
    private void handleBack() {
        if (adapter.isSelectionMode()) {
            adapter.setSelectionMode(false);
            return;
        }
        if (filterAlbumId != null || filterFavourites) {
            filterAlbumId = null;
            filterFavourites = false;
            filterTitle = "Notes+";
            recyclerViewGallery.setVisibility(View.GONE);
            recyclerViewAlbums.setVisibility(View.VISIBLE);
            tabLayout.setVisibility(View.VISIBLE);
            tabLayout.selectTab(tabLayout.getTabAt(1));
            loadAlbums();
            updateChrome();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    // ================================================================ actions

    private void share(List<String> names) {
        if (names.isEmpty()) return;
        expectExternalActivity();
        executor.execute(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            try {
                // Retire copies exported by an earlier share before writing new ones.
                VaultCrypto.wipeContents(VaultCrypto.shareDir(this));
                for (SecretEntity entity : database.secretDao().getByFileNames(names)) {
                    File exported = VaultMedia.exportForShare(this, entity);
                    uris.add(FileProvider.getUriForFile(this, getPackageName() + ".provider", exported));
                }
                Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE)
                        .setType("*/*")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    startActivity(Intent.createChooser(intent, "Share " + names.size() + " items"));
                    adapter.setSelectionMode(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Share failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to share", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void restore(List<String> names) {
        if (names.isEmpty()) return;
        adapter.setSelectionMode(false);
        executor.execute(() -> {
            int count = 0;
            for (SecretEntity entity : database.secretDao().getByFileNames(names)) {
                try {
                    VaultMedia.restoreToDevice(this, entity);
                    count++;
                } catch (Exception e) {
                    Log.e(TAG, "Restore failed for " + entity.getFileName(), e);
                }
            }
            final int restored = count;
            runOnUiThread(() -> {
                Snackbar.make(galleryRoot, restored == names.size()
                        ? "Moved " + restored + (restored == 1 ? " item" : " items") + " back to your gallery"
                        : "Moved " + restored + " of " + names.size() + " back to your gallery",
                        Snackbar.LENGTH_LONG).show();
                loadGallery(false);
            });
        });
    }

    /** Hides the items now and erases them when the undo bar goes away. */
    private void deleteWithUndo(List<String> names) {
        if (names.isEmpty()) return;
        commitPendingDelete(); // One undoable batch at a time.
        adapter.setSelectionMode(false);
        undoable = names;
        pendingDelete.addAll(names);
        loadGallery(false);

        undoBar = Snackbar.make(galleryRoot,
                        names.size() == 1 ? "Item deleted" : names.size() + " items deleted", UNDO_MS)
                .setAction("Undo", v -> {
                    pendingDelete.removeAll(names);
                    undoable = null;
                    loadGallery(false);
                })
                .addCallback(new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar bar, int event) {
                        if (event != DISMISS_EVENT_ACTION && undoable == names) commitPendingDelete();
                    }
                });
        undoBar.show();
    }

    private void commitPendingDelete() {
        final List<String> names = undoable;
        if (names == null) return;
        undoable = null;
        executor.execute(() -> {
            for (SecretEntity entity : database.secretDao().getByFileNames(names)) {
                VaultMedia.remove(this, entity);
            }
            runOnUiThread(() -> pendingDelete.removeAll(names));
        });
    }

    // ================================================================= albums

    private void setUpAlbums() {
        recyclerViewAlbums.setLayoutManager(new GridLayoutManager(this, 2));
        albumAdapter = new AlbumAdapter(thumbnails, album -> {
            if (album.id == -1) showFilteredGallery(null, true, "Favourites");
            else showFilteredGallery(album.id, false, album.name);
        }, album -> {
            if (album.id != -1) showAlbumOptionsDialog(album);
        });
        recyclerViewAlbums.setAdapter(albumAdapter);
    }

    private void loadAlbums() {
        executor.execute(() -> {
            List<AlbumAdapter.AlbumItem> items = new ArrayList<>();
            SecretDao dao = database.secretDao();
            int favCount = dao.getFavouriteCount(vaultId);
            if (favCount > 0) {
                SecretEntity latestFav = dao.getLatestFavourite(vaultId);
                items.add(new AlbumAdapter.AlbumItem(-1, "Favourites", favCount,
                        latestFav != null ? latestFav.getFileName() : null,
                        latestFav != null && latestFav.isVideo()));
            }
            for (AlbumEntity album : database.albumDao().getAlbumsForVaultDirect(vaultId)) {
                SecretEntity latest = dao.getLatestForAlbum(album.getId());
                items.add(new AlbumAdapter.AlbumItem(album.getId(), album.getName(),
                        dao.getSecretCountForAlbum(album.getId()),
                        latest != null ? latest.getFileName() : null,
                        latest != null && latest.isVideo()));
            }
            runOnUiThread(() -> {
                albumAdapter.setAlbums(items);
                if (tabLayout.getSelectedTabPosition() == 1 && filterAlbumId == null && !filterFavourites) {
                    emptyState.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    private void showFilteredGallery(Integer albumId, boolean favs, String title) {
        filterAlbumId = albumId;
        filterFavourites = favs;
        filterTitle = title;
        recyclerViewGallery.setVisibility(View.VISIBLE);
        recyclerViewAlbums.setVisibility(View.GONE);
        tabLayout.setVisibility(View.GONE);
        updateChrome();
        loadGallery(true);
    }

    private void showAlbumOptionsDialog(AlbumAdapter.AlbumItem album) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(album.name)
                .setItems(new String[]{"Delete Album"}, (dialog, which) -> {
                    if (which == 0) deleteAlbum(album);
                })
                .show();
    }

    private void deleteAlbum(AlbumAdapter.AlbumItem album) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Album?")
                .setMessage("Items inside will not be deleted.")
                .setPositiveButton("Delete", (d, w) -> executor.execute(() -> {
                    database.secretDao().unsetAlbumId(album.id);
                    AlbumEntity entity = database.albumDao().getAlbumById(album.id);
                    if (entity != null) database.albumDao().delete(entity);
                    runOnUiThread(this::loadAlbums);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAlbumListDialog(List<String> names) {
        if (names.isEmpty()) return;
        executor.execute(() -> {
            List<AlbumEntity> albums = database.albumDao().getAlbumsForVaultDirect(vaultId);
            runOnUiThread(() -> {
                String[] labels = new String[albums.size() + 1];
                labels[0] = "+ New album";
                for (int i = 0; i < albums.size(); i++) labels[i + 1] = albums.get(i).getName();
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Add to album")
                        .setItems(labels, (dialog, which) -> {
                            if (which == 0) showCreateAlbumDialog(names);
                            else moveToAlbum(names, albums.get(which - 1));
                        })
                        .show();
            });
        });
    }

    private void showCreateAlbumDialog(List<String> names) {
        Prompts.text(this, "New album", null, "Album name", null, Prompts.TEXT, "Create", name ->
                executor.execute(() -> {
                    AlbumEntity album = new AlbumEntity(name, vaultId, null);
                    album.setId((int) database.albumDao().insert(album));
                    runOnUiThread(() -> {
                        if (names != null) moveToAlbum(names, album);
                        else loadAlbums();
                    });
                }), null);
    }

    private void moveToAlbum(List<String> names, AlbumEntity album) {
        adapter.setSelectionMode(false);
        executor.execute(() -> {
            database.secretDao().setAlbum(names, album.getId());
            runOnUiThread(() -> {
                Snackbar.make(galleryRoot, "Added to " + album.getName(), Snackbar.LENGTH_SHORT).show();
                loadGallery(false);
            });
        });
    }

    // ============================================================ background

    private void applyVaultBackground() {
        executor.execute(() -> {
            VaultEntity vault = database.vaultDao().getVaultById(vaultId);
            if (vault == null) return;
            runOnUiThread(() -> {
                String type = vault.getBgType();
                String value = vault.getBgValue();
                stopVideoBackground();

                if ("COLOR".equals(type) && value != null) {
                    try {
                        galleryRoot.setBackgroundColor(Color.parseColor(value));
                        vaultBackground.setVisibility(View.GONE);
                        blurCircle.setVisibility(View.GONE);
                        videoBgView.setVisibility(View.GONE);
                    } catch (Exception e) {
                        restoreDefaultBackground();
                    }
                } else if ("IMAGE".equals(type) && value != null && new File(value).exists()) {
                    vaultBackground.setVisibility(View.VISIBLE);
                    blurCircle.setVisibility(View.GONE);
                    videoBgView.setVisibility(View.GONE);
                    galleryRoot.setBackgroundColor(Color.TRANSPARENT);
                    Glide.with(this).load(new File(value)).into(vaultBackground);
                } else if (("VIDEO".equals(type) || "URL".equals(type)) && value != null) {
                    vaultBackground.setVisibility(View.GONE);
                    blurCircle.setVisibility(View.GONE);
                    videoBgView.setVisibility(View.VISIBLE);
                    galleryRoot.setBackgroundColor(Color.BLACK);
                    startVideoBackground(value);
                } else {
                    restoreDefaultBackground();
                }
            });
        });
    }

    private void startVideoBackground(String source) {
        if (exoPlayer != null) exoPlayer.release();
        exoPlayer = new ExoPlayer.Builder(this).build();
        videoBgView.setPlayer(exoPlayer);
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
        exoPlayer.setVolume(0f);
        Uri uri = source.startsWith("http") ? Uri.parse(source) : Uri.fromFile(new File(source));
        exoPlayer.setMediaItem(MediaItem.fromUri(uri));
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
        galleryRoot.setBackgroundColor(ContextCompat.getColor(this, R.color.gallery_background));
        vaultBackground.setVisibility(View.GONE);
        videoBgView.setVisibility(View.GONE);
        blurCircle.setVisibility(View.VISIBLE);
        stopVideoBackground();
    }

    // ============================================================= lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        applyVaultBackground();
        // The viewer may have deleted, favourited or moved items; the diff shows just that.
        if (recyclerViewGallery.getVisibility() == View.VISIBLE) loadGallery(false);
        else loadAlbums();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopVideoBackground();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the screen ends the undo window.
        commitPendingDelete();
        if (undoBar != null) undoBar.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVideoBackground();
    }

    // ================================================================ import

    private void pickFile() {
        // Up to Android 10 originals are removed through the file system (legacy storage),
        // which needs the write permission. From 11 on, MediaStore needs none.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERMISSION);
            return;
        }
        expectExternalActivity();
        // A type-only pick: gallery apps answer with MediaStore items, which is what lets the
        // originals be removed afterwards.
        Intent intent = new Intent(Intent.ACTION_PICK).setType("image/* video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WRITE_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pickFile();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DELETE_ORIGINALS) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, "The originals were kept in your gallery", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clipData = data.getClipData();
            for (int i = 0; i < clipData.getItemCount(); i++) uris.add(clipData.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        importUris(uris);
    }

    /**
     * Encrypts the picked items, then offers to remove the originals.
     *
     * Each vault file is durably written before its row is inserted, and an original is only
     * removed after its copy is safe. New items slide into the grid as each one lands.
     */
    private void importUris(List<Uri> uris) {
        if (uris.isEmpty()) return;
        final Integer albumId = filterAlbumId;
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("Securing items")
                .setMessage("0 of " + uris.size())
                .setCancelable(false)
                .show();

        executor.execute(() -> {
            List<Uri> originals = new ArrayList<>();
            int secured = 0;
            int unremovable = 0;
            long lastRefresh = 0;
            for (int i = 0; i < uris.size(); i++) {
                final int n = i + 1;
                runOnUiThread(() -> progress.setMessage(n + " of " + uris.size()));
                try {
                    VaultMedia.Imported result = VaultMedia.importItem(this, uris.get(i), vaultId, albumId);
                    secured++;
                    if (result.original != null) originals.add(result.original);
                    else unremovable++;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to secure item", e);
                }
                long now = System.currentTimeMillis();
                if (now - lastRefresh > 800) {
                    lastRefresh = now;
                    runOnUiThread(() -> loadGallery(false));
                }
            }
            if (secured > 0 && SecurityHelper.isAutoSyncEnabled(this)) SyncWorker.requestBackup(this);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) deleteOriginalsDirectly(originals);

            final int finalSecured = secured;
            final int finalUnremovable = unremovable;
            runOnUiThread(() -> {
                progress.dismiss();
                String message = "Secured " + finalSecured + " of " + uris.size() + " items";
                if (finalUnremovable > 0) {
                    message += ". Delete " + finalUnremovable + " original(s) from the app they came from";
                }
                Snackbar.make(galleryRoot, message, Snackbar.LENGTH_LONG).show();
                loadGallery(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestOriginalsDeletion(originals);
            });
        });
    }

    /** Android 11+: the system asks once for the whole batch; no storage permission needed. */
    private void requestOriginalsDeletion(List<Uri> originals) {
        if (originals.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), originals);
            expectExternalActivity();
            startIntentSenderForResult(request.getIntentSender(), DELETE_ORIGINALS, null, 0, 0, 0);
        } catch (Exception e) {
            Log.w(TAG, "Could not ask to remove the originals", e);
        }
    }

    /** Before Android 11 the legacy write permission lets us delete directly. */
    private void deleteOriginalsDirectly(List<Uri> originals) {
        for (Uri uri : originals) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception e) {
                Log.w(TAG, "Could not remove original " + uri, e);
            }
        }
    }

    // =================================================================== menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gallery_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean selecting = adapter != null && adapter.isSelectionMode();
        MenuItem selectAll = menu.findItem(R.id.action_select_all);
        if (selectAll != null) {
            selectAll.setVisible(selecting);
            selectAll.setIcon(isAllSelected ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_empty);
        }
        MenuItem settings = menu.findItem(R.id.action_settings);
        if (settings != null) settings.setVisible(!selecting);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, GallerySettingsActivity.class)
                    .putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId));
            return true;
        } else if (id == R.id.action_select_all) {
            adapter.selectAll(!isAllSelected);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
