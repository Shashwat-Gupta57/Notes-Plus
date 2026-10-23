package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.flexstudios.notes.plus.VaultSettingsView.card;
import static org.flexstudios.notes.plus.VaultSettingsView.chevron;
import static org.flexstudios.notes.plus.VaultSettingsView.divider;
import static org.flexstudios.notes.plus.VaultSettingsView.footer;
import static org.flexstudios.notes.plus.VaultSettingsView.header;
import static org.flexstudios.notes.plus.VaultSettingsView.row;

/**
 * Settings of the chat vault that is open: its ID, name, photo, background and lock. The main
 * vault also manages the other chat vaults, auto-lock, screenshots and the lock screen's look.
 * The phone's ChatSettingsActivity; never mentions anything outside the chat space.
 */
final class ChatSettingsView extends Screen {

    /** Chat list background presets: name and colour (null = default). */
    static final String[] COLOR_NAMES = {"Default", "Ocean", "Forest", "Sunset", "Plum", "Rose", "Slate", "Night"};
    static final String[] COLORS = {null, "#1565C0", "#2E7D32", "#E65100", "#6A1B9A", "#AD1457", "#37474F", "#121212"};
    private static final String KEY_BACKGROUND = "chat_background_key";

    private final int vaultId;
    private final boolean changeLockFirst;
    private final BorderPane root = new BorderPane();
    private final VBox column = new VBox();
    private ChatStore.Vault vault;
    private boolean writable = true, entered;
    private String holder, chattingAs = "…";
    private final ChatVaults.Session.Listener session = (canWrite, by) -> {
        writable = canWrite;
        holder = by;
    };
    private final Runnable onChange = () -> load(false);

