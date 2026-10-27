package org.flexstudios.notes.plus;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app session logger for Notes+ Android.
 *
 * - Stores .log (lifecycle, info, debug, general) and .err (warnings, errors, uncaught exceptions).
 * - Preserves exactly the latest 10 sessions, automatically purging older sessions.
 * - Tracks application lifecycle, activity transitions, and device diagnostics.
 * - Captures uncaught exceptions without interfering with standard crash handling.
 * - Keeps all logs strictly local for privacy and DOS/abuse immunity.
 */
public final class AppLog {

    private static final String TAG = "NotesPlus";
    private static final int MAX_SESSIONS = 10;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_FMT = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private static final Pattern SESSION_PATTERN = Pattern.compile("^notesplus-(\\d{8}-\\d{6})-p(\\d+)\\.(log|err)$");

    private static File logDirectory;
    private static File currentLogFile;
    private static File currentErrFile;
    private static PrintWriter logWriter;
    private static PrintWriter errWriter;
    private static boolean initialized;
    private static String currentSessionKey = "";

    public static final class Session {
        private final String key;
        private final String title;
        private final File logFile;
        private final File errFile;
        private final long timestamp;

        public Session(String key, String title, File logFile, File errFile, long timestamp) {
            this.key = key;
            this.title = title;
            this.logFile = logFile;
            this.errFile = errFile;
            this.timestamp = timestamp;
        }

        public String key() { return key; }
        public String title() { return title; }
        public File logFile() { return logFile; }
        public File errFile() { return errFile; }
        public long timestamp() { return timestamp; }
    }

    private AppLog() {}

