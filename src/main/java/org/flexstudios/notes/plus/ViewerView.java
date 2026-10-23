package org.flexstudios.notes.plus;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Full-window viewer over the items the gallery was showing, in its order: the phone's
 * MediaPagerActivity. Always dark.
 *
 * Click hides the bars; ← → (or the side arrows) move between items; the wheel zooms a photo
 * and dragging pans it; dragging an unzoomed photo down sends the viewer away. Space plays a
 * video, F favourites, Delete deletes (with Undo).
 */
final class ViewerView extends Screen {

    private static final int UNDO_MS = 4000;

    private final int vaultId;
    private final List<VaultStore.Item> items;
    private int index;
    private final StackPane root = new StackPane();
    private final StackPane stage = new StackPane();
    private final VBox topBar = new VBox(), bottomBar = new VBox();
    private final Label title = new Label(), subtitle = new Label();
    private final Ui.IconView heart = Ui.icon(Icons.HEART_EMPTY, 24, "white");
    private final Map<String, CompletableFuture<Image>> photos = new HashMap<>();
    private MediaPlayer player;
    private String playingFile;
    private final Button prev, next;
    private ImageView photo;
    private boolean chrome = true;
    private double pressX, pressY;
    private boolean dismissing;

    private VaultStore.Item pendingDelete;
    private int pendingIndex;

    ViewerView(int vaultId, List<VaultStore.Item> items, int start) {
        this.vaultId = vaultId;
        this.items = items;
        this.index = start;
        root.setStyle("-fx-background-color: black;");

        title.getStyleClass().add("viewer-title");
        subtitle.getStyleClass().add("viewer-subtitle");
        HBox top = new HBox(Ui.iconButton(Icons.ARROW_BACK, "white", "Back (Esc)", NotesApp::back),
                new VBox(title, subtitle), Ui.spacer(), Ui.iconButton(Icons.INFO, "white", "Details", this::showInfo));
        top.setAlignment(Pos.CENTER_LEFT);
        top.setSpacing(8);
        top.setPadding(new Insets(8));
        topBar.getChildren().add(top);
        topBar.getStyleClass().add("viewer-bar");
        topBar.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(topBar, Pos.TOP_CENTER);

        Button fav = Ui.iconButton(Icons.HEART_EMPTY, "white", "Favourite (F)", this::toggleFavourite);
        fav.setGraphic(heart);
        HBox actions = new HBox(
                grow(Ui.iconButton(Icons.SHARE, "white", "Share", this::share)),
                grow(fav),
                grow(Ui.iconButton(Icons.ALBUM, "white", "Album", this::chooseAlbum)),
                grow(Ui.iconButton(Icons.MOVE_TO_GALLERY, "white", "Move back to this computer", this::moveOut)),
                grow(Ui.iconButton(Icons.DELETE, "white", "Delete (Del)", this::delete)));
        actions.setPadding(new Insets(8, 8, 12, 8));
        actions.setMaxWidth(720);
        bottomBar.getChildren().add(actions);
        bottomBar.setAlignment(Pos.BOTTOM_CENTER);
        bottomBar.getStyleClass().addAll("viewer-bar", "bottom");
        bottomBar.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(bottomBar, Pos.BOTTOM_CENTER);

        prev = Ui.iconButton(Icons.ARROW_BACK, "white", "Previous (←)", () -> go(index - 1));
        next = Ui.iconButton(Icons.ARROW_BACK, "white", "Next (→)", () -> go(index + 1));
        next.getGraphic().setRotate(180);
        StackPane.setAlignment(prev, Pos.CENTER_LEFT);
        StackPane.setAlignment(next, Pos.CENTER_RIGHT);
        StackPane.setMargin(prev, new Insets(8));
        StackPane.setMargin(next, new Insets(8));

        root.getChildren().addAll(stage, topBar, bottomBar, prev, next);
        installDismiss();
        show();
    }

