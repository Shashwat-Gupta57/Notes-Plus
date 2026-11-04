package org.flexstudios.notes.plus;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Reminders for list items with a day: a notification at the item's time (9:00 on its day when it
 * has none), even with the app closed. One alarm is set, for the soonest reminder to come; when it
 * goes off, everything due by then is shown and the next alarm is set. Rescheduled after every list
 * save, sync pull and widget tick ({@link ChecklistWidget#refresh}), when the app comes forward, and
 * after a reboot or a change of clock or time zone.
 *
 * Only my own lists that are on the grid: never a locked, hidden, archived or template list (a
 * notification would show what they hold), never a deleted one.
 */
final class Reminders {

    private Reminders() {}

    static final String CHANNEL = "reminders";
    private static final String ACTION_FIRE = "org.flexstudios.notes.plus.REMIND";
    /** Up to when reminders have been shown, so none shows twice and none from before is shown late. */
    private static final String PREF_SHOWN_UNTIL = "reminders_shown_until";
    /** A reminder found up to this late (a phone asleep, an alarm held back) is still shown. */
    private static final long LATE_OK = 12 * 60 * 60 * 1000L;

    private static final class Moment {
        long at;
        NoteEntity note;
        NoteData.TodoItem item;
        int index;
    }

    /** When an item reminds, on this device's clock. */
    static long at(NoteData.TodoItem item) {
        int[] c = Due.civil(Due.day(item.due));
        int minute = Due.remindMinute(item);
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(c[0], c[1] - 1, c[2], minute / 60, minute % 60, 0);
        return cal.getTimeInMillis();
    }

    private static List<Moment> moments(Context context) {
        List<Moment> out = new ArrayList<>();
        for (NoteEntity n : AppDatabase.getInstance(context).noteDao().getAllDirect()) {
            if (!NoteData.LIST.equals(n.getType()) || n.isHidden() || n.isArchived() || n.isTemplate()
                    || NoteLock.isLocked(n.getData())) continue;
            List<NoteData.TodoItem> items = NoteData.todos(n.getData());
            for (int i = 0; i < items.size(); i++) {
                NoteData.TodoItem item = items.get(i);
                if (!Due.remindable(item)) continue;
                Moment m = new Moment();
                m.at = at(item);
                m.note = n;
                m.item = item;
                m.index = i;
                out.add(m);
            }
        }
        return out;
    }

    /** Sets the alarm for the next reminder (off the main thread). */
    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AppDatabase.IO.execute(() -> scheduleNow(app));
    }

    private static SharedPreferences prefs(Context context) {
        return SecurityHelper.getAppPrefs(context);
    }

    private static void scheduleNow(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (!prefs.contains(PREF_SHOWN_UNTIL)) prefs.edit().putLong(PREF_SHOWN_UNTIL, now).apply(); // Nothing from before.
        long shownUntil = prefs.getLong(PREF_SHOWN_UNTIL, now);
        long next = Long.MAX_VALUE;
        for (Moment m : moments(context)) if (m.at > shownUntil && m.at < next) next = m.at;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent fire = PendingIntent.getBroadcast(context, 0,
                new Intent(context, Receiver.class).setAction(ACTION_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (next == Long.MAX_VALUE) {
            alarms.cancel(fire);
            return;
        }
        long when = Math.max(next, now + 1000);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fire); // Within a few minutes.
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fire);
            }
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fire);
        }
    }

    /** Shows every reminder due by now that hasn't been shown, then sets the next alarm. */
    private static void fire(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        long shownUntil = prefs.getLong(PREF_SHOWN_UNTIL, now);
        for (Moment m : moments(context)) {
            if (m.at > shownUntil && m.at <= now + 30_000 && now - m.at < LATE_OK) show(context, m);
        }
        prefs.edit().putLong(PREF_SHOWN_UNTIL, Math.max(shownUntil, now + 30_000)).apply();
        scheduleNow(context);
    }

    private static void show(Context context, Moment m) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("List items when they're due");
            manager.createNotificationChannel(channel);
        }
        String list = m.note.getTitle() == null || m.note.getTitle().trim().isEmpty() ? "List" : m.note.getTitle().trim();
        String time = Due.time(m.item.time);
        String when = time != null ? "Due " + time : "Due today";
        PendingIntent open = TaskStackBuilder.create(context)
                .addNextIntent(new Intent(context, MainActivity.class))
                .addNextIntent(NoteData.editor(context, NoteData.LIST, m.note.getId()))
                .getPendingIntent(m.note.getId(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_clock)
                .setColor(ContextCompat.getColor(context, R.color.brand_yellow))
                .setContentTitle(NoteLinks.shown(m.item.text.trim()))
                .setContentText(list + "  ·  " + when)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open);
        try {
            NotificationManagerCompat.from(context).notify("remind_" + m.note.getId(), m.index, b.build());
        } catch (SecurityException ignored) {
            // Notifications switched off: nothing to show.
        }
    }

    /** Asks for permission to notify (Android 13+), once a day has been given to an item. */
    static void askIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = prefs(activity);
        if (prefs.getBoolean("reminders_asked", false)) return; // Asked once; Android remembers a "no".
        prefs.edit().putBoolean("reminders_asked", true).apply();
        activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);
    }

    /** The alarm going off, and the moments the alarm has to be set again (boot, clock, update). */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            PendingResult pending = goAsync();
            Context app = context.getApplicationContext();
            boolean firing = ACTION_FIRE.equals(intent.getAction());
            AppDatabase.IO.execute(() -> {
                try {
                    if (firing) fire(app);
                    else scheduleNow(app);
                } finally {
                    pending.finish();
                }
            });
        }
    }
}
