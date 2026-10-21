package org.flexstudios.notes.plus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.List;

/**
 * Settings of the chat vault that is open: its ID, name and lock. The main vault also manages
 * the other chat vaults and the auto-lock. Never mentions anything outside the chat space.
 */
public class ChatSettingsActivity extends VaultActivity {

    /** Opens straight into "Change lock" (from the first-run suggestion). */
    static final String EXTRA_CHANGE_LOCK = "change_lock";

    private static final long[] AUTO_LOCK_MS = {0, 60_000, 5 * 60_000, 15 * 60_000};
    private static final String[] AUTO_LOCK_LABELS = {"Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes"};

    private ChatStore.Dao dao;
    private ChatStore.Vault vault;
    private boolean writable = true;
    private String holder;
    private final ChatVaults.Session.Listener session = (canWrite, by) -> {
        writable = canWrite;
        holder = by;
    };

    @Override
    protected String realm() {
        return UnlockActivity.REALM_CHAT;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_settings);
        dao = AppDatabase.getInstance(this).chatDao();
        Toolbar toolbar = findViewById(R.id.toolbarChatSettings);
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.rowCode).setOnClickListener(v -> showCode());
        findViewById(R.id.rowName).setOnClickListener(v -> {
            if (canEdit()) rename(vault);
        });
        findViewById(R.id.rowLock).setOnClickListener(v -> {
            if (canEdit()) editLock(vault);
        });
        findViewById(R.id.rowAddVault).setOnClickListener(v -> {
            if (canEdit()) addVault();
        });
        findViewById(R.id.rowAutoLock).setOnClickListener(v -> pickAutoLock());
        com.google.android.material.materialswitch.MaterialSwitch screenshots = findViewById(R.id.switchScreenshots);
        screenshots.setChecked(SecurityHelper.screenshotsAllowed(this, UnlockActivity.REALM_CHAT));
        screenshots.setOnCheckedChangeListener((b, allowed) -> {
            SecurityHelper.setScreenshotsAllowed(this, UnlockActivity.REALM_CHAT, allowed);
            SecurityHelper.applyScreenProtection(getWindow(), this, UnlockActivity.REALM_CHAT);
        });

        findViewById(R.id.rowPhoto).setOnClickListener(v -> {
            if (canEdit()) choosePhoto();
        });
        findViewById(R.id.rowBackground).setOnClickListener(v -> chooseBackground(false));
        findViewById(R.id.rowLockBackground).setOnClickListener(v -> chooseBackground(true));

        TextView chattingAs = findViewById(R.id.txtChattingAs);
        chattingAs.setText(Account.uid() == null ? "Not signed in" : "…");
        Account.loadProfile(p -> {
            if (!isDestroyed()) chattingAs.setText(p == null ? "Not signed in"
                    : p.username == null ? "No username yet" : "@" + p.username);
        });
        load(getIntent().getBooleanExtra(EXTRA_CHANGE_LOCK, false));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (vault != null) ChatVaults.Session.enter(this, vault.code, session);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ChatVaults.Session.leave(session);
    }

    /** Another phone has this vault open: here it can only be looked at. */
    private boolean canEdit() {
        if (writable) return true;
        Toast.makeText(this, "Open on " + holder + ". Changes can only be made there for now.", Toast.LENGTH_LONG).show();
        return false;
    }

    private void load(boolean thenChangeLock) {
        AppDatabase.IO.execute(() -> {
            ChatStore.Vault v = dao.vault(vaultId);
            runOnUiThread(() -> {
                if (v == null) {
                    finish();
                    return;
                }
                boolean first = vault == null;
                vault = v;
                if (first && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    ChatVaults.Session.enter(this, v.code, session);
                }
                ((TextView) findViewById(R.id.txtCode)).setText(v.code + "  ·  tap to copy or change");
                ((TextView) findViewById(R.id.txtName)).setText(v.name);
                ((TextView) findViewById(R.id.txtLock)).setText(lockLabel(v.lockType));
                ((TextView) findViewById(R.id.txtPhoto)).setText(v.photo == null
                        ? "Your account photo shows. Choose one just for this vault" : "Shown to people this vault talks to");
                ((TextView) findViewById(R.id.txtBackground)).setText(v.background == null ? "Default" : "Custom");
                android.widget.ImageView photo = findViewById(R.id.imgVaultPhoto);
                if (v.photo != null) {
                    photo.setBackground(null);
                    photo.setPadding(0, 0, 0, 0);
                    photo.setImageTintList(null);
                    ChatMedia.loadAvatar(this, photo, v.photo, () -> {});
                }
                findViewById(R.id.sectionMain).setVisibility(v.isMain ? View.VISIBLE : View.GONE);
                if (v.isMain) {
                    showAutoLock();
                    dao.vaultsLive().observe(this, this::showVaults);
                }
                if (thenChangeLock) editLock(v);
            });
        });
    }

    // ============================================================ this vault

    private void showCode() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Vault ID " + vault.code)
                .setMessage("Share it with your username so people can reach this vault."
                        + (vault.isMain ? " Your main vault is also reached without any ID." : ""))
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Vault ID", vault.code));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Change", (d, w) -> {
                    if (canEdit()) changeCode();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void changeCode() {
        Prompts.text(this, "New vault ID", "4 letters or numbers. People who have the old ID keep "
                        + "their old conversations; new messages need the new ID.", "ID", vault.code,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "Save",
                raw -> {
                    String code = ChatSync.normaliseCode(raw);
                    if (code == null || code.isEmpty()) {
                        Toast.makeText(this, "Use exactly 4 letters or numbers", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (code.equals(vault.code)) return;
                    AppDatabase.IO.execute(() -> {
                        // Retired IDs count too: an ID once used is never handed out again.
                        if (dao.vaultByCodeAny(code) != null) {
                            runOnUiThread(() -> Toast.makeText(this, "That ID is taken or was used before", Toast.LENGTH_LONG).show());
                            return;
                        }
                        ChatVaults.changeCode(this, vault, code);
                        runOnUiThread(() -> load(false));
                    });
                }, null);
    }

    private void rename(ChatStore.Vault target) {
        Prompts.text(this, "Name", null, "Vault name", target.name, Prompts.TEXT, "Save", name ->
                AppDatabase.IO.execute(() -> {
                    target.name = name;
                    ChatVaults.save(this, target);
                    runOnUiThread(() -> load(false));
                }), null);
    }

    // =============================================================== locks

    /** Chooses a lock (PIN, password or pattern). {@code onLock} gets type and value. */
    private void askLock(String title, @Nullable String withName, LockConsumer onLock) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_create_vault, null);
        EditText name = view.findViewById(R.id.editVaultName);
        EditText pin = view.findViewById(R.id.editVaultPin);
        EditText password = view.findViewById(R.id.editVaultPassword);
        PatternView pattern = view.findViewById(R.id.patternVaultSetup);
        TabLayout tabs = view.findViewById(R.id.tabLayoutLockType);
        name.setVisibility(withName == null ? View.GONE : View.VISIBLE);
        if (withName != null) name.setText(withName);
        String[] type = {"PIN"};
        String[] drawn = {""};
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                type[0] = tab.getPosition() == 0 ? "PIN" : tab.getPosition() == 1 ? "PASSWORD" : "PATTERN";
                pin.setVisibility(tab.getPosition() == 0 ? View.VISIBLE : View.GONE);
                password.setVisibility(tab.getPosition() == 1 ? View.VISIBLE : View.GONE);
                pattern.setVisibility(tab.getPosition() == 2 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        pattern.setOnPatternListener(value -> drawn[0] = value);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    String value = "PIN".equals(type[0]) ? pin.getText().toString()
                            : "PASSWORD".equals(type[0]) ? password.getText().toString() : drawn[0];
                    String problem = value.isEmpty() ? "The lock can't be empty"
                            : "PIN".equals(type[0]) && value.length() < 4 ? "A PIN needs at least 4 digits"
                            : "PATTERN".equals(type[0]) && value.length() < 4 ? "Connect at least 4 dots" : null;
                    if (problem != null) {
                        Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                        return;
                    }
                    onLock.accept(name.getText().toString().trim(), type[0], value);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface LockConsumer {
        void accept(String name, String type, String value);
    }

    private void editLock(ChatStore.Vault target) {
        askLock("New lock for " + target.name, null, (ignored, type, value) -> AppDatabase.IO.execute(() -> {
            try {
                // Unlock opens the first vault whose lock matches: a shared one would hide one.
                if (ChatVaults.lockTaken(this, type, value, target.id)) {
                    runOnUiThread(() -> Toast.makeText(this, "Another vault already uses that lock", Toast.LENGTH_LONG).show());
                    return;
                }
                target.lockType = type;
                target.lockValue = ChatVaults.hashLock(this, type, value);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't save the lock", Toast.LENGTH_LONG).show());
                return;
            }
            ChatVaults.save(this, target);
            if (target.isMain) SecurityHelper.getAppPrefs(this).edit().remove(ChatSync.PREF_INHERITED_LOCK).apply();
            runOnUiThread(() -> {
                Toast.makeText(this, "Lock changed", Toast.LENGTH_SHORT).show();
                load(false);
            });
        }));
    }

    // ============================================================ main vault

    private void showVaults(List<ChatStore.Vault> vaults) {
        LinearLayout list = findViewById(R.id.listVaults);
        list.removeAllViews();
        for (ChatStore.Vault v : vaults) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_vault_setting, list, false);
            ((TextView) row.findViewById(R.id.textVaultName)).setText(v.name + (v.isMain ? "  (main)" : ""));
            ((TextView) row.findViewById(R.id.textVaultType)).setText("ID " + v.code + "  ·  " + lockLabel(v.lockType));
            View delete = row.findViewById(R.id.btnDeleteVault);
            delete.setVisibility(v.isMain ? View.GONE : View.VISIBLE);
            delete.setOnClickListener(x -> {
                if (canEdit()) confirmDelete(v);
            });
            row.setOnClickListener(x -> {
                if (canEdit()) showVaultOptions(v);
            });
            list.addView(row);
            View divider = new View(this);
            list.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.max(1, Math.round(getResources().getDisplayMetrics().density * 0.5f))));
            divider.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.divider));
        }
    }

    private void showVaultOptions(ChatStore.Vault v) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(v.name)
                .setItems(new String[]{"Rename", "Change lock"}, (d, which) -> {
                    if (which == 0) rename(v);
                    else editLock(v);
                })
                .show();
    }

    private void addVault() {
        askLock("New vault", "", (name, type, value) -> AppDatabase.IO.execute(() -> {
            ChatStore.Vault v;
            try {
                if (ChatVaults.lockTaken(this, type, value, -1)) {
                    runOnUiThread(() -> Toast.makeText(this, "Another vault already uses that lock", Toast.LENGTH_LONG).show());
                    return;
                }
                v = ChatVaults.create(this, name.isEmpty() ? "Vault" : name, type, value);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't create the vault", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                    .setTitle(v.name + " is ready")
                    .setMessage("Its ID is " + v.code + ". Open it with its own lock from the lock screen.")
                    .setPositiveButton("OK", null)
                    .show());
        }));
    }

    private void confirmDelete(ChatStore.Vault v) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + v.name + "?")
                .setMessage("Its conversations are removed from this phone. Messages sent to ID " + v.code
                        + " won't arrive here any more.")
                .setPositiveButton("Delete", (d, w) -> AppDatabase.IO.execute(() -> ChatVaults.delete(this, v)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAutoLock() {
        long current = SecurityHelper.getAutoLockDelay(this, UnlockActivity.REALM_CHAT);
        String label = AUTO_LOCK_LABELS[0];
        for (int i = 0; i < AUTO_LOCK_MS.length; i++) if (AUTO_LOCK_MS[i] == current) label = AUTO_LOCK_LABELS[i];
        ((TextView) findViewById(R.id.txtAutoLock)).setText(label);
    }

    private void pickAutoLock() {
        long current = SecurityHelper.getAutoLockDelay(this, UnlockActivity.REALM_CHAT);
        int checked = 0;
        for (int i = 0; i < AUTO_LOCK_MS.length; i++) if (AUTO_LOCK_MS[i] == current) checked = i;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Auto-lock")
                .setSingleChoiceItems(AUTO_LOCK_LABELS, checked, (d, which) -> {
                    SecurityHelper.setAutoLockDelay(this, UnlockActivity.REALM_CHAT, AUTO_LOCK_MS[which]);
                    showAutoLock();
                    d.dismiss();
                })
                .show();
    }

    // ======================================================== photo, backgrounds

    private static final String[] COLOR_NAMES = {"Default", "Ocean", "Forest", "Sunset", "Plum", "Rose", "Slate", "Night"};
    private static final String[] COLORS = {null, "#1565C0", "#2E7D32", "#E65100", "#6A1B9A", "#AD1457", "#37474F", "#121212"};

    private final androidx.activity.result.ActivityResultLauncher<String[]> pickPhoto = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), this::savePhoto);
    private final androidx.activity.result.ActivityResultLauncher<String[]> pickBackground = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) saveBackgroundImage(uri, false);
            });
    private final androidx.activity.result.ActivityResultLauncher<String[]> pickLockBackground = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) saveBackgroundImage(uri, true);
            });

    /** This vault's own photo, shown to the people it talks to instead of the account's. */
    private void choosePhoto() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Profile photo for " + vault.name)
                .setItems(new String[]{"Choose a photo", "Use my account photo"}, (d, which) -> {
                    if (which == 1) {
                        AppDatabase.IO.execute(() -> {
                            ChatVaults.setPhoto(this, vault, null);
                            runOnUiThread(() -> load(false));
                        });
                    } else if (Account.driveEmail(this) == null) {
                        Toast.makeText(this, "Connect Google Drive in your Notes+ account first: photos are kept there",
                                Toast.LENGTH_LONG).show();
                    } else {
                        expectExternalActivity();
                        pickPhoto.launch(new String[]{"image/*"});
                    }
                })
                .show();
    }

    private void savePhoto(@Nullable android.net.Uri uri) {
        if (uri == null) return;
        androidx.appcompat.app.AlertDialog progress = Prompts.progress(this, "Setting photo");
        progress.setMessage("Encrypting and uploading");
        AppDatabase.IO.execute(() -> {
            String error = null;
            try {
                DriveStore drive = Account.drive(this);
                if (drive == null) throw new java.io.IOException("Connect Google Drive first");
                ChatVaults.setPhoto(this, vault, ChatMedia.uploadPhoto(this, drive, uri));
            } catch (Exception e) {
                error = e.getMessage() == null ? "Couldn't set the photo" : e.getMessage();
            }
            String message = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                load(false);
            });
        });
    }

    /**
     * {@code lock}: the lock screen's background. There is one lock screen look for the whole
     * app on purpose; two different ones would tell anyone swiping both ways that there are two.
     * Otherwise: this vault's chat list, on this phone.
     */
    private void chooseBackground(boolean lock) {
        String[] options = new String[COLOR_NAMES.length + 1];
        System.arraycopy(COLOR_NAMES, 0, options, 0, COLOR_NAMES.length);
        options[COLOR_NAMES.length] = "A photo…";
        new MaterialAlertDialogBuilder(this)
                .setTitle(lock ? "Lock screen background" : "Chats background")
                .setItems(options, (d, which) -> {
                    if (which == COLOR_NAMES.length) {
                        expectExternalActivity();
                        (lock ? pickLockBackground : pickBackground).launch(new String[]{"image/*"});
                    } else if (lock) {
                        SecurityHelper.getAppPrefs(this).edit()
                                .putString(SecurityHelper.KEY_LOCK_BG_TYPE, COLORS[which] == null ? "DEFAULT" : "COLOR")
                                .putString(SecurityHelper.KEY_LOCK_BG_VALUE, COLORS[which])
                                .apply();
                        Toast.makeText(this, "Lock screen updated", Toast.LENGTH_SHORT).show();
                    } else {
                        String json = null;
                        if (COLORS[which] != null) {
                            try {
                                json = new org.json.JSONObject().put("kind", "color").put("value", COLORS[which]).toString();
                            } catch (Exception ignored) {
                                // Plain values only.
                            }
                        }
                        setVaultBackground(json);
                    }
                })
                .show();
    }

    private void setVaultBackground(@Nullable String json) {
        AppDatabase.IO.execute(() -> {
            if (vault.background != null && json == null) VaultCrypto.deleteRecursive(backgroundFile(this, vault.id));
            vault.background = json;
            dao.updateVault(vault); // This phone only: not a synced field.
            runOnUiThread(() -> load(false));
        });
    }

    private void saveBackgroundImage(android.net.Uri uri, boolean lock) {
        AppDatabase.IO.execute(() -> {
            try {
                if (lock) {
                    // Same place and form as the lock screen's own setting.
                    java.io.File dir = new java.io.File(getFilesDir(), "bg_lock");
                    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("No space");
                    java.io.File[] old = dir.listFiles();
                    if (old != null) for (java.io.File f : old) VaultCrypto.deleteRecursive(f);
                    java.io.File file = new java.io.File(dir, "bg_" + System.currentTimeMillis() + ".jpg");
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                         java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                        if (in == null) throw new java.io.IOException("Could not open that picture");
                        VaultCrypto.copy(in, out);
                    }
                    SecurityHelper.getAppPrefs(this).edit()
                            .putString(SecurityHelper.KEY_LOCK_BG_TYPE, "IMAGE")
                            .putString(SecurityHelper.KEY_LOCK_BG_VALUE, file.getAbsolutePath())
                            .apply();
                    runOnUiThread(() -> Toast.makeText(this, "Lock screen updated", Toast.LENGTH_SHORT).show());
                } else {
                    // Kept encrypted with this phone's key, like everything else in here.
                    java.io.File file = backgroundFile(this, vault.id);
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("Could not open that picture");
                        VaultCrypto.write(this, file, in);
                    }
                    setVaultBackground(new org.json.JSONObject().put("kind", "image").put("file", file.getAbsolutePath()).toString());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't use that picture", Toast.LENGTH_LONG).show());
            }
        });
    }

    static java.io.File backgroundFile(android.content.Context context, int vaultId) {
        java.io.File dir = new java.io.File(context.getFilesDir(), "chat_bg");
        if (!dir.exists() && !dir.mkdirs()) android.util.Log.w("ChatSettings", "Could not create " + dir);
        return new java.io.File(dir, "vault_" + vaultId);
    }

    /** A chat list background, decrypted and decoded no wider than {@code width}. Background thread. */
    @Nullable
    static android.graphics.Bitmap decodeBackground(android.content.Context context, java.io.File file, int width) {
        try {
            byte[] bytes = VaultCrypto.readAll(context, file);
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    private static String lockLabel(String type) {
        return "PIN".equals(type) ? "PIN" : "PASSWORD".equals(type) ? "Password" : "Pattern";
    }
}