    public static synchronized void init(Application app) {
        if (initialized) return;
        initialized = true;

        try {
            logDirectory = resolveLogDirectory(app);
            cleanOldSessions(logDirectory);

            Date now = new Date();
            String timestamp = FILE_FMT.format(now);
            int pid = Process.myPid();
            currentSessionKey = timestamp + "-p" + pid;

            currentLogFile = new File(logDirectory, "notesplus-" + currentSessionKey + ".log");
            currentErrFile = new File(logDirectory, "notesplus-" + currentSessionKey + ".err");

            logWriter = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(currentLogFile, true)), StandardCharsets.UTF_8), true);
            errWriter = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(currentErrFile, true)), StandardCharsets.UTF_8), true);

            writeHeader(app);
            hookUncaughtExceptions();
            hookActivityLifecycle(app);

            i("AppLog", "Session started: " + currentSessionKey);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AppLog", e);
        }
    }

    private static File resolveLogDirectory(Context context) {
        File ext = context.getExternalFilesDir("logs");
        if (ext != null && (ext.exists() || ext.mkdirs())) {
            return ext;
        }
        File internal = new File(context.getFilesDir(), "logs");
        if (!internal.exists()) internal.mkdirs();
        return internal;
    }

    private static void writeHeader(Application app) {
        String verName = "?";
        long verCode = 0;
        try {
            android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            verName = pi.versionName;
            verCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception ignored) {}

        String divider = "================================================================================";
        String header = divider + "\n"
                + " Notes+ Android Session Log\n"
                + " Version: " + verName + " (Build " + verCode + ")\n"
                + " Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.PRODUCT + ")\n"
                + " Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + " Session PID: " + Process.myPid() + "\n"
                + " Started: " + TIME_FMT.format(new Date()) + "\n"
                + divider;

        if (logWriter != null) logWriter.println(header);
        if (errWriter != null) errWriter.println(header);
    }

    private static void hookUncaughtExceptions() {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                e("CrashHandler", "FATAL EXCEPTION in thread [" + thread.getName() + "]", ex);
                flush();
            } catch (Throwable ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        });
    }

    private static void hookActivityLifecycle(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                lifecycle(activity.getClass().getSimpleName(), "created" + (savedInstanceState != null ? " (restored)" : ""));
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                lifecycle(activity.getClass().getSimpleName(), "started");
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                lifecycle(activity.getClass().getSimpleName(), "resumed");
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                lifecycle(activity.getClass().getSimpleName(), "paused");
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                lifecycle(activity.getClass().getSimpleName(), "stopped");
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                lifecycle(activity.getClass().getSimpleName(), "destroyed");
            }
        });
    }

    public static synchronized void d(String tag, String message) {
        Log.d(tag, message);
        write(logWriter, "DEBUG", tag, message);
    }

    public static synchronized void i(String tag, String message) {
        Log.i(tag, message);
        write(logWriter, "INFO ", tag, message);
    }

    public static synchronized void w(String tag, String message) {
        Log.w(tag, message);
        write(logWriter, "WARN ", tag, message);
        write(errWriter, "WARN ", tag, message);
    }

    public static synchronized void w(String tag, String message, Throwable tr) {
        Log.w(tag, message, tr);
        write(logWriter, "WARN ", tag, message + "\n" + stackTraceToString(tr));
        write(errWriter, "WARN ", tag, message + "\n" + stackTraceToString(tr));
    }

    public static synchronized void e(String tag, String message) {
        Log.e(tag, message);
        write(logWriter, "ERROR", tag, message);
        write(errWriter, "ERROR", tag, message);
    }

    public static synchronized void e(String tag, String message, Throwable tr) {
        Log.e(tag, message, tr);
        String details = message + (tr != null ? "\n" + stackTraceToString(tr) : "");
        write(logWriter, "ERROR", tag, details);
        write(errWriter, "ERROR", tag, details);
    }

    public static synchronized void lifecycle(String component, String event) {
        Log.d("Lifecycle", "[" + component + "] " + event);
        write(logWriter, "LIFEC", component, event);
    }

    private static void write(PrintWriter writer, String level, String tag, String msg) {
        if (writer == null) return;
        String time = TIME_FMT.format(new Date());
        writer.println(time + " [" + level + "] [" + tag + "] " + msg);
        writer.flush();
    }

    public static synchronized void flush() {
        if (logWriter != null) logWriter.flush();
        if (errWriter != null) errWriter.flush();
    }

    private static String stackTraceToString(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        tr.printStackTrace(pw);
        return sw.toString().trim();
    }

    private static void cleanOldSessions(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        Map<String, List<File>> sessions = new TreeMap<>(Collections.reverseOrder());
        for (File f : files) {
            Matcher m = SESSION_PATTERN.matcher(f.getName());
            if (m.matches()) {
                String key = m.group(1) + "-p" + m.group(2);
                sessions.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }
        }

        int keep = MAX_SESSIONS - 1;
        int count = 0;
        for (Map.Entry<String, List<File>> entry : sessions.entrySet()) {
            count++;
            if (count > keep) {
                for (File f : entry.getValue()) {
                    try {
                        f.delete();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public static List<Session> getSessions(Context context) {
        File dir = logDirectory != null ? logDirectory : resolveLogDirectory(context);
        File[] files = dir.listFiles();
        if (files == null) return Collections.emptyList();

        Map<String, File[]> sessionMap = new TreeMap<>(Collections.reverseOrder());
        for (File f : files) {
            Matcher m = SESSION_PATTERN.matcher(f.getName());
            if (m.matches()) {
                String key = m.group(1) + "-p" + m.group(2);
                File[] pair = sessionMap.computeIfAbsent(key, k -> new File[2]);
                if (f.getName().endsWith(".log")) pair[0] = f;
                else if (f.getName().endsWith(".err")) pair[1] = f;
            }
        }

        List<Session> result = new ArrayList<>();
        SimpleDateFormat displayFmt = new SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault());
        int index = 0;
        for (Map.Entry<String, File[]> entry : sessionMap.entrySet()) {
            index++;
            String key = entry.getKey();
            File logFile = entry.getValue()[0];
            File errFile = entry.getValue()[1];
            long lastMod = logFile != null ? logFile.lastModified() : (errFile != null ? errFile.lastModified() : 0);
            String title = (key.equals(currentSessionKey) ? "Current Session" : "Session " + index)
                    + " (" + displayFmt.format(new Date(lastMod)) + ")";
            result.add(new Session(key, title, logFile, errFile, lastMod));
        }
        return result;
    }

    public static String readFile(File file) {
        if (file == null || !file.exists()) return "Log file is empty or does not exist.";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            sb.append("\n[Error reading log: ").append(e.getMessage()).append("]");
        }
        return sb.toString();
    }
}
