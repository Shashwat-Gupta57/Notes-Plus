package org.flexstudios.notes.plus;

import javafx.animation.FadeTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Full-window view of one photo or video from a note or journal entry: PhotoView and
 * MediaPlayer dialogs. Always dark. Click the close button or Escape to close; the wheel zooms
 * it; drag pans when zoomed; right-click copies or saves the picture.
 */
final class MediaViewer {

    private MediaViewer() {}

    static void open(Path file, boolean video) {
        open(file, null, video, null);
    }

    /**
     * {@code picture}: a photo already decoded in memory (a chat photo is never written out
     * decrypted), shown instead of reading {@code file}. {@code onClosed} runs once it's gone.
     */
    static Ui.Modal open(Path file, javafx.scene.image.Image picture, boolean video, Runnable onClosed) {
        StackPane stage = new StackPane();
        stage.setStyle("-fx-background-color: black;");
        MediaPlayer[] player = new MediaPlayer[1];
        Ui.Modal[] modal = new Ui.Modal[1];
        Runnable close = () -> modal[0].cancel();

        if (video) {
            MediaPlayer p;
            try {
                p = new MediaPlayer(new Media(file.toUri().toString()));
            } catch (Exception e) {
                Ui.toast("This video can't be played here");
                if (onClosed != null) onClosed.run();
                return null;
            }
            player[0] = p;
            MediaView view = new MediaView(p);
            view.setPreserveRatio(true);
            view.fitWidthProperty().bind(stage.widthProperty());
            view.fitHeightProperty().bind(stage.heightProperty().subtract(64));
            StackPane.setAlignment(view, Pos.CENTER);
            stage.getChildren().add(view);
            stage.getChildren().add(controls(p));
            p.setOnError(() -> Ui.toast("This video can't be played here"));
            p.setOnEndOfMedia(() -> {
                p.pause();
                p.seek(Duration.ZERO);
            });
            view.setOnMouseClicked(e -> toggle(p));
            p.play();
        } else {
            ImageView view = new ImageView();
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.fitWidthProperty().bind(stage.widthProperty());
            view.fitHeightProperty().bind(stage.heightProperty());
            view.setScaleX(1.0);
            view.setScaleY(1.0);
            view.setTranslateX(0);
            view.setTranslateY(0);

            if (picture != null) view.setImage(picture);
            else Images.load(file, 4096, view::setImage);
            stage.getChildren().add(view);

            // Double click to toggle zoom (fit vs 2.5x)
            view.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    if (view.getScaleX() > 1.0) {
                        view.setScaleX(1.0);
                        view.setScaleY(1.0);
                        view.setTranslateX(0);
                        view.setTranslateY(0);
                    } else {
                        view.setScaleX(2.5);
                        view.setScaleY(2.5);
                    }
                }
            });

            // Zoom on mouse wheel centered
            stage.addEventFilter(ScrollEvent.SCROLL, e -> {
                double factor = Math.pow(1.002, e.getDeltaY());
                double oldScale = view.getScaleX();
                double newScale = Math.max(1.0, Math.min(8.0, oldScale * factor));
                view.setScaleX(newScale);
                view.setScaleY(newScale);
                if (newScale <= 1.0) {
                    view.setTranslateX(0);
                    view.setTranslateY(0);
                }
                e.consume();
            });

            // Pan image when zoomed
            double[] last = new double[2];
            view.setOnMousePressed(e -> {
                last[0] = e.getSceneX();
                last[1] = e.getSceneY();
            });
            view.setOnMouseDragged(e -> {
                if (view.getScaleX() <= 1.0) return;
                view.setTranslateX(view.getTranslateX() + e.getSceneX() - last[0]);
                view.setTranslateY(view.getTranslateY() + e.getSceneY() - last[1]);
                last[0] = e.getSceneX();
                last[1] = e.getSceneY();
            });

            // Dismiss when clicking empty letterboxed space on stage
            stage.setOnMouseClicked(e -> {
                if (e.getTarget() == stage && e.isStillSincePress() && e.getButton() == MouseButton.PRIMARY) {
                    close.run();
                }
            });

            // Context menu: Copy picture, Save picture as, Reset zoom
            ContextMenu menu = new ContextMenu();
            MenuItem copyItem = new MenuItem("Copy picture");
            copyItem.setOnAction(ev -> copyToClipboard(file, view.getImage()));
            MenuItem saveItem = new MenuItem("Save picture as…");
            saveItem.setOnAction(ev -> saveAs(stage, file, view.getImage()));
            MenuItem resetZoomItem = new MenuItem("Reset zoom");
            resetZoomItem.setOnAction(ev -> {
                view.setScaleX(1.0);
                view.setScaleY(1.0);
                view.setTranslateX(0);
                view.setTranslateY(0);
            });
            menu.getItems().addAll(copyItem, saveItem, resetZoomItem);
            view.setOnContextMenuRequested(e -> menu.show(view, e.getScreenX(), e.getScreenY()));

            // Top-right action bar: Copy, Save, Zoom in, Zoom out, Reset
            Button copyBtn = Ui.iconButton(Icons.SHARE, "white", "Copy picture", () -> copyToClipboard(file, view.getImage()));
            Button saveBtn = Ui.iconButton(Icons.SAVE, "white", "Save picture as…", () -> saveAs(stage, file, view.getImage()));
            Button zoomInBtn = Ui.iconButton(Icons.ADD, "white", "Zoom in", () -> {
                double s = Math.min(8.0, view.getScaleX() * 1.35);
                view.setScaleX(s);
                view.setScaleY(s);
            });
            Button zoomOutBtn = Ui.iconButton(Icons.CENTER, "white", "Reset zoom", () -> {
                view.setScaleX(1.0);
                view.setScaleY(1.0);
                view.setTranslateX(0);
                view.setTranslateY(0);
            });
            HBox topActions = new HBox(6, zoomInBtn, zoomOutBtn, copyBtn, saveBtn);
            topActions.setAlignment(Pos.CENTER_RIGHT);
            StackPane.setAlignment(topActions, Pos.TOP_RIGHT);
            StackPane.setMargin(topActions, new Insets(8));
            stage.getChildren().add(topActions);
        }

        Button x = Ui.iconButton(Icons.CLOSE, "white", "Close (Esc)", close);
        StackPane.setAlignment(x, Pos.TOP_LEFT);
        StackPane.setMargin(x, new Insets(8));
        stage.getChildren().add(x);

        modal[0] = Ui.present(stage, Pos.CENTER, false, () -> {
            if (player[0] != null) player[0].dispose();
            if (onClosed != null) onClosed.run();
        });
        modal[0].root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE && player[0] != null) {
                e.consume();
                toggle(player[0]);
            }
        });
        stage.setOpacity(0);
        FadeTransition in = new FadeTransition(Duration.millis(150), stage);
        in.setToValue(1);
        in.play();
        return modal[0];
    }

    private static void copyToClipboard(Path file, javafx.scene.image.Image img) {
        if (img == null && file == null) return;
        ClipboardContent content = new ClipboardContent();
        if (img != null) content.putImage(img);
        if (file != null && Files.exists(file)) {
            content.putFiles(List.of(file.toFile()));
        }
        Clipboard.getSystemClipboard().setContent(content);
        Ui.toast("Picture copied");
    }

    private static void saveAs(StackPane stage, Path file, javafx.scene.image.Image img) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save picture as");
        String name = file != null ? file.getFileName().toString() : "picture.png";
        chooser.setInitialFileName(name);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PNG image (*.png)", "*.png"),
                new FileChooser.ExtensionFilter("JPEG image (*.jpg)", "*.jpg")
        );
        File dest = chooser.showSaveDialog(stage.getScene() != null ? stage.getScene().getWindow() : null);
        if (dest == null) return;
        try {
            if (file != null && Files.exists(file)) {
                Files.copy(file, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Ui.toast("Saved to " + dest.getName());
            } else if (img != null) {
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", dest);
                Ui.toast("Saved to " + dest.getName());
            }
        } catch (Exception ex) {
            Ui.toast("Could not save picture");
        }
    }

    private static void toggle(MediaPlayer p) {
        if (p.getStatus() == MediaPlayer.Status.PLAYING) p.pause();
        else p.play();
    }

    /** Play/pause, the position and a seek bar along the bottom. */
    private static HBox controls(MediaPlayer p) {
        Button play = Ui.iconButton(Icons.PAUSE, "white", "Play / pause (Space)", () -> toggle(p));
        Ui.IconView glyph = (Ui.IconView) play.getGraphic();
        p.statusProperty().addListener((o, a, b) ->
                glyph.setIcon(b == MediaPlayer.Status.PLAYING ? Icons.PAUSE : Icons.PLAY_BIG));
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
        HBox bar = new HBox(12, play, seek, time);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 8));
        bar.setMaxHeight(64);
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        return bar;
    }
}
