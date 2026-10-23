package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

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
        Ui.Field field = new Ui.Field("Password or PIN", null, true);
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
                .message((retry ? "That didn't open it. " : "") + "Enter the backup password, or the lock of any vault in that backup.")
                .content(box).width(420)
                .positive("Open", () -> onKey.accept(field.input.getText()))
                .negative("Cancel", onCancel).show();
        Platform.runLater(field.input::requestFocus);
    }

    // ======================================================= backup password

    static boolean hasPassword() {
        try {
            return VaultCrypto.backupPasswordWrap() != null;
        } catch (Exception e) {
            return false;
        }
    }

    static void password(Runnable changed) {
        if (hasPassword()) {
            Ui.dialog().title("Backup password")
                    .message("New backups open only with this password. Without it, any vault's lock opens them.")
                    .positive("Change", () -> setPassword(changed))
                    .neutral("Remove", () -> store(null, changed))
                    .negative("Cancel", null).width(440).show();
        } else {
            setPassword(changed);
        }
    }

    private static void setPassword(Runnable changed) {
        Ui.Field field = new Ui.Field("Backup password", null, true);
        Ui.dialog().title("Backup password")
                .message("Recommended: a 4-digit PIN falls to an offline guesser in minutes. With a password set, it is the only key to new backups. Keep it safe: it can't be recovered.")
                .content(field).width(440)
                .positive("Save", () -> {
                    String pw = field.input.getText();
                    if (pw.length() < 8) {
                        Ui.toast("Use at least 8 characters");
                        return;
                    }
                    store(pw, changed);
                })
                .negative("Cancel", null).show();
        Platform.runLater(field.input::requestFocus);
    }

    private static void store(String password, Runnable changed) {
        VaultStore.load(s -> {
            try {
                VaultCrypto.setBackupPasswordWrap(password == null ? null
                        : Backup.wrap(VaultCrypto.backupKey(), password, "Backup password"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }, ok -> {
            Ui.toast(Boolean.TRUE.equals(ok) ? (password == null ? "Backup password removed" : "Backup password set")
                    : "Couldn't change the backup password");
            changed.run();
        });
    }
}
