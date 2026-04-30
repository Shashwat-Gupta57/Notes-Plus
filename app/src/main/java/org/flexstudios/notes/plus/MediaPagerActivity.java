package org.flexstudios.notes.plus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

public class MediaPagerActivity extends AppCompatActivity {
    private static final String KEY_CURRENT_POS = "current_pos";
    private ViewPager2 viewPager;
    private MediaPagerAdapter adapter;
    private final List<SecretItem> mediaItems = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AppDatabase database;
    private int savedPosition = -1;
    private int currentVaultId = 1;
    private ImageButton btnFavourite, btnAddToAlbum;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_media_pager);

        database = AppDatabase.getInstance(this);
        currentVaultId = getIntent().getIntExtra(UnlockActivity.EXTRA_VAULT_ID, 1);
        
        if (savedInstanceState != null) {
            savedPosition = savedInstanceState.getInt(KEY_CURRENT_POS, -1);
        }

        viewPager = findViewById(R.id.mediaViewPager);
        btnFavourite = findViewById(R.id.actionFavourite);
        btnAddToAlbum = findViewById(R.id.actionAddToAlbum);
        
        findViewById(R.id.pagerBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.actionShare).setOnClickListener(v -> shareCurrentItem());
        findViewById(R.id.actionInfo).setOnClickListener(v -> showInfo());
        findViewById(R.id.btnRestore).setOnClickListener(v -> restoreCurrentItem());
        findViewById(R.id.btnDelete).setOnClickListener(v -> deleteCurrentItem());
        
        btnFavourite.setOnClickListener(v -> toggleFavourite());
        btnAddToAlbum.setOnClickListener(v -> onAddToAlbumClicked());

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateUIForPosition(position);
            }
        });

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (Math.abs(velocityY) > Math.abs(velocityX) && Math.abs(velocityY) > 1000) {
                    finish();
                    return true;
                }
                return false;
            }
        });

        View recyclerView = viewPager.getChildAt(0);
        recyclerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });

        loadMedia();
    }

    private void onAddToAlbumClicked() {
        if (mediaItems.isEmpty()) return;
        int currentPos = viewPager.getCurrentItem();
        SecretItem currentItem = mediaItems.get(currentPos);

        executor.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(currentItem.getFile().getName());
            if (entity != null && entity.getAlbumId() != null) {
                AlbumEntity album = database.albumDao().getAlbumById(entity.getAlbumId());
                String albumName = (album != null) ? album.getName() : "an album";
                runOnUiThread(() -> showRemoveFromAlbumDialog(entity, albumName));
            } else {
                runOnUiThread(this::showAddToAlbumDialog);
            }
        });
    }

    private void showRemoveFromAlbumDialog(SecretEntity entity, String albumName) {
        new AlertDialog.Builder(this)
                .setTitle("Remove from Album")
                .setMessage("This item is already in \"" + albumName + "\". Do you want to remove it?")
                .setPositiveButton("Remove", (dialog, which) -> {
                    executor.execute(() -> {
                        entity.setAlbumId(null);
                        database.secretDao().update(entity);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Removed from album", Toast.LENGTH_SHORT).show();
                            updateUIForPosition(viewPager.getCurrentItem());
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddToAlbumDialog() {
        if (mediaItems.isEmpty()) return;
        int currentPos = viewPager.getCurrentItem();
        SecretItem currentItem = mediaItems.get(currentPos);

        executor.execute(() -> {
            List<AlbumEntity> allAlbums = database.albumDao().getAlbumsForVaultDirect(currentVaultId);
            runOnUiThread(() -> {
                String[] names = new String[allAlbums.size() + 1];
                names[0] = "+ Create New Album";
                for (int i = 0; i < allAlbums.size(); i++) names[i+1] = allAlbums.get(i).getName();

                new AlertDialog.Builder(this)
                        .setTitle("Add to Album")
                        .setItems(names, (dialog, which) -> {
                            if (which == 0) {
                                showCreateAlbumDialogForCurrent(currentItem);
                            } else {
                                moveItemToAlbum(currentItem, allAlbums.get(which - 1).getId());
                            }
                        })
                        .show();
            });
        });
    }

    private void showCreateAlbumDialogForCurrent(SecretItem item) {
        EditText input = new EditText(this);
        input.setHint("Album Name");
        new AlertDialog.Builder(this)
                .setTitle("New Album")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "New Album";
                    String finalName = name;
                    executor.execute(() -> {
                        AlbumEntity album = new AlbumEntity(finalName, currentVaultId, null);
                        int id = (int) database.albumDao().insert(album);
                        moveItemToAlbum(item, id);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moveItemToAlbum(SecretItem item, int albumId) {
        executor.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
            if (entity != null) {
                entity.setAlbumId(albumId);
                database.secretDao().update(entity);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Added to album", Toast.LENGTH_SHORT).show();
                    updateUIForPosition(viewPager.getCurrentItem());
                });
            }
        });
    }

    private void loadMedia() {
        executor.execute(() -> {
            List<SecretEntity> entities = database.secretDao().getSecretsForVaultDirect(currentVaultId);
            List<SecretItem> loadedItems = new ArrayList<>();
            File vaultDir = new File(getFilesDir(), "vault");
            for (SecretEntity entity : entities) {
                File f = new File(vaultDir, entity.getFileName());
                if (f.exists()) loadedItems.add(new SecretItem(f, entity.isVideo()));
            }
            
            runOnUiThread(() -> {
                mediaItems.clear();
                mediaItems.addAll(loadedItems);
                adapter = new MediaPagerAdapter(mediaItems);
                viewPager.setAdapter(adapter);

                if (savedPosition != -1) {
                    viewPager.setCurrentItem(savedPosition, false);
                } else {
                    String initialPath = getIntent().getStringExtra("initial_file_path");
                    if (initialPath != null) {
                        for (int i = 0; i < mediaItems.size(); i++) {
                            if (mediaItems.get(i).getFile().getAbsolutePath().equals(initialPath)) {
                                viewPager.setCurrentItem(i, false);
                                break;
                            }
                        }
                    }
                }
                updateUIForPosition(viewPager.getCurrentItem());
            });
        });
    }

    private void toggleFavourite() {
        if (mediaItems.isEmpty()) return;
        int pos = viewPager.getCurrentItem();
        SecretItem item = mediaItems.get(pos);
        executor.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
            if (entity != null) {
                entity.setFavourite(!entity.isFavourite());
                database.secretDao().update(entity);
                runOnUiThread(() -> {
                    btnFavourite.setImageResource(entity.isFavourite() ? R.drawable.ic_heart_filled : R.drawable.ic_heart_empty);
                    btnFavourite.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(() -> 
                        btnFavourite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    ).start();
                });
            }
        });
    }

    private void updateUIForPosition(int position) {
        if (mediaItems.isEmpty() || position < 0 || position >= mediaItems.size()) return;
        SecretItem item = mediaItems.get(position);
        executor.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
            if (entity != null) {
                runOnUiThread(() -> {
                    btnFavourite.setImageResource(entity.isFavourite() ? R.drawable.ic_heart_filled : R.drawable.ic_heart_empty);
                    if (entity.getAlbumId() != null) {
                        btnAddToAlbum.setImageResource(R.drawable.ic_check_circle_filled);
                    } else {
                        btnAddToAlbum.setImageResource(android.R.drawable.ic_menu_add);
                    }
                });
            }
        });
    }

    private void shareCurrentItem() {
        if (mediaItems.isEmpty()) return;
        SecretItem item = mediaItems.get(viewPager.getCurrentItem());
        executor.execute(() -> {
            try {
                String extension = item.isVideo() ? ".mp4" : ".jpg";
                SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
                if (entity != null && entity.getOriginalPath() != null) {
                    String origPath = entity.getOriginalPath();
                    int lastDot = origPath.lastIndexOf('.');
                    if (lastDot != -1) {
                        extension = origPath.substring(lastDot);
                    }
                }

                File tempFile = decryptToCache(item.getFile(), extension);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", tempFile);
                
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(item.isVideo() ? "video/*" : "image/*");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Share via"));
            } catch (Exception e) { 
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to share", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void restoreCurrentItem() {
        if (mediaItems.isEmpty()) return;
        int currentPos = viewPager.getCurrentItem();
        SecretItem item = mediaItems.get(currentPos);
        executor.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
            if (entity != null && entity.getOriginalPath() != null) {
                try {
                    File target = new File(entity.getOriginalPath());
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    
                    decryptToFile(item.getFile(), target);
                    
                    if (entity.getDateTaken() != 0) {
                        target.setLastModified(entity.getDateTaken());
                    }
                    
                    database.secretDao().delete(entity);
                    item.getFile().delete();
                    
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Restored to gallery", Toast.LENGTH_SHORT).show();
                        removeMediaItemAt(currentPos);
                    });
                } catch (Exception e) { 
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(this, "Restore failed", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void deleteCurrentItem() {
        if (mediaItems.isEmpty()) return;
        int currentPos = viewPager.getCurrentItem();
        SecretItem item = mediaItems.get(currentPos);

        new AlertDialog.Builder(this)
                .setTitle("Delete Permanently?")
                .setMessage("This will erase the file forever.")
                .setPositiveButton("Delete", (d, w) -> {
                    executor.execute(() -> {
                        SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
                        if (entity != null) database.secretDao().delete(entity);
                        item.getFile().delete();
                        runOnUiThread(() -> removeMediaItemAt(currentPos));
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void removeMediaItemAt(int position) {
        if (position >= 0 && position < mediaItems.size()) {
            mediaItems.remove(position);
            if (mediaItems.isEmpty()) {
                finish();
            } else {
                adapter.notifyItemRemoved(position);
                updateUIForPosition(viewPager.getCurrentItem());
            }
        }
    }

    private void showInfo() {
        if (mediaItems.isEmpty()) return;
        SecretItem item = mediaItems.get(viewPager.getCurrentItem());
        executor.execute(() -> {
            SecretEntity entity = database.secretDao().getSecretByFileName(item.getFile().getName());
            if (entity != null) {
                String info = "Original Path: " + entity.getOriginalPath() + 
                             "\nDate Taken: " + new java.util.Date(entity.getDateTaken()).toString() +
                             "\nDate Added: " + new java.util.Date(entity.getDateAdded()).toString();
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Metadata").setMessage(info).show());
            }
        });
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

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (viewPager != null) outState.putInt(KEY_CURRENT_POS, viewPager.getCurrentItem());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (adapter != null) adapter.releaseAll();
    }
}