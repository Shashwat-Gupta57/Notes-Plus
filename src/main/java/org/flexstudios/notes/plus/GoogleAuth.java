package org.flexstudios.notes.plus;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Google sign-in for a desktop app: the browser opens Google's own page, and the answer
 * comes back to a one-off server on 127.0.0.1 (OAuth 2.0 for installed apps, with PKCE).
 * The same flow grants Drive's {@code drive.file} for attachments, as the phone asks for.
 *
 * Needs an OAuth client of type "Desktop app" in the Firebase project's Google Cloud
 * project, saved as {@code oauth-client.json} (Google's download, unchanged) in the app's data
 * folder or bundled in its resources.
 */
final class GoogleAuth {

    private GoogleAuth() {}

    static final String SCOPE_SIGN_IN = "openid email profile";
    static final String SCOPE_DRIVE = "https://www.googleapis.com/auth/drive.file";

    record Client(String id, String secret) {}

    record Tokens(String idToken, String accessToken, String refreshToken, String email, String name, String picture) {}

    private static volatile CompletableFuture<Map<String, String>> waiting;
    /** The sign-in page being waited on, to open again or copy; null when nothing waits. */
    private static volatile String pendingUrl;

    static String pendingUrl() {
        return pendingUrl;
    }

    /** The desktop OAuth client, or null if none has been set up. */
    static Client client() {
        try {
            JSONObject o = null;
            Path local = Storage.root().resolve("oauth-client.json");
            if (Files.exists(local)) o = new JSONObject(Files.readString(local));
            else {
                try (InputStream in = GoogleAuth.class.getResourceAsStream("oauth-client.json")) {
                    if (in != null) o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (o == null) return null;
            JSONObject c = o.optJSONObject("installed") != null ? o.getJSONObject("installed") : o;
            String id = c.optString("client_id", null), secret = c.optString("client_secret", null);
            return id == null ? null : new Client(id, secret);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Runs the browser sign-in for {@code scopes}. Blocking, up to five minutes; {@link #cancel}
     * gives up early. {@code hint}: the account to suggest, or null.
     */
    static Tokens authorize(String scopes, String hint) throws IOException {
        Client client = client();
        if (client == null) throw new IOException("Google sign-in isn't set up on this computer yet (no oauth-client.json).");
        String verifier = random(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String state = random(16);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
        waiting = answer;
        server.createContext("/", exchange -> {
            Map<String, String> q = params(exchange.getRequestURI().getRawQuery());
            boolean ok = q.containsKey("code");
            byte[] page = page(ok).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            if (q.containsKey("code") || q.containsKey("error")) answer.complete(q);
        });
        server.start();
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            StringBuilder url = new StringBuilder("https://accounts.google.com/o/oauth2/v2/auth?response_type=code")
                    .append("&client_id=").append(enc(client.id()))
                    .append("&redirect_uri=").append(enc(redirect))
                    .append("&scope=").append(enc(scopes))
                    .append("&code_challenge=").append(challenge).append("&code_challenge_method=S256")
                    .append("&state=").append(state)
                    .append("&access_type=offline&include_granted_scopes=true&prompt=consent%20select_account");
            if (hint != null) url.append("&login_hint=").append(enc(hint));
            pendingUrl = url.toString();
            NotesApp.openInBrowser(pendingUrl);
            Map<String, String> q;
            try {
                q = answer.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new IOException("cancelled");
            } catch (Exception e) {
                throw new IOException("cancelled");
            }
            if (q.containsKey("error")) throw new IOException(q.get("error").equals("access_denied") ? "cancelled" : q.get("error"));
            if (!state.equals(q.get("state"))) throw new IOException("Google's answer didn't match the request");
            JSONObject t = token("grant_type=authorization_code&code=" + enc(q.get("code"))
                    + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + verifier, client);
            JSONObject claims = claims(t.optString("id_token", null));
            return new Tokens(t.optString("id_token", null), t.optString("access_token", null),
                    t.optString("refresh_token", null), claims.optString("email", null),
                    claims.optString("name", null), claims.optString("picture", null));
        } finally {
            waiting = null;
            pendingUrl = null;
            server.stop(0);
        }
    }

    /** Stops waiting for the browser. */
    static void cancel() {
        CompletableFuture<Map<String, String>> w = waiting;
        if (w != null) w.completeExceptionally(new IOException("cancelled"));
    }

    // ======================================================== access tokens

    private static final Map<String, String[]> ACCESS = new HashMap<>();

    /** A current access token for a stored refresh token (cached until shortly before it expires). */
    static synchronized String accessToken(String refreshToken) throws IOException {
        String[] cached = ACCESS.get(refreshToken);
        if (cached != null && Long.parseLong(cached[1]) > System.currentTimeMillis() + 60_000) return cached[0];
        Client client = client();
        if (client == null) throw new IOException("Google sign-in isn't set up on this computer");
        JSONObject t = token("grant_type=refresh_token&refresh_token=" + enc(refreshToken), client);
        String access = t.getString("access_token");
        ACCESS.put(refreshToken, new String[]{access,
                String.valueOf(System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000)});
        return access;
    }

    /** Withdraws the app's access (signing out of Drive for good). Quiet on failure. */
    static void revoke(String token) {
        try {
            Firebase.HTTP.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/revoke?token=" + enc(token)))
                    .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Best effort: the token expires on its own.
        }
    }

    private static JSONObject token(String form, Client client) throws IOException {
        String body = form + "&client_id=" + enc(client.id()) + (client.secret() == null ? "" : "&client_secret=" + enc(client.secret()));
        try {
            HttpResponse<String> r = Firebase.HTTP.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            JSONObject o = new JSONObject(r.body());
            if (r.statusCode() / 100 != 2) {
                String error = o.optString("error", "HTTP " + r.statusCode());
                throw new Firebase.Error(r.statusCode(), error.equals("invalid_grant") ? "GOOGLE_RECONNECT" : error);
            }
            return o;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    // =============================================================== helpers

    /** The payload of a JWT, unverified (it came straight from Google over TLS). */
    static JSONObject claims(String jwt) {
        if (jwt == null) return new JSONObject();
        try {
            String[] parts = jwt.split("\\.");
            return new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String page(boolean ok) {
        return "<!doctype html><html><head><meta charset=utf-8><title>Notes+</title>"
                + "<style>body{font-family:Roboto,Segoe UI,sans-serif;background:#F2F2F7;display:flex;align-items:center;"
                + "justify-content:center;height:100vh;margin:0}div{background:#fff;border-radius:28px;padding:40px 48px;"
                + "text-align:center;box-shadow:0 6px 24px rgba(0,0,0,.12)}h1{font-size:24px;margin:0 0 8px}"
                + "p{color:#8E8E93;margin:0}</style></head><body><div><h1>"
                + (ok ? "You're signed in" : "Sign-in didn't finish")
                + "</h1><p>You can close this tab and go back to Notes+.</p></div></body></html>";
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
