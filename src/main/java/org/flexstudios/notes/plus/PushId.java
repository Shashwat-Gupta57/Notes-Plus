package org.flexstudios.notes.plus;

import java.security.SecureRandom;

/**
 * Firebase push keys, made here as the Android SDK makes them: 8 characters of time and 12 of
 * randomness, so keys sort by when they were made and two never collide. A message gets its
 * key before it is sent, so a retry can never duplicate it.
 */
final class PushId {

    private PushId() {}

    private static final String CHARS = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastTime;
    private static final int[] lastRandom = new int[12];

    static synchronized String next() {
        long now = System.currentTimeMillis();
        boolean same = now == lastTime;
        lastTime = now;
        char[] time = new char[8];
        for (int i = 7; i >= 0; i--) {
            time[i] = CHARS.charAt((int) (now % 64));
            now /= 64;
        }
        if (!same) {
            for (int i = 0; i < 12; i++) lastRandom[i] = RANDOM.nextInt(64);
        } else {
            // Same millisecond: increment, so keys made together still sort in order.
            int i = 11;
            while (i >= 0 && lastRandom[i] == 63) lastRandom[i--] = 0;
            if (i >= 0) lastRandom[i]++;
        }
        StringBuilder id = new StringBuilder(20).append(time);
        for (int r : lastRandom) id.append(CHARS.charAt(r));
        return id.toString();
    }
}