    ChatSettingsView(int vaultId, boolean changeLock) {
        this.vaultId = vaultId;
        this.changeLockFirst = changeLock;
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        HBox toolbar = new HBox(Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Back (Esc)", NotesApp::back),
                Ui.label("Settings", 22));
        toolbar.getStyleClass().add("toolbar");
        toolbar.setSpacing(8);
        root.setTop(toolbar);
        column.setMaxWidth(560);
        column.setPadding(new Insets(0, 0, 32, 0));
        StackPane page = new StackPane(column);
        StackPane.setAlignment(column, Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setCenter(scroll);
        if (Account.uid() == null) chattingAs = "Not signed in";
        else ChatStore.load(s -> {
            try {
                String name = Account.loadProfile().username;
                return name == null ? "No username yet" : "@" + name;
            } catch (Exception e) {
                return "Offline";
            }
        }, name -> {
            chattingAs = name;
            build();
        });
        load(changeLock);
    }

    @Override
    Node view() {
        return root;
    }

    @Override
    void onResume() {
        ChatStore.get().observe(onChange);
        if (vault != null) ChatVaults.Session.enter(vault.code, session);
    }

    @Override
    void onPause() {
        ChatStore.get().unobserve(onChange);
        ChatVaults.Session.leave(session);
    }

    /** Another device has this vault open: here it can only be looked at. */
    private boolean canEdit() {
        if (writable) return true;
        Ui.toast("Open on " + holder + ". Changes can only be made there for now.");
        return false;
    }

    private void load(boolean thenChangeLock) {
        ChatStore.load(s -> new Object[]{s.vault(vaultId), s.vaults()}, r -> {
            ChatStore.Vault v = (ChatStore.Vault) r[0];
            if (v == null) {
                NotesApp.back();
                return;
            }
            vault = v;
            if (!entered) {
                entered = true;
                ChatVaults.Session.enter(v.code, session);
            }
            @SuppressWarnings("unchecked") List<ChatStore.Vault> all = (List<ChatStore.Vault>) r[1];
            vaults = all;
            build();
            if (thenChangeLock) editLock(v);
        });
    }

    private List<ChatStore.Vault> vaults = List.of();

    private void build() {
        column.getChildren().clear();
        if (vault == null) return;
        ChatUi.Avatar photo = new ChatUi.Avatar(30);
        photo.set(vault.name, "tile_purple", vault.photo);
        Node photoEnd = vault.photo == null ? chevron() : photo;
        column.getChildren().addAll(header("This vault"), card(
                row(Icons.LOCK, "tile_blue", "Vault ID", vault.code + "  ·  click to copy or change", chevron(), this::showCode),
                row(Icons.NOTE_TEXT, "tile_orange", "Name", vault.name, chevron(), () -> {
                    if (canEdit()) rename(vault);
                }),
                row(Icons.PERSON, "tile_purple", "Profile photo", vault.photo == null
                        ? "Your account photo shows. Choose one just for this vault" : "Shown to people this vault talks to",
                        photoEnd, () -> {
                            if (canEdit()) choosePhoto();
                        }),
                row(Icons.PHOTO, "tile_teal", "Chats background", vault.background == null ? "Default" : "Custom",
                        chevron(), this::chooseBackground),
                row(Icons.LOCK, "tile_green", "Change lock", LockInput.label(vault.lockType), chevron(), () -> {
                    if (canEdit()) editLock(vault);
                })),
                footer("People reach this vault with your username plus its ID. Without an ID, messages go to your main vault."));

        if (vault.isMain) {
            VBox list = new VBox();
            for (ChatStore.Vault v : vaults) {
                Node end = v.isMain ? chevron() : Ui.iconButton(Icons.DELETE, 40, 20, "text_secondary", "Delete vault", () -> {
                    if (canEdit()) confirmDelete(v);
                });
                if (!list.getChildren().isEmpty()) list.getChildren().add(divider());
                list.getChildren().add(row(Icons.LOCK, v.isMain ? "tile_blue" : "tile_gray", v.name + (v.isMain ? "  (main)" : ""),
                        "ID " + v.code + "  ·  " + LockInput.label(v.lockType), end, () -> {
                            if (canEdit()) showVaultOptions(v);
                        }));
            }
            column.getChildren().addAll(header("Vaults"), card(list,
                    row(Icons.ADD, "tile_green", "New vault", "Its own lock, its own conversations", null, () -> {
                        if (canEdit()) addVault();
                    })),
                    footer("Each vault opens with its own lock and shows only its own conversations. "
                            + "You can talk to the same person from several vaults."));
            column.getChildren().addAll(header("Security"), card(
                    row(Icons.TIMER, "tile_orange", "Auto-lock", "Lock after you leave the window", autoLock(), null),
                    row(Icons.PHOTO, "tile_purple", "Allow screenshots",
                            "Off: these screens can't be captured, recorded or shared",
                            VaultSettingsView.toggle(ChatSpace.screenshotsAllowed(), ChatSpace::setScreenshotsAllowed), null),
                    // One lock screen look for the whole app, on purpose: two would tell anyone there are two.
                    VaultSettingsView.wallpaperRow("Lock screen background", "What the lock screen shows behind the keypad",
                            (type, value) -> {
                                Prefs.put(Vault.KEY_LOCK_BG_TYPE, type);
                                Prefs.put(Vault.KEY_LOCK_BG_VALUE, value);
                                Ui.toast("Lock screen updated");
                            }, "bg_lock"),
                    VaultSettingsView.lockOptions()));
        }
        column.getChildren().addAll(header("Account"), card(
                row(Icons.PERSON, "tile_blue", "Chatting as", chattingAs, chevron(), () -> NotesApp.open(new AccountView()))),
                footer("Messages are kept on this computer. Online, each conversation keeps only its latest 100 messages."));
    }

    private Node autoLock() {
        long delay = ChatSpace.autoLockDelay();
        String[] labels = {"Now", "1 min", "5 min", "15 min"};
        long[] values = {0, 60_000, 5 * 60_000, 15 * 60_000};
        HBox group = new HBox();
        group.getStyleClass().add("segmented");
        group.setMaxHeight(Region.USE_PREF_SIZE);
        group.setMinWidth(Region.USE_PREF_SIZE);
        Button[] buttons = new Button[values.length];
        boolean known = false;
        for (long v : values) known |= v == delay;
        for (int i = 0; i < values.length; i++) {
            int index = i;
            Button b = new Button(labels[i]);
            b.getStyleClass().setAll("segment");
            if (i == 0) b.getStyleClass().add("first");
            if (i == values.length - 1) b.getStyleClass().add("last");
            if (values[i] == delay || (i == 0 && !known)) b.getStyleClass().add("on");
            b.setOnAction(e -> {
                ChatSpace.setAutoLockDelay(values[index]);
                for (Button o : buttons) o.getStyleClass().remove("on");
                b.getStyleClass().add("on");
            });
            buttons[i] = b;
            group.getChildren().add(b);
        }
        return group;
    }

    // ============================================================ this vault

    private void showCode() {
        Ui.dialog().title("Vault ID " + vault.code)
                .message("Share it with your username so people can reach this vault."
                        + (vault.isMain ? " Your main vault is also reached without any ID." : ""))
                .positive("Copy", () -> {
                    ClipboardContent c = new ClipboardContent();
                    c.putString(vault.code);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
                    Ui.toast("Copied");
                })
                .neutral("Change", () -> {
                    if (canEdit()) changeCode();
                })
                .negative("Close", null).width(420).show();
    }

    private void changeCode() {
        Ui.prompt("New vault ID", "4 letters or numbers. People who have the old ID keep their old conversations; "
                + "new messages need the new ID.", "ID", vault.code, "Save", raw -> {
            String code = ChatSync.normaliseCode(raw);
            if (code == null || code.isEmpty()) {
                Ui.toast("Use exactly 4 letters or numbers");
                return;
            }
            if (code.equals(vault.code)) return;
            ChatStore.load(s -> {
                // Retired IDs count too: an ID once used is never handed out again.
                if (s.vaultByCodeAny(code) != null) return false;
                ChatVaults.changeCode(vault, code);
                return true;
            }, ok -> {
                if (!Boolean.TRUE.equals(ok)) Ui.toast("That ID is taken or was used before");
                else load(false);
            });
        }, null, null, null);
    }

    private void rename(ChatStore.Vault target) {
        Ui.prompt("Name", "Vault name", target.name, "Save", name -> ChatStore.write(s -> {
            target.name = name;
            ChatVaults.save(target);
        }));
    }

    // =============================================================== locks

    private interface LockConsumer {
        void accept(String name, String type, String value);
    }

    /** Chooses a lock (PIN, password or pattern), with a name field when {@code withName} isn't null. */
    private void askLock(String title, String withName, LockConsumer onLock) {
        Ui.Field name = new Ui.Field("Vault name", withName);
        Ui.Tabs tabs = new Ui.Tabs("m3_primary", "PIN", "Password", "Pattern");
        LockInput[] inputs = new LockInput[3];
        for (int i = 0; i < 3; i++) inputs[i] = new LockInput(LockInput.TYPES[i], null, null);
        StackPane slot = new StackPane(inputs[0]);
        slot.setMinHeight(300);
        tabs.selected.addListener((o, a, b) -> slot.getChildren().setAll(inputs[b.intValue()]));
        VBox box = new VBox(12, tabs, slot);
        if (withName != null) box.getChildren().add(0, name);
        Ui.dialog().title(title).content(box).width(440).positive("Save", () -> {
            LockInput input = inputs[tabs.selected.get()];
            String value = input.value();
            String problem = LockInput.problem(input.type, value);
            if (problem != null) {
                Ui.toast(problem);
                return;
            }
            onLock.accept(name.text(), input.type, value);
        }).negative("Cancel", null).show();
    }

    private void editLock(ChatStore.Vault target) {
        askLock("New lock for " + target.name, null, (ignored, type, value) -> ChatStore.load(s -> {
            // Unlock opens the first vault whose lock matches: a shared one would hide one.
            if (ChatVaults.lockTaken(type, value, target.id)) return "Another vault already uses that lock";
            target.lockType = type;
            target.lockValue = ChatVaults.hashLock(type, value);
            ChatVaults.save(target);
            if (target.isMain) Prefs.put(ChatSync.PREF_INHERITED_LOCK, null);
            return null;
        }, problem -> Ui.toast(problem != null ? problem : "Lock changed")));
    }

    // ============================================================ main vault

    private void showVaultOptions(ChatStore.Vault v) {
        Ui.dialog().title(v.name).items(List.of("Rename", "Change lock"), List.of(() -> rename(v), () -> editLock(v)))
                .width(320).show();
    }

    private void addVault() {
        askLock("New vault", "", (name, type, value) -> ChatStore.load(s -> {
            if (ChatVaults.lockTaken(type, value, -1)) return null;
            return ChatVaults.create(name.isEmpty() ? "Vault" : name, type, value);
        }, v -> {
            if (v == null) {
                Ui.toast("Another vault already uses that lock");
                return;
            }
            Ui.dialog().title(v.name + " is ready")
                    .message("Its ID is " + v.code + ". Open it with its own lock from the lock screen.")
                    .positive("OK", null).width(400).show();
        }));
    }

    private void confirmDelete(ChatStore.Vault v) {
        Ui.dialog().title("Delete " + v.name + "?")
                .message("Its conversations are removed from this computer. Messages sent to ID " + v.code
                        + " won't arrive here any more.")
                .positive("Delete", () -> ChatStore.write(s -> ChatVaults.delete(v)))
                .negative("Cancel", null).width(420).show();
    }

    // ======================================================== photo, background

    /** This vault's own photo, shown to the people it talks to instead of the account's. */
    private void choosePhoto() {
        Ui.dialog().title("Profile photo for " + vault.name)
                .items(List.of("Choose a photo", "Use my account photo"), List.of(() -> {
                    if (ChatUi.needsDrive()) return;
                    File file = pick("Choose a photo");
                    if (file == null) return;
                    Ui.toast("Encrypting and uploading…");
                    ChatStore.load(s -> {
                        try {
                            Drive drive = Account.drive();
                            if (drive == null) return "Connect Google Drive first";
                            ChatVaults.setPhoto(vault, ChatMedia.uploadPhoto(drive, file.toPath()));
                            return null;
                        } catch (Exception e) {
                            return e.getMessage() == null ? "Couldn't set the photo" : e.getMessage();
                        }
                    }, problem -> {
                        if (problem != null) Ui.toast(problem);
                    });
                }, () -> ChatStore.write(s -> ChatVaults.setPhoto(vault, null)))).width(360).show();
    }

    /** This vault's chat list, on this computer only. */
    private void chooseBackground() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>(List.of(COLOR_NAMES));
        labels.add("A photo…");
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();
        for (String color : COLORS) {
            actions.add(() -> setVaultBackground(color == null ? null : new JSONObject().put("kind", "color").put("value", color).toString()));
        }
        actions.add(() -> {
            File file = pick("Choose a picture");
            if (file == null) return;
            ChatStore.load(s -> {
                try {
                    Path target = backgroundFile(vaultId);
                    writeEncrypted(file.toPath(), target);
                    return new JSONObject().put("kind", "image").put("file", target.toString()).toString();
                } catch (Exception e) {
                    return null;
                }
            }, json -> {
                if (json == null) Ui.toast("Couldn't use that picture");
                else setVaultBackground(json);
            });
        });
        Ui.dialog().title("Chats background").items(labels, actions).width(320).show();
    }

