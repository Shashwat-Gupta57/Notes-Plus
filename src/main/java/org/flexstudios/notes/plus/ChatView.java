package org.flexstudios.notes.plus;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One conversation, the phone's ChatActivity. Sending only stores the message; {@link ChatSync}
 * delivers it in the background. While this screen is open, new messages arrive live and are
 * marked seen.
 *
 * Under each of my messages: a clock (waiting), one tick (sent), two ticks (received), two
 * yellow ticks (seen), or "Not sent" (click to try again). Photos and videos show as pictures;
 * the background is shared by both people.
 *
 * Desktop ways in for the phone's touches: Enter sends (Shift+Enter is a new line), a picture
 * pasted or a file dropped is sent, right-click copies a message.
 */
final class ChatView extends Screen {

    private final int vaultId, conversationId;
    private final StackPane root = new StackPane();
    private final ImageView backgroundImage = new ImageView();
    private final Region backgroundDim = new Region();
    private final ChatUi.Avatar avatar = new ChatUi.Avatar(38);
    private final Label title = new Label(), subtitle = Ui.label("", 12, "secondary");
    private final Button menu = Ui.iconButton(Icons.MORE, "text_primary", "More", this::showMenu);
    private final ListView<ChatStore.Message> list = new ListView<>();
    private final Label banner = ChatUi.readOnlyBanner();
    private final HBox compose = new HBox(8);
    private final TextArea input = new TextArea();
    private final Button attach = ChatUi.round(Icons.PHOTO, 44, "app_surface", "text_primary", "Photo or video (or paste / drop one)", this::askAttach);
    private final Button mic = ChatUi.round(Icons.MIC, 44, "tile_blue", "white", "Record a voice message", this::toggleRecording);
    private final Button send = ChatUi.round(Icons.SEND, 44, "tile_blue", "white", "Send (Enter)", this::sendTyped);
    private final HBox recordBar = new HBox(10);
    private final Label recordTime = Ui.label("0:00", 14);
    private final WaveformView recordWave = new WaveformView(28);
    private final Runnable onChange = this::reload;
    private final ChatVaults.Session.Listener session = this::onSession;
    private final Timeline poller = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
        ChatSync.pollConversation(conversationId());
        loadHeader();
    }));
    private Runnable stopLive = () -> {};
    private boolean visible, writable = true, isGroup;
    private String shownTheme, peerName = "";
    /** Decoded pictures for bubbles; decrypted only in memory. */
    private final Map<String, Image> pictures = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > 80;
        }
    };
    private final java.util.Set<String> decoding = new java.util.HashSet<>();

    ChatView(int vaultId, int conversationId) {
        this.vaultId = vaultId;
        this.conversationId = conversationId;
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        backgroundImage.setPreserveRatio(false);
        backgroundImage.fitWidthProperty().bind(root.widthProperty());
        backgroundImage.fitHeightProperty().bind(root.heightProperty());
        backgroundImage.setVisible(false);
        backgroundImage.setManaged(false);
        backgroundDim.setStyle("-fx-background-color: #00000040;");
        backgroundDim.setVisible(false);
        backgroundDim.setMouseTransparent(true);

        title.setStyle("-fx-text-fill: " + Theme.ref("text_primary") + "; -fx-font-size: 17px; -fx-font-weight: bold;");
        VBox titles = new VBox(0, title, subtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(10, Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Back (Esc)", NotesApp::back), avatar, titles,
                Ui.spacer(), menu);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("toolbar");
        // The toolbar's stylesheet spacing wins over setSpacing: margins it is.
        HBox.setMargin(avatar, new Insets(0, 12, 0, 4));
        header.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");

        list.getStyleClass().add("chat-list");
        list.setPlaceholder(new Label());
        list.setCellFactory(v -> new Bubble());
        list.setStyle("-fx-padding: 8 0 8 0;");
        list.setFocusTraversable(false);

        buildCompose();
        VBox bottom = new VBox(banner, compose);
        BorderPane frame = new BorderPane(list);
        frame.setTop(header);
        frame.setBottom(bottom);
        root.getChildren().addAll(backgroundImage, backgroundDim, frame);
        poller.setCycleCount(Timeline.INDEFINITE);

        // Files dropped on the conversation are sent, like picking them.
        root.setOnDragOver(e -> {
            if (writable && e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        root.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().hasFiles() ? e.getDragboard().getFiles() : List.of();
            e.setDropCompleted(!files.isEmpty());
            for (File f : files) sendFile(f, false);
        });
        loadHeader();
    }

    private int conversationId() {
        return conversationId;
    }

    @Override
    Node view() {
        return root;
    }

    @Override
    void onResume() {
        visible = true;
        ChatStore.get().observe(onChange);
        reload();
        ChatStore.load(s -> s.vault(vaultId), vault -> {
            if (vault == null) ChatSpace.relock(); // Deleted, from another device.
            else if (visible) ChatVaults.Session.enter(vault.code, session);
        });
        Platform.runLater(input::requestFocus);
    }

    @Override
    void onPause() {
        visible = false;
        stopRecording(false);
        stopPlayback();
        if (onceModal != null) onceModal.cancel(); // Leaving a view-once item ends it.
        ChatStore.get().unobserve(onChange);
        ChatVaults.Session.leave(session);
        stopLive.run();
        stopLive = () -> {};
        poller.stop();
    }

    @Override
    void onKey(KeyEvent e) {
        if (e.isShortcutDown() && e.getCode() == KeyCode.V && writable && pasteMedia()) e.consume();
    }

    /** Writer: live, marks seen, can send. Read-only: polls every two seconds, writes nothing. */
    private void onSession(boolean canWrite, String by) {
        writable = canWrite;
        ChatUi.showBanner(banner, !canWrite, by);
        compose.setVisible(canWrite);
        compose.setManaged(canWrite);
        stopLive.run();
        stopLive = () -> {};
        poller.stop();
        if (canWrite) {
            ChatSync.markSeen(conversationId);
            ChatStore.load(s -> ChatSync.watchConversation(conversationId), stop -> {
                if (stop == null) return;
                if (visible && writable) stopLive = stop;
                else stop.run();
            });
        } else {
            poller.play();
        }
    }

    private void reload() {
        ChatStore.load(s -> s.messages(conversationId), messages -> {
            if (messages == null) return;
            boolean atBottom = isAtBottom();
            list.getItems().setAll(messages);
            if (atBottom && !messages.isEmpty()) list.scrollTo(messages.size() - 1);
            if (visible && writable) ChatSync.markSeen(conversationId);
        });
        loadHeader();
    }

    private boolean isAtBottom() {
        javafx.scene.control.ScrollBar bar = (javafx.scene.control.ScrollBar) list.lookup(".scroll-bar:vertical");
        return bar == null || !bar.isVisible() || bar.getValue() >= bar.getMax() - 0.02 || list.getItems().isEmpty();
    }

    // ============================================================ header, theme

    /** Photo, title (nickname or @username), their vault's current ID, and the background. */
    private void loadHeader() {
        ChatStore.load(s -> s.conversation(conversationId), c -> {
            if (c == null) {
                if (NotesApp.current() == this) NotesApp.back();
                return;
            }
            isGroup = c.isGroup;
            peerName = c.peerName == null ? "" : c.peerName;
            if (c.isGroup) {
                String name = c.peerName == null || c.peerName.isEmpty() ? "Group" : c.peerName;
                title.setText(name);
                int count = ChatSync.json(c.members).length();
                subtitle.setText(count + (count == 1 ? " member" : " members"));
                avatar.set(name, "tile_purple", null);
            } else {
                boolean nick = c.nickname != null && !c.nickname.isEmpty();
                String t = nick ? c.nickname : "@" + c.peerName;
                title.setText(t);
                subtitle.setText((nick ? "@" + c.peerName + "  ·  " : "") + "Vault " + (c.peerShown != null ? c.peerShown : c.peerVault));
                avatar.set(t, "tile_blue", c.peerPhoto);
            }
            applyTheme(c.theme);
        });
    }

    private void applyTheme(String json) {
        if (Objects.equals(json, shownTheme)) return;
        shownTheme = json;
        backgroundImage.setVisible(false);
        backgroundDim.setVisible(false);
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        if (json == null) return;
        try {
            JSONObject theme = new JSONObject(json);
            if ("color".equals(theme.optString("kind"))) {
                root.setStyle("-fx-background-color: " + theme.optString("value") + ";");
            } else if ("image".equals(theme.optString("kind"))) {
                JSONObject media = theme.optJSONObject("media");
                ChatMedia.Meta meta = ChatMedia.Meta.parse(media == null ? null : media.toString());
                if (meta == null || meta.id == null) return;
                ChatStore.load(s -> decode(ChatSync.themeTag(meta.id), meta, 1920), image -> {
                    if (image == null || !Objects.equals(json, shownTheme)) return;
                    backgroundImage.setImage(image);
                    backgroundImage.setVisible(true);
                    backgroundDim.setVisible(true);
                });
            }
        } catch (Exception ignored) {
            // An unreadable theme shows the default background.
        }
    }

    // ================================================================= menu

    private void showMenu() {
        if (!writable) {
            Ui.toast("Open on another device. Changes can only be made there for now.");
            return;
        }
        if (isGroup) {
            Ui.menu(menu, List.of("Members", "Rename group", "Background", "Leave group"),
                    List.of(this::showMembers, this::askGroupName, this::chooseBackground, this::confirmLeave));
        } else {
            Ui.menu(menu, List.of("Nickname", "Background"), List.of(this::askNickname, this::chooseBackground));
        }
    }

    private void showMembers() {
        ChatStore.load(s -> {
            ChatStore.Conversation c = s.conversation(conversationId);
            return new Object[]{c, ChatSync.groupOwner(conversationId)};
        }, r -> {
            ChatStore.Conversation c = (ChatStore.Conversation) r[0];
            String owner = (String) r[1];
            if (c == null) return;
            String me = Account.uid();
            JSONObject members = ChatSync.json(c.members);
            List<String[]> people = new ArrayList<>();
            for (String uid : members.keySet()) {
                JSONObject info = members.optJSONObject(uid);
                people.add(new String[]{uid, info == null ? "?" : info.optString("name", "?")});
            }
            people.sort((x, y) -> x[1].compareToIgnoreCase(y[1]));
            boolean iOwn = me != null && me.equals(owner);
            List<String> rows = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (String[] who : people) {
                rows.add("@" + who[1] + (who[0].equals(owner) ? "  · owner" : "") + (who[0].equals(me) ? "  (you)" : ""));
                actions.add(() -> {
                    if (iOwn && !who[0].equals(me)) confirmRemove(who[0], who[1]);
                });
            }
            Ui.DialogBuilder d = Ui.dialog().title(rows.size() + " of " + ChatSync.MAX_GROUP + " members" + (iOwn ? "  ·  click to remove" : ""))
                    .items(rows, actions).negative("Close", null).width(400);
            if (rows.size() < ChatSync.MAX_GROUP) d.positive("Add people", this::askAddPeople);
            d.show();
        });
    }

    private void confirmRemove(String uid, String name) {
        Ui.dialog().title("Remove @" + name + "?")
                .message("They stop getting this group's messages and can't read it any more. What's already on their device stays there.")
                .positive("Remove", () -> ChatStore.load(s -> {
                    try {
                        ChatSync.removeFromGroup(conversationId, uid);
                        return "@" + name + " removed";
                    } catch (Exception e) {
                        return "Couldn't remove them";
                    }
                }, Ui::toast))
                .negative("Cancel", null).width(420).show();
    }

    private void askAddPeople() {
        Ui.prompt("Add people", "Usernames, separated by commas. Add /ID to reach a particular vault, like nancy/AB12.",
                "Usernames", null, "Add", raw -> ChatStore.load(s -> {
                    try {
                        ChatSync.addToGroup(conversationId, ChatSync.resolveAll(raw));
                        return "Added";
                    } catch (Exception e) {
                        return e.getMessage() == null ? "Couldn't add them" : e.getMessage();
                    }
                }, Ui::toast), null, null, null);
    }

    private void askGroupName() {
        Ui.prompt("Rename group", "Everyone in the group sees the new name.", "Group name", title.getText(), "Save",
                name -> ChatStore.load(s -> {
                    try {
                        ChatSync.renameGroup(conversationId, name.length() > 60 ? name.substring(0, 60) : name);
                        return null;
                    } catch (Exception e) {
                        return "Couldn't rename it";
                    }
                }, problem -> {
                    if (problem != null) Ui.toast(problem);
                }), null, null, null);
    }

    private void confirmLeave() {
        Ui.dialog().title("Leave this group?")
                .message("You won't get its messages any more, and it's removed from this vault.")
                .positive("Leave", () -> ChatStore.load(s -> {
                    try {
                        ChatSync.leaveGroup(conversationId);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }, ok -> {
                    if (Boolean.TRUE.equals(ok)) NotesApp.back();
                    else Ui.toast("Couldn't leave right now");
                }))
                .negative("Cancel", null).width(400).show();
    }

    private void askNickname() {
        ChatStore.load(s -> s.conversation(conversationId), c -> {
            if (c == null) return;
            Ui.prompt("Nickname for @" + c.peerName, "Only you see it, on all your devices.", "Nickname", c.nickname, "Save",
                    name -> saveNickname(name.length() > 40 ? name.substring(0, 40) : name), null,
                    "Remove", ignored -> saveNickname(null));
        });
    }

    private void saveNickname(String name) {
        ChatStore.write(s -> ChatSync.setNickname(conversationId, name));
    }

    private void chooseBackground() {
        List<String> labels = new ArrayList<>(List.of(ChatSettingsView.COLOR_NAMES));
        labels.add("A photo…");
        List<Runnable> actions = new ArrayList<>();
        for (String color : ChatSettingsView.COLORS) actions.add(() -> ChatStore.write(s -> ChatSync.setThemeColor(conversationId, color)));
        actions.add(() -> {
            if (ChatUi.needsDrive()) return;
            File file = pickFile(true);
            if (file == null) return;
            Ui.toast("Encrypting and uploading…");
            ChatStore.load(s -> {
                try {
                    ChatSync.setThemeImage(conversationId, file.toPath());
                    return null;
                } catch (Exception e) {
                    return e.getMessage() == null ? "Couldn't set the background" : e.getMessage();
                }
            }, problem -> {
                if (problem != null) Ui.toast(problem);
            });
        });
        Ui.dialog().title("Background").items(labels, actions).width(320).show();
    }

    // ============================================================== compose

    private void buildCompose() {
        input.getStyleClass().add("chat-input");
        input.setPromptText("Message");
        input.setWrapText(true);
        input.setPrefRowCount(1);
        Ui.autoGrow(input, 44, 0);
        input.setMaxHeight(160);
        HBox.setHgrow(input, Priority.ALWAYS);
        input.textProperty().addListener((o, a, b) -> updateSendButton());
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                sendTyped();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.V && pasteMedia()) {
                e.consume();
            }
        });

        Region dot = new Region();
        dot.setMinSize(10, 10);
        dot.setMaxSize(10, 10);
        dot.setStyle("-fx-background-color: " + Theme.ref("destructive") + "; -fx-background-radius: 5;");
        HBox.setHgrow(recordWave, Priority.ALWAYS);
        recordBar.getChildren().addAll(dot, recordTime, recordWave,
                Ui.iconButton(Icons.DELETE, 36, 20, "text_secondary", "Discard", () -> stopRecording(false)));
        recordBar.setAlignment(Pos.CENTER_LEFT);
        recordBar.setMinHeight(44);
        recordBar.setPadding(new Insets(0, 6, 0, 12));
        recordBar.setStyle("-fx-background-color: " + Theme.ref("app_surface") + "; -fx-background-radius: 18;");
        HBox.setHgrow(recordBar, Priority.ALWAYS);
        recordBar.setVisible(false);
        recordBar.setManaged(false);

        compose.getChildren().addAll(attach, input, recordBar, mic, send);
        compose.setAlignment(Pos.BOTTOM_CENTER);
        compose.setPadding(new Insets(6, 8, 10, 8));
        updateSendButton();
    }

    /** An empty box offers the microphone; typing turns it into Send. */
    private void updateSendButton() {
        boolean typing = !input.getText().trim().isEmpty();
        boolean recordingNow = recorder != null;
        send.setVisible(typing && !recordingNow);
        send.setManaged(typing && !recordingNow);
        mic.setVisible(!typing || recordingNow);
        mic.setManaged(!typing || recordingNow);
    }

    private void sendTyped() {
        String text = input.getText().trim();
        if (text.isEmpty() || !writable) return;
        input.clear();
        ChatStore.write(s -> ChatSync.send(conversationId, text));
        Platform.runLater(() -> list.scrollTo(list.getItems().size()));
    }

    private void askAttach() {
        if (!writable || ChatUi.needsDrive()) return;
        Ui.menu(attach, List.of("Photo or video…", "Photo or video, view once…"),
                List.of(() -> pickAndSend(false), () -> pickAndSend(true)));
    }

    private void pickAndSend(boolean once) {
        File file = pickFile(false);
        if (file != null) sendFile(file, once);
    }

    private File pickFile(boolean picturesOnly) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(picturesOnly ? "Choose a picture" : "Choose a photo or video");
        chooser.getExtensionFilters().add(picturesOnly
                ? new FileChooser.ExtensionFilter("Pictures", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp")
                : new FileChooser.ExtensionFilter("Photos and videos", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp",
                "*.mp4", "*.m4v", "*.mov", "*.webm", "*.3gp"));
        ChatSpace.expectExternal();
        return chooser.showOpenDialog(NotesApp.stage());
    }

    /** The caption is whatever is typed; it goes with the file. */
    private void sendFile(File file, boolean once) {
        if (!writable || ChatUi.needsDrive()) return;
        String mime = ChatMedia.mimeOf(file.getName());
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
            Ui.toast("Photos and videos can be sent");
            return;
        }
        String caption = input.getText().trim();
        input.clear();
        ChatStore.load(s -> {
            try {
                ChatSync.sendMedia(conversationId, file.toPath(), caption, once);
                return null;
            } catch (Exception e) {
                return e.getMessage() == null ? "Couldn't send that file" : e.getMessage();
            }
        }, problem -> {
            if (problem != null) Ui.toast(problem);
            else list.scrollTo(list.getItems().size());
        });
    }

    /** A picture (or files) on the clipboard is sent; text pastes as usual. */
    private boolean pasteMedia() {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        if (cb.hasFiles()) {
            List<File> media = new ArrayList<>();
            for (File f : cb.getFiles()) {
                String mime = ChatMedia.mimeOf(f.getName());
                if (mime.startsWith("image/") || mime.startsWith("video/")) media.add(f);
            }
            if (media.isEmpty()) return false;
            media.forEach(f -> sendFile(f, false));
            return true;
        }
        if (!cb.hasImage() || cb.hasString()) return false;
        if (ChatUi.needsDrive()) return true;
        Image image = cb.getImage();
        String caption = input.getText().trim();
        input.clear();
        ChatStore.load(s -> {
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", png);
                ChatSync.sendPicture(conversationId, png.toByteArray(), caption, false);
                return null;
            } catch (Exception e) {
                return "Couldn't send the picture";
            }
        }, problem -> {
            if (problem != null) Ui.toast(problem);
        });
        return true;
    }

    // =========================================================== voice notes

    private AudioRecorder recorder;
    private Path recording;
    private long recordStart;
    private final List<Float> levels = new ArrayList<>();
    private final Timeline recordTicker = new Timeline(new KeyFrame(Duration.millis(80), e -> sampleLevel()));

    private void toggleRecording() {
        if (recorder != null) stopRecording(true);
        else startRecording();
    }

    private void startRecording() {
        if (!writable || ChatUi.needsDrive()) return;
        stopPlayback();
        recording = ChatMedia.plainDir().resolve("voice_" + System.currentTimeMillis() + ".wav");
        AudioRecorder r = new AudioRecorder();
        try {
            r.start(recording);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(recording);
            } catch (Exception ignored) {
                // Swept with the folder.
            }
            Ui.toast("The microphone isn't available");
            return;
        }
        recorder = r;
        recordStart = System.currentTimeMillis();
        levels.clear();
        input.setVisible(false);
        input.setManaged(false);
        attach.setVisible(false);
        attach.setManaged(false);
        recordBar.setVisible(true);
        recordBar.setManaged(true);
        ((Ui.IconView) mic.getGraphic()).setIcon(Icons.SEND);
        Ui.tooltip(mic, "Send voice message");
        updateSendButton();
        recordTicker.setCycleCount(Timeline.INDEFINITE);
        recordTicker.play();
    }

    /** Every 80 ms: the live waveform and the timer. */
    private void sampleLevel() {
        if (recorder == null) return;
        levels.add((float) Math.min(1, Math.sqrt(recorder.takePeak() / 32767.0)));
        recordWave.setLive(levels);
        long elapsed = System.currentTimeMillis() - recordStart;
        recordTime.setText(ChatUi.clock(elapsed));
        if (elapsed > 15 * 60_000) stopRecording(true); // A quarter of an hour is plenty for one message.
    }

    /** {@code send}: send it; otherwise throw it away. */
    private void stopRecording(boolean sendIt) {
        if (recorder == null) return;
        recordTicker.stop();
        long duration = System.currentTimeMillis() - recordStart;
        recorder.stop();
        recorder = null;
        recordBar.setVisible(false);
        recordBar.setManaged(false);
        input.setVisible(true);
        input.setManaged(true);
        attach.setVisible(true);
        attach.setManaged(true);
        ((Ui.IconView) mic.getGraphic()).setIcon(Icons.MIC);
        Ui.tooltip(mic, "Record a voice message");
        updateSendButton();
        Path file = recording;
        recording = null;
        if (!sendIt || duration < 700) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // Swept with the folder.
            }
            if (sendIt) Ui.toast("Hold on a little longer to record");
            return;
        }
        float[] wave = downsample(levels, 40);
        ChatStore.load(s -> {
            try {
                ChatSync.sendVoice(conversationId, file, duration, wave);
                return null;
            } catch (Exception e) {
                return "Couldn't send the voice message";
            }
        }, problem -> {
            if (problem != null) Ui.toast(problem);
        });
    }

    private static float[] downsample(List<Float> values, int count) {
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

    // ---- playback: one voice note at a time, decrypted to a temporary file while it plays

    private MediaPlayer player;
    private String playingKey;
    private float[] playingWave;
    private Path playingFile;
    private WaveformView playingView;
    private final Timeline playTicker = new Timeline(new KeyFrame(Duration.millis(60), e -> showProgress()));

    private void showProgress() {
        if (player == null || playingView == null) return;
        Duration total = player.getTotalDuration();
        if (total == null || total.isUnknown() || total.toMillis() <= 0) return;
        playingView.setWave(playingWave, player.getCurrentTime().toMillis() / total.toMillis());
    }

    private void togglePlay(ChatStore.Message m, ChatMedia.Meta meta) {
        if (m.msgKey.equals(playingKey) && player != null) {
            if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
            else player.play();
            list.refresh();
            return;
        }
        stopPlayback();
        playingKey = m.msgKey;
        playingWave = meta.waveform();
        ChatStore.load(s -> {
            try {
                return ChatMedia.decryptToTemp(m.msgKey, meta);
            } catch (Exception e) {
                return null;
            }
        }, file -> {
            if (file == null) {
                if (m.msgKey.equals(playingKey)) stopPlayback();
                Ui.toast("Couldn't fetch the voice message");
                return;
            }
            if (!m.msgKey.equals(playingKey) || !visible) {
                deleteQuietly(file);
                return;
            }
            try {
                playingFile = file;
                player = new MediaPlayer(new Media(file.toUri().toString()));
                player.setOnEndOfMedia(this::stopPlayback);
                player.setOnError(() -> {
                    stopPlayback();
                    Ui.toast("Couldn't play it");
                });
                player.statusProperty().addListener((o, a, b) -> list.refresh());
                player.play();
                playTicker.setCycleCount(Timeline.INDEFINITE);
                playTicker.play();
            } catch (Exception e) {
                stopPlayback();
                Ui.toast("Couldn't play it");
            }
        });
    }

    private void stopPlayback() {
        playTicker.stop();
        if (player != null) {
            player.dispose();
            player = null;
        }
        if (playingFile != null) deleteQuietly(playingFile);
        playingFile = null;
        boolean was = playingKey != null;
        playingKey = null;
        playingView = null;
        if (was) list.refresh();
    }

    private static void deleteQuietly(Path file) {
        ChatStore.IO.execute(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Files.deleteIfExists(file);
                    return;
                } catch (Exception e) {
                    try {
                        Thread.sleep(300); // The player lets go of it a moment after disposing.
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    // ================================================================ media

    /** Decrypts and decodes a picture no wider than {@code width}. Background thread; null on failure. */
    private Image decode(String tag, ChatMedia.Meta meta, double width) {
        synchronized (pictures) {
            Image cached = pictures.get(tag);
            if (cached != null) return cached;
        }
        try {
            Image image = new Image(new ByteArrayInputStream(ChatMedia.decryptBytes(tag, meta)), width * 2, 0, true, true);
            if (image.isError()) return null;
            synchronized (pictures) {
                pictures.put(tag, image);
            }
            return image;
        } catch (Exception e) {
            System.getLogger("Chat").log(System.Logger.Level.WARNING, "Couldn't show a picture", e);
            return null;
        }
    }

    private Ui.Modal onceModal;

    /** Full window: photos in memory, videos from a temporary file deleted on close. */
    private void openMedia(ChatStore.Message m, ChatMedia.Meta meta, boolean once) {
        Label wait = Ui.label(once ? "Opening…" : "Loading…", 14);
        wait.setStyle("-fx-text-fill: white;");
        Ui.Modal loading = Ui.present(new StackPane(wait), Pos.CENTER, true, null);
        if (once) Capture.protect(true); // The space's "allow screenshots" never applies to view-once media.
        ChatStore.load(s -> {
            try {
                if (meta.isVideo()) return ChatMedia.decryptToTemp(m.msgKey, meta);
                return new Image(new ByteArrayInputStream(ChatMedia.decryptBytes(m.msgKey, meta)));
            } catch (Exception e) {
                return e;
            }
        }, result -> {
            loading.dismiss();
            if (result instanceof Exception e || result == null) {
                if (once) Capture.protect(!ChatSpace.screenshotsAllowed());
                Ui.toast("Couldn't open this." + (result instanceof Exception e2 && e2.getMessage() != null ? " " + e2.getMessage() : ""));
                return;
            }
            Path file = result instanceof Path p ? p : null;
            Image image = result instanceof Image i ? i : null;
            Ui.Modal modal = MediaViewer.open(file, image, file != null, () -> {
                if (file != null) deleteQuietly(file);
                if (once) {
                    onceModal = null;
                    Capture.protect(!ChatSpace.screenshotsAllowed());
                    ChatSync.markOpened(m.msgKey);
                }
            });
            if (once) onceModal = modal;
        });
    }

    // ================================================================ bubbles

    private final class Bubble extends ListCell<ChatStore.Message> {
        private final HBox line = new HBox();
        private final VBox bubble = new VBox(2);
        private final Label sender = new Label(), body = new Label(), meta = new Label();
        private final Ui.IconView status = Ui.icon(Icons.CHECK, 15, "white");
        private final HBox metaRow = new HBox(4, meta, status);
        private final StackPane mediaFrame = new StackPane();
        private final ImageView picture = new ImageView();
        private final Label mediaInfo = new Label();
        private final StackPane playBadge = new StackPane(Ui.icon(Icons.PLAY_BIG, 48, "white"));
        private final HBox voiceRow = new HBox(10);
        private final Button playVoice = new Button();
        private final WaveformView voiceWave = new WaveformView(32);
        private final Label voiceLength = new Label();
        private final Rectangle clip = new Rectangle();
        private String shownKey;

        Bubble() {
            setStyle("-fx-background-color: transparent; -fx-padding: 2 12 2 12;");
            sender.setPadding(new Insets(2, 8, 0, 10));
            sender.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + Theme.ref("tile_purple") + ";");
            body.setWrapText(true);
            body.setMaxWidth(420);
            body.setPadding(new Insets(4, 8, 0, 10));
            metaRow.setAlignment(Pos.CENTER_RIGHT);
            metaRow.setPadding(new Insets(2, 8, 0, 10));

            picture.setPreserveRatio(false);
            clip.setArcWidth(28);
            clip.setArcHeight(28);
            mediaFrame.setClip(clip);
            mediaInfo.setStyle("-fx-background-color: #00000099; -fx-background-radius: 10; -fx-text-fill: white;"
                    + " -fx-font-size: 11px; -fx-padding: 1 6 1 6;");
            StackPane.setAlignment(mediaInfo, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(mediaInfo, new Insets(8));
            playBadge.setMouseTransparent(true);
            mediaFrame.getChildren().addAll(picture, playBadge, mediaInfo);
            mediaFrame.setStyle("-fx-background-color: #33000000; -fx-cursor: hand;");

            playVoice.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            playVoice.getStyleClass().setAll("chat-round");
            playVoice.setMinSize(40, 40);
            playVoice.setMaxSize(40, 40);
            playVoice.setFocusTraversable(false);
            HBox.setHgrow(voiceWave, Priority.ALWAYS);
            voiceRow.getChildren().addAll(playVoice, voiceWave, voiceLength);
            voiceRow.setAlignment(Pos.CENTER_LEFT);
            voiceRow.setPrefWidth(250);
            voiceRow.setPadding(new Insets(4, 8, 0, 4));

            bubble.setPadding(new Insets(4, 4, 6, 4));
            bubble.setMaxWidth(Region.USE_PREF_SIZE);
            line.getChildren().add(bubble);
        }

        @Override
        protected void updateItem(ChatStore.Message m, boolean empty) {
            super.updateItem(m, empty);
            setText(null);
            if (empty || m == null) {
                setGraphic(null);
                shownKey = null;
                return;
            }
            line.setAlignment(m.mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            bubble.setAlignment(m.mine ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
            String bubbleColor = m.status == ChatStore.FAILED ? Theme.ref("destructive") : Theme.ref(m.mine ? "tile_blue" : "app_surface");
            bubble.setStyle("-fx-background-color: " + bubbleColor + "; -fx-background-radius: 18;");
            String textColor = m.mine ? "white" : Theme.ref("text_primary");
            String metaColor = m.mine ? "#FFFFFFCC" : Theme.ref("text_secondary");
            bubble.getChildren().clear();

            if (isGroup && !m.mine && m.senderName != null) {
                sender.setText(m.senderName);
                bubble.getChildren().add(sender);
            }

            ChatMedia.Meta media = ChatMedia.Meta.parse(m.media);
            if (media != null && media.isAudio()) bindVoice(m, media);
            else if (media != null && !media.once) bindMedia(m, media);

            boolean once = media != null && media.once;
            boolean hasText = (m.text != null && !m.text.isEmpty()) || once;
            body.setGraphic(null);
            body.setStyle("-fx-font-size: 16px; -fx-text-fill: " + textColor + ";");
            if (once) {
                // Never a preview: just what it is, and whether it was opened.
                boolean opened = ChatSync.hasFlag(m.media, "opened");
                String kind = media.isVideo() ? "Video" : "Photo";
                String text = m.mine ? "View-once " + kind.toLowerCase(Locale.ROOT) + (opened ? " · opened" : " · not opened yet")
                        : opened ? kind + " · opened" : kind + " · click to view once";
                body.setText(m.text == null || m.text.isEmpty() ? text : text + "\n" + m.text);
                body.setGraphic(Ui.icon(opened ? Icons.CHECK_CIRCLE_EMPTY : Icons.TIMER, 18, m.mine ? "white" : "brand_yellow"));
                body.setGraphicTextGap(6);
            } else {
                body.setText(m.text);
            }
            if (hasText) bubble.getChildren().add(body);

            meta.setStyle("-fx-font-size: 11px; -fx-text-fill: " + metaColor + ";");
            meta.setText(m.status != ChatStore.FAILED ? ChatUi.time(m.at)
                    : media != null && Account.driveEmail() == null ? "Not sent · connect Google Drive, then click" : "Not sent · click to retry");
            boolean showStatus = m.mine && m.status != ChatStore.FAILED;
            status.setVisible(showStatus);
            status.setManaged(showStatus);
            if (m.mine) {
                status.setIcon(m.status == ChatStore.WAITING ? Icons.TIMER : m.status == ChatStore.SENT ? Icons.CHECK : Icons.DONE_ALL);
                status.color(m.status == ChatStore.SEEN ? "brand_yellow" : "white");
                status.setOpacity(m.status == ChatStore.SEEN ? 1 : 0.8);
                Ui.tooltip(status, m.status == ChatStore.WAITING ? "Waiting" : m.status == ChatStore.SENT ? "Sent"
                        : m.status == ChatStore.RECEIVED ? "Received" : "Seen");
            }
            bubble.getChildren().add(metaRow);

            bubble.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.SECONDARY) {
                    if (!hasText || m.text == null || m.text.isEmpty()) return;
                    ClipboardContent c = new ClipboardContent();
                    c.putString(m.text);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
                    Ui.toast("Copied");
                    return;
                }
                if (m.status == ChatStore.FAILED && writable) ChatSync.retry(m);
                // Read-only devices can't open them: opening uses the one view up.
                else if (once && !m.mine && writable && !ChatSync.hasFlag(m.media, "opened")) openMedia(m, media, true);
            });
            bubble.setStyle(bubble.getStyle() + (m.status == ChatStore.FAILED || (once && !m.mine) ? " -fx-cursor: hand;" : ""));
            shownKey = m.msgKey;
            setGraphic(line);
        }

        private void bindMedia(ChatStore.Message m, ChatMedia.Meta media) {
            double width = 240;
            double height = media.w > 0 && media.h > 0 ? width * media.h / media.w : 180;
            height = Math.max(120, Math.min(height, 320));
            mediaFrame.setMinSize(width, height);
            mediaFrame.setPrefSize(width, height);
            mediaFrame.setMaxSize(width, height);
            clip.setWidth(width);
            clip.setHeight(height);
            picture.setFitWidth(width);
            picture.setFitHeight(height);
            picture.setImage(null);
            playBadge.setVisible(media.isVideo());
            mediaInfo.setVisible(media.isVideo() && media.dur > 0);
            mediaInfo.setText(ChatUi.clock(media.dur));
            if (!media.isVideo()) {
                Image cached;
                synchronized (pictures) {
                    cached = pictures.get(m.msgKey);
                }
                if (cached != null) {
                    setCover(cached, width, height);
                } else if (decoding.add(m.msgKey)) {
                    double w = width, h = height;
                    ChatStore.load(s -> decode(m.msgKey, media, w), image -> {
                        decoding.remove(m.msgKey);
                        if (image != null && m.msgKey.equals(shownKey)) setCover(image, w, h);
                    });
                }
            }
            mediaFrame.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    e.consume();
                    openMedia(m, media, false);
                }
            });
            bubble.getChildren().add(mediaFrame);
        }

        /** Centre-crops the picture to the frame, like the phone's centerCrop. */
        private void setCover(Image image, double w, double h) {
            double scale = Math.max(w / image.getWidth(), h / image.getHeight());
            double vw = w / scale, vh = h / scale;
            picture.setViewport(new javafx.geometry.Rectangle2D((image.getWidth() - vw) / 2, (image.getHeight() - vh) / 2, vw, vh));
            picture.setImage(image);
        }

        private void bindVoice(ChatStore.Message m, ChatMedia.Meta media) {
            voiceWave.setColors(m.mine ? Color.WHITE : Theme.color("tile_blue"), m.mine ? Color.WHITE : Theme.color("text_secondary"));
            boolean current = m.msgKey.equals(playingKey) && player != null;
            double progress = 0;
            if (current) {
                playingView = voiceWave;
                Duration total = player.getTotalDuration();
                if (total != null && !total.isUnknown() && total.toMillis() > 0) progress = player.getCurrentTime().toMillis() / total.toMillis();
            } else if (playingView == voiceWave) {
                playingView = null;
            }
            voiceWave.setWave(media.waveform(), progress);
            voiceLength.setText(ChatUi.clock(media.dur));
            voiceLength.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (m.mine ? "#FFFFFFCC" : Theme.ref("text_secondary")) + ";");
            boolean playing = current && player.getStatus() == MediaPlayer.Status.PLAYING;
            playVoice.setGraphic(Ui.icon(playing ? Icons.PAUSE : Icons.PLAY, 22, m.mine ? "white" : "tile_blue"));
            playVoice.setStyle("-fx-background-color: #33FFFFFF;");
            playVoice.setOnAction(e -> togglePlay(m, media));
            bubble.getChildren().add(voiceRow);
        }
    }
}
