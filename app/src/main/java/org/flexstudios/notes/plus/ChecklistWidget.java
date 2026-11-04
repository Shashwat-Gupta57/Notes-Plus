package org.flexstudios.notes.plus;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.TaskStackBuilder;

import java.util.List;

/**
 * A list on the home screen: its name, how much is done, and its items, each ticked with a tap
 * (saved like a tick in the app, then synced). The list is chosen when the widget is added
 * ({@link ChecklistWidgetConfig}) and kept by its uuid. A list that is locked, hidden or gone shows
 * only "This list isn't available", never its words. Notes only: nothing here touches the hidden spaces.
 */
public class ChecklistWidget extends AppWidgetProvider {

    static final String ACTION_TICK = "org.flexstudios.notes.plus.widget.TICK";
    static final String EXTRA_ITEM = "item";
    private static final String PREF = "widget_list_";

    static void choose(Context context, int widgetId, String uuid) {
        SecurityHelper.getAppPrefs(context).edit().putString(PREF + widgetId, uuid).apply();
    }

    static String chosen(Context context, int widgetId) {
        String uuid = SecurityHelper.getAppPrefs(context).getString(PREF + widgetId, null);
        if (uuid != null) return uuid;
        // A widget placed from inside the app ("Add to home screen"): its list was named before it came.
        // Only for two minutes: a request the launcher's dialog cancelled mustn't name a later widget's list.
        String pending = SecurityHelper.getAppPrefs(context).getString(PENDING, null);
        if (pending == null || pending.indexOf('|') < 0) return null;
        String uuid2 = pending.substring(0, pending.indexOf('|'));
        long at;
        try {
            at = Long.parseLong(pending.substring(pending.indexOf('|') + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        SecurityHelper.getAppPrefs(context).edit().remove(PENDING).apply();
        if (System.currentTimeMillis() - at > 120_000) return null;
        SecurityHelper.getAppPrefs(context).edit().putString(PREF + widgetId, uuid2).apply();
        return uuid2;
    }

    private static final String PENDING = "widget_pending_list";

    /**
     * Asks the launcher to put a widget for this list on the home screen (it shows its own "Add"
     * confirmation). Some launchers' widget pickers don't list other apps' widgets at all; this
     * works there too. False when the launcher can't.
     */
    static boolean pin(Context context, String uuid) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return false;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (!manager.isRequestPinAppWidgetSupported()) return false;
        SecurityHelper.getAppPrefs(context).edit().putString(PENDING, uuid + "|" + System.currentTimeMillis()).apply();
        // Mutable: the launcher adds the new widget's id to it.
        PendingIntent placed = PendingIntent.getBroadcast(context, uuid.hashCode(),
                new Intent(context, Pinned.class).putExtra("uuid", uuid),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        return manager.requestPinAppWidget(new ComponentName(context, ChecklistWidget.class), null, placed);
    }

    /** The launcher placed a pinned list widget: it shows that list. */
    public static final class Pinned extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            String uuid = intent.getStringExtra("uuid");
            if (id < 0 || uuid == null) return;
            SecurityHelper.getAppPrefs(context).edit().putString(PREF + id, uuid).remove(PENDING).apply();
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            PendingResult pending = goAsync();
            AppDatabase.IO.execute(() -> {
                try {
                    update(context, manager, id);
                    manager.notifyAppWidgetViewDataChanged(new int[]{id}, R.id.widgetItems);
                } finally {
                    pending.finish();
                }
            });
        }
    }

    /** The note a widget shows, if it can still show it. Off the main thread. */
    static NoteEntity listOf(Context context, int widgetId) {
        String uuid = chosen(context, widgetId);
        if (uuid == null) return null;
        NoteEntity n = AppDatabase.getInstance(context).noteDao().getByUuid(uuid);
        if (n == null || n.isDeleted() || n.isHidden() || !NoteData.LIST.equals(n.getType()) || NoteLock.isLocked(n.getData())) return null;
        return n;
    }

    /** Every list widget shows what's stored now (after a save here, or a sync). */
    static void refresh(Context context) {
        Reminders.schedule(context); // Lists changed: so may the next reminder.
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ChecklistWidget.class));
        if (ids.length == 0) return;
        AppDatabase.IO.execute(() -> {
            for (int id : ids) update(context, manager, id);
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetItems);
        });
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        AppDatabase.IO.execute(() -> {
            for (int id : ids) update(context, manager, id);
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetItems);
        });
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) SecurityHelper.getAppPrefs(context).edit().remove(PREF + id).apply();
    }

    /** The header and the list's frame. Off the main thread. */
    static void update(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_checklist);
        NoteEntity n = listOf(context, id);
        Intent service = new Intent(context, ChecklistWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        service.setData(Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))); // One adapter per widget.
        views.setRemoteAdapter(R.id.widgetItems, service);
        views.setEmptyView(R.id.widgetItems, R.id.widgetEmpty);
        if (n == null) {
            views.setTextViewText(R.id.widgetTitle, chosen(context, id) == null ? "Choose a list" : "List");
            views.setTextViewText(R.id.widgetProgress, "");
            views.setTextViewText(R.id.widgetEmpty, chosen(context, id) == null ? "Long-press to choose a list" : "This list isn't available");
            views.setOnClickPendingIntent(R.id.widgetHeader, openApp(context, null));
        } else {
            List<NoteData.TodoItem> items = NoteData.todos(n.getData());
            Due.rollover(items, Due.today());
            int done = 0, total = 0;
            for (NoteData.TodoItem item : items) {
                if (item.picture() != null || item.text.trim().isEmpty()) continue;
                total++;
                if (item.done) done++;
            }
            String title = n.getTitle() == null || n.getTitle().trim().isEmpty() ? "List" : n.getTitle().trim();
            views.setTextViewText(R.id.widgetTitle, title);
            views.setTextViewText(R.id.widgetProgress, total == 0 ? "" : done == total ? "All done" : done + " of " + total);
            views.setTextViewText(R.id.widgetEmpty, "Nothing on this list yet");
            views.setOnClickPendingIntent(R.id.widgetHeader, openApp(context, n));
        }
        // Each row fills in which item; the tick comes back here.
        Intent tick = new Intent(context, Tick.class).setAction(ACTION_TICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        views.setPendingIntentTemplate(R.id.widgetItems, PendingIntent.getBroadcast(context, id, tick,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        manager.updateAppWidget(id, views);
    }

    /** The list in its editor, over the notes (Back goes to them); or the notes. */
    private static PendingIntent openApp(Context context, NoteEntity n) {
        TaskStackBuilder stack = TaskStackBuilder.create(context).addNextIntent(new Intent(context, MainActivity.class));
        if (n != null) stack.addNextIntent(NoteData.editor(context, NoteData.LIST, n.getId()));
        return stack.getPendingIntent(n == null ? 0 : n.getId(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * A tick from a widget row. Its own receiver, not exported: the widget provider itself has to be
     * exported (some launchers list only exported widgets), and no other app may tick a list.
     */
    public static final class Tick extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_TICK.equals(intent.getAction())) return;
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            int index = intent.getIntExtra(EXTRA_ITEM, -1);
            PendingResult pending = goAsync();
            AppDatabase.IO.execute(() -> {
                try {
                    tick(context, id, index);
                } finally {
                    pending.finish();
                }
            });
        }
    }

    /** Ticks or unticks one item, as the app would (a repeating item moves on to its next day). */
    private static void tick(Context context, int widgetId, int index) {
        NoteEntity n = listOf(context, widgetId);
        if (n == null || index < 0) return;
        if (NoteSync.OPEN.contains(n.getId())) {
            // Open in the app: its editor holds the list, and would write over a tick from here.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "That list is open in Notes+: tick it there", Toast.LENGTH_SHORT).show());
            return;
        }
        List<NoteData.TodoItem> items = NoteData.todos(n.getData());
        long today = Due.today();
        Due.rollover(items, today);
        if (index >= items.size() || items.get(index).picture() != null) return;
        NoteData.TodoItem item = items.get(index);
        Due.tick(item, !item.done, today);
        StringBuilder words = new StringBuilder();
        for (NoteData.TodoItem it : items) if (it.picture() == null && !it.text.trim().isEmpty()) words.append(it.text.trim()).append('\n');
        AppDatabase.getInstance(context).noteDao().saveContent(n.getId(), n.getTitle(), words.toString().trim(),
                NoteData.todosJson(items, NoteData.wraps(n.getData())), n.getColor(), n.isPinned(), System.currentTimeMillis());
        NoteSync.schedule(context);
        refresh(context);
    }
}
