package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links that open a note in any browser, with Notes+ on the web: no account needed to read it,
 * one needed to save it. And invite links for group notes: whoever opens one, signed in, joins.
 *
 * A note's link is {@code links/{token}} (readable by anyone who has the token, never listable):
 * {@code from}, {@code fromName}, {@code uuid}, {@code at}, and {@code note} (its words and
 * structure, like a synced note but without files). {@code users/{uid}/links/{uuid}} = token
 * tells my devices which notes have one: after each push of such a note, it is published there
 * again, so the link always shows the latest version. Deleting the note, or locking it, takes
 * its link down.
 *
 * A group note's invite is {@code groupInvites/{token}} = {gid, by, byName, name, at}, with
 * {@code groupNotes/{gid}/invite} = token so every member sees the same link. The database rules
 * let a signed-in person add themself to the group only with a valid token.
 */
final class ShareLinks {

    private ShareLinks() {}

    private static final System.Logger LOG = System.getLogger("ShareLinks");
    static final String VIEW = "https://notes.talken.in/app/#/s/";
    static final String JOIN = "https://notes.talken.in/app/#/j/";

    /** My notes' link tokens by note uuid, as last read (or written) here. */
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static volatile String loadedFor;

    static String newToken() {
        byte[] raw = new byte[18];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Words and structure only: not someone else's, not a recording, not locked, not a group's. */
    static boolean shareable(NoteEntity n) {
        return !n.isReceived() && !NoteData.AUDIO.equals(n.getType()) && !NoteLock.isLocked(n.getData()) && n.getGroupId() == null;
    }

    /** Reads which of my notes have links. Blocking; once per sync pass. */
    static void refresh(String uid) throws IOException {
        JSONObject all = Firebase.getObject("users/" + uid + "/links");
        TOKENS.clear();
        if (all != null) for (String uuid : all.keySet()) if (all.opt(uuid) instanceof String t) TOKENS.put(uuid, t);
        loadedFor = uid;
    }

    /** What a link shows: the synced note's words and structure, without its files. */
    private static JSONObject noteValue(NoteEntity n) {
        JSONObject full = NoteSync.toRemote(n), out = new JSONObject();
        for (String k : new String[]{"type", "title", "content", "data", "sketch", "color", "edited"}) {
            if (full.has(k)) out.put(k, full.get(k));
        }
        return out;
    }

    private static String myName(String uid) throws IOException {
        String name = Firebase.get("users/" + uid + "/profile/username", null) instanceof String s ? s : null;
        if (name == null) throw new IOException("Choose a username in your account first");
        return name;
    }

    /** A new link for {@code n}. Blocking. Returns its address. */
    static String create(String uid, NoteEntity n) throws IOException {
        String token = newToken();
        JSONObject link = new JSONObject().put("from", uid).put("fromName", myName(uid)).put("uuid", n.getUuid())
                .put("at", Firebase.now()).put("note", noteValue(n));
        Firebase.patch("", new JSONObject().put("links/" + token, link).put("users/" + uid + "/links/" + n.getUuid(), token));
        TOKENS.put(n.getUuid(), token);
        return VIEW + token;
    }

    /** Takes {@code uuid}'s link down; the address stops working. Blocking. */
    static void stop(String uid, String uuid) throws IOException {
        String token = TOKENS.get(uuid);
        if (token == null && Firebase.get("users/" + uid + "/links/" + uuid, null) instanceof String t) token = t;
        JSONObject update = new JSONObject().put("users/" + uid + "/links/" + uuid, JSONObject.NULL);
        if (token != null) update.put("links/" + token, JSONObject.NULL);
        Firebase.patch("", update);
        TOKENS.remove(uuid);
    }

    /** After a push (or a delete) of one of my notes: its link, if it has one, follows. Never throws. */
    static void afterPush(String uid, NoteEntity n) {
        String token = TOKENS.get(n.getUuid());
        if (token == null || !uid.equals(loadedFor)) return;
        try {
            if (n.isDeleted() || !shareable(n)) stop(uid, n.getUuid());
            else Firebase.patch("links/" + token, new JSONObject().put("note", noteValue(n)).put("at", Firebase.now()));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "The link catches up on the next sync", e);
        }
    }

    // ================================================================== sheets

