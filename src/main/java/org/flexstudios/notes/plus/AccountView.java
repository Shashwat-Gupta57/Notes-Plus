package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.function.Consumer;

/**
 * The notes account: sign in (Google or email), verify an email, then a profile with sync
 * status, the Drive connection for attachments, username, sign out and delete. The phone's
 * AccountActivity, panel for panel.
 *
 * All auth goes through {@link Account}; this screen only shows state and asks questions.
 */
final class AccountView extends Screen {

    private final BorderPane root = new BorderPane();
    private final VBox column = new VBox();
    private final ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Ui.Field email = new Ui.Field("Email", null);
    private final Ui.Field password = new Ui.Field("Password", null, true);
    private final Button emailButton = new Button("Sign in");
    private final Button toggleMode = Ui.textButton("New here? Create an account", this::toggleMode);
    private final Button googleButton = new Button("Continue with Google", Ui.icon(Icons.PERSON, 18, "m3_on_primary"));
    private boolean creating;
    private Account.Profile profile;
    private final Label syncStatus = Ui.label("", 13, "secondary"), driveStatus = Ui.label("", 13, "secondary");

    AccountView() {
        root.setStyle("-fx-background-color: " + Theme.ref("gallery_background") + ";");
        HBox toolbar = new HBox(Ui.iconButton(Icons.ARROW_BACK, "text_primary", "Back (Esc)", NotesApp::back),
                Ui.label("Account", 22));
        toolbar.getStyleClass().add("toolbar");
        toolbar.setSpacing(8);
        progress.getStyleClass().add("linear-busy");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(4);
        progress.setVisible(false);
        root.setTop(new VBox(toolbar, progress));

        column.setMaxWidth(520);
        StackPane page = new StackPane(column);
        StackPane.setAlignment(column, Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setCenter(scroll);

        email.getStyleClass().add("on-page");
        password.getStyleClass().add("on-page");
        googleButton.getStyleClass().setAll("filled-button");
        googleButton.setMaxWidth(Double.MAX_VALUE);
        googleButton.setOnAction(e -> signInWithGoogle());
        emailButton.getStyleClass().setAll("tonal-button", "wide");
        emailButton.setMaxWidth(Double.MAX_VALUE);
        emailButton.setOnAction(e -> emailSubmit());
        password.input.setOnAction(e -> emailSubmit());
        email.input.setOnAction(e -> password.input.requestFocus());

        NoteSync.observe(this::showSyncStatus);
        refresh();
    }

    @Override
    Node view() {
        return root;
    }

    @Override
    void onResume() {
        showSyncStatus();
    }

    // ================================================================= state

    private void refresh() {
        busy(false);
        column.getChildren().clear();
        if (!Account.signedIn()) column.getChildren().add(signInPanel());
        else if (Account.uid() == null) column.getChildren().add(verifyPanel());
        else column.getChildren().add(accountPanel());
        column.getChildren().add(appSection());
    }

    /** Updates and About: in every state, signed in or not. */
    private Node appSection() {
        Label updateStatus = Ui.label("You're on " + Updates.VERSION, 13, "secondary");
        Label lockTime = Ui.label(lockTimeLabel(NoteLock.timeoutMinutes()), 13, "secondary");
        Label theme = Ui.label(themeLabel(), 13, "secondary");
        VBox app = card(
                row(Icons.PALETTE, "tile_purple", "Theme", theme, chevron(), () -> chooseTheme(theme)),
                row(Icons.LOCK, "tile_orange", "Locked notes", lockTime, chevron(), () -> chooseLockTime(lockTime)),
                row(Icons.SAVE, "tile_green", "Check for updates", updateStatus, null, () -> checkForUpdate(updateStatus)),
                row(Icons.INFO, "tile_gray", "About", Ui.label("Version " + Updates.VERSION, 13, "secondary"), null,
                        AccountView::showAbout));
        return new VBox(header("Notes+"), app);
    }

    private static String themeLabel() {
        int i = java.util.Arrays.asList(Theme.MODES).indexOf(Theme.mode());
        return Theme.MODE_NAMES[Math.max(0, i)];
    }

    /** The whole app's look: Windows' choice, light, dark, or high contrast. */
    private static void chooseTheme(Label shown) {
        int current = Math.max(0, java.util.Arrays.asList(Theme.MODES).indexOf(Theme.mode()));
        Ui.dialog().title("Theme").singleChoice(Theme.MODE_NAMES, current, which -> {
            Theme.setMode(Theme.MODES[which]);
            shown.setText(themeLabel());
        }).show();
    }

    private static final int[] LOCK_MINUTES = {0, 1, 5, 15, 30, 60, -1};

    private static String lockTimeLabel(int minutes) {
        return minutes == 0 ? "Lock again as soon as I leave the note"
                : minutes < 0 ? "Stay open until Notes+ closes"
                : "Lock again " + (minutes == 60 ? "1 hour" : minutes + (minutes == 1 ? " minute" : " minutes")) + " after leaving";
    }

    /** How long an unlocked note stays open after leaving it, like a vault's auto-lock. */
    private static void chooseLockTime(Label shown) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        for (int minutes : LOCK_MINUTES) {
            labels.add((minutes == NoteLock.timeoutMinutes() ? "\u2713  " : "") + lockTimeLabel(minutes));
            actions.add(() -> {
                NoteLock.setTimeoutMinutes(minutes);
                shown.setText(lockTimeLabel(minutes));
            });
        }
        Ui.dialog().title("Locked notes").items(labels, actions).negative("Cancel", null).width(440).show();
    }

