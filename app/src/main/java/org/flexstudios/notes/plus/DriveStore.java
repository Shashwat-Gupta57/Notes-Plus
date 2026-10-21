package org.flexstudios.notes.plus;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Thin, safe layer over the Drive API. Everything above it deals in names, not file ids.
 *
 * Replaces the ad-hoc helpers in the old GoogleDriveManager, which had three structural
 * problems this class exists to remove:
 *
 * <ul>
 *   <li>{@code findFileId} <em>deleted</em> duplicate results as a side effect of a lookup, so
 *       two concurrent syncs could destroy each other's uploads. Reads here never mutate;
 *       duplicate cleanup is an explicit call.</li>
 *   <li>{@code findFileGlobally} ignored its parent argument and searched the whole Drive by
 *       bare name. Every lookup here is parent-scoped.</li>
 *   <li>Query literals were concatenated straight into the query string, so an apostrophe in
 *       a vault or album name broke or altered it. Everything goes through {@link #escape}.</li>
 * </ul>
 *
 * Folder ids are resolved once per instance and cached, replacing the old per-item
 * {@code getOrCreateFolder} calls inside download loops.
 *
 * Also carries the small JSON documents (index, restore points, keyring). All of them go up
 * encrypted with the vault data key except the keyring, which has to be readable before any
 * key is known. The old code uploaded {@code settings.json} in the clear with every vault's
 * lock value inside it.
 */
public class DriveStore {

    private static final String TAG = "DriveStore";

    /** Kept as-is so backups written by 7.50 and earlier stay reachable. */
    public static final String BACKUP_PATH = "Notes-plus/Saved-Notes/Cloud/Remote/Backup";

    public static final String FOLDER_OBJECTS = "objects";
    public static final String FOLDER_SNAPSHOTS = "snapshots";
    public static final String FOLDER_BACKGROUNDS = "backgrounds";

    private static final String MIME_FOLDER = "application/vnd.google-apps.folder";
    private static final int MAX_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1000;

    /** Credentials are gone or revoked. Never worth retrying; the user must reconnect. */
    public static class AuthException extends IOException {
        public AuthException(String message) { super(message); }
    }

    /** The Drive account is out of space. Retrying cannot help. */
    public static class QuotaException extends IOException {
        public QuotaException(String message) { super(message); }
    }

    private static final String MIME_JSON = "application/json";
    private static final String MIME_BINARY = "application/octet-stream";

    private final Context context;
    private final Drive drive;
    /** parentId + '/' + name -> folder id. Populated lazily, valid for one sync run. */
    private final Map<String, String> folderCache = new HashMap<>();

    public DriveStore(Context context, GoogleSignInAccount account) {
        this.context = context.getApplicationContext();
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE));
        credential.setSelectedAccount(account.getAccount());
        HttpTransport transport = new NetHttpTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        this.drive = new Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName("Notes+").build();
    }

    public static GoogleSignInClient signInClient(Context context) {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                .build();
        return GoogleSignIn.getClient(context, options);
    }

    // ------------------------------------------------------------------ folders

    /** Resolves the backup root, creating the folder chain if this is a first run. */
    public String rootFolder() throws IOException {
        String parentId = "root";
        for (String part : BACKUP_PATH.split("/")) {
            parentId = folder(part, parentId);
        }
        return parentId;
    }

    /** Folder id for {@code name} under {@code parentId}, created on first use and cached. */
    public String folder(String name, String parentId) throws IOException {
        String cacheKey = parentId + "/" + name;
        String cached = folderCache.get(cacheKey);
        if (cached != null) return cached;

        String existing = findFile(name, parentId);
        if (existing == null) {
            com.google.api.services.drive.model.File metadata =
                    new com.google.api.services.drive.model.File()
                            .setName(name)
                            .setMimeType(MIME_FOLDER)
                            .setParents(Collections.singletonList(parentId));
            existing = call(() -> drive.files().create(metadata).setFields("id").execute().getId());
        }
        folderCache.put(cacheKey, existing);
        return existing;
    }

    /** Drops cached folder ids. Call when a restore may have replaced the remote layout. */
    public void resetFolderCache() {
        folderCache.clear();
    }

    // -------------------------------------------------------------------- reads

    /**
     * Newest file with this exact name under this exact parent, or null.
     *
     * Deliberately non-destructive: duplicates are reported by {@link #findDuplicates} and
     * removed only by an explicit {@link #deleteFile} from the reconcile step.
     */
    public String findFile(String name, String parentId) throws IOException {
        List<com.google.api.services.drive.model.File> matches = findAll(name, parentId);
        return matches.isEmpty() ? null : matches.get(0).getId();
    }

    /** All same-named entries under a parent, newest first. */
    public List<com.google.api.services.drive.model.File> findAll(String name, String parentId)
            throws IOException {
        String query = "name = '" + escape(name) + "' and '" + escape(parentId)
                + "' in parents and trashed = false";
        List<com.google.api.services.drive.model.File> files = list(query, "id, name, size, createdTime");
        if (files.size() > 1) {
            files.sort((a, b) -> {
                String left = a.getCreatedTime() == null ? "" : a.getCreatedTime().toString();
                String right = b.getCreatedTime() == null ? "" : b.getCreatedTime().toString();
                return right.compareTo(left);
            });
        }
        return files;
    }

    /** Stale same-named copies under a parent, i.e. everything but the newest. */
    public List<String> findDuplicates(String name, String parentId) throws IOException {
        List<com.google.api.services.drive.model.File> matches = findAll(name, parentId);
        List<String> stale = new ArrayList<>();
        for (int i = 1; i < matches.size(); i++) stale.add(matches.get(i).getId());
        return stale;
    }

    /** Every child of a folder. Pages through the whole result set. */
    public List<com.google.api.services.drive.model.File> listChildren(String parentId)
            throws IOException {
        return list("'" + escape(parentId) + "' in parents and trashed = false", "id, name, size");
    }

    public List<com.google.api.services.drive.model.File> list(String query, String fields)
            throws IOException {
        List<com.google.api.services.drive.model.File> all = new ArrayList<>();
        String pageToken = null;
        do {
            final String token = pageToken;
            FileList result = call(() -> drive.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setPageSize(1000)
                    .setFields("nextPageToken, files(" + fields + ")")
                    .setPageToken(token)
                    .execute());
            if (result.getFiles() != null) all.addAll(result.getFiles());
            pageToken = result.getNextPageToken();
        } while (pageToken != null);
        return all;
    }

    // ------------------------------------------------------------------- writes

    /**
     * Creates or replaces {@code name} under {@code parentId}.
     *
     * Unconditional by design. The old code skipped the upload when the remote size matched
     * the local length, which silently dropped any change that preserved length; callers here
     * decide what to skip using the store index, which carries a content hash.
     */
    public String putFile(File local, String name, String mimeType, String parentId)
            throws IOException {
        String existingId = findFile(name, parentId);
        FileContent content = new FileContent(mimeType, local);
        if (existingId != null) {
            final String id = existingId;
            call(() -> drive.files().update(id, null, content).execute());
            return existingId;
        }
        com.google.api.services.drive.model.File metadata =
                new com.google.api.services.drive.model.File()
                        .setName(name)
                        .setParents(Collections.singletonList(parentId));
        return call(() -> drive.files().create(metadata, content).setFields("id").execute().getId());
    }

    public void downloadFile(String fileId, File target) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            call(() -> {
                drive.files().get(fileId).executeMediaAndDownloadTo(out);
                return null;
            });
        }
    }

    public void deleteFile(String fileId) throws IOException {
        try {
            call(() -> {
                drive.files().delete(fileId).execute();
                return null;
            });
        } catch (GoogleJsonResponseException e) {
            // Already gone is the state we wanted.
            if (e.getStatusCode() != 404) throw e;
        }
    }

    // ---------------------------------------------------------------- documents

    /** Uploads a JSON document encrypted with the vault data key. */
    public void writeEncrypted(String name, String parentId, JSONObject document) throws Exception {
        byte[] plaintext = document.toString().getBytes(StandardCharsets.UTF_8);
        File temp = scratch(name);
        try {
            try (ByteArrayInputStream in = new ByteArrayInputStream(plaintext);
                 FileOutputStream out = new FileOutputStream(temp)) {
                VaultCrypto.encrypt(in, out, VaultCrypto.getOrCreateDataKey(context),
                        VaultCrypto.getKeyId(context));
            }
            putFile(temp, name, MIME_BINARY, parentId);
        } finally {
            delete(temp);
        }
    }

    /**
     * Downloads and decrypts a JSON document; null when it does not exist.
     *
     * A network failure is thrown as an IOException so callers can tell "not there" from
     * "couldn't ask". A genuinely plain JSON document (a pre-7.50 {@code settings.json}) is
     * returned as-is.
     */
    public JSONObject readEncrypted(String name, String parentId, VaultCrypto.KeyResolver resolver)
            throws Exception {
        byte[] raw = download(name, parentId);
        if (raw == null) return null;
        JSONObject plain = tryParsePlainJson(raw);
        if (plain != null) return plain;
        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        try {
            VaultCrypto.decrypt(new ByteArrayInputStream(raw), decrypted, resolver);
        } catch (IOException corrupt) {
            // The bytes are already local, so this is damage, not the network.
            throw new java.security.GeneralSecurityException("Unreadable document " + name, corrupt);
        }
        return new JSONObject(new String(decrypted.toByteArray(), StandardCharsets.UTF_8));
    }

    /** The keyring, which must be readable before any key is available. */
    public void writePlain(String name, String parentId, JSONObject document) throws Exception {
        File temp = scratch(name);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(document.toString().getBytes(StandardCharsets.UTF_8));
            }
            putFile(temp, name, MIME_JSON, parentId);
        } finally {
            delete(temp);
        }
    }

    public JSONObject readPlain(String name, String parentId) throws Exception {
        byte[] raw = download(name, parentId);
        return raw == null ? null : new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    private byte[] download(String name, String parentId) throws IOException {
        String fileId = findFile(name, parentId);
        if (fileId == null) return null;
        File temp = scratch(name);
        try {
            downloadFile(fileId, temp);
            try (FileInputStream in = new FileInputStream(temp)) {
                return VaultCrypto.readAll(in);
            }
        } finally {
            delete(temp);
        }
    }

    /**
     * An encrypted payload starts with the "NP" magic or with random salt; a pre-7.50 plain
     * document starts with '{'.
     */
    private static JSONObject tryParsePlainJson(byte[] raw) {
        int i = 0;
        while (i < raw.length && Character.isWhitespace((char) raw[i])) i++;
        if (i >= raw.length || raw[i] != '{') return null;
        try {
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private File scratch(String name) throws IOException {
        File dir = new File(context.getCacheDir(), "clouddocs");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create scratch directory");
        File temp = new File(dir, name);
        delete(temp);
        return temp;
    }

    private static void delete(File file) {
        if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Escapes a Drive query string literal.
     *
     * Vault names, album names and directory names read back out of a cloud manifest all end
     * up here, and any of them may contain an apostrophe.
     */
    public static String escape(String literal) {
        if (literal == null) return "";
        return literal.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Runs a Drive call with exponential backoff on rate limits and server errors.
     *
     * Previously a single 429 aborted the entire sync and WorkManager restarted it from the
     * beginning. Auth and quota failures are rethrown as typed exceptions so the worker can
     * tell the user something useful instead of retrying forever.
     */
    private <T> T call(Callable<T> operation) throws IOException {
        IOException last = null;
        long backoff = INITIAL_BACKOFF_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return operation.call();
            } catch (GoogleJsonResponseException e) {
                int status = e.getStatusCode();
                String reason = reasonOf(e);

                if (status == 401 || "authError".equals(reason)) {
                    throw new AuthException("Google Drive access has expired. Reconnect to continue.");
                }
                if ("storageQuotaExceeded".equals(reason)) {
                    throw new QuotaException("Google Drive is full. Free up space and we'll finish.");
                }
                boolean transientFailure = status == 429 || status >= 500
                        || "rateLimitExceeded".equals(reason)
                        || "userRateLimitExceeded".equals(reason);
                if (!transientFailure) throw e;

                last = e;
            } catch (IOException e) {
                last = e; // Network blip; worth another attempt.
            } catch (Exception e) {
                throw new IOException(e);
            }

            if (attempt < MAX_ATTEMPTS) {
                Log.w(TAG, "Drive call failed, retrying in " + backoff + "ms", last);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Sync interrupted", interrupted);
                }
                backoff *= 2;
            }
        }
        throw last != null ? last : new IOException("Drive call failed");
    }

    private static String reasonOf(GoogleJsonResponseException e) {
        if (e.getDetails() == null || e.getDetails().getErrors() == null
                || e.getDetails().getErrors().isEmpty()) {
            return "";
        }
        String reason = e.getDetails().getErrors().get(0).getReason();
        return reason == null ? "" : reason;
    }
}
