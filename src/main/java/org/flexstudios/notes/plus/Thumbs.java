package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The vault's thumbnails, the phone's ThumbnailStore: 512 px, upright, made once per item and
 * kept encrypted in {@code vault/thumbs} (never a plaintext copy on disk), plus a memory cache
 * for scrolling. Photos are decoded in memory; a video's frame is grabbed through the player
 * from a short-lived playback copy ({@link VaultVideo}), which also tells its length.
 */
final class Thumbs {

    private Thumbs() {}

    static final int SIZE = 512;

    private static final ExecutorService WORK = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "vault-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final Map<String, Image> MEMORY = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > 600;
        }
    };
    private static final Map<String, Long> DURATIONS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Image>> PENDING = new ConcurrentHashMap<>();

    private static Path dir() {
        Path d = VaultCrypto.dir().resolve("thumbs");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    static synchronized Image peek(String name) {
        return MEMORY.get(name);
    }

    static long peekDuration(String name) {
        return DURATIONS.getOrDefault(name, 0L);
    }

    /** The item's thumbnail, on the FX thread (null if it can't be made). */
    static void load(VaultStore.Item item, Consumer<Image> then) {
        Image hit = peek(item.fileName());
        if (hit != null) {
            then.accept(hit);
            return;
        }
        PENDING.computeIfAbsent(item.fileName(), n -> CompletableFuture.supplyAsync(() -> make(item), WORK))
                .whenComplete((img, err) -> {
                    PENDING.remove(item.fileName());
                    Platform.runLater(() -> then.accept(err == null ? img : null));
                });
    }

    static synchronized void forget(String name) {
        MEMORY.remove(name);
        try {
            Files.deleteIfExists(dir().resolve(name));
        } catch (IOException ignored) {
            // A stray thumbnail is harmless and encrypted.
        }
    }

    /** The vault closed: nothing decrypted stays in memory. */
    static synchronized void clear() {
        MEMORY.clear();
    }

    private static Image make(VaultStore.Item item) {
        if (!VaultCrypto.isUnlocked()) return null;
        Path cached = dir().resolve(item.fileName());
        try {
            Image img = null;
            if (Files.exists(cached)) {
                img = new Image(new ByteArrayInputStream(VaultCrypto.readAll(cached)));
                if (img.isError()) img = null;
            }
            if (img == null) {
                img = item.video() ? videoFrame(item) : photo(item);
                if (img != null) store(cached, img);
            }
            if (img != null) {
                synchronized (Thumbs.class) {
                    MEMORY.put(item.fileName(), img);
                }
            }
            return img;
        } catch (Exception e) {
            System.getLogger("Vault").log(System.Logger.Level.DEBUG, "No thumbnail for " + item.fileName(), e);
            return null;
        }
    }

    private static Image photo(VaultStore.Item item) throws IOException {
        byte[] bytes = VaultCrypto.readAll(VaultCrypto.mediaDir().resolve(item.fileName()));
        Image img = new Image(new ByteArrayInputStream(bytes), SIZE, SIZE, true, true);
        if (img.isError()) return null;
        return Images.upright(img, Images.orientation(new ByteArrayInputStream(bytes)));
    }

    /** A frame a second in (or a third of the way, if shorter), through the player. */
    private static Image videoFrame(VaultStore.Item item) throws Exception {
        CompletableFuture<Image> frame = new CompletableFuture<>();
        Path plain = VaultVideo.open(item.fileName());
        frame.whenComplete((img, err) -> VaultVideo.close(item.fileName()));
        Platform.runLater(() -> {
            try {
                MediaPlayer p = new MediaPlayer(new Media(plain.toUri().toString()));
                MediaView view = new MediaView(p);
                view.setPreserveRatio(true);
                view.setFitWidth(SIZE);
                view.setFitHeight(SIZE);
                // Frames are only drawn in a window on screen: a holder in ours, underneath the
                // (opaque) screens, so it is rendered but never seen.
                Group holder = new Group(view);
                holder.setManaged(false);
                holder.setMouseTransparent(true);
                javafx.scene.layout.Pane under = Ui.layer == null ? null : (javafx.scene.layout.Pane) Ui.layer.getParent();
                if (under != null) under.getChildren().add(0, holder);
                else new Scene(holder);
                frame.whenComplete((img, err) -> Platform.runLater(() -> {
                    if (under != null) under.getChildren().remove(holder);
                }));
                p.setMute(true);
                p.setOnError(() -> {
                    p.dispose();
                    frame.complete(null);
                });
                p.setOnReady(() -> {
                    Duration total = p.getTotalDuration();
                    if (total != null && !total.isUnknown()) {
                        long ms = (long) total.toMillis();
                        DURATIONS.put(item.fileName(), ms);
                        if (item.durationMs() == 0) VaultStore.write(s -> s.setDuration(item.fileName(), ms));
                    }
                    double at = total == null || total.isUnknown() ? 0 : Math.min(1000, total.toMillis() / 3);
                    p.seek(Duration.millis(at));
                    p.play();
                    // Polled each frame: once the picture has moved past the mark, take it.
                    new javafx.animation.AnimationTimer() {
                        private int frames;

                        @Override
                        public void handle(long now) {
                            if (frame.isDone()) {
                                stop();
                                return;
                            }
                            if (p.getCurrentTime().toMillis() < at + 60 && ++frames < 240) return;
                            stop();
                            p.pause();
                            SnapshotParameters params = new SnapshotParameters();
                            params.setFill(Color.BLACK);
                            Image shot = view.snapshot(params, null);
                            p.dispose();
                            frame.complete(shot);
                        }
                    }.start();
                });
            } catch (Exception e) {
                frame.complete(null);
            }
        });
        try {
            return frame.get(8, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return null;
        }
    }

    private static void store(Path target, Image img) {
        try {
            BufferedImage argb = SwingFXUtils.fromFXImage(img, null);
            BufferedImage rgb = new BufferedImage(argb.getWidth(), argb.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(argb, 0, 0, null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", jpeg);
            try (OutputStream out = VaultCrypto.write(target.getParent(), target.getFileName().toString())) {
                out.write(jpeg.toByteArray());
            }
        } catch (Exception e) {
            System.getLogger("Vault").log(System.Logger.Level.DEBUG, "Could not keep a thumbnail", e);
        }
    }
}
