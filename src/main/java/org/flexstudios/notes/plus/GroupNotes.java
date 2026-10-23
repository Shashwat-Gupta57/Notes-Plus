package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/**
 * Group notes: any note but audio, shared with up to {@link #MAX} people, who all edit it. The
 * phone's GroupNotes, over REST.
 *
 * <p>Online a group note is {@code groupNotes/{id}}: {@code owner}, {@code members} {uid:
 * username}, {@code size}, and {@code note}, the same shape as a note of my own, synced exactly
 * like one by {@link NoteSync}. {@code groupIndex/{uid}/{id}} tells each member's devices there is
 * news. Sharing turns a note into a group note under the same id; leaving (or deleting it here)
 * leaves the group; being removed keeps the note here as my own.
 */
final class GroupNotes {

    static final int MAX = 20;

    private GroupNotes() {}

    /** Shares {@code noteId} with these people, making it a group note first if it isn't one. Blocking. */
    static void invite(int noteId, List<ChatSync.Contact> people) throws IOException {
        NoteStore store = NoteStore.get();
        NoteEntity note = store.byId(noteId);
        String uid = Account.uid();
        if (note == null || uid == null) throw new IOException("Sign in first");
        if (NoteData.AUDIO.equals(note.getType())) throw new IOException("Audio notes can't be shared");
        if (note.isReceived()) throw new IOException("Only the person who wrote it can share it");
        if (note.getSentTo() != null) throw new IOException("This note is sent to people: stop sending it first");
        String myName = Firebase.get("users/" + uid + "/profile/username", null) instanceof String s ? s : null;
        if (myName == null) throw new IOException("Choose a username in your account first");

        if (note.getGroupId() == null) {
            create(note, uid, myName, people);
            return;
        }
        String gid = note.getGroupId();
        JSONObject members = new JSONObject();
        if (Firebase.get("groupNotes/" + gid + "/members", null) instanceof JSONObject online) {
            for (String m : online.keySet()) if (online.opt(m) instanceof String name) members.put(m, name);
        }
        JSONObject update = new JSONObject();
        for (ChatSync.Contact p : people) {
            if (members.has(p.uid)) continue;
            members.put(p.uid, p.name);
            update.put("groupNotes/" + gid + "/members/" + p.uid, p.name);
            update.put("groupIndex/" + p.uid + "/" + gid, new JSONObject().put("at", Firebase.now()));
        }
        if (members.length() > MAX) throw new IOException("A group note has at most " + MAX + " people");
        if (update.isEmpty()) return;
        update.put("groupNotes/" + gid + "/size", members.length());
        Firebase.patch("", update);
        store.setGroupMembers(note.getId(), members.toString());
    }

    /** The first share: the note moves from my own notes to its group, under the same id. */
    private static void create(NoteEntity note, String uid, String myName, List<ChatSync.Contact> people) throws IOException {
        NoteStore store = NoteStore.get();
        String gid = note.getUuid();
        JSONObject members = new JSONObject().put(uid, myName);
        for (ChatSync.Contact p : people) members.put(p.uid, p.name);
        if (members.length() > MAX) throw new IOException("A group note has at most " + MAX + " people");

        NoteEntity fresh = store.byId(note.getId());
        fresh.setRev(0);
        fresh.setMedia(null); // Its files are re-shared the group's way on the next sync.
        JSONObject update = new JSONObject()
                .put("groupNotes/" + gid + "/owner", uid)
                .put("groupNotes/" + gid + "/size", members.length())
                .put("groupNotes/" + gid + "/note", NoteSync.toRemote(fresh));
        for (String m : members.keySet()) {
            update.put("groupNotes/" + gid + "/members/" + m, members.getString(m));
            update.put("groupIndex/" + m + "/" + gid, new JSONObject().put("at", Firebase.now()));
        }
        Firebase.patch("", update);
        boolean hasFiles = note.getMedia() != null;
        store.makeGroup(note.getId(), gid, members.toString(), 1, hasFiles);
        retirePersonal(uid, note);
        NoteSync.schedule();
    }

    /** My other devices drop their own copy: the group one replaces it. */
    private static void retirePersonal(String uid, NoteEntity note) {
        if (note.getRev() == 0) return; // Never reached my own notes online.
        String path = "users/" + uid + "/notes/" + note.getUuid();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Firebase.Tagged now = Firebase.getTagged(path);
                if (!(now.value() instanceof JSONObject current) || !current.has("rev")) return;
                JSONObject tombstone = new JSONObject().put("rev", current.optLong("rev") + 1)
                        .put("updatedAt", Firebase.now()).put("deleted", true);
                if (Firebase.putIf(path, tombstone, now.etag()) == null) return;
            }
        } catch (IOException e) {
            System.getLogger("GroupNotes").log(System.Logger.Level.WARNING, "Couldn't retire the personal copy", e);
        }
    }

    /** The owner removes someone; their copy stays theirs. Blocking. */
    static void remove(NoteEntity note, String member) throws IOException {
        String gid = note.getGroupId();
        if (gid == null) return;
        JSONObject members = NoteSync.json(note.getGroupMembers());
        members.remove(member);
        Firebase.patch("", new JSONObject()
                .put("groupNotes/" + gid + "/members/" + member, JSONObject.NULL)
                .put("groupNotes/" + gid + "/size", members.length())
                .put("groupIndex/" + member + "/" + gid, JSONObject.NULL));
        NoteStore.get().setGroupMembers(note.getId(), members.toString());
    }

    /** I leave; I keep it as my own note, the others keep theirs. Blocking. */
    static void leave(NoteEntity note) throws IOException {
        String uid = Account.uid();
        if (uid == null || note.getGroupId() == null) return;
        NoteSync.leaveGroup(uid, note.getGroupId());
        NoteStore.get().leaveGroup(note.getId());
        NoteSync.schedule();
    }

    /** The group's owner, or null if it can't be read. Blocking. */
    static String owner(NoteEntity note) {
        if (note.getGroupId() == null) return null;
        try {
            return Firebase.get("groupNotes/" + note.getGroupId() + "/owner", null) instanceof String s ? s : null;
        } catch (IOException e) {
            return null;
        }
    }

    static int count(String json) {
        return NoteSync.json(json).length();
    }
}
