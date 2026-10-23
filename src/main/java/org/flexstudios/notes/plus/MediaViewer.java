package org.flexstudios.notes.plus;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.nio.file.Path;

/**
 * Full-window view of one photo or video from a journal entry: the phone's PhotoView and
 * ExoPlayer dialogs. Always dark. Click a photo (or press Escape) to close; the wheel zooms
 * it. A video plays at once, with play/pause and a seek bar.
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
            if (picture != null) view.setImage(picture);
            else Images.load(file, 4096, view::setImage);
            stage.getChildren().add(view);
            view.setOnMouseClicked(e -> {
                if (e.isStillSincePress()) close.run();
            });
            stage.setOnScroll(e -> {
                double s = Math.max(1, Math.min(6, view.getScaleX() * Math.pow(1.0018, e.getDeltaY())));
                view.setScaleX(s);
                view.setScaleY(s);
                if (s == 1) {
                    view.setTranslateX(0);
                    view.setTranslateY(0);
                }
            });
            double[] last = new double[2];
            view.setOnMousePressed(e -> {
                last[0] = e.getSceneX();
                last[1] = e.getSceneY();
            });
            view.setOnMouseDragged(e -> {
                if (view.getScaleX() <= 1) return;
                view.setTranslateX(view.getTranslateX() + e.getSceneX() - last[0]);
                view.setTranslateY(view.getTranslateY() + e.getSceneY() - last[1]);
                last[0] = e.getSceneX();
                last[1] = e.getSceneY();
            });
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
