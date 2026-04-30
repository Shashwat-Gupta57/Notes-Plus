package org.flexstudios.notes.plus;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_media_pager);

        database = AppDatabase.getInstance(this);
        if (savedInstanceState != null) {
            savedPosition = savedInstanceState.getInt(KEY_CURRENT_POS, -1);
        }

        viewPager = findViewById(R.id.mediaViewPager);
        findViewById(R.id.pagerBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.actionShare).setOnClickListener(v -> shareCurrentItem());
        findViewById(R.id.actionInfo).setOnClickListener(v -> showInfo());
        findViewById(R.id.btnRestore).setOnClickListener(v -> restoreCurrentItem());
        findViewById(R.id.btnDelete).setOnClickListener(v -> deleteCurrentItem());

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

    private void loadMedia() {
        executor.execute(() -> {
            List<SecretEntity> entities = database.secretDao().getAllSecretsDirect();
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
            });
        });
    }

    private void shareCurrentItem() {
        if (mediaItems.isEmpty()) return;
        SecretItem item = mediaItems.get(viewPager.getCurrentItem());
        executor.execute(() -> {
            try {
                // Get the original extension from DB to ensure format is preserved
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
                    
                    // Restore original timestamp
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
        
        // Ensure extension starts with a dot
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