    private static void checkForUpdate(Label status) {
        status.setText("Checking…");
        Account.BG.execute(() -> {
            String text;
            Updates.Release found = null;
            try {
                found = Updates.check();
                text = found == null ? "Up to date (" + Updates.VERSION + ")" : "Version " + found.versionName() + " is available";
            } catch (Exception e) {
                text = "Couldn't check. Are you online?";
            }
            String shown = text;
            Updates.Release release = found;
            Platform.runLater(() -> {
                status.setText(shown);
                if (release != null) Updates.offer(release);
            });
        });
    }

    private static void showAbout() {
        Ui.dialog().title("Notes+ for Windows")
                .message("Version " + Updates.VERSION + "\n\n"
                        + "Notes, lists, mind maps, sketches and recordings, synced with your phone.\n\n"
                        + "Flex Studios · notes.talken.in")
                .positive("OK", null)
                .neutral("Website", () -> NotesApp.openInBrowser("https://notes.talken.in"))
                .show();
    }

    private void busy(boolean on) {
        progress.setVisible(on);
        emailButton.setDisable(on);
        googleButton.setDisable(on);
    }

    /** Runs {@code work} off the FX thread; {@code then} gets null or a message for people. */
    private void background(Work work, Consumer<String> then) {
        busy(true);
        Account.BG.execute(() -> {
            String error = null;
            try {
                work.run();
            } catch (Throwable e) {
                System.getLogger("Account").log(System.Logger.Level.WARNING, "Account step failed", e);
                error = e instanceof Exception ? Account.friendly((Exception) e)
                        : (e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : e.getClass().getSimpleName());
            }
            String result = error;
            Platform.runLater(() -> {
                busy(false);
                then.accept(result);
            });
        });
    }

    interface Work {
        void run() throws Throwable;
    }

    // ================================================================ panels

    private Node signInPanel() {
        VBox p = new VBox();
        p.setPadding(new Insets(24, 24, 24, 24));
        p.setAlignment(Pos.TOP_CENTER);
        Label headline = Ui.label("Your notes, on every device", 24);
        Label text = Ui.label("Sign in to back up your notes and keep them in step across your phone and computer. "
                + "Photos and recordings go to your own Google Drive.", 15, "secondary");
        text.setWrapText(true);
        text.setAlignment(Pos.CENTER);
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Label or = Ui.label("or use your email", 13, "secondary");
        Button forgot = Ui.textButton("Forgot password?", this::forgotPassword);
        forgot.setStyle("-fx-text-fill: " + Theme.ref("text_secondary") + ";");
        emailButton.setText(creating ? "Create account" : "Sign in");
        toggleMode.setText(creating ? "Have an account? Sign in" : "New here? Create an account");
        p.getChildren().addAll(tile(Icons.CLOUD, "tile_blue", 72), gap(20), headline, gap(8), text, gap(28), googleButton,
                gap(20), or, gap(12), email, gap(12), password, gap(16), emailButton, gap(4), toggleMode, forgot);
        return p;
    }

