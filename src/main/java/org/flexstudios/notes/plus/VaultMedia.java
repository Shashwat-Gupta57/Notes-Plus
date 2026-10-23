package org.flexstudios.notes.plus;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Everything that moves media in or out of the vault, the phone's VaultMedia: import (encrypt
 * a file into the vault), export (decrypted copies to share), move back out (to where it came
 * from, or Pictures), and erase. Screens call these and never touch the files themselves.
 *
 * Vault files are named like the phone's: {@code IMG_<millis>_<8 hex>} or {@code VID_…}, and
 * that name is the key everywhere (database, thumbnails, encryption's associated data).
 */
final class VaultMedia {

    private VaultMedia() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov") || n.endsWith(".3gp")
                || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".avi") || n.endsWith(".wmv");
    }

    static boolean isMedia(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return isVideo(n) || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif")
                || n.endsWith(".bmp") || n.endsWith(".webp") || n.endsWith(".heic");
    }

    /** Encrypts {@code source} into the vault and records it. Blocking; the vault must be open. */
    static VaultStore.Secret importFile(Path source, int vaultId, Integer albumId) throws IOException {
        boolean video = isVideo(source.getFileName().toString());
        String name = (video ? "VID_" : "IMG_") + System.currentTimeMillis() + "_" + HexFormat.of().formatHex(random(4));
        try (InputStream in = Files.newInputStream(source); OutputStream out = VaultCrypto.write(VaultCrypto.mediaDir(), name)) {
            in.transferTo(out);
        }
        VaultStore.Secret s = new VaultStore.Secret();
        s.fileName = name;
        s.originalPath = source.toAbsolutePath().toString();
        s.dateTaken = video ? Files.getLastModifiedTime(source).toMillis() : dateTaken(source);
        s.dateAdded = System.currentTimeMillis();
        s.video = video;
        s.vaultId = vaultId;
        s.albumId = albumId;
        VaultStore.get().insertSecret(s);
        return s;
    }

    /** A decrypted copy in {@code dir}, named after the original. Blocking. */
    static Path export(VaultStore.Secret s, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path target = free(dir, displayName(s));
        try (InputStream in = VaultCrypto.read(VaultCrypto.mediaDir().resolve(s.fileName));
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(s.dateTaken));
        return target;
    }

    /**
     * Puts an item back on the computer (where it came from if that folder still exists,
     * otherwise Pictures\Notes+), then removes it from the vault. Returns where it went.
     */
    static Path moveOut(VaultStore.Secret s) throws IOException {
        Path dir = null;
        if (s.originalPath != null) {
            Path parent = Path.of(s.originalPath).getParent();
            if (parent != null && Files.isDirectory(parent)) dir = parent;
        }
        if (dir == null) dir = Path.of(System.getProperty("user.home"), "Pictures", "Notes+");
        Path out = export(s, dir);
        remove(s);
        return out;
    }

    /** Erases an item: its file, its thumbnail, its row. Blocking. */
    static void remove(VaultStore.Secret s) {
        try {
            Files.deleteIfExists(VaultCrypto.mediaDir().resolve(s.fileName));
        } catch (IOException e) {
            System.getLogger("Vault").log(System.Logger.Level.WARNING, "Could not erase " + s.fileName, e);
        }
        Thumbs.forget(s.fileName);
        VaultStore.get().deleteSecret(s.fileName);
    }

    /** The original's name if known, else the vault name with the right extension. */
    static String displayName(VaultStore.Secret s) {
        if (s.originalPath != null) {
            String n = Path.of(s.originalPath).getFileName().toString();
            if (!n.isBlank()) return n;
        }
        return s.fileName + (s.video ? ".mp4" : ".jpg");
    }

    private static Path free(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return p;
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot), ext = dot < 0 ? "" : name.substring(dot);
        for (int i = 1; ; i++) {
            p = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    // ================================================================= dates

    /** When a photo was taken: EXIF DateTimeOriginal, else the file's own date. */
    static long dateTaken(Path file) {
        try (InputStream raw = Files.newInputStream(file)) {
            long exif = exifDate(raw);
            if (exif > 0) return exif;
        } catch (Exception ignored) {
            // Fall through to the file's date.
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static long exifDate(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        if (in.readUnsignedShort() != 0xFFD8) return 0;
        while (true) {
            int marker = in.readUnsignedShort();
            if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA) return 0;
            int length = in.readUnsignedShort();
            if (marker != 0xFFE1) {
                in.skipNBytes(length - 2);
                continue;
            }
            byte[] seg = new byte[length - 2];
            in.readFully(seg);
            if (seg.length < 14 || !new String(seg, 0, 4, StandardCharsets.US_ASCII).equals("Exif")) continue;
            int t = 6;
            boolean little = seg[t] == 'I';
            int ifd0 = t + (int) u32(seg, t + 4, little);
            int exifIfd = -1;
            String date = null;
            if (ifd0 > 0 && ifd0 + 2 <= seg.length) {
                int count = u16(seg, ifd0, little);
                for (int i = 0; i < count; i++) {
                    int e = ifd0 + 2 + i * 12;
                    if (e + 12 > seg.length) break;
                    int tag = u16(seg, e, little);
                    if (tag == 0x8769) exifIfd = t + (int) u32(seg, e + 8, little);
                    if (tag == 0x0132) date = ascii(seg, t + (int) u32(seg, e + 8, little));
                }
            }
            if (exifIfd > 0 && exifIfd + 2 <= seg.length) {
                int count = u16(seg, exifIfd, little);
                for (int i = 0; i < count; i++) {
                    int e = exifIfd + 2 + i * 12;
                    if (e + 12 > seg.length) break;
                    if (u16(seg, e, little) == 0x9003) date = ascii(seg, t + (int) u32(seg, e + 8, little));
                }
            }
            if (date == null) return 0;
            try {
                return new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(date).getTime();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static String ascii(byte[] b, int at) {
        if (at < 0 || at + 19 > b.length) return null;
        return new String(b, at, 19, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] b, int at, boolean little) {
        int a = b[at] & 0xFF, c = b[at + 1] & 0xFF;
        return little ? a | c << 8 : a << 8 | c;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long lo = u16(b, at, little), hi = u16(b, at + 2, little);
        return little ? lo | hi << 16 : lo << 16 | hi;
    }
}
