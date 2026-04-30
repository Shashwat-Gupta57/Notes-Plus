package org.flexstudios.notes.plus;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class GallerySettingsActivity extends AppCompatActivity {
    private static final int PICK_VAULT_IMAGE = 1001;
    private static final int PICK_VAULT_VIDEO = 1002;
    private static final int PICK_LOCK_IMAGE = 1003;
    private static final int PICK_LOCK_VIDEO = 1004;

    private RecyclerView recyclerViewVaults;
    private VaultSettingsAdapter adapter;
    private AppDatabase database;
    private List<VaultEntity> vaults = new ArrayList<>();
    private int currentVaultId = 1;
    private VaultEntity currentVault;
    private boolean isMainVaultSettings = true;

    private CheckBox checkLockBgBlur, checkLockBgDim;
    private View layoutLockBgOptions;
    private TextView textUpdateStatus;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UpdateDownloadReceiver.ACTION_UPDATE_DOWNLOADED.equals(intent.getAction())) {
                refreshUpdateStatus();
                showInstallPrompt();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_gallery_settings);

        database = AppDatabase.getInstance(this);
        currentVaultId = getIntent().getIntExtra(UnlockActivity.EXTRA_VAULT_ID, 1);

        Toolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        recyclerViewVaults = findViewById(R.id.recyclerViewVaults);
        recyclerViewVaults.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VaultSettingsAdapter();
        recyclerViewVaults.setAdapter(adapter);

        findViewById(R.id.btnAddVault).setOnClickListener(v -> showCreateVaultDialog());
        findViewById(R.id.optionChangeLock).setOnClickListener(v -> {
            if (currentVault != null) showEditVaultDialog(currentVault);
        });

        setupAutoLock();
        setupBackgroundSettings();
        setupUpdateSettings();
        checkVaultAccess();

        IntentFilter filter = new IntentFilter(UpdateDownloadReceiver.ACTION_UPDATE_DOWNLOADED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    private void setupAutoLock() {
        android.widget.RadioGroup radioGroup = findViewById(R.id.radioGroupAutoLock);
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

    private void setupBackgroundSettings() {
        findViewById(R.id.btnVaultBgDefault).setOnClickListener(v -> updateVaultBg("DEFAULT", null));
        findViewById(R.id.btnVaultBgColor).setOnClickListener(v -> showColorPickerDialog(true));
        findViewById(R.id.btnVaultBgImage).setOnClickListener(v -> pickMedia("image/*", PICK_VAULT_IMAGE));
        findViewById(R.id.btnVaultBgVideo).setOnClickListener(v -> pickMedia("video/*", PICK_VAULT_VIDEO));
        findViewById(R.id.btnVaultBgUrl).setOnClickListener(v -> showUrlInputDialog(true));

        findViewById(R.id.btnLockBgDefault).setOnClickListener(v -> updateLockBg("DEFAULT", null));
        findViewById(R.id.btnLockBgColor).setOnClickListener(v -> showColorPickerDialog(false));
        findViewById(R.id.btnLockBgImage).setOnClickListener(v -> pickMedia("image/*", PICK_LOCK_IMAGE));
        findViewById(R.id.btnLockBgVideo).setOnClickListener(v -> pickMedia("video/*", PICK_LOCK_VIDEO));
        findViewById(R.id.btnLockBgUrl).setOnClickListener(v -> showUrlInputDialog(false));

        layoutLockBgOptions = findViewById(R.id.layoutLockBgOptions);
        checkLockBgBlur = findViewById(R.id.checkLockBgBlur);
        checkLockBgDim = findViewById(R.id.checkLockBgDim);

        layoutLockBgOptions.setVisibility(View.VISIBLE);

        checkLockBgBlur.setChecked(SecurityHelper.getAppPrefs(this).getBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, false));
        checkLockBgDim.setChecked(SecurityHelper.getAppPrefs(this).getBoolean(SecurityHelper.KEY_LOCK_BG_DIM, false));

        checkLockBgBlur.setOnCheckedChangeListener((v, checked) -> SecurityHelper.getAppPrefs(this).edit().putBoolean(SecurityHelper.KEY_LOCK_BG_BLUR, checked).apply());
        checkLockBgDim.setOnCheckedChangeListener((v, checked) -> SecurityHelper.getAppPrefs(this).edit().putBoolean(SecurityHelper.KEY_LOCK_BG_DIM, checked).apply());
    }

    private void setupUpdateSettings() {
        textUpdateStatus = findViewById(R.id.textUpdateStatus);
        findViewById(R.id.btnCheckUpdates).setOnClickListener(v -> performUpdateCheck());
        refreshUpdateStatus();
    }

    private void performUpdateCheck() {
        textUpdateStatus.setText("Checking for updates...");
        new UpdateManager(this).checkForUpdates(new UpdateManager.UpdateCallback() {
            @Override
            public void onUpdateFound(int versionCode, String versionName, boolean forced, String changelog) {
                textUpdateStatus.setText("Update found: v" + versionName + ". Downloading...");
            }

            @Override
            public void onNoUpdate() {
                textUpdateStatus.setText("App is up to date");
            }

            @Override
            public void onError(Exception e) {
                textUpdateStatus.setText("Failed to check for updates");
            }
        });
    }

    private void refreshUpdateStatus() {
        SharedPreferences prefs = getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
        String path = prefs.getString("pending_apk_path", null);
        if (path != null && new File(path).exists()) {
            textUpdateStatus.setText("Update ready to install.");
        } else {
            textUpdateStatus.setText("App is up to date");
        }
    }

    private void showInstallPrompt() {
        new AlertDialog.Builder(this)
                .setTitle("Update Ready")
                .setMessage("The latest version of Notes+ has been downloaded. Would you like to install it now?")
                .setPositiveButton("Install & Restart", (dialog, which) -> {
                    triggerInstallation();
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void triggerInstallation() {
        SharedPreferences prefs = getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
        String apkPath = prefs.getString("pending_apk_path", null);
        
        if (apkPath != null) {
            File apkFile = new File(apkPath);
            if (apkFile.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apkFile);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                
                startActivity(intent);
                prefs.edit().remove("pending_apk_path").apply();
            }
        }
    }

    private void pickMedia(String type, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType(type);
        startActivityForResult(intent, requestCode);
    }

    private void showUrlInputDialog(boolean forVault) {
        EditText input = new EditText(this);
        input.setHint("https://example.com/media.mp4");
        new AlertDialog.Builder(this)
                .setTitle("Enter Media URL")
                .setMessage("Supports Images, GIFs, and Videos")
                .setView(input)
                .setPositiveButton("Set", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        if (forVault) updateVaultBg("URL", url);
                        else updateLockBg("URL", url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showColorPickerDialog(boolean forVault) {
        EditText input = new EditText(this);
        input.setHint("#RRGGBB");
        new AlertDialog.Builder(this)
                .setTitle("Enter Hex Color")
                .setView(input)
                .setPositiveButton("Set", (d, w) -> {
                    String color = input.getText().toString().trim();
                    try {
                        Color.parseColor(color);
                        if (forVault) updateVaultBg("COLOR", color);
                        else updateLockBg("COLOR", color);
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid color format", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateVaultBg(String type, String value) {
        if (currentVault == null) return;
        currentVault.setBgType(type);
        currentVault.setBgValue(value);
        Executors.newSingleThreadExecutor().execute(() -> database.vaultDao().update(currentVault));
        Toast.makeText(this, "Vault background updated", Toast.LENGTH_SHORT).show();
    }

    private void updateLockBg(String type, String value) {
        SecurityHelper.getAppPrefs(this).edit()
                .putString(SecurityHelper.KEY_LOCK_BG_TYPE, type)
                .putString(SecurityHelper.KEY_LOCK_BG_VALUE, value)
                .apply();
        Toast.makeText(this, "Lock screen background updated", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == PICK_VAULT_IMAGE || requestCode == PICK_VAULT_VIDEO) {
                saveBgMedia(uri, true, requestCode == PICK_VAULT_VIDEO);
            } else if (requestCode == PICK_LOCK_IMAGE || requestCode == PICK_LOCK_VIDEO) {
                saveBgMedia(uri, false, requestCode == PICK_LOCK_VIDEO);
            }
        }
    }

    private void saveBgMedia(Uri uri, boolean forVault, boolean isVideo) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File dir = forVault ? new File(getFilesDir(), "bg_vault_" + currentVaultId) : new File(getFilesDir(), "bg_lock");
                if (!dir.exists()) dir.mkdirs();
                
                String ext = isVideo ? ".mp4" : ".jpg";
                String fileName = "bg_" + System.currentTimeMillis() + ext;
                File file = new File(dir, fileName);
                
                File[] oldFiles = dir.listFiles();
                if (oldFiles != null) {
                    for (File f : oldFiles) f.delete();
                }

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[16384];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                }

                runOnUiThread(() -> {
                    String type = isVideo ? "VIDEO" : "IMAGE";
                    if (forVault) updateVaultBg(type, file.getAbsolutePath());
                    else updateLockBg(type, file.getAbsolutePath());
                });
            } catch (IOException e) { e.printStackTrace(); }
        });
    }

    private void checkVaultAccess() {
        Executors.newSingleThreadExecutor().execute(() -> {
            currentVault = database.vaultDao().getVaultById(currentVaultId);
            isMainVaultSettings = (currentVault != null && currentVault.isMain());
            runOnUiThread(() -> {
                findViewById(R.id.recyclerViewVaults).setVisibility(isMainVaultSettings ? View.VISIBLE : View.GONE);
                findViewById(R.id.btnAddVault).setVisibility(isMainVaultSettings ? View.VISIBLE : View.GONE);
                TextView optionTitle = findViewById(R.id.optionChangeLock);
                optionTitle.setText(isMainVaultSettings ? "Change Main Vault Lock" : "Change Vault Lock");
                if (isMainVaultSettings) loadVaults();
            });
        });
    }

    private void loadVaults() {
        database.vaultDao().getAllVaults().observe(this, entities -> {
            if (isMainVaultSettings) {
                vaults.clear();
                vaults.addAll(entities);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void showCreateVaultDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_vault, null);
        EditText nameInput = dialogView.findViewById(R.id.editVaultName);
        EditText pinInput = dialogView.findViewById(R.id.editVaultPin);
        EditText passInput = dialogView.findViewById(R.id.editVaultPassword);
        PatternView patternInput = dialogView.findViewById(R.id.patternVaultSetup);
        TabLayout tabs = dialogView.findViewById(R.id.tabLayoutLockType);
        
        final String[] currentLockType = {"PIN"};
        final String[] currentLockValue = {""};

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                pinInput.setVisibility(View.GONE);
                passInput.setVisibility(View.GONE);
                patternInput.setVisibility(View.GONE);
                if (tab.getPosition() == 0) {
                    currentLockType[0] = "PIN";
                    pinInput.setVisibility(View.VISIBLE);
                } else if (tab.getPosition() == 1) {
                    currentLockType[0] = "PASSWORD";
                    passInput.setVisibility(View.VISIBLE);
                } else {
                    currentLockType[0] = "PATTERN";
                    patternInput.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        patternInput.setOnPatternListener(pattern -> currentLockValue[0] = pattern);

        new AlertDialog.Builder(this)
                .setTitle("New Secret Vault")
                .setView(dialogView)
                .setPositiveButton("Create", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "New Vault";
                    
                    String value = currentLockValue[0];
                    if (currentLockType[0].equals("PIN")) value = pinInput.getText().toString();
                    else if (currentLockType[0].equals("PASSWORD")) value = passInput.getText().toString();

                    if (value.isEmpty()) {
                        Toast.makeText(this, "Lock cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String finalName = name;
                    String finalValue = value;
                    Executors.newSingleThreadExecutor().execute(() -> {
                        VaultEntity newVault = new VaultEntity(finalName, currentLockType[0], finalValue, false, vaults.size());
                        database.vaultDao().insert(newVault);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditVaultDialog(VaultEntity vault) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_vault, null);
        EditText nameInput = dialogView.findViewById(R.id.editVaultName);
        EditText pinInput = dialogView.findViewById(R.id.editVaultPin);
        EditText passInput = dialogView.findViewById(R.id.editVaultPassword);
        PatternView patternInput = dialogView.findViewById(R.id.patternVaultSetup);
        TabLayout tabs = dialogView.findViewById(R.id.tabLayoutLockType);
        
        nameInput.setText(vault.getName());
        final String[] currentLockType = {vault.getLockType()};
        final String[] currentLockValue = {vault.getLockValue()};

        pinInput.setVisibility(View.GONE);
        passInput.setVisibility(View.GONE);
        patternInput.setVisibility(View.GONE);

        if ("PIN".equals(vault.getLockType())) {
            tabs.getTabAt(0).select();
            pinInput.setVisibility(View.VISIBLE);
            pinInput.setText(vault.getLockValue());
        } else if ("PASSWORD".equals(vault.getLockType())) {
            tabs.getTabAt(1).select();
            passInput.setVisibility(View.VISIBLE);
            passInput.setText(vault.getLockValue());
        } else {
            tabs.getTabAt(2).select();
            patternInput.setVisibility(View.VISIBLE);
        }

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                pinInput.setVisibility(View.GONE);
                passInput.setVisibility(View.GONE);
                patternInput.setVisibility(View.GONE);
                if (tab.getPosition() == 0) {
                    currentLockType[0] = "PIN";
                    pinInput.setVisibility(View.VISIBLE);
                } else if (tab.getPosition() == 1) {
                    currentLockType[0] = "PASSWORD";
                    passInput.setVisibility(View.VISIBLE);
                } else {
                    currentLockType[0] = "PATTERN";
                    patternInput.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        patternInput.setOnPatternListener(pattern -> currentLockValue[0] = pattern);

        new AlertDialog.Builder(this)
                .setTitle("Edit Vault Settings")
                .setView(dialogView)
                .setPositiveButton("Save", (d, w) -> {
                    vault.setName(nameInput.getText().toString().trim());
                    vault.setLockType(currentLockType[0]);
                    String value = currentLockValue[0];
                    if (currentLockType[0].equals("PIN")) value = pinInput.getText().toString();
                    else if (currentLockType[0].equals("PASSWORD")) value = passInput.getText().toString();
                    vault.setLockValue(value);

                    Executors.newSingleThreadExecutor().execute(() -> database.vaultDao().update(vault));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteVault(VaultEntity vault) {
        if (vault.isMain()) {
            Toast.makeText(this, "Cannot delete Main Vault", Toast.LENGTH_SHORT).show();
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            int count = database.vaultDao().getSecretCountForVault(vault.getId());
            runOnUiThread(() -> {
                if (count > 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Delete " + vault.getName() + "?")
                            .setMessage("There are " + count + " items still inside. What would you like to do?")
                            .setNeutralButton("Cancel", null)
                            .setNegativeButton("Delete Everything", (d, w) -> {
                                Executors.newSingleThreadExecutor().execute(() -> {
                                    List<SecretEntity> secrets = database.secretDao().getSecretsForVaultDirect(vault.getId());
                                    for (SecretEntity s : secrets) {
                                        new File(getFilesDir(), "vault/" + s.getFileName()).delete();
                                        database.secretDao().delete(s);
                                    }
                                    deleteVaultResources(vault);
                                    database.vaultDao().delete(vault);
                                });
                            })
                            .setPositiveButton("Restore then Delete", (d, w) -> {
                                Executors.newSingleThreadExecutor().execute(() -> {
                                    List<SecretEntity> secrets = database.secretDao().getSecretsForVaultDirect(vault.getId());
                                    for (SecretEntity s : secrets) {
                                        try {
                                            restoreFile(s);
                                            database.secretDao().delete(s);
                                        } catch (Exception e) { e.printStackTrace(); }
                                    }
                                    deleteVaultResources(vault);
                                    database.vaultDao().delete(vault);
                                });
                            })
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Delete Vault?")
                            .setMessage("Are you sure you want to delete this empty vault?")
                            .setPositiveButton("Delete", (d, w) -> {
                                Executors.newSingleThreadExecutor().execute(() -> {
                                    deleteVaultResources(vault);
                                    database.vaultDao().delete(vault);
                                });
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
        });
    }

    private void deleteVaultResources(VaultEntity vault) {
        File dir = new File(getFilesDir(), "bg_vault_" + vault.getId());
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            dir.delete();
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            database.albumDao().deleteAlbumsForVault(vault.getId());
        });
    }

    private void restoreFile(SecretEntity s) throws GeneralSecurityException, IOException {
        File encryptedFile = new File(getFilesDir(), "vault/" + s.getFileName());
        File target = new File(s.getOriginalPath());
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                encryptedFile, this, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build();

        try (InputStream in = encryptedStorage.openFileInput();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        }
        if (s.getDateTaken() != 0) target.setLastModified(s.getDateTaken());
        encryptedFile.delete();
    }

    private class VaultSettingsAdapter extends RecyclerView.Adapter<VaultSettingsAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vault_setting, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VaultEntity v = vaults.get(position);
            holder.name.setText(v.isMain() ? v.getName() + " (Main)" : v.getName());
            holder.type.setText("Lock: " + v.getLockType());
            holder.itemView.setOnClickListener(view -> showEditVaultDialog(v));
            holder.btnDelete.setVisibility(v.isMain() ? View.GONE : View.VISIBLE);
            holder.btnDelete.setOnClickListener(view -> deleteVault(v));
        }

        @Override
        public int getItemCount() { return vaults.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, type;
            ImageButton btnDelete;
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.textVaultName);
                type = itemView.findViewById(R.id.textVaultType);
                btnDelete = itemView.findViewById(R.id.btnDeleteVault);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(updateReceiver);
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