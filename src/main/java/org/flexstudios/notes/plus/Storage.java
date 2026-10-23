package org.flexstudios.notes.plus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where the app keeps its data: {@code %APPDATA%\Notes+} on Windows, {@code ~/.notes-plus}
 * elsewhere, or the {@code notes.home} system property when set (tests, a portable copy). Inside: {@code notes.db} (the notes table) and {@code notes/<id>/} (each note's
 * photos, sketches and recordings, the same layout as the phone's {@code filesDir/notes}).
 */
final class Storage {

    private Storage() {}

    static Path root() {
        String override = System.getProperty("notes.home");
        String appData = System.getenv("APPDATA");
        Path root = override != null ? Path.of(override)
                : appData != null && !appData.isBlank()
                ? Path.of(appData, "Notes+")
                : Path.of(System.getProperty("user.home"), ".notes-plus");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + root, e);
        }
        return root;
    }

    static Path database() {
        return root().resolve("notes.db");
    }

    static Path notesDir() {
        return root().resolve("notes");
    }

    static void deleteRecursive(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.getLogger("Storage").log(System.Logger.Level.WARNING, "Could not delete " + p, e);
                }
            });
        } catch (IOException e) {
            System.getLogger("Storage").log(System.Logger.Level.WARNING, "Could not delete " + path, e);
        }
    }
}
