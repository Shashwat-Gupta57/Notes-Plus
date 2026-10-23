package org.flexstudios.notes.plus;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.json.JSONObject;

import java.util.List;

/**
 * The chat space's home for the vault that was unlocked: its conversations, newest first, and
 * "New chat". The phone's ChatHomeActivity. Reached only through the chat lock screen, and
 * nothing here refers to any other hidden part of the app.
 *
 * Right-click (or Ctrl+click) a conversation for its options, the phone's long press.
 */
final class ChatHomeView extends Screen {

    final int vaultId;
    private final StackPane root = new StackPane();
    private final ImageView background = new ImageView();
    private final Label title = Ui.label("Chats", 22);
    private final Label subtitle = Ui.label("", 13, "secondary");
    private final Label banner = ChatUi.readOnlyBanner();
    private final VBox card = new VBox(10);
    private final Label cardText = new Label();
    private final Button openAccount = new Button("Open account");
    private final ListView<ChatStore.Conversation> list = new ListView<>();
    private final Label empty = Ui.label("No conversations yet.\nClick New chat and enter a username.", 15, "secondary");
    private final Button fab = Ui.extendedFab("New chat", Icons.CHAT, this::askKind);
    private final Runnable onChange = this::reload;
    private final ChatVaults.Session.Listener session = this::onSession;
    private final Timeline poller = new Timeline(new KeyFrame(Duration.seconds(5), e -> ChatSync.pollAll()));
    private String code = "", username;
    private boolean writable = true, visible;
    private String holder;

