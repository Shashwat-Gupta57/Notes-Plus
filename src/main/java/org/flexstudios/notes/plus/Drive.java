package org.flexstudios.notes.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The user's own Google Drive, for note attachments: the phone's DriveStore, over Drive's
 * REST API with the {@code drive.file} scope (the app sees only the files it made, on the
 * phone or here, since both are the same Google Cloud project).
 *
 * Layout matches the phone: {@code Notes-plus/Attachments/<note uuid>/<file name>}.
 */
final class Drive {

    static final String APP_FOLDER = "Notes-plus";
    static final String ATTACHMENTS = "Attachments";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";

    /** Drive refused for good: the connection needs redoing. */
    static final class AuthException extends IOException {
        AuthException(String m) {
            super(m);
        }
    }

    /** The Drive is full. */
    static final class QuotaException extends IOException {
        QuotaException(String m) {
            super(m);
        }
    }

    private final String refreshToken;
    private final Map<String, String> folders = new HashMap<>();

    Drive(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    private String token() throws IOException {
        try {
            return GoogleAuth.accessToken(refreshToken);
        } catch (Firebase.Error e) {
            throw new AuthException("Google Drive needs reconnecting");
        }
    }

    // ================================================================ folders

    /** The id of folder {@code name} in {@code parent}, made if missing. */
    String folder(String name, String parent) throws IOException {
        String key = parent + "/" + name;
        String cached = folders.get(key);
        if (cached != null) return cached;
        String id = find(name, parent, true);
        if (id == null) {
            JSONObject meta = new JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
                    .put("parents", new JSONArray().put(parent));
            id = json(send(request(API + "?fields=id").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(meta.toString())))).getString("id");
        }
        folders.put(key, id);
        return id;
    }

    /** The id of a file or folder called {@code name} in {@code parent}, or null. */
    String find(String name, String parent, boolean folder) throws IOException {
        String q = "name = '" + escape(name) + "' and '" + escape(parent) + "' in parents and trashed = false"
                + (folder ? " and mimeType = '" + FOLDER_MIME + "'" : "");
        JSONObject r = json(send(request(API + "?fields=files(id)&spaces=drive&q=" + enc(q)).GET()));
        JSONArray files = r.optJSONArray("files");
        return files == null || files.isEmpty() ? null : files.getJSONObject(0).getString("id");
    }

    /** {@code Notes-plus/Attachments}. */
    String attachmentsRoot() throws IOException {
        return folder(ATTACHMENTS, folder(APP_FOLDER, "root"));
    }

    // ================================================================== files

    /** Uploads a file (resumable, so a big video survives a slow line); returns its id. */
    String putFile(Path file, String name, String mime, String parent) throws IOException {
        JSONObject meta = new JSONObject().put("name", name).put("parents", new JSONArray().put(parent));
        HttpResponse<String> start = send(request(UPLOAD + "?uploadType=resumable&fields=id")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mime)
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString())));
        String session = start.headers().firstValue("Location").orElseThrow(() -> new IOException("No upload session"));
        HttpResponse<String> done = send(HttpRequest.newBuilder(URI.create(session))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", mime)
                .PUT(HttpRequest.BodyPublishers.ofFile(file)));
        return json(done).getString("id");
    }

    /**
     * Uploads a file, replacing the newest one of that name in {@code parent} if there is one
     * (the phone's DriveStore.putFile): documents keep one copy, and a retried object doesn't
     * leave a twin. Returns its id.
     */
    String putOrReplace(Path file, String name, String mime, String parent) throws IOException {
        String existing = newest(name, parent);
        if (existing == null) return putFile(file, name, mime, parent);
        HttpResponse<String> start = send(request(UPLOAD + "/" + enc(existing) + "?uploadType=resumable&fields=id")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", mime)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{}")));
        String session = start.headers().firstValue("Location").orElseThrow(() -> new IOException("No upload session"));
        send(HttpRequest.newBuilder(URI.create(session))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", mime)
                .PUT(HttpRequest.BodyPublishers.ofFile(file)));
        return existing;
    }

    /** The newest file called {@code name} in {@code parent}, or null. */
    String newest(String name, String parent) throws IOException {
        String q = "name = '" + escape(name) + "' and '" + escape(parent) + "' in parents and trashed = false";
        JSONObject r = json(send(request(API + "?fields=files(id)&spaces=drive&orderBy=createdTime%20desc&q=" + enc(q)).GET()));
        JSONArray files = r.optJSONArray("files");
        return files == null || files.isEmpty() ? null : files.getJSONObject(0).getString("id");
    }

    /** Everything in {@code parent}: {id, name, size}, every page. */
    java.util.List<JSONObject> children(String parent) throws IOException {
        java.util.List<JSONObject> all = new java.util.ArrayList<>();
        String q = "'" + escape(parent) + "' in parents and trashed = false";
        String page = null;
        do {
            String url = API + "?pageSize=1000&spaces=drive&fields=" + enc("nextPageToken,files(id,name,size)")
                    + "&q=" + enc(q) + (page == null ? "" : "&pageToken=" + enc(page));
            JSONObject r = json(send(request(url).GET()));
            JSONArray files = r.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) all.add(files.getJSONObject(i));
            page = r.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null);
        return all;
    }

    /** Downloads a file to {@code target} (through a .part file, so a broken one never appears). */
    void download(String id, Path target) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = Firebase.HTTP.send(request(API + "/" + enc(id) + "?alt=media").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() / 100 != 2) {
                String body = new String(r.body().readAllBytes(), StandardCharsets.UTF_8);
                throw failure(r.statusCode(), body);
            }
            try (InputStream in = r.body()) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** Opens a file to anyone with its link: chat media, readable by the members' devices without Drive. */
    void shareWithLink(String id) throws IOException {
        JSONObject permission = new JSONObject().put("role", "reader").put("type", "anyone");
        send(request(API + "/" + enc(id) + "/permissions").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(permission.toString())));
    }

    void delete(String id) throws IOException {
        HttpResponse<String> r = raw(request(API + "/" + enc(id)).DELETE());
        if (r.statusCode() == 404) return;
        check(r);
    }

    // ================================================================ helpers

    private HttpRequest.Builder request(String url) throws IOException {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token());
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException {
        HttpResponse<String> r = raw(b);
        check(r);
        return r;
    }

    private static HttpResponse<String> raw(HttpRequest.Builder b) throws IOException {
        try {
            return Firebase.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static void check(HttpResponse<String> r) throws IOException {
        if (r.statusCode() / 100 != 2) throw failure(r.statusCode(), r.body());
    }

    private static IOException failure(int status, String body) {
        String reason = "";
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            JSONArray errors = e == null ? null : e.optJSONArray("errors");
            reason = errors == null || errors.isEmpty() ? "" : errors.getJSONObject(0).optString("reason");
        } catch (Exception ignored) {
            // Not JSON.
        }
        if (status == 401) return new AuthException("Google Drive needs reconnecting");
        if (reason.contains("storageQuotaExceeded") || reason.contains("quotaExceeded")) return new QuotaException("Google Drive is full");
        return new IOException("Drive: HTTP " + status + " " + reason);
    }

    private static JSONObject json(HttpResponse<String> r) {
        return new JSONObject(r.body());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
