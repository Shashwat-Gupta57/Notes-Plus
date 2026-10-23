package org.flexstudios.notes.plus;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.api.services.drive.DriveScopes;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The notes app's account: the only code that talks to Firebase Auth.
 *
 * Sign-in is Google (Credential Manager) or email + password; an email account can store
 * nothing until its address is verified (the database rules enforce the same). Every account
 * picks a unique {@code @username}, a public handle that is never used to sign in.
 *
 * Photos and recordings sync through the user's own Google Drive, connected separately with
 * the {@code drive.file} scope through the Authorization API, so it never touches the
 * {@code GoogleSignIn} state the vault's Drive backup relies on.
 *
 * Nothing here knows the vault exists, and nothing in the vault imports this.
 */
final class Account {

    private static final String TAG = "Account";
    private static final String PREF_DRIVE_EMAIL = "notes_drive_email";
    private static final long TIMEOUT_S = 30;
    static final String USERNAME_PATTERN = "^[a-z0-9_.]{3,20}$";

    private static boolean initialised;

    private Account() {}

    /** Result of an auth step: {@code error} null on success, "" when the user cancelled. */
    interface Callback {
        void done(@Nullable String error);
    }

    static final class Profile {
        String username, displayName, email, photoUrl;
    }

    /** Hooks sync to the app's lifecycle: leaving the app pushes what was finished. Once. */
    static synchronized void init(Context context) {
        if (initialised) return;
        initialised = true;
        Context app = context.getApplicationContext();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                NoteSync.schedule(app);
            }
        });
        if (uid() != null) {
            NoteSync.schedulePeriodic(app);
            NoteSync.schedule(app);
        }
    }

    // ================================================================== state

    @Nullable
    static FirebaseUser user() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    /** The uid notes sync to: a Google account, or an email account that has been verified. */
    @Nullable
    static String uid() {
        FirebaseUser user = user();
        return user != null && (isGoogle(user) || user.isEmailVerified()) ? user.getUid() : null;
    }

    static boolean isGoogle(FirebaseUser user) {
        for (UserInfo info : user.getProviderData()) {
            if (GoogleAuthProvider.PROVIDER_ID.equals(info.getProviderId())) return true;
        }
        return false;
    }

    static DatabaseReference root() {
        return FirebaseDatabase.getInstance().getReference();
    }

    static DatabaseReference notesOf(String uid) {
        return root().child("users").child(uid).child("notes");
    }

    // ================================================================= Google

    /** Shows the Google account sheet and signs in with the chosen account. */
    static void signInWithGoogle(Activity activity, Callback callback) {
        googleCredential(activity, credential -> finishSignIn(
                FirebaseAuth.getInstance().signInWithCredential(credential), callback), callback);
    }

    private static void googleCredential(Activity activity, Consumer<AuthCredential> then, Callback failed) {
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(
                activity.getString(R.string.default_web_client_id)).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(activity).getCredentialAsync(activity, request, new CancellationSignal(),
                ContextCompat.getMainExecutor(activity),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        if (credential instanceof CustomCredential && GoogleIdTokenCredential
                                .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                            String token = GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
                            then.accept(GoogleAuthProvider.getCredential(token, null));
                        } else {
                            failed.done("Google sign-in returned something unexpected");
                        }
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        Log.w(TAG, "Google sign-in: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        String message = e.getMessage() == null ? "" : e.getMessage();
                        // Play services reports an app it can't verify (signing SHA-1 not
                        // registered in Firebase) as a "cancellation" with code 16, after the
                        // user has already agreed. Only a real back-out stays silent.
                        if (e instanceof GetCredentialCancellationException && (message.contains("[16]")
                                || message.toLowerCase(Locale.ROOT).contains("reauth"))) {
                            failed.done("Google sign-in isn't set up for this build of the app yet "
                                    + "(its signing fingerprint is missing in Firebase).");
                        } else if (e instanceof GetCredentialCancellationException) failed.done("");
                        else if (e instanceof NoCredentialException) failed.done("No Google account on this phone");
                        else {
                            Log.w(TAG, "Google sign-in failed", e);
                            failed.done("Google sign-in failed. " + (e.getMessage() == null ? "" : e.getMessage()));
                        }
                    }
                });
    }

    // ================================================================== email

    /** Creates the account and sends the verification link; it syncs once that is clicked. */
    static void signUp(String email, String password, Callback callback) {
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        callback.done(friendly(task.getException()));
                        return;
                    }
                    FirebaseUser user = user();
                    if (user != null) user.sendEmailVerification();
                    callback.done(null);
                });
    }

    static void signIn(String email, String password, Callback callback) {
        finishSignIn(FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password), callback);
    }

    static void resetPassword(String email, Callback callback) {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim())
                .addOnCompleteListener(task -> callback.done(task.isSuccessful() ? null : friendly(task.getException())));
    }

    static void resendVerification(Callback callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.done("Not signed in");
            return;
        }
        user.sendEmailVerification()
                .addOnCompleteListener(task -> callback.done(task.isSuccessful() ? null : friendly(task.getException())));
    }

    /**
     * Re-reads the user after they clicked the link, and refreshes the token: the database
     * rules read {@code email_verified} from the token, not from the user record.
     */
    static void checkVerified(Callback callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.done("Not signed in");
            return;
        }
        user.reload().continueWithTask(t -> user.getIdToken(true)).addOnCompleteListener(task -> {
            FirebaseUser fresh = user();
            if (!task.isSuccessful()) callback.done(friendly(task.getException()));
            else if (fresh == null || !fresh.isEmailVerified()) callback.done("Not verified yet. Tap the link in the email first.");
            else finishSignIn(Tasks.forResult(null), callback);
        });
    }

    /** Common tail of every sign-in: make sure the profile exists, then start syncing. */
    private static void finishSignIn(Task<?> signIn, Callback callback) {
        signIn.addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                callback.done(friendly(task.getException()));
                return;
            }
            FirebaseUser user = user();
            if (user == null || uid() == null) {
                callback.done(null); // An unverified email account: the screen asks for the link.
                return;
            }
            DatabaseReference profile = root().child("users").child(user.getUid()).child("profile");
            Map<String, Object> fields = new HashMap<>();
            if (user.getDisplayName() != null) fields.put("displayName", clip(user.getDisplayName(), 100));
            if (user.getPhotoUrl() != null) fields.put("photoUrl", clip(user.getPhotoUrl().toString(), 2000));
            DatabaseReference publicPhoto = root().child("profiles").child(user.getUid()).child("photo");
            profile.child("createdAt").get().continueWithTask(t -> {
                if (t.isSuccessful() && !t.getResult().exists()) fields.put("createdAt", ServerValue.TIMESTAMP);
                return fields.isEmpty() ? Tasks.forResult(null) : profile.updateChildren(fields);
            }).continueWithTask(t -> publicPhoto.get()).continueWithTask(t -> {
                // The photo people see in chats: the Google one, until a custom one is chosen.
                if (t.isSuccessful() && !t.getResult().exists() && user.getPhotoUrl() != null) {
                    Map<String, Object> google = new HashMap<>();
                    google.put("url", clip(user.getPhotoUrl().toString(), 2000));
                    return publicPhoto.setValue(google);
                }
                return Tasks.forResult(null);
            }).addOnCompleteListener(t -> {
                if (!t.isSuccessful()) Log.w(TAG, "Could not write the profile", t.getException());
                callback.done(null);
            });
        });
    }

    // =============================================================== profile

    static void loadProfile(Consumer<Profile> then) {
        FirebaseUser user = user();
        if (user == null) {
            then.accept(null);
            return;
        }
        Profile profile = new Profile();
        profile.email = user.getEmail();
        profile.displayName = user.getDisplayName();
        profile.photoUrl = user.getPhotoUrl() == null ? null : user.getPhotoUrl().toString();
        if (uid() == null) {
            then.accept(profile);
            return;
        }
        root().child("users").child(user.getUid()).child("profile").get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                DataSnapshot s = task.getResult();
                profile.username = s.child("username").getValue(String.class);
                String name = s.child("displayName").getValue(String.class);
                if (name != null) profile.displayName = name;
            }
            then.accept(profile);
        });
    }

    /** Lower-cases and checks the shape; null when it can never be a username. */
    @Nullable
    static String normaliseUsername(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("@")) name = name.substring(1);
        return name.matches(USERNAME_PATTERN) ? name : null;
    }

    /** True if nobody (or this user) holds {@code name}. */
    static void isUsernameFree(String name, Consumer<Boolean> then) {
        String uid = uid();
        root().child("usernames").child(name).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                then.accept(null);
                return;
            }
            String owner = task.getResult().getValue(String.class);
            then.accept(owner == null || owner.equals(uid));
        });
    }

    /**
     * Claims {@code name} and releases {@code previous} in one atomic update; the rules refuse
     * it if someone else got there first.
     */
    static void claimUsername(String name, @Nullable String previous, Callback callback) {
        String uid = uid();
        if (uid == null) {
            callback.done("Not signed in");
            return;
        }
        Map<String, Object> update = new HashMap<>();
        update.put("usernames/" + name, uid);
        update.put("users/" + uid + "/profile/username", name);
        if (previous != null && !previous.equals(name)) update.put("usernames/" + previous, null);
        root().updateChildren(update).addOnCompleteListener(task -> callback.done(task.isSuccessful() ? null
                : "@" + name + " is taken. Try another."));
    }

    // ================================================================= photo

    /** The account's photo as others see it: {"url":...} or a custom photo's JSON. */
    static void loadPhoto(Consumer<String> then) {
        String uid = uid();
        if (uid == null) {
            then.accept(null);
            return;
        }
        root().child("profiles").child(uid).child("photo").get().addOnCompleteListener(t ->
                then.accept(t.isSuccessful() ? ChatSync.jsonOf(t.getResult().getValue()) : null));
    }

    /** A custom photo (shrunk, encrypted, on Drive), or null to go back to the Google one. Blocking. */
    static void setPhoto(Context context, @Nullable Uri source) throws Exception {
        String uid = uid();
        FirebaseUser user = user();
        if (uid == null || user == null) throw new java.io.IOException("Not signed in");
        Object value;
        if (source == null) {
            if (user.getPhotoUrl() == null) {
                value = null;
            } else {
                Map<String, Object> google = new HashMap<>();
                google.put("url", clip(user.getPhotoUrl().toString(), 2000));
                value = google;
            }
        } else {
            DriveStore drive = drive(context);
            if (drive == null) throw new java.io.IOException("Connect Google Drive first: photos are kept there");
            value = ChatSync.toMap(ChatMedia.uploadPhoto(context, drive, source).toJson());
        }
        Tasks.await(root().child("profiles").child(uid).child("photo").setValue(value), TIMEOUT_S, TimeUnit.SECONDS);
    }

    // ================================================================= Drive

    /**
     * Asks for {@code drive.file} on {@code email}'s Google account (or lets the user pick one).
     * The result may need a consent screen: see {@link AuthorizationResult#hasResolution()}.
     */
    static Task<AuthorizationResult> authorizeDrive(Activity activity, @Nullable String email) {
        AuthorizationRequest.Builder request = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(DriveScopes.DRIVE_FILE)));
        if (email != null) request.setAccount(new android.accounts.Account(email, "com.google"));
        return Identity.getAuthorizationClient(activity).authorize(request.build());
    }

    /** Records which Google account holds this user's attachments. False if it can't tell. */
    static boolean driveAuthorized(Context context, AuthorizationResult result, @Nullable String fallbackEmail) {
        GoogleSignInAccount account = result.toGoogleSignInAccount();
        String email = account != null && account.getEmail() != null ? account.getEmail() : fallbackEmail;
        if (email == null) return false;
        prefs(context).edit().putString(PREF_DRIVE_EMAIL, email).remove(NoteSync.PREF_DRIVE_ERROR).apply();
        String uid = uid();
        if (uid != null) root().child("users").child(uid).child("profile").child("driveEmail").setValue(email);
        NoteSync.schedule(context);
        return true;
    }

    @Nullable
    static String driveEmail(Context context) {
        return prefs(context).getString(PREF_DRIVE_EMAIL, null);
    }

    static void disconnectDrive(Context context) {
        prefs(context).edit().remove(PREF_DRIVE_EMAIL).remove(NoteSync.PREF_DRIVE_ERROR).apply();
    }

    /** The user's own Drive, or null if they have not connected one. Worker threads only. */
    @Nullable
    static DriveStore drive(Context context) {
        String email = driveEmail(context);
        return email == null ? null : new DriveStore(context, email);
    }

    // ========================================================= sign out / delete

    /**
     * Signs out. {@code keepNotes}: the account's notes stay on this phone as its own notes;
     * otherwise they and their files are removed from it. The account itself is untouched.
     */
    static void signOut(Context context, boolean keepNotes, Runnable done) {
        Context app = context.getApplicationContext();
        String uid = user() == null ? null : user().getUid();
        AppDatabase.IO.execute(() -> {
            NoteSync.stopLive();
            NoteSync.cancel(app);
            if (uid != null) NoteSync.leave(app, uid, keepNotes);
            FirebaseAuth.getInstance().signOut();
            disconnectDrive(app);
            CredentialManager.create(app).clearCredentialStateAsync(new ClearCredentialStateRequest(),
                    new CancellationSignal(), ContextCompat.getMainExecutor(app),
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override
                        public void onResult(Void unused) {}

                        @Override
                        public void onError(@NonNull ClearCredentialException e) {
                            Log.w(TAG, "Could not clear the credential state", e);
                        }
                    });
            ContextCompat.getMainExecutor(app).execute(done);
        });
    }

    /**
     * Proves it is really the owner (Google sheet, or {@code password} for email accounts),
     * then removes everything the account stored: its Drive attachments, its notes and
     * username in the database, and finally the account. Notes stay on this phone.
     */
    static void deleteAccount(Activity activity, @Nullable String password, Callback callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.done("Not signed in");
            return;
        }
        Consumer<AuthCredential> withCredential = credential -> user.reauthenticate(credential)
                .addOnCompleteListener(auth -> {
                    if (!auth.isSuccessful()) {
                        callback.done(friendly(auth.getException()));
                        return;
                    }
                    Context app = activity.getApplicationContext();
                    AppDatabase.IO.execute(() -> {
                        String error = null;
                        try {
                            eraseEverything(app, user);
                        } catch (Exception e) {
                            Log.w(TAG, "Account deletion failed", e);
                            error = friendly(e);
                        }
                        String result = error;
                        ContextCompat.getMainExecutor(app).execute(() -> callback.done(result));
                    });
                });
        if (isGoogle(user)) {
            googleCredential(activity, withCredential, callback);
        } else if (password == null || user.getEmail() == null) {
            callback.done("Enter your password");
        } else {
            withCredential.accept(EmailAuthProvider.getCredential(user.getEmail(), password));
        }
    }

    private static void eraseEverything(Context context, FirebaseUser user) throws Exception {
        String uid = user.getUid();
        NoteSync.stopLive();
        NoteSync.cancel(context);
        NoteSync.eraseAttachments(context);
        String username = Tasks.await(root().child("users").child(uid).child("profile").child("username").get(),
                TIMEOUT_S, TimeUnit.SECONDS).getValue(String.class);
        // Notes I sent stop being readable by anyone.
        for (NoteEntity n : AppDatabase.getInstance(context).noteDao().sentNotes(uid)) {
            try {
                SentNotes.takeDown(context, uid, n);
            } catch (Exception e) {
                Log.w(TAG, "A sent note wasn't taken down", e);
            }
        }
        Map<String, Object> wipe = new HashMap<>();
        wipe.put("users/" + uid, null);
        wipe.put("chatHome/" + uid, null);
        wipe.put("chatIndex/" + uid, null);
        wipe.put("chatVaults/" + uid, null);
        wipe.put("chatPlace/" + uid, null);
        wipe.put("chatSessions/" + uid, null);
        wipe.put("chatNick/" + uid, null);
        wipe.put("profiles/" + uid, null);
        wipe.put("vaultPhotos/" + uid, null);
        wipe.put("groupIndex/" + uid, null);
        wipe.put("inbox/" + uid, null);
        if (username != null) wipe.put("usernames/" + username, null);
        Tasks.await(root().updateChildren(wipe), TIMEOUT_S, TimeUnit.SECONDS);
        Tasks.await(user.delete(), TIMEOUT_S, TimeUnit.SECONDS);
        NoteSync.leave(context, uid, true);
        disconnectDrive(context);
    }

    // =============================================================== helpers

    /** Firebase's messages are written for developers; these are for people. */
    static String friendly(@Nullable Exception e) {
        if (e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof Exception) {
            e = (Exception) e.getCause();
        }
        if (e == null) return "Something went wrong";
        if (e instanceof FirebaseNetworkException) return "No connection. Try again when you're online.";
        if (e instanceof FirebaseAuthWeakPasswordException) return "Choose a password of at least 6 characters";
        if (e instanceof FirebaseAuthUserCollisionException) return "An account with this email already exists. Sign in instead.";
        if (e instanceof FirebaseAuthInvalidUserException) return "No account with that email";
        if (e instanceof FirebaseAuthInvalidCredentialsException) return "Wrong email or password";
        if (e instanceof java.util.concurrent.TimeoutException) return "That took too long. Check your connection.";
        Log.w(TAG, "Auth error", e);
        return e.getMessage() == null ? "Something went wrong" : e.getMessage();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static SharedPreferences prefs(Context context) {
        return SecurityHelper.getAppPrefs(context);
    }
}
