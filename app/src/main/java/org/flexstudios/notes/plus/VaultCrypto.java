package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDataSource;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.MasterKeys;

import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Every cryptographic operation in Notes+, and the only code allowed to put plaintext on disk.
 *
 * <h3>1. Device storage ({@code vault/}, {@code thumbs/})</h3>
 * Tink streaming AEAD (AES256-GCM-HKDF, 4 KB segments) under an Android Keystore master key.
 * This is byte-for-byte what androidx {@code EncryptedFile} writes — same keyset, same
 * associated data (the file name) — so every existing file stays readable. The difference is
 * that the primitive is built once per process instead of once per file, which used to cost a
 * Keystore round trip for every thumbnail. Writes go to a {@code .part} file that is fsynced
 * and renamed, so a crash never leaves a half-written vault file under its real name.
 *
 * <h3>2. Portable payloads (Drive objects, .bak files)</h3>
 * Encrypted with a random 256-bit Vault Data Key. A vault lock (or the optional backup
 * password) only wraps that key in a small keyring, so a lock change never touches the media.
 * <pre>
 * v3:  'N' 'P' 0x03 | keyId(8) | Tink AES-GCM-HKDF streaming ciphertext   (header is the AAD)
 * v2:  'N' 'P' 0x02 | keyId(8) | iv(12) | single-shot GCM ciphertext      (read only)
 * v1:  salt(16) | iv(12) | single-shot GCM, key = PBKDF2(lock)           (read only)
 * </pre>
 * v1 and v2 were single-shot GCM, which Android's provider buffers entirely in memory, so a
 * large video ran the app out of heap. v3 is segmented: memory use is flat at any file size.
 *
 * <h3>3. Plaintext exports</h3>
 * Files handed to other apps (share) are the one place decrypted media exists on disk. They
 * live in {@link #shareDir} and are wiped by {@link #sweep} each time the vault opens.
 */
public final class VaultCrypto {

    private static final String TAG = "VaultCrypto";

    private VaultCrypto() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    // =====================================================================================
    // 1. Device storage
    // =====================================================================================

    /** Where androidx EncryptedFile keeps its keyset. Must not change, or old files go dark. */
    private static final String FILE_KEYSET_PREF = "__androidx_security_crypto_encrypted_file_pref__";
    private static final String FILE_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_file_keyset__";
    private static final String PART_SUFFIX = ".part";

    private static volatile StreamingAead deviceAead;

    /** Produces the plaintext of a file being written. */
    public interface Sink {
        void writeTo(OutputStream out) throws Exception;
    }

    private static StreamingAead device(Context context) throws IOException {
        StreamingAead aead = deviceAead;
        if (aead != null) return aead;
        synchronized (VaultCrypto.class) {
            if (deviceAead == null) {
                try {
                    StreamingAeadConfig.register();
                    String masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                    KeysetHandle handle = new AndroidKeysetManager.Builder()
                            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                            .withSharedPref(context.getApplicationContext(),
                                    FILE_KEYSET_ALIAS, FILE_KEYSET_PREF)
                            .withMasterKeyUri("android-keystore://" + masterKey)
                            .build()
                            .getKeysetHandle();
                    deviceAead = handle.getPrimitive(StreamingAead.class);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Device key unavailable", e);
                }
            }
            return deviceAead;
        }
    }

    private static byte[] aad(File file) {
        return file.getName().getBytes(StandardCharsets.UTF_8);
    }

    /** Streams the plaintext of a vault or thumbnail file. */
    public static InputStream openRead(Context context, File file) throws IOException {
        FileInputStream raw = new FileInputStream(file);
        try {
            return device(context).newDecryptingStream(raw, aad(file));
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            raw.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Random-access plaintext view of a vault file. Only the 4 KB segments actually read are
     * decrypted, which is what lets a video seek or show a frame without decrypting the
     * whole file first.
     */
    public static SeekableByteChannel openSeekable(Context context, File file) throws IOException {
        FileInputStream raw = new FileInputStream(file);
        try {
            return primed(device(context).newSeekableDecryptingChannel(raw.getChannel(), aad(file)));
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            raw.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Makes {@code size()} usable straight away. A keyset-backed channel only learns which key
     * the file was written with on its first read, and until then size() throws ("Cannot
     * determine size before first read()-call"). MediaMetadataRetriever and ExoPlayer both ask
     * for the size first, so every video failed to play or thumbnail without this.
     */
    static SeekableByteChannel primed(SeekableByteChannel channel) throws IOException {
        channel.read(ByteBuffer.allocate(1));
        channel.position(0);
        return channel;
    }

    /**
     * Encrypts whatever {@code sink} writes into {@code target}, atomically.
     *
     * The ciphertext is written under a temporary name, fsynced, then renamed into place, so
     * {@code target} either holds a complete file or is untouched. Existing files are
     * replaced, which EncryptedFile refused to do.
     */
    public static void write(Context context, File target, Sink sink) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + PART_SUFFIX);
        try {
            FileOutputStream raw = new FileOutputStream(part);
            OutputStream encrypted;
            try {
                encrypted = device(context).newEncryptingStream(new SyncingOutputStream(raw), aad(target));
            } catch (Exception e) {
                raw.close();
                throw e;
            }
            try (OutputStream out = encrypted) {
                sink.writeTo(out);
            }
            if (!part.renameTo(target)) throw new IOException("Could not save " + target.getName());
        } finally {
            if (part.exists() && !part.delete()) Log.w(TAG, "Could not remove " + part.getName());
        }
    }

    /** Convenience for {@link #write} from a stream. Returns the number of plaintext bytes. */
    public static long write(Context context, File target, InputStream source) throws Exception {
        final long[] count = {0};
        write(context, target, out -> count[0] = copy(source, out));
        return count[0];
    }

    /** Whole plaintext of a vault file in memory. For images being displayed, not videos. */
    public static byte[] readAll(Context context, File file) throws IOException {
        try (InputStream in = openRead(context, file)) {
            return readAll(in);
        }
    }

    /** Decrypts a vault file into an ordinary file, e.g. when restoring it to the gallery. */
    public static void decryptTo(Context context, File vaultFile, OutputStream out) throws IOException {
        try (InputStream in = openRead(context, vaultFile)) {
            copy(in, out);
        }
    }

    /** Removes write leftovers from a crash mid-import or mid-restore. */
    public static void removeIncompleteWrites(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(PART_SUFFIX));
        if (files == null) return;
        for (File f : files) {
            if (!f.delete()) Log.w(TAG, "Could not remove " + f.getName());
        }
    }

    /**
     * A {@link MediaDataSource} that decrypts on demand, so MediaMetadataRetriever can read a
     * video's first frame straight out of the vault without a plaintext temp copy.
     */
    public static MediaDataSource mediaDataSource(Context context, File vaultFile) throws IOException {
        final SeekableByteChannel channel = openSeekable(context, vaultFile);
        return new MediaDataSource() {
            @Override
            public synchronized int readAt(long position, byte[] buffer, int offset, int size)
                    throws IOException {
                if (size == 0) return 0;
                if (position >= channel.size()) return -1;
                channel.position(position);
                return channel.read(ByteBuffer.wrap(buffer, offset, size));
            }

            @Override
            public synchronized long getSize() throws IOException {
                return channel.size();
            }

            @Override
            public synchronized void close() throws IOException {
                channel.close();
            }
        };
    }

    /** fsyncs before closing, so a rename never publishes bytes still in the page cache. */
    private static final class SyncingOutputStream extends FilterOutputStream {
        private final FileOutputStream file;

        SyncingOutputStream(FileOutputStream file) {
            super(file);
            this.file = file;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                file.flush();
                file.getFD().sync();
            } finally {
                file.close();
            }
        }
    }

    // =====================================================================================
    // 2. Portable payloads
    // =====================================================================================

    public static final int VERSION_LEGACY = 1;
    public static final int VERSION_GCM = 2;
    public static final int VERSION_STREAM = 3;

    private static final byte[] MAGIC = {'N', 'P'};
    private static final int KEY_ID_LEN = 8;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    /** Ciphertext segment of the v3 stream. Bounds memory; 256 KB keeps overhead under 0.01%. */
    private static final int STREAM_SEGMENT = 256 * 1024;
    private static final byte[] STREAM_KEY_LABEL =
            "notes-plus/portable-stream/v3".getBytes(StandardCharsets.UTF_8);

    /** OWASP's floor for PBKDF2-HMAC-SHA256. */
    public static final int PBKDF2_ITERATIONS = 210_000;
    /** What v1 payloads were written with; needed to read them back. */
    public static final int LEGACY_PBKDF2_ITERATIONS = 65_536;

    /** Supplies the key for a payload once its header says which one it needs. */
    public interface KeyResolver {
        /** Data key for a v2/v3 payload stamped with this keyId. */
        SecretKey resolveDataKey(String keyId) throws Exception;

        /** v1: derive from the lock value using the salt found in the header. */
        SecretKey resolveLegacyKey(byte[] salt) throws Exception;
    }

    /** Thrown when no wrap in a keyring opens with the supplied password. */
    public static class WrongPasswordException extends Exception {
        public WrongPasswordException(String message) {
            super(message);
        }
    }

    /** Header fields of a payload, parsed before any plaintext is produced. */
    public static final class Header {
        public final int version;
        public final String keyId;   // v2, v3
        public final byte[] salt;    // v1
        final byte[] raw;            // v3: the exact bytes, used as associated data

        Header(int version, String keyId, byte[] salt, byte[] raw) {
            this.version = version;
            this.keyId = keyId;
            this.salt = salt;
            this.raw = raw;
        }

        public boolean isLegacy() {
            return version == VERSION_LEGACY;
        }
    }

    /** Streams {@code in} into {@code out} as a v3 payload. Neither stream is closed. */
    public static void encrypt(InputStream in, OutputStream out, SecretKey dataKey, String keyId)
            throws Exception {
        try (OutputStream encrypted = encryptingStream(out, dataKey, keyId)) {
            copy(in, encrypted);
        }
        out.flush();
    }

    /**
     * Writes a v3 header to {@code out} and returns a stream that encrypts into it. Closing
     * the returned stream finalises the payload but leaves {@code out} open.
     */
    public static OutputStream encryptingStream(OutputStream out, SecretKey dataKey, String keyId)
            throws Exception {
        byte[] keyIdBytes = hexToBytes(keyId);
        if (keyIdBytes.length != KEY_ID_LEN) {
            throw new IllegalArgumentException("keyId must be " + KEY_ID_LEN + " bytes");
        }
        byte[] header = new byte[3 + KEY_ID_LEN];
        header[0] = MAGIC[0];
        header[1] = MAGIC[1];
        header[2] = VERSION_STREAM;
        System.arraycopy(keyIdBytes, 0, header, 3, KEY_ID_LEN);

        out.write(header);
        return portable(dataKey).newEncryptingStream(new NonClosingOutputStream(out), header);
    }

    /** Reads a payload header. The stream is left at the start of the ciphertext. */
    public static Header readHeader(PushbackInputStream in) throws IOException {
        byte[] prefix = new byte[3];
        int read = fill(in, prefix);
        if (read < 3) throw new IOException("Truncated backup: header is only " + read + " bytes");

        boolean magic = prefix[0] == MAGIC[0] && prefix[1] == MAGIC[1];
        if (magic && (prefix[2] == VERSION_GCM || prefix[2] == VERSION_STREAM)) {
            byte[] keyId = new byte[KEY_ID_LEN];
            if (fill(in, keyId) < KEY_ID_LEN) throw new IOException("Truncated backup: key id");
            byte[] raw = new byte[3 + KEY_ID_LEN];
            System.arraycopy(prefix, 0, raw, 0, 3);
            System.arraycopy(keyId, 0, raw, 3, KEY_ID_LEN);
            return new Header(prefix[2], bytesToHex(keyId), null, raw);
        }

        // No magic: this predates versioning, and the first 16 bytes are the PBKDF2 salt.
        in.unread(prefix, 0, 3);
        byte[] salt = new byte[SALT_LEN];
        if (fill(in, salt) < SALT_LEN) throw new IOException("Truncated backup: salt");
        return new Header(VERSION_LEGACY, null, salt, null);
    }

    /**
     * Plaintext view of a payload whose header has been read.
     *
     * v3 streams with flat memory. v1/v2 are single-shot GCM and must be decrypted whole;
     * they only ever came from releases whose writer had the same in-memory limit, so any
     * that exist fit.
     *
     * A v3 stream verifies each segment as it is read and fails on truncation at the end, so
     * a caller must treat what it consumed as untrusted until the stream reports EOF.
     */
    public static InputStream openDecrypted(PushbackInputStream in, Header header,
                                            KeyResolver resolver) throws Exception {
        if (header.version == VERSION_STREAM) {
            SecretKey key = resolver.resolveDataKey(header.keyId);
            return portable(key).newDecryptingStream(in, header.raw);
        }
        byte[] iv = new byte[IV_LEN];
        if (fill(in, iv) < IV_LEN) throw new IOException("Truncated backup: iv");
        SecretKey key = header.isLegacy()
                ? resolver.resolveLegacyKey(header.salt)
                : resolver.resolveDataKey(header.keyId);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new java.io.ByteArrayInputStream(cipher.doFinal(readAll(in)));
    }

    /** Decrypts a whole payload of any version into {@code out}. Neither stream is closed. */
    public static void decrypt(InputStream in, OutputStream out, KeyResolver resolver)
            throws Exception {
        PushbackInputStream pushback = new PushbackInputStream(in, 3);
        Header header = readHeader(pushback);
        copy(openDecrypted(pushback, header, resolver), out);
        out.flush();
    }

    /** Small payloads (keyring wraps). */
    static byte[] sealBytes(byte[] plaintext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    static byte[] openBytes(byte[] ciphertext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    /**
     * The streaming primitive for a data key. The data key is never used directly: a
     * label-specific subkey keeps v3 and the older single-shot format cryptographically apart.
     */
    private static StreamingAead portable(SecretKey dataKey) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(dataKey.getEncoded(), "HmacSHA256"));
        byte[] subkey = mac.doFinal(STREAM_KEY_LABEL);
        return new AesGcmHkdfStreaming(subkey, "HmacSha256", 32, STREAM_SEGMENT, 0);
    }

    /** Lets Tink close its encrypting stream (which finalises the last segment) safely. */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }

    // ------------------------------------------------------------------ key derivation

    /** Derives an AES key from a password. Only ever wraps the data key, never payloads. */
    public static SecretKey deriveKey(String password, byte[] salt, int iterations) throws Exception {
        return new SecretKeySpec(pbkdf2(password, salt, iterations), "AES");
    }

    /**
     * PBKDF2-HMAC-SHA256 with a 32-byte output. The platform implementation only exists from
     * API 26; below that (minSdk is 24) the same function is computed directly.
     */
    static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException e) {
            return pbkdf2Direct(password.getBytes(StandardCharsets.UTF_8), salt, iterations);
        }
    }

    /** RFC 8018 PBKDF2 for a single 32-byte block (dkLen == hLen). */
    static byte[] pbkdf2Direct(byte[] password, byte[] salt, int iterations) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        // HMAC zero-pads its key, so one zero byte is the empty key, which SecretKeySpec rejects.
        mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA256"));
        mac.update(salt);
        byte[] u = mac.doFinal(new byte[]{0, 0, 0, 1});
        byte[] result = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < result.length; j++) result[j] ^= u[j];
        }
        return result;
    }

    // =====================================================================================
    // Key management: the Vault Data Key and the keyring that wraps it
    // =====================================================================================

    private static final String KEY_DATA_KEY = "vault_data_key";
    private static final String KEY_DATA_KEY_ID = "vault_data_key_id";
    /** A wrap made with the optional backup password; when present it is the only wrap. */
    private static final String KEY_BACKUP_WRAP = "backup_password_wrap";
    /**
     * Set only for installs that existed before the key hierarchy, which may have payloads
     * encrypted with a lock value. A fresh install never sees the migration.
     */
    private static final String KEY_LEGACY_PRESENT = "legacy_payloads_present";

    public static final String KEYRING_FILE = "keyring.json";
    private static final int KEYRING_FORMAT_VERSION = 2;
    private static final int DATA_KEY_BYTES = 32;

    private static volatile SecretKey cachedDataKey;
    private static volatile String cachedKeyId;

    /** This installation's data key, generated on first use and cached for the process. */
    public static synchronized SecretKey getOrCreateDataKey(Context context) {
        if (cachedDataKey != null) return cachedDataKey;
        SharedPreferences prefs = SecurityHelper.getEncryptedPrefs(context);
        String stored = prefs.getString(KEY_DATA_KEY, null);
        String storedId = prefs.getString(KEY_DATA_KEY_ID, null);
        if (stored != null && storedId != null) {
            cachedDataKey = new SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES");
            cachedKeyId = storedId;
            return cachedDataKey;
        }

        byte[] fresh = randomBytes(DATA_KEY_BYTES);
        String keyId = bytesToHex(randomBytes(KEY_ID_LEN));
        // commit, not apply: losing this write would orphan everything encrypted with it.
        boolean saved = prefs.edit()
                .putString(KEY_DATA_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
                .putString(KEY_DATA_KEY_ID, keyId)
                .commit();
        if (!saved) throw new IllegalStateException("Could not persist the vault data key");
        Log.i(TAG, "Generated a new vault data key");
        cachedDataKey = new SecretKeySpec(fresh, "AES");
        cachedKeyId = keyId;
        return cachedDataKey;
    }

    /** Identifier stamped into every payload, so a key mismatch is detected, not guessed. */
    public static synchronized String getKeyId(Context context) {
        getOrCreateDataKey(context);
        return cachedKeyId;
    }

    /** Adopts a data key recovered from a backup, so that backup's payloads stay readable. */
    public static synchronized void adoptDataKey(Context context, SecretKey key, String keyId) {
        boolean saved = SecurityHelper.getEncryptedPrefs(context).edit()
                .putString(KEY_DATA_KEY, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP))
                .putString(KEY_DATA_KEY_ID, keyId)
                .commit();
        if (!saved) throw new IllegalStateException("Could not persist the adopted data key");
        cachedDataKey = key;
        cachedKeyId = keyId;
        Log.i(TAG, "Adopted data key " + keyId + " from a backup");
    }

    /**
     * Builds the keyring that ships with every backup.
     *
     * With a backup password set, it is the only wrap: vault PINs are short enough to be
     * brute-forced offline, so they must not be able to open anything that leaves the phone.
     * Without one, every vault lock gets a wrap, so any of them can open the backup.
     */
    public static JSONObject buildKeyring(Context context, List<VaultEntity> vaults) throws Exception {
        SecretKey dataKey = getOrCreateDataKey(context);
        JSONObject keyring = new JSONObject();
        keyring.put("formatVersion", KEYRING_FORMAT_VERSION);
        keyring.put("keyId", getKeyId(context));

        JSONArray wraps = new JSONArray();
        String backupWrap = SecurityHelper.getEncryptedPrefs(context).getString(KEY_BACKUP_WRAP, null);
        if (backupWrap != null) {
            wraps.put(new JSONObject(backupWrap));
        } else {
            List<String> seen = new ArrayList<>();
            for (VaultEntity vault : vaults) {
                String lockValue = vault.getLockValue();
                if (lockValue == null || lockValue.isEmpty() || seen.contains(lockValue)) continue;
                seen.add(lockValue);
                wraps.put(wrap(dataKey, lockValue, vault.getName()));
            }
        }
        keyring.put("wraps", wraps);
        return keyring;
    }

    public static boolean hasBackupPassword(Context context) {
        return SecurityHelper.getEncryptedPrefs(context).contains(KEY_BACKUP_WRAP);
    }

    /** Sets (or with null, removes) the backup password. Only the wrap is stored. */
    public static void setBackupPassword(Context context, String password) throws Exception {
        SharedPreferences.Editor editor = SecurityHelper.getEncryptedPrefs(context).edit();
        if (password == null) {
            editor.remove(KEY_BACKUP_WRAP);
        } else {
            editor.putString(KEY_BACKUP_WRAP,
                    wrap(getOrCreateDataKey(context), password, "Backup password").toString());
        }
        if (!editor.commit()) throw new IOException("Could not save the backup password");
    }

    private static JSONObject wrap(SecretKey dataKey, String password, String label) throws Exception {
        byte[] salt = randomBytes(SALT_LEN);
        byte[] iv = randomBytes(IV_LEN);
        SecretKey wrappingKey = deriveKey(password, salt, PBKDF2_ITERATIONS);
        byte[] wrapped = sealBytes(dataKey.getEncoded(), wrappingKey, iv);

        JSONObject entry = new JSONObject();
        entry.put("label", label == null ? "Vault" : label);
        entry.put("iterations", PBKDF2_ITERATIONS);
        entry.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        entry.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        entry.put("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP));
        return entry;
    }

    /** Tries every wrap against one password, so the caller need not know which it opens. */
    public static SecretKey unwrapDataKey(JSONObject keyring, String password) throws Exception {
        if (keyring == null) throw new WrongPasswordException("No keyring available");
        JSONArray wraps = keyring.optJSONArray("wraps");
        if (wraps == null || wraps.length() == 0) {
            throw new WrongPasswordException("Keyring contains no wrapped keys");
        }
        for (int i = 0; i < wraps.length(); i++) {
            JSONObject entry = wraps.getJSONObject(i);
            try {
                byte[] salt = Base64.decode(entry.getString("salt"), Base64.NO_WRAP);
                byte[] iv = Base64.decode(entry.getString("iv"), Base64.NO_WRAP);
                byte[] wrapped = Base64.decode(entry.getString("wrapped"), Base64.NO_WRAP);
                int iterations = entry.optInt("iterations", PBKDF2_ITERATIONS);
                SecretKey wrappingKey = deriveKey(password, salt, iterations);
                return new SecretKeySpec(openBytes(wrapped, wrappingKey, iv), "AES");
            } catch (Exception e) {
                // Wrong password for this wrap; the next one may match.
            }
        }
        throw new WrongPasswordException("That password does not open this backup");
    }

    public static String keyIdOf(JSONObject keyring) {
        return keyring == null ? null : keyring.optString("keyId", null);
    }

    /**
     * Resolver for this device's own payloads: v2/v3 use the local data key, v1 falls back to
     * a lock value the caller already has.
     */
    public static KeyResolver localResolver(final Context context, final String legacyPassword) {
        return new KeyResolver() {
            @Override
            public SecretKey resolveDataKey(String keyId) throws Exception {
                SecretKey local = getOrCreateDataKey(context);
                String localId = getKeyId(context);
                if (keyId != null && localId != null && !keyId.equals(localId)) {
                    throw new WrongPasswordException("This backup was made with a different vault key");
                }
                return local;
            }

            @Override
            public SecretKey resolveLegacyKey(byte[] salt) throws Exception {
                if (legacyPassword == null) {
                    throw new WrongPasswordException("This backup needs its original lock");
                }
                return deriveKey(legacyPassword, salt, LEGACY_PBKDF2_ITERATIONS);
            }
        };
    }

    /**
     * Resolver for a backup that may come from another installation: the local key if the
     * ids match, otherwise the backup's own keyring opened with {@code password}.
     */
    public static KeyResolver keyringResolver(final Context context, final JSONObject keyring,
                                              final String password) {
        return new KeyResolver() {
            private SecretKey unwrapped;

            @Override
            public SecretKey resolveDataKey(String keyId) throws Exception {
                if (keyId != null && keyId.equals(getKeyId(context))) return getOrCreateDataKey(context);
                if (unwrapped == null) unwrapped = unwrapDataKey(keyring, password);
                return unwrapped;
            }

            @Override
            public SecretKey resolveLegacyKey(byte[] salt) throws Exception {
                return deriveKey(password, salt, LEGACY_PBKDF2_ITERATIONS);
            }
        };
    }

    // ------------------------------------------------- legacy migration gating

    /** True while pre-7.50 payloads may still exist; a lock change is gated until resolved. */
    public static boolean hasUnmigratedLegacyPayloads(Context context) {
        return SecurityHelper.getAppPrefs(context).getBoolean(KEY_LEGACY_PRESENT, false);
    }

    public static void markLegacyPayloadsCleared(Context context, boolean cleared) {
        SecurityHelper.getAppPrefs(context).edit().putBoolean(KEY_LEGACY_PRESENT, !cleared).apply();
    }

    public static void markLegacyPayloadsPossible(Context context) {
        SecurityHelper.getAppPrefs(context).edit().putBoolean(KEY_LEGACY_PRESENT, true).apply();
    }

    // =====================================================================================
    // 3. Plaintext exports
    // =====================================================================================

    private static final String DIR_SHARE = "share";

    /** Directories and file prefixes earlier versions left decrypted media in. */
    private static final String[] LEGACY_DIRS = {"viewer", "thumbwork", "image_manager_disk_cache"};
    private static final String[] LEGACY_PREFIXES = {
            "thumb_", "thumb_album_", "pager_", "share_", "temp_view_", "temp_play_",
            "restore_", "cloud_", "v_", "tmp_", "up_", "backup_temp", "restore_temp",
            "migrate_", "local_restore"
    };

    /** Decrypted copies handed to other apps through the FileProvider. */
    public static File shareDir(Context context) {
        File dir = new File(context.getCacheDir(), DIR_SHARE);
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create " + dir);
        return dir;
    }

    /** Decrypts a vault file into the share directory under a name other apps can read. */
    public static File exportForShare(Context context, File vaultFile, String extension)
            throws IOException {
        if (!extension.startsWith(".")) extension = "." + extension;
        File out = new File(shareDir(context), vaultFile.getName() + extension);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            decryptTo(context, vaultFile, fos);
        }
        return out;
    }

    /**
     * Wipes every decrypted copy. Run on entry to the vault, never on exit: a file just
     * shared may not have been read by the receiving app yet.
     */
    public static void sweep(Context context) {
        File cache = context.getCacheDir();
        wipeContents(new File(cache, DIR_SHARE));
        for (String dir : LEGACY_DIRS) {
            File legacy = new File(cache, dir);
            wipeContents(legacy);
            if (legacy.exists() && !legacy.delete()) Log.w(TAG, "Could not remove " + dir);
        }
        File[] files = cache.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            for (String prefix : LEGACY_PREFIXES) {
                if (f.getName().startsWith(prefix)) {
                    if (!f.delete()) Log.w(TAG, "Could not delete residue " + f.getName());
                    break;
                }
            }
        }
    }

    public static void wipeContents(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursive(child);
    }

    public static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file.getName());
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    public static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            total += len;
        }
        return total;
    }

    /** Reads a stream fully. Only for content known to be small or already bounded. */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static int fill(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) break;
            offset += read;
        }
        return offset;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Not a hex string: " + hex);
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
