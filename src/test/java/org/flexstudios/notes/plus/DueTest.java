package org.flexstudios.notes.plus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Due dates and repeats on list items. The phone's DueTest and the web's due.js agree with these. */
class DueTest {

    private static final long MON = Due.day("2026-11-02");

    private static NoteData.TodoItem item(boolean done, String due, String repeat) {
        NoteData.TodoItem item = new NoteData.TodoItem("Gym", done);
        item.due = due;
        item.repeat = repeat;
        return item;
    }

    @Test
    void countsDaysLikeTheCalendar() {
        assertEquals(0, Due.day("1970-01-01"));
        assertEquals(1, Due.weekday(MON));
        assertEquals(7, Due.weekday(Due.day("2026-11-08")));
        assertEquals("2024-02-29", Due.ymd(Due.day("2024-02-29")));
        assertEquals("1999-12-31", Due.ymd(Due.day("2000-01-01") - 1));
        assertEquals(Due.NO_DAY, Due.day("2025-02-29"));
        assertEquals(Due.NO_DAY, Due.day("tomorrow"));
        assertEquals(29, Due.monthLength(2028, 2));
    }

    @Test
    void findsTheNextDayOfEachRepeat() {
        assertEquals("2026-11-03", Due.ymd(Due.first("daily", MON + 1)));
        assertEquals("2026-11-09", Due.ymd(Due.first("weekdays", Due.day("2026-11-07"))));
        assertEquals("2026-11-04", Due.ymd(Due.first("weekly:1,3,5", Due.day("2026-11-03"))));
        assertEquals("2026-11-30", Due.ymd(Due.first("monthly:31", Due.day("2026-11-01"))));
        assertEquals("2027-02-28", Due.ymd(Due.first("monthly:31", Due.day("2027-02-01"))));
        assertEquals("2028-02-29", Due.ymd(Due.first("monthly:30", Due.day("2028-02-01"))));
        assertFalse(Due.valid("weekly:"));
        assertFalse(Due.valid("yearly"));
    }

    @Test
    void aTickedRepeatMovesOnAndComesBackUnticked() {
        NoteData.TodoItem gym = item(false, "2026-11-02", "weekly:1,3,5");
        Due.tick(gym, true, MON);
        assertTrue(gym.done);
        assertEquals("2026-11-04", gym.due);
        assertFalse(Due.rollover(Collections.singletonList(gym), MON + 1));
        assertTrue(Due.rollover(Collections.singletonList(gym), MON + 2));
        assertFalse(gym.done);
        assertEquals(Due.TODAY, Due.status(gym, MON + 2));
    }

    @Test
    void untickingInTheSameSessionGivesTheDayBack() {
        NoteData.TodoItem gym = item(false, "2026-11-02", "daily");
        Due.tick(gym, true, MON);
        assertEquals("2026-11-03", gym.due);
        Due.tick(gym, false, MON);
        assertEquals("2026-11-02", gym.due);
        assertNull(gym.undoDue);
    }

    @Test
    void tickedAheadOfItsDayMovesPastIt() {
        NoteData.TodoItem gym = item(false, "2026-11-04", "weekly:1,3,5");
        Due.tick(gym, true, MON);
        assertEquals("2026-11-06", gym.due);
    }

    @Test
    void anOverdueRepeatMovesToAfterToday() {
        NoteData.TodoItem gym = item(false, "2026-10-26", "weekly:1");
        assertEquals(Due.OVERDUE, Due.status(gym, MON + 1));
        Due.tick(gym, true, MON + 1);
        assertEquals("2026-11-09", gym.due);
    }

    @Test
    void aOneOffItemKeepsItsDay() {
        NoteData.TodoItem call = item(false, "2026-11-01", null);
        assertEquals(Due.OVERDUE, Due.status(call, MON));
        assertEquals("Overdue · Yesterday", Due.pill(call, MON));
        Due.tick(call, true, MON);
        assertEquals("2026-11-01", call.due);
        assertEquals(Due.DONE, Due.status(call, MON));
        assertFalse(Due.rollover(Collections.singletonList(call), MON + 30));
    }

