package org.flexstudios.notes.plus;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class GallerySettingsActivity extends AppCompatActivity {
    private static final int REQ_VERIFY_FOR_CHANGE = 201;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_gallery_settings);

        Toolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        findViewById(R.id.optionChangeLock).setOnClickListener(v -> {
            Intent intent = new Intent(this, UnlockActivity.class);
            intent.putExtra(UnlockActivity.EXTRA_VERIFY_ONLY, true);
            startActivityForResult(intent, REQ_VERIFY_FOR_CHANGE);
        });

        RadioGroup radioGroup = findViewById(R.id.radioGroupAutoLock);
        long currentDelay = SecurityHelper.getAutoLockDelay(this);
        
        if (currentDelay == 0) radioGroup.check(R.id.radioImmediately);
        else if (currentDelay == 30000) radioGroup.check(R.id.radio30s);
        else if (currentDelay == 60000) radioGroup.check(R.id.radio1m);

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            long delay = 0;
            if (checkedId == R.id.radio30s) delay = 30000;
            else if (checkedId == R.id.radio1m) delay = 60000;
            SecurityHelper.setAutoLockDelay(this, delay);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VERIFY_FOR_CHANGE && resultCode == RESULT_OK) {
            // Re-setup the lock
            SecurityHelper.setSetupDone(this, false);
            startActivity(new Intent(this, SetupLockActivity.class));
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}