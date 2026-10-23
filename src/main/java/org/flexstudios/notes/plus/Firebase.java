package org.flexstudios.notes.plus;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * The phone's Firebase project (notes-plus-88985), spoken to over its REST APIs: Auth
 * (Identity Toolkit and Secure Token) and the Realtime Database, including the database's
 * streaming (server-sent events) for live updates. Java has no client SDK for end users;
 * these are the same calls the SDKs make, under the same security rules.
 *
 * The API key and database address are public by design, as in the phone's
 * google-services.json: the rules do the protecting.
 */
final class Firebase {

    private Firebase() {}

    static final String API_KEY = "AIzaSyAPJ5EG0CzDI--yJY9yAVDArzUn3v1tEss";

    /** {@code -Dnotes.emulator=127.0.0.1}: the local Firebase emulators instead (tests). */
    private static final String EMULATOR = System.getProperty("notes.emulator");
    private static final String AUTH = EMULATOR == null ? "https://identitytoolkit.googleapis.com"
            : "http://" + EMULATOR + ":9099/identitytoolkit.googleapis.com";
    private static final String SECURE_TOKEN = EMULATOR == null ? "https://securetoken.googleapis.com"
            : "http://" + EMULATOR + ":9099/securetoken.googleapis.com";
    static final String DATABASE = EMULATOR == null
            ? "https://notes-plus-88985-default-rtdb.asia-southeast1.firebasedatabase.app"
            : "http://" + EMULATOR + ":9000";
    private static final String NAMESPACE = EMULATOR == null ? "" : "ns=notes-plus-88985-default-rtdb&";

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** A refusal from Firebase: {@code code} is Auth's code (EMAIL_EXISTS…) or the database's message. */
    static final class Error extends IOException {
        final int status;
        final String code;

        Error(int status, String code) {
            super(code + " (" + status + ")");
            this.status = status;
            this.code = code;
        }

        boolean refused() {
            return status == 401 || status == 403 || "Permission denied".equalsIgnoreCase(code);
        }
    }

    // =================================================================== Auth