    private Node verifyPanel() {
        VBox p = new VBox();
        p.setPadding(new Insets(24));
        p.setAlignment(Pos.TOP_CENTER);
        Label text = Ui.label("We sent a link to " + Account.email() + ". Click it to confirm it's you, then come back here.",
                15, "secondary");
        text.setWrapText(true);
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Button verified = new Button("I've clicked the link");
        verified.getStyleClass().setAll("filled-button");
        verified.setMaxWidth(Double.MAX_VALUE);
        verified.setOnAction(e -> background(() -> {
            if (!Account.checkVerified()) throw new java.io.IOException("Not verified yet. Click the link in the email first.");
        }, this::afterSignIn));
        Button resend = Ui.textButton("Send the email again", () -> background(Account::resendVerification,
                error -> Ui.toast(error == null ? "Sent. Check your inbox (and spam)." : error)));
        Button other = Ui.textButton("Use another account", () -> {
            Account.signOut(true);
            refresh();
        });
        p.getChildren().addAll(tile(Icons.INFO, "tile_orange", 72), gap(20), Ui.label("Check your inbox", 24), gap(8), text,
                gap(28), verified, gap(4), resend, other);
        return p;
    }

    private Node accountPanel() {
        VBox p = new VBox();
        p.setPadding(new Insets(12, 0, 24, 0));
        StackPane avatar = new StackPane(tile(Icons.PERSON, "tile_purple", 84));
        avatar.setMaxSize(84, 84);
        Label name = Ui.label(Account.email(), 24);
        name.setMaxWidth(Double.MAX_VALUE);
        name.setAlignment(Pos.CENTER);
        Label handle = Ui.label("", 15, "secondary");
        handle.setMaxWidth(Double.MAX_VALUE);
        handle.setAlignment(Pos.CENTER);
        Label username = Ui.label("", 13, "secondary");

        VBox sync = card(
                row(Icons.CLOUD, "tile_blue", "Notes", syncStatus, Ui.label("Sync now", 14, "medium"), () -> {
                    NoteSync.schedule();
                    Ui.toast("Syncing…");
                }),
                row(Icons.PHOTO, "tile_green", "Photos and recordings", driveStatus, chevron(), this::driveOptions));
        Label deleteTitle = Ui.label("Delete account", 16);
        deleteTitle.setStyle(deleteTitle.getStyle() + "-fx-text-fill: " + Theme.ref("destructive") + ";");
        VBox profileCard = card(
                row(Icons.PERSON, "tile_orange", "Username", username, chevron(), () -> askUsername(false)),
                row(Icons.ARROW_BACK, "tile_gray", "Sign out", Ui.label(Account.email(), 13, "secondary"), null, this::confirmSignOut),
                rowWithTitle(Icons.DELETE, "destructive", deleteTitle, Ui.label("Removes everything stored online", 13, "secondary"),
                        null, this::confirmDelete));
        Label footer = Ui.label("Your notes are stored in the Notes+ cloud and only your account can open them. "
                + "Photos and recordings stay in your own Google Drive, in a Notes-plus folder.", 13, "secondary");
        footer.getStyleClass().add("settings-footer");
        footer.setWrapText(true);

        p.getChildren().addAll(avatar, gap(14), name, gap(2), handle, header("Sync"), sync, header("Profile"), profileCard, footer);
        VBox.setMargin(sync, new Insets(0, 16, 0, 16));
        VBox.setMargin(profileCard, new Insets(0, 16, 0, 16));
        p.setAlignment(Pos.TOP_CENTER);

        showSyncStatus();
        showDriveStatus();
        loadProfile(pr -> {
            name.setText(pr.displayName == null || pr.displayName.isEmpty()
                    ? (pr.username != null ? pr.username : pr.email) : pr.displayName);
            handle.setText(pr.username == null ? "" : "@" + pr.username);
            username.setText(pr.username == null ? "Choose one so people can find you" : "@" + pr.username);
            // A custom photo too (kept encrypted on Drive), not only Google's.
            ChatMedia.loadAvatar(pr.photoJson, img -> {
                if (img == null) return;
                ImageView view = new ImageView(img);
                view.setFitWidth(84);
                view.setFitHeight(84);
                view.setClip(new Circle(42, 42, 42));
                Runnable show = () -> {
                    if (!img.isError()) avatar.getChildren().setAll(view);
                };
                if (img.getProgress() >= 1) show.run();
                else img.progressProperty().addListener((o, a, b) -> {
                    if (b.doubleValue() >= 1) show.run();
                });
            });
        });
        return p;
    }