    private static Button grow(Button b) {
        b.setMaxWidth(Double.MAX_VALUE);
        b.setPrefHeight(56);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    @Override
    Node view() {
        return root;
    }

    @Override
    void onPause() {
        if (player != null) player.pause();
    }

    private void stopVideo() {
        if (player != null) player.dispose();
        player = null;
        if (playingFile != null) VaultVideo.close(playingFile);
        playingFile = null;
    }

    @Override
    void onDestroy() {
        stopVideo();
        commitPendingDelete();
        Ui.closeSnack(false);
    }

    @Override
    void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case LEFT -> go(index - 1);
            case RIGHT -> go(index + 1);
            case DELETE -> delete();
            case F -> toggleFavourite();
            case SPACE -> {
                if (player == null) return;
                if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
                else player.play();
            }
            default -> {
                return;
            }
        }
        e.consume();
    }

    private VaultStore.Item current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    // ================================================================ pages

    private void go(int to) {
        if (to < 0 || to >= items.size() || to == index) return;
        index = to;
        show();
    }

    private void show() {
        stopVideo();
        photo = null;
        VaultStore.Item item = current();
        if (item == null) {
            NotesApp.back();
            return;
        }
        Date date = new Date(item.dateTaken());
        title.setText(new GalleryView.Labeler(System.currentTimeMillis()).label(GalleryView.startOfDay(item.dateTaken())));
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        long ms = item.durationMs() > 0 ? item.durationMs() : Thumbs.peekDuration(item.fileName());
        subtitle.setText(item.video() && ms > 0 ? time + "  ·  " + NoteData.formatDuration(ms) : time);
        heart.setIcon(item.favourite() ? Icons.HEART_FILLED : Icons.HEART_EMPTY);

        if (item.video()) showVideo(item);
        else showPhoto(item);
        arrows();
        // Neighbours are decoded before the arrow lands.
        for (int n : new int[]{index - 1, index + 1}) {
            if (n >= 0 && n < items.size() && !items.get(n).video()) decode(items.get(n));
        }
        photos.keySet().removeIf(k -> items.stream().noneMatch(i -> i.fileName().equals(k))
                || Math.abs(indexOf(k) - index) > 1);
    }

    private int indexOf(String name) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).fileName().equals(name)) return i;
        return -1;
    }

    private CompletableFuture<Image> decode(VaultStore.Item item) {
        return photos.computeIfAbsent(item.fileName(), n -> CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = VaultCrypto.readAll(VaultCrypto.mediaDir().resolve(n));
                Image img = new Image(new ByteArrayInputStream(bytes), 4096, 4096, true, true);
                return img.isError() ? null : Images.upright(img, Images.orientation(new ByteArrayInputStream(bytes)));
            } catch (Exception e) {
                return null;
            }
        }));
    }

    private void showPhoto(VaultStore.Item item) {
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.fitWidthProperty().bind(root.widthProperty());
        view.fitHeightProperty().bind(root.heightProperty());
        Image cached = Thumbs.peek(item.fileName());
        if (cached != null) view.setImage(cached);
        photo = view;
        stage.getChildren().setAll(view);
        decode(item).thenAccept(img -> Platform.runLater(() -> {
            if (img != null && photo == view) view.setImage(img);
        }));
        stage.setOnScroll(e -> {
            if (photo == null) return;
            double s = Math.max(1, Math.min(6, photo.getScaleX() * Math.pow(1.0018, e.getDeltaY())));
            photo.setScaleX(s);
            photo.setScaleY(s);
            if (s == 1) {
                photo.setTranslateX(0);
                photo.setTranslateY(0);
            }
        });
        view.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress()) return;
            if (e.getClickCount() == 2) {
                double s = view.getScaleX() > 1 ? 1 : 2.5;
                ScaleTransition zoom = new ScaleTransition(Duration.millis(180), view);
                zoom.setToX(s);
                zoom.setToY(s);
                zoom.play();
                if (s == 1) {
                    view.setTranslateX(0);
                    view.setTranslateY(0);
                }
            } else {
                setChrome(!chrome);
            }
        });
    }

    /** Videos play from a temporary decrypted copy (see {@link VaultVideo}), made first. */
    private void showVideo(VaultStore.Item item) {
        stage.getChildren().setAll(Ui.label("Opening…", 14, "secondary"));
        CompletableFuture.supplyAsync(() -> {
            try {
                return VaultVideo.open(item.fileName());
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(file -> Platform.runLater(() -> {
            if (current() != item) {
                VaultVideo.close(item.fileName());
                return;
            }
            if (file == null) {
                stage.getChildren().setAll(Ui.label("This video can't be played here", 14, "secondary"));
                return;
            }
            play(item, file);
        }));
    }

    private void play(VaultStore.Item item, Path file) {
        MediaPlayer p;
        try {
            p = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (Exception e) {
            stage.getChildren().setAll(Ui.label("This video can't be played here", 14, "secondary"));
            return;
        }
        playingFile = item.fileName();
        player = p;
        MediaView view = new MediaView(p);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(root.widthProperty());
        view.fitHeightProperty().bind(root.heightProperty());
        view.setOnMouseClicked(e -> {
            if (e.isStillSincePress()) setChrome(!chrome);
        });
        Button play = Ui.iconButton(Icons.PAUSE, "white", "Play / pause (Space)", () -> {
            if (p.getStatus() == MediaPlayer.Status.PLAYING) p.pause();
            else p.play();
        });
        Ui.IconView glyph = (Ui.IconView) play.getGraphic();
        p.statusProperty().addListener((o, a, b) -> glyph.setIcon(b == MediaPlayer.Status.PLAYING ? Icons.PAUSE : Icons.PLAY_BIG));
        Slider seek = new Slider(0, 1, 0);
        HBox.setHgrow(seek, Priority.ALWAYS);
        Label time = new Label("0:00");
        time.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
        boolean[] dragging = {false};
        seek.valueChangingProperty().addListener((o, a, b) -> {
            dragging[0] = b;
            if (!b) p.seek(p.getTotalDuration().multiply(seek.getValue()));
        });
        seek.setOnMouseReleased(e -> p.seek(p.getTotalDuration().multiply(seek.getValue())));
        p.currentTimeProperty().addListener((o, a, now) -> {
            Duration total = p.getTotalDuration();
            if (total == null || total.isUnknown() || total.toMillis() <= 0) return;
            if (!dragging[0]) seek.setValue(now.toMillis() / total.toMillis());
            time.setText(NoteData.formatDuration((long) now.toMillis()) + " / " + NoteData.formatDuration((long) total.toMillis()));
        });
        p.setOnEndOfMedia(() -> {
            p.pause();
            p.seek(Duration.ZERO);
        });
        p.setOnError(() -> stage.getChildren().setAll(Ui.label("This video can't be played here", 14, "secondary")));
        HBox controls = new HBox(12, play, seek, time);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 16, 84, 8));
        controls.setMaxHeight(Region.USE_PREF_SIZE);
        controls.visibleProperty().bind(bottomBar.visibleProperty());
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        stage.getChildren().setAll(view, controls);
        stage.setOnScroll(null);
        p.play();
    }

    private void setChrome(boolean visible) {
        chrome = visible;
        for (Node bar : new Node[]{topBar, bottomBar}) {
            FadeTransition f = new FadeTransition(Duration.millis(200), bar);
            f.setToValue(visible ? 1 : 0);
            f.play();
            bar.setMouseTransparent(!visible);
        }
        arrows();
    }

    private void arrows() {
        prev.setVisible(chrome && index > 0);
        next.setVisible(chrome && index < items.size() - 1);
    }

    /**
     * Dragging an unzoomed photo carries it with the pointer (zoomed, it pans); let go far
     * enough down or up and the viewer closes, like the phone's swipe away.
     */
    private void installDismiss() {
        stage.setOnMousePressed(e -> {
            pressX = e.getSceneX();
            pressY = e.getSceneY();
            dismissing = false;
        });
        stage.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - pressX, dy = e.getSceneY() - pressY;
            if (photo != null && photo.getScaleX() > 1) {
                photo.setTranslateX(photo.getTranslateX() + dx);
                photo.setTranslateY(photo.getTranslateY() + dy);
                pressX = e.getSceneX();
                pressY = e.getSceneY();
                return;
            }
            if (!dismissing && Math.abs(dy) > 12 && Math.abs(dy) > Math.abs(dx) * 1.6) dismissing = true;
            if (!dismissing) return;
            double progress = Math.min(1, Math.abs(dy) / (root.getHeight() * 0.5));
            stage.setTranslateY(dy);
            stage.setScaleX(1 - 0.15 * progress);
            stage.setScaleY(1 - 0.15 * progress);
            root.setStyle("-fx-background-color: rgba(0,0,0," + (1 - progress) + ");");
        });
        stage.setOnMouseReleased(e -> {
            if (!dismissing) return;
            dismissing = false;
            double dy = e.getSceneY() - pressY;
            if (Math.abs(dy) > root.getHeight() * 0.18) {
                NotesApp.back();
                return;
            }
            stage.setTranslateY(0);
            stage.setScaleX(1);
            stage.setScaleY(1);
            root.setStyle("-fx-background-color: black;");
        });
    }

    // ================================================================ actions

    private void toggleFavourite() {
        VaultStore.Item item = current();
        if (item == null) return;
        boolean fav = !item.favourite();
        items.set(index, item.withFavourite(fav));
        VaultStore.write(s -> s.setFavourite(List.of(item.fileName()), fav));
        heart.setIcon(fav ? Icons.HEART_FILLED : Icons.HEART_EMPTY);
        ScaleTransition pop = new ScaleTransition(Duration.millis(110), heart);
        pop.setToX(1.25);
        pop.setToY(1.25);
        pop.setAutoReverse(true);
        pop.setCycleCount(2);
        pop.play();
    }

    private void share() {
        VaultStore.Item item = current();
        if (item == null) return;
        Ui.dialog().title("Share").items(List.of("Copy, to paste into another app", "Save a copy to a folder…"),
                List.of(() -> GalleryView.exportTo(List.of(item.fileName()), null), () -> {
                    javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                    Vault.expectExternal();
                    java.io.File dir = chooser.showDialog(NotesApp.stage());
                    if (dir != null) GalleryView.exportTo(List.of(item.fileName()), dir.toPath());
                })).show();
    }

    private void chooseAlbum() {
        VaultStore.Item item = current();
        if (item == null) return;
        VaultStore.load(s -> {
            VaultStore.Secret secret = s.secret(item.fileName());
            return new Object[]{s.albums(vaultId), secret == null ? null : secret.albumId};
        }, r -> {
            @SuppressWarnings("unchecked") List<VaultStore.Album> albums = (List<VaultStore.Album>) r[0];
            Integer currentAlbum = (Integer) r[1];
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add("+ New album");
            actions.add(() -> Ui.prompt("New album", "Album name", null, "Create",
                    name -> VaultStore.load(s -> s.insertAlbum(name, vaultId), a -> setAlbum(item, a))));
            for (VaultStore.Album a : albums) {
                labels.add(currentAlbum != null && currentAlbum == a.id ? a.name + "  ✓" : a.name);
                actions.add(() -> setAlbum(item, a));
            }
            if (currentAlbum != null) {
                labels.add("Remove from album");
                actions.add(() -> setAlbum(item, null));
            }
            Ui.dialog().title("Album").items(labels, actions).show();
        });
    }

    private void setAlbum(VaultStore.Item item, VaultStore.Album album) {
        VaultStore.write(s -> s.setAlbum(List.of(item.fileName()), album == null ? null : album.id));
        Ui.snackbar(album == null ? "Removed from album" : "Added to " + album.name, Ui.SHORT);
    }

    private void moveOut() {
        VaultStore.Item item = current();
        if (item == null) return;
        int position = index;
        VaultStore.load(s -> {
            try {
                VaultStore.Secret secret = s.secret(item.fileName());
                return secret == null ? null : VaultMedia.moveOut(secret);
            } catch (Exception e) {
                return null;
            }
        }, where -> {
            if (where == null) {
                Ui.toast("Couldn't move it back");
                return;
            }
            removePage(position, item);
            Ui.snackbar("Moved back to " + where.getParent(), Ui.LONG);
        });
    }

    /** Removes the page now; the file is erased only when the undo bar goes away. */
    private void delete() {
        VaultStore.Item item = current();
        if (item == null) return;
        commitPendingDelete();
        pendingDelete = item;
        pendingIndex = index;
        removePage(index, item);
        if (items.isEmpty()) return; // That was the last one; leaving commits it.
        Ui.snackbar("Deleted", Duration.millis(UNDO_MS), "Undo", () -> {
            if (pendingDelete != item) return;
            pendingDelete = null;
            int at = Math.min(pendingIndex, items.size());
            items.add(at, item);
            index = at;
            show();
        }, byAction -> {
            if (!byAction && pendingDelete == item) commitPendingDelete();
        });
    }

    private void commitPendingDelete() {
        VaultStore.Item item = pendingDelete;
        if (item == null) return;
        pendingDelete = null;
        VaultStore.write(s -> {
            VaultStore.Secret secret = s.secret(item.fileName());
            if (secret != null) VaultMedia.remove(secret);
        });
    }

    private void removePage(int position, VaultStore.Item item) {
        if (position < 0 || position >= items.size() || items.get(position) != item) return;
        items.remove(position);
        photos.remove(item.fileName());
        if (items.isEmpty()) {
            NotesApp.back();
            return;
        }
        index = Math.min(position, items.size() - 1);
        show();
    }

    private void showInfo() {
        VaultStore.Item item = current();
        if (item == null) return;
        VaultStore.load(s -> s.secret(item.fileName()), secret -> {
            if (secret == null) return;
            Path file = VaultCrypto.mediaDir().resolve(item.fileName());
            long size;
            try {
                size = Files.size(file);
            } catch (Exception e) {
                size = 0;
            }
            SimpleDateFormat full = new SimpleDateFormat("EEEE, d MMMM yyyy  ·  HH:mm", Locale.getDefault());
            StringBuilder info = new StringBuilder(item.video() ? "Video" : "Photo");
            long ms = item.durationMs() > 0 ? item.durationMs() : Thumbs.peekDuration(item.fileName());
            if (item.video() && ms > 0) info.append("  ·  ").append(NoteData.formatDuration(ms));
            info.append("  ·  ").append(bytes(size));
            info.append("\n\nTaken\n").append(full.format(new Date(secret.dateTaken)));
            info.append("\n\nAdded to vault\n").append(full.format(new Date(secret.dateAdded)));
            if (secret.originalPath != null) info.append("\n\nOriginally\n").append(secret.originalPath);
            Ui.dialog().title("Details").message(info.toString()).positive("Done", null).width(420).show();
        });
    }

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }
}
