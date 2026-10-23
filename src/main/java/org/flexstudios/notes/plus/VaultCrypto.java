package org.flexstudios.notes.plus;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The vault's only crypto code, the desktop twin of the phone's VaultCrypto.
 *
 * Media is encrypted with Tink's streaming AEAD (AES256_GCM_HKDF_4KB, the phone's scheme),
 * associated data = the file's name, under one random <b>vault data key</b>. That key never
 * touches the disk in the clear: {@code vault/keyring.json} holds it wrapped once per vault,
 * under a key derived from that vault's lock (PBKDF2-SHA256, AES-GCM). So unlocking any vault
 * opens the data, and changing a lock rewrites a few hundred bytes and touches no media.
 *
 * Locks themselves are kept only as hashes ({@code h1:salt:hash}) in the database. Their
 * values are also kept encrypted under the data key ({@code vault/locks.bin}), readable only
 * while a vault is open, because a backup must open on the phone with any vault's lock (the
 * phone's own keyring wraps its backup key under each lock value).
 *
 * The data key is a bundle: the media keyset, plus the phone-compatible <b>backup key</b>
 * (32 bytes, with an 8-byte id) that .bak payloads are encrypted with.
 *
 * Writes go to {@code <name>.part} and are renamed when complete, so a crash never leaves a
 * half-written item looking whole.
 */
final class VaultCrypto {

    private VaultCrypto() {}

    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile StreamingAead aead;
    private static volatile byte[] dataKey;
    private static volatile byte[] backupKey;
    private static volatile String backupKeyId;

    static {
        try {
            StreamingAeadConfig.register();
        } catch (Exception e) {
            throw new IllegalStateException("Tink unavailable", e);
        }
    }

