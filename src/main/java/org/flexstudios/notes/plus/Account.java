package org.flexstudios.notes.plus;

import javafx.application.Platform;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The notes account, the same one as on the phone: the only code that knows who is signed in.
 *
 * Sign-in is Google (in the browser) or email + password; an email account can store nothing
 * until its address is verified (the database rules enforce the same). Every account picks a
 * unique {@code @username}, a public handle that is never used to sign in.
 *
 * Photos and recordings sync through the user's own Google Drive ({@code drive.file}). A
 * Google account grants it while signing in; an email account connects one separately.
 *
 * Every call that talks to the network blocks: run it on {@link #BG}.
 */
final class Account {

    private Account() {}

    static final String USERNAME_PATTERN = "^[a-z0-9_.]{3,20}$";

    /** One thread for account work, so the screen never waits on the network. */
    static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notes-account");
        t.setDaemon(true);
        return t;
    });

    private static final String UID = "uid", REFRESH = "refresh", EMAIL = "email", PROVIDER = "provider",
            VERIFIED = "verified", NAME = "name", PHOTO = "photo", DRIVE_TOKEN = "driveToken", DRIVE_EMAIL = "driveEmail";

    private static String idToken;
    private static long idTokenExpires;

    private static final List<Runnable> OBSERVERS = new CopyOnWriteArrayList<>();

    /** Runs on the FX thread whenever someone signs in or out. */
    static void observe(Runnable r) {
        OBSERVERS.add(r);
    }

    private static void changed() {
        Platform.runLater(() -> OBSERVERS.forEach(Runnable::run));
    }

    static final class Profile {
        String username, displayName, email, photoUrl;
        /** The photo others see, as {@link ChatMedia#loadAvatar} takes it: custom, else Google's. */
        String photoJson;
    }

    // ================================================================== state

    /** Signed in at all (perhaps still waiting for the email link). */
    static boolean signedIn() {
        return Prefs.secret(UID) != null;
    }

    static boolean isGoogle() {
        return "google.com".equals(Prefs.secret(PROVIDER));
    }

    /** The uid notes sync to: a Google account, or an email account that has been verified. */
    static String uid() {
        String uid = Prefs.secret(UID);
        return uid != null && (isGoogle() || "true".equals(Prefs.secret(VERIFIED))) ? uid : null;
    }

    static String email() {
        return Prefs.secret(EMAIL);
    }

    /** A current ID token, refreshed when it is about to expire. Blocking. */
    static synchronized String token() throws IOException {
        String refresh = Prefs.secret(REFRESH);
        if (refresh == null) throw new IOException("Not signed in");
        if (idToken != null && System.currentTimeMillis() < idTokenExpires - 120_000) return idToken;
        JSONObject r = Firebase.refresh(refresh);
        remember(r.getString("id_token"), r.optString("refresh_token", refresh), r.optLong("expires_in", 3600));
        return idToken;
    }

    private static synchronized void remember(String token, String refresh, long expiresIn) {
        idToken = token;
        idTokenExpires = System.currentTimeMillis() + expiresIn * 1000;
        Prefs.putSecret(REFRESH, refresh);
        JSONObject claims = GoogleAuth.claims(token);
        if (claims.has("email_verified")) Prefs.putSecret(VERIFIED, String.valueOf(claims.optBoolean("email_verified")));
    }

    private static void startSession(JSONObject r, String provider) {
        Prefs.putSecret(UID, r.getString("localId"));
        Prefs.putSecret(PROVIDER, provider);
        Prefs.putSecret(EMAIL, r.optString("email", null));
        if (r.has("displayName")) Prefs.putSecret(NAME, r.optString("displayName"));
        if (r.has("photoUrl")) Prefs.putSecret(PHOTO, r.optString("photoUrl"));
        remember(r.getString("idToken"), r.getString("refreshToken"), r.optLong("expiresIn", 3600));
        if (r.has("emailVerified")) Prefs.putSecret(VERIFIED, String.valueOf(r.optBoolean("emailVerified")));
    }

    // ================================================================= Google

    /** Opens Google's sign-in in the browser; Drive for attachments is granted in the same step. Blocking. */
    static void signInWithGoogle() throws IOException {
        GoogleAuth.Tokens t = GoogleAuth.authorize(GoogleAuth.SCOPE_SIGN_IN + " " + GoogleAuth.SCOPE_DRIVE, null);
        JSONObject r = idp(t);
        if (r.optBoolean("needConfirmation")) {
            throw new IOException("An account with this email already exists. Sign in with your email and password.");
        }
        startSession(r, "google.com");
        if (t.refreshToken() != null && t.email() != null) {
            Prefs.putSecret(DRIVE_TOKEN, t.refreshToken());
            Prefs.putSecret(DRIVE_EMAIL, t.email());
        }
        finishSignIn();
    }

    private static JSONObject idp(GoogleAuth.Tokens t) throws IOException {
        String post = t.idToken() != null ? "id_token=" + t.idToken() : "access_token=" + t.accessToken();
        JSONObject body = new JSONObject().put("postBody", post + "&providerId=google.com")
                .put("requestUri", "http://localhost").put("returnSecureToken", true).put("returnIdpCredential", true);
        try {
            return Firebase.auth("signInWithIdp", body);
        } catch (Firebase.Error e) {
            if (t.accessToken() == null || t.idToken() == null) throw e;
            // The ID token's audience is the desktop client; the access token proves the same person.
            body.put("postBody", "access_token=" + t.accessToken() + "&providerId=google.com");
            return Firebase.auth("signInWithIdp", body);
        }
    }

    // ================================================================== email

    /** Creates the account and sends the verification link; it syncs once that is clicked. */
    static void signUp(String email, String password) throws IOException {
        JSONObject r = Firebase.auth("signUp", new JSONObject().put("email", email.trim())
                .put("password", password).put("returnSecureToken", true));
        startSession(r, "password");
        Prefs.putSecret(VERIFIED, "false");
        resendVerification();
        changed();
    }

    static void signIn(String email, String password) throws IOException {
        JSONObject r = Firebase.auth("signInWithPassword", new JSONObject().put("email", email.trim())
                .put("password", password).put("returnSecureToken", true));
        startSession(r, "password");
        lookup();
        finishSignIn();
    }

    static void resetPassword(String email) throws IOException {
        Firebase.auth("sendOobCode", new JSONObject().put("requestType", "PASSWORD_RESET").put("email", email.trim()));
    }

    static void resendVerification() throws IOException {
        Firebase.auth("sendOobCode", new JSONObject().put("requestType", "VERIFY_EMAIL").put("idToken", token()));
    }

    /**
     * Re-reads the account after the link was clicked, and refreshes the token: the database
     * rules read {@code email_verified} from the token, not from the account record.
     */
    static boolean checkVerified() throws IOException {
        synchronized (Account.class) {
            idToken = null;
        }
        token();
        lookup();
        if (uid() == null) return false;
        finishSignIn();
        return true;
    }

    private static void lookup() throws IOException {
        JSONObject r = Firebase.auth("lookup", new JSONObject().put("idToken", token()));
        JSONObject user = r.optJSONArray("users") == null ? null : r.getJSONArray("users").optJSONObject(0);
        if (user == null) return;
        Prefs.putSecret(VERIFIED, String.valueOf(user.optBoolean("emailVerified")));
        if (user.has("displayName")) Prefs.putSecret(NAME, user.optString("displayName"));
        if (user.has("photoUrl")) Prefs.putSecret(PHOTO, user.optString("photoUrl"));
    }

    /** Common tail of every sign-in: make sure the profile exists, then announce it. */
    private static void finishSignIn() throws IOException {
        String uid = uid();
        if (uid == null) {
            changed(); // An unverified email account: the screen asks for the link.
            return;
        }
        String base = "users/" + uid + "/profile";
        JSONObject fields = new JSONObject();
        String name = Prefs.secret(NAME), photo = Prefs.secret(PHOTO);
        if (name != null) fields.put("displayName", clip(name, 100));
        if (photo != null) fields.put("photoUrl", clip(photo, 2000));
        if (Firebase.get(base + "/createdAt", null) == null) fields.put("createdAt", Firebase.now());
        String driveEmail = Prefs.secret(DRIVE_EMAIL);
        if (driveEmail != null) fields.put("driveEmail", clip(driveEmail, 320));
        if (!fields.isEmpty()) Firebase.patch(base, fields);
        // The photo people see in chats: the Google one, until a custom one is chosen.
        if (photo != null && photo.startsWith("https://") && Firebase.get("profiles/" + uid + "/photo", null) == null) {
            Firebase.put("profiles/" + uid + "/photo", new JSONObject().put("url", clip(photo, 2000)));
        }
        changed();
    }

    // =============================================================== profile

    static Profile loadProfile() throws IOException {
        Profile p = new Profile();
        p.email = email();
        p.displayName = Prefs.secret(NAME);
        p.photoUrl = Prefs.secret(PHOTO);
        String uid = uid();
        if (uid == null) return p;
        JSONObject s = Firebase.getObject("users/" + uid + "/profile");
        if (s != null) {
            p.username = s.optString("username", null);
            if (s.has("displayName")) p.displayName = s.optString("displayName");
        }
        JSONObject photo = Firebase.getObject("profiles/" + uid + "/photo");
        if (photo != null && photo.has("url")) p.photoUrl = photo.optString("url");
        p.photoJson = photo != null ? photo.toString()
                : p.photoUrl != null && p.photoUrl.startsWith("https://") ? new JSONObject().put("url", p.photoUrl).toString() : null;
        return p;
    }

    /** Lower-cases and checks the shape; null when it can never be a username. */
    static String normaliseUsername(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("@")) name = name.substring(1);
        return name.matches(USERNAME_PATTERN) ? name : null;
    }

    static boolean isUsernameFree(String name) throws IOException {
        Object owner = Firebase.get("usernames/" + name, null);
        return owner == null || owner.equals(uid());
    }

    /**
     * Claims {@code name} and releases {@code previous} in one atomic update; the rules refuse
     * it if someone else got there first.
     */
    static void claimUsername(String name, String previous) throws IOException {
        String uid = uid();
        if (uid == null) throw new IOException("Not signed in");
        JSONObject update = new JSONObject().put("usernames/" + name, uid).put("users/" + uid + "/profile/username", name);
        if (previous != null && !previous.equals(name)) update.put("usernames/" + previous, JSONObject.NULL);
        try {
            Firebase.patch("", update);
        } catch (Firebase.Error e) {
            if (e.refused()) throw new IOException("@" + name + " is taken. Try another.");
            throw e;
        }
    }

    // ================================================================= Drive

    /** Asks Google for {@code drive.file} in the browser. Blocking. */
    static void connectDrive() throws IOException {
        GoogleAuth.Tokens t = GoogleAuth.authorize("email " + GoogleAuth.SCOPE_DRIVE, isGoogle() ? email() : null);
        if (t.refreshToken() == null || t.email() == null) throw new IOException("Google didn't grant access to Drive");
        Prefs.putSecret(DRIVE_TOKEN, t.refreshToken());
        Prefs.putSecret(DRIVE_EMAIL, t.email());
        Prefs.put(NoteSync.PREF_DRIVE_ERROR, null);
        String uid = uid();
        if (uid != null) Firebase.put("users/" + uid + "/profile/driveEmail", clip(t.email(), 320));
        NoteSync.schedule();
    }

    static String driveEmail() {
        return Prefs.secret(DRIVE_EMAIL);
    }

    static void disconnectDrive() {
        Prefs.putSecret(DRIVE_TOKEN, null);
        Prefs.putSecret(DRIVE_EMAIL, null);
        Prefs.put(NoteSync.PREF_DRIVE_ERROR, null);
    }

    /** The user's own Drive, or null if not connected. */
    static Drive drive() {
        String token = Prefs.secret(DRIVE_TOKEN);
        return token == null ? null : new Drive(token);
    }

    // ========================================================= sign out / delete

    /**
     * Signs out. {@code keepNotes}: the account's notes stay on this computer as its own;
     * otherwise they and their files are removed from it. The account itself is untouched.
     */
    static void signOut(boolean keepNotes) {
        String uid = Prefs.secret(UID);
        NoteSync.stopLive();
        if (uid != null) NoteSync.leave(uid, keepNotes);
        clearSession();
        changed();
    }

    private static void clearSession() {
        for (String k : new String[]{UID, REFRESH, EMAIL, PROVIDER, VERIFIED, NAME, PHOTO}) Prefs.putSecret(k, null);
        disconnectDrive();
        synchronized (Account.class) {
            idToken = null;
        }
    }

    /**
     * Proves it is really the owner (Google in the browser, or {@code password}), then removes
     * everything the account stored: its Drive attachments, its notes and username in the
     * database, and finally the account. Notes stay on this computer. Blocking.
     */
    static void deleteAccount(String password) throws IOException {
        String uid = Prefs.secret(UID);
        if (uid == null) throw new IOException("Not signed in");
        if (isGoogle()) {
            GoogleAuth.Tokens t = GoogleAuth.authorize(GoogleAuth.SCOPE_SIGN_IN, email());
            JSONObject r = idp(t);
            if (!uid.equals(r.optString("localId"))) throw new IOException("That's a different Google account");
            remember(r.getString("idToken"), r.getString("refreshToken"), r.optLong("expiresIn", 3600));
        } else {
            JSONObject r = Firebase.auth("signInWithPassword", new JSONObject().put("email", email())
                    .put("password", password).put("returnSecureToken", true));
            remember(r.getString("idToken"), r.getString("refreshToken"), r.optLong("expiresIn", 3600));
        }
        NoteSync.stopLive();
        NoteSync.eraseAttachments();
        Object username = Firebase.get("users/" + uid + "/profile/username", null);
        // Notes I sent stop being readable by anyone.
        for (NoteEntity n : NoteStore.get().sentNotes(uid)) {
            try {
                SentNotes.takeDown(uid, n);
            } catch (IOException e) {
                System.getLogger("Account").log(System.Logger.Level.WARNING, "A sent note wasn't taken down", e);
            }
        }
        JSONObject wipe = new JSONObject();
        for (String node : new String[]{"users", "chatHome", "chatIndex", "chatVaults", "chatPlace", "chatSessions",
                "chatNick", "profiles", "vaultPhotos", "groupIndex", "inbox"}) {
            wipe.put(node + "/" + uid, JSONObject.NULL);
        }
        if (username instanceof String u) wipe.put("usernames/" + u, JSONObject.NULL);
        Firebase.patch("", wipe);
        Firebase.auth("delete", new JSONObject().put("idToken", token()));
        NoteSync.leave(uid, true);
        clearSession();
        changed();
    }

    // =============================================================== helpers

    /** Firebase's messages are written for developers; these are for people. "" = cancelled. */
    static String friendly(Throwable e) {
        if (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null) e = e.getCause();
        if (e == null) return "Something went wrong";
        if (e instanceof ConnectException || e instanceof HttpTimeoutException || e instanceof UnresolvedAddressException
                || e.getCause() instanceof UnresolvedAddressException || e instanceof java.net.UnknownHostException) {
            return "No connection. Try again when you're online.";
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.equals("cancelled")) return "";
        if (e instanceof Firebase.Error fe) {
            String c = fe.code;
            if (c.startsWith("EMAIL_EXISTS")) return "An account with this email already exists. Sign in instead.";
            if (c.startsWith("EMAIL_NOT_FOUND")) return "No account with that email";
            if (c.startsWith("INVALID_PASSWORD") || c.startsWith("INVALID_LOGIN_CREDENTIALS")) return "Wrong email or password";
            if (c.startsWith("WEAK_PASSWORD")) return "Choose a password of at least 6 characters";
            if (c.startsWith("INVALID_EMAIL")) return "Enter a valid email";
            if (c.startsWith("TOO_MANY_ATTEMPTS")) return "Too many tries. Wait a little and try again.";
            if (c.startsWith("USER_DISABLED")) return "This account has been disabled";
            if (c.startsWith("TOKEN_EXPIRED") || c.startsWith("USER_NOT_FOUND") || c.startsWith("INVALID_REFRESH_TOKEN")) {
                return "You've been signed out. Sign in again.";
            }
            if (c.startsWith("INVALID_IDP_RESPONSE")) return "Google sign-in isn't allowed for this app yet (see the setup note).";
            if (fe.refused()) return "The server refused that. Are the latest database rules published?";
            return c;
        }
        return m.isEmpty() ? "Something went wrong" : m;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
