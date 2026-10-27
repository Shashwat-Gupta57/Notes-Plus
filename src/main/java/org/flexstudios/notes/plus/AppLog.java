package org.flexstudios.notes.plus;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Robust session logger for Notes+ and Notes+ Setup.
 *
 * - Generates .log (info/lifecycle/unified) and .err (errors/exceptions) in the app directory
 *   (Program Files\Notes+\logs in prod, <project>\logs in dev, %APPDATA%\Notes+\logs fallback).
 * - Tracks and retains the latest 10 sessions, automatically purging older sessions.
 * - Handles multi-window and multi-process sessions with process IDs, timestamps, and window lifecycle tracking.
 * - Intercepts System.out, System.err, and uncaught exceptions with automatic thread and timestamp formatting.
 */
public final class AppLog {

    private static final int MAX_SESSIONS = 10;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static Path logDir;
    private static Path currentLogFile;
    private static Path currentErrFile;
    private static PrintWriter logWriter;
    private static PrintWriter errWriter;
    private static boolean initialized;
    private static String appIdentifier = "app";
    private static long startTime;

    private AppLog() {}

    public static synchronized void init(String appName) {
        if (initialized) return;
        initialized = true;
        appIdentifier = appName;
        startTime = System.currentTimeMillis();

        try {
            logDir = resolveLogDirectory();
            cleanOldSessions(logDir, appName);

            String timestamp = LocalDateTime.now().format(FILE_FMT);
            long pid = ProcessHandle.current().pid();
            String prefix = appName + "-" + timestamp + "-p" + pid;

            currentLogFile = logDir.resolve(prefix + ".log");
            currentErrFile = logDir.resolve(prefix + ".err");

            OutputStream logOut = new BufferedOutputStream(Files.newOutputStream(currentLogFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
            OutputStream errOut = new BufferedOutputStream(Files.newOutputStream(currentErrFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE));

            logWriter = new PrintWriter(new OutputStreamWriter(logOut, StandardCharsets.UTF_8), true);
            errWriter = new PrintWriter(new OutputStreamWriter(errOut, StandardCharsets.UTF_8), true);

            // Print diagnostic header
            writeHeader();

            // Hook System.out and System.err
            hookSystemStreams();

            // Hook Uncaught Exceptions across all threads
            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
                error("Uncaught exception in thread [" + thread.getName() + "]", ex);
            });

            // JVM Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                long duration = (System.currentTimeMillis() - startTime) / 1000L;
                info("Application shutting down cleanly. Session uptime: " + duration + "s");
                flush();
            }, "applog-shutdown"));

        } catch (Exception e) {
            System.err.println("Failed to initialize AppLog: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Path getLogDirectory() {
        return logDir;
    }

    private static Path resolveLogDirectory() {
        Path base = null;
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (!cmd.isEmpty()) {
            try {
                Path exeParent = Path.of(cmd).getParent();
                if (exeParent != null && (Files.exists(exeParent.resolve("Notes+.exe")) || Files.exists(exeParent.resolve("runtime")))) {
                    base = exeParent;
                }
            } catch (Exception ignored) {}
        }
        if (base == null) {
            base = Path.of(System.getProperty("user.dir"));
        }

        Path logs = base.resolve("logs");
        try {
            Files.createDirectories(logs);
            Path probe = logs.resolve(".probe-" + System.currentTimeMillis());
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return logs;
        } catch (Exception e) {
            // Protected directory (e.g. Program Files without write permission): fallback to AppData
            String appData = System.getenv("APPDATA");
            Path fallback = Path.of(appData != null && !appData.isBlank() ? appData : System.getProperty("user.home"), "Notes+", "logs");
            try {
                Files.createDirectories(fallback);
            } catch (Exception ignored) {}
            return fallback;
        }
    }

    private static void cleanOldSessions(Path dir, String appName) {
        try {
            if (!Files.isDirectory(dir)) return;
            // Identify all session files matching <appName>-<timestamp>-p<pid>.(log|err)
            Pattern pattern = Pattern.compile("^" + Pattern.quote(appName) + "-(\\d{8}-\\d{6})-p(\\d+)\\.(log|err)$");
            Map<String, List<Path>> sessions = new TreeMap<>(Collections.reverseOrder());

            try (Stream<Path> stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    Matcher m = pattern.matcher(name);
                    if (m.matches()) {
                        String sessionKey = m.group(1) + "-p" + m.group(2);
                        sessions.computeIfAbsent(sessionKey, k -> new ArrayList<>()).add(p);
                    }
                }
            }

            // Keep the latest (MAX_SESSIONS - 1) existing sessions so that adding the current one makes exactly 10
            int keep = MAX_SESSIONS - 1;
            int count = 0;
            for (Map.Entry<String, List<Path>> entry : sessions.entrySet()) {
                count++;
                if (count > keep) {
                    for (Path f : entry.getValue()) {
                        try {
                            Files.deleteIfExists(f);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static String getVersion() {
        try (InputStream in = AppLog.class.getResourceAsStream("app.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("version", "");
            return v.isEmpty() || v.startsWith("${") ? "15.2" : v;
        } catch (Exception e) {
            return "15.2";
        }
    }

    private static void writeHeader() {
        String div = "=".repeat(80);
        String ver = getVersion();
        logWriter.println(div);
        logWriter.println("Notes+ Desktop Session Log: " + appIdentifier.toUpperCase(Locale.ROOT));
        logWriter.println("Session Start   : " + LocalDateTime.now().format(TIME_FMT));
        logWriter.println("Process ID (PID): " + ProcessHandle.current().pid());
        logWriter.println("Version         : " + ver);
        logWriter.println("Working Dir     : " + System.getProperty("user.dir"));
        logWriter.println("Log Directory   : " + logDir.toAbsolutePath());
        logWriter.println("Java Runtime    : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        logWriter.println("Operating System: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        logWriter.println("Memory (MB)     : Max=" + maxMem + ", Total=" + totalMem + ", Free=" + freeMem);
        logWriter.println(div);

        errWriter.println(div);
        errWriter.println("Notes+ Desktop Error Log: " + appIdentifier.toUpperCase(Locale.ROOT));
        errWriter.println("Session Start   : " + LocalDateTime.now().format(TIME_FMT));
        errWriter.println("Process ID (PID): " + ProcessHandle.current().pid());
        errWriter.println("Version         : " + ver);
        errWriter.println(div);
    }

    private static void hookSystemStreams() {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;

        System.setOut(new PrintStream(new TeeStream(origOut, logWriter, false), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new TeeStream(origErr, errWriter, true), true, StandardCharsets.UTF_8));
    }

    private static class TeeStream extends OutputStream {
        private final PrintStream console;
        private final PrintWriter sessionWriter;
        private final boolean isErr;
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();

        TeeStream(PrintStream console, PrintWriter sessionWriter, boolean isErr) {
            this.console = console;
            this.sessionWriter = sessionWriter;
            this.isErr = isErr;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            console.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                lineBuf.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            console.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    flushLine();
                } else if (b[i] != '\r') {
                    lineBuf.write(b[i]);
                }
            }
        }

        private void flushLine() {
            String text = lineBuf.toString(StandardCharsets.UTF_8);
            lineBuf.reset();
            String timestamp = LocalDateTime.now().format(TIME_FMT);
            String thread = Thread.currentThread().getName();
            String prefix = "[" + timestamp + "] [" + thread + "] " + (isErr ? "[STDERR] " : "");

            sessionWriter.println(prefix + text);
            if (isErr && logWriter != null) {
                logWriter.println(prefix + text);
            }
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            sessionWriter.flush();
            if (isErr && logWriter != null) logWriter.flush();
        }
    }

    public static synchronized void info(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String thread = Thread.currentThread().getName();
        String line = "[" + timestamp + "] [" + thread + "] [INFO] " + message;
        if (logWriter != null) {
            logWriter.println(line);
        } else {
            System.out.println(line);
        }
    }

    public static synchronized void warn(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String thread = Thread.currentThread().getName();
        String line = "[" + timestamp + "] [" + thread + "] [WARN] " + message;
        if (logWriter != null) {
            logWriter.println(line);
        } else {
            System.out.println(line);
        }
    }

    public static synchronized void error(String message, Throwable t) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String thread = Thread.currentThread().getName();
        String line = "[" + timestamp + "] [" + thread + "] [ERROR] " + message;
        if (errWriter != null) {
            errWriter.println(line);
            if (t != null) t.printStackTrace(errWriter);
        }
        if (logWriter != null) {
            logWriter.println(line);
            if (t != null) t.printStackTrace(logWriter);
        }
        if (errWriter == null && logWriter == null) {
            System.err.println(line);
            if (t != null) t.printStackTrace();
        }
    }

    // Multi-window session tracking
    public static void windowOpened(int windowId, String title, int totalActive) {
        info("[WINDOW-OPEN] Window #" + windowId + " (\"" + title + "\") opened. Total active windows: " + totalActive);
    }

    public static void windowFocused(int windowId, String title) {
        info("[WINDOW-FOCUS] Window #" + windowId + " (\"" + title + "\") focused");
    }

    public static void windowClosed(int windowId, int totalActive) {
        info("[WINDOW-CLOSE] Window #" + windowId + " closed. Remaining active windows: " + totalActive);
    }

    public static synchronized void flush() {
        if (logWriter != null) logWriter.flush();
        if (errWriter != null) errWriter.flush();
    }
}
