package org.flexstudios.notes.plus;

import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A wallpaper behind the lock screen or a vault's gallery, as the phone draws them: a colour,
 * a picture (optionally blurred and dimmed), a looping muted video, or either from a link.
 * "DEFAULT" leaves the screen's own background.
 */
final class Backdrop extends StackPane {
    private MediaPlayer player;

    Backdrop(String type, String value, boolean blur, boolean dim) {
        setMouseTransparent(true);
        setMinSize(0, 0);
        if (type == null || value == null || "DEFAULT".equals(type)) return;
        try {
            if ("COLOR".equals(type)) {
                Color.web(value);
                setStyle("-fx-background-color: " + value + ";");
                return;
            }
            boolean link = value.startsWith("http://") || value.startsWith("https://");
            if (!link && !Files.exists(Path.of(value))) return;
            String uri = link ? value : Path.of(value).toUri().toString();
            boolean video = "VIDEO".equals(type) || ("URL".equals(type) && looksLikeVideo(value));
            if (video) {
                player = new MediaPlayer(new Media(uri));
                player.setMute(true);
                player.setCycleCount(MediaPlayer.INDEFINITE);
                MediaView view = new MediaView(player);
                view.setPreserveRatio(false);
                view.fitWidthProperty().bind(widthProperty());
                view.fitHeightProperty().bind(heightProperty());
                setStyle("-fx-background-color: black;");
                getChildren().add(view);
                player.play();
            } else {
                ImageView view = new ImageView(new Image(uri, true));
                view.setPreserveRatio(false);
                view.fitWidthProperty().bind(widthProperty());
                view.fitHeightProperty().bind(heightProperty());
                view.setViewport(null);
                if (blur) view.setEffect(new GaussianBlur(25));
                getChildren().add(cover(view));
            }
            if (dim) {
                Region shade = new Region();
                shade.setStyle("-fx-background-color: rgba(0,0,0,0.5);");
                getChildren().add(shade);
            }
        } catch (Exception e) {
            getChildren().clear();
            setStyle("");
        }
    }

    /** Centre-crops the picture to the screen instead of stretching it. */
    private StackPane cover(ImageView view) {
        StackPane box = new StackPane(view);
        view.fitWidthProperty().unbind();
        view.fitHeightProperty().unbind();
        view.setPreserveRatio(true);
        Runnable fit = () -> {
            Image img = view.getImage();
            if (img == null || img.getWidth() == 0 || getWidth() == 0) return;
            double scale = Math.max(getWidth() / img.getWidth(), getHeight() / img.getHeight());
            view.setFitWidth(img.getWidth() * scale);
        };
        view.imageProperty().get().progressProperty().addListener((o, a, b) -> fit.run());
        widthProperty().addListener((o, a, b) -> fit.run());
        heightProperty().addListener((o, a, b) -> fit.run());
        box.setMinSize(0, 0);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        box.setClip(clip);
        return box;
    }

    private static boolean looksLikeVideo(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains(".mp4") || u.contains(".m4v") || u.contains(".mov") || u.contains(".m3u8");
    }

    boolean isEmpty() {
        return getChildren().isEmpty() && (getStyle() == null || getStyle().isEmpty());
    }

    void stop() {
        if (player != null) player.dispose();
        player = null;
    }
}
