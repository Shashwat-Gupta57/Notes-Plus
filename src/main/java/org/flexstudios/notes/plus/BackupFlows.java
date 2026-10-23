package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The backup questions and progress, shared by the vault's settings and the first run: save a
 * .bak, restore one (asking for the lock that opens it), check one, and the backup password.
 * Jobs run on their own thread with a progress dialog and Cancel; the vault doesn't auto-lock
 * while one runs.
 */
final class BackupFlows {

    private BackupFlows() {}

    /** What opens the backup that just refused a password, when it says (Drive keyrings do). */
    private static volatile String lastWhy;

    interface Job {
        Backup.Result run(Backup.Progress progress) throws Exception;
    }

    /** Runs {@code job} with a progress dialog; {@code done} gets the result, or null with the error shown. */
    static void run(String title, Job job, Consumer<Backup.Result> done) {
        run(title, job, done, null);
    }

    /** As above; {@code wrongPassword} runs instead when the password didn't open the backup. */
    static void run(String title, Job job, Consumer<Backup.Result> done, Runnable wrongPassword) {
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("linear-busy");
        Label status = Ui.label("Starting…", 14, "secondary");
        VBox box = new VBox(12, status, bar);
        AtomicBoolean cancelled = new AtomicBoolean();
        Ui.Modal dialog = Ui.dialog().title(title).content(box).width(420)
                .negative("Cancel", () -> cancelled.set(true)).show();
        Vault.busy(true);
        Thread worker = new Thread(() -> {
            Backup.Result result = null;
            String error = null;
            try {
                result = job.run(new Backup.Progress() {
                    private long last;

                    @Override
                    public void update(long done, long total, String message) {
                        long now = System.nanoTime();
                        if (now - last < 80_000_000L && done < total) return;
                        last = now;
                        double p = total > 0 ? (double) done / total : ProgressBar.INDETERMINATE_PROGRESS;
                        String text = message + (total > 0 ? "  ·  " + ViewerView.bytes(done) + " of " + ViewerView.bytes(total) : "");
                        Platform.runLater(() -> {
                            bar.setProgress(p);
                            status.setText(text);
                        });
                    }

                    @Override
                    public boolean stopped() {
                        return cancelled.get();
                    }
                });
            } catch (java.util.concurrent.CancellationException e) {
                error = "";
            } catch (Backup.WrongPassword e) {
                error = "WRONG";
                lastWhy = e.getMessage() != null && e.getMessage().startsWith("This backup") ? e.getMessage() : null;
            } catch (Exception e) {
                System.getLogger("Backup").log(System.Logger.Level.WARNING, title + " failed", e);
                error = e.getMessage() == null ? "Something went wrong" : e.getMessage();
            }
            Backup.Result r = result;
            String err = error;
            Platform.runLater(() -> {
                Vault.busy(false);
                dialog.dismiss();
                if ("WRONG".equals(err) && wrongPassword != null) {
                    wrongPassword.run();
                    return;
                }
                if (err != null) {
                    String text = "WRONG".equals(err) ? "That password doesn't open this backup" : err;
                    if (!text.isEmpty()) Ui.dialog().title(title + " failed").message(text).positive("OK", null).show();
                    done.accept(null);
                    return;
                }
                done.accept(r);
            });
        }, "notes-backup");
        worker.setDaemon(true);
        worker.start();
    }

    // ================================================================== save

