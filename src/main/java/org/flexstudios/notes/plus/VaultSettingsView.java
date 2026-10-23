package org.flexstudios.notes.plus;

import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The vault's settings, the phone's GallerySettingsActivity without its backup half (that
 * comes later on the desktop): change lock, auto-lock, screenshots; the vaults themselves
 * (main vault only); and the vault's and the lock screen's wallpapers.
 */
final class VaultSettingsView extends Screen {

    private final int vaultId;
    private final BorderPane root = new BorderPane();
    private final VBox column = new VBox();
    private final VBox vaultRows = new VBox();
    private VaultStore.Vault vault;

    VaultSettingsView(int vaultId) {
        this.vaultId = vaultId;
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
        VaultStore.load(s -> s.vault(vaultId), v -> {
            vault = v;
            build();
        });
    }

    @Override
    Node view() {
        return root;
    }

    private void build() {
        column.getChildren().clear();
        if (vault == null) return;
        boolean main = vault.main;

        column.getChildren().addAll(header("Security"), card(
                row(Icons.LOCK, "tile_blue", main ? "Change main vault lock" : "Change lock",
                        "PIN, password or pattern for this vault", chevron(), () -> editVault(vault)),
                row(Icons.INFO, "tile_orange", "Auto-lock", "Lock the vault after you leave it", autoLock(), null),
                row(Icons.PHOTO, "tile_purple", "Allow screenshots",
                        "Off: these screens can't be captured, recorded or shared", toggle(Vault.screenshotsAllowed(),
                                Vault::setScreenshotsAllowed), null)));

        if (main) {
            column.getChildren().addAll(header("Vaults"), card(vaultRows,
                    row(Icons.ADD, "tile_green", "New vault", "A separate space with its own lock", null, () -> editVault(null))),
                    footer("Each vault opens with its own lock from the same secret gesture."));
            loadVaults();
        }

        column.getChildren().addAll(header("Appearance"), card(
                wallpaperRow("Vault wallpaper", "Behind this vault's gallery", (type, value) -> {
                    vault.bgType = type;
                    vault.bgValue = value;
                    VaultStore.write(s -> s.updateVault(vault));
                    Ui.toast("Vault background updated");
                }, "bg_vault_" + vault.id),
                wallpaperRow("Lock screen wallpaper", "Shared by every vault", (type, value) -> {
                    Prefs.put(Vault.KEY_LOCK_BG_TYPE, type);
                    Prefs.put(Vault.KEY_LOCK_BG_VALUE, value);
                    Ui.toast("Lock screen background updated");
                }, "bg_lock"),
                lockOptions()));
        if (main) {
            column.getChildren().addAll(header("Backup file"), card(
                    row(Icons.SAVE, "tile_blue", "Save a backup file", "One encrypted .bak to keep, or to open on another device",
                            chevron(), BackupFlows::save),
                    row(Icons.CLOUD, "tile_green", "Restore", "From a .bak file, made here or on the phone", chevron(),
                            () -> BackupFlows.restore(() -> { })),
                    row(Icons.CHECK, "tile_teal", "Check a backup file", "Reads it end to end and confirms it will restore",
                            chevron(), BackupFlows::check),
                    row(Icons.LOCK, "tile_orange", "Backup password", BackupFlows.hasPassword()
                                    ? "Set: only it opens new backups" : "Recommended: PINs are easy to guess offline",
                            chevron(), () -> BackupFlows.password(this::build))),
                    footer("Google Drive backups come to the desktop later."));
        }
    }

    // ============================================================ security

    private Node autoLock() {
        long delay = Vault.autoLockDelay();
        String[] labels = {"At once", "30 s", "1 min"};
        long[] values = {0, 30_000, 60_000};
        HBox group = new HBox();
        group.getStyleClass().add("segmented");
        group.setMaxHeight(Region.USE_PREF_SIZE);
        Button[] buttons = new Button[3];
        for (int i = 0; i < 3; i++) {
            int index = i;
            Button b = new Button(labels[i]);
            b.getStyleClass().setAll("segment");
            if (i == 0) b.getStyleClass().add("first");
            if (i == 2) b.getStyleClass().add("last");
            if (values[i] == delay || (i == 0 && delay != 30_000 && delay != 60_000)) b.getStyleClass().add("on");
            b.setOnAction(e -> {
                Vault.setAutoLockDelay(values[index]);
                for (Button o : buttons) o.getStyleClass().remove("on");
                b.getStyleClass().add("on");
            });
            buttons[i] = b;
            group.getChildren().add(b);
        }
        return group;
    }

