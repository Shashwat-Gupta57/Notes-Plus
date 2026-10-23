package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Pictures for cards and editors: decoded off the FX thread at the size they are shown,
 * turned upright from their EXIF orientation (photos from a phone camera are stored sideways),
 * and kept in a small cache keyed by file, size and modification time, so a rewritten sketch
 * preview is never served stale.
 */
final class Images {

    private Images() {}

    private static final ExecutorService DECODE = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "notes-images");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > 80;
        }
    };

    /** Decodes {@code file} to fit {@code maxSize} and hands it over on the FX thread (null if unreadable). */
    static void load(Path file, double maxSize, Consumer<Image> then) {
        String key = key(file, maxSize);
        Image hit;
        synchronized (CACHE) {
            hit = key == null ? null : CACHE.get(key);
        }
        if (hit != null) {
            then.accept(hit);
            return;
        }
        DECODE.execute(() -> {
            Image image = decode(file, maxSize);
            Platform.runLater(() -> then.accept(image));
        });
    }

    /** Blocking; any thread. */
    static Image decode(Path file, double maxSize) {
        String key = key(file, maxSize);
        if (key == null) return null;
        synchronized (CACHE) {
            Image hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        Image image;
        try {
            image = new Image(file.toUri().toString(), maxSize, maxSize, true, true, false);
            if (image.isError()) return null;
            image = upright(image, orientation(file));
        } catch (Exception e) {
            return null;
        }
        synchronized (CACHE) {
            CACHE.put(key, image);
        }
        return image;
    }

    private static String key(Path file, double maxSize) {
        try {
            return file.toAbsolutePath() + "|" + maxSize + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================== EXIF

    /** The EXIF orientation tag of a JPEG (1 = upright), read from its APP1 segment. */
    static int orientation(Path file) {
        try (InputStream raw = Files.newInputStream(file)) {
            return orientation(raw);
        } catch (Exception e) {
            return 1;
        }
    }

    /** The orientation tag from a JPEG's bytes (1 when there is none). */
    static int orientation(InputStream raw) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            if (in.readUnsignedShort() != 0xFFD8) return 1;
            while (true) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA) return 1;
                int length = in.readUnsignedShort();
                if (marker == 0xFFE1) {
                    byte[] segment = new byte[length - 2];
                    in.readFully(segment);
                    if (segment.length > 14 && new String(segment, 0, 4, StandardCharsets.US_ASCII).equals("Exif")) {
                        return tiffOrientation(segment, 6);
                    }
                } else {
                    in.skipNBytes(length - 2);
                }
            }
        } catch (Exception e) {
            return 1;
        }
    }

    private static int tiffOrientation(byte[] b, int start) {
        boolean little = b[start] == 'I';
        int ifd = start + (int) u32(b, start + 4, little);
        int count = u16(b, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 10 > b.length) break;
            if (u16(b, entry, little) == 0x0112) return u16(b, entry + 8, little);
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int a = b[at] & 0xFF, c = b[at + 1] & 0xFF;
        return little ? a | c << 8 : a << 8 | c;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long lo = u16(b, at, little), hi = u16(b, at + 2, little);
        return little ? lo | hi << 16 : lo << 16 | hi;
    }

    /** Rotates by the orientation tag (mirrored variants are treated as their rotation). */
    static Image upright(Image image, int orientation) {
        int degrees = switch (orientation) {
            case 3, 4 -> 180;
            case 5, 6 -> 90;
            case 7, 8 -> 270;
            default -> 0;
        };
        if (degrees == 0) return image;
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] src = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), src, 0, w);
        int ow = degrees == 180 ? w : h, oh = degrees == 180 ? h : w;
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src[y * w + x];
                switch (degrees) {
                    case 90 -> dst[x * ow + (h - 1 - y)] = p;
                    case 180 -> dst[(h - 1 - y) * ow + (w - 1 - x)] = p;
                    default -> dst[(w - 1 - x) * ow + y] = p;
                }
            }
        }
        WritableImage out = new WritableImage(ow, oh);
        out.getPixelWriter().setPixels(0, 0, ow, oh, PixelFormat.getIntArgbInstance(), dst, 0, ow);
        return out;
    }
}
