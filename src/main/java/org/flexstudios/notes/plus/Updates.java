package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

/**
 * This build's version, and whether a newer one is out. The phone's updater reads the same
 * {@code version.json}; its {@code desktop} entry is this app's. Downloads go through the
 * browser (the zip replaces the app folder), so nothing here writes to disk.
 */
final class Updates {

    private Updates() {}

    private static final String[] VERSION_ENDPOINTS = {
            "https://releases.talken.in/version.json",
            "https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json"
    };
    private static final String PREF_LAST_CHECK = "update_last_check";
    private static final long AUTO_CHECK_MS = 5 * 60 * 1000L;
    private static boolean checkedOnStartThisSession;

    /** From pom.xml, filled in at build time. */
    static final String VERSION = load();

    record Release(String versionName, String url, String size, String changelog) {}

    private static String load() {
        try (InputStream in = Updates.class.getResourceAsStream("app.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("version", "");
            return v.isEmpty() || v.startsWith("${") ? "dev" : v;
        } catch (Exception e) {
            return "dev";
        }
    }

    /** Blocking. The newer release, or null when this build is up to date. */
    static Release check() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
        for (String endpoint : VERSION_ENDPOINTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?t=" + System.currentTimeMillis()))
                        .header("User-Agent", "NotesPlus-Desktop/" + VERSION)
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) continue;
                Prefs.put(PREF_LAST_CHECK, System.currentTimeMillis());
                JSONObject desktop = new JSONObject(response.body()).optJSONObject("desktop");
                if (desktop == null) return null;
                String name = desktop.optString("versionName", "");
                String url = desktop.optString("url", "");
                if (name.isEmpty() || !url.startsWith("https://") || compare(name, VERSION) <= 0) return null;
                return new Release(name, url, desktop.optString("size", ""), desktop.optString("changelog", ""));
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Numeric, part by part: "10.0" is newer than "1.1.0". A dev build is never behind. */
    static int compare(String a, String b) {
        if (b.equals("dev")) return -1;
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Offers a release: what's new, then the update. Installed with Notes+ Setup, the update is
     * Setup itself, opened on Update (it downloads, closes Notes+ and installs); otherwise
     * (a hand-unpacked copy) the installer downloads in the browser.
     */
    static void offer(Release r) {
        String size = r.size().isEmpty() ? "" : " (" + r.size() + ")";
        java.nio.file.Path setup = installedSetup();
        Ui.dialog().title("Notes+ " + r.versionName() + " is out")
                .message((r.changelog().isEmpty() ? "" : r.changelog() + "\n\n")
                        + (setup != null ? "Notes+ closes, updates, and you can open it again. Your notes stay where they are."
                        : "Download the installer" + size + " and run it. Your notes stay where they are."))
                .positive(setup != null ? "Update now" : "Download", () -> {
                    if (setup != null) runSetup(setup);
                    else NotesApp.openInBrowser(r.url());
                })
                .negative("Later", null)
                .show();
    }

    /** "Notes+ Setup.exe" beside this app, when it was installed by Setup; else null. */
    static java.nio.file.Path installedSetup() {
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (cmd.isEmpty()) return null;
        java.nio.file.Path dir = java.nio.file.Path.of(cmd).getParent();
        java.nio.file.Path setup = dir == null ? null : dir.resolve(Setup.SETUP_EXE);
        return setup != null && java.nio.file.Files.exists(setup) ? setup : null;
    }

    /** Opens Setup on Update, then closes Notes+ the usual way (saving everything) so its files can change. */
    private static void runSetup(java.nio.file.Path setup) {
        try {
            java.nio.file.Path temp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
            new ProcessBuilder(setup.toString(), "--update").directory(temp.toFile()).start();
        } catch (Exception e) {
            Ui.toast("Couldn't open Notes+ Setup");
            return;
        }
        javafx.stage.Stage stage = NotesApp.stage();
        stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        stage.close();
        javafx.application.Platform.exit();
        System.exit(0);
    }

    /** At start: looks at most every twelve hours, and offers a newer release quietly. */
    static void checkSoon() {
        long last = Prefs.getLong(PREF_LAST_CHECK);
        long now = System.currentTimeMillis();
        if (checkedOnStartThisSession && now - last < AUTO_CHECK_MS) return;
        checkedOnStartThisSession = true;
        Account.BG.execute(() -> {
            try {
                Release r = check();
                if (r != null) {
                    javafx.application.Platform.runLater(() -> offer(r));
                }
            } catch (Exception e) {
                System.getLogger("Updates").log(System.Logger.Level.INFO, "Update check skipped", e);
            }
        });
    }
}