    private void loadProfile(Consumer<Account.Profile> then) {
        Account.BG.execute(() -> {
            Account.Profile p;
            try {
                p = Account.loadProfile();
            } catch (Exception e) {
                p = new Account.Profile();
                p.email = Account.email();
            }
            Account.Profile result = p;
            Platform.runLater(() -> {
                profile = result;
                then.accept(result);
            });
        });
    }

    private void showSyncStatus() {
        String uid = Account.uid();
        if (uid == null) return;
        NoteStore.load(s -> s.countDirty(uid), waiting -> {
            long last = NoteSync.lastSynced(uid);
            syncStatus.setText(NoteSync.isRunning() ? "Syncing…"
                    : (last == 0 ? "Not synced yet" : "Synced " + ago(last)) + (waiting > 0 ? " · " + waiting + " waiting" : ""));
        });
    }

    private void showDriveStatus() {
        String email = Account.driveEmail();
        String error = NoteSync.driveError();
        driveStatus.setText(email == null ? "Not connected: they stay on this computer"
                : error != null ? error + " · click to fix" : "Google Drive · " + email);
    }

    private static String ago(long millis) {
        long minutes = (System.currentTimeMillis() - millis) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    // ================================================================ sign in

    private void toggleMode() {
        creating = !creating;
        emailButton.setText(creating ? "Create account" : "Sign in");
        toggleMode.setText(creating ? "Have an account? Sign in" : "New here? Create an account");
    }

    private void emailSubmit() {
        String e = email.text(), pw = password.input.getText();
        if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            Ui.toast("Enter a valid email");
            return;
        }
        if (pw.length() < 6) {
            Ui.toast("The password needs at least 6 characters");
            return;
        }
        background(() -> {
            if (creating) Account.signUp(e, pw);
            else Account.signIn(e, pw);
        }, this::afterSignIn);
    }

    private void signInWithGoogle() {
        if (GoogleAuth.client() == null) {
            Ui.dialog().title("Google sign-in isn't set up yet")
                    .message("This computer needs the app's Google sign-in key: an OAuth client of type "
                            + "\"Desktop app\" from the Notes+ Firebase project, saved as oauth-client.json in "
                            + Storage.root() + ". Until then, sign in with your email.")
                    .positive("OK", null).width(440).show();
            return;
        }
        Ui.Modal waiting = browserWait("Choose your Google account in the browser window that just opened, then come back here.", null);
        background(Account::signInWithGoogle, error -> {
            GoogleAuth.setUrlListener(null);
            waiting.dismiss();
            NotesApp.stage().toFront();
            afterSignIn(error);
        });
    }

