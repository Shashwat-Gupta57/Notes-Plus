package org.flexstudios.notes.plus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Hidden notes' lock hash: the phone's HiddenLockTest values (worked out with Python's hashlib). */
class HiddenLockTest {

    private static final String SALT = "000102030405060708090a0b0c0d0e0f";

    @Test
    void knownHashesOpen() {
        String pin = "h1:" + SALT + ":658b926a32769f047921347e59600b098ffb2135401096f25aa7357c34503d46";
        assertTrue(HiddenLock.check(pin, "PIN", "1234"));
        assertFalse(HiddenLock.check(pin, "PIN", "1235"));
        assertFalse(HiddenLock.check(pin, "PASSWORD", "1234"));
        String pattern = "h1:" + SALT + ":7fe29e7003d091812221206f82372212e8a731910be6484d234bf5d6afd972ad";
        assertTrue(HiddenLock.check(pattern, "PATTERN", "01258"));
        assertFalse(HiddenLock.check(pattern, "PATTERN", "01257"));
    }

    @Test
    void aNewHashOpensWithItsLockOnly() {
        String h = HiddenLock.hash("PASSWORD", "river stone");
        assertTrue(HiddenLock.check(h, "PASSWORD", "river stone"));
        assertFalse(HiddenLock.check(h, "PASSWORD", "river ston"));
    }
}
