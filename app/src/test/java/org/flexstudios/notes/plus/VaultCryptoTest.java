package org.flexstudios.notes.plus;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The portable payload format and the pieces of key handling that do not need a device.
 * The test JVM runs with a 128 MB heap (see app/build.gradle.kts), which is what makes
 * {@link #largePayloadStreamsInFlatMemory} meaningful.
 */
public class VaultCryptoTest {

    private static final SecretKey KEY = new SecretKeySpec(VaultCrypto.randomBytes(32), "AES");
    private static final String KEY_ID = VaultCrypto.bytesToHex(VaultCrypto.randomBytes(8));

    private static VaultCrypto.KeyResolver fixed(SecretKey key) {
        return new VaultCrypto.KeyResolver() {
            @Override
            public SecretKey resolveDataKey(String keyId) {
                return key;
            }

            @Override
            public SecretKey resolveLegacyKey(byte[] salt) {
                return key;
            }
        };
    }

    private static byte[] encrypt(byte[] plaintext) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(plaintext), out, KEY, KEY_ID);
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] payload, VaultCrypto.KeyResolver resolver) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VaultCrypto.decrypt(new ByteArrayInputStream(payload), out, resolver);
        return out.toByteArray();
    }

    @Test
    public void roundTripsAcrossSegmentBoundaries() throws Exception {
        Random random = new Random(1);
        for (int size : new int[]{0, 1, 4095, 256 * 1024 - 1, 256 * 1024, 256 * 1024 + 1, 3_000_000}) {
            byte[] plaintext = new byte[size];
            random.nextBytes(plaintext);
            assertArrayEquals("size " + size, plaintext, decrypt(encrypt(plaintext), fixed(KEY)));
        }
    }

    @Test
    public void writesTheVersion3Header() throws Exception {
        byte[] payload = encrypt("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals('N', payload[0]);
        assertEquals('P', payload[1]);
        assertEquals(VaultCrypto.VERSION_STREAM, payload[2]);
        assertEquals(KEY_ID, VaultCrypto.bytesToHex(Arrays.copyOfRange(payload, 3, 11)));
    }

    /** The old single-shot GCM ran out of memory on a 64 MB file in a 256 MB heap. */
    @Test(timeout = 120_000)
    public void largePayloadStreamsInFlatMemory() throws Exception {
        long size = 256L * 1024 * 1024; // Twice the test heap.
        PipedCheck check = new PipedCheck();
        java.io.File temp = java.io.File.createTempFile("vaultcrypto", ".bin");
        temp.deleteOnExit();
        try (OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(temp))) {
            VaultCrypto.encrypt(new PatternStream(size), out, KEY, KEY_ID);
        }
        try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(temp))) {
            VaultCrypto.decrypt(in, check, fixed(KEY));
        }
        assertEquals(size, check.count);
        assertTrue("plaintext came back altered", check.intact);
        assertTrue(temp.delete());
    }

    @Test
    public void rejectsTampering() throws Exception {
        byte[] payload = encrypt(new byte[100_000]);
        payload[payload.length / 2] ^= 1;
        expectFailure(payload, fixed(KEY));
    }

    @Test
    public void rejectsTruncation() throws Exception {
        byte[] payload = encrypt(new byte[600_000]);
        // Cut exactly at a segment boundary too, which a naive stream would accept.
        expectFailure(Arrays.copyOf(payload, payload.length - 100), fixed(KEY));
        // 11-byte NP header, then Tink's first segment ends exactly 256 KB later.
        expectFailure(Arrays.copyOf(payload, 11 + 256 * 1024), fixed(KEY));
    }

    @Test
    public void headerIsBoundToTheCiphertext() throws Exception {
        byte[] payload = encrypt(new byte[1000]);
        payload[5] ^= 1; // Inside the keyId.
        expectFailure(payload, fixed(KEY));
    }

    @Test
    public void wrongKeyFails() throws Exception {
        byte[] payload = encrypt(new byte[1000]);
        expectFailure(payload, fixed(new SecretKeySpec(VaultCrypto.randomBytes(32), "AES")));
    }

    /** Backups written before 7.50: salt | iv | single-shot GCM under PBKDF2(lock, 65536). */
    @Test
    public void readsVersion1Payloads() throws Exception {
        byte[] salt = VaultCrypto.randomBytes(16);
        byte[] iv = VaultCrypto.randomBytes(12);
        SecretKey lockKey = VaultCrypto.deriveKey("1234", salt, VaultCrypto.LEGACY_PBKDF2_ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, lockKey, new GCMParameterSpec(128, iv));
        byte[] plaintext = "an old backup".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        legacy.write(salt);
        legacy.write(iv);
        legacy.write(cipher.doFinal(plaintext));

        VaultCrypto.KeyResolver resolver = new VaultCrypto.KeyResolver() {
            @Override
            public SecretKey resolveDataKey(String keyId) {
                throw new AssertionError("v1 has no data key");
            }

            @Override
            public SecretKey resolveLegacyKey(byte[] headerSalt) throws Exception {
                return VaultCrypto.deriveKey("1234", headerSalt, VaultCrypto.LEGACY_PBKDF2_ITERATIONS);
            }
        };
        assertArrayEquals(plaintext, decrypt(legacy.toByteArray(), resolver));
    }

    /** API 24/25 have no PBKDF2WithHmacSHA256; the fallback must give the same key. */
    @Test
    public void directPbkdf2MatchesThePlatform() throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        for (String password : new String[]{"1234", "correct horse battery staple", "pässwörd", "0"}) {
            byte[] salt = VaultCrypto.randomBytes(16);
            byte[] expected = factory.generateSecret(
                    new PBEKeySpec(password.toCharArray(), salt, 1000, 256)).getEncoded();
            byte[] actual = VaultCrypto.pbkdf2Direct(password.getBytes(StandardCharsets.UTF_8), salt, 1000);
            assertArrayEquals(password, expected, actual);
        }
    }

    /**
     * Vault files are read through a keyset-backed Tink primitive, whose seekable channel
     * cannot report its size before the first read. Video thumbnails and playback ask for the
     * size first, which is how every video broke. This uses the same kind of primitive.
     */
    @Test
    public void seekableVaultChannelKnowsItsSizeAndSeeks() throws Exception {
        com.google.crypto.tink.streamingaead.StreamingAeadConfig.register();
        com.google.crypto.tink.StreamingAead aead = com.google.crypto.tink.KeysetHandle
                .generateNew(com.google.crypto.tink.KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                .getPrimitive(com.google.crypto.tink.StreamingAead.class);
        byte[] aad = "VID_1_abc".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = new byte[1_000_003];
        new Random(7).nextBytes(plaintext);

        java.io.File file = java.io.File.createTempFile("vaultfile", ".bin");
        file.deleteOnExit();
        try (OutputStream out = aead.newEncryptingStream(new java.io.FileOutputStream(file), aad)) {
            out.write(plaintext);
        }

        try (java.io.FileInputStream raw = new java.io.FileInputStream(file);
             java.nio.channels.SeekableByteChannel unprimed =
                     aead.newSeekableDecryptingChannel(raw.getChannel(), aad)) {
            unprimed.size();
            fail("Tink now reports size before a read; the priming step may be unnecessary");
        } catch (java.io.IOException expected) {
            // The behaviour VaultCrypto.primed works around.
        }

        try (java.io.FileInputStream raw = new java.io.FileInputStream(file);
             java.nio.channels.SeekableByteChannel channel = VaultCrypto.primed(
                     aead.newSeekableDecryptingChannel(raw.getChannel(), aad))) {
            assertEquals(plaintext.length, channel.size());
            assertEquals(0, channel.position());
            for (long offset : new long[]{0, 4095, 4096, 500_000, plaintext.length - 10}) {
                channel.position(offset);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(10);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // Fill.
                }
                assertArrayEquals("offset " + offset,
                        Arrays.copyOfRange(plaintext, (int) offset, (int) offset + 10), buffer.array());
            }
        }
        assertTrue(file.delete());
    }

    @Test
    public void retentionKeepsPinnedRecentAndDaily() {
        // Midday, so no time zone puts the burst on two different calendar days.
        long now = TimeUnit.DAYS.toMillis(1000) + TimeUnit.HOURS.toMillis(12);
        long hour = TimeUnit.HOURS.toMillis(1);
        long day = TimeUnit.DAYS.toMillis(1);
        List<Snapshot.Summary> all = new ArrayList<>();
        // Ten runs within the last hour: the old rule kept five and dropped everything older.
        for (int i = 0; i < 10; i++) all.add(summary("burst" + i, now - i * 60_000L, "a", false));
        // One a day for the past 40 days.
        for (int d = 1; d <= 40; d++) all.add(summary("day" + d, now - d * day + hour, "a", false));
        all.add(summary("pinned", now - 400 * day, "a", true));
        all.add(summary("otherPhone", now - 200 * day, "b", false));

        Set<String> kept = new HashSet<>();
        for (Snapshot.Summary s : CloudSync.applyRetention(all, now)) kept.add(s.id);

        assertTrue(kept.contains("burst0"));
        assertTrue(kept.contains("burst2"));
        assertFalse("only the newest of a busy day beyond the recent three", kept.contains("burst5"));
        assertTrue(kept.contains("day29"));
        assertFalse(kept.contains("day35"));
        assertTrue(kept.contains("pinned"));
        assertTrue("each installation keeps its own newest", kept.contains("otherPhone"));
    }

    private static Snapshot.Summary summary(String id, long createdAt, String installId, boolean pinned) {
        Snapshot.Summary s = new Snapshot.Summary();
        s.id = id;
        s.createdAt = createdAt;
        s.installId = installId;
        s.pinned = pinned;
        return s;
    }

    private static void expectFailure(byte[] payload, VaultCrypto.KeyResolver resolver) {
        try {
            decrypt(payload, resolver);
            fail("Accepted a damaged or foreign payload");
        } catch (Exception expected) {
            // Any failure is correct; silently producing plaintext is not.
        }
    }

    /** Deterministic bytes without allocating them: byte i is (i * 31) & 0xFF. */
    private static final class PatternStream extends InputStream {
        private final long size;
        private long position;

        PatternStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            return position < size ? (int) ((position++ * 31) & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= size) return -1;
            int n = (int) Math.min(len, size - position);
            for (int i = 0; i < n; i++) b[off + i] = (byte) ((position++ * 31) & 0xFF);
            return n;
        }
    }

    /** Verifies the pattern as it arrives, holding nothing. */
    private static final class PipedCheck extends OutputStream {
        long count;
        boolean intact = true;

        @Override
        public void write(int b) {
            if ((byte) b != (byte) ((count++ * 31) & 0xFF)) intact = false;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                if (b[off + i] != (byte) ((count++ * 31) & 0xFF)) intact = false;
            }
        }
    }
}