    /** A Material switch. */
    static Node toggle(boolean on, Consumer<Boolean> changed) {
        StackPane track = new StackPane();
        track.getStyleClass().add("switch-track");
        track.setMinSize(52, 32);
        track.setMaxSize(52, 32);
        Region thumb = new Region();
        thumb.getStyleClass().add("switch-thumb");
        track.getChildren().add(thumb);
        boolean[] state = {on};
        Runnable show = () -> {
            if (state[0]) track.getStyleClass().add("on");
            else track.getStyleClass().remove("on");
            double size = state[0] ? 24 : 16;
            thumb.setMinSize(size, size);
            thumb.setMaxSize(size, size);
            TranslateTransition t = new TranslateTransition(Duration.millis(140), thumb);
            t.setToX(state[0] ? 10 : -10);
            t.play();
        };
        track.setOnMouseClicked(e -> {
            state[0] = !state[0];
            show.run();
            changed.accept(state[0]);
        });
        show.run();
        return track;
    }

    // ============================================================== vaults

    private void loadVaults() {
        VaultStore.load(s -> {
            List<VaultStore.Vault> all = s.vaults();
            int[] counts = new int[all.size()];
            for (int i = 0; i < all.size(); i++) counts[i] = s.countFor(all.get(i).id);
            return new Object[]{all, counts};
        }, r -> {
            @SuppressWarnings("unchecked") List<VaultStore.Vault> all = (List<VaultStore.Vault>) r[0];
            int[] counts = (int[]) r[1];
            vaultRows.getChildren().clear();
            for (int i = 0; i < all.size(); i++) {
                VaultStore.Vault v = all.get(i);
                String sub = LockInput.label(v.lockType) + "  ·  " + counts[i] + (counts[i] == 1 ? " item" : " items")
                        + (v.main ? "  ·  Main" : "");
                Node end = v.main ? chevron() : Ui.iconButton(Icons.DELETE, 40, 20, "text_secondary", "Delete vault", () -> deleteVault(v));
                if (!vaultRows.getChildren().isEmpty()) vaultRows.getChildren().add(divider());
                vaultRows.getChildren().add(row(Icons.LOCK, v.main ? "tile_blue" : "tile_gray", v.name, sub, end, () -> editVault(v)));
            }
        });
    }

    /** Creates a vault ({@code v} null) or edits one: its name and lock. */
    private void editVault(VaultStore.Vault v) {
        boolean creating = v == null;
        Ui.Field name = new Ui.Field("Vault name", creating ? null : v.name);
        Ui.Tabs tabs = new Ui.Tabs("m3_primary", "PIN", "Password", "Pattern");
        LockInput[] inputs = new LockInput[3];
        for (int i = 0; i < 3; i++) {
            String type = LockInput.TYPES[i];
            String hint = creating ? null : LockInput.PIN.equals(type) ? "New PIN (blank = keep)"
                    : LockInput.PASSWORD.equals(type) ? "New password (blank = keep)" : null;
            inputs[i] = new LockInput(type, hint, null);
        }
        StackPane slot = new StackPane();
        slot.setMinHeight(300);
        tabs.selected.addListener((o, a, b) -> slot.getChildren().setAll(inputs[b.intValue()]));
        int start = 0;
        if (!creating) for (int i = 0; i < 3; i++) if (LockInput.TYPES[i].equals(v.lockType)) start = i;
        tabs.selected.set(start);
        slot.getChildren().setAll(inputs[start]);
        VBox box = new VBox(12, name, tabs, slot);
        Ui.dialog().title(creating ? "New Secret Vault" : "Edit Vault Settings").content(box).width(440)
                .positive(creating ? "Create" : "Save", () -> {
                    LockInput input = inputs[tabs.selected.get()];
                    String value = input.value();
                    boolean typeChanged = !creating && !input.type.equals(v.lockType);
                    boolean keep = !creating && value.isEmpty() && !typeChanged;
                    if (!keep) {
                        String problem = LockInput.problem(input.type, value);
                        if (problem != null) {
                            Ui.toast(problem);
                            return;
                        }
                    }
                    saveVault(v, name.text(), input.type, keep ? null : value);
                })
                .negative("Cancel", null).show();
    }