    private void setVaultBackground(String json) {
        ChatStore.write(s -> {
            try {
                if (vault.background != null && json == null) Files.deleteIfExists(backgroundFile(vault.id));
            } catch (Exception ignored) {
                // Overwritten next time.
            }
            vault.background = json;
            s.updateVault(vault); // This computer only: not a synced field.
        });
    }

    private static File pick(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp"));
        ChatSpace.expectExternal();
        return chooser.showOpenDialog(NotesApp.stage());
    }

    static Path backgroundFile(int vaultId) throws java.io.IOException {
        Path dir = Storage.root().resolve("chat").resolve("backgrounds");
        Files.createDirectories(dir);
        return dir.resolve("vault_" + vaultId);
    }

    /** Chat list backgrounds stay encrypted at rest, with a key of this computer's. */
    private static byte[] backgroundKey() {
        String k = Prefs.secret(KEY_BACKGROUND);
        if (k == null) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            k = Base64.getEncoder().encodeToString(key);
            Prefs.putSecret(KEY_BACKGROUND, k);
        }
        return Base64.getDecoder().decode(k);
    }

    private static void writeEncrypted(Path source, Path target) throws java.io.IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(part)) {
            try (OutputStream enc = Portable.encrypting(out, backgroundKey(), "00000000000000b9")) {
                in.transferTo(enc);
            }
        }
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** A chat list background as an image, or null. Background thread. */
    static Image readBackground(JSONObject bg) {
        try (InputStream in = Files.newInputStream(Path.of(bg.optString("file")))) {
            Image image = new Image(new ByteArrayInputStream(Portable.decryptAll(in, backgroundKey())));
            return image.isError() ? null : image;
        } catch (Exception e) {
            return null;
        }
    }
}
