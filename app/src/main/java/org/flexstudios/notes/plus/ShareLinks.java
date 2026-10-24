package org.flexstudios.notes.plus;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.ServerValue;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Links that open a note in any browser, with Notes+ on the web: no account needed to read it,
 * one needed to save it. And invite links for group notes: whoever opens one, signed in, joins.
 *
 * A note's link is {@code links/{token}} (readable by anyone who has the token, never listable):
 * {@code from}, {@code fromName}, {@code uuid}, {@code at}, and {@code note} (its words and
 * structure, like a synced note but without files). {@code users/{uid}/links/{uuid}} = token
 * tells my devices which notes have one: after each push of such a note it is published there
 * again, so the link always shows the latest version. Deleting the note, or locking it, takes
 * its link down.
 *
 * A group note's invite is {@code groupInvites/{token}} = {gid, by, byName, name, at}, with
 * {@code groupNotes/{gid}/invite} = token so every member sees the same link. The database rules
 * let a signed-in person add themself to the group only with a valid token.
 */
final class ShareLinks {

    private ShareLinks() {}

    private static final String TAG = "ShareLinks";
    private static final long TIMEOUT_S = 30;
    static final String VIEW = "https://notes.talken.in/app/#/s/";
    static final String JOIN = "https://notes.talken.in/app/#/j/";

    /** My notes' link tokens by note uuid, as last read (or written) here. */
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static volatile String loadedFor;

