package org.flexstudios.notes.plus;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The phone's palette (its values/colors.xml and values-night/colors.xml), light and dark,
 * following the system setting like the phone does.
 *
 * Every colour has one name, used two ways: CSS refers to it as a looked-up colour
 * ({@code -np-text-primary}, from {@link #ref}), and canvases ask for it with {@link #color},
 * redrawing when {@link #dark} flips. Use the semantic names, never a raw hex, or a screen
 * breaks in dark mode.
 */
final class Theme {

    private Theme() {}

    /** True while the system is in dark mode. */
    static final BooleanProperty dark = new SimpleBooleanProperty(false);

    /** name → {light, dark}. */
    private static final Map<String, String[]> PALETTE = new LinkedHashMap<>();

    private static void def(String name, String light, String dark) {
        PALETTE.put(name, new String[]{light, dark});
    }

    private static void def(String name, String both) {
        def(name, both, both);
    }

    static {
        def("black", "#000000");
        def("white", "#FFFFFF");

        // Brand / status: the same in light and dark.
        def("brand_yellow", "#FFCC00");
        def("destructive", "#FF3B30");
        def("accent_blue", "#007AFF");
        def("on_brand_yellow", "#FFFFFF");

        // Surfaces and content.
        def("app_background", "#FFFFFF", "#000000");
        def("app_surface", "#FFFFFF", "#1C1C1E");
        def("gallery_background", "#F2F2F7", "#000000");
        def("thumb_placeholder", "#E5E5EA", "#2C2C2E");
        def("text_primary", "#000000", "#FFFFFF");
        def("text_secondary", "#8E8E93", "#98989F");
        def("text_body", "#333333", "#E5E5EA");
        def("divider", "#C6C6C8", "#38383A");

        // Icon tiles and type accents.
        def("tile_blue", "#0A84FF");
        def("tile_green", "#30D158");
        def("tile_orange", "#FF9F0A");
        def("tile_purple", "#BF5AF2");
        def("tile_red", "#FF453A");
        def("tile_gray", "#8E8E93");
        def("tile_teal", "#40C8E0");

        // Note card tints.
        def("note_yellow", "#FFF4C2", "#3D3624");
        def("note_green", "#DDF5E1", "#22382A");
        def("note_blue", "#DCEBFF", "#1F2F45");
        def("note_purple", "#EDE2FF", "#33284A");
        def("note_pink", "#FFE0EA", "#452A34");
        def("note_gray", "#E9E9EE", "#2C2C30");

        // Material 3 defaults the phone inherits without naming them (baseline scheme).
        def("m3_surface", "#FEF7FF", "#141218");
        def("m3_on_surface", "#1D1B20", "#E6E0E9");
        // JavaFX reads 8-digit hex as RRGGBBAA (Android: AARRGGBB).
        def("header_chip_background", "#FFFFFFA6", "#000000A6");
        def("header_chip_text", "#333333", "#EBEBF5");
        def("m3_primary", "#6750A4", "#D0BCFF");
        def("m3_on_primary", "#FFFFFF", "#381E72");
        def("m3_primary_container", "#EADDFF", "#4F378B");
        def("m3_on_primary_container", "#21005D", "#EADDFF");
        def("m3_secondary_container", "#E8DEF8", "#4A4458");
        def("m3_on_secondary_container", "#1D192B", "#E8DEF8");
        def("m3_sheet", "#F7F2FA", "#1D1B20");
        def("m3_menu", "#F3EDF7", "#211F26");
        def("m3_track", "#E6E0E9", "#36343B");
        def("m3_outline", "#79747E", "#938F99");
        def("m3_inverse_surface", "#322F35", "#E6E0E9");
        def("m3_inverse_on_surface", "#F5EFF7", "#322F35");
        def("m3_inverse_primary", "#D0BCFF", "#6750A4");
        def("m3_hover", "#0000000F", "#FFFFFF14");
    }

    /** The colour {@code name} in the current mode. */
    static Color color(String name) {
        String[] pair = PALETTE.get(name);
        if (pair == null) throw new IllegalArgumentException("No colour " + name);
        return Color.web(pair[dark.get() ? 1 : 0]);
    }

    /** The CSS looked-up colour for {@code name}, e.g. {@code -np-text-primary}. */
    static String ref(String name) {
        if (!PALETTE.containsKey(name)) throw new IllegalArgumentException("No colour " + name);
        return "-np-" + name.replace('_', '-');
    }

    /** Hooks a scene to the system's light/dark setting, now and whenever it changes. */
    static void attach(Scene scene) {
        try {
            var prefs = Platform.getPreferences();
            dark.set(prefs.getColorScheme() == ColorScheme.DARK);
            prefs.colorSchemeProperty().addListener((o, a, b) -> dark.set(b == ColorScheme.DARK));
        } catch (Throwable ignored) {
            // No platform preferences: stay light.
        }
        apply(scene);
        dark.addListener((o, a, b) -> apply(scene));
    }

    /** The palette as a stylesheet in front of the app's own, swapped when the mode changes. */
    private static void apply(Scene scene) {
        StringBuilder css = new StringBuilder(".root {\n");
        boolean d = dark.get();
        for (Map.Entry<String, String[]> e : PALETTE.entrySet()) {
            css.append("  ").append(ref(e.getKey())).append(": ").append(e.getValue()[d ? 1 : 0]).append(";\n");
        }
        css.append("}\n");
        String uri = "data:text/css;base64," + Base64.getEncoder().encodeToString(css.toString().getBytes(StandardCharsets.UTF_8));
        scene.getStylesheets().removeIf(s -> s.startsWith("data:text/css"));
        scene.getStylesheets().add(0, uri);
        scene.getRoot().pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dark"), d);
    }
}