    private void saveVault(VaultStore.Vault v, String name, String type, String newLock) {
        VaultStore.load(s -> {
            try {
                if (newLock != null) {
                    for (VaultStore.Vault other : s.vaults()) {
                        if ((v == null || other.id != v.id) && type.equals(other.lockType)
                                && VaultCrypto.matches(other.lockHash, type, newLock)) {
                            return "Another vault already uses that lock";
                        }
                    }
                }
                VaultStore.Vault target = v;
                if (target == null) {
                    target = new VaultStore.Vault();
                    target.name = name.isEmpty() ? "New Vault" : name;
                    target.sortOrder = s.vaults().size();
                    target.lockType = type;
                    target.lockHash = VaultCrypto.hashLock(type, newLock);
                    s.insertVault(target);
                } else {
                    if (!name.isEmpty()) target.name = name;
                    if (newLock != null) {
                        target.lockType = type;
                        target.lockHash = VaultCrypto.hashLock(type, newLock);
                    }
                    s.updateVault(target);
                }
                // The data key, wrapped under the new lock: a few hundred bytes, no media moves.
                if (newLock != null) VaultCrypto.wrapFor(target.uuid, type, newLock);
                if (newLock != null && target.main) ChatVaults.rememberInitialLock(type, newLock);
                return null;
            } catch (Exception e) {
                return "Couldn't save the vault";
            }
        }, error -> {
            if (error != null) {
                Ui.toast(error);
                return;
            }
            VaultStore.load(s -> s.vault(vaultId), fresh -> {
                vault = fresh;
                build();
            });
        });
    }

    private void deleteVault(VaultStore.Vault v) {
        VaultStore.load(s -> s.countFor(v.id), count -> {
            if (count > 0) {
                Ui.dialog().title("Delete " + v.name + "?")
                        .message("There are " + count + " items still inside. What would you like to do?")
                        .neutral("Cancel", null)
                        .negative("Delete Everything", () -> eraseVault(v, false))
                        .positive("Move out, then Delete", () -> eraseVault(v, true))
                        .width(460).show();
            } else {
                Ui.dialog().title("Delete Vault?").message("Are you sure you want to delete this empty vault?")
                        .positive("Delete", () -> eraseVault(v, false)).negative("Cancel", null).show();
            }
        });
    }

    /** Items that couldn't be moved out would be stranded, so the vault stays until they are out. */
    private void eraseVault(VaultStore.Vault v, boolean moveOut) {
        VaultStore.load(s -> {
            int stuck = 0;
            for (VaultStore.Secret secret : s.secretsFor(v.id)) {
                if (moveOut) {
                    try {
                        VaultMedia.moveOut(secret);
                    } catch (Exception e) {
                        stuck++;
                    }
                } else {
                    VaultMedia.remove(secret);
                }
            }
            if (stuck == 0) {
                s.deleteVault(v.id);
                Storage.deleteRecursive(VaultCrypto.dir().resolve("bg_vault_" + v.id));
                try {
                    VaultCrypto.forget(v.uuid);
                } catch (Exception ignored) {
                    // An orphaned wrap opens nothing without its vault's lock and row.
                }
            }
            return stuck;
        }, stuck -> {
            Ui.toast(stuck == 0 ? "Vault deleted" : stuck + " item(s) couldn't be moved out, so the vault was kept");
            loadVaults();
        });
    }

    // ========================================================== wallpapers

    static Node wallpaperRow(String title, String subtitle, BiConsumer<String, String> set, String folder) {
        FlowPane chips = new FlowPane(8, 8);
        String[] labels = {"Default", "Colour", "Image", "Video", "Link"};
        for (String l : labels) {
            Button b = new Button(l);
            b.getStyleClass().setAll("tonal-button");
            b.setMinHeight(40);
            b.setOnAction(e -> {
                switch (l) {
                    case "Default" -> set.accept("DEFAULT", null);
                    case "Colour" -> Ui.prompt("Colour", "A hex colour, like #1C1C1E.", "#RRGGBB", "#", "Use it", c -> {
                        try {
                            Color.web(c);
                            set.accept("COLOR", c);
                        } catch (Exception ex) {
                            Ui.toast("That isn't a colour like #RRGGBB");
                        }
                    }, null, null, null);
                    case "Image", "Video" -> pickWallpaper(l.equals("Video"), folder, set);
                    default -> Ui.prompt("Wallpaper from a link", "An image, GIF or video on the web.", "https://…", null,
                            "Use it", url -> set.accept("URL", url), null, null, null);
                }
            });
            chips.getChildren().add(b);
        }
        VBox texts = new VBox(1, Ui.label(title, 16), Ui.label(subtitle, 13, "secondary"), chips);
        VBox.setMargin(chips, new Insets(10, 0, 0, 0));
        HBox row = new HBox(texts);
        row.setPadding(new Insets(12, 16, 14, 16));
        return row;
    }

