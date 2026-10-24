package org.flexstudios.notes.plus;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Group notes: any note but audio, shared with up to {@link #MAX} people, who all edit it.
 *
 * <p>Online a group note is {@code groupNotes/{id}}: {@code owner}, {@code members} {uid:
 * username}, {@code size}, and {@code note}, the same shape as a note of my own. It syncs exactly
 * like one ({@link NoteSync}: revisions, conflict copies, edits pushed when the editor closes);
 * only the place differs. {@code groupIndex/{uid}/{id}} tells each member's phones there is news.
 * Its photos and videos are encrypted and shared by link, like chat photos, so every member can
 * open them.
 *
 * <p>Sharing a note turns it into a group note under the same id; my other phones swap their
 * copy for the group one. Leaving (or deleting it here) leaves the group; being removed keeps
 * the note here, as my own again.
 */
final class GroupNotes {

    static final int MAX = 20;
    private static final String TAG = "GroupNotes";
    private static final long TIMEOUT_S = 30;

    private GroupNotes() {}

    // ================================================================ online

    /**
     * Shares {@code noteId} with these people, making it a group note first if it isn't one.
     * Blocking.
     */
    static void invite(Context context, int noteId, List<ChatSync.Contact> people) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        NoteEntity note = dao.getNoteById(noteId);
        String uid = Account.uid();
        if (note == null || uid == null) throw new IOException("Sign in first");
        if (NoteData.AUDIO.equals(note.getType())) throw new IOException("Audio notes can't be shared");
        if (note.isReceived()) throw new IOException("Only the person who wrote it can share it");
        if (note.getSentTo() != null) throw new IOException("This note is sent to people: stop sending it first");
        String myName = await(Account.root().child("users").child(uid).child("profile").child("username").get())
                .getValue(String.class);
        if (myName == null) throw new IOException("Choose a username in your account first");

        if (note.getGroupId() == null) {
            create(context, note, uid, myName, people);
            return;
        }
        String gid = note.getGroupId();
        JSONObject members = membersOf(await(Account.root().child("groupNotes").child(gid).child("members").get()));
        Map<String, Object> update = new HashMap<>();
        for (ChatSync.Contact p : people) {
            if (members.has(p.uid)) continue;
            members.put(p.uid, p.name);
            update.put("groupNotes/" + gid + "/members/" + p.uid, p.name);
            update.put("groupIndex/" + p.uid + "/" + gid, at());
        }
        if (members.length() > MAX) throw new IOException("A group note has at most " + MAX + " people");
        if (update.isEmpty()) return;
        update.put("groupNotes/" + gid + "/size", members.length());
        await(Account.root().updateChildren(update));
        dao.setGroupMembers(note.getId(), members.toString());
    }

    /** The first share: the note moves from my own notes to its group, under the same id. */
    private static void create(Context context, NoteEntity note, String uid, String myName,
                               List<ChatSync.Contact> people) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        String gid = note.getUuid();
        JSONObject members = new JSONObject().put(uid, myName);
        for (ChatSync.Contact p : people) members.put(p.uid, p.name);
        if (members.length() > MAX) throw new IOException("A group note has at most " + MAX + " people");

        NoteEntity fresh = dao.getNoteById(note.getId());
        fresh.setRev(0);
        fresh.setMedia(null); // Its files are re-shared the group's way on the next sync.
        Map<String, Object> value = NoteSync.toRemote(context, fresh);
        value.remove("archived"); // How it's filed stays mine.
        value.remove("folder");
        Map<String, Object> update = new HashMap<>();
        update.put("groupNotes/" + gid + "/owner", uid);
        update.put("groupNotes/" + gid + "/size", members.length());
        update.put("groupNotes/" + gid + "/note", value);
        for (Iterator<String> it = members.keys(); it.hasNext(); ) {
            String member = it.next();
            update.put("groupNotes/" + gid + "/members/" + member, members.getString(member));
            update.put("groupIndex/" + member + "/" + gid, at());
        }
        await(Account.root().updateChildren(update));
        boolean hasFiles = note.getMedia() != null;
        dao.makeGroup(note.getId(), gid, members.toString(), 1, hasFiles);
        retirePersonal(uid, note);
        NoteSync.schedule(context);
    }

    /** My other phones drop their own copy: the group one replaces it. */
    private static void retirePersonal(String uid, NoteEntity note) {
        if (note.getRev() == 0) return; // Never reached my own notes online.
        Account.notesOf(uid).child(note.getUuid()).runTransaction(new com.google.firebase.database.Transaction.Handler() {
            @Override
            public com.google.firebase.database.Transaction.Result doTransaction(MutableData current) {
                Long rev = current.child("rev").getValue(Long.class);
                if (current.getValue() == null || rev == null) return com.google.firebase.database.Transaction.abort();
                Map<String, Object> tombstone = new HashMap<>();
                tombstone.put("rev", rev + 1);
                tombstone.put("updatedAt", ServerValue.TIMESTAMP);
                tombstone.put("deleted", true);
                current.setValue(tombstone);
                return com.google.firebase.database.Transaction.success(current);
            }

            @Override
            public void onComplete(@Nullable com.google.firebase.database.DatabaseError error, boolean committed,
                                   @Nullable DataSnapshot snapshot) {
                if (error != null) Log.w(TAG, "Couldn't retire the personal copy", error.toException());
            }
        }, false);
    }

    /** After a push: every member's phones hear there's a new version. */
    static void announce(NoteEntity note) {
        JSONObject members = json(note.getGroupMembers());
        Map<String, Object> update = new HashMap<>();
        for (Iterator<String> it = members.keys(); it.hasNext(); ) {
            update.put("groupIndex/" + it.next() + "/" + note.getGroupId(), at());
        }
        if (!update.isEmpty()) Account.root().updateChildren(update);
    }

    /** I leave; the others keep it. Blocking. */
    static void leave(Context context, NoteEntity note) throws Exception {
        String uid = Account.uid();
        String gid = note.getGroupId();
        if (uid == null || gid == null) return;
        try {
            Long size = await(Account.root().child("groupNotes").child(gid).child("size").get()).getValue(Long.class);
            Map<String, Object> update = new HashMap<>();
            update.put("groupNotes/" + gid + "/members/" + uid, null);
            update.put("groupNotes/" + gid + "/size", Math.max(0, (size == null ? 1 : size) - 1));
            update.put("groupIndex/" + uid + "/" + gid, null);
            await(Account.root().updateChildren(update));
        } catch (java.util.concurrent.ExecutionException e) {
            if (!ChatSync.refused(e)) throw e; // Already out of it.
        }
    }

    /** The owner removes someone; their copy stays theirs. Blocking. */
    static void remove(Context context, NoteEntity note, String member) throws Exception {
        String gid = note.getGroupId();
        if (gid == null) return;
        JSONObject members = json(note.getGroupMembers());
        members.remove(member);
        Map<String, Object> update = new HashMap<>();
        update.put("groupNotes/" + gid + "/members/" + member, null);
        update.put("groupNotes/" + gid + "/size", members.length());
        update.put("groupIndex/" + member + "/" + gid, null);
        await(Account.root().updateChildren(update));
        AppDatabase.getInstance(context).noteDao().setGroupMembers(note.getId(), members.toString());
    }

    // ================================================================ screen

    /**
     * The members sheet from an editor's menu: who's in, invite people, remove (owner) or leave.
     * {@code beforeSharing} saves the note first, so the newest text is what gets shared.
     */
    static void showSheet(Activity activity, int noteId, Runnable beforeSharing) {
        if (Account.uid() == null) {
            Toast.makeText(activity, "Sign in to your account to share notes", Toast.LENGTH_LONG).show();
            return;
        }
        beforeSharing.run();
        AppDatabase.IO.execute(() -> {
            NoteEntity note = noteId > 0 ? AppDatabase.getInstance(activity).noteDao().getNoteById(noteId) : null;
            String owner = null;
            if (note != null && note.getGroupId() != null) {
                try {
                    owner = await(Account.root().child("groupNotes").child(note.getGroupId()).child("owner").get())
                            .getValue(String.class);
                } catch (Exception ignored) {
                    // Offline: shown without owner powers.
                }
            }
            String ownerUid = owner;
            activity.runOnUiThread(() -> sheet(activity, noteId, note, ownerUid));
        });
    }

    private static void sheet(Activity activity, int noteId, @Nullable NoteEntity note, @Nullable String owner) {
        if (note == null) {
            Toast.makeText(activity, "Write something first, then share it", Toast.LENGTH_LONG).show();
            return;
        }
        String me = Account.uid();
        JSONObject members = json(note.getGroupMembers());
        List<String> uids = new ArrayList<>();
        for (Iterator<String> it = members.keys(); it.hasNext(); ) uids.add(it.next());
        Collections.sort(uids, (a, b) -> members.optString(a).compareToIgnoreCase(members.optString(b)));
        String[] rows = new String[uids.size()];
        for (int i = 0; i < uids.size(); i++) {
            String uid = uids.get(i);
            rows[i] = "@" + members.optString(uid) + (uid.equals(owner) ? "  · owner" : "")
                    + (uid.equals(me) ? "  (you)" : "");
        }
        boolean shared = note.getGroupId() != null;
        boolean iOwn = me != null && me.equals(owner);
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(shared ? "Shared with " + uids.size() + " of " + MAX : "Share this note")
                .setNegativeButton("Close", null);
        if (shared) {
            dialog.setItems(rows, (d, which) -> {
                String uid = uids.get(which);
                if (iOwn && !uid.equals(me)) confirmRemove(activity, note, uid, members.optString(uid));
            });
            dialog.setNeutralButton("Leave", (d, w) -> confirmLeave(activity, note));
        } else {
            dialog.setMessage("Invite up to " + (MAX - 1) + " people by username. Everyone can edit it, and it "
                    + "stays in step on every phone, like your own notes do across yours.");
        }
        if (!shared || uids.size() < MAX) dialog.setPositiveButton("Invite people", (d, w) -> askInvite(activity, noteId));
        dialog.show();
    }

    private static void askInvite(Activity activity, int noteId) {
        Prompts.text(activity, "Invite people", "Usernames, separated by commas.", "Usernames", null,
                Prompts.TEXT, "Invite", raw -> ChatSync.resolvePeople(raw, people -> AppDatabase.IO.execute(() -> {
                    String error = null;
                    try {
                        invite(activity, noteId, people);
                    } catch (Exception e) {
                        error = e.getMessage() == null ? "Couldn't share it" : e.getMessage();
                    }
                    String message = error;
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            message != null ? message : "Shared. They'll see it in their notes.", Toast.LENGTH_LONG).show());
                }), error -> Toast.makeText(activity, error, Toast.LENGTH_LONG).show()), null);
    }

    private static void confirmRemove(Activity activity, NoteEntity note, String uid, String name) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Remove @" + name + "?")
                .setMessage("They keep the copy they have, but won't see changes any more.")
                .setPositiveButton("Remove", (d, w) -> AppDatabase.IO.execute(() -> {
                    try {
                        remove(activity, note, uid);
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, "Couldn't remove them", Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void confirmLeave(Activity activity, NoteEntity note) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Leave this group note?")
                .setMessage("You keep it as your own note; the others keep theirs.")
                .setPositiveButton("Leave", (d, w) -> AppDatabase.IO.execute(() -> {
                    try {
                        leave(activity, note);
                        AppDatabase.getInstance(activity).noteDao().leaveGroup(note.getId());
                        NoteSync.schedule(activity);
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, "Couldn't leave right now", Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================================================ helpers

    /** {uid: username} from a members snapshot, as JSON for the note row. */
    static String membersJson(DataSnapshot members) throws Exception {
        return membersOf(members).toString();
    }

    private static JSONObject membersOf(DataSnapshot members) throws Exception {
        JSONObject out = new JSONObject();
        for (DataSnapshot m : members.getChildren()) {
            String name = m.getValue(String.class);
            if (m.getKey() != null && name != null) out.put(m.getKey(), name);
        }
        return out;
    }

    static int count(@Nullable String json) {
        return json(json).length();
    }

    private static JSONObject json(@Nullable String s) {
        try {
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static Map<String, Object> at() {
        Map<String, Object> m = new HashMap<>();
        m.put("at", ServerValue.TIMESTAMP);
        return m;
    }

    private static <T> T await(com.google.android.gms.tasks.Task<T> task) throws Exception {
        return Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS);
    }
}
