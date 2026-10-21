package org.flexstudios.notes.plus;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.util.Objects;

/**
 * The chat space's home for the vault that was unlocked: its conversations, and "New chat".
 *
 * Reached only through the chat lock screen. Nothing here, or anywhere in the chat space,
 * refers to any other hidden part of the app.
 */
public class ChatHomeActivity extends VaultActivity {

    private ChatStore.Dao dao;
    private Runnable stopLive = () -> {};
    private final Adapter adapter = new Adapter();
    private String code = "", username;
    private boolean writable = true;
    private String holder;
    private final android.os.Handler poller = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Read-only: no live updates, a fetch every five seconds instead. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            ChatSync.pollAll(ChatHomeActivity.this);
            poller.postDelayed(this, 5_000);
        }
    };
    private final ChatVaults.Session.Listener session = this::onSession;

    @Override
    protected String realm() {
        return UnlockActivity.REALM_CHAT;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_home);
        dao = AppDatabase.getInstance(this).chatDao();

        Toolbar toolbar = findViewById(R.id.toolbarChats);
        MenuItem settings = toolbar.getMenu().add("Settings").setIcon(R.drawable.ic_gear);
        settings.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (settings.getIcon() != null) {
            settings.getIcon().mutate().setTint(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        }
        toolbar.setOnMenuItemClickListener(item -> {
            startActivity(new Intent(this, ChatSettingsActivity.class).putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId));
            return true;
        });

        RecyclerView list = findViewById(R.id.recyclerChats);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        View empty = findViewById(R.id.emptyChats);
        dao.conversations(vaultId).observe(this, conversations -> {
            adapter.submitList(conversations);
            empty.setVisibility(conversations.isEmpty() ? View.VISIBLE : View.GONE);
        });

        findViewById(R.id.fabNewChat).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Start")
                .setItems(new String[]{"A chat with one person", "A group (up to " + ChatSync.MAX_GROUP + ")"},
                        (d, which) -> {
                            if (which == 0) newChat();
                            else newGroup();
                        })
                .show());
        findViewById(R.id.btnOpenAccount).setOnClickListener(v -> {
            expectExternalActivity();
            startActivity(new Intent(this, AccountActivity.class));
        });
        ChatSync.schedulePeriodic(this);
        ChatMedia.sweepPlain(this); // Videos decrypted for playing never outlive a visit.
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshHeader();
        ChatSync.schedule(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ChatVaults.Session.leave(session);
        stopLive.run();
        stopLive = () -> {};
        poller.removeCallbacks(poll);
    }

    /**
     * Writer: live updates and everything enabled. Read-only (open on another phone): a
     * banner, no new chats, no changes, and a fetch every five seconds.
     */
    private void onSession(boolean canWrite, @androidx.annotation.Nullable String by) {
        writable = canWrite;
        holder = by;
        TextView banner = findViewById(R.id.txtReadOnly);
        banner.setVisibility(canWrite ? View.GONE : View.VISIBLE);
        banner.setText("Open on " + by + ". You can read here; it switches over when that phone leaves.");
        findViewById(R.id.fabNewChat).setVisibility(canWrite ? View.VISIBLE : View.GONE);
        stopLive.run();
        stopLive = () -> {};
        poller.removeCallbacks(poll);
        if (canWrite) stopLive = ChatSync.watchIndex(this);
        else poller.post(poll);
    }

    /** Title, this vault's address, and whether chatting is possible yet. */
    private void refreshHeader() {
        Toolbar toolbar = findViewById(R.id.toolbarChats);
        View card = findViewById(R.id.cardSignIn);
        TextView cardText = findViewById(R.id.txtSignIn);
        AppDatabase.IO.execute(() -> {
            ChatStore.Vault vault = dao.vault(vaultId);
            if (vault == null) {
                runOnUiThread(this::relock); // Deleted, from another phone.
                return;
            }
            runOnUiThread(() -> {
                toolbar.setTitle(vault.isMain ? "Chats" : vault.name);
                if (!vault.code.equals(code)) {
                    code = vault.code;
                    ChatVaults.Session.leave(session);
                }
                applyBackground(vault.background);
                // Only while on screen: an answer arriving after onStop must not take the lease.
                if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    ChatVaults.Session.enter(this, code, session);
                }
                showAddress(toolbar);
                if (vault.isMain && SecurityHelper.getAppPrefs(this).getBoolean(ChatSync.PREF_INHERITED_LOCK, false)) {
                    offerOwnLock();
                }
            });
        });
        if (Account.uid() != null && SecurityHelper.getAppPrefs(this).getBoolean(ChatSync.PREF_REFUSED, false)) {
            cardText.setText("The server is refusing chat right now (permission denied), so messages wait here. "
                    + "They'll go out by themselves once it accepts them again.");
            card.setVisibility(View.VISIBLE);
            findViewById(R.id.btnOpenAccount).setVisibility(View.GONE);
            return;
        }
        findViewById(R.id.btnOpenAccount).setVisibility(View.VISIBLE);
        if (Account.uid() == null) {
            cardText.setText("Sign in to your Notes+ account to start chatting. People find you by your username.");
            card.setVisibility(View.VISIBLE);
            return;
        }
        Account.loadProfile(p -> {
            if (isFinishing() || isDestroyed()) return;
            boolean ready = p != null && p.username != null;
            card.setVisibility(ready ? View.GONE : View.VISIBLE);
            if (!ready) cardText.setText("Choose a username in your Notes+ account. It's how people reach you here.");
            username = ready ? p.username : null;
            showAddress(toolbar);
        });
    }

    /** This vault's chat list background, chosen in its settings (this phone only). */
    private void applyBackground(@androidx.annotation.Nullable String json) {
        android.widget.ImageView image = findViewById(R.id.imgHomeBackground);
        image.setVisibility(View.GONE);
        View coordinator = findViewById(R.id.homeRoot);
        coordinator.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.gallery_background));
        if (json == null) return;
        try {
            org.json.JSONObject bg = new org.json.JSONObject(json);
            if ("color".equals(bg.optString("kind"))) {
                coordinator.setBackgroundColor(android.graphics.Color.parseColor(bg.optString("value")));
            } else if ("image".equals(bg.optString("kind"))) {
                java.io.File file = new java.io.File(bg.optString("file"));
                int width = getResources().getDisplayMetrics().widthPixels;
                AppDatabase.IO.execute(() -> {
                    android.graphics.Bitmap bitmap = ChatSettingsActivity.decodeBackground(this, file, width);
                    runOnUiThread(() -> {
                        if (bitmap == null || isDestroyed()) return;
                        image.setImageBitmap(bitmap);
                        image.setVisibility(View.VISIBLE);
                    });
                });
            }
        } catch (Exception ignored) {
            // An unreadable background shows the default.
        }
    }

    /** "@name · ID AB12": exactly what someone types to reach this vault. */
    private void showAddress(Toolbar toolbar) {
        toolbar.setSubtitle((username == null ? "" : "@" + username + "  ·  ") + "ID " + code);
    }

    /** Once: the main vault opened with the lock it started with. Suggest a lock of its own. */
    private void offerOwnLock() {
        SecurityHelper.getAppPrefs(this).edit().remove(ChatSync.PREF_INHERITED_LOCK).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Give chats their own lock?")
                .setMessage("This vault still opens with the lock it was set up with. A lock used only here keeps it private even from someone who knows your other codes.")
                .setPositiveButton("Choose a lock", (d, w) -> startActivity(new Intent(this, ChatSettingsActivity.class)
                        .putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId)
                        .putExtra(ChatSettingsActivity.EXTRA_CHANGE_LOCK, true)))
                .setNegativeButton("Keep it", null)
                .show();
    }

    // ============================================================== new chat

    private void newChat() {
        if (Account.uid() == null) {
            Toast.makeText(this, "Sign in to your account first", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextInputLayout name = Prompts.field(this, "Username", null, Prompts.TEXT);
        TextInputLayout code = Prompts.field(this, "Vault ID (optional)", null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setHelperText("Leave empty to reach their main vault");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        box.addView(name);
        box.addView(code, lp);
        TextInputEditText nameInput = (TextInputEditText) name.getEditText();
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("New chat")
                .setView(Prompts.padded(this, box))
                .setPositiveButton("Start", null)
                .setNegativeButton("Cancel", null)
                .create();
        Prompts.wire(dialog, nameInput);
        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            v.setEnabled(false);
            ChatSync.resolve(Prompts.textOf(nameInput), Prompts.textOf((TextInputEditText) code.getEditText()), contact ->
                    AppDatabase.IO.execute(() -> {
                        int id = ChatSync.open(this, vaultId, contact);
                        runOnUiThread(() -> {
                            dialog.dismiss();
                            if (id > 0) openConversation(id);
                        });
                    }), error -> {
                v.setEnabled(true);
                name.setError(error);
            });
        });
    }

    /** A name and the people: usernames, each optionally /ID for a particular vault of theirs. */
    private void newGroup() {
        if (Account.uid() == null) {
            Toast.makeText(this, "Sign in to your account first", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextInputLayout name = Prompts.field(this, "Group name", null, Prompts.TEXT);
        TextInputLayout people = Prompts.field(this, "Members", null, Prompts.TEXT);
        people.setHelperText("Usernames, separated by commas. Add /ID for a particular vault, like nancy/AB12.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        box.addView(name);
        box.addView(people, lp);
        TextInputEditText nameInput = (TextInputEditText) name.getEditText();
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("New group")
                .setView(Prompts.padded(this, box))
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        Prompts.wire(dialog, nameInput);
        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String groupName = Prompts.textOf(nameInput);
            String list = Prompts.textOf((TextInputEditText) people.getEditText());
            v.setEnabled(false);
            ChatSync.resolveAll(list, contacts -> AppDatabase.IO.execute(() -> {
                try {
                    int id = ChatSync.createGroup(this, vaultId, groupName.length() > 60 ? groupName.substring(0, 60) : groupName, contacts);
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        openConversation(id);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        v.setEnabled(true);
                        people.setError(e.getMessage() == null ? "Couldn't create the group" : e.getMessage());
                    });
                }
            }), error -> {
                v.setEnabled(true);
                people.setError(error);
            });
        });
    }

    private void openConversation(int id) {
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId)
                .putExtra(ChatActivity.EXTRA_CONVERSATION, id));
    }

    private void showOptions(ChatStore.Conversation c) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(c.isGroup ? c.peerName : "@" + c.peerName)
                .setItems(new String[]{"Move to another vault…", c.isGroup ? "Leave group" : "Delete"}, (d, which) -> {
                    if (which == 0) askMove(c);
                    else if (c.isGroup) AppDatabase.IO.execute(() -> {
                        try {
                            ChatSync.leaveGroup(this, c.id);
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "Couldn't leave right now", Toast.LENGTH_LONG).show());
                        }
                    });
                    else confirmDelete(c);
                })
                .show();
    }

    /**
     * By ID, not from a list: a vault never shows which other vaults exist. The conversation
     * keeps its address; the other person notices nothing.
     */
    private void askMove(ChatStore.Conversation c) {
        Prompts.text(this, "Move to another vault", "Enter the ID of the vault to move this conversation to. "
                        + "@" + c.peerName + " keeps writing to the same address; you'll read and reply from there.",
                "Vault ID", null, android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
                "Move", raw -> {
                    String target = ChatSync.normaliseCode(raw);
                    AppDatabase.IO.execute(() -> {
                        ChatStore.Vault to = target == null || target.isEmpty() ? null : dao.vaultByCode(target);
                        runOnUiThread(() -> {
                            if (to == null) {
                                Toast.makeText(this, "No vault with that ID on this phone", Toast.LENGTH_LONG).show();
                            } else if (to.id == vaultId) {
                                Toast.makeText(this, "It's already here", Toast.LENGTH_SHORT).show();
                            } else {
                                AppDatabase.IO.execute(() -> ChatVaults.move(this, c, to));
                                Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                }, null);
    }

    private void confirmDelete(ChatStore.Conversation c) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this conversation?")
                .setMessage("It's removed from this phone. If @" + c.peerName + " writes again, it comes back with their recent messages.")
                .setPositiveButton("Delete", (d, w) -> AppDatabase.IO.execute(() -> {
                    dao.deleteMessagesOf(c.id);
                    dao.deleteConversation(c.id);
                    // Or the next fetch would bring it straight back.
                    String uid = Account.uid();
                    if (uid != null) Account.root().child("chatIndex").child(uid).child(c.chatId).removeValue();
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =============================================================== adapter

    private final class Adapter extends ListAdapter<ChatStore.Conversation, Adapter.Holder> {
        Adapter() {
            super(new DiffUtil.ItemCallback<ChatStore.Conversation>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChatStore.Conversation a, @NonNull ChatStore.Conversation b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull ChatStore.Conversation a, @NonNull ChatStore.Conversation b) {
                    return Objects.equals(a.lastText, b.lastText) && a.lastAt == b.lastAt && a.unread == b.unread
                            && Objects.equals(a.peerName, b.peerName) && Objects.equals(a.nickname, b.nickname)
                            && Objects.equals(a.peerPhoto, b.peerPhoto) && Objects.equals(a.peerShown, b.peerShown);
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_thread, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ChatStore.Conversation c = getItem(position);
            String name = c.peerName == null ? "?" : c.peerName;
            boolean nick = c.nickname != null && !c.nickname.isEmpty();
            h.initial.setText((nick ? c.nickname : name).substring(0, 1).toUpperCase(Locale.getDefault()));
            String theirVault = c.peerShown != null ? c.peerShown : c.peerVault;
            h.peer.setText(c.isGroup ? name : nick ? c.nickname : "@" + name + (theirVault == null ? "" : "  ·  " + theirVault));
            h.initial.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(ChatHomeActivity.this, c.isGroup ? R.color.tile_purple : R.color.tile_blue)));
            if (!Objects.equals(c.peerPhoto, h.avatar.getTag())) {
                ChatMedia.loadAvatar(ChatHomeActivity.this, h.avatar, c.peerPhoto, () -> h.avatar.setImageDrawable(null));
            }
            h.last.setText(c.lastText == null ? "Say hello" : c.lastText);
            h.time.setText(c.lastAt == 0 ? "" : DateUtils.getRelativeTimeSpanString(c.lastAt,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            h.unread.setVisibility(c.unread > 0 ? View.VISIBLE : View.GONE);
            h.unread.setText(c.unread > 99 ? "99+" : String.valueOf(c.unread));
            h.itemView.setOnClickListener(v -> openConversation(c.id));
            h.itemView.setOnLongClickListener(v -> {
                if (writable) showOptions(c);
                else Toast.makeText(ChatHomeActivity.this, "Open on " + holder + ". Changes can only be made there for now.",
                        Toast.LENGTH_LONG).show();
                return true;
            });
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView initial, peer, last, time, unread;
            final android.widget.ImageView avatar;

            Holder(View v) {
                super(v);
                avatar = v.findViewById(R.id.imgAvatar);
                initial = v.findViewById(R.id.txtInitial);
                peer = v.findViewById(R.id.txtPeer);
                last = v.findViewById(R.id.txtLast);
                time = v.findViewById(R.id.txtTime);
                unread = v.findViewById(R.id.txtUnread);
            }
        }
    }
}
