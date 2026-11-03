package org.flexstudios.notes.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

/** What a note starts as, from a template. The desktop's TemplatesTest and the web's templates.js agree. */
public class TemplatesTest {

    private static final long MON = Due.day("2026-11-02");
    private static final long DAY_START = 1_793_577_600_000L;

    @Test
    public void aListStartsUntickedWithItsRepeatsFromToday() {
        String data = "{\"items\":["
                + "{\"text\":\"Passport\",\"done\":true},"
                + "{\"text\":\"![[IMG_1_ab.png]]\",\"done\":false},"
                + "{\"text\":\"Call the hotel\",\"done\":false,\"due\":\"2026-10-20\",\"time\":\"09:00\"},"
                + "{\"text\":\"Water the plants\",\"done\":true,\"due\":\"2026-10-01\",\"time\":\"08:00\",\"repeat\":\"weekly:3\"}"
                + "],\"wrap\":false}";
        String[] fresh = Templates.fresh(NoteData.LIST, "x", data, MON, DAY_START);
        List<NoteData.TodoItem> items = NoteData.todos(fresh[1]);
        assertEquals(3, items.size());
        for (NoteData.TodoItem item : items) assertFalse(item.done);
        assertNull(items.get(1).due);
        assertNull(items.get(1).time);
        assertEquals("2026-11-04", items.get(2).due);
        assertEquals("08:00", items.get(2).time);
        assertEquals("weekly:3", items.get(2).repeat);
        assertFalse(NoteData.wraps(fresh[1]));
        assertEquals("Passport\nCall the hotel\nWater the plants", fresh[0]);
    }

    @Test
    public void textLosesItsPictureLines() {
        String text = "# Meeting\n\n![[IMG_1700000000000_ab12cd34.png]]\n\n- Agenda\n- [ ] Actions";
        assertEquals("# Meeting\n\n- Agenda\n- [ ] Actions", Templates.fresh(NoteData.TEXT, text, null, MON, DAY_START)[0]);
    }

    @Test
    public void aJournalIsForTodayWithNoMoodOrMedia() {
        String data = "{\"day\":1700000000000,\"mood\":\"😄\",\"media\":[\"IMG_1_ab.jpg\"]}";
        String[] fresh = Templates.fresh(NoteData.JOURNAL, "Grateful for:", data, MON, DAY_START);
        NoteData.Journal j = NoteData.journal(fresh[1], 0);
        assertEquals(DAY_START, j.day);
        assertEquals("", j.mood);
        assertEquals(0, j.media.size());
        assertEquals("Grateful for:", fresh[0]);
    }

    @Test
    public void mindMapIdeasLoseTheirPictures() {
        String data = "{\"nodes\":[{\"id\":1,\"parent\":-1,\"text\":\"Trip\",\"x\":0,\"y\":0,\"image\":\"IMG_1_ab.png\"}],\"links\":[]}";
        NoteData.MindMap map = NoteData.mindMap(Templates.fresh(NoteData.MINDMAP, "Trip", data, MON, DAY_START)[1]);
        assertEquals("Trip", map.nodes.get(0).text);
        assertNull(map.nodes.get(0).image);
    }

    @Test
    public void onlyWordsAndStructureKindsCanBeTemplates() {
        assertFalse(Templates.kindAllowed(NoteData.SKETCH));
        assertFalse(Templates.kindAllowed(NoteData.AUDIO));
    }
}
