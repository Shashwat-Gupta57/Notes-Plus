package org.flexstudios.notes.plus;

import java.time.LocalDate;
import java.util.List;

/**
 * Due dates and repeats on list items: the item's {@code due} (a calendar day, "YYYY-MM-DD", the
 * same on every device whatever its time zone), optional {@code time} ("HH:mm") and {@code repeat}:
 * {@code daily}, {@code weekdays}, {@code weekly:1,3,5} (1 = Monday … 7 = Sunday) or
 * {@code monthly:9} (that day of each month, the month's last day when it's shorter).
 *
 * Ticking a repeating item keeps it ticked and moves {@code due} to its next day; when that day
 * comes it shows unticked again ({@link #rollover}). Days are counted from 1970-01-01 in plain
 * arithmetic, so this file and the phone's and web's copies agree to the day.
 * Pictures between items never have a date.
 */
final class Due {

    private Due() {}

    static final int NONE = 0, DONE = 1, LATER = 2, TODAY = 3, OVERDUE = 4;
    static final String[] WEEKDAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    static final long NO_DAY = Long.MIN_VALUE;

    // ================================================================== days

    /** The day number of {@code y-m-d} (1970-01-01 = 0). */
    static long day(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** {y, m, d} of a day number. */
    static int[] civil(long z) {
        z += 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        int d = (int) (doy - (153 * mp + 2) / 5 + 1);
        int m = (int) (mp < 10 ? mp + 3 : mp - 9);
        return new int[]{(int) (yoe + era * 400 + (m <= 2 ? 1 : 0)), m, d};
    }

    /** The day number of "YYYY-MM-DD", or {@link #NO_DAY}. */
    static long day(String ymd) {
        if (ymd == null || !ymd.matches("\\d{4}-\\d{2}-\\d{2}")) return NO_DAY;
        int y = Integer.parseInt(ymd.substring(0, 4)), m = Integer.parseInt(ymd.substring(5, 7)), d = Integer.parseInt(ymd.substring(8));
        if (m < 1 || m > 12 || d < 1 || d > monthLength(y, m)) return NO_DAY;
        return day(y, m, d);
    }

    static String ymd(long day) {
        int[] c = civil(day);
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c[0], c[1], c[2]);
    }

