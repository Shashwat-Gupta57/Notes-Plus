package org.flexstudios.notes.plus;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Colour emoji, drawn from the phone's own Noto Color Emoji pictures (JavaFX on Windows draws
 * emoji as outlines). Only the app's own emoji are bundled: the journal moods and the list's
 * "All done" party popper. Anything else falls back to text.
 */
final class Emoji {

    private Emoji() {}

    private static final Map<String, Image> CACHE = new HashMap<>();

    /** The emoji at {@code size} px. */
    static Node view(String emoji, double size) {
        Image image = image(emoji);
        if (image == null) {
            Label l = new Label(emoji);
            l.setStyle("-fx-font-size: " + size + "px; -fx-font-family: 'Segoe UI Emoji';");
            return l;
        }
        ImageView v = new ImageView(image);
        v.setFitWidth(size);
        v.setFitHeight(size);
        v.setSmooth(true);
        return v;
    }

    static synchronized Image image(String emoji) {
        if (emoji == null || emoji.isEmpty()) return null;
        String key = emoji.codePoints().filter(c -> c != 0xFE0F)
                .mapToObj(Integer::toHexString).collect(Collectors.joining("_"));
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Image image = null;
        try (InputStream in = Emoji.class.getResourceAsStream("emoji/" + key + ".png")) {
            if (in != null) image = new Image(in);
        } catch (Exception ignored) {
            // Not bundled: text it is.
        }
        CACHE.put(key, image);
        return image;
    }
}
