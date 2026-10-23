package org.flexstudios.notes.plus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * One conversation. Sending only stores the message; {@link ChatSync} delivers it in the
 * background, so leaving the screen or closing the app never loses it. While this screen is
 * open, new messages arrive live and are marked seen.
 *
 * Under each of my messages: a clock (waiting), one tick (sent), two ticks (received), two
 * yellow ticks (seen), or "Not sent" (tap to try again). Photos and videos show as pictures,
 * never as links; the background (colour or photo) is shared by both people.
 */
public class ChatActivity extends VaultActivity {

    static final String EXTRA_CONVERSATION = "conversation";

    /** Shared background presets: name and colour (null = default). */
    private static final String[] THEME_NAMES = {"Default", "Ocean", "Forest", "Sunset", "Plum", "Rose", "Slate", "Night"};
    private static final String[] THEME_COLORS = {null, "#1565C0", "#2E7D32", "#E65100", "#6A1B9A", "#AD1457", "#37474F", "#121212"};

    private ChatStore.Dao dao;
    private int conversationId;
    private Runnable stopLive = () -> {}, stopTheme = () -> {};
    private boolean visible, writable = true;
    private String shownTheme;
    private final android.os.Handler poller = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Read-only: no live updates, a fetch every two seconds instead. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            ChatSync.pollConversation(ChatActivity.this, conversationId);
            loadHeader();
            poller.postDelayed(this, 2_000);
        }
    };
    private final ChatVaults.Session.Listener session = this::onSession;
    private final Adapter adapter = new Adapter();
    /** Decoded pictures for bubbles; decrypted only in memory. */
    private final LruCache<String, Bitmap> pictures = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L << 20)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final ActivityResultLauncher<String[]> pickMedia = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::sendPicked);
    private final ActivityResultLauncher<String[]> pickBackground = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::setBackgroundPhoto);
    private final ActivityResultLauncher<String[]> mediaPermission = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> loadRecent());

    private static final String PREF_KEYBOARD = "chat_keyboard_height";
    /** The next photo or video goes as view-once. Reset after each send. */
    private boolean viewOnce;
    /** Height of the soft keyboard last time it showed: the picker takes exactly its place. */
    private int keyboardHeight;
    private final PickerAdapter picker = new PickerAdapter();
    private final androidx.activity.OnBackPressedCallback closePicker = new androidx.activity.OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            hidePicker();
        }
    };

    @Override
    protected String realm() {
        return UnlockActivity.REALM_CHAT;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        dao = AppDatabase.getInstance(this).chatDao();
        conversationId = getIntent().getIntExtra(EXTRA_CONVERSATION, -1);

        Toolbar toolbar = findViewById(R.id.toolbarChat);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            String action = String.valueOf(item.getTitle());
            if (!writable) {
                Toast.makeText(this, "Open on another phone. Changes can only be made there for now.", Toast.LENGTH_LONG).show();
            } else if ("Nickname".equals(action)) {
                askNickname();
            } else if ("Members".equals(action)) {
                showMembers();
            } else if ("Rename group".equals(action)) {
                askGroupName();
            } else if ("Leave group".equals(action)) {
                confirmLeave();
            } else {
                chooseBackground();
            }
            return true;
        });
        loadHeader();

        RecyclerView list = findViewById(R.id.recyclerMessages);
        LinearLayoutManager layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        list.setAdapter(adapter);
        dao.messages(conversationId).observe(this, messages -> {
            boolean atBottom = !list.canScrollVertically(1);
            adapter.submitList(messages, () -> {
                if (atBottom && !messages.isEmpty()) list.scrollToPosition(messages.size() - 1);
            });
            if (visible && writable) ChatSync.markSeen(this, conversationId);
        });

        EditText input = findViewById(R.id.inputMessage);
        messageList = list;
        // An empty box offers the microphone; typing turns it into Send.
        input.addTextChangedListener(NoteEditorActivity.simpleWatcher(this::updateSendButton));
        updateSendButton();
        findViewById(R.id.btnMic).setOnClickListener(v -> {
            if (recorder != null) stopRecording(true);
            else startRecording();
        });
        findViewById(R.id.btnCancelRecord).setOnClickListener(v -> stopRecording(false));
        findViewById(R.id.btnSend).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() || !writable) return;
            input.setText("");
            AppDatabase.IO.execute(() -> ChatSync.send(this, conversationId, text));
            list.post(() -> list.scrollToPosition(Math.max(0, adapter.getItemCount() - 1)));
        });
        findViewById(R.id.btnAttach).setOnClickListener(v -> togglePicker());
        input.setOnClickListener(v -> hidePicker());
        input.setOnFocusChangeListener((v, focused) -> {
            if (focused) hidePicker();
        });
        setUpPicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        AppDatabase.IO.execute(() -> {
            ChatStore.Vault vault = dao.vault(vaultId);
            runOnUiThread(() -> {
                if (vault == null) relock(); // Deleted, from another phone.
                else if (visible) ChatVaults.Session.enter(this, vault.code, session);
            });
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRecording(false);
        stopPlayback();
        visible = false;
        ChatVaults.Session.leave(session);
        stopLive.run();
        stopLive = () -> {};
        stopTheme.run();
        stopTheme = () -> {};
        poller.removeCallbacks(poll);
    }

    /**
     * Writer: live, marks seen, can send. Read-only (open on another phone): reads only, and
     * fetches every two seconds; not even "seen" is written from here.
     */
    private void onSession(boolean canWrite, @Nullable String by) {
        writable = canWrite;
        TextView banner = findViewById(R.id.txtReadOnly);
        banner.setVisibility(canWrite ? View.GONE : View.VISIBLE);
        banner.setText("Open on " + by + ". You can read here; it switches over when that phone leaves.");
        findViewById(R.id.composeBar).setVisibility(canWrite ? View.VISIBLE : View.GONE);
        stopLive.run();
        stopLive = () -> {};
        stopTheme.run();
        stopTheme = () -> {};
        poller.removeCallbacks(poll);
        if (canWrite) {
            ChatSync.markSeen(this, conversationId);
            stopLive = ChatSync.watchConversation(this, conversationId, () -> {
                if (visible && writable) ChatSync.markSeen(this, conversationId);
            });
            stopTheme = ChatSync.watchTheme(this, conversationId, this::loadHeader);
        } else {
            poller.post(poll);
        }
    }

    // ============================================================ header, theme

    /** Photo, title (nickname or @username), their vault's current ID, and the background. */
    private void loadHeader() {
        AppDatabase.IO.execute(() -> {
            ChatStore.Conversation c = dao.conversation(conversationId);
            runOnUiThread(() -> {
                if (c == null) {
                    finish();
                    return;
                }
                if (isDestroyed()) return;
                buildMenu(c.isGroup);
                if (c.isGroup) {
                    isGroup = true;
                    groupMembers = c.members;
                    String name = c.peerName == null || c.peerName.isEmpty() ? "Group" : c.peerName;
                    ((TextView) findViewById(R.id.txtChatTitle)).setText(name);
                    int count = memberCount(c.members);
                    ((TextView) findViewById(R.id.txtChatSubtitle)).setText(count + (count == 1 ? " member" : " members"));
                    ((TextView) findViewById(R.id.txtPeerInitial)).setText(name.substring(0, 1).toUpperCase(Locale.getDefault()));
                    ((ImageView) findViewById(R.id.imgPeer)).setImageDrawable(null);
                    applyTheme(c.theme);
                    return;
                }
                boolean nick = c.nickname != null && !c.nickname.isEmpty();
                String title = nick ? c.nickname : "@" + c.peerName;
                ((TextView) findViewById(R.id.txtChatTitle)).setText(title);
                ((TextView) findViewById(R.id.txtChatSubtitle)).setText((nick ? "@" + c.peerName + "  ·  " : "")
                        + "Vault " + (c.peerShown != null ? c.peerShown : c.peerVault));
                ((TextView) findViewById(R.id.txtPeerInitial)).setText(
                        title.replace("@", "").isEmpty() ? "?" : title.replace("@", "").substring(0, 1).toUpperCase(Locale.getDefault()));
                ImageView photo = findViewById(R.id.imgPeer);
                if (!Objects.equals(c.peerPhoto, photo.getTag())) {
                    ChatMedia.loadAvatar(this, photo, c.peerPhoto, () -> photo.setImageDrawable(null));
                }
                applyTheme(c.theme);
            });
        });
    }

    private boolean menuBuilt, isGroup;
    private String groupMembers;

    private void buildMenu(boolean group) {
        if (menuBuilt) return;
        menuBuilt = true;
        Menu menu = ((Toolbar) findViewById(R.id.toolbarChat)).getMenu();
        if (group) {
            menu.add("Members");
            menu.add("Rename group");
            menu.add("Background");
            menu.add("Leave group");
        } else {
            menu.add("Nickname");
            menu.add("Background");
        }
    }

    private static int memberCount(@Nullable String json) {
        try {
            return json == null ? 0 : new JSONObject(json).length();
        } catch (Exception e) {
            return 0;
        }
    }

    // ================================================================ groups

    private void showMembers() {
        AppDatabase.IO.execute(() -> {
            ChatStore.Conversation c = dao.conversation(conversationId);
            if (c == null) return;
            String owner = ChatSync.groupOwner(this, conversationId);
            String me = Account.uid();
            java.util.List<String[]> people = new java.util.ArrayList<>(); // {uid, name}
            try {
                JSONObject members = new JSONObject(c.members == null ? "{}" : c.members);
                for (java.util.Iterator<String> it = members.keys(); it.hasNext(); ) {
                    String uid = it.next();
                    JSONObject info = members.optJSONObject(uid);
                    people.add(new String[]{uid, info == null ? "?" : info.optString("name", "?")});
                }
            } catch (Exception ignored) {
                // Shown as an empty list.
            }
            java.util.Collections.sort(people, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            String[] rows = new String[people.size()];
            for (int i = 0; i < rows.length; i++) {
                String uid = people.get(i)[0];
                rows[i] = "@" + people.get(i)[1] + (uid.equals(owner) ? "  · owner" : "") + (uid.equals(me) ? "  (you)" : "");
            }
            boolean iOwn = me != null && me.equals(owner);
            int count = rows.length;
            runOnUiThread(() -> {
                MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(count + " of " + ChatSync.MAX_GROUP + " members" + (iOwn ? "  ·  tap to remove" : ""))
                        .setItems(rows, (d, which) -> {
                            String[] who = people.get(which);
                            if (iOwn && !who[0].equals(me)) confirmRemove(who[0], who[1]);
                        })
                        .setNegativeButton("Close", null);
                if (count < ChatSync.MAX_GROUP) dialog.setPositiveButton("Add people", (d, w) -> askAddPeople());
                dialog.show();
            });
        });
    }

    private void confirmRemove(String uid, String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove @" + name + "?")
                .setMessage("They stop getting this group's messages and can't read it any more. What's already on their phone stays there.")
                .setPositiveButton("Remove", (d, w) -> AppDatabase.IO.execute(() -> {
                    String error = null;
                    try {
                        ChatSync.removeFromGroup(this, conversationId, uid);
                    } catch (Exception e) {
                        error = "Couldn't remove them";
                    }
                    String message = error;
                    runOnUiThread(() -> {
                        Toast.makeText(this, message != null ? message : "@" + name + " removed", Toast.LENGTH_SHORT).show();
                        loadHeader();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askAddPeople() {
        Prompts.text(this, "Add people", "Usernames, separated by commas. Add /ID to reach a particular vault, "
                        + "like nancy/AB12.", "Usernames", null, Prompts.TEXT, "Add",
                raw -> ChatSync.resolveAll(raw, people -> AppDatabase.IO.execute(() -> {
                    String error = null;
                    try {
                        ChatSync.addToGroup(this, conversationId, people);
                    } catch (Exception e) {
                        error = e.getMessage() == null ? "Couldn't add them" : e.getMessage();
                    }
                    String message = error;
                    runOnUiThread(() -> {
                        Toast.makeText(this, message != null ? message : "Added", Toast.LENGTH_LONG).show();
                        loadHeader();
                    });
                }), error -> Toast.makeText(this, error, Toast.LENGTH_LONG).show()), null);
    }

    private void askGroupName() {
        String current = ((TextView) findViewById(R.id.txtChatTitle)).getText().toString();
        Prompts.text(this, "Rename group", "Everyone in the group sees the new name.", "Group name", current,
                Prompts.TEXT, "Save", name -> AppDatabase.IO.execute(() -> {
                    try {
                        ChatSync.renameGroup(this, conversationId, name.length() > 60 ? name.substring(0, 60) : name);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Couldn't rename it", Toast.LENGTH_LONG).show());
                    }
                    runOnUiThread(this::loadHeader);
                }), null);
    }

    private void confirmLeave() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Leave this group?")
                .setMessage("You won't get its messages any more, and it's removed from this vault.")
                .setPositiveButton("Leave", (d, w) -> AppDatabase.IO.execute(() -> {
                    try {
                        ChatSync.leaveGroup(this, conversationId);
                        runOnUiThread(this::finish);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Couldn't leave right now", Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================================== voice notes

    private android.media.MediaRecorder recorder;
    private File recording;
    private long recordStart;
    private final java.util.List<Float> levels = new java.util.ArrayList<>();
    private final android.os.Handler recordTicker = new android.os.Handler(android.os.Looper.getMainLooper());
    private RecyclerView messageList;
    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startRecording();
                else Toast.makeText(this, "Voice messages need the microphone", Toast.LENGTH_LONG).show();
            });

    private void updateSendButton() {
        boolean typing = ((EditText) findViewById(R.id.inputMessage)).getText().toString().trim().length() > 0;
        boolean recordingNow = recorder != null;
        findViewById(R.id.btnSend).setVisibility(typing && !recordingNow ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnMic).setVisibility(typing && !recordingNow ? View.GONE : View.VISIBLE);
    }

    private void startRecording() {
        if (!writable) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            expectExternalActivity();
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }
        if (Account.driveEmail(this) == null) {
            needsDrive();
            return;
        }
        stopPlayback();
        hidePicker();
        recording = new File(ChatMedia.plainDir(this), "voice_" + System.currentTimeMillis() + ".m4a");
        android.media.MediaRecorder r = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                ? new android.media.MediaRecorder(this) : new android.media.MediaRecorder();
        try {
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(64_000);
            r.setAudioSamplingRate(44_100);
            r.setOutputFile(recording.getAbsolutePath());
            r.prepare();
            r.start();
        } catch (Exception e) {
            r.release();
            VaultCrypto.deleteRecursive(recording);
            Toast.makeText(this, "The microphone isn't available", Toast.LENGTH_SHORT).show();
            return;
        }
        recorder = r;
        recordStart = android.os.SystemClock.elapsedRealtime();
        levels.clear();
        findViewById(R.id.inputMessage).setVisibility(View.GONE);
        findViewById(R.id.btnAttach).setVisibility(View.GONE);
        findViewById(R.id.recordBar).setVisibility(View.VISIBLE);
        ((ImageView) findViewById(R.id.btnMic)).setImageResource(R.drawable.ic_send);
        findViewById(R.id.btnMic).setContentDescription("Send voice message");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateSendButton();
        recordTicker.post(sampleLevel);
    }

    /** Every 80 ms: the live waveform and the timer. */
    private final Runnable sampleLevel = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) return;
            levels.add(Math.min(1f, (float) Math.sqrt(recorder.getMaxAmplitude() / 32767f)));
            ((AudioNoteActivity.WaveformView) findViewById(R.id.recordWave)).setLive(levels);
            long elapsed = android.os.SystemClock.elapsedRealtime() - recordStart;
            ((TextView) findViewById(R.id.txtRecordTime)).setText(clock(elapsed));
            if (elapsed > 15 * 60_000) {
                stopRecording(true); // A quarter of an hour is plenty for one message.
                return;
            }
            recordTicker.postDelayed(this, 80);
        }
    };

    /** {@code send}: send it; otherwise throw it away. */
    private void stopRecording(boolean send) {
        if (recorder == null) return;
        recordTicker.removeCallbacks(sampleLevel);
        long duration = android.os.SystemClock.elapsedRealtime() - recordStart;
        try {
            recorder.stop();
        } catch (RuntimeException tooShort) {
            duration = 0; // Stopped within a moment: no audio.
        }
        recorder.release();
        recorder = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        findViewById(R.id.recordBar).setVisibility(View.GONE);
        findViewById(R.id.inputMessage).setVisibility(View.VISIBLE);
        findViewById(R.id.btnAttach).setVisibility(View.VISIBLE);
        ((ImageView) findViewById(R.id.btnMic)).setImageResource(R.drawable.ic_mic);
        findViewById(R.id.btnMic).setContentDescription("Record a voice message");
        updateSendButton();
        File file = recording;
        recording = null;
        if (!send || duration < 700) {
            VaultCrypto.deleteRecursive(file);
            if (send) Toast.makeText(this, "Hold on a little longer to record", Toast.LENGTH_SHORT).show();
            return;
        }
        float[] wave = downsample(levels, 40);
        long length = duration;
        AppDatabase.IO.execute(() -> {
            try {
                ChatSync.sendVoice(this, conversationId, file, length, wave);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't send the voice message", Toast.LENGTH_LONG).show());
            }
        });
    }

    private static float[] downsample(java.util.List<Float> values, int count) {
        float[] out = new float[count];
        if (values.isEmpty()) return out;
        for (int i = 0; i < count; i++) {
            int from = i * values.size() / count, to = Math.max(from + 1, (i + 1) * values.size() / count);
            float peak = 0;
            for (int k = from; k < to && k < values.size(); k++) peak = Math.max(peak, values.get(k));
            out[i] = peak;
        }
        return out;
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---- playback: one voice note at a time, decrypted to a temporary file while it plays

    private AudioPlayback player;
    private String playingKey;
    private float[] playingWave;
    private File playingFile;
    private final android.os.Handler playTicker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable playProgress = new Runnable() {
        @Override
        public void run() {
            if (player == null || playingKey == null || messageList == null) return;
            View view = messageList.findViewWithTag(playingKey);
            if (view instanceof AudioNoteActivity.WaveformView && player.getDuration() > 0) {
                ((AudioNoteActivity.WaveformView) view).setWave(playingWave,
                        player.getCurrentPosition() / (float) player.getDuration());
            }
            playTicker.postDelayed(this, 60);
        }
    };

    private void togglePlay(ChatStore.Message m, ChatMedia.Meta meta) {
        if (m.msgKey.equals(playingKey) && player != null) {
            if (player.isPlaying()) player.pause();
            else player.start();
            refreshRow(m.msgKey);
            return;
        }
        stopPlayback();
        playingKey = m.msgKey;
        playingWave = meta.waveform();
        AppDatabase.IO.execute(() -> {
            try {
                File file = ChatMedia.decryptToTemp(this, m.msgKey, meta);
                runOnUiThread(() -> {
                    if (!m.msgKey.equals(playingKey) || isFinishing()) {
                        VaultCrypto.deleteRecursive(file);
                        return;
                    }
                    try {
                        playingFile = file;
                        // Desktop voice messages are WAV: AudioPlayback plays those on every phone.
                        player = AudioPlayback.open(file);
                        player.setOnCompletionListener(this::stopPlayback);
                        player.start();
                        playTicker.post(playProgress);
                        refreshRow(m.msgKey);
                    } catch (Exception e) {
                        stopPlayback();
                        Toast.makeText(this, "Couldn't play it", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (m.msgKey.equals(playingKey)) stopPlayback();
                    Toast.makeText(this, "Couldn't fetch the voice message", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void refreshRow(String key) {
        for (int i = 0; i < adapter.getCurrentList().size(); i++) {
            if (adapter.getCurrentList().get(i).msgKey.equals(key)) adapter.notifyItemChanged(i);
        }
    }

    private void stopPlayback() {
        playTicker.removeCallbacks(playProgress);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
                // Released either way.
            }
            player = null;
        }
        if (playingFile != null) VaultCrypto.deleteRecursive(playingFile);
        playingFile = null;
        String was = playingKey;
        playingKey = null;
        if (was != null) refreshRow(was);
    }

    private void bindVoice(Adapter.Holder h, ChatStore.Message m, ChatMedia.Meta meta) {
        h.mediaFrame.setVisibility(View.GONE);
        h.voiceRow.setVisibility(View.VISIBLE);
        int played = m.mine ? 0xFFFFFFFF : ContextCompat.getColor(this, R.color.tile_blue);
        int rest = m.mine ? 0x80FFFFFF : ContextCompat.getColor(this, R.color.text_secondary);
        h.voiceWave.setColors(played, rest);
        h.voiceWave.setTag(m.msgKey);
        boolean current = m.msgKey.equals(playingKey) && player != null;
        float progress = current && player.getDuration() > 0 ? player.getCurrentPosition() / (float) player.getDuration() : 0;
        h.voiceWave.setWave(meta.waveform(), progress);
        h.voiceLength.setText(clock(meta.dur));
        h.voiceLength.setTextColor(m.mine ? 0xCCFFFFFF : ContextCompat.getColor(this, R.color.text_secondary));
        h.playVoice.setImageResource(current && player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        h.playVoice.setImageTintList(ColorStateList.valueOf(m.mine ? 0xFFFFFFFF : ContextCompat.getColor(this, R.color.tile_blue)));
        h.playVoice.setOnClickListener(v -> togglePlay(m, meta));
    }

    private void applyTheme(@Nullable String json) {
        if (Objects.equals(json, shownTheme)) return;
        shownTheme = json;
        View root = findViewById(R.id.chatRoot);
        ImageView image = findViewById(R.id.imgBackground);
        View dim = findViewById(R.id.backgroundDim);
        image.setVisibility(View.GONE);
        dim.setVisibility(View.GONE);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.gallery_background));
        if (json == null) return;
        try {
            JSONObject theme = new JSONObject(json);
            if ("color".equals(theme.optString("kind"))) {
                root.setBackgroundColor(Color.parseColor(theme.optString("value")));
            } else if ("image".equals(theme.optString("kind"))) {
                ChatMedia.Meta meta = ChatMedia.Meta.parse(theme.optJSONObject("media") == null ? null
                        : theme.optJSONObject("media").toString());
                if (meta == null || meta.id == null) return;
                int width = getResources().getDisplayMetrics().widthPixels;
                AppDatabase.IO.execute(() -> {
                    Bitmap bitmap = decode(ChatSync.themeTag(meta.id), meta, width);
                    runOnUiThread(() -> {
                        if (bitmap == null || !Objects.equals(json, shownTheme)) return;
                        image.setImageBitmap(bitmap);
                        image.setVisibility(View.VISIBLE);
                        dim.setVisibility(View.VISIBLE);
                    });
                });
            }
        } catch (Exception ignored) {
            // An unreadable theme shows the default background.
        }
    }

    private void chooseBackground() {
        String[] options = new String[THEME_NAMES.length + 1];
        System.arraycopy(THEME_NAMES, 0, options, 0, THEME_NAMES.length);
        options[THEME_NAMES.length] = "A photo…";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Background")
                .setItems(options, (d, which) -> {
                    if (which < THEME_COLORS.length) {
                        AppDatabase.IO.execute(() -> {
                            ChatSync.setThemeColor(this, conversationId, THEME_COLORS[which]);
                            runOnUiThread(this::loadHeader);
                        });
                    } else if (!needsDrive()) {
                        expectExternalActivity();
                        pickBackground.launch(new String[]{"image/*"});
                    }
                })
                .show();
    }

    private void setBackgroundPhoto(@Nullable Uri uri) {
        if (uri == null) return;
        AlertDialog progress = Prompts.progress(this, "Setting background");
        progress.setMessage("Encrypting and uploading");
        AppDatabase.IO.execute(() -> {
            String error = null;
            try {
                ChatSync.setThemeImage(this, conversationId, uri);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Couldn't set the background" : e.getMessage();
            }
            String message = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                loadHeader();
            });
        });
    }

    private void askNickname() {
        AppDatabase.IO.execute(() -> {
            ChatStore.Conversation c = dao.conversation(conversationId);
            if (c == null) return;
            runOnUiThread(() -> Prompts.text(this, "Nickname for @" + c.peerName,
                    "Only you see it, on all your phones.", "Nickname", c.nickname, Prompts.TEXT, "Save",
                    name -> saveNickname(name.length() > 40 ? name.substring(0, 40) : name), null,
                    "Remove", ignored -> saveNickname(null)));
        });
    }

    private void saveNickname(@Nullable String name) {
        AppDatabase.IO.execute(() -> {
            ChatSync.setNickname(this, conversationId, name);
            runOnUiThread(this::loadHeader);
        });
    }

    // ================================================================ media

    /** Photos and videos travel through the sender's own Drive. */
    private boolean needsDrive() {
        if (Account.driveEmail(this) != null) return false;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Connect Google Drive first")
                .setMessage("Photos, videos and backgrounds are encrypted and stored in your own Google Drive. Connect it in your Notes+ account.")
                .setPositiveButton("Open account", (d, w) -> {
                    expectExternalActivity();
                    startActivity(new Intent(this, AccountActivity.class));
                })
                .setNegativeButton("Not now", null)
                .show();
        return true;
    }

    private void sendPicked(@Nullable Uri uri) {
        if (uri == null) return;
        EditText input = findViewById(R.id.inputMessage);
        String caption = input.getText().toString().trim();
        input.setText("");
        boolean once = viewOnce;
        if (once) {
            viewOnce = false;
            com.google.android.material.button.MaterialButton toggle = findViewById(R.id.btnViewOnce);
            toggle.setText("View once: off");
            toggle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
        AppDatabase.IO.execute(() -> {
            try {
                ChatSync.sendMedia(this, conversationId, uri, caption, once);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage() == null ? "Couldn't send that file"
                        : e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Decrypts and decodes a picture no wider than {@code width}. Background thread; null on failure. */
    @Nullable
    private Bitmap decode(String tag, ChatMedia.Meta meta, int width) {
        Bitmap cached = pictures.get(tag);
        if (cached != null) return cached;
        try {
            byte[] bytes = ChatMedia.decryptBytes(this, tag, meta);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null) pictures.put(tag, bitmap);
            return bitmap;
        } catch (Exception e) {
            android.util.Log.w("ChatActivity", "Couldn't show a picture", e);
            return null;
        }
    }

    private void bindMedia(Adapter.Holder h, ChatStore.Message m, ChatMedia.Meta meta) {
        float density = getResources().getDisplayMetrics().density;
        int width = Math.round(240 * density);
        int height = meta.w > 0 && meta.h > 0 ? (int) (width * meta.h / meta.w) : Math.round(180 * density);
        height = Math.max(Math.round(120 * density), Math.min(height, Math.round(320 * density)));
        ViewGroup.LayoutParams lp = h.mediaFrame.getLayoutParams();
        lp.width = width;
        lp.height = height;
        h.mediaFrame.setLayoutParams(lp);
        h.mediaFrame.setClipToOutline(true);
        h.mediaFrame.setVisibility(View.VISIBLE);
        h.picture.setImageDrawable(null);
        h.play.setVisibility(meta.isVideo() ? View.VISIBLE : View.GONE);
        h.mediaInfo.setVisibility(meta.isVideo() && meta.dur > 0 ? View.VISIBLE : View.GONE);
        if (meta.isVideo()) {
            long s = meta.dur / 1000;
            h.mediaInfo.setText(String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60));
            h.picture.setContentDescription("Video");
        } else {
            h.picture.setContentDescription("Photo");
            h.picture.setTag(m.msgKey);
            Bitmap cached = pictures.get(m.msgKey);
            if (cached != null) {
                h.picture.setImageBitmap(cached);
            } else {
                AppDatabase.IO.execute(() -> {
                    Bitmap bitmap = decode(m.msgKey, meta, width);
                    runOnUiThread(() -> {
                        if (bitmap != null && m.msgKey.equals(h.picture.getTag())) h.picture.setImageBitmap(bitmap);
                    });
                });
            }
        }
        h.mediaFrame.setOnClickListener(v -> startActivity(new Intent(this, ChatMediaActivity.class)
                .putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId)
                .putExtra(ChatMediaActivity.EXTRA_TAG, m.msgKey)
                .putExtra(ChatMediaActivity.EXTRA_META, m.media)));
    }

    // ======================================================== media picker

    /**
     * WhatsApp-style: the attach button swaps the keyboard for the newest photos and videos,
     * in exactly the keyboard's space. Typing (or Back) swaps the keyboard back.
     */
    private void setUpPicker() {
        float density = getResources().getDisplayMetrics().density;
        keyboardHeight = SecurityHelper.getAppPrefs(this).getInt(PREF_KEYBOARD, Math.round(290 * density));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot), (v, insets) -> {
            int ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    - insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            if (ime > 120 * density && ime != keyboardHeight) {
                keyboardHeight = ime;
                SecurityHelper.getAppPrefs(this).edit().putInt(PREF_KEYBOARD, ime).apply();
            }
            return insets;
        });
        getOnBackPressedDispatcher().addCallback(this, closePicker);
        RecyclerView grid = findViewById(R.id.recyclerPicker);
        grid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 4));
        grid.setAdapter(picker);
        com.google.android.material.button.MaterialButton once = findViewById(R.id.btnViewOnce);
        once.setOnClickListener(v -> {
            viewOnce = !viewOnce;
            once.setText(viewOnce ? "View once: on" : "View once: off");
            once.setTextColor(ContextCompat.getColor(this, viewOnce ? R.color.brand_yellow : R.color.text_secondary));
        });
        findViewById(R.id.btnAllPhotos).setOnClickListener(v -> {
            hidePicker();
            expectExternalActivity();
            pickMedia.launch(new String[]{"image/*", "video/*"});
        });
    }

    private void togglePicker() {
        View panel = findViewById(R.id.pickerPanel);
        if (panel.getVisibility() == View.VISIBLE) {
            hidePicker();
            EditText input = findViewById(R.id.inputMessage);
            input.requestFocus();
            android.view.inputmethod.InputMethodManager ime = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (ime != null) ime.showSoftInput(input, 0);
            return;
        }
        if (needsDrive()) return;
        View input = findViewById(R.id.inputMessage);
        android.view.inputmethod.InputMethodManager ime = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.height = keyboardHeight;
        panel.setLayoutParams(lp);
        // Once the keyboard has gone, so the two don't stack for a frame.
        panel.postDelayed(() -> {
            panel.setVisibility(View.VISIBLE);
            closePicker.setEnabled(true);
        }, 120);
        if (hasMediaAccess()) {
            loadRecent();
        } else {
            expectExternalActivity();
            mediaPermission.launch(android.os.Build.VERSION.SDK_INT >= 33
                    ? new String[]{android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO}
                    : new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE});
        }
    }

    private void hidePicker() {
        findViewById(R.id.pickerPanel).setVisibility(View.GONE);
        closePicker.setEnabled(false);
    }

    private boolean hasMediaAccess() {
        String[] permissions = android.os.Build.VERSION.SDK_INT >= 34
                ? new String[]{android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}
                : android.os.Build.VERSION.SDK_INT >= 33
                ? new String[]{android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    /** The newest 120 photos and videos on the phone. */
    private void loadRecent() {
        TextView title = findViewById(R.id.txtPickerTitle);
        if (!hasMediaAccess()) {
            title.setText("Allow photo access to see recent ones here");
            picker.submit(new java.util.ArrayList<>());
            return;
        }
        title.setText("Recent");
        AppDatabase.IO.execute(() -> {
            java.util.List<PickerItem> items = new java.util.ArrayList<>();
            Uri files = android.provider.MediaStore.Files.getContentUri("external");
            String[] projection = android.os.Build.VERSION.SDK_INT >= 29
                    ? new String[]{"_id", "media_type", "duration"} : new String[]{"_id", "media_type"};
            try (android.database.Cursor c = getContentResolver().query(files, projection,
                    "media_type IN (1, 3)", null, "date_added DESC")) {
                while (c != null && c.moveToNext() && items.size() < 120) {
                    long id = c.getLong(0);
                    boolean video = c.getInt(1) == 3;
                    PickerItem item = new PickerItem();
                    item.uri = android.content.ContentUris.withAppendedId(video
                            ? android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    item.durationMs = video && c.getColumnCount() > 2 && !c.isNull(2) ? c.getLong(2) : (video ? -1 : 0);
                    items.add(item);
                }
            } catch (Exception e) {
                android.util.Log.w("ChatActivity", "Couldn't list recent media", e);
            }
            runOnUiThread(() -> picker.submit(items));
        });
    }

    private static final class PickerItem {
        Uri uri;
        /** 0 for photos, -1 for a video of unknown length. */
        long durationMs;
    }

    private final class PickerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private java.util.List<PickerItem> items = new java.util.ArrayList<>();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void submit(java.util.List<PickerItem> next) {
            items = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int size = parent.getWidth() > 0 ? parent.getWidth() / 4 : getResources().getDisplayMetrics().widthPixels / 4;
            FrameLayout cell = new FrameLayout(ChatActivity.this);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));
            cell.setPadding(2, 2, 2, 2);
            ImageView image = new ImageView(ChatActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0x22000000);
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView length = new TextView(ChatActivity.this);
            length.setTextColor(Color.WHITE);
            length.setTextSize(11);
            length.setShadowLayer(4, 0, 0, Color.BLACK);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, 10, 6);
            cell.addView(length, lp);
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            PickerItem item = items.get(position);
            FrameLayout cell = (FrameLayout) holder.itemView;
            ImageView image = (ImageView) cell.getChildAt(0);
            TextView length = (TextView) cell.getChildAt(1);
            com.bumptech.glide.Glide.with(ChatActivity.this).load(item.uri).centerCrop()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).into(image);
            long s = item.durationMs / 1000;
            length.setText(item.durationMs > 0 ? String.format(Locale.ROOT, "▶ %d:%02d", s / 60, s % 60)
                    : item.durationMs < 0 ? "▶" : "");
            cell.setOnClickListener(v -> {
                hidePicker();
                sendPicked(item.uri);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // =============================================================== adapter

    private final class Adapter extends ListAdapter<ChatStore.Message, Adapter.Holder> {
        Adapter() {
            super(new DiffUtil.ItemCallback<ChatStore.Message>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChatStore.Message a, @NonNull ChatStore.Message b) {
                    return a.msgKey.equals(b.msgKey);
                }

                @Override
                public boolean areContentsTheSame(@NonNull ChatStore.Message a, @NonNull ChatStore.Message b) {
                    return a.status == b.status && Objects.equals(a.text, b.text) && a.at == b.at
                            && Objects.equals(a.media, b.media);
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ChatStore.Message m = getItem(position);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
            lp.gravity = m.mine ? Gravity.END : Gravity.START;
            h.bubble.setLayoutParams(lp);
            ((LinearLayout) h.bubble).setGravity(m.mine ? Gravity.END : Gravity.START);
            int bubbleColor = ContextCompat.getColor(ChatActivity.this, m.mine ? R.color.tile_blue : R.color.app_surface);
            int textColor = m.mine ? 0xFFFFFFFF : ContextCompat.getColor(ChatActivity.this, R.color.text_primary);
            int metaColor = m.mine ? 0xCCFFFFFF : ContextCompat.getColor(ChatActivity.this, R.color.text_secondary);
            h.bubble.setBackgroundTintList(ColorStateList.valueOf(m.status == ChatStore.FAILED
                    ? ContextCompat.getColor(ChatActivity.this, R.color.destructive) : bubbleColor));

            boolean showSender = isGroup && !m.mine && m.senderName != null;
            h.sender.setVisibility(showSender ? View.VISIBLE : View.GONE);
            if (showSender) h.sender.setText(m.senderName);

            ChatMedia.Meta meta = ChatMedia.Meta.parse(m.media);
            h.voiceRow.setVisibility(View.GONE);
            if (meta != null && meta.isAudio()) bindVoice(h, m, meta);
            else if (meta != null && !meta.once) bindMedia(h, m, meta);
            else h.mediaFrame.setVisibility(View.GONE);

            boolean once = meta != null && meta.once;
            boolean hasText = (m.text != null && !m.text.isEmpty()) || once;
            h.body.setVisibility(hasText ? View.VISIBLE : View.GONE);
            h.body.setTextColor(textColor);
            h.body.setLinkTextColor(textColor);
            h.body.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            if (once) {
                // Never a preview: just what it is, and whether it was opened.
                boolean opened = ChatSync.hasFlag(m.media, "opened");
                String kind = meta.isVideo() ? "Video" : "Photo";
                String line = m.mine ? (opened ? "View-once " + kind.toLowerCase(Locale.ROOT) + " · opened"
                        : "View-once " + kind.toLowerCase(Locale.ROOT) + " · not opened yet")
                        : (opened ? kind + " · opened" : kind + " · tap to view once");
                h.body.setText(m.text == null || m.text.isEmpty() ? line : line + "\n" + m.text);
                android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(ChatActivity.this,
                        opened ? R.drawable.ic_check_circle_empty : R.drawable.ic_timer);
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setTint(m.mine ? 0xFFFFFFFF : ContextCompat.getColor(ChatActivity.this, R.color.brand_yellow));
                    int size = Math.round(18 * getResources().getDisplayMetrics().density);
                    icon.setBounds(0, 0, size, size);
                    h.body.setCompoundDrawablesRelative(icon, null, null, null);
                    h.body.setCompoundDrawablePadding(Math.round(6 * getResources().getDisplayMetrics().density));
                }
            } else {
                h.body.setText(m.text);
            }
            h.meta.setTextColor(metaColor);
            String time = DateFormat.getTimeFormat(ChatActivity.this).format(new Date(m.at));
            h.meta.setText(m.status != ChatStore.FAILED ? time
                    : meta != null && Account.driveEmail(ChatActivity.this) == null
                    ? "Not sent · connect Google Drive, then tap" : "Not sent · tap to retry");

            h.status.setVisibility(m.mine && m.status != ChatStore.FAILED ? View.VISIBLE : View.GONE);
            if (m.mine) {
                int icon = m.status == ChatStore.WAITING ? R.drawable.ic_timer
                        : m.status == ChatStore.SENT ? R.drawable.ic_check : R.drawable.ic_done_all;
                h.status.setImageResource(icon);
                h.status.setImageTintList(ColorStateList.valueOf(m.status == ChatStore.SEEN
                        ? ContextCompat.getColor(ChatActivity.this, R.color.brand_yellow) : metaColor));
                h.status.setContentDescription(m.status == ChatStore.WAITING ? "Waiting"
                        : m.status == ChatStore.SENT ? "Sent" : m.status == ChatStore.RECEIVED ? "Received" : "Seen");
            }

            h.bubble.setOnClickListener(m.status == ChatStore.FAILED && writable
                    ? v -> ChatSync.retry(ChatActivity.this, m) : null);
            // Read-only phones can't open them: opening uses the one view up.
            if (once && !m.mine && writable && !ChatSync.hasFlag(m.media, "opened")) {
                h.bubble.setOnClickListener(v -> startActivity(new Intent(ChatActivity.this, ChatMediaActivity.class)
                        .putExtra(UnlockActivity.EXTRA_VAULT_ID, vaultId)
                        .putExtra(ChatMediaActivity.EXTRA_TAG, m.msgKey)
                        .putExtra(ChatMediaActivity.EXTRA_META, m.media)
                        .putExtra(ChatMediaActivity.EXTRA_ONCE, true)));
            }
            h.bubble.setOnLongClickListener(v -> {
                if (!hasText) return false;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Message", m.text));
                Toast.makeText(ChatActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View bubble, mediaFrame, voiceRow;
            final TextView body, meta, mediaInfo, sender, voiceLength;
            final ImageView status, picture, play, playVoice;
            final AudioNoteActivity.WaveformView voiceWave;

            Holder(View v) {
                super(v);
                voiceRow = v.findViewById(R.id.voiceRow);
                voiceWave = v.findViewById(R.id.voiceWave);
                voiceLength = v.findViewById(R.id.txtVoiceLength);
                playVoice = v.findViewById(R.id.btnPlayVoice);
                sender = v.findViewById(R.id.txtSender);
                bubble = v.findViewById(R.id.bubble);
                mediaFrame = v.findViewById(R.id.mediaFrame);
                picture = v.findViewById(R.id.imgMedia);
                play = v.findViewById(R.id.imgPlay);
                mediaInfo = v.findViewById(R.id.txtMediaInfo);
                body = v.findViewById(R.id.txtBody);
                meta = v.findViewById(R.id.txtMeta);
                status = v.findViewById(R.id.imgStatus);
            }
        }
    }
}
