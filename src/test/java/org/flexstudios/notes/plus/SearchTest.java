package org.flexstudios.notes.plus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Searching every note. The phone's SearchTest and the web's search.js agree. */
class SearchTest {

    private static int ids;

    private static NoteEntity note(String type, String title, String content, String data, long time) {
        NoteEntity n = new NoteEntity(title, content, time);
        n.setId(++ids);
        n.setType(type);
        n.setData(data);
        return n;
    }

    private static List<String> titles(List<NoteEntity> notes) {
        List<String> out = new ArrayList<>();
        for (NoteEntity n : notes) out.add(n.getTitle());
        return out;
    }

    @Test
    void everyWordMustBeSomewhereInAnyOrder() {
        NoteEntity trip = note(NoteData.TEXT, "Trip to Japan", "Tokyo first, then **Kyoto** by train.", null, 1);
        NoteEntity list = note(NoteData.LIST, "Packing", "Passport\nTrain tickets", "{\"items\":[{\"text\":\"Passport\",\"done\":false},{\"text\":\"Train tickets\",\"done\":false}]}", 2);
        NoteEntity map = note(NoteData.MINDMAP, "Ideas", "Ideas\nKyoto temples\nmorning walk", null, 3);
        List<NoteEntity> all = Arrays.asList(trip, list, map);
        assertEquals(Arrays.asList("Trip to Japan"), titles(Search.run(all, "kyoto TRAIN", null)));
        assertEquals(Arrays.asList("Packing", "Trip to Japan"), titles(Search.run(all, "train", null)));
        assertEquals(Arrays.asList("Ideas", "Trip to Japan"), titles(Search.run(all, "kyoto", null)));
        assertTrue(Search.run(all, "  ", null).isEmpty());
        assertEquals(Arrays.asList("a", "b"), Search.terms(" A  \"b\" a "));
    }

    @Test
    void titlesComeFirstAndHiddenOrLockedWordsStayUnread() {
        NoteEntity body = note(NoteData.TEXT, "Notes", "budget for march", null, 9);
        NoteEntity title = note(NoteData.TEXT, "Budget", "", null, 1);
        NoteEntity hidden = note(NoteData.TEXT, "Budget secret", "", null, 5);
        hidden.setHidden(true);
        NoteEntity locked = note(NoteData.TEXT, "Vault", "", "{\"lock\":1,\"ct\":\"x\",\"type\":\"PIN\"}", 3);
        locked.setContent("budget hidden by its lock");
        assertEquals(Arrays.asList("Budget", "Notes"), titles(Search.run(Arrays.asList(body, title, hidden, locked), "budget", null)));
    }

    @Test
    void filtersNarrowIt() {
        NoteEntity red = note(NoteData.TEXT, "Red one", "words ![[IMG_1_ab.png]]", null, 1);
        red.setColor(8);
        red.setFolder("Work");
        NoteEntity dated = note(NoteData.LIST, "Week", "Gym", "{\"items\":[{\"text\":\"Gym\",\"done\":false,\"due\":\"2026-11-09\"}]}", 2);
        NoteEntity journal = note(NoteData.JOURNAL, "Monday", "Rained", "{\"day\":1,\"mood\":\"\",\"media\":[\"IMG_2_cd.jpg\"]}", 3);
        NoteEntity plain = note(NoteData.TEXT, "Plain", "words", null, 4);
        List<NoteEntity> all = Arrays.asList(red, dated, journal, plain);
        Search.Filters f = new Search.Filters();
        f.pictures = true;
        assertEquals(Arrays.asList("Monday", "Red one"), titles(Search.run(all, "", f)));
        f = new Search.Filters();
        f.dates = true;
        assertEquals(Arrays.asList("Week"), titles(Search.run(all, "", f)));
        f = new Search.Filters();
        f.color = 8;
        f.folder = "work";
        assertEquals(Arrays.asList("Red one"), titles(Search.run(all, "words", f)));
        f = new Search.Filters();
        f.folder = "";
        f.type = NoteData.TEXT;
        assertEquals(Arrays.asList("Plain"), titles(Search.run(all, "", f)));
        assertFalse(new Search.Filters().any());
    }

    @Test
    void aSnippetShowsWhereTheWordsAre() {
        NoteEntity n = note(NoteData.TEXT, "Trip", "# Day one\nWe landed in **Tokyo** at night, then took the slow train to Kyoto and slept for twelve hours straight.", null, 1);
        Search.Snippet s = Search.snippet(n, Search.terms("kyoto tokyo"), 60);
        assertTrue(s.text.startsWith("…") || s.text.startsWith("Day one"), s.text);
        assertFalse(s.text.contains("**"));
        for (int[] h : s.hits) {
            String marked = s.text.substring(h[0], h[1]).toLowerCase();
            assertTrue(marked.equals("tokyo") || marked.equals("kyoto"), marked);
        }
        assertTrue(s.hits.size() >= 1);
        assertNull(Search.snippet(n, Search.terms("japan"), 60));
        assertEquals(1, Search.marks("Banana", Search.terms("ana")).size());
    }
}
