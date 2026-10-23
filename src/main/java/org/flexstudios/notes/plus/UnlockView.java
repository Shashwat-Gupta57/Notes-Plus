package org.flexstudios.notes.plus;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.List;

/**
 * The lock screen of the gallery vault, the phone's UnlockActivity: three pages (PIN,
 * password, pattern) to swipe between, opening on the main vault's kind. Whichever vault's
 * lock matches opens; a miss says only "Incorrect".
 *
 * Failures are stored, not counted in memory: after five, a lockout of 30 s that doubles with
 * every further miss, up to an hour.
 */
final class UnlockView extends Screen {

    private static final int FREE_ATTEMPTS = 5;
    private static final long FIRST_LOCKOUT_MS = 30_000, MAX_LOCKOUT_MS = 60 * 60_000;
    private static final String KEY_FAILURES = "unlock_failures", KEY_LOCKED_UNTIL = "unlock_locked_until";
    /** Which hidden space this lock screen opens; the screen itself looks the same for both. */
    static final String REALM_GALLERY = "", REALM_CHAT = "_chat";

    private final StackPane root = new StackPane();
    private final HBox strip = new HBox();
    private final Pane pager = new Pane(strip);
    private final LockInput[] inputs = new LockInput[3];
    private final Label error = Ui.label("", 14);
    private final Backdrop backdrop;
    private int page;
    private boolean checking;
    private Timeline lockoutTimer;
    private double dragStart = Double.NaN, dragOffset;
    private final String realm;

    UnlockView() {
        this(REALM_GALLERY);
    }

    UnlockView(String realm) {
        this.realm = realm;
        root.setStyle("-fx-background-color: " + Theme.ref("app_background") + ";");
        backdrop = new Backdrop(Prefs.get(Vault.KEY_LOCK_BG_TYPE), Prefs.get(Vault.KEY_LOCK_BG_VALUE),
                "true".equals(Prefs.get(Vault.KEY_LOCK_BG_BLUR)), "true".equals(Prefs.get(Vault.KEY_LOCK_BG_DIM)));

        String[] titles = {"Enter PIN", "Enter Password", "Draw Pattern"};
        for (int i = 0; i < 3; i++) {
            String type = LockInput.TYPES[i];
            inputs[i] = new LockInput(type, null, v -> attempt(type, v));
            strip.getChildren().add(page(titles[i], inputs[i]));
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(pager.widthProperty());
        clip.heightProperty().bind(pager.heightProperty());
        pager.setClip(clip);
        pager.widthProperty().addListener((o, a, b) -> layoutPages(false));
        pager.heightProperty().addListener((o, a, b) -> layoutPages(false));
        // Drag empty space sideways to turn the page, like the phone's swipe.
        pager.setOnMousePressed(e -> {
            if (e.getTarget() instanceof javafx.scene.control.TextInputControl || inPattern(e.getTarget())) return;
            dragStart = e.getSceneX();
        });
        pager.setOnMouseDragged(e -> {
            if (Double.isNaN(dragStart)) return;
            dragOffset = e.getSceneX() - dragStart;
            strip.setTranslateX(-page * pager.getWidth() + dragOffset);
        });
        pager.setOnMouseReleased(e -> {
            if (Double.isNaN(dragStart)) return;
            dragStart = Double.NaN;
            int next = Math.abs(dragOffset) > pager.getWidth() * 0.18 ? page + (dragOffset < 0 ? 1 : -1) : page;
            dragOffset = 0;
            go(Math.max(0, Math.min(2, next)));
        });

        error.setStyle("-fx-text-fill: " + Theme.ref("destructive") + "; -fx-font-size: 14px;");
        error.setVisible(false);
        Label hint = Ui.label("Swipe (or ← →) for PIN / Password / Pattern", 12, "secondary");
        hint.setOpacity(0.6);
        VBox bottom = new VBox(16, error, hint);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(0, 0, 32, 0));

        Button prev = Ui.iconButton(Icons.ARROW_BACK, "text_secondary", "Previous", () -> go(page - 1));
        Button next = Ui.iconButton(Icons.ARROW_BACK, "text_secondary", "Next", () -> go(page + 1));
        next.getGraphic().setRotate(180);
        prev.setOpacity(0.5);
        next.setOpacity(0.5);
        BorderPane content = new BorderPane(pager);
        content.setLeft(new StackPane(prev));
        content.setRight(new StackPane(next));
        content.setBottom(bottom);
        Button close = Ui.iconButton(Icons.CLOSE, "text_secondary", "Back to notes (Esc)", NotesApp::back);
        StackPane.setAlignment(close, Pos.TOP_LEFT);
        StackPane.setMargin(close, new Insets(8));
        root.getChildren().addAll(backdrop, content, close);

        // Opens on the main vault's kind of lock.
        java.util.function.Consumer<String> openOn = type -> {
            if (type == null) return;
            for (int i = 0; i < 3; i++) if (LockInput.TYPES[i].equals(type)) page = i;
            layoutPages(false);
            inputs[page].focus();
        };
        if (REALM_CHAT.equals(realm)) {
            ChatStore.load(s -> s.mainVault() == null ? null : s.mainVault().lockType, openOn);
        } else {
            VaultStore.load(s -> s.mainVault() == null ? null : s.mainVault().lockType, openOn);
        }
    }

    private static boolean inPattern(Object target) {
        for (Object n = target; n instanceof Node node; n = node.getParent()) if (node instanceof PatternView) return true;
        return false;
    }