    /** The note's link: make one, or copy or stop the one it has. */
    static void sheet(int noteId) {
        String uid = Account.uid();
        if (uid == null) {
            Ui.toast("Sign in to your account to share notes by link");
            return;
        }
        NoteStore.load(s -> {
            NoteEntity n = s.byId(noteId);
            if (n == null) return null;
            try {
                if (!uid.equals(loadedFor)) refresh(uid);
            } catch (IOException e) {
                return new Object[]{n, e};
            }
            return new Object[]{n, TOKENS.get(n.getUuid())};
        }, r -> {
            if (r == null) {
                Ui.toast("Write something first, then share it");
                return;
            }
            NoteEntity n = (NoteEntity) r[0];
            if (r[1] instanceof Exception) {
                Ui.toast("Couldn't reach your account. Try again when you're online.");
                return;
            }
            if (!shareable(n)) {
                Ui.toast(NoteLock.isLocked(n.getData()) ? "A locked note can't be shared by link"
                        : NoteData.AUDIO.equals(n.getType()) ? "Recordings can't be shared by link yet"
                        : "Group notes are shared with an invite link instead");
                return;
            }
            String token = (String) r[1];
            if (token == null) {
                Ui.dialog().title("Share by link")
                        .message("Anyone with the link can read this note in their browser, without an account, and "
                                + "always sees your latest version. To keep a copy they sign in. Pictures and files stay "
                                + "in the apps for now.")
                        .positive("Create link", () -> work(() -> create(uid, n), url -> {
                            copy(url);
                            Ui.toast("Link copied");
                        }))
                        .negative("Cancel", null).width(460).show();
            } else {
                String url = VIEW + token;
                Ui.dialog().title("Shared by link").message(url + "\n\nAnyone with this link can read the note.")
                        .positive("Copy link", () -> {
                            copy(url);
                            Ui.toast("Link copied");
                        })
                        .neutral("Stop sharing", () -> work(() -> {
                            stop(uid, n.getUuid());
                            return null;
                        }, x -> Ui.toast("The link no longer opens this note")))
                        .negative("Close", null).width(460).show();
            }
        });
    }

    /** A group note's invite link: whoever opens it, signed in, joins (up to the group's 20). */
    static void inviteSheet(NoteEntity n) {
        String uid = Account.uid();
        if (uid == null || n.getGroupId() == null) return;
        String gid = n.getGroupId();
        work(() -> Firebase.get("groupNotes/" + gid + "/invite", null) instanceof String t ? t : "", token -> {
            if (token.isEmpty()) {
                Ui.dialog().title("Invite link")
                        .message("Anyone who opens the link and signs in joins this note, and can read and change it. "
                                + "Share it only with people you'd add yourself.")
                        .positive("Create link", () -> work(() -> {
                            String t = newToken();
                            JSONObject invite = new JSONObject().put("gid", gid).put("by", uid).put("byName", myName(uid))
                                    .put("name", n.getTitle() == null ? "" : n.getTitle()).put("at", Firebase.now());
                            Firebase.patch("", new JSONObject().put("groupInvites/" + t, invite).put("groupNotes/" + gid + "/invite", t));
                            return JOIN + t;
                        }, url -> {
                            copy(url);
                            Ui.toast("Invite link copied");
                        }))
                        .negative("Cancel", null).width(460).show();
            } else {
                String url = JOIN + token;
                Ui.dialog().title("Invite link").message(url + "\n\nWhoever opens it and signs in joins this note.")
                        .positive("Copy link", () -> {
                            copy(url);
                            Ui.toast("Invite link copied");
                        })
                        .neutral("Stop the link", () -> work(() -> {
                            Firebase.patch("", new JSONObject().put("groupInvites/" + token, JSONObject.NULL)
                                    .put("groupNotes/" + gid + "/invite", JSONObject.NULL));
                            return null;
                        }, x -> Ui.toast("The invite link no longer works")))
                        .negative("Close", null).width(460).show();
            }
        });
    }

    interface Work<T> {
        T run() throws Exception;
    }

    /** Network work off the FX thread; a failure is a toast. */
    private static <T> void work(Work<T> job, java.util.function.Consumer<T> then) {
        Thread t = new Thread(() -> {
            try {
                T r = job.run();
                javafx.application.Platform.runLater(() -> then.accept(r));
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Link", e);
                String why = e.getMessage() != null && e.getMessage().contains("username") ? e.getMessage()
                        : "Couldn't do that. Check your connection and try again.";
                javafx.application.Platform.runLater(() -> Ui.toast(why));
            }
        }, "share-link");
        t.setDaemon(true);
        t.start();
    }

    private static void copy(String text) {
        javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
        c.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
    }
}
