package org.flexstudios.notes.plus;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SecretGalleryActivity extends AppCompatActivity {
    private static final int PICK_FILE = 100;
    private static final int MANAGE_STORAGE_REQUEST = 102;
    
    private SecretGalleryAdapter adapter;
    private List<SecretItem> secretItems = new ArrayList<>();
    private View selectionBottomBar;
    private LinearLayout emptyState;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private File vaultDir;
    private AppDatabase database;
    private boolean skipAutoLockOnce = false;
    private boolean isAllSelected = false;
    private int currentVaultId = 1;

    private ImageView vaultBackground;
    private View blurCircle, galleryRoot;
    private PlayerView videoBgView;
    private ExoPlayer exoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_secret_gallery);

        database = AppDatabase.getInstance(this);
        currentVaultId = getIntent().getIntExtra(UnlockActivity.EXTRA_VAULT_ID, 1);
        handleIntent(getIntent());

        Toolbar toolbar = findViewById(R.id.toolbarGallery);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Notes+"); // Always Notes+
        }

        vaultDir = new File(getFilesDir(), "vault");
        if (!vaultDir.exists()) vaultDir.mkdirs();
        createNoMediaFile();

        vaultBackground = findViewById(R.id.vaultBackground);
        blurCircle = findViewById(R.id.blurCircle);
        galleryRoot = findViewById(R.id.galleryRoot);
        videoBgView = findViewById(R.id.videoBgView);

        selectionBottomBar = findViewById(R.id.selectionBottomBar);
        emptyState = findViewById(R.id.emptyStateGallery);
        RecyclerView recyclerView = findViewById(R.id.recyclerViewGallery);
        
        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return (adapter != null && adapter.getItemViewType(position) == 0) ? 3 : 1;
            }
        });
        recyclerView.setLayoutManager(layoutManager);
        
        adapter = new SecretGalleryAdapter(secretItems, new ArrayList<>(), new SecretGalleryAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(SecretItem item) {
                Intent intent = new Intent(SecretGalleryActivity.this, MediaPagerActivity.class);
                intent.putExtra("initial_file_path", item.getFile().getAbsolutePath());
                intent.putExtra(UnlockActivity.EXTRA_VAULT_ID, currentVaultId);
                startActivity(intent);
            }

            @Override
            public void onItemLongClick(SecretItem item) { }

            @Override
            public void onSelectionChanged(int count) {
                if (selectionBottomBar != null) {
                    selectionBottomBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                }
                invalidateOptionsMenu();
            }

            @Override
            public void onAllItemsSelectionChanged(boolean allSelected) {
                isAllSelected = allSelected;
                invalidateOptionsMenu();
            }
        });
        recyclerView.setAdapter(adapter);

        findViewById(R.id.fabAddSecret).setOnClickListener(v -> {
            if (checkStoragePermission()) pickFile();
        });

        findViewById(R.id.btnSelectionShare).setOnClickListener(v -> shareSelectedItems());
        findViewById(R.id.btnSelectionRestore).setOnClickListener(v -> restoreSelectedItems());
        findViewById(R.id.btnSelectionDelete).setOnClickListener(v -> deleteSelectedItems());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter != null && adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    return;
                }
                Intent intent = new Intent(SecretGalleryActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
                overridePendingTransition(0, 0);
            }
        });

        observeVault();
        checkStoragePermission();
    }

    private void applyVaultBackground() {
        Executors.newSingleThreadExecutor().execute(() -> {
            VaultEntity vault = database.vaultDao().getVaultById(currentVaultId);
            if (vault != null) {
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
                    } else if ("IMAGE".equals(type) && value != null) {
                        File imgFile = new File(value);
                        if (imgFile.exists()) {
                            vaultBackground.setVisibility(View.VISIBLE);
                            blurCircle.setVisibility(View.GONE);
                            videoBgView.setVisibility(View.GONE);
                            galleryRoot.setBackgroundColor(Color.TRANSPARENT);
                            Glide.with(this).load(imgFile).into(vaultBackground);
                        } else {
                            restoreDefaultBackground();
                        }
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
            }
        });
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
        galleryRoot.setBackgroundColor(Color.parseColor("#F2F2F7"));
        vaultBackground.setVisibility(View.GONE);
        videoBgView.setVisibility(View.GONE);
        blurCircle.setVisibility(View.VISIBLE);
        stopVideoBackground();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(UnlockActivity.EXTRA_UNLOCKED_FLAG, false)) {
            skipAutoLockOnce = true;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!skipAutoLockOnce) {
            checkAutoLock();
        }
        skipAutoLockOnce = false;
        SecurityHelper.updateLastActiveTime(this);
        applyVaultBackground();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SecurityHelper.updateLastActiveTime(this);
        stopVideoBackground();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVideoBackground();
    }

    private void checkAutoLock() {
        long delay = SecurityHelper.getAutoLockDelay(this);
        long lastActive = SecurityHelper.getLastActiveTime(this);
        long now = System.currentTimeMillis();
        long effectiveDelay = (delay == 0) ? 2000 : delay;
        if (lastActive > 0 && (now - lastActive) > effectiveDelay) {
            Intent intent = new Intent(this, UnlockActivity.class);
            intent.putExtra(UnlockActivity.EXTRA_VAULT_ID, currentVaultId);
            startActivity(intent);
            finish();
        }
    }

    private void observeVault() {
        database.secretDao().getSecretsForVault(currentVaultId).observe(this, entities -> {
            executor.execute(() -> {
                List<SecretItem> newItems = new ArrayList<>();
                for (SecretEntity entity : entities) {
                    File file = new File(vaultDir, entity.getFileName());
                    if (file.exists()) {
                        newItems.add(new SecretItem(file, entity.isVideo()));
                    }
                }
                runOnUiThread(() -> {
                    secretItems.clear();
                    secretItems.addAll(newItems);
                    if (adapter != null) {
                        adapter.updateData(secretItems, entities);
                    }
                    emptyState.setVisibility(entities.isEmpty() ? View.VISIBLE : View.GONE);
                });
            });
        });
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("image/* video/*");
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_STORAGE_REQUEST) {
            if (checkStoragePermission()) Toast.makeText(this, "Permission Granted!", Toast.LENGTH_SHORT).show();
        } else if (resultCode == RESULT_OK && data != null) {
            if (requestCode == PICK_FILE) {
                if (data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    List<Uri> uris = new ArrayList<>();
                    for (int i = 0; i < clipData.getItemCount(); i++) uris.add(clipData.getItemAt(i).getUri());
                    processUris(uris);
                } else if (data.getData() != null) {
                    processUris(Collections.singletonList(data.getData()));
                }
            }
        }
    }

    private void processUris(List<Uri> uris) {
        AtomicInteger processedCount = new AtomicInteger(0);
        int total = uris.size();
        for (Uri uri : uris) {
            executor.execute(() -> {
                try {
                    String mimeType = getContentResolver().getType(uri);
                    boolean isVideo = mimeType != null && mimeType.startsWith("video");
                    String originalPath = getActualPath(uri);
                    long dateTaken = recoverOriginalDate(uri, originalPath, isVideo);
                    long dateAdded = System.currentTimeMillis();
                    String fileName = (isVideo ? "VID_" : "IMG_") + dateAdded + "_" + processedCount.get();
                    File encryptedFile = new File(vaultDir, fileName);
                    String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                    EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                            encryptedFile, this, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                    ).build();

                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = encryptedStorage.openFileOutput()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                    }
                    SecretEntity entity = new SecretEntity(fileName, originalPath, dateTaken, dateAdded, isVideo, currentVaultId);
                    database.secretDao().insert(entity);

                    if (originalPath != null) {
                        File originalFile = new File(originalPath);
                        if (originalFile.exists()) {
                            originalFile.delete();
                            Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                            scanIntent.setData(Uri.fromFile(originalFile));
                            sendBroadcast(scanIntent);
                        }
                    }
                    if (processedCount.incrementAndGet() == total) {
                        runOnUiThread(() -> Toast.makeText(this, "Secured " + total + " items", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) { e.printStackTrace(); }
            });
        }
    }

    private String getActualPath(Uri uri) {
        String path = null;
        String[] projection = { MediaStore.MediaColumns.DATA };
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (index != -1) path = cursor.getString(index);
            }
        }
        return path;
    }

    private long recoverOriginalDate(Uri uri, String path, boolean isVideo) {
        if (!isVideo) {
            try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                if (inputStream != null) {
                    ExifInterface exif = new ExifInterface(inputStream);
                    String dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    if (dateString == null) dateString = exif.getAttribute(ExifInterface.TAG_DATETIME);
                    if (dateString != null) {
                        SimpleDateFormat fmt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());
                        Date d = fmt.parse(dateString);
                        if (d != null) return d.getTime();
                    }
                }
            } catch (Exception e) {}
        }
        if (path != null && path.toLowerCase().contains("whatsapp")) {
            Pattern waPattern = Pattern.compile("(IMG|VID)-(\\d{8})-WA");
            Matcher matcher = waPattern.matcher(new File(path).getName());
            if (matcher.find()) {
                try {
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
                    Date d = fmt.parse(matcher.group(2));
                    if (d != null) return d.getTime();
                } catch (Exception e) {}
            }
        }
        if (path != null) {
            File f = new File(path);
            if (f.exists()) return f.lastModified();
        }
        return System.currentTimeMillis();
    }

    private void shareSelectedItems() {
        List<SecretItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        executor.execute(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            try {
                for (SecretItem item : selected) {
                    SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
                    String ext = item.isVideo() ? ".mp4" : ".jpg";
                    if (entity != null && entity.getOriginalPath() != null) {
                        String path = entity.getOriginalPath();
                        int lastDot = path.lastIndexOf('.');
                        if (lastDot != -1) ext = path.substring(lastDot);
                    }
                    File tempFile = decryptToCache(item.getFile(), ext);
                    uris.add(FileProvider.getUriForFile(this, getPackageName() + ".provider", tempFile));
                }
                
                Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Share " + selected.size() + " items"));
                
                runOnUiThread(() -> adapter.setSelectionMode(false));
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void restoreSelectedItems() {
        List<SecretItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        executor.execute(() -> {
            int count = 0;
            for (SecretItem item : selected) {
                SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
                if (entity != null && entity.getOriginalPath() != null) {
                    try {
                        File target = new File(entity.getOriginalPath());
                        File parent = target.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        
                        decryptToFile(item.getFile(), target);
                        if (entity.getDateTaken() != 0) target.setLastModified(entity.getDateTaken());
                        
                        database.secretDao().delete(entity);
                        item.getFile().delete();
                        count++;
                    } catch (Exception e) { e.printStackTrace(); }
                }
            }
            final int finalCount = count;
            runOnUiThread(() -> {
                Toast.makeText(this, "Restored " + finalCount + " items", Toast.LENGTH_SHORT).show();
                adapter.setSelectionMode(false);
            });
        });
    }

    private void deleteSelectedItems() {
        List<SecretItem> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle("Delete " + selected.size() + " items?")
                .setMessage("These files will be permanently erased.")
                .setPositiveButton("Delete", (d, w) -> {
                    executor.execute(() -> {
                        for (SecretItem item : selected) {
                            SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
                            if (entity != null) database.secretDao().delete(entity);
                            item.getFile().delete();
                        }
                        runOnUiThread(() -> adapter.setSelectionMode(false));
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private File decryptToCache(File encryptedFile, String extension) throws GeneralSecurityException, IOException {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                encryptedFile, this, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build();
        if (!extension.startsWith(".")) extension = "." + extension;
        File tempFile = new File(getCacheDir(), "share_" + encryptedFile.getName() + extension);
        try (InputStream in = encryptedStorage.openFileInput();
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            out.flush();
        }
        return tempFile;
    }

    private void decryptToFile(File encryptedFile, File target) throws GeneralSecurityException, IOException {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                encryptedFile, this, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build();
        try (InputStream in = encryptedStorage.openFileInput();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            out.flush();
        }
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showPermissionDialog();
                return false;
            }
        }
        return true;
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("To move files to the secure vault, Notes+ needs permission to manage files. Please enable it in the next screen.")
                .setPositiveButton("Settings", (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, MANAGE_STORAGE_REQUEST);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, MANAGE_STORAGE_REQUEST);
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void createNoMediaFile() {
        File noMedia = new File(vaultDir, ".nomedia");
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile();
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gallery_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem selectAllItem = menu.findItem(R.id.action_select_all);
        if (selectAllItem != null) {
            selectAllItem.setVisible(adapter != null && adapter.isSelectionMode());
            selectAllItem.setIcon(isAllSelected ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_empty);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, GallerySettingsActivity.class);
            intent.putExtra(UnlockActivity.EXTRA_VAULT_ID, currentVaultId);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_select_all) {
            if (adapter != null) {
                adapter.selectAll(!isAllSelected);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}