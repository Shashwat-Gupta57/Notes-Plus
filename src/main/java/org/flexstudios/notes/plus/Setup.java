package org.flexstudios.notes.plus;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Notes+ Setup: install, update, repair and uninstall, in the app's own look.
 *
 * <ul>
 *   <li><b>Install</b> copies this app image into {@code Program Files\Notes+} (refusing if it
 *       is already there), adds the shortcuts asked for, and registers the app with Windows:
 *       Apps &amp; features (uninstall via {@code unins.exe}) and App Paths.</li>
 *   <li><b>Update</b> compares the installed version with the newest online one (the
 *       {@code desktop} entry of version.json) and with this Setup's own, and installs the
 *       newest, downloading it when this Setup is older. Up to date: says so.</li>
 *   <li><b>Repair</b> reinstalls this Setup's own version over what is there, no questions
 *       asked online.</li>
 *   <li><b>Uninstall</b> removes the app, its shortcuts and registration; notes stay unless
 *       asked otherwise.</li>
 * </ul>
 *
 * It needs administrator rights (Program Files), and relaunches itself elevated. Run from
 * inside the installed folder (the app's updater, unins.exe), it first copies itself to a
 * temporary folder, since it can't replace the files it runs from. Notes live in
 * {@code %APPDATA%\Notes+}, never in the program folder, so none of this touches them.
 */
public class Setup extends Application {

    static final String APP_EXE = "Notes+.exe", SETUP_EXE = "Notes+ Setup.exe", UNINS_EXE = "unins.exe";
    private static final String[] VERSION_ENDPOINTS = {
            "https://releases.talken.in/version.json",
            "https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json"
    };
    private static final String REG_UNINSTALL = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Notes+";
    private static final String REG_APP_PATH = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\Notes+.exe";
    private static final String TEMP_PREFIX = "NotesPlusSetup-";

    private Stage stage;
    private final StackPane page = new StackPane();
    private List<String> args;
    /** Another Setup took over from this one (elevated, or from a temporary copy): leave its files be. */
    private volatile boolean handedOff;

    /** Where Notes+ is installed. */
    static Path installDir() {
        String pf = System.getenv("ProgramFiles");
        return Path.of(pf == null || pf.isBlank() ? "C:\\Program Files" : pf, "Notes+");
    }

    /** This program's own app image (the folder holding Notes+ Setup.exe), or null when run from an IDE. */
    static Path selfImage() {
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (cmd.isEmpty()) return null;
        Path dir = Path.of(cmd).getParent();
        return dir != null && Files.exists(dir.resolve(APP_EXE)) && Files.isDirectory(dir.resolve("runtime")) ? dir : null;
    }

    /**
     * Whether {@code p} is in {@code dir}, comparing real paths without regard to case: Windows
     * may report the running program under a short (PROGRA~1) or differently cased path, and a
     * miss here made Setup replace the runtime it was running from.
     */
    static boolean inside(Path p, Path dir) {
        try {
            if (!Files.exists(dir)) return false;
            String a = p.toRealPath().toString().toLowerCase(java.util.Locale.ROOT);
            String b = dir.toRealPath().toString().toLowerCase(java.util.Locale.ROOT);
            return a.equals(b) || a.startsWith(b + java.io.File.separator);
        } catch (IOException e) {
            return p.toString().toLowerCase(java.util.Locale.ROOT).startsWith(dir.toString().toLowerCase(java.util.Locale.ROOT));
        }
    }

    static boolean installed() {
        return Files.exists(installDir().resolve(APP_EXE));
    }

    /** The installed version, "" when unknown (installed by hand, or not at all). */
    static String installedVersion() {
        try {
            return Files.readString(installDir().resolve("version.txt"), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    // ================================================================= start

    @Override
    public void start(Stage stage) {
        releaseWorkingDir();
        this.stage = stage;
        args = getParameters().getRaw();
        for (String face : new String[]{"Regular", "Medium", "Bold"}) {
            try (InputStream in = NotesApp.class.getResourceAsStream("fonts/Roboto-" + face + ".ttf")) {
                if (in != null) javafx.scene.text.Font.loadFont(in, 14);
            } catch (Exception ignored) {
                // System font.
            }
        }
        page.getStyleClass().add("setup-root");
        Scene scene = new Scene(page, 640, 540);
        scene.getStylesheets().add(NotesApp.class.getResource("styles.css").toExternalForm());
        Theme.attach(scene);
        stage.setTitle("Notes+ Setup");
        stage.setResizable(false);
        try (InputStream icon = NotesApp.class.getResourceAsStream("icon.png")) {
            if (icon != null) stage.getIcons().add(new Image(icon));
        } catch (Exception ignored) {
            // Default icon.
        }
        stage.setScene(scene);
        stage.show();

        Path self = selfImage();
        if (self != null && inside(self, installDir())) {
            // Can't replace the files it runs from: run a temporary copy instead.
            show(busy("Getting ready…"));
            work(() -> {
                Path copy = Files.createTempDirectory(TEMP_PREFIX).resolve("Notes+");
                copyTree(self, copy, null);
                relaunch(copy.resolve(SETUP_EXE), false);
                return null;
            }, r -> {
                Platform.exit();
                System.exit(0);
            }, err -> show(message("Couldn't start Setup", err, false)));
            return;
        }
        if (self != null && !isAdmin()) {
            elevate();
            return;
        }
        route();
    }

    @Override
    public void stop() {
        if (!handedOff) cleanUpTemp();
    }

    /** The screen the arguments ask for, or the choices. */
    private void route() {
        if (args.contains("--uninstall")) uninstallScreen();
        else if (args.contains("--update")) update();
        else if (args.contains("--repair")) repair();
        else home();
    }

    // =========================================================== elevation

    private static boolean isAdmin() {
        try {
            Path probe = Files.createTempFile(installDir().getParent(), "np-setup", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Relaunches with administrator rights (Windows asks), or explains why it's needed. */
    private void elevate() {
        show(busy("Asking Windows for permission…"));
        work(() -> relaunch(Path.of(ProcessHandle.current().info().command().orElseThrow()), true),
                ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        Platform.exit();
                        System.exit(0);
                    } else show(screen("Setup needs permission",
                            "Notes+ installs into Program Files, which Windows guards. Choose Yes when Windows asks.",
                            null, button("Try again", true, this::elevate), button("Exit", false, () -> {
                                Platform.exit();
                                System.exit(0);
                            })));
                }, err -> show(message("Couldn't ask for permission", err, false)));
    }

    /** Starts {@code exe} with this run's arguments; {@code admin}: through the UAC prompt. True if it started. */
    private Boolean relaunch(Path exe, boolean admin) throws Exception {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        if (!admin) {
            List<String> cmd = new ArrayList<>(List.of(exe.toString()));
            cmd.addAll(args);
            handedOff = true;
            new ProcessBuilder(cmd).directory(temp.toFile()).start();
            return true;
        }
        StringBuilder ps = new StringBuilder("Start-Process -FilePath '").append(exe.toString().replace("'", "''"))
                .append("' -WorkingDirectory '").append(temp.toString().replace("'", "''")).append("' -Verb RunAs");
        if (!args.isEmpty()) {
            ps.append(" -ArgumentList ");
            for (int i = 0; i < args.size(); i++) ps.append(i == 0 ? "" : ",").append("'").append(args.get(i).replace("'", "''")).append("'");
        }
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps.toString())
                .directory(temp.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        boolean started = p.waitFor() == 0;
        if (started) handedOff = true;
        return started;
    }

    // ================================================================ home

    private void home() {
        Path self = selfImage();
        boolean have = installed();
        String there = installedVersion();
        List<Node> rows = new ArrayList<>();
        Path freshPending = installDir().resolveSibling("Notes+.new");
        if (Files.exists(freshPending.resolve(APP_EXE)) && Files.exists(freshPending.resolve("version.txt"))) {
            String pendingVer = "";
            try {
                pendingVer = Files.readString(freshPending.resolve("version.txt"), StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {}
            if (!pendingVer.isEmpty() && (there.isEmpty() || Updates.compare(pendingVer, there) > 0)) {
                final String fv = pendingVer;
                rows.add(VaultSettingsView.row(Icons.SAVE, "tile_green", "Finish Update",
                        "Install downloaded update " + pendingVer + " now", VaultSettingsView.chevron(),
                        () -> runInstall(freshPending, fv, Files.exists(desktopLink()), Files.exists(startMenuLink()), "Updated")));
            }
        }
        if (!have) rows.add(VaultSettingsView.row(Icons.SAVE, "tile_green", "Install",
                "Notes+ " + Updates.VERSION + " on this PC", VaultSettingsView.chevron(), this::install));
        rows.add(VaultSettingsView.row(Icons.CLOUD, "tile_blue", "Update",
                "The newest version, if this PC doesn't have it", VaultSettingsView.chevron(), this::update));
        if (have) rows.add(VaultSettingsView.row(Icons.GEAR, "tile_orange", "Repair",
                "Reinstall " + Updates.VERSION + " over what is there", VaultSettingsView.chevron(), this::repair));
        if (have) rows.add(VaultSettingsView.row(Icons.DELETE, "destructive", "Uninstall",
                "Remove Notes+ from this PC", VaultSettingsView.chevron(), this::uninstallScreen));
        String status = self == null ? "Run this from the downloaded installer to install."
                : have ? "Notes+" + (there.isEmpty() ? "" : " " + there) + " is installed on this PC."
                : "Notes, lists, mind maps, sketches and recordings, synced with your phone.";
        show(screen(have ? "Welcome back" : "Welcome to Notes+", status,
                VaultSettingsView.card(rows.toArray(new Node[0])), null, button("Close", false, Platform::exit)));
    }

    // ============================================================= install

    /** Asked on the install screen: make Notes+ the default for text files (Windows asks the person). */
    private boolean makeDefault;

    private void install() {
        if (installed()) {
            String v = installedVersion();
            show(screen("Already installed",
                    "Notes+" + (v.isEmpty() ? "" : " " + v) + " is already on this PC, and can't be installed twice. "
                            + "Update gets a newer version; Repair reinstalls this one.",
                    null, button("Back", false, this::home), button("Close", true, Platform::exit)));
            return;
        }
        boolean[] opts = {true, true, true};
        Node options = VaultSettingsView.card(
                VaultSettingsView.row(Icons.PIN, "tile_blue", "Desktop shortcut", "Notes+ on your desktop",
                        VaultSettingsView.toggle(true, on -> opts[0] = on), null),
                VaultSettingsView.row(Icons.SEARCH, "tile_teal", "Start menu",
                        "Find it by typing \"Notes+\" after the Windows key", VaultSettingsView.toggle(true, on -> opts[1] = on), null),
                VaultSettingsView.row(Icons.EDIT, "tile_orange", "Default text editor",
                        "Text files (.txt) open with Notes+", VaultSettingsView.toggle(true, on -> opts[2] = on), null));
        Label where = Ui.label("Installs to " + installDir() + ". Your notes live apart, in your own profile.", 12.5, "secondary");
        where.setWrapText(true);
        show(screen("Install Notes+ " + Updates.VERSION, "Choose how you'd like to find it.", new VBox(10, options, where),
                button("Install", true, () -> {
                    makeDefault = opts[2];
                    runInstall(selfImage(), Updates.VERSION, opts[0], opts[1], "All set");
                }),
                button("Back", false, this::home)));
    }

    /** Copies {@code source} in, registers it, and says it's done. */
    private void runInstall(Path source, String version, boolean desktop, boolean menu, String doneTitle) {
        if (source == null) {
            show(message("Nothing to install", "Run Notes+ Setup from the downloaded installer.", false));
            return;
        }
        whenClosed(() -> {
            ProgressBar bar = new ProgressBar(0);
            Label status = Ui.label("Copying files…", 14, "secondary");
            show(progressScreen("Installing Notes+ " + version, status, bar));
            work(() -> {
                releaseWorkingDir();
                Path dir = installDir();
                Path fresh = dir.resolveSibling("Notes+.new"), old = dir.resolveSibling("Notes+.old");
                if (!source.equals(fresh)) {
                    try {
                        deleteTree(fresh);
                    } catch (Exception ignored) {}
                    try {
                        deleteTree(old);
                    } catch (Exception ignored) {}
                    if (Files.exists(old)) {
                        old = dir.resolveSibling("Notes+.old-" + System.currentTimeMillis());
                    }
                    long total = size(source);
                    long[] done = {0};
                    copyTree(source, fresh, n -> {
                        done[0] += n;
                        double f = total == 0 ? 0 : (double) done[0] / total;
                        Platform.runLater(() -> bar.setProgress(f));
                    });
                    Files.writeString(fresh.resolve("version.txt"), version, StandardCharsets.UTF_8);
                }
                long total = size(fresh);
                Platform.runLater(() -> status.setText("Finishing…"));

                // Multi-tier atomic replace
                swapDirectories(fresh, dir, old);

                Path logs = dir.resolve("logs");
                try {
                    Files.createDirectories(logs);
                    new ProcessBuilder("icacls", logs.toString(), "/grant", "*S-1-5-32-545:(OI)(CI)M", "/T", "/Q").start().waitFor();
                } catch (Exception ignored) {}

                register(dir, version, total);
                shortcut(desktopLink(), dir, desktop);
                shortcut(startMenuLink(), dir, menu);
                if (makeDefault) {
                    try {
                        DefaultApp.set(".txt", PROG_ID);
                    } catch (Exception e) {
                        System.getLogger("Setup").log(System.Logger.Level.WARNING, "Couldn't make Notes+ the default for .txt", e);
                    }
                }
                return null;
            }, r -> show(screen(doneTitle, "Notes+ " + version + " is ready. Your notes are where they were."
                            + (makeDefault ? " Text files now open with it." : ""),
                    null,
                    button("Finish", false, Platform::exit),
                    button("Open Notes+", true, () -> {
                        try {
                            new ProcessBuilder(installDir().resolve(APP_EXE).toString()).directory(installDir().toFile()).start();
                        } catch (IOException ignored) {
                            // They can open it from the Start menu.
                        }
                        Platform.exit();
                    }))),
                    err -> show(message("Installation didn't finish",
                            err + "\n\nTip: Close any open Notes+ windows or File Explorer folders, then click Retry.",
                            () -> runInstall(source, version, desktop, menu, doneTitle),
                            true)));
        });
    }

    // ============================================================== update

    private void update() {
        if (!installed()) {
            show(screen("Notes+ isn't installed", "There is nothing to update yet.", null,
                    button("Back", false, this::home), button("Install", true, this::install)));
            return;
        }
        show(busy("Looking for the newest version…"));
        String have = installedVersion();
        work(Setup::latestOnline, online -> {
            String bundled = Updates.VERSION;
            String onlineVersion = online == null ? null : online.optString("versionName", null);
            String best = onlineVersion != null && Updates.compare(onlineVersion, bundled) > 0 ? onlineVersion : bundled;
            if (!have.isEmpty() && Updates.compare(best, have) <= 0) {
                show(screen("You're up to date", "Notes+ " + have + " is the newest version.", null,
                        button("Back", false, this::home), button("Done", true, Platform::exit)));
                return;
            }
            boolean shortcutsDesktop = Files.exists(desktopLink()), shortcutsMenu = Files.exists(startMenuLink()) || !Files.exists(desktopLink());
            if (best.equals(bundled) || online == null || online.optString("zip").isEmpty()) {
                show(screen("Update to " + bundled, (have.isEmpty() ? "" : "This PC has " + have + ". ") + "Your notes stay as they are.", null,
                        button("Back", false, this::home),
                        button("Update", true, () -> runInstall(selfImage(), bundled, shortcutsDesktop, shortcutsMenu, "Updated"))));
                return;
            }
            show(screen("Update to " + best, (have.isEmpty() ? "" : "This PC has " + have + ". ")
                    + "It downloads" + (online.optString("size").isEmpty() ? "" : " (" + online.optString("size") + ")")
                    + ", then installs. Your notes stay as they are.", null,
                    button("Back", false, this::home),
                    button("Download and update", true, () -> download(online, best, shortcutsDesktop, shortcutsMenu))));
        }, err -> show(message("Couldn't check for updates",
                "Couldn't reach the update server: " + err + "\n\nPlease check your internet connection.",
                this::update,
                true)));
    }

    /** The {@code desktop} entry of version.json. */
    static JSONObject latestOnline() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Exception lastErr = null;
        for (String url : VERSION_ENDPOINTS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url + "?t=" + System.currentTimeMillis()))
                        .header("User-Agent", "NotesPlus-Setup/" + Updates.VERSION)
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    JSONObject obj = new JSONObject(r.body()).optJSONObject("desktop");
                    if (obj != null) return obj;
                }
            } catch (Exception e) {
                lastErr = e;
            }
        }
        if (lastErr != null) throw lastErr;
        throw new IOException("Failed to fetch version info from update servers.");
    }

    /** Downloads the newest app zip, checks it, unpacks it and installs it. */
    private void download(JSONObject online, String version, boolean desktop, boolean menu) {
        ProgressBar bar = new ProgressBar(0);
        Label status = Ui.label("Downloading…", 14, "secondary");
        show(progressScreen("Downloading Notes+ " + version, status, bar));
        work(() -> {
            String url = online.getString("zip");
            if (!url.startsWith("https://")) throw new IOException("Unexpected download address");
            Path temp = Files.createTempDirectory(TEMP_PREFIX + "dl-");
            Path zip = temp.resolve("notes-plus.zip");
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "NotesPlus-Setup/" + Updates.VERSION).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("Download failed (HTTP " + r.statusCode() + ")");
            long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(zip)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    double f = total > 0 ? (double) done / total : ProgressBar.INDETERMINATE_PROGRESS;
                    long d = done;
                    Platform.runLater(() -> {
                        bar.setProgress(f);
                        status.setText("Downloading…  " + ViewerView.bytes(d) + (total > 0 ? " of " + ViewerView.bytes(total) : ""));
                    });
                }
            }
            String expected = online.optString("zipSha256", "");
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(HexFormat.of().formatHex(sha.digest()))) {
                throw new IOException("The download was damaged (its checksum doesn't match). Try again.");
            }
            Platform.runLater(() -> {
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                status.setText("Unpacking…");
            });
            Path out = temp.resolve("unpacked");
            unzip(zip, out);
            Files.deleteIfExists(zip);
            Path image = out.resolve("Notes+");
            if (!Files.exists(image.resolve(APP_EXE))) throw new IOException("The download isn't a Notes+ build");
            return image;
        }, image -> runInstall(image, version, desktop, menu, "Updated"),
                err -> show(message("Update didn't finish",
                        err + "\n\nTip: Check your connection and try again.",
                        () -> download(online, version, desktop, menu),
                        true)));
    }

    // ============================================================== repair

    private void repair() {
        if (!installed()) {
            install();
            return;
        }
        String have = installedVersion();
        show(screen("Repair Notes+", "Reinstalls version " + Updates.VERSION + (have.isEmpty() ? "" : " over " + have)
                        + ", replacing the program files. Your notes stay as they are.", null,
                button("Repair", true, () -> runInstall(selfImage(), Updates.VERSION,
                        Files.exists(desktopLink()), Files.exists(startMenuLink()), "Repaired")),
                button("Back", false, this::home)));
    }

    // =========================================================== uninstall

    private void uninstallScreen() {
        if (!installed()) {
            show(screen("Notes+ isn't installed", "There is nothing to remove.", null,
                    button("Close", true, Platform::exit), button("Back", false, this::home)));
            return;
        }
        boolean[] wipe = {false};
        Node options = VaultSettingsView.card(VaultSettingsView.row(Icons.DELETE, "destructive",
                "Also delete my notes and vault", "On this PC only. Notes in your account stay online.",
                VaultSettingsView.toggle(false, on -> wipe[0] = on), null));
        show(screen("Uninstall Notes+", "Removes the app, its shortcuts and its place in Windows. "
                        + "Leave the switch off to keep your notes for next time.", options,
                button("Uninstall", true, () -> runUninstall(wipe[0])), button("Cancel", false, Platform::exit)));
    }

    private void runUninstall(boolean wipe) {
        whenClosed(() -> {
            show(busy("Removing Notes+…"));
            work(() -> {
                Files.deleteIfExists(desktopLink());
                Files.deleteIfExists(startMenuLink());
                unregister();
                deleteTree(installDir());
                if (wipe) {
                    String appData = System.getenv("APPDATA");
                    if (appData != null && !appData.isBlank()) deleteTree(Path.of(appData, "Notes+"));
                }
                return null;
            }, r -> show(screen("Notes+ is removed", wipe ? "The app and its data are gone from this PC."
                            : "Your notes are still in your profile, ready if you install it again.", null,
                    button("Finish", true, Platform::exit), null)),
                    err -> show(message("Uninstall didn't finish", err, true)));
        });
    }

    // ======================================================== the open app

    private interface WinKernel extends com.sun.jna.win32.StdCallLibrary {
        WinKernel INSTANCE = com.sun.jna.Native.load("kernel32", WinKernel.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        boolean SetCurrentDirectory(String path);
        boolean MoveFileEx(String existingFile, String newFile, int flags);
    }

    static void releaseWorkingDir() {
        try {
            WinKernel.INSTANCE.SetCurrentDirectory(System.getProperty("java.io.tmpdir"));
        } catch (Throwable ignored) {}
    }

    private static void killLingeringApp() {
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", APP_EXE, "/T").redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {}
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {}
    }

    private static boolean isAppRunning() {
        if (!runningApp().isEmpty()) return true;
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + APP_EXE, "/NH").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return out.toLowerCase(Locale.ROOT).contains(APP_EXE.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    /** Notes+ must be closed before its files change: asks, and closes it on request. */
    private void whenClosed(Runnable then) {
        if (!isAppRunning()) {
            then.run();
            return;
        }
        show(screen("Notes+ is open", "Close it first, so its files can be replaced. Notes are saved as you go.", null,
                button("Cancel", false, this::home),
                button("Close Notes+ and continue", true, () -> {
                    show(busy("Closing Notes+…"));
                    work(() -> {
                        for (ProcessHandle p : runningApp()) p.destroy();
                        for (ProcessHandle p : runningApp()) {
                            try {
                                p.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (Exception ignored) {}
                        }
                        killLingeringApp();
                        return null;
                    }, r -> then.run(), err -> show(message("Couldn't close Notes+", err, () -> whenClosed(then), true)));
                })));
    }

    private static List<ProcessHandle> runningApp() {
        Path install = installDir();
        String exeName = APP_EXE.toLowerCase(java.util.Locale.ROOT);
        long me = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != me && p.info().command().map(c -> {
                    try {
                        Path cp = Path.of(c);
                        return inside(cp, install) || cp.getFileName().toString().toLowerCase(java.util.Locale.ROOT).equals(exeName);
                    } catch (Exception e) {
                        return false;
                    }
                }).orElse(false))
                .toList();
    }

    private static void swapDirectories(Path fresh, Path dir, Path old) throws IOException {
        releaseWorkingDir();
        killLingeringApp();

        // Tier 1: Fast directory rename (standard move)
        if (Files.exists(dir)) {
            try {
                moveWithRetry(dir, old);
                moveWithRetry(fresh, dir);
                cleanOldDirectory(old);
                return;
            } catch (IOException e) {
                System.getLogger("Setup").log(System.Logger.Level.WARNING,
                        "Directory move failed (" + e.getMessage() + "), falling back to in-place file replacement.");
                if (!Files.exists(dir) && Files.exists(old)) {
                    try {
                        Files.move(old, dir);
                    } catch (Exception ignored) {}
                }
            }
        } else {
            moveWithRetry(fresh, dir);
            return;
        }

        // Tier 2: In-place file replacement (handles locked directories/CWD/Explorer)
        copyTreeWithFileFallback(fresh, dir);
        try {
            deleteTree(fresh);
        } catch (Exception ignored) {}
    }

    private static void copyTreeWithFileFallback(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (Files.isRegularFile(src)) {
                    replaceFileWithFallback(src, dst);
                }
            }
        }
    }

    private static void replaceFileWithFallback(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException e) {
            Path oldFile = dst.resolveSibling(dst.getFileName().toString() + ".old-" + System.currentTimeMillis());
            try {
                Files.move(dst, oldFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                throw new IOException("Cannot replace file " + dst.getFileName() + ": " + e.getMessage(), e);
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.deleteIfExists(oldFile);
            } catch (Exception ignored) {
                try {
                    WinKernel.INSTANCE.MoveFileEx(oldFile.toString(), null, 0x04); // MOVEFILE_DELAY_UNTIL_REBOOT
                } catch (Throwable ignored2) {}
            }
        }
    }

    private static void cleanOldDirectory(Path old) {
        if (!Files.exists(old)) return;
        try {
            deleteTree(old);
        } catch (Exception e) {
            try {
                new ProcessBuilder("cmd", "/c", "ping -n 4 127.0.0.1 >nul & rmdir /s /q \"" + old + "\"").start();
            } catch (Exception ignored) {}
        }
    }

    private static void moveWithRetry(Path source, Path target) throws IOException {
        IOException last = null;
        for (int i = 0; i < 15; i++) {
            try {
                Files.move(source, target);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during file move", ie);
                }
            }
        }
        if (last != null) throw last;
    }

    // ======================================================= registration

    /** Apps &amp; features (with unins.exe) and App Paths, so Windows knows Notes+. */
    private static final String CLASSES = "HKEY_LOCAL_MACHINE\\Software\\Classes\\";
    private static final String PROG_ID = "NotesPlus.TextFile";

    /**
     * Apps & features (with unins.exe), App Paths, and "Open with" for .txt files, written as
     * one .reg file and imported, so paths with spaces and quotes reach the registry exactly.
     * Windows lets people choose the default app themselves: Notes+ appears in "Open with"
     * for text files, and "Always" makes it theirs.
     */
    private static void register(Path dir, String version, long bytes) throws Exception {
        String app = dir.resolve(APP_EXE).toString();
        String open = "\"" + app + "\" \"%1\"";
        String uninstall = "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Notes+";
        String appPath = "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\Notes+.exe";
        StringBuilder r = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
        key(r, uninstall);
        str(r, "DisplayName", "Notes+");
        str(r, "DisplayVersion", version);
        str(r, "Publisher", "Flex Studios");
        str(r, "DisplayIcon", app);
        str(r, "InstallLocation", dir.toString());
        str(r, "UninstallString", "\"" + dir.resolve(UNINS_EXE) + "\"");
        str(r, "ModifyPath", "\"" + dir.resolve(SETUP_EXE) + "\"");
        str(r, "URLInfoAbout", "https://notes.talken.in");
        r.append("\"EstimatedSize\"=dword:").append(String.format("%08x", bytes / 1024)).append("\r\n");
        r.append("\"NoRepair\"=dword:00000000\r\n");
        key(r, appPath);
        r.append("@=").append(quote(app)).append("\r\n");
        str(r, "Path", dir.toString());
        // Open with: a ProgID for Notes+'s text files, offered for .txt.
        key(r, CLASSES + PROG_ID);
        r.append("@=").append(quote("Text document")).append("\r\n");
        key(r, CLASSES + PROG_ID + "\\DefaultIcon");
        r.append("@=").append(quote(app + ",0")).append("\r\n");
        key(r, CLASSES + PROG_ID + "\\shell\\open\\command");
        r.append("@=").append(quote(open)).append("\r\n");
        key(r, CLASSES + ".txt\\OpenWithProgids");
        r.append(quote(PROG_ID)).append("=hex(0):\r\n");
        key(r, CLASSES + "Applications\\Notes+.exe");
        str(r, "FriendlyAppName", "Notes+");
        key(r, CLASSES + "Applications\\Notes+.exe\\SupportedTypes");
        str(r, ".txt", "");
        key(r, CLASSES + "Applications\\Notes+.exe\\shell\\open\\command");
        r.append("@=").append(quote(open)).append("\r\n");
        // Exported notes (.npnote) are Notes+'s own files: they open with it.
        key(r, CLASSES + ".npnote");
        r.append("@=").append(quote("NotesPlus.Note")).append("\r\n");
        key(r, CLASSES + "NotesPlus.Note");
        r.append("@=").append(quote("Notes+ note")).append("\r\n");
        key(r, CLASSES + "NotesPlus.Note\\DefaultIcon");
        r.append("@=").append(quote(app + ",0")).append("\r\n");
        key(r, CLASSES + "NotesPlus.Note\\shell\\open\\command");
        r.append("@=").append(quote(open)).append("\r\n");
        // A registered application, so Default apps lists Notes+ and what it can open.
        key(r, "HKEY_LOCAL_MACHINE\\Software\\NotesPlus\\Capabilities");
        str(r, "ApplicationName", "Notes+");
        str(r, "ApplicationDescription", "Notes, and a text editor that keeps .txt files in your notes.");
        key(r, "HKEY_LOCAL_MACHINE\\Software\\NotesPlus\\Capabilities\\FileAssociations");
        str(r, ".txt", PROG_ID);
        key(r, "HKEY_LOCAL_MACHINE\\Software\\RegisteredApplications");
        str(r, "Notes+", "Software\\NotesPlus\\Capabilities");
        Path file = Files.createTempFile("notes-plus-setup", ".reg");
        try {
            // regedit files are UTF-16 with a BOM.
            byte[] body = r.toString().getBytes(StandardCharsets.UTF_16LE);
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xFE;
            System.arraycopy(body, 0, out, 2, body.length);
            Files.write(file, out);
            run("reg", "import", file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Removes everything {@link #register} wrote. */
    private static void unregister() throws Exception {
        run("reg", "delete", REG_UNINSTALL, "/f");
        run("reg", "delete", REG_APP_PATH, "/f");
        run("reg", "delete", "HKLM\\Software\\Classes\\" + PROG_ID, "/f");
        run("reg", "delete", "HKLM\\Software\\Classes\\Applications\\Notes+.exe", "/f");
        run("reg", "delete", "HKLM\\Software\\Classes\\.txt\\OpenWithProgids", "/v", PROG_ID, "/f");
        run("reg", "delete", "HKLM\\Software\\NotesPlus", "/f");
        run("reg", "delete", "HKLM\\Software\\Classes\\.npnote", "/f");
        run("reg", "delete", "HKLM\\Software\\Classes\\NotesPlus.Note", "/f");
        run("reg", "delete", "HKLM\\Software\\RegisteredApplications", "/v", "Notes+", "/f");
    }

    private static void key(StringBuilder r, String key) {
        r.append("\r\n[").append(key).append("]\r\n");
    }

    private static void str(StringBuilder r, String name, String value) {
        r.append(quote(name)).append("=").append(quote(value)).append("\r\n");
    }

    /** A .reg string: in quotes, with backslashes and quotes escaped. */
    private static String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path desktopLink() {
        String pub = System.getenv("PUBLIC");
        return Path.of(pub == null ? "C:\\Users\\Public" : pub, "Desktop", "Notes+.lnk");
    }

    private static Path startMenuLink() {
        String data = System.getenv("ProgramData");
        return Path.of(data == null ? "C:\\ProgramData" : data, "Microsoft", "Windows", "Start Menu", "Programs", "Notes+.lnk");
    }

    /** Makes (or, when not wanted, removes) a shortcut to the app. */
    private static void shortcut(Path link, Path dir, boolean wanted) throws Exception {
        Files.deleteIfExists(link);
        if (!wanted) return;
        Files.createDirectories(link.getParent());
        String target = dir.resolve(APP_EXE).toString();
        String script = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('" + q(link.toString()) + "'); "
                + "$s.TargetPath = '" + q(target) + "'; $s.WorkingDirectory = '" + q(dir.toString()) + "'; "
                + "$s.IconLocation = '" + q(target) + ",0'; $s.Description = 'Notes+'; $s.Save()";
        run("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
    }

    private static String q(String s) {
        return s.replace("'", "''");
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        // Deleting a registry key that isn't there is fine.
        if (code != 0 && !(cmd[0].equals("reg") && cmd[1].equals("delete"))) {
            throw new IOException(cmd[0] + " failed: " + out.trim());
        }
    }

    // ============================================================== files

    interface Copied {
        void bytes(long n);
    }

    private static void copyTree(Path from, Path to, Copied progress) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("version.txt")) return FileVisitResult.CONTINUE;
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                if (progress != null) progress.bytes(attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long size(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void unzip(Path zip, Path to) throws IOException {
        Path root = to.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("Damaged download");
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** A temporary copy of Setup (the installer's or one made to update) removes itself after closing. */
    private static void cleanUpTemp() {
        Path self = selfImage();
        String tmp = System.getenv("TEMP");
        if (self == null || tmp == null || self.getParent() == null) return;
        Path holder = self.getParent();
        if (!holder.startsWith(Path.of(tmp)) || !holder.getFileName().toString().startsWith(TEMP_PREFIX)) return;
        try {
            new ProcessBuilder("cmd", "/c", "ping -n 4 127.0.0.1 >nul & rmdir /s /q \"" + holder + "\"").start();
        } catch (IOException ignored) {
            // Windows clears its temp folder in time.
        }
    }

    // =================================================================== ui

    interface Task<T> {
        T run() throws Exception;
    }

    private <T> void work(Task<T> task, java.util.function.Consumer<T> done, java.util.function.Consumer<String> failed) {
        Thread t = new Thread(() -> {
            try {
                T r = task.run();
                Platform.runLater(() -> done.accept(r));
            } catch (Exception e) {
                System.getLogger("Setup").log(System.Logger.Level.WARNING, "Setup step failed", e);
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> failed.accept(m));
            }
        }, "setup-work");
        t.setDaemon(true);
        t.start();
    }

    private void show(Node content) {
        content.setOpacity(0);
        page.getChildren().setAll(content);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(180), content);
        fade.setToValue(1);
        fade.play();
    }

    /** The brand line, a title and a line under it, the body, and the buttons with the version beside them. */
    private Node screen(String title, String subtitle, Node body, Button... buttons) {
        Label brand = new Label("Notes+");
        brand.getStyleClass().add("setup-brand");
        Label chip = new Label("Setup");
        chip.getStyleClass().add("setup-chip");
        HBox brandRow = new HBox(10, icon(28), brand, chip);
        brandRow.setAlignment(Pos.CENTER_LEFT);

        Label t = new Label(title);
        t.getStyleClass().add("setup-title");
        t.setWrapText(true);
        Label s = new Label(subtitle);
        s.getStyleClass().add("setup-subtitle");
        s.setWrapText(true);
        VBox head = new VBox(6, t, s);

        VBox content = new VBox(22, brandRow, head);
        if (body != null) content.getChildren().add(body);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Label foot = new Label("Version " + Updates.VERSION + "  ·  Flex Studios");
        foot.getStyleClass().add("setup-foot");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bottom = new HBox(10, foot, gap);
        bottom.setAlignment(Pos.CENTER_LEFT);
        if (buttons != null) {
            for (Button b : buttons) {
                if (b != null) bottom.getChildren().add(b);
            }
        }
        content.getChildren().addAll(grow, bottom);
        content.setPadding(new Insets(26, 32, 22, 32));
        return content;
    }

    private static ImageView icon(double size) {
        ImageView icon = new ImageView();
        try (InputStream in = NotesApp.class.getResourceAsStream("icon.png")) {
            if (in != null) icon.setImage(new Image(in, size, size, true, true));
        } catch (Exception ignored) {
            // No icon.
        }
        return icon;
    }

    private Node message(String title, String text, Runnable retry, boolean back) {
        List<Button> buttons = new ArrayList<>();
        if (back) buttons.add(button("Back", false, this::home));
        if (retry != null) buttons.add(button("Retry", true, retry));
        buttons.add(button("Close", retry == null, Platform::exit));
        return screen(title, text, null, buttons.toArray(new Button[0]));
    }

    private Node message(String title, String text, boolean back) {
        return message(title, text, null, back);
    }

    private Node busy(String text) {
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.getStyleClass().add("setup-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        return screen("One moment", text, bar, null, null);
    }

    private Node progressScreen(String title, Label status, ProgressBar bar) {
        bar.getStyleClass().add("setup-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        status.setStyle("-fx-font-size: 12.5px;");
        return screen(title, "This takes a moment. Keep this window open.", new VBox(10, bar, status), null, null);
    }

    private static Button button(String text, boolean primary, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().setAll(primary ? "filled-button" : "tonal-button", "compact");
        b.setOnAction(e -> action.run());
        return b;
    }
}