    static void save() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save a backup file");
        chooser.setInitialFileName("Notes-Plus-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".bak");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Notes+ backup", "*.bak"));
        Vault.expectExternal();
        File file = chooser.showSaveDialog(NotesApp.stage());
        if (file == null) return;
        run("Saving backup", p -> Backup.create(file.toPath(), p), r -> {
            if (r == null) return;
            Ui.dialog().title("Backup saved")
                    .message(r.items() + (r.items() == 1 ? " item" : " items") + " · " + ViewerView.bytes(r.bytes())
                            + "\n\nChecked after saving: it will restore.\n\n" + file
                            + (hasPassword() ? "\n\nIt opens only with your backup password."
                            : "\n\nIt opens with any vault's lock, on this computer or the phone."))
                    .positive("Done", null).width(460).show();
        });
    }

    // =============================================================== restore

    /** Picks a .bak and restores it into the open vault; {@code after} runs when it's over. */
    static void restore(Runnable after) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Restore a backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Notes+ backup", "*.bak"));
        Vault.expectExternal();
        File file = chooser.showOpenDialog(NotesApp.stage());
        if (file == null) {
            after.run();
            return;
        }
        Ui.dialog().title("Restore this backup?")
                .message("Items already here stay. The backup's vaults open with the locks they had.")
                .positive("Restore", () -> restore(file.toPath(), null, false, after))
                .neutral("Use its settings too", () -> restore(file.toPath(), null, true, after))
                .negative("Cancel", after).width(460).show();
    }

    private static void restore(Path file, String password, boolean replace, Runnable after) {
        run("Restoring backup", p -> Backup.restore(file, password, replace, p), r -> {
            if (r == null) {
                after.run();
                return;
            }
            Ui.dialog().title("Restored").message(r.items() + (r.items() == 1 ? " item" : " items")
                    + " brought back. Each restored vault opens with the lock it had in the backup.")
                    .positive("Done", after).show();
        }, () -> askKey(password != null, key -> restore(file, key, replace, after), after));
    }

    // ================================================================= check

    static void check() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Check a backup file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Notes+ backup", "*.bak"));
        Vault.expectExternal();
        File file = chooser.showOpenDialog(NotesApp.stage());
        if (file != null) check(file.toPath(), null);
    }

    private static void check(Path file, String password) {
        run("Checking backup", p -> Backup.verify(file, password, p), r -> {
            if (r == null) return;
            Ui.dialog().title("This backup is good")
                    .message("Every part of it was read and verified.\n\n" + r.items() + (r.items() == 1 ? " item" : " items")
                            + " · " + ViewerView.bytes(r.bytes()))
                    .positive("Done", null).show();
        }, () -> askKey(password != null, key -> check(file, key), () -> { }));
    }

    /**
     * Asks for whatever opens a backup: the backup password, or any vault's PIN, password or
     * pattern (drawn, not typed).
     */
    static void askKey(boolean retry, Consumer<String> onKey, Runnable onCancel) {
        String why = lastWhy;
        lastWhy = null;
        askKey(retry, why, onKey, onCancel);
    }

    /** As above, saying what opens this backup ({@code why}, or the general rule when null). */
    static void askKey(boolean retry, String why, Consumer<String> onKey, Runnable onCancel) {
        Ui.Field field = new Ui.Field("PIN or password", null, true);
        PatternView pattern = new PatternView(240);
        StackPane slot = new StackPane(field);
        Button toggle = Ui.textButton("Draw a pattern instead", () -> { });
        toggle.setOnAction(e -> {
            boolean drawing = slot.getChildren().get(0) != pattern;
            slot.getChildren().setAll(drawing ? pattern : field);
            toggle.setText(drawing ? "Type a password or PIN instead" : "Draw a pattern instead");
        });
        VBox box = new VBox(8, slot, toggle);
        box.setPadding(new Insets(4, 0, 0, 0));
        Ui.Modal[] m = new Ui.Modal[1];
        pattern.setOnPattern(p -> {
            m[0].dismiss();
            onKey.accept(p);
        });
        field.input.setOnAction(e -> {
            m[0].dismiss();
            onKey.accept(field.input.getText());
        });
        m[0] = Ui.dialog().title("Unlock backup")
                .message((retry ? "That didn't open it. " : "") + (why != null ? why
                        : "Enter the backup lock, or the lock of any vault in that backup."))
                .content(box).width(420)
                .positive("Open", () -> onKey.accept(field.input.getText()))
                .negative("Cancel", onCancel).show();
        Platform.runLater(field.input::requestFocus);
    }

    // ========================================================== Google Drive

    /** Signs the vault's own Google account in for Drive, in the browser. */
    static void driveConnect(Runnable changed) {
        Ui.Modal[] waiting = {AccountView.browserWait(
                "Choose the Google account for your vault's backups, and allow Notes+ to keep its own files in your Drive.", null)};
        Vault.busy(true);
        Vault.expectExternal();
        Thread worker = new Thread(() -> {
            String error = null;
            try {
                CloudBackup.connect();
            } catch (Exception e) {
                error = "cancelled".equals(e.getMessage()) ? "" : e.getMessage() == null ? "Couldn't connect" : e.getMessage();
            }
            String err = error;
            Platform.runLater(() -> {
                Vault.busy(false);
                waiting[0].dismiss();
                if (err == null) {
                    Ui.toast("Google Drive connected");
                    changed.run();
                    driveBackup(null, changed);
                } else if (!err.isEmpty()) {
                    Ui.dialog().title("Couldn't connect").message(err).positive("OK", null).show();
                }
            });
        }, "notes-drive-connect");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Backs up to Drive. {@code password}: null at first; when the Drive backup was started by
     * the phone, it is asked for once (a phone vault's lock, or its backup password).
     */
    static void driveBackup(String password, Runnable changed) {
        run("Backing up to Google Drive", p -> CloudBackup.backup(password, p), r -> {
            changed.run();
            if (r == null) return;
            Ui.toast("Backed up · " + r.items() + (r.items() == 1 ? " item" : " items") + " on Drive");
        }, () -> askKey(password != null, key -> driveBackup(key, changed), () -> { }));
    }

    /** Lists the restore points on Drive, then restores the one picked. */
    static void driveRestore(String password, Runnable after) {
        java.util.concurrent.atomic.AtomicReference<java.util.List<CloudBackup.Summary>> found = new java.util.concurrent.atomic.AtomicReference<>();
        run("Restore from Google Drive", p -> {
            found.set(CloudBackup.restorePoints(password, p));
            return new Backup.Result(found.get().size(), 0);
        }, r -> {
            if (r == null) return;
            java.util.List<CloudBackup.Summary> points = found.get();
            if (points.isEmpty()) {
                Ui.dialog().title("No restore points yet").message("Back up from this computer or the phone first.")
                        .positive("OK", null).show();
                return;
            }
            SimpleDateFormat when = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault());
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<Runnable> actions = new java.util.ArrayList<>();
            for (CloudBackup.Summary x : points) {
                labels.add(when.format(new Date(x.createdAt)) + "  ·  " + (x.device == null ? "Unknown device" : x.device)
                        + "  ·  " + x.itemCount + (x.itemCount == 1 ? " item" : " items") + (x.pinned ? "  ·  kept" : ""));
                actions.add(() -> confirmDriveRestore(x, after));
            }
            Ui.dialog().title("Pick a restore point").items(labels, actions).negative("Cancel", null).width(520).show();
        }, () -> askKey(password != null, key -> driveRestore(key, after), () -> { }));
    }

    private static void confirmDriveRestore(CloudBackup.Summary point, Runnable after) {
        Ui.dialog().title("Restore this point?")
                .message("Items already here stay; only missing ones are downloaded. The backup's vaults open with the locks they had.")
                .positive("Restore", () -> driveRestore(point, false, after))
                .neutral("Use its settings too", () -> driveRestore(point, true, after))
                .negative("Cancel", null).width(460).show();
    }

    private static void driveRestore(CloudBackup.Summary point, boolean replace, Runnable after) {
        run("Restoring from Google Drive", p -> CloudBackup.restore(point.id, replace, p), r -> {
            after.run();
            if (r == null) return;
            Ui.dialog().title("Restored").message(r.items() + (r.items() == 1 ? " item" : " items")
                    + " brought back. Each restored vault opens with the lock it had in the backup.")
                    .positive("Done", null).show();
        });
    }

    /** The connected account: disconnect it here (the backup on Drive stays). */
    static void driveAccount(Runnable changed) {
        String email = CloudBackup.email();
        Ui.dialog().title("Google Drive")
                .message("Backing up to " + (email == null || email.isEmpty() ? "your Google Drive" : email)
                        + ".\n\nDisconnecting stops backups from this computer. What's on Drive stays, and connecting again picks it up.")
                .positive("Done", null)
                .neutral("Disconnect", () -> {
                    try {
                        CloudBackup.disconnect();
                        Ui.toast("Google Drive disconnected");
                    } catch (Exception e) {
                        Ui.toast("Couldn't disconnect");
                    }
                    changed.run();
                }).width(460).show();
    }

    // ======================================================= backup password

    static boolean hasPassword() {
        try {
            return VaultCrypto.backupPasswordWrap() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** PIN, PASSWORD or PATTERN; null when backups open with the vault locks. */
    static String lockType() {
        try {
            JSONObject w = VaultCrypto.backupPasswordWrap();
            return w == null ? null : w.optString("lockType", LockInput.PASSWORD);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The backup lock: by default backups (Drive and .bak) open with the vault locks, the main
     * one included; set one, of any kind, and it is their only key.
     */
    static void password(Runnable changed) {
        String type = lockType();
        if (type != null) {
            Ui.dialog().title("Backup lock")
                    .message("Your Drive and file backups open only with your backup " + LockInput.label(type).toLowerCase(Locale.ROOT)
                            + ". Keep it safe: without it, a backup can't be opened on a new device.")
                    .positive("Change", () -> setLock(changed))
                    .neutral("Use the vault lock", () -> store(null, null, changed))
                    .negative("Cancel", null).width(440).show();
        } else {
            Ui.dialog().title("Backup lock")
                    .message("Your Drive and file backups open with your main vault lock. Set a backup lock (a PIN, "
                            + "password or pattern) and only it will open them.")
                    .positive("Set a backup lock", () -> setLock(changed))
                    .negative("Cancel", null).width(440).show();
        }
    }

    /** PIN / Password / Pattern, entered twice. */
    private static void setLock(Runnable changed) {
        Ui.Tabs tabs = new Ui.Tabs("m3_primary", "PIN", "Password", "Pattern");
        LockInput[] inputs = new LockInput[3];
        for (int i = 0; i < 3; i++) inputs[i] = new LockInput(LockInput.TYPES[i], null, null);
        StackPane slot = new StackPane(inputs[0]);
        slot.setMinHeight(300);
        tabs.selected.addListener((o, a, b) -> slot.getChildren().setAll(inputs[b.intValue()]));
        tabs.selected.set(0);
        Ui.dialog().title("New backup lock").content(new VBox(12, tabs, slot)).width(440)
                .positive("Next", () -> {
                    LockInput first = inputs[tabs.selected.get()];
                    String value = first.value();
                    String problem = LockInput.problem(first.type, value);
                    if (problem != null) {
                        Ui.toast(problem);
                        return;
                    }
                    LockInput again = new LockInput(first.type, "Once more", null);
                    StackPane confirm = new StackPane(again);
                    confirm.setMinHeight(300);
                    Ui.dialog().title("Once more, to confirm").content(confirm).width(440)
                            .positive("Save", () -> {
                                if (!value.equals(again.value())) {
                                    Ui.toast("Those didn't match. Try again.");
                                    return;
                                }
                                store(first.type, value, changed);
                            })
                            .negative("Cancel", null).show();
                    again.focus();
                })
                .negative("Cancel", null).show();
        inputs[0].focus();
    }

    private static void store(String type, String password, Runnable changed) {
        VaultStore.load(s -> {
            try {
                VaultCrypto.setBackupPasswordWrap(password == null ? null
                        : Backup.wrap(VaultCrypto.backupKey(), password, "Backup lock").put("lockType", type));
                return true;
            } catch (Exception e) {
                return false;
            }
        }, ok -> {
            Ui.toast(Boolean.TRUE.equals(ok) ? (password == null ? "Backups open with your vault lock" : "Backup lock saved")
                    : "Couldn't change the backup lock");
            changed.run();
        });
    }
}