    static Path dir() {
        Path d = Storage.root().resolve("vault");
        try {
            Files.createDirectories(d.resolve("media"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    static Path mediaDir() {
        return dir().resolve("media");
    }

    private static Path keyring() {
        return dir().resolve("keyring.json");
    }

    static boolean isUnlocked() {
        return aead != null;
    }

    /** Forgets the data key: the vault is closed. */
    static synchronized void lock() {
        if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
        if (backupKey != null) Arrays.fill(backupKey, (byte) 0);
        dataKey = null;
        backupKey = null;
        backupKeyId = null;
        aead = null;
    }

    /** The phone-compatible key .bak payloads are encrypted with; the vault must be open. */
    static byte[] backupKey() throws IOException {
        if (backupKey == null) throw new IOException("The vault is locked");
        return backupKey.clone();
    }

    static String backupKeyId() throws IOException {
        if (backupKeyId == null) throw new IOException("The vault is locked");
        return backupKeyId;
    }

    // ================================================================ locks

    /** A lock as stored: {@code h1:<salt hex>:<PBKDF2 hex>}, over its type and value. */
    static String hashLock(String type, String value) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "h1:" + HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(derive(salt, type, value, "hash"));
    }

    static boolean matches(String stored, String type, String value) {
        if (stored == null || !stored.startsWith("h1:")) return false;
        String[] parts = stored.split(":");
        if (parts.length != 3) return false;
        byte[] want = HexFormat.of().parseHex(parts[2]);
        byte[] got = derive(HexFormat.of().parseHex(parts[1]), type, value, "hash");
        return MessageDigest.isEqual(want, got);
    }

    private static byte[] derive(byte[] salt, String type, String value, String purpose) {
        try {
            String secret = purpose + "\u0000" + type + "\u0000" + value;
            PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ============================================================== keyring

    static boolean hasKeyring() {
        return Files.exists(keyring());
    }

    /** The very first vault: a fresh data key, wrapped under its lock, and open. */
    static synchronized void create(String vaultUuid, String type, String value) throws IOException {
        try {
            KeysetHandle handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"));
            byte[] keyset = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
            byte[] backup = new byte[32], id = new byte[8];
            RANDOM.nextBytes(backup);
            RANDOM.nextBytes(id);
            open(new JSONObject().put("keyset", b64(keyset)).put("backupKey", b64(backup))
                    .put("keyId", HexFormat.of().formatHex(id)).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("Could not make the vault key", e);
        }
        wrapFor(vaultUuid, type, value);
    }

    /** Wraps the open data key under a vault's lock (a new vault, or a changed lock). */
    static synchronized void wrapFor(String vaultUuid, String type, String value) throws IOException {
        if (dataKey == null) throw new IOException("The vault is locked");
        byte[] salt = new byte[16], iv = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derive(salt, type, value, "wrap"), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(vaultUuid.getBytes(StandardCharsets.UTF_8));
            byte[] wrapped = c.doFinal(dataKey);
            JSONObject ring = readKeyring();
            ring.getJSONObject("entries").put(vaultUuid, new JSONObject()
                    .put("salt", b64(salt)).put("iv", b64(iv)).put("key", b64(wrapped)));
            writeKeyring(ring);
            JSONObject locks = readLocks();
            locks.put(vaultUuid, new JSONObject().put("type", type).put("value", value));
            writeLocks(locks);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not wrap the vault key", e);
        }
    }

    /** Opens the data with a vault's lock. False if this lock doesn't unwrap it. */
    static synchronized boolean unlock(String vaultUuid, String type, String value) {
        try {
            JSONObject e = readKeyring().getJSONObject("entries").optJSONObject(vaultUuid);
            if (e == null) return false;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(unb64(e.getString("salt")), type, value, "wrap"), "AES"),
                    new GCMParameterSpec(128, unb64(e.getString("iv"))));
            c.updateAAD(vaultUuid.getBytes(StandardCharsets.UTF_8));
            open(c.doFinal(unb64(e.getString("key"))));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void forget(String vaultUuid) throws IOException {
        JSONObject ring = readKeyring();
        ring.getJSONObject("entries").remove(vaultUuid);
        writeKeyring(ring);
        if (aead != null) {
            JSONObject locks = readLocks();
            locks.remove(vaultUuid);
            writeLocks(locks);
        }
    }

    /** A vault's lock value, for building a backup's keyring; the vault must be open. */
    static synchronized String lockValue(String vaultUuid) throws IOException {
        JSONObject l = readLocks().optJSONObject(vaultUuid);
        return l == null ? null : l.optString("value", null);
    }

    /** The optional backup password's wrap (phone format), stored as it is: only PBKDF2 opens it. */
    static synchronized JSONObject backupPasswordWrap() throws IOException {
        return readKeyring().optJSONObject("backupPasswordWrap");
    }

    static synchronized void setBackupPasswordWrap(JSONObject wrap) throws IOException {
        JSONObject ring = readKeyring();
        if (wrap == null) ring.remove("backupPasswordWrap");
        else ring.put("backupPasswordWrap", wrap);
        writeKeyring(ring);
    }

    private static Path locksFile() {
        return dir().resolve("locks.bin");
    }

    private static JSONObject readLocks() throws IOException {
        if (!Files.exists(locksFile())) return new JSONObject();
        return new JSONObject(new String(readAll(locksFile()), StandardCharsets.UTF_8));
    }

    private static void writeLocks(JSONObject locks) throws IOException {
        try (OutputStream out = write(dir(), "locks.bin")) {
            out.write(locks.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void open(byte[] bundle) throws Exception {
        JSONObject b = new JSONObject(new String(bundle, StandardCharsets.UTF_8));
        KeysetHandle handle = TinkProtoKeysetFormat.parseKeyset(unb64(b.getString("keyset")), InsecureSecretKeyAccess.get());
        aead = handle.getPrimitive(StreamingAead.class);
        backupKey = unb64(b.getString("backupKey"));
        backupKeyId = b.getString("keyId");
        dataKey = bundle;
    }

    private static JSONObject readKeyring() throws IOException {
        if (!hasKeyring()) return new JSONObject().put("version", 1).put("entries", new JSONObject());
        return new JSONObject(Files.readString(keyring(), StandardCharsets.UTF_8));
    }

    private static void writeKeyring(JSONObject ring) throws IOException {
        Path tmp = keyring().resolveSibling("keyring.json.part");
        Files.writeString(tmp, ring.toString(2), StandardCharsets.UTF_8);
        Files.move(tmp, keyring(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ================================================================ media

    private static StreamingAead aead() throws IOException {
        StreamingAead a = aead;
        if (a == null) throw new IOException("The vault is locked");
        return a;
    }

    /** Encrypts into {@code dir/name}: written as {@code .part}, renamed when closed. */
    static OutputStream write(Path dir, String name) throws IOException {
        Path part = dir.resolve(name + ".part");
        Path target = dir.resolve(name);
        FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        OutputStream raw = java.nio.channels.Channels.newOutputStream(channel);
        OutputStream enc;
        try {
            enc = aead().newEncryptingStream(raw, name.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            raw.close();
            Files.deleteIfExists(part);
            throw e instanceof IOException io ? io : new IOException(e);
        }
        return new FilterOutputStream(enc) {
            private boolean closed;

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                out.close();
                channel.close();
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        };
    }

    static InputStream read(Path file) throws IOException {
        try {
            return aead().newDecryptingStream(Files.newInputStream(file),
                    file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Random access to the plaintext, for videos: seeking decrypts only what is read. */
    static SeekableByteChannel seekable(Path file) throws IOException {
        try {
            return aead().newSeekableDecryptingChannel(Files.newByteChannel(file),
                    file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static byte[] readAll(Path file) throws IOException {
        try (InputStream in = read(file)) {
            return in.readAllBytes();
        }
    }

    /** Removes {@code .part} files a crash left behind. */
    static void removeIncompleteWrites() {
        try (var files = Files.list(mediaDir())) {
            files.filter(f -> f.getFileName().toString().endsWith(".part")).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // Next time.
                }
            });
        } catch (IOException ignored) {
            // Nothing to sweep.
        }
    }

    /** Where decrypted copies go for sharing: wiped when the vault opens and closes. */
    static Path shareDir() {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "notes-plus-share");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    static void wipeShareDir() {
        Storage.deleteRecursive(shareDir());
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }
}
