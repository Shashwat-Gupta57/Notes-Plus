package org.flexstudios.notes.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Links between notes. The desktop's NoteLinksTest and the web's notelinks.js agree. */
public class NoteLinksTest {

    private static int ids;

    private static NoteEntity note(String type, String title, String content, String data, long time) {
        NoteEntity n = new NoteEntity(title, content, time);
        n.setId(++ids);
        n.setType(type);
        n.setData(data);
        return n;
    }

    @Test
    public void findsLinksButNotPictures() {
        assertEquals(Arrays.asList("Trip to Japan", "budget"), // Each title once, as first written.
                NoteLinks.titlesIn("See [[Trip to Japan]] and [[budget|the money]], [[ trip to japan ]] again.\n![[IMG_1.png]]\n[[Budget]]"));
        assertEquals("See Trip to Japan and the money.", NoteLinks.shown("See [[Trip to Japan]] and [[Budget|the money]]."));
        assertEquals("• Trip", Markdown.plainLine("- [[Trip]]"));
    }

    @Test
    public void theNewestNoteWithTheTitleWins() {
        NoteEntity old = note(NoteData.TEXT, "Plan", "", null, 1);
        NoteEntity fresh = note(NoteData.TEXT, " plan ", "", null, 2);
        NoteEntity tpl = note(NoteData.TEXT, "Plan", "", null, 3);
        tpl.setTemplate(true);
        List<NoteEntity> notes = Arrays.asList(old, fresh, tpl);
        assertSame(fresh, NoteLinks.resolve("PLAN", notes));
        assertNull(NoteLinks.resolve("Nothing", notes));
        assertNull(NoteLinks.resolve("  ", notes));
    }

    @Test
    public void backlinksComeFromWordsListsAndMindMaps() {
        NoteEntity trip = note(NoteData.TEXT, "Trip", "", null, 5);
        NoteEntity a = note(NoteData.TEXT, "A", "Before [[trip]] after", null, 1);
        NoteEntity b = note(NoteData.LIST, "B", "Pack for [[Trip|it]]", "{\"items\":[{\"text\":\"Pack for [[Trip|it]]\",\"done\":false}]}", 3);
        NoteEntity c = note(NoteData.MINDMAP, "C", "Ideas", "{\"nodes\":[{\"id\":1,\"parent\":-1,\"text\":\"Ideas\",\"x\":0,\"y\":0,\"linkUuid\":\"" + trip.getUuid() + "\"}],\"links\":[]}", 2);
        NoteEntity d = note(NoteData.TEXT, "D", "Just ![[Trip.png]] here", null, 4);
        List<NoteEntity> from = NoteLinks.backlinks(trip, Arrays.asList(trip, a, b, c, d));
        assertEquals(Arrays.asList(b, c, a), from);
    }

    @Test
    public void renamingKeepsWordsShownAndLeavesPicturesAlone() {
        assertEquals("[[Japan 2027]], [[Japan 2027|the trip]], ![[Trip]], [[Tripod]]",
                NoteLinks.renameIn("[[trip]], [[Trip|the trip]], ![[Trip]], [[Tripod]]", "Trip", "Japan 2027"));
        NoteEntity list = note(NoteData.LIST, "L", "Book [[Trip]]", "{\"items\":[{\"text\":\"Book [[Trip]]\",\"done\":true}],\"wrap\":false}", 1);
        String[] next = NoteLinks.renamed(list, "trip", "Japan");
        assertEquals("Book [[Japan]]", next[0]);
        NoteData.TodoItem item = NoteData.todos(next[1]).get(0);
        assertEquals("Book [[Japan]]", item.text);
        assertEquals(true, item.done);
        assertEquals(false, NoteData.wraps(next[1]));
        assertNull(NoteLinks.renamed(note(NoteData.TEXT, "X", "No links", null, 1), "Trip", "Japan"));
    }

    @Test
    public void suggestionsStartWithWhatsTyped() {
        List<NoteEntity> notes = Arrays.asList(note(NoteData.TEXT, "Budget 2026", "", null, 1), note(NoteData.TEXT, "Trip budget", "", null, 3),
                note(NoteData.TEXT, "Budget", "", null, 2), note(NoteData.TEXT, "", "untitled", null, 9));
        assertEquals(Arrays.asList("Budget", "Budget 2026", "Trip budget"), NoteLinks.suggestions("bud", notes, 8, 0));
        assertEquals(2, NoteLinks.suggestions("", notes, 2, 0).size());
    }
}