    /**
     * "Continue in your browser", with ways back if the window didn't open or was closed: open
     * it again, copy the link, or select it by hand from the field. Shared with the vault's
     * Drive connection. {@code onCancel} runs after the sign-in is called off.
     */
    static Ui.Modal browserWait(String message, Runnable onCancel) {
        javafx.scene.control.TextField link = new javafx.scene.control.TextField();
        link.setEditable(false);
        link.setPromptText("The sign-in link appears here");
        link.setVisible(false);
        link.setManaged(false);

        java.util.function.Consumer<String> urlUpdater = url -> {
            if (url != null && !url.isEmpty()) {
                link.setText(url);
                link.setVisible(true);
                link.setManaged(true);
            }
        };
        GoogleAuth.setUrlListener(urlUpdater);
        String currentUrl = GoogleAuth.pendingUrl();
        if (currentUrl != null && !currentUrl.isEmpty()) {
            urlUpdater.accept(currentUrl);
        }

        javafx.scene.control.Button again = Ui.textButton("Open browser again", () -> {
            String url = GoogleAuth.pendingUrl();
            if (url == null && !link.getText().isEmpty()) url = link.getText();
            if (url != null) NotesApp.openInBrowser(url);
            else Ui.toast("Still preparing the link; try again in a moment");
        });
        javafx.scene.control.Button copy = Ui.textButton("Copy link", () -> {
            String url = GoogleAuth.pendingUrl();
            if (url == null && !link.getText().isEmpty()) url = link.getText();
            if (url == null) {
                Ui.toast("Still preparing the link; try again in a moment");
                return;
            }
            boolean copied = false;
            try {
                javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
                c.putString(url);
                copied = javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            } catch (Exception ignored) {
                // AWT below.
            }
            try {
                // Windows sometimes refuses JavaFX's clipboard while another window holds it.
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(url), null);
                copied = true;
            } catch (Exception ignored) {
                // The field below still shows it.
            }
            // Shown either way, selected, so Ctrl+C works even if both clipboards refused.
            link.setText(url);
            link.setVisible(true);
            link.setManaged(true);
            link.requestFocus();
            link.selectAll();
            Ui.toast(copied ? "Link copied. Paste it into your browser." : "Select the link below and press Ctrl+C");
        });
        javafx.scene.layout.HBox actions = new javafx.scene.layout.HBox(4, again, copy);
        javafx.scene.layout.VBox.setMargin(actions, new javafx.geometry.Insets(4, 0, 0, -12));
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(6, actions, link);
        return Ui.dialog().title("Continue in your browser").message(message + " Nothing opened, or closed it by mistake?")
                .content(box).negative("Cancel", () -> {
                    GoogleAuth.setUrlListener(null);
                    GoogleAuth.cancel();
                    if (onCancel != null) onCancel.run();
                }).width(460).show();
    }

    private void forgotPassword() {
        Ui.prompt("Reset password", "We'll email you a link to choose a new one.", "Email", email.text(), "Send link",
                address -> background(() -> Account.resetPassword(address),
                        error -> Ui.toast(error == null ? "Sent. Check your inbox." : error)), null, null, null);
    }

    /** Every sign-in path ends here: then the questions a new account needs answered. */
    private void afterSignIn(String error) {
        refresh();
        if (error != null) {
            if (!error.isEmpty()) Ui.toast(error);
            return;
        }
        String uid = Account.uid();
        if (uid == null) return; // Waiting for the email link; the verify panel says so.
        NoteSync.startLive();
        NoteStore.load(s -> s.countOutside(uid), outside -> {
            if (outside > 0) offerAdopt(uid, outside);
            else afterAdopt();
        });
    }

    private void offerAdopt(String uid, int count) {
        Ui.dialog().title("Add your notes to this account?")
                .message("You have " + count + (count == 1 ? " note" : " notes")
                        + " on this computer. Add them to your account so they're backed up and on your other devices.")
                .positive("Add them", () -> {
                    NoteStore.write(s -> {
                        s.adopt(uid);
                        NoteSync.schedule();
                    });
                    afterAdopt();
                })
                .negative("Keep on this computer only", this::afterAdopt)
                .width(440).show();
    }

    private void afterAdopt() {
        NoteSync.schedule();
        loadProfile(p -> {
            if (p.username == null) askUsername(true);
            else if (Account.driveEmail() == null) offerDrive();
        });
    }

    // =============================================================== username

    private void askUsername(boolean onboarding) {
        String current = profile == null ? null : profile.username;
        Ui.prompt(current == null ? "Choose a username" : "Change username",
                "3–20 letters, numbers, dots or underscores. It's how people will find you; you never sign in with it.",
                "username", current, "Save", raw -> {
                    String name = Account.normaliseUsername(raw);
                    if (name == null) {
                        Ui.toast("Use 3–20 letters, numbers, . or _");
                        askUsername(onboarding);
                        return;
                    }
                    background(() -> {
                        if (!Account.isUsernameFree(name)) throw new java.io.IOException("@" + name + " is taken. Try another.");
                        Account.claimUsername(name, current);
                    }, error -> {
                        if (error != null) {
                            Ui.toast(error);
                            askUsername(onboarding);
                            return;
                        }
                        refresh();
                        if (onboarding && Account.driveEmail() == null) offerDrive();
                    });
                }, () -> {
                    if (onboarding && Account.driveEmail() == null) offerDrive();
                }, null, null);
    }

    // ================================================================== Drive

    private void offerDrive() {
        Ui.dialog().title("Back up photos and recordings?")
                .message("Photos, videos and recordings in your notes go to your own Google Drive, so only you can ever "
                        + "open them. Notes+ can only see the files it puts there.")
                .positive("Connect Google Drive", this::connectDrive)
                .negative("Not now", null).width(440).show();
    }

    private void driveOptions() {
        String email = Account.driveEmail();
        if (email == null || NoteSync.driveError() != null) {
            connectDrive();
            return;
        }
        Ui.dialog().title("Google Drive")
                .message("Photos and recordings sync through " + email + ".")
                .positive("OK", null)
                .negative("Disconnect", () -> {
                    Account.disconnectDrive();
                    showDriveStatus();
                }).show();
    }

    private void connectDrive() {
        if (GoogleAuth.client() == null) {
            signInWithGoogle(); // Explains the missing setup.
            return;
        }
        Ui.Modal waiting = browserWait("Allow Notes+ to keep its files in your Google Drive, then come back here.", null);
        background(Account::connectDrive, error -> {
            GoogleAuth.setUrlListener(null);
            waiting.dismiss();
            NotesApp.stage().toFront();
            if (error != null && !error.isEmpty()) Ui.toast(error);
            showDriveStatus();
        });
    }

    // ======================================================= sign out / delete

    private void confirmSignOut() {
        String uid = Account.uid();
        NoteStore.load(s -> uid == null ? 0 : s.countDirty(uid), waiting -> {
            Ui.DialogBuilder d = Ui.dialog().title("Sign out?").width(440);
            if (waiting > 0) {
                // Removing them now would lose edits the account has never seen.
                d.message(waiting + (waiting == 1 ? " note hasn't" : " notes haven't")
                                + " synced yet, so your notes will stay on this computer.")
                        .positive("Sign out", () -> signOut(true));
            } else {
                d.message("Your notes are safe in your account. Keep a copy on this computer too?")
                        .positive("Keep on this computer", () -> signOut(true))
                        .neutral("Remove from computer", () -> signOut(false));
            }
            d.negative("Cancel", null).show();
        });
    }

    private void signOut(boolean keep) {
        background(() -> NoteStore.IO.submit(() -> Account.signOut(keep)).get(), error -> {
            profile = null;
            refresh();
            Ui.toast("Signed out");
        });
    }

    private void confirmDelete() {
        Ui.dialog().title("Delete your account?")
                .message("This removes your notes from the cloud, your username, and the photos and recordings Notes+ put "
                        + "in your Google Drive. Notes on this computer stay here. It can't be undone.")
                .positive("Delete", () -> {
                    if (Account.isGoogle()) deleteAccount(null);
                    else askPassword();
                })
                .negative("Cancel", null).width(440).show();
    }

    private void askPassword() {
        Ui.Field field = new Ui.Field("Password", null, true);
        Ui.Modal m = Ui.dialog().title("Confirm it's you").content(field).width(400)
                .positive("Delete account", () -> deleteAccount(field.input.getText()))
                .negative("Cancel", null).show();
        field.input.setOnAction(e -> {
            m.dismiss();
            deleteAccount(field.input.getText());
        });
        Platform.runLater(field.input::requestFocus);
    }

    private void deleteAccount(String pw) {
        background(() -> Account.deleteAccount(pw), error -> {
            if (error != null) {
                if (!error.isEmpty()) Ui.toast(error);
                return;
            }
            profile = null;
            refresh();
            Ui.toast("Account deleted");
        });
    }

    // ================================================================ helpers

    private static StackPane tile(Icons.Icon icon, String color, double size) {
        StackPane t = new StackPane(Ui.icon(icon, size * 0.5, "white"));
        t.setStyle("-fx-background-color: " + Theme.ref(color) + "; -fx-background-radius: " + (size > 50 ? size / 2 : 8) + ";");
        t.setMinSize(size, size);
        t.setMaxSize(size, size);
        return t;
    }

    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }

    private static Label header(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("settings-header");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private static VBox card(Node... rows) {
        VBox card = new VBox();
        card.getStyleClass().add("settings-card");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                Region divider = new Region();
                divider.getStyleClass().add("settings-divider");
                VBox.setMargin(divider, new Insets(0, 0, 0, 62));
                card.getChildren().add(divider);
            }
            card.getChildren().add(rows[i]);
        }
        return card;
    }

    private static Node chevron() {
        return Ui.icon(Icons.CHEVRON_RIGHT, 20, "text_secondary");
    }

    private static HBox row(Icons.Icon icon, String color, String title, Label subtitle, Node end, Runnable action) {
        return rowWithTitle(icon, color, Ui.label(title, 16), subtitle, end, action);
    }

    private static HBox rowWithTitle(Icons.Icon icon, String color, Label title, Label subtitle, Node end, Runnable action) {
        StackPane tile = new StackPane(Ui.icon(icon, 20, "white"));
        tile.setStyle("-fx-background-color: " + Theme.ref(color) + "; -fx-background-radius: 8;");
        tile.setMinSize(30, 30);
        tile.setMaxSize(30, 30);
        subtitle.setWrapText(true);
        VBox texts = new VBox(1, title, subtitle);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(tile, texts);
        if (end != null) row.getChildren().add(end);
        row.getStyleClass().add("settings-row");
        row.setOnMouseClicked(e -> action.run());
        return row;
    }
}
