package org.flexstudios.notes.plus;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** The rows of a list widget ({@link ChecklistWidget}): one per item, pictures left out. */
public class ChecklistWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Rows(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
    }

    private static final class Rows implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        /** The items shown, with each one's place in the list (pictures are skipped). */
        private final List<NoteData.TodoItem> items = new ArrayList<>();
        private final List<Integer> places = new ArrayList<>();

        Rows(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override
        public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            // Called on a binder thread: the database is fine here.
            items.clear();
            places.clear();
            NoteEntity n = ChecklistWidget.listOf(context, widgetId);
            if (n == null) return;
            List<NoteData.TodoItem> all = NoteData.todos(n.getData());
            Due.rollover(all, Due.today());
            for (int i = 0; i < all.size(); i++) {
                NoteData.TodoItem item = all.get(i);
                if (item.picture() != null || item.text.trim().isEmpty()) continue;
                items.add(item);
                places.add(i);
            }
        }

        @Override
        public void onDestroy() {
            items.clear();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_checklist_item);
            if (position >= items.size()) return row;
            NoteData.TodoItem item = items.get(position);
            SpannableString text = new SpannableString(item.text.trim());
            if (item.done) text.setSpan(new StrikethroughSpan(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setTextViewText(R.id.widgetItemText, text);
            row.setTextColor(R.id.widgetItemText, ContextCompat.getColor(context, item.done ? R.color.text_secondary : R.color.text_body));
            row.setImageViewResource(R.id.widgetItemBox, item.done ? R.drawable.widget_box_on : R.drawable.widget_box_off);
            row.setContentDescription(R.id.widgetItemBox, item.done ? "Done: tap to untick" : "Tap to tick");
            long today = Due.today();
            String due = Due.status(item, today) == Due.NONE ? "" : Due.pill(item, today);
            row.setTextViewText(R.id.widgetItemDue, due);
            row.setViewVisibility(R.id.widgetItemDue, due.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            int s = Due.status(item, today);
            row.setTextColor(R.id.widgetItemDue, ContextCompat.getColor(context, s == Due.OVERDUE ? R.color.destructive
                    : s == Due.TODAY ? R.color.tile_orange : R.color.text_secondary));
            row.setOnClickFillInIntent(R.id.widgetItemRow, new Intent().putExtra(ChecklistWidget.EXTRA_ITEM, places.get(position)));
            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
