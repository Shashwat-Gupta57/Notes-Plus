package org.flexstudios.notes.plus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Playable copies of vault videos. JavaFX's player only plays files reliably, so a video is
 * decrypted to a private temporary folder while it is being watched (or its thumbnail made),
 * and that copy is deleted when the viewer moves on. The whole folder is wiped whenever the
 * vault opens or closes and when the app starts, so nothing outlives a visit.
 *
 * (The phone streams from the encrypted file; this is the desktop's one exception.)
 */
final class VaultVideo {

    private VaultVideo() {}

    static Path dir() {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "notes-plus-play");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    /** Decrypts {@code name} for playback. Blocking; the vault must be open. */
    static Path open(String name) throws IOException {
        Path target = dir().resolve(name + ".mp4");
        if (Files.exists(target)) return target;
        Path part = dir().resolve(name + ".mp4.part");
        try (InputStream in = VaultCrypto.read(VaultCrypto.mediaDir().resolve(name)); OutputStream out = Files.newOutputStream(part)) {
            in.transferTo(out);
        }
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Done with it. */
    static void close(String name) {
        try {
            Files.deleteIfExists(dir().resolve(name + ".mp4"));
        } catch (IOException ignored) {
            // Still in use by a closing player; the next sweep takes it.
        }
    }

    static void sweep() {
        Storage.deleteRecursive(dir());
    }
}