    ChatHomeView(int vaultId) {
        this.vaultId = vaultId;
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        background.setPreserveRatio(false);
        background.fitWidthProperty().bind(root.widthProperty());
        background.fitHeightProperty().bind(root.heightProperty());
        background.setVisible(false);
        background.setManaged(false);

        title.setStyle("-fx-font-size: 22px; -fx-text-fill: " + Theme.ref("text_primary") + ";");
        VBox titles = new VBox(0, title, subtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox toolbar = new HBox(Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Back to notes (Esc)", NotesApp::back), titles, Ui.spacer(),
                Ui.iconButton(Icons.GEAR, "text_primary", "Settings", () -> NotesApp.open(new ChatSettingsView(vaultId, false))));
        toolbar.getStyleClass().add("toolbar");
        HBox.setMargin(titles, new Insets(0, 0, 0, 4));

        cardText.setWrapText(true);
        cardText.setStyle("-fx-text-fill: " + Theme.ref("text_primary") + "; -fx-font-size: 15px;");
        openAccount.getStyleClass().setAll("tonal-button");
        openAccount.setOnAction(e -> NotesApp.open(new AccountView()));
        card.getChildren().addAll(cardText, openAccount);
        card.setPadding(new Insets(16));
        card.setStyle("-fx-background-color: " + Theme.ref("app_surface") + "; -fx-background-radius: 16;");
        VBox.setMargin(card, new Insets(8, 16, 8, 16));
        card.setVisible(false);
        card.setManaged(false);

        list.getStyleClass().add("chat-list");
        list.setPlaceholder(new Label());
        list.setCellFactory(v -> new Row());
        list.setStyle("-fx-padding: 0 0 96 0;");
        empty.setWrapText(true);
        empty.setAlignment(Pos.CENTER);
        empty.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        empty.setMouseTransparent(true);
        empty.setVisible(false);
        StackPane listArea = new StackPane(list, empty);
        VBox.setVgrow(listArea, Priority.ALWAYS);

        VBox column = new VBox(toolbar, banner, card, listArea);
        BorderPane frame = new BorderPane(column);
        StackPane.setAlignment(fab, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(fab, new Insets(20));
        root.getChildren().addAll(background, frame, fab);
        poller.setCycleCount(Timeline.INDEFINITE);
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
        refreshHeader();
        ChatSync.schedule();
    }

    @Override
    void onPause() {
        visible = false;
        ChatStore.get().unobserve(onChange);
        ChatVaults.Session.leave(session);
        poller.stop();
    }

    /** Back leaves the chat space for the notes. */
    @Override
    boolean onBack() {
        ChatSpace.close();
        return false;
    }

    /** Writer: everything enabled. Read-only (open on another device): a banner, no new chats, polling. */
    private void onSession(boolean canWrite, String by) {
        writable = canWrite;
        holder = by;
        ChatUi.showBanner(banner, !canWrite, by);
        fab.setVisible(canWrite);
        if (canWrite) poller.stop();
        else poller.play();
    }

    private void reload() {
        ChatStore.load(s -> s.conversations(vaultId), conversations -> {
            if (conversations == null) return;
            list.getItems().setAll(conversations);
            empty.setVisible(conversations.isEmpty());
        });
    }

    /** Title, this vault's address, and whether chatting is possible yet. */
    private void refreshHeader() {
        ChatStore.load(s -> s.vault(vaultId), vault -> {
            if (vault == null) {
                ChatSpace.relock(); // Deleted, from another device.
                return;
            }
            title.setText(vault.isMain ? "Chats" : vault.name);
            if (!vault.code.equals(code)) {
                ChatVaults.Session.leave(session);
                code = vault.code;
            }
            applyBackground(vault.background);
            if (visible) ChatVaults.Session.enter(code, session);
            showAddress();
            if (vault.isMain && "true".equals(Prefs.get(ChatSync.PREF_INHERITED_LOCK))) offerOwnLock();
        });
        if (Account.uid() != null && "true".equals(Prefs.get(ChatSync.PREF_REFUSED))) {
            showCard("The server is refusing chat right now (permission denied), so messages wait here. "
                    + "They'll go out by themselves once it accepts them again.", false);
            return;
        }
        if (Account.uid() == null) {
            showCard("Sign in to your Notes+ account to start chatting. People find you by your username.", true);
            return;
        }
        ChatStore.load(s -> {
            try {
                return Account.loadProfile().username;
            } catch (Exception e) {
                return "";
            }
        }, name -> {
            boolean ready = name != null;
            if (ready && !name.isEmpty()) username = name;
            if (ready) hideCard();
            else showCard("Choose a username in your Notes+ account. It's how people reach you here.", true);
            showAddress();
        });
    }

    private void showCard(String text, boolean withButton) {
        cardText.setText(text);
        openAccount.setVisible(withButton);
        openAccount.setManaged(withButton);
        card.setVisible(true);
        card.setManaged(true);
    }

    private void hideCard() {
        card.setVisible(false);
        card.setManaged(false);
    }

    /** "@name · ID AB12": exactly what someone types to reach this vault. */
    private void showAddress() {
        subtitle.setText((username == null ? "" : "@" + username + "  ·  ") + "ID " + code);
    }

    /** This vault's chat list background, chosen in its settings (this computer only). */
    private void applyBackground(String json) {
        background.setVisible(false);
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        if (json == null) return;
        try {
            JSONObject bg = new JSONObject(json);
            if ("color".equals(bg.optString("kind"))) {
                root.setStyle("-fx-background-color: " + bg.optString("value") + ";");
            } else if ("image".equals(bg.optString("kind"))) {
                ChatStore.load(s -> ChatSettingsView.readBackground(bg), image -> {
                    if (image == null) return;
                    background.setImage(image);
                    background.setVisible(true);
                });
            }
        } catch (Exception ignored) {
            // An unreadable background shows the default.
        }
    }

    /** Once: the main vault opened with the lock it started with. Suggest a lock of its own. */
    private void offerOwnLock() {
        Prefs.put(ChatSync.PREF_INHERITED_LOCK, null);
        Ui.dialog().title("Give chats their own lock?")
                .message("This vault still opens with the lock it was set up with. A lock used only here keeps it "
                        + "private even from someone who knows your other codes.")
                .positive("Choose a lock", () -> NotesApp.open(new ChatSettingsView(vaultId, true)))
                .negative("Keep it", null).width(440).show();
    }

    // ============================================================== new chat

    private void askKind() {
        if (!writable) return;
        Ui.dialog().title("Start").items(List.of("A chat with one person", "A group (up to " + ChatSync.MAX_GROUP + ")"),
                List.of(this::newChat, this::newGroup)).width(360).show();
    }

    private void newChat() {
        if (Account.uid() == null) {
            Ui.toast("Sign in to your account first");
            return;
        }
        Ui.Field name = new Ui.Field("Username", null);
        Ui.Field id = new Ui.Field("Vault ID (optional)", null);
        Label help = Ui.label("Leave the ID empty to reach their main vault", 12, "secondary");
        Label error = Ui.label("", 13);
        error.setStyle("-fx-text-fill: " + Theme.ref("destructive") + ";");
        VBox box = new VBox(12, name, id, help, error);
        VBox.setMargin(id, new Insets(12, 0, 0, 0));
        boolean[] busy = {false};
        Ui.Modal[] modal = new Ui.Modal[1];
        Runnable start = () -> {
            if (busy[0] || name.text().isEmpty()) return;
            busy[0] = true;
            error.setText("");
            ChatStore.load(s -> {
                try {
                    return (Object) ChatSync.open(vaultId, ChatSync.resolve(name.text(), id.text()));
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, result -> {
                busy[0] = false;
                if (result instanceof Integer conversation && conversation > 0) {
                    modal[0].dismiss();
                    openConversation(conversation);
                } else {
                    error.setText(result instanceof String s ? s : "Couldn't start the chat");
                }
            });
        };
        modal[0] = Ui.dialog().title("New chat").content(box).width(420)
                .positive("Start", null).negative("Cancel", null).keepOpen(start).show();
        name.input.setOnAction(e -> start.run());
        id.input.setOnAction(e -> start.run());
        javafx.application.Platform.runLater(name.input::requestFocus);
    }

    /** A name and the people: usernames, each optionally /ID for a particular vault of theirs. */
    private void newGroup() {
        if (Account.uid() == null) {
            Ui.toast("Sign in to your account first");
            return;
        }
        Ui.Field name = new Ui.Field("Group name", null);
        Ui.Field people = new Ui.Field("Members", null);
        Label help = Ui.label("Usernames, separated by commas. Add /ID for a particular vault, like nancy/AB12.", 12, "secondary");
        help.setWrapText(true);
        Label error = Ui.label("", 13);
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: " + Theme.ref("destructive") + ";");
        VBox box = new VBox(12, name, people, help, error);
        VBox.setMargin(people, new Insets(12, 0, 0, 0));
        boolean[] busy = {false};
        Ui.Modal[] modal = new Ui.Modal[1];
        Runnable create = () -> {
            if (busy[0] || name.text().isEmpty()) return;
            busy[0] = true;
            error.setText("");
            String groupName = name.text().length() > 60 ? name.text().substring(0, 60) : name.text();
            ChatStore.load(s -> {
                try {
                    return (Object) ChatSync.createGroup(vaultId, groupName, ChatSync.resolveAll(people.text()));
                } catch (Exception e) {
                    return e.getMessage() == null ? "Couldn't create the group" : e.getMessage();
                }
            }, result -> {
                busy[0] = false;
                if (result instanceof Integer conversation && conversation > 0) {
                    modal[0].dismiss();
                    openConversation(conversation);
                } else {
                    error.setText(String.valueOf(result));
                }
            });
        };
        modal[0] = Ui.dialog().title("New group").content(box).width(440)
                .positive("Create", null).negative("Cancel", null).keepOpen(create).show();
        javafx.application.Platform.runLater(name.input::requestFocus);
    }

    private void openConversation(int id) {
        NotesApp.open(new ChatView(vaultId, id));
    }

    // =============================================================== options

    private void showOptions(ChatStore.Conversation c, Node anchor) {
        if (!writable) {
            Ui.toast("Open on " + holder + ". Changes can only be made there for now.");
            return;
        }
        Ui.menu(anchor, List.of("Move to another vault…", c.isGroup ? "Leave group" : "Delete"),
                List.of(() -> askMove(c), c.isGroup ? () -> ChatStore.write(s -> {
                    try {
                        ChatSync.leaveGroup(c.id);
                    } catch (Exception e) {
                        javafx.application.Platform.runLater(() -> Ui.toast("Couldn't leave right now"));
                    }
                }) : () -> confirmDelete(c)));
    }

    /** By ID, not from a list: a vault never shows which other vaults exist. */
    private void askMove(ChatStore.Conversation c) {
        Ui.prompt("Move to another vault", "Enter the ID of the vault to move this conversation to. "
                        + (c.isGroup ? "The group" : "@" + c.peerName) + " keeps writing to the same address; you'll read and reply from there.",
                "Vault ID", null, "Move", raw -> {
                    String target = ChatSync.normaliseCode(raw);
                    ChatStore.load(s -> target == null || target.isEmpty() ? null : s.vaultByCode(target), to -> {
                        if (to == null) {
                            Ui.toast("No vault with that ID on this computer");
                        } else if (to.id == vaultId) {
                            Ui.toast("It's already here");
                        } else {
                            ChatStore.write(s -> ChatVaults.move(c, to));
                            Ui.toast("Moved");
                        }
                    });
                }, null, null, null);
    }

    private void confirmDelete(ChatStore.Conversation c) {
        Ui.dialog().title("Delete this conversation?")
                .message("It's removed from this computer. If @" + c.peerName + " writes again, it comes back with their recent messages.")
                .positive("Delete", () -> ChatStore.write(s -> {
                    s.deleteConversation(c.id);
                    // Or the next fetch would bring it straight back.
                    String uid = Account.uid();
                    if (uid != null) {
                        try {
                            Firebase.put("chatIndex/" + uid + "/" + c.chatId, JSONObject.NULL);
                        } catch (Exception ignored) {
                            // Deleted here regardless.
                        }
                    }
                }))
                .negative("Cancel", null).width(420).show();
    }

    // ================================================================== rows

    private final class Row extends ListCell<ChatStore.Conversation> {
        private final ChatUi.Avatar avatar = new ChatUi.Avatar(48);
        private final Label peer = new Label(), last = new Label(), time = new Label(), unread = new Label();
        private final HBox box;

        Row() {
            setStyle("-fx-background-color: transparent; -fx-padding: 0;");
            peer.setStyle("-fx-text-fill: " + Theme.ref("text_primary") + "; -fx-font-size: 16px; -fx-font-weight: bold;");
            last.setStyle("-fx-text-fill: " + Theme.ref("text_secondary") + "; -fx-font-size: 14px;");
            time.setStyle("-fx-text-fill: " + Theme.ref("text_secondary") + "; -fx-font-size: 12px;");
            unread.setStyle("-fx-background-color: " + Theme.ref("tile_blue") + "; -fx-background-radius: 10; -fx-text-fill: white;"
                    + " -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 1 6 1 6;");
            unread.setMinHeight(20);
            unread.setMinWidth(20);
            unread.setAlignment(Pos.CENTER);
            peer.setMaxWidth(Double.MAX_VALUE);
            last.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(peer, Priority.ALWAYS);
            HBox.setHgrow(last, Priority.ALWAYS);
            HBox top = new HBox(8, peer, time);
            HBox bottom = new HBox(8, last, unread);
            top.setAlignment(Pos.CENTER_LEFT);
            bottom.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(2, top, bottom);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(14, avatar, text);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("chat-row");
            box.setOnMouseClicked(e -> {
                ChatStore.Conversation c = getItem();
                if (c == null) return;
                if (e.getButton() == MouseButton.SECONDARY || e.isShortcutDown()) showOptions(c, box);
                else if (e.getButton() == MouseButton.PRIMARY) openConversation(c.id);
            });
        }

        @Override
        protected void updateItem(ChatStore.Conversation c, boolean emptyRow) {
            super.updateItem(c, emptyRow);
            setText(null);
            if (emptyRow || c == null) {
                setGraphic(null);
                return;
            }
            String name = c.peerName == null ? "?" : c.peerName;
            boolean nick = c.nickname != null && !c.nickname.isEmpty();
            String theirVault = c.peerShown != null ? c.peerShown : c.peerVault;
            peer.setText(c.isGroup ? name : nick ? c.nickname : "@" + name + (theirVault == null ? "" : "  ·  " + theirVault));
            avatar.set(nick ? c.nickname : name, c.isGroup ? "tile_purple" : "tile_blue", c.isGroup ? null : c.peerPhoto);
            last.setText(c.lastText == null ? "Say hello" : c.lastText);
            time.setText(ChatUi.when(c.lastAt));
            unread.setVisible(c.unread > 0);
            unread.setManaged(c.unread > 0);
            unread.setText(c.unread > 99 ? "99+" : String.valueOf(c.unread));
            setGraphic(box);
        }
    }
}
