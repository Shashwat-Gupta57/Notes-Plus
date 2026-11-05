package org.flexstudios.notes.plus;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Hidden notes' lock hash, against values worked out independently (Python's hashlib): the desktop's
 * HiddenLockTest and the web's hiddenlock tests check the same ones, so a lock set anywhere opens
 * everywhere.
 */
public class HiddenLockTest {

    private static final String SALT = "000102030405060708090a0b0c0d0e0f";

    @Test
    public void knownHashesOpen() throws Exception {
        String pin = "h1:" + SALT + ":658b926a32769f047921347e59600b098ffb2135401096f25aa7357c34503d46";
        assertTrue(HiddenLock.check(pin, "PIN", "1234"));
        assertFalse(HiddenLock.check(pin, "PIN", "1235"));
        assertFalse(HiddenLock.check(pin, "PASSWORD", "1234"));
        String pattern = "h1:" + SALT + ":7fe29e7003d091812221206f82372212e8a731910be6484d234bf5d6afd972ad";
        assertTrue(HiddenLock.check(pattern, "PATTERN", "01258"));
        assertFalse(HiddenLock.check(pattern, "PATTERN", "01257"));
    }

    @Test
    public void aNewHashOpensWithItsLockOnly() throws Exception {
        String h = HiddenLock.hash("PASSWORD", "river stone");
        assertTrue(h.startsWith("h1:"));
        assertTrue(HiddenLock.check(h, "PASSWORD", "river stone"));
        assertFalse(HiddenLock.check(h, "PASSWORD", "river ston"));
    }
}
