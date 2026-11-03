package org.flexstudios.notes.plus;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import androidx.core.app.TaskStackBuilder;

/** "New" on the home screen: a note, a list, today's journal or a recording, one tap each. */
public class NewNoteWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_new_note);
            views.setOnClickPendingIntent(R.id.widgetNewText, start(context, NoteData.TEXT, 1));
            views.setOnClickPendingIntent(R.id.widgetNewList, start(context, NoteData.LIST, 2));
            views.setOnClickPendingIntent(R.id.widgetNewJournal, start(context, NoteData.JOURNAL, 3));
            views.setOnClickPendingIntent(R.id.widgetNewAudio, start(context, NoteData.AUDIO, 4));
            manager.updateAppWidget(id, views);
        }
    }

    /** A new note of {@code type} in its editor, over the notes (Back goes to them). */
    private static PendingIntent start(Context context, String type, int code) {
        return TaskStackBuilder.create(context)
                .addNextIntent(new Intent(context, MainActivity.class))
                .addNextIntent(NoteData.editor(context, type, -1))
                .getPendingIntent(100 + code, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
