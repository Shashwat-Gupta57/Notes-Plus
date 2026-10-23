package org.flexstudios.notes.plus;

import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * The phone's portable "v3" payload, readable on any device that has the key:
 * {@code "NP" 0x03 keyId(8)} then a streaming AES-GCM-HKDF ciphertext (256 KB segments) with
 * that header as associated data, under HMAC-SHA256(key, "notes-plus/portable-stream/v3").
 *
 * .bak files use it with the backup key; chat photos, videos and voice notes with a random key
 * per file that travels in the message.
 */
final class Portable {

    private Portable() {}

    private static final int SEGMENT = 256 * 1024;
    private static final byte[] LABEL = "notes-plus/portable-stream/v3".getBytes(StandardCharsets.UTF_8);

    private static StreamingAead aead(byte[] key) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return new AesGcmHkdfStreaming(mac.doFinal(LABEL), "HmacSha256", 32, SEGMENT, 0);
        } catch (Exception e) {
            throw new IOException("Encryption unavailable", e);
        }
    }

    /** Writes the header to {@code out} and returns a stream encrypting after it (closing it leaves {@code out} open). */
    static OutputStream encrypting(OutputStream out, byte[] key, String keyId) throws IOException {
        byte[] header = new byte[11];
        header[0] = 'N';
        header[1] = 'P';
        header[2] = 3;
        System.arraycopy(HexFormat.of().parseHex(keyId), 0, header, 3, 8);
        out.write(header);
        OutputStream keepOpen = new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        };
        try {
            return aead(key).newEncryptingStream(keepOpen, header);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Reads the 11-byte header off {@code in}; returns it (its key id is bytes 3..11). */
    static byte[] header(InputStream in) throws IOException {
        byte[] header = new byte[11];
        new DataInputStream(in).readFully(header);
        if (header[0] != 'N' || header[1] != 'P' || header[2] != 3) {
            throw new IOException("Not a current Notes+ payload");
        }
        return header;
    }

    static String keyId(byte[] header) {
        return HexFormat.of().formatHex(header, 3, 11);
    }

    /** The plaintext after a header already read. */
    static InputStream decrypting(InputStream in, byte[] header, byte[] key) throws IOException {
        try {
            return aead(key).newDecryptingStream(in, header);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Whole payload (header included) decrypted into memory. */
    static byte[] decryptAll(InputStream in, byte[] key) throws IOException {
        byte[] header = header(in);
        try (InputStream plain = decrypting(in, header, key)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            plain.transferTo(out);
            return out.toByteArray();
        }
    }
}