    /** Copies the chosen file next to the vault, so the wallpaper survives the original moving. */
    static void pickWallpaper(boolean video, String folder, BiConsumer<String, String> set) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(video ? "Choose a video" : "Choose a picture");
        chooser.getExtensionFilters().add(video ? new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.m4v", "*.mov")
                : new FileChooser.ExtensionFilter("Pictures", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
        Vault.expectExternal();
        ChatSpace.expectExternal();
        File file = chooser.showOpenDialog(NotesApp.stage());
        if (file == null) return;
        VaultStore.load(s -> {
            try {
                Path dir = VaultCrypto.dir().resolve(folder);
                Storage.deleteRecursive(dir);
                Files.createDirectories(dir);
                Path target = dir.resolve("wallpaper_" + System.currentTimeMillis() + file.getName().substring(Math.max(0, file.getName().lastIndexOf('.'))));
                Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                return target.toString();
            } catch (Exception e) {
                return null;
            }
        }, path -> {
            if (path == null) Ui.toast("Couldn't use that file");
            else set.accept(video ? "VIDEO" : "IMAGE", path);
        });
    }

    static Node lockOptions() {
        VBox box = new VBox(
                check("Blur the image", Vault.KEY_LOCK_BG_BLUR),
                check("Dim it for contrast", Vault.KEY_LOCK_BG_DIM));
        box.setPadding(new Insets(0, 16, 12, 16));
        return box;
    }

    static Node check(String text, String key) {
        StackPane box = new StackPane(Ui.icon(Icons.CHECK, 16, "white"));
        box.getStyleClass().add("check");
        box.setMinSize(18, 18);
        box.setMaxSize(18, 18);
        boolean[] on = {"true".equals(Prefs.get(key))};
        Runnable show = () -> {
            if (on[0]) box.getStyleClass().add("on");
            else box.getStyleClass().remove("on");
            box.getChildren().get(0).setVisible(on[0]);
        };
        show.run();
        HBox row = new HBox(box, Ui.label(text, 15));
        row.getStyleClass().add("check-row");
        row.setOnMouseClicked(e -> {
            on[0] = !on[0];
            Prefs.put(key, String.valueOf(on[0]));
            show.run();
        });
        return row;
    }

    // ============================================================= helpers

    static Label header(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("settings-header");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    static Label footer(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-footer");
        l.setWrapText(true);
        return l;
    }

    static VBox card(Node... rows) {
        VBox card = new VBox();
        card.getStyleClass().add("settings-card");
        for (Node r : rows) {
            if (!card.getChildren().isEmpty()) card.getChildren().add(divider());
            card.getChildren().add(r);
        }
        VBox.setMargin(card, new Insets(0, 16, 0, 16));
        return card;
    }

    static Region divider() {
        Region d = new Region();
        d.getStyleClass().add("settings-divider");
        VBox.setMargin(d, new Insets(0, 0, 0, 62));
        return d;
    }

    static Node chevron() {
        return Ui.icon(Icons.CHEVRON_RIGHT, 20, "text_secondary");
    }

    static HBox row(Icons.Icon icon, String color, String title, String subtitle, Node end, Runnable action) {
        StackPane tile = new StackPane(Ui.icon(icon, 20, "white"));
        tile.setStyle("-fx-background-color: " + Theme.ref(color) + "; -fx-background-radius: 8;");
        tile.setMinSize(30, 30);
        tile.setMaxSize(30, 30);
        Label sub = Ui.label(subtitle, 13, "secondary");
        sub.setWrapText(true);
        VBox texts = new VBox(1, Ui.label(title, 16), sub);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(tile, texts);
        if (end != null) row.getChildren().add(end);
        row.getStyleClass().add("settings-row");
        if (action != null) row.setOnMouseClicked(e -> action.run());
        else row.setStyle("-fx-cursor: default;");
        return row;
    }
}
