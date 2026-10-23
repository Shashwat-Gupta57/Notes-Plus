package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The first run's "App Lock", the phone's SetupLockActivity: choose a PIN, password or
 * pattern. It becomes the main vault's lock, and the vault's data key is made and wrapped
 * under it. Backups and restore come later on the desktop, so there is nothing to restore.
 */
final class SetupLockView extends Screen {

    private final StackPane root = new StackPane();
    private final StackPane container = new StackPane();
    private final Ui.Tabs tabs = new Ui.Tabs("m3_primary", "PIN", "Password", "Pattern");
    private final LockInput[] inputs = new LockInput[3];
    private boolean done;
    private final String realm;

    SetupLockView() {
        this(UnlockView.REALM_GALLERY);
    }

    /** {@link UnlockView#REALM_CHAT}: the chat space's first lock, when there was none to start from. */
    SetupLockView(String realm) {
        this.realm = realm;
        root.setStyle("-fx-background-color: " + Theme.ref("app_background") + ";");
        for (int i = 0; i < 3; i++) {
            String type = LockInput.TYPES[i];
            inputs[i] = new LockInput(type, LockInput.PIN.equals(type) ? "Enter 4-6 digits"
                    : LockInput.PASSWORD.equals(type) ? "Enter password" : null, v -> save());
        }
        container.setMinHeight(320);
        tabs.selected.addListener((o, a, b) -> show(b.intValue()));
        show(0);

        Label title = Ui.label("App Lock", 28, "bold");
        Label subtitle = Ui.label("Choose how you want to protect your privacy", 16, "secondary");
        Button set = new Button("Set Lock");
        set.getStyleClass().setAll("filled-button");
        set.setMaxWidth(Double.MAX_VALUE);
        set.setStyle("-fx-font-size: 18px;");
        set.setOnAction(e -> save());

        VBox column = new VBox(title, subtitle, tabs, container, set);
        column.setAlignment(Pos.TOP_CENTER);
        column.setSpacing(16);
        column.setPadding(new Insets(48, 24, 24, 24));
        column.setMaxWidth(480);
        VBox.setMargin(tabs, new Insets(16, 0, 0, 0));
        root.getChildren().add(column);
        StackPane.setAlignment(column, Pos.TOP_CENTER);
    }

    private void show(int tab) {
        container.getChildren().setAll(inputs[tab]);
        inputs[tab].focus();
    }

    @Override
    Node view() {
        return root;
    }

    /** No way around it: the app needs its lock before anything else (chat's can wait). */
    @Override
    boolean onBack() {
        return !done && !UnlockView.REALM_CHAT.equals(realm);
    }

    private void save() {
        LockInput input = inputs[tabs.selected.get()];
        String value = input.value();
        String problem = LockInput.problem(input.type, value);
        if (problem != null) {
            Ui.toast(problem);
            return;
        }
        if (UnlockView.REALM_CHAT.equals(realm)) {
            ChatStore.load(s -> ChatVaults.createMain(input.type, value), main -> {
                if (main == null) {
                    Ui.toast("Couldn't set the lock");
                    return;
                }
                done = true;
                ChatSpace.opened(main.id);
                NotesApp.replace(new ChatHomeView(main.id));
            });
            return;
        }
        VaultStore.load(s -> {
            try {
                VaultStore.Vault main = s.mainVault();
                if (main == null) {
                    main = new VaultStore.Vault();
                    main.name = "Main Vault";
                    main.main = true;
                }
                main.lockType = input.type;
                main.lockHash = VaultCrypto.hashLock(input.type, value);
                if (main.id == 0) s.insertVault(main);
                else s.updateVault(main);
                VaultCrypto.create(main.uuid, input.type, value);
                ChatVaults.rememberInitialLock(input.type, value);
                return true;
            } catch (Exception e) {
                System.getLogger("Vault").log(System.Logger.Level.ERROR, "Setup failed", e);
                return false;
            }
        }, ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                Ui.toast("Couldn't set the lock");
                return;
            }
            Prefs.put(Vault.KEY_SETUP_DONE, "true");
            done = true;
            // As on the phone: a fresh lock is the moment to bring an old vault back.
            Ui.dialog().title("Setup Complete")
                    .message("Would you like to restore data from a previous backup?")
                    .positive("Restore Data", () -> BackupFlows.restore(this::finish))
                    .negative("Skip", this::finish).show();
        });
    }

    private void finish() {
        VaultCrypto.lock();
        NotesApp.back();
    }
}
