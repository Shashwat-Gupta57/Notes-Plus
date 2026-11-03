package org.flexstudios.notes.plus;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Adding a list widget: which list it shows. Lists only, never locked or hidden ones, newest first.
 * Cancelling removes the widget again (the launcher does that on RESULT_CANCELED).
 */
public class ChecklistWidgetConfig extends AppCompatActivity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        if (getIntent().getExtras() != null) {
            widgetId = getIntent().getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        AppDatabase.IO.execute(() -> {
            List<NoteEntity> lists = new ArrayList<>();
            for (NoteEntity n : AppDatabase.getInstance(this).noteDao().getAllDirect()) {
                if (NoteData.LIST.equals(n.getType()) && !n.isHidden() && !n.isTemplate() && !NoteLock.isLocked(n.getData())) lists.add(n);
            }
            lists.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
            runOnUiThread(() -> ask(lists));
        });
    }

    private void ask(List<NoteEntity> lists) {
        if (isFinishing()) return;
        if (lists.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No lists yet")
                    .setMessage("Make a list in Notes+, then add this widget again.")
                    .setPositiveButton("OK", null)
                    .setOnDismissListener(d -> finish())
                    .show();
            return;
        }
        String[] names = new String[lists.size()];
        for (int i = 0; i < lists.size(); i++) {
            String t = lists.get(i).getTitle();
            names[i] = t == null || t.trim().isEmpty() ? "Untitled list" : t.trim();
        }
        boolean[] chose = {false};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Show which list?")
                .setItems(names, (d, which) -> {
                    chose[0] = true;
                    ChecklistWidget.choose(this, widgetId, lists.get(which).getUuid());
                    AppWidgetManager manager = AppWidgetManager.getInstance(this);
                    AppDatabase.IO.execute(() -> {
                        ChecklistWidget.update(this, manager, widgetId);
                        manager.notifyAppWidgetViewDataChanged(new int[]{widgetId}, R.id.widgetItems);
                        runOnUiThread(() -> {
                            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
                            finish();
                        });
                    });
                })
                .setOnDismissListener(d -> {
                    if (!chose[0]) finish();
                })
                .show();
    }
}
