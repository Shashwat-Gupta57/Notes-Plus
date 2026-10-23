package org.flexstudios.notes.plus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts the phone must agree with: note JSON, sync decisions, keys; plus link finding. */
class NotesTest {

    @Test
    void listsRoundTripAsOnThePhone() {
        String json = NoteData.todosJson(List.of(new NoteData.TodoItem("Milk", true), new NoteData.TodoItem("Eggs", false)));
        List<NoteData.TodoItem> back = NoteData.todos(json);
        assertEquals(2, back.size());
        assertTrue(back.get(0).done);
        assertEquals("Eggs", back.get(1).text);
        assertTrue(NoteData.todos("not json").isEmpty());
    }

    @Test
    void mindMapsKeepLinksAndPictures() {
        NoteData.MindMap map = new NoteData.MindMap();
        NoteData.MindNode root = new NoteData.MindNode(1, -1, "Trip", 0, 0);
        NoteData.MindNode idea = new NoteData.MindNode(2, 1, "Hotel", 230.04, 64);
        idea.image = "IMG_1_2.png";
        idea.linkUuid = "0123456789abcdef0123456789abcdef";
        map.nodes.add(root);
        map.nodes.add(idea);
        map.links.add(new long[]{1, 2});
        NoteData.MindMap back = NoteData.mindMap(NoteData.mindMapJson(map));
        assertEquals("IMG_1_2.png", back.nodes.get(1).image);
        assertEquals(idea.linkUuid, back.nodes.get(1).linkUuid);
        assertEquals(230.0, back.nodes.get(1).x, 0.001);
        assertEquals(1, back.links.size());
        assertNull(back.nodes.get(0).image);
    }

    @Test
    void recordingsKeepTheirWaveform() {
        NoteData.Recording r = new NoteData.Recording("rec_1.wav", "Idea", 4200, 1, new float[]{0.1f, 0.554f, 1f});
        NoteData.Recording back = NoteData.recordings(NoteData.recordingsJson(List.of(r))).get(0);
        assertEquals(4200, back.durationMs);
        assertEquals(0.55f, back.wave[1], 0.0001f);
    }

    @Test
    void pullDecisionsMatchThePhone() {
        assertEquals(NoteSync.INSERT, NoteSync.decide(false, false, false, 0, 1, false));
        assertEquals(NoteSync.SKIP, NoteSync.decide(false, false, false, 0, 3, true));
        assertEquals(NoteSync.WAIT, NoteSync.decide(true, true, false, 1, 2, false));
        assertEquals(NoteSync.SKIP, NoteSync.decide(true, false, true, 1, 2, false));
        assertEquals(NoteSync.SKIP, NoteSync.decide(true, false, false, 2, 2, false));
        assertEquals(NoteSync.OVERWRITE, NoteSync.decide(true, false, false, 1, 2, false));
        assertEquals(NoteSync.REMOVE, NoteSync.decide(true, false, false, 1, 2, true));
    }

    @Test
    void namesAreSafeAndKeysLegal() {
        assertEquals("IMG_1_png", NoteSync.key("IMG_1.png"));
        assertTrue(NoteSync.safeName("IMG_1.png"));
        assertFalse(NoteSync.safeName("../evil"));
        assertFalse(NoteSync.safeName("a\\b"));
        assertTrue(NoteSync.conflictTitle("").startsWith("Untitled (conflict, "));
        assertEquals(32, NoteSync.newUuid().length());
        assertEquals(32, new NoteEntity("", "", 0).getUuid().length());
    }

    @Test
    void usernamesAreNormalised() {
        assertEquals("ada_l", Account.normaliseUsername(" @Ada_L "));
        assertNull(Account.normaliseUsername("ab"));
        assertNull(Account.normaliseUsername("no spaces"));
    }

    @Test
    void linksAndAddressesAreFound() {
        List<Links.Match> m = Links.find("See https://example.com/a?b=1, or www.notes.app. Router 192.168.1.1:8080 and 10.0.0.5.");
        assertEquals(4, m.size());
        assertEquals("https://example.com/a?b=1", m.get(0).text());
        assertTrue(m.get(0).url());
        assertEquals("www.notes.app", m.get(1).text());
        assertEquals("192.168.1.1:8080", m.get(2).text());
        assertFalse(m.get(2).url());
        assertEquals("10.0.0.5", m.get(3).text());
        assertTrue(Links.find("version 1.2.3.4.5 and 999.1.1.1").isEmpty());
    }

    @Test
    void pastedPicturesLeaveTheCardText() {
        String content = "Before\n![[IMG_1_ab.png]]\nAfter";
        assertEquals("Before\nAfter", NoteCard.plain(content));
        assertEquals("IMG_1_ab.png", NoteCard.firstPicture(content));
        assertNull(NoteCard.firstPicture("no pictures"));
    }
}
