package org.flexstudios.notes.plus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ctrl+K's matching. The web's palette.js gives the same answers. */
class PaletteTest {

    private static int score(String q, String t) {
        Palette.Match m = Palette.match(q, t);
        assertNotNull(m, q + " in " + t);
        return m.score();
    }

    @Test
    void lettersMustComeInOrder() {
        assertNull(Palette.match("xyz", "Weekly review"));
        assertNull(Palette.match("wr2", "Weekly review"));
        assertNotNull(Palette.match("wkr", "Weekly review"));
    }

    @Test
    void aStartBeatsAWordStartBeatsTheMiddle() {
        assertTrue(score("rev", "Review the plan") > score("rev", "Weekly review"));
        assertTrue(score("rev", "Weekly review") > score("rev", "Unrevised"));
    }

    @Test
    void lettersTogetherBeatLettersApart() {
        assertTrue(score("wkr", "wkr notes") > score("wkr", "Weekly review"));
        assertTrue(score("wr", "Weekly review") > score("wr", "Bowler")); // Word starts beat letters inside words.
    }

    @Test
    void itMarksTheLettersItMatched() {
        assertArrayEquals(new int[]{7, 8, 9}, Palette.match("rev", "Weekly review").at());
        assertArrayEquals(new int[]{0, 7}, Palette.match("wr", "Weekly review").at());
        assertArrayEquals(new int[0], Palette.match("  ", "Anything").at());
    }
}