    static String newToken() {
        byte[] raw = new byte[18];
        new SecureRandom().nextBytes(raw);
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** Words and structure only: not someone else's, not a recording, not locked, not a group's. */
    static boolean shareable(NoteEntity n) {
        return !n.isReceived() && !NoteData.AUDIO.equals(n.getType()) && !NoteLock.isLocked(n.getData()) && n.getGroupId() == null;
    }

    /** Reads which of my notes have links. Blocking; once per sync pass. */
    static void refresh(String uid) throws Exception {
        DataSnapshot all = await(Account.root().child("users").child(uid).child("links").get());
        TOKENS.clear();
        for (DataSnapshot c : all.getChildren()) {
            String t = c.getValue(String.class);
            if (c.getKey() != null && t != null) TOKENS.put(c.getKey(), t);
        }
        loadedFor = uid;
    }

    /** What a link shows: the synced note's words and structure, without its files. */
    private static Map<String, Object> noteValue(Context context, NoteEntity n) {
        Map<String, Object> full = NoteSync.toRemote(context, n), out = new HashMap<>();
        for (String k : new String[]{"type", "title", "content", "data", "sketch", "color", "edited"}) {
            if (full.containsKey(k)) out.put(k, full.get(k));
        }
        return out;
    }

    private static String myName(String uid) throws Exception {
        String name = await(Account.root().child("users").child(uid).child("profile").child("username").get())
                .getValue(String.class);
        if (name == null) throw new IOException("Choose a username in your account first");
        return name;
    }

    /** A new link for {@code n}. Blocking. Returns its address. */
    static String create(Context context, String uid, NoteEntity n) throws Exception {
        String token = newToken();
        Map<String, Object> link = new HashMap<>();
        link.put("from", uid);
        link.put("fromName", myName(uid));
        link.put("uuid", n.getUuid());
        link.put("at", ServerValue.TIMESTAMP);
        link.put("note", noteValue(context, n));
        Map<String, Object> update = new HashMap<>();
        update.put("links/" + token, link);
        update.put("users/" + uid + "/links/" + n.getUuid(), token);
        await(Account.root().updateChildren(update));
        TOKENS.put(n.getUuid(), token);
        return VIEW + token;
    }

    /** Takes {@code uuid}'s link down; the address stops working. Blocking. */
    static void stop(String uid, String uuid) throws Exception {
        String token = TOKENS.get(uuid);
        if (token == null) token = await(Account.root().child("users").child(uid).child("links").child(uuid).get()).getValue(String.class);
        Map<String, Object> update = new HashMap<>();
        update.put("users/" + uid + "/links/" + uuid, null);
        if (token != null) update.put("links/" + token, null);
        await(Account.root().updateChildren(update));
        TOKENS.remove(uuid);
    }

    /** After a push (or a delete) of one of my notes: its link, if it has one, follows. Never throws. */
    static void afterPush(Context context, String uid, NoteEntity n) {
        String token = TOKENS.get(n.getUuid());
        if (token == null || !uid.equals(loadedFor)) return;
        try {
            if (n.isDeleted() || !shareable(n)) {
                stop(uid, n.getUuid());
            } else {
                Map<String, Object> update = new HashMap<>();
                update.put("note", noteValue(context, n));
                update.put("at", ServerValue.TIMESTAMP);
                await(Account.root().child("links").child(token).updateChildren(update));
            }
        } catch (Exception e) {
            Log.w(TAG, "The link catches up on the next sync", e);
        }
    }

    // ================================================================== sheets

    /** The note's link: make one, or copy or stop the one it has. */
    static void sheet(Activity activity, int noteId) {
        String uid = Account.uid();
        if (uid == null) {
            toast(activity, "Sign in to your account to share notes by link");
            return;
        }
        Context app = activity.getApplicationContext();
        AppDatabase.IO.execute(() -> {
            NoteEntity n = AppDatabase.getInstance(app).noteDao().getNoteById(noteId);
            String token = null;
            boolean offline = false;
            try {
                if (n != null && !uid.equals(loadedFor)) refresh(uid);
                if (n != null) token = TOKENS.get(n.getUuid());
            } catch (Exception e) {
                offline = true;
            }
            String found = token;
            boolean failed = offline;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (n == null) {
                    toast(activity, "Write something first, then share it");
                } else if (failed) {
                    toast(activity, "Couldn't reach your account. Try again when you're online.");
                } else if (!shareable(n)) {
                    toast(activity, NoteLock.isLocked(n.getData()) ? "A locked note can't be shared by link"
                            : NoteData.AUDIO.equals(n.getType()) ? "Recordings can't be shared by link yet"
                            : "Group notes are shared with an invite link instead");
                } else if (found == null) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle("Share by link")
                            .setMessage("Anyone with the link can read this note in their browser, without an account, "
                                    + "and always sees your latest version. To keep a copy they sign in. Pictures and "
                                    + "files stay in the apps for now.")
                            .setPositiveButton("Create link", (d, w) -> work(activity, () -> create(app, uid, n), url -> {
                                copy(activity, url);
                                toast(activity, "Link copied");
                            }))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    String url = VIEW + found;
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle("Shared by link")
                            .setMessage(url + "\n\nAnyone with this link can read the note.")
                            .setPositiveButton("Copy link", (d, w) -> {
                                copy(activity, url);
                                toast(activity, "Link copied");
                            })
                            .setNeutralButton("Stop sharing", (d, w) -> work(activity, () -> {
                                stop(uid, n.getUuid());
                                return "";
                            }, x -> toast(activity, "The link no longer opens this note")))
                            .setNegativeButton("Close", null)
                            .show();
                }
            });
        });
    }

    /** A group note's invite link: whoever opens it, signed in, joins (up to the group's 20). */
    static void inviteSheet(Activity activity, NoteEntity n) {
        String uid = Account.uid();
        if (uid == null || n.getGroupId() == null) return;
        String gid = n.getGroupId();
        work(activity, () -> {
            String t = await(Account.root().child("groupNotes").child(gid).child("invite").get()).getValue(String.class);
            return t == null ? "" : t;
        }, token -> {
            if (token.isEmpty()) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Invite link")
                        .setMessage("Anyone who opens the link and signs in joins this note, and can read and change it. "
                                + "Share it only with people you'd add yourself.")
                        .setPositiveButton("Create link", (d, w) -> work(activity, () -> {
                            String t = newToken();
                            Map<String, Object> invite = new HashMap<>();
                            invite.put("gid", gid);
                            invite.put("by", uid);
                            invite.put("byName", myName(uid));
                            invite.put("name", n.getTitle() == null ? "" : n.getTitle());
                            invite.put("at", ServerValue.TIMESTAMP);
                            Map<String, Object> update = new HashMap<>();
                            update.put("groupInvites/" + t, invite);
                            update.put("groupNotes/" + gid + "/invite", t);
                            await(Account.root().updateChildren(update));
                            return JOIN + t;
                        }, url -> {
                            copy(activity, url);
                            toast(activity, "Invite link copied");
                        }))
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                String url = JOIN + token;
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Invite link")
                        .setMessage(url + "\n\nWhoever opens it and signs in joins this note.")
                        .setPositiveButton("Copy link", (d, w) -> {
                            copy(activity, url);
                            toast(activity, "Invite link copied");
                        })
                        .setNeutralButton("Stop the link", (d, w) -> work(activity, () -> {
                            Map<String, Object> update = new HashMap<>();
                            update.put("groupInvites/" + token, null);
                            update.put("groupNotes/" + gid + "/invite", null);
                            await(Account.root().updateChildren(update));
                            return "";
                        }, x -> toast(activity, "The invite link no longer works")))
                        .setNegativeButton("Close", null)
                        .show();
            }
        });
    }

    interface Work {
        String run() throws Exception;
    }

    /** Network work on the shared pool; a failure is a toast. */
    private static void work(Activity activity, Work job, java.util.function.Consumer<String> then) {
        AppDatabase.IO.execute(() -> {
            try {
                String r = job.run();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing()) then.accept(r);
                });
            } catch (Exception e) {
                Log.w(TAG, "Link", e);
                String why = e.getMessage() != null && e.getMessage().contains("username") ? e.getMessage()
                        : "Couldn't do that. Check your connection and try again.";
                activity.runOnUiThread(() -> toast(activity, why));
            }
        });
    }

    private static void copy(Context context, String text) {
        ClipboardManager cb = context.getSystemService(ClipboardManager.class);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Notes+ link", text));
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private static <T> T await(com.google.android.gms.tasks.Task<T> task) throws Exception {
        return Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS);
    }
}
