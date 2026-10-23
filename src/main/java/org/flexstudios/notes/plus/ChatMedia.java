package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Photos, videos and voice notes in chat, and chat backgrounds: the phone's ChatMedia.
 *
 * A file is encrypted here with its own random key, uploaded to the sender's Google Drive, and
 * shared as "anyone with the link". The message carries the Drive id and the key; only the
 * members can read messages, so only they can turn the public file back into a picture.
 *
 * Only the encrypted copy is kept ({@link #cached}). Pictures decrypt into memory; videos and
 * voice notes into {@link #plainDir} while they play, swept whenever the chat space opens.
 */
final class ChatMedia {

    private ChatMedia() {}

    static final long MAX_BYTES = 100L * 1024 * 1024;
    /** keyId stamped in the header; each file has its own key, so it identifies nothing. */
    private static final String KEY_ID = "00000000000000c1";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a message (or background, or photo) says about its file. JSON locally and online. */
    static final class Meta {
        String id, key, mime, name, wave;
        long size, w, h, dur;
        boolean once;

        boolean isVideo() {
            return mime != null && mime.startsWith("video/");
        }

        boolean isAudio() {
            return mime != null && mime.startsWith("audio/");
        }

        float[] waveform() {
            if (wave == null || wave.isEmpty()) return new float[0];
            String[] parts = wave.split(",");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    out[i] = Float.parseFloat(parts[i]);
                } catch (NumberFormatException e) {
                    out[i] = 0;
                }
            }
            return out;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            if (id != null) o.put("id", id);
            o.put("key", key);
            o.put("mime", mime == null ? "application/octet-stream" : mime);
            if (name != null) o.put("name", name);
            o.put("size", size);
            if (w > 0) o.put("w", w);
            if (h > 0) o.put("h", h);
            if (dur > 0) o.put("dur", dur);
            if (wave != null) o.put("wave", wave);
            if (once) o.put("once", true);
            return o;
        }

        static Meta parse(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JSONObject o = new JSONObject(json);
                Meta m = new Meta();
                m.id = o.optString("id", null);
                m.key = o.optString("key", null);
                m.mime = o.optString("mime", "application/octet-stream");
                m.name = o.optString("name", null);
                m.size = o.optLong("size");
                m.w = o.optLong("w");
                m.h = o.optLong("h");
                m.dur = o.optLong("dur");
                m.wave = o.optString("wave", null);
                m.once = o.optBoolean("once", false);
                return m.key == null ? null : m;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ============================================================= sending

    /** Encrypts a picked file into the outbox under {@code tag}, ready to upload. Blocking. */
    static Meta prepare(Path source, String tag) throws IOException {
        long size = Files.size(source);
        if (size > MAX_BYTES) throw new IOException("Files up to 100 MB can be sent");
        Meta m = new Meta();
        m.mime = mimeOf(source.getFileName().toString());
        m.name = source.getFileName().toString();
        m.size = size;
        if (m.mime.startsWith("image/")) {
            try (InputStream in = Files.newInputStream(source)) {
                describeImage(in, Images.orientation(source), m);
            }
        }
        try (InputStream in = Files.newInputStream(source)) {
            encryptInto(in, tag, m);
        }
        return m;
    }

    /** A pasted picture (PNG bytes). Blocking. */
    static Meta prepareBytes(byte[] png, String name, String tag) throws IOException {
        Meta m = new Meta();
        m.mime = "image/png";
        m.name = name;
        m.size = png.length;
        describeImage(new ByteArrayInputStream(png), 1, m);
        encryptInto(new ByteArrayInputStream(png), tag, m);
        return m;
    }

    /** A voice note just recorded (WAV): encrypted with its length and waveform. Blocking. */
    static Meta prepareVoice(Path recording, long durationMs, float[] levels, String tag) throws IOException {
        Meta m = new Meta();
        m.mime = "audio/wav";
        m.size = Files.size(recording);
        m.dur = durationMs;
        StringBuilder wave = new StringBuilder();
        for (float level : levels) {
            if (wave.length() > 0) wave.append(',');
            wave.append(Math.round(Math.max(0, Math.min(1, level)) * 100) / 100f);
        }
        m.wave = wave.toString();
        try (InputStream in = Files.newInputStream(recording)) {
            encryptInto(in, tag, m);
        }
        return m;
    }

    private static void encryptInto(InputStream in, String tag, Meta m) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        m.key = Base64.getEncoder().encodeToString(key);
        Path target = cached(tag);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(part)) {
            try (OutputStream encrypted = Portable.encrypting(out, key, KEY_ID)) {
                in.transferTo(encrypted);
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        if (m.size == 0) m.size = Files.size(target);
    }

    private static void describeImage(InputStream in, int orientation, Meta m) {
        try {
            Image image = new Image(in);
            boolean turned = orientation >= 5;
            m.w = Math.round(turned ? image.getHeight() : image.getWidth());
            m.h = Math.round(turned ? image.getWidth() : image.getHeight());
        } catch (Exception ignored) {
            // No dimensions: the bubble picks a square.
        }
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }

    /** Uploads the encrypted file for {@code tag} and opens it to anyone with the link. */
    static String upload(Drive drive, String tag) throws IOException {
        Path file = cached(tag);
        if (!Files.exists(file)) throw new IOException("The file to send is gone");
        String folder = drive.folder("Shared", drive.folder(Drive.APP_FOLDER, "root"));
        String id = drive.putFile(file, tag + ".bin", "application/octet-stream", folder);
        drive.shareWithLink(id);
        return id;
    }

    // ============================================================== photos

    /** A profile photo: 512 px JPEG, encrypted, uploaded, opened by link; kept as {@link #photoTag}. Blocking. */
    static Meta uploadPhoto(Drive drive, Path source) throws Exception {
        BufferedImage original = ImageIO.read(source.toFile());
        if (original == null) throw new IOException("That isn't a picture");
        BufferedImage square = squareJpeg(original, Images.orientation(source), 512);
        Path jpeg = plainDir().resolve("photo_" + System.nanoTime() + ".jpg");
        ImageIO.write(square, "jpg", jpeg.toFile());
        String temp = "ptmp_" + System.nanoTime();
        try {
            Meta meta = prepare(jpeg, temp);
            meta.mime = "image/jpeg";
            meta.name = null;
            meta.id = upload(drive, temp);
            Files.move(cached(temp), cached(photoTag(meta.id)), StandardCopyOption.REPLACE_EXISTING);
            return meta;
        } finally {
            Files.deleteIfExists(jpeg);
            Files.deleteIfExists(cached(temp));
        }
    }

    /** Upright, centre-cropped to a square, at most {@code size} px, as RGB for JPEG. */
    private static BufferedImage squareJpeg(BufferedImage src, int orientation, int size) {
        int w = src.getWidth(), h = src.getHeight();
        int side = Math.min(w, h);
        int out = Math.min(size, side);
        BufferedImage dst = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        AffineTransform t = new AffineTransform();
        t.translate(out / 2.0, out / 2.0);
        switch (orientation) {
            case 3 -> t.rotate(Math.PI);
            case 6 -> t.rotate(Math.PI / 2);
            case 8 -> t.rotate(-Math.PI / 2);
            default -> { }
        }
        double scale = (double) out / side;
        t.scale(scale, scale);
        t.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, t, null);
        g.dispose();
        return dst;
    }

    static String photoTag(String driveId) {
        return "p_" + driveId;
    }

    private static final Map<String, Image> AVATARS = new ConcurrentHashMap<>();

    /**
     * A profile photo as an image: {"url":...} (a Google photo) or a custom one (downloaded
     * and decrypted). {@code then} gets null when there is none, on the FX thread.
     */
    static void loadAvatar(String json, Consumer<Image> then) {
        if (json == null) {
            then.accept(null);
            return;
        }
        Image cached = AVATARS.get(json);
        if (cached != null) {
            then.accept(cached);
            return;
        }
        String url = null;
        try {
            url = new JSONObject(json).optString("url", null);
        } catch (Exception ignored) {
            // Not a URL photo.
        }
        if (url != null) {
            Image image = new Image(url, 192, 192, true, true, true);
            AVATARS.put(json, image);
            then.accept(image);
            return;
        }
        Meta meta = Meta.parse(json);
        if (meta == null || meta.id == null) {
            then.accept(null);
            return;
        }
        ChatStore.IO.execute(() -> {
            Image image = null;
            try {
                image = new Image(new ByteArrayInputStream(decryptBytes(photoTag(meta.id), meta)), 192, 192, true, true);
                if (image.isError()) image = null;
            } catch (Exception e) {
                System.getLogger("Chat").log(System.Logger.Level.WARNING, "Couldn't show a profile photo", e);
            }
            if (image != null) AVATARS.put(json, image);
            Image shown = image;
            Platform.runLater(() -> then.accept(shown));
        });
    }

    // ============================================================= reading

    /** The encrypted copy here, downloaded from its public link if needed. Blocking. */
    static Path ensure(String tag, Meta m) throws IOException {
        Path file = cached(tag);
        if (Files.exists(file) && Files.size(file) > 0) return file;
        if (m.id == null) throw new IOException("Not uploaded yet");
        Path part = file.resolveSibling(file.getFileName() + ".part");
        String url = "https://drive.usercontent.google.com/download?id=" + URLEncoder.encode(m.id, StandardCharsets.UTF_8)
                + "&export=download&confirm=t";
        try {
            HttpResponse<InputStream> r = Firebase.HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) throw new IOException("Download failed (" + r.statusCode() + ")");
                String type = r.headers().firstValue("Content-Type").orElse("");
                if (type.startsWith("text/html")) throw new IOException("The file is no longer shared");
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            Files.deleteIfExists(part);
        }
        return file;
    }

    /** Whole file decrypted into memory: for pictures. Blocking. */
    static byte[] decryptBytes(String tag, Meta m) throws IOException {
        Path file = ensure(tag, m);
        try (InputStream in = Files.newInputStream(file)) {
            return Portable.decryptAll(in, keyOf(m));
        }
    }

    /** Decrypted into {@code target}. Blocking. */
    static void decryptTo(String tag, Meta m, Path target) throws IOException {
        Path file = ensure(tag, m);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(part)) {
            byte[] header = Portable.header(in);
            try (InputStream plain = Portable.decrypting(in, header, keyOf(m))) {
                plain.transferTo(out);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Decrypted into a temporary file, for the player. Delete it when done. Blocking. */
    static Path decryptToTemp(String tag, Meta m) throws IOException {
        String ext = m.isVideo() ? ".mp4" : "audio/wav".equals(m.mime) ? ".wav" : m.isAudio() ? ".m4a" : ".bin";
        Path plain = plainDir().resolve(tag.replaceAll("[^A-Za-z0-9_-]", "_") + ext);
        decryptTo(tag, m, plain);
        return plain;
    }

    private static byte[] keyOf(Meta m) {
        return Base64.getDecoder().decode(m.key);
    }

    // ============================================================= storage

    /** Encrypted copies, sent and received. Safe at rest: useless without the message. */
    static Path cached(String tag) {
        Path dir = Storage.root().resolve("chat_media");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // The write that follows reports it.
        }
        return dir.resolve(tag.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    static boolean isCached(String tag) {
        return Files.exists(cached(tag));
    }

    /** Decrypted files while they play. Emptied whenever the chat space opens or closes. */
    static Path plainDir() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "notes-plus-chat");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // The write that follows reports it.
        }
        return dir;
    }

    static void sweepPlain() {
        try (var files = Files.list(plainDir())) {
            files.forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // In use by a player still closing; next sweep.
                }
            });
        } catch (IOException ignored) {
            // Nothing to sweep.
        }
    }

    static void forget(String tag) {
        try {
            Files.deleteIfExists(cached(tag));
        } catch (IOException ignored) {
            // Gone at the next attempt.
        }
    }
}
