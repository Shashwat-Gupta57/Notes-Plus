package org.flexstudios.notes.plus;

import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;
import com.bumptech.glide.Glide;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

public class PhotoViewerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_photo_viewer);

        ImageView imageView = findViewById(R.id.fullImageView);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        String filePath = getIntent().getStringExtra("file_path");
        if (filePath != null) {
            decryptAndShow(new File(filePath), imageView);
        }
    }

    private void decryptAndShow(File encryptedFile, ImageView imageView) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                    encryptedFile, this, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build();

            File tempFile = new File(getCacheDir(), "temp_view_" + encryptedFile.getName());
            try (InputStream in = encryptedStorage.openFileInput();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            }

            Glide.with(this).load(tempFile).into(imageView);
            tempFile.deleteOnExit();
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
        }
    }
}