    /** {@code accounts:<method>} of the Identity Toolkit. */
    static JSONObject auth(String method, JSONObject body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        AUTH + "/v1/accounts:" + method + "?key=" + API_KEY))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return json(send(request));
    }

    /** A fresh ID token for a refresh token: {id_token, refresh_token, expires_in, user_id}. */
    static JSONObject refresh(String refreshToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SECURE_TOKEN + "/v1/token?key=" + API_KEY))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&refresh_token="
                        + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)))
                .build();
        return json(send(request));
    }

    // =============================================================== Database

    /** Server time, for {@code updatedAt} and friends. */
    static JSONObject now() {
        return new JSONObject().put(".sv", "timestamp");
    }

    private static URI uri(String path, String query) throws IOException {
        String token = Account.token();
        String q = NAMESPACE + "auth=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + (query == null ? "" : "&" + query);
        return URI.create(DATABASE + "/" + path + ".json?" + q);
    }

    /** {@code orderBy="updatedAt"&startAt=…}, encoded. */
    static String query(String orderBy, String bound, long value) {
        return "orderBy=" + URLEncoder.encode("\"" + orderBy + "\"", StandardCharsets.UTF_8) + "&" + bound + "=" + value;
    }

    /** The value at {@code path} (JSONObject, String, Number, Boolean) or null. */
    static Object get(String path, String query) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(path, query)).timeout(TIMEOUT).GET().build();
        return parse(send(request));
    }

    static JSONObject getObject(String path) throws IOException {
        Object v = get(path, null);
        return v instanceof JSONObject o ? o : null;
    }

    /** A value together with its ETag, for compare-and-set. */
    record Tagged(Object value, String etag) {}

    static Tagged getTagged(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(path, null)).timeout(TIMEOUT)
                .header("X-Firebase-ETag", "true").GET().build();
        HttpResponse<String> r = sendRaw(request);
        check(r);
        return new Tagged(parse(r.body()), r.headers().firstValue("ETag").orElse(null));
    }

    /**
     * Writes {@code value} only if the location still has {@code etag}: the REST form of a
     * transaction. Null when written; otherwise what is there now, with its tag.
     */
    static Tagged putIf(String path, Object value, String etag) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(path, null)).timeout(TIMEOUT)
                .header("if-match", etag).header("X-Firebase-ETag", "true")
                .PUT(HttpRequest.BodyPublishers.ofString(body(value)))
                .build();
        HttpResponse<String> r = sendRaw(request);
        if (r.statusCode() == 412) return new Tagged(parse(r.body()), r.headers().firstValue("ETag").orElse(null));
        check(r);
        return null;
    }

    /** A value as JSON text: a plain string must go quoted, or the database refuses it. */
    private static String body(Object value) {
        if (value instanceof String text) return JSONObject.quote(text);
        return String.valueOf(JSONObject.wrap(value));
    }

    static void put(String path, Object value) throws IOException {
        send(HttpRequest.newBuilder(uri(path, null)).timeout(TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(body(value))).build());
    }

    /** A multi-location update: {@code {"a/b": 1, "c": null}}, all or nothing. */
    static void patch(String path, JSONObject update) throws IOException {
        send(HttpRequest.newBuilder(uri(path, null)).timeout(TIMEOUT)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(update.toString())).build());
    }

    // ============================================================== streaming

    /** A live listener on a location; {@link #close} stops it. */
    static final class Listener {
        private volatile boolean closed;
        private volatile InputStream stream;
        private final Thread thread;

        private Listener(String path, String query, BiConsumer<String, JSONObject> onEvent) {
            thread = new Thread(() -> loop(path, query, onEvent), "notes-live");
            thread.setDaemon(true);
            thread.start();
        }

        /** Reconnects after drops and token expiry until closed. */
        private void loop(String path, String query, BiConsumer<String, JSONObject> onEvent) {
            int failures = 0;
            while (!closed) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(uri(path, query))
                            .header("Accept", "text/event-stream").GET().build();
                    HttpResponse<InputStream> r = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (r.statusCode() != 200) {
                        r.body().close();
                        throw new Error(r.statusCode(), "stream");
                    }
                    stream = r.body();
                    failures = 0;
                    BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                    String event = null, line;
                    while (!closed && (line = in.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:") && event != null) {
                            String data = line.substring(5).trim();
                            if (event.equals("auth_revoked") || event.equals("cancel")) break;
                            if ((event.equals("put") || event.equals("patch")) && data.startsWith("{")) {
                                onEvent.accept(event, new JSONObject(data));
                            }
                            event = null;
                        }
                    }
                } catch (Exception e) {
                    if (closed) return;
                    failures++;
                }
                if (closed) return;
                try {
                    Thread.sleep(Math.min(60_000, 2_000L * Math.max(1, failures)));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        void close() {
            closed = true;
            try {
                if (stream != null) stream.close();
            } catch (IOException ignored) {
                // Closing either way.
            }
            thread.interrupt();
        }
    }

    /** Streams changes at {@code path}: (event, {path, data}). */
    static Listener listen(String path, String query, BiConsumer<String, JSONObject> onEvent) {
        return new Listener(path, query, onEvent);
    }

    // ================================================================ helpers

    private static HttpResponse<String> sendRaw(HttpRequest request) throws IOException {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String send(HttpRequest request) throws IOException {
        HttpResponse<String> r = sendRaw(request);
        check(r);
        return r.body();
    }

    private static void check(HttpResponse<String> r) throws Error {
        if (r.statusCode() / 100 == 2) return;
        String code = "HTTP " + r.statusCode();
        try {
            JSONObject o = new JSONObject(r.body());
            Object e = o.opt("error");
            if (e instanceof JSONObject eo) code = eo.optString("message", code);
            else if (e instanceof String s) code = s;
            else if (o.has("error_description")) code = o.optString("error_description");
        } catch (Exception ignored) {
            // Not JSON: keep the status.
        }
        throw new Error(r.statusCode(), code);
    }

    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("Unexpected answer", e);
        }
    }

    private static Object parse(String body) {
        if (body == null || body.isBlank() || body.equals("null")) return null;
        Object v = new JSONTokener(body).nextValue();
        return v == JSONObject.NULL ? null : v;
    }
}
