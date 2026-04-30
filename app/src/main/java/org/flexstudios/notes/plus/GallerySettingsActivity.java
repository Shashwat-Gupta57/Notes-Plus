package org.flexstudios.notes.plus;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
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
    private RecyclerView recyclerViewVaults;
    private VaultSettingsAdapter adapter;
    private AppDatabase database;
    private List<VaultEntity> vaults = new ArrayList<>();
    private int currentVaultId = 1;
    private boolean isMainVaultSettings = true;

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
            // Find current vault to change its lock
            Executors.newSingleThreadExecutor().execute(() -> {
                VaultEntity currentVault = database.vaultDao().getVaultById(currentVaultId);
                if (currentVault != null) {
                    runOnUiThread(() -> showEditVaultDialog(currentVault));
                }
            });
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

        checkVaultAccess();
    }

    private void checkVaultAccess() {
        Executors.newSingleThreadExecutor().execute(() -> {
            VaultEntity current = database.vaultDao().getVaultById(currentVaultId);
            isMainVaultSettings = (current != null && current.isMain());
            runOnUiThread(() -> {
                findViewById(R.id.recyclerViewVaults).setVisibility(isMainVaultSettings ? View.VISIBLE : View.GONE);
                findViewById(R.id.btnAddVault).setVisibility(isMainVaultSettings ? View.VISIBLE : View.GONE);
                // Option title change if secondary
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
                                    // Delete all files and db records
                                    List<SecretEntity> secrets = database.secretDao().getSecretsForVaultDirect(vault.getId());
                                    for (SecretEntity s : secrets) {
                                        new File(getFilesDir(), "vault/" + s.getFileName()).delete();
                                        database.secretDao().delete(s);
                                    }
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
                                    database.vaultDao().delete(vault);
                                });
                            })
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Delete Vault?")
                            .setMessage("Are you sure you want to delete this empty vault?")
                            .setPositiveButton("Delete", (d, w) -> {
                                Executors.newSingleThreadExecutor().execute(() -> database.vaultDao().delete(vault));
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
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
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}