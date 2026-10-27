package org.flexstudios.notes.plus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity displaying in-app session logs.
 * Allows inspecting the latest 10 sessions (.log and .err) and saving logs to a local file.
 */
public class LogsActivity extends AppCompatActivity {

    private Spinner spinnerSessions;
    private MaterialButtonToggleGroup toggleLogType;
    private TextView txtLogInfo, txtLogContent;

    private List<AppLog.Session> sessions = new ArrayList<>();
    private AppLog.Session selectedSession;
    private boolean showErrorsOnly = false;
    private File activeFile;

    private final ActivityResultLauncher<Intent> saveFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && activeFile != null) {
                        saveLogToUri(uri, activeFile);
                    }
                }
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        Toolbar toolbar = findViewById(R.id.toolbarLogs);
        toolbar.setNavigationOnClickListener(v -> finish());

        spinnerSessions = findViewById(R.id.spinnerSessions);
        toggleLogType = findViewById(R.id.toggleLogType);
        txtLogInfo = findViewById(R.id.txtLogInfo);
        txtLogContent = findViewById(R.id.txtLogContent);

        findViewById(R.id.btnCopy).setOnClickListener(v -> copyToClipboard());
        findViewById(R.id.btnShare).setOnClickListener(v -> shareLog());
        findViewById(R.id.btnSave).setOnClickListener(v -> promptSaveFile());

        toggleLogType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                showErrorsOnly = (checkedId == R.id.btnTypeErr);
                updateLogView();
            }
        });

        loadSessions();
    }

    private void loadSessions() {
        sessions = AppLog.getSessions(this);
        if (sessions.isEmpty()) {
            txtLogInfo.setText("No sessions");
            txtLogContent.setText("No session logs recorded yet.");
            return;
        }

        List<String> titles = new ArrayList<>();
        for (AppLog.Session s : sessions) {
            titles.add(s.title());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, titles);
        spinnerSessions.setAdapter(adapter);

        spinnerSessions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < sessions.size()) {
                    selectedSession = sessions.get(position);
                    updateLogView();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        selectedSession = sessions.get(0);
        updateLogView();
    }

    private void updateLogView() {
        if (selectedSession == null) return;
        activeFile = showErrorsOnly ? selectedSession.errFile() : selectedSession.logFile();

        if (activeFile != null && activeFile.exists()) {
            long size = activeFile.length();
            txtLogInfo.setText(formatBytes(size));
            String content = AppLog.readFile(activeFile);
            txtLogContent.setText(content);
        } else {
            txtLogInfo.setText("0 B");
            txtLogContent.setText(showErrorsOnly ? "No error events recorded in this session." : "No log data available.");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void copyToClipboard() {
        CharSequence text = txtLogContent.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Notes+ Log", text);
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "Copied log to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void shareLog() {
        CharSequence text = txtLogContent.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Notes+ Log (" + (activeFile != null ? activeFile.getName() : "session") + ")");
        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(intent, "Share log via"));
    }

    private void promptSaveFile() {
        if (activeFile == null || !activeFile.exists()) {
            Toast.makeText(this, "No log file to save", Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = activeFile.getName();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {
            saveFileLauncher.launch(intent);
        } catch (Exception e) {
            // Fallback to saving directly to Downloads directory if document picker unavailable
            saveDirectToDownloads(activeFile);
        }
    }

    private void saveLogToUri(Uri uri, File source) {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot open destination stream");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            Toast.makeText(this, "Log saved to file successfully", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.e("LogsActivity", "Failed to save log to URI: " + uri, e);
            Toast.makeText(this, "Failed to save file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveDirectToDownloads(File source) {
        try {
            File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dlDir.exists()) dlDir.mkdirs();
            File dest = new File(dlDir, source.getName());
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            Toast.makeText(this, "Saved to Downloads: " + dest.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.e("LogsActivity", "Failed to save directly to Downloads", e);
            Toast.makeText(this, "Could not save to Downloads: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