    private VBox page(String title, LockInput input) {
        Label t = Ui.label(title, 20, "bold");
        StackPane frame = new StackPane(input);
        frame.setMinHeight(300);
        VBox page = new VBox(t, frame);
        VBox.setMargin(t, new Insets(0, 0, 32, 0));
        if (!LockInput.PATTERN.equals(input.type)) {
            Button unlock = new Button("Unlock");
            unlock.getStyleClass().setAll("yellow-button");
            unlock.setMaxWidth(360);
            unlock.setPrefWidth(360);
            unlock.setOnAction(e -> attempt(input.type, input.value()));
            VBox.setMargin(unlock, new Insets(32, 0, 0, 0));
            page.getChildren().add(unlock);
        }
        page.setAlignment(Pos.CENTER);
        page.setPadding(new Insets(24));
        return page;
    }

    private void layoutPages(boolean animate) {
        double w = pager.getWidth(), h = pager.getHeight();
        for (Node n : strip.getChildren()) {
            ((VBox) n).setMinSize(w, h);
            ((VBox) n).setPrefSize(w, h);
            ((VBox) n).setMaxSize(w, h);
        }
        double target = -page * w;
        if (!animate) {
            strip.setTranslateX(target);
            return;
        }
        new Timeline(new KeyFrame(Duration.millis(260),
                new javafx.animation.KeyValue(strip.translateXProperty(), target, Interpolator.SPLINE(0.2, 0.8, 0.2, 1)))).play();
    }

    private void go(int to) {
        if (to < 0 || to > 2) {
            layoutPages(true);
            return;
        }
        page = to;
        layoutPages(true);
        inputs[page].focus();
    }

    @Override
    Node view() {
        return root;
    }

    @Override
    void onResume() {
        if (lockoutRemaining() > 0) showLockout();
        inputs[page].focus();
    }

    @Override
    void onDestroy() {
        backdrop.stop();
        if (lockoutTimer != null) lockoutTimer.stop();
    }

    @Override
    void onKey(KeyEvent e) {
        boolean typing = e.getTarget() instanceof javafx.scene.control.TextInputControl t && !t.getText().isEmpty();
        if (e.getCode() == KeyCode.RIGHT && !typing) {
            e.consume();
            go(page + 1);
        } else if (e.getCode() == KeyCode.LEFT && !typing) {
            e.consume();
            go(page - 1);
        }
    }

    // ================================================================ attempts

    private void attempt(String type, String value) {
        if (checking || value == null || value.isEmpty()) return;
        if (lockoutRemaining() > 0) {
            showLockout();
            return;
        }
        checking = true;
        if (REALM_CHAT.equals(realm)) {
            ChatStore.load(s -> ChatVaults.find(type, value), match -> {
                checking = false;
                if (match == null) {
                    failed();
                    inputs[page].clear();
                    return;
                }
                Prefs.put(KEY_FAILURES, null);
                Prefs.put(KEY_LOCKED_UNTIL, null);
                ChatSpace.opened(match.id);
                NotesApp.replace(new ChatHomeView(match.id));
            });
            return;
        }
        // Locks are hashed (PBKDF2): checking takes a moment, off the FX thread.
        VaultStore.load(s -> {
            List<VaultStore.Vault> vaults = s.vaults();
            for (VaultStore.Vault v : vaults) {
                if (type.equals(v.lockType) && VaultCrypto.matches(v.lockHash, type, value)
                        && VaultCrypto.unlock(v.uuid, type, value)) {
                    if (v.main) ChatVaults.rememberInitialLock(type, value);
                    return v;
                }
            }
            return null;
        }, match -> {
            checking = false;
            if (match == null) {
                failed();
                inputs[page].clear();
                return;
            }
            Prefs.put(KEY_FAILURES, null);
            Prefs.put(KEY_LOCKED_UNTIL, null);
            Vault.opened(match.id);
            NotesApp.replace(new GalleryView(match.id));
        });
    }

    private void failed() {
        long failures = Prefs.getLong(KEY_FAILURES) + 1;
        Prefs.put(KEY_FAILURES, failures);
        if (failures >= FREE_ATTEMPTS) {
            long doublings = Math.min(failures - FREE_ATTEMPTS, 20);
            Prefs.put(KEY_LOCKED_UNTIL, System.currentTimeMillis() + Math.min(FIRST_LOCKOUT_MS << doublings, MAX_LOCKOUT_MS));
        }
        if (lockoutRemaining() > 0) {
            showLockout();
        } else {
            error.setText("Incorrect"); // Deliberately generic.
            error.setVisible(true);
        }
    }

    private static long lockoutRemaining() {
        long remaining = Prefs.getLong(KEY_LOCKED_UNTIL) - System.currentTimeMillis();
        // A clock moved backwards must not extend a lockout indefinitely.
        return Math.min(remaining, MAX_LOCKOUT_MS);
    }

    private void showLockout() {
        if (lockoutTimer != null) lockoutTimer.stop();
        error.setVisible(true);
        lockoutTimer = new Timeline(new KeyFrame(Duration.ZERO, e -> {
            long left = lockoutRemaining();
            if (left <= 0) {
                error.setVisible(false);
                lockoutTimer.stop();
            } else {
                error.setText("Try again in " + (left / 1000 + 1) + "s");
            }
        }), new KeyFrame(Duration.seconds(1)));
        lockoutTimer.setCycleCount(Timeline.INDEFINITE);
        lockoutTimer.play();
    }
}