    @Test
    void thePickerEditsTheRepeat() {
        NoteData.TodoItem gym = item(false, "2026-11-03", null);
        Due.setRepeat(gym, "weekly", MON);
        assertEquals("weekly:2", gym.repeat);
        Due.toggleWeekday(gym, 4, MON);
        assertEquals("weekly:2,4", gym.repeat);
        Due.toggleWeekday(gym, 2, MON);
        Due.toggleWeekday(gym, 4, MON); // The last day stays on.
        assertEquals("weekly:4", gym.repeat);
        assertEquals("2026-11-05", gym.due); // Tuesday is no longer one of its days.
        Due.setDay(gym, Due.day("2026-11-07"));
        assertEquals("weekly:4,6", gym.repeat);
        Due.setRepeat(gym, "monthly", MON);
        assertEquals("monthly:7", gym.repeat);
        Due.setRepeat(gym, "weekdays", MON);
        assertEquals("2026-11-09", gym.due); // Saturday the 7th isn't a weekday.
        Due.setRepeat(gym, null, MON);
        assertNull(gym.repeat);
        assertEquals("2026-11-09", gym.due);
    }

    @Test
    void wordsForPillsTagsAndTheSheet() {
        NoteData.TodoItem gym = item(false, "2026-11-02", "weekly:1,3,5");
        gym.time = "07:30";
        assertEquals("Today 7:30  ·  ↻ Mon Wed Fri", Due.pill(gym, MON));
        assertEquals("Every Mon, Wed, Fri  ·  next today, 7:30", Due.summary(gym, MON));
        assertEquals("Today 7:30 ↻", Due.tag(Collections.singletonList(gym), MON));
        Due.tick(gym, true, MON);
        assertEquals("Next Wed 7:30  ·  ↻ Mon Wed Fri", Due.pill(gym, MON));
        assertEquals("Due 9 Nov", Due.summary(item(false, "2026-11-09", null), MON));
        assertEquals("Due tomorrow", Due.summary(item(false, "2026-11-03", null), MON));
        assertEquals("Every month on the 31st  ·  next 31 Dec", Due.summary(item(false, "2026-12-31", "monthly:31"), MON));
        assertEquals("15 Jan 2027", Due.when(Due.day("2027-01-15"), MON));
        assertEquals("Overdue", Due.tag(Arrays.asList(item(false, "2026-11-09", null), item(false, "2026-10-30", null)), MON));
        assertEquals("", Due.tag(Collections.singletonList(item(true, "2026-10-30", null)), MON));
        assertEquals("Daily", Due.repeatShort("weekly:1,2,3,4,5,6,7"));
        // The soonest by time on the same day, wherever it is in the list; no time comes after timed ones.
        NoteData.TodoItem late = item(false, "2026-11-02", null), soon = item(false, "2026-11-02", null), bare = item(false, "2026-11-02", null);
        late.time = "13:45";
        soon.time = "12:47";
        assertEquals("Today 12:47", Due.tag(Arrays.asList(late, soon), MON));
        assertEquals("Today 12:47", Due.tag(Arrays.asList(bare, soon), MON));
    }

    @Test
    void theFieldsTravelInTheNote() {
        NoteData.TodoItem gym = item(false, "2026-11-02", "weekly:1,3,5");
        gym.time = "07:30";
        String json = NoteData.todosJson(Arrays.asList(gym, new NoteData.TodoItem("Milk", true)));
        NoteData.TodoItem back = NoteData.todos(json).get(0);
        assertEquals("2026-11-02", back.due);
        assertEquals("07:30", back.time);
        assertEquals("weekly:1,3,5", back.repeat);
        assertFalse(json.contains("undoDue"));
        assertFalse(NoteData.todos(json).get(1).due != null);
        assertNull(NoteData.todos("{\"items\":[{\"text\":\"x\",\"due\":\"soon\",\"repeat\":\"daily\"}]}").get(0).repeat);
    }
}
