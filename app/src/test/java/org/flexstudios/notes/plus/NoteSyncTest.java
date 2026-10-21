package org.flexstudios.notes.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The decisions sync makes about a pulled note, and the names it trusts from the account. */
public class NoteSyncTest {

    private static int decide(boolean exists, boolean open, boolean dirty, long localRev, long remoteRev, boolean deleted) {
        return NoteSync.decide(exists, open, dirty, localRev, remoteRev, deleted);
    }

    @Test
    public void newNotesArriveAndUnknownTombstonesAreIgnored() {
        assertEquals(NoteSync.INSERT, decide(false, false, false, 0, 1, false));
        assertEquals(NoteSync.SKIP, decide(false, false, false, 0, 4, true));
    }

    @Test
    public void anOpenNoteIsNeverTouched() {
        assertEquals(NoteSync.WAIT, decide(true, true, false, 1, 2, false));
        assertEquals(NoteSync.WAIT, decide(true, true, false, 1, 2, true));
        assertEquals(NoteSync.WAIT, decide(true, true, true, 1, 2, false));
    }

    @Test
    public void unpushedEditsAreLeftForThePushToResolve() {
        // Never overwritten by a pull, whatever the server says: the push transaction sees the
        // newer rev and keeps both versions.
        assertEquals(NoteSync.SKIP, decide(true, false, true, 1, 2, false));
        assertEquals(NoteSync.SKIP, decide(true, false, true, 1, 2, true));
        assertEquals(NoteSync.SKIP, decide(true, false, true, 3, 3, false));
    }

    @Test
    public void cleanNotesFollowNewerVersionsOnly() {
        assertEquals(NoteSync.OVERWRITE, decide(true, false, false, 1, 2, false));
        assertEquals(NoteSync.REMOVE, decide(true, false, false, 1, 2, true));
        assertEquals(NoteSync.SKIP, decide(true, false, false, 2, 2, false));
        assertEquals(NoteSync.SKIP, decide(true, false, false, 5, 3, false)); // an echo of an older write
    }

    @Test
    public void databaseKeysNeverContainForbiddenCharacters() {
        assertEquals("IMG_123_jpg", NoteSync.key("IMG_123.jpg"));
        assertEquals("a_b_c_d_e_f", NoteSync.key("a.b#c$d[e]f"));
        assertEquals("x_y", NoteSync.key("x/y"));
    }

    @Test
    public void namesFromTheAccountCannotEscapeTheNoteFolder() {
        assertTrue(NoteSync.safeName("IMG_123.jpg"));
        assertFalse(NoteSync.safeName("../../shared_prefs/app_prefs.xml"));
        assertFalse(NoteSync.safeName("a/b.jpg"));
        assertFalse(NoteSync.safeName("a\\b.jpg"));
        assertFalse(NoteSync.safeName(".."));
        assertFalse(NoteSync.safeName(""));
    }

    @Test
    public void uuidsAreValidDatabaseKeys() {
        for (int i = 0; i < 100; i++) {
            assertTrue(NoteSync.newUuid().matches("^[0-9a-f]{32}$"));
        }
    }

    @Test
    public void usernamesAreNormalisedOrRefused() {
        assertEquals("ada.l", Account.normaliseUsername(" @Ada.L "));
        assertEquals("x_9", Account.normaliseUsername("x_9"));
        assertEquals(null, Account.normaliseUsername("ab"));
        assertEquals(null, Account.normaliseUsername("has space"));
        assertEquals(null, Account.normaliseUsername("emoji😀"));
        assertEquals(null, Account.normaliseUsername("a234567890123456789012"));
    }
}
