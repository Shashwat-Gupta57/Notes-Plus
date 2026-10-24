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
 * following the system setting like the phone does, unless a theme is chosen on the Account
 * screen: light, dark, or high contrast (dark or light: plain black and white, no greys).
 *
 * Every colour has one name, used two ways: CSS refers to it as a looked-up colour
 * ({@code -np-text-primary}, from {@link #ref}), and canvases ask for it with {@link #color},
 * redrawing when {@link #dark} flips. Use the semantic names, never a raw hex, or a screen
 * breaks in dark mode.
 */
final class Theme {

    private Theme() {}

    /** True while the app is dark (the system's choice, or the theme's). */
    static final BooleanProperty dark = new SimpleBooleanProperty(false);

    /** The themes, as stored ({@code app_theme}), and as shown. */
    static final String[] MODES = {"system", "light", "dark", "contrast_dark", "contrast_light"};
    static final String[] MODE_NAMES = {"Same as Windows", "Light", "Dark", "High contrast, dark", "High contrast, light"};

    private static boolean systemDark;
    private static final java.util.List<Scene> SCENES = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Overrides for the high-contrast themes: black and white, with plain lines. */
    private static final Map<String, String> CONTRAST_DARK = new java.util.HashMap<>(), CONTRAST_LIGHT = new java.util.HashMap<>();

    static String mode() {
        String m = Prefs.get("app_theme");
        return m == null ? "system" : m;
    }

    /** Switches every window to theme {@code m} now. */
    static void setMode(String m) {
        Prefs.put("app_theme", m);
        boolean before = dark.get();
        update();
        if (dark.get() == before) {
            // Same lightness, other colours (contrast): canvases redraw on a flip.
            dark.set(!before);
            dark.set(before);
        }
        for (Scene s : SCENES) apply(s);
    }

    private static void update() {
        String m = mode();
        dark.set(m.equals("dark") || m.equals("contrast_dark") || (m.equals("system") && systemDark));
    }

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
        def("note_orange", "#FFE3CC", "#43301F");
        def("note_red", "#FFD9D6", "#45231F");
        def("note_teal", "#D3F2EF", "#1D3836");
        def("note_indigo", "#DEDDFB", "#26264A");
        def("note_brown", "#EBDDD2", "#372B23");
        def("note_lime", "#EDF7C9", "#323A1C");

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

        for (String n : new String[]{"app_background", "app_surface", "gallery_background", "m3_surface", "m3_sheet", "m3_menu"}) {
            CONTRAST_DARK.put(n, "#000000");
            CONTRAST_LIGHT.put(n, "#FFFFFF");
        }
        for (String n : new String[]{"text_primary", "text_secondary", "text_body", "divider", "m3_outline", "m3_on_surface",
                "header_chip_text"}) {
            CONTRAST_DARK.put(n, "#FFFFFF");
            CONTRAST_LIGHT.put(n, "#000000");
        }
        CONTRAST_DARK.put("thumb_placeholder", "#1A1A1A");
        CONTRAST_LIGHT.put("thumb_placeholder", "#D0D0D0");
        CONTRAST_DARK.put("header_chip_background", "#000000E6");
        CONTRAST_LIGHT.put("header_chip_background", "#FFFFFFE6");
    }

    /** {@code name}'s value in the current theme. */
    private static String value(String name, String[] pair) {
        String m = mode();
        String over = m.equals("contrast_dark") ? CONTRAST_DARK.get(name) : m.equals("contrast_light") ? CONTRAST_LIGHT.get(name) : null;
        return over != null ? over : pair[dark.get() ? 1 : 0];
    }

    /** The colour {@code name} in the current mode. */
    static Color color(String name) {
        String[] pair = PALETTE.get(name);
        if (pair == null) throw new IllegalArgumentException("No colour " + name);
        return Color.web(value(name, pair));
    }

    /** The CSS looked-up colour for {@code name}, e.g. {@code -np-text-primary}. */
    static String ref(String name) {
        if (!PALETTE.containsKey(name)) throw new IllegalArgumentException("No colour " + name);
        return "-np-" + name.replace('_', '-');
    }

    /** Hooks a scene to the system's light/dark setting, now and whenever it changes. */
    static void attach(Scene scene) {
        if (SCENES.isEmpty()) {
            try {
                var prefs = Platform.getPreferences();
                systemDark = prefs.getColorScheme() == ColorScheme.DARK;
                prefs.colorSchemeProperty().addListener((o, a, b) -> {
                    systemDark = b == ColorScheme.DARK;
                    update();
                });
            } catch (Throwable ignored) {
                // No platform preferences: light, unless a theme says otherwise.
            }
            update();
        }
        SCENES.add(scene);
        scene.windowProperty().addListener((o, a, w) -> {
            if (w != null) w.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> SCENES.remove(scene));
        });
        apply(scene);
        dark.addListener((o, a, b) -> apply(scene));
    }

    /** The palette as a stylesheet in front of the app's own, swapped when the mode changes. */
    private static void apply(Scene scene) {
        StringBuilder css = new StringBuilder(".root {\n");
        boolean d = dark.get();
        for (Map.Entry<String, String[]> e : PALETTE.entrySet()) {
            css.append("  ").append(ref(e.getKey())).append(": ").append(value(e.getKey(), e.getValue())).append(";\n");
        }
        css.append("}\n");
        String uri = "data:text/css;base64," + Base64.getEncoder().encodeToString(css.toString().getBytes(StandardCharsets.UTF_8));
        scene.getStylesheets().removeIf(s -> s.startsWith("data:text/css"));
        scene.getStylesheets().add(0, uri);
        scene.getRoot().pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dark"), d);
    }
}