    /** Today, on this device's calendar. */
    static long today() {
        LocalDate d = LocalDate.now();
        return day(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    /** 1 = Monday … 7 = Sunday. */
    static int weekday(long day) {
        return (int) Math.floorMod(day + 3, 7L) + 1;
    }

    static int monthLength(int y, int m) {
        return (int) (m == 12 ? day(y + 1, 1, 1) - day(y, 12, 1) : day(y, m + 1, 1) - day(y, m, 1));
    }

    // ================================================================== repeats

    /** The days of a weekly repeat (1–7), sorted; empty for any other repeat. */
    static boolean[] weekDays(String repeat) {
        boolean[] on = new boolean[8];
        if (repeat != null && repeat.startsWith("weekly:")) {
            for (String p : repeat.substring(7).split(",")) {
                try {
                    int d = Integer.parseInt(p.trim());
                    if (d >= 1 && d <= 7) on[d] = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return on;
    }

    static String weekly(boolean[] on) {
        StringBuilder s = new StringBuilder("weekly:");
        for (int d = 1; d <= 7; d++) if (on[d]) s.append(s.length() > 7 ? "," : "").append(d);
        return s.length() > 7 ? s.toString() : null;
    }

    /** The day of the month a monthly repeat falls on (1–31), or 0. */
    static int monthDay(String repeat) {
        if (repeat == null || !repeat.startsWith("monthly:")) return 0;
        try {
            int d = Integer.parseInt(repeat.substring(8).trim());
            return d >= 1 && d <= 31 ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean valid(String repeat) {
        return "daily".equals(repeat) || "weekdays".equals(repeat) || weekly(weekDays(repeat)) != null || monthDay(repeat) > 0;
    }

    /** Whether {@code day} is one of the repeat's days. */
    static boolean matches(String repeat, long day) {
        if ("daily".equals(repeat)) return true;
        if ("weekdays".equals(repeat)) return weekday(day) <= 5;
        if (repeat != null && repeat.startsWith("weekly:")) return weekDays(repeat)[weekday(day)];
        int md = monthDay(repeat);
        if (md == 0) return false;
        int[] c = civil(day);
        return c[2] == Math.min(md, monthLength(c[0], c[1]));
    }

    /** The repeat's first day on or after {@code from} ({@code from} itself for no repeat). */
    static long first(String repeat, long from) {
        if (!valid(repeat)) return from;
        for (long d = from; d < from + 400; d++) if (matches(repeat, d)) return d;
        return from;
    }

    // ================================================================== items

    static boolean dated(NoteData.TodoItem item) {
        return item.due != null && item.picture() == null && day(item.due) != NO_DAY; // Cheapest test first: most items have no day.
    }

    static boolean repeats(NoteData.TodoItem item) {
        return dated(item) && valid(item.repeat);
    }

    /**
     * Ticks or unticks an item. A repeating item stays ticked and moves on to its next day after
     * today (or after its day, when ticked ahead of it); unticked again, it gets that day back.
     */
    static void tick(NoteData.TodoItem item, boolean done, long today) {
        if (done && !item.done && repeats(item)) {
            item.undoDue = item.due;
            item.due = ymd(first(item.repeat, Math.max(today, day(item.due)) + 1));
        } else if (!done && item.done && item.undoDue != null) {
            item.due = item.undoDue;
        }
        if (!done || !repeats(item)) item.undoDue = null;
        item.done = done;
    }

    /** Unticks every repeating item whose next day has come. Whether any changed. */
    static boolean rollover(List<NoteData.TodoItem> items, long today) {
        boolean changed = false;
        for (NoteData.TodoItem item : items) {
            if (item.done && repeats(item) && day(item.due) <= today) {
                item.done = false;
                item.undoDue = null;
                changed = true;
            }
        }
        return changed;
    }

    static int status(NoteData.TodoItem item, long today) {
        if (!dated(item)) return NONE;
        long d = day(item.due);
        if (item.done) return repeats(item) ? LATER : DONE;
        return d < today ? OVERDUE : d == today ? TODAY : LATER;
    }

    /** How many unticked items are past their day. */
    static int overdue(List<NoteData.TodoItem> items, long today) {
        int n = 0;
        for (NoteData.TodoItem item : items) if (status(item, today) == OVERDUE) n++;
        return n;
    }

    /** The item a card's tag speaks for: the soonest still to do (overdue first), or null. */
    static NoteData.TodoItem urgent(List<NoteData.TodoItem> items, long today) {
        NoteData.TodoItem best = null;
        for (NoteData.TodoItem item : items) {
            int s = status(item, today);
            if (s < LATER) continue;
            if (best == null || day(item.due) < day(best.due)) best = item;
        }
        return best;
    }

    static boolean anyDated(List<NoteData.TodoItem> items) {
        for (NoteData.TodoItem item : items) if (dated(item)) return true;
        return false;
    }

    // ================================================================== edits from the picker

    /** Gives the item this day; a weekly repeat gains its weekday, a monthly one moves to its date. */
    static void setDay(NoteData.TodoItem item, long day) {
        item.due = ymd(day);
        item.undoDue = null;
        if (item.repeat != null && item.repeat.startsWith("weekly:")) {
            boolean[] on = weekDays(item.repeat);
            on[weekday(day)] = true;
            item.repeat = weekly(on);
        } else if (monthDay(item.repeat) > 0) {
            item.repeat = "monthly:" + civil(day)[2];
        }
    }

    /**
     * Sets how the item repeats ("daily", "weekdays", "weekly", "monthly" or null) around its day
     * (today when it has none), then moves the day onto the repeat if it isn't one of its days.
     */
    static void setRepeat(NoteData.TodoItem item, String kind, long today) {
        long d = dated(item) ? day(item.due) : today;
        if (kind == null) item.repeat = null;
        else if ("weekly".equals(kind)) {
            boolean[] on = new boolean[8];
            on[weekday(d)] = true;
            item.repeat = weekly(on);
        } else if ("monthly".equals(kind)) item.repeat = "monthly:" + civil(d)[2];
        else item.repeat = kind;
        item.undoDue = null;
        realign(item, d, today);
    }

    /** Turns one weekday of a weekly repeat on or off (never the last one). */
    static void toggleWeekday(NoteData.TodoItem item, int weekday, long today) {
        boolean[] on = weekDays(item.repeat);
        on[weekday] = !on[weekday];
        String next = weekly(on);
        if (next == null) return;
        item.repeat = next;
        item.undoDue = null;
        realign(item, dated(item) ? day(item.due) : today, today);
    }

    private static void realign(NoteData.TodoItem item, long d, long today) {
        if (item.repeat == null) {
            item.due = ymd(d);
            return;
        }
        // A day that is one of the repeat's stays (even overdue); any other moves to its next one.
        item.due = ymd(matches(item.repeat, d) ? d : first(item.repeat, Math.max(d, today)));
    }

    static void clear(NoteData.TodoItem item) {
        item.due = item.time = item.repeat = item.undoDue = null;
    }

    // ================================================================== words

    /** "Today", "Tomorrow", "Yesterday", "Fri" (this week), "9 Nov", "9 Nov 2027". */
    static String when(long day, long today) {
        long diff = day - today;
        if (diff == 0) return "Today";
        if (diff == 1) return "Tomorrow";
        if (diff == -1) return "Yesterday";
        if (diff > 1 && diff < 7) return WEEKDAYS[weekday(day) - 1];
        int[] c = civil(day), t = civil(today);
        return c[2] + " " + MONTHS[c[1] - 1] + (c[0] == t[0] ? "" : " " + c[0]);
    }

    /** "7:30" from "07:30". */
    static String time(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) return null;
        return (hhmm.charAt(0) == '0' ? hhmm.substring(1) : hhmm);
    }

    /** "Daily", "Weekdays", "Mon Wed Fri", "Monthly". */
    static String repeatShort(String repeat) {
        if ("daily".equals(repeat)) return "Daily";
        if ("weekdays".equals(repeat)) return "Weekdays";
        if (monthDay(repeat) > 0) return "Monthly";
        boolean[] on = weekDays(repeat);
        StringBuilder s = new StringBuilder();
        int n = 0;
        for (int d = 1; d <= 7; d++) if (on[d]) {
            s.append(s.length() > 0 ? " " : "").append(WEEKDAYS[d - 1]);
            n++;
        }
        return n == 7 ? "Daily" : n == 0 ? "" : s.toString();
    }

    /** "Every day", "Every weekday", "Every Mon, Wed, Fri", "Every month on the 9th". */
    static String repeatLong(String repeat) {
        if ("daily".equals(repeat)) return "Every day";
        if ("weekdays".equals(repeat)) return "Every weekday";
        int md = monthDay(repeat);
        if (md > 0) return "Every month on the " + md + ordinal(md);
        String days = repeatShort(repeat);
        return "Daily".equals(days) ? "Every day" : "Every " + days.replace(" ", ", ");
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return "th";
        return n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
    }

    /** The pill under an item: "Today 7:30 · ↻ Mon Wed Fri", "Next Wed", "Overdue · 2 Nov". */
    static String pill(NoteData.TodoItem item, long today) {
        int s = status(item, today);
        if (s == NONE) return "";
        String w = when(day(item.due), today), t = time(item.time);
        String head = s == OVERDUE ? "Overdue · " + w : item.done && repeats(item) ? "Next " + w : w;
        if (t != null) head += " " + t;
        return repeats(item) ? head + "  ·  ↻ " + repeatShort(item.repeat) : head;
    }

    /** The picker's headline: "Every Mon, Wed, Fri · next Mon 9 Nov, 7:30" or "Due tomorrow". */
    static String summary(NoteData.TodoItem item, long today) {
        if (!dated(item)) return "No date yet";
        long d = day(item.due);
        long diff = d - today;
        String w = when(d, today);
        if (diff >= -1 && diff <= 1) w = w.toLowerCase(java.util.Locale.ROOT); // today, tomorrow, yesterday
        else if (w.length() == 3) { // A weekday this week: add its date too.
            int[] c = civil(d);
            w += " " + c[2] + " " + MONTHS[c[1] - 1];
        }
        String t = time(item.time);
        String at = w + (t != null ? ", " + t : "");
        if (repeats(item)) return repeatLong(item.repeat) + "  ·  next " + at;
        return (!item.done && d < today ? "Was due " : "Due ") + at;
    }

    /** The card's tag: "Overdue", "Today 7:30", "Fri"; "" when every dated item is done. */
    static String tag(List<NoteData.TodoItem> items, long today) {
        NoteData.TodoItem u = urgent(items, today);
        if (u == null) return "";
        int s = status(u, today);
        String t = time(u.time);
        String w = s == OVERDUE ? "Overdue" : when(day(u.due), today) + (s == TODAY && t != null ? " " + t : "");
        return repeats(u) ? w + " ↻" : w;
    }
}
