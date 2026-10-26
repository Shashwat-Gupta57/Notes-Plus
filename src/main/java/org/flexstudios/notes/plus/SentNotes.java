package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Notes sent to people: not a group note, just my note, which they can read. The phone's
 * SentNotes, over REST, writing exactly what it writes.
 *
 * <p>Online a sent note is {@code sentNotes/{uuid}}: {@code from}, {@code fromName}, {@code to}
 * {uid: username}, and {@code note}, the same shape as my own notes. Only I write it; each edit is
 * published there after it syncs. Its files are encrypted and shared by link, like a group
 * note's. {@code inbox/{uid}/{uuid}} tells a recipient's devices it's there;
 * {@code users/{me}/sent/{uuid}} tells my other devices I sent it.
 *
 * <p>Recipients see it among their notes and under "Shared with me", with my @username,
 * read-only ({@link NoteEntity#getSharedFrom}); they can save a copy of their own, or remove it.
 */
final class SentNotes {

    private static final System.Logger LOG = System.getLogger("SentNotes");
    static final int MAX = 50;

    private SentNotes() {}

    // ================================================================ sending

    /** Sends {@code noteId} to these people (or to more people). Blocking. */
    static void send(int noteId, List<ChatSync.Contact> people) throws IOException {
        NoteStore store = NoteStore.get();
        NoteEntity note = store.byId(noteId);
        String uid = Account.uid();
        if (note == null || uid == null) throw new IOException("Sign in first");
        if (note.isReceived()) throw new IOException("Only the person who wrote it can send it on");
        if (note.getGroupId() != null) throw new IOException("This is a group note: invite people to it instead");
        String myName = Firebase.get("users/" + uid + "/profile/username", null) instanceof String s ? s : null;
        if (myName == null) throw new IOException("Choose a username in your account first");
        if (!uid.equals(note.getAccount())) {
            // A note from before signing in: it joins the account first, so it has a home online.
            note.setAccount(uid);
            note.setDirty(true);
            store.update(note);
        }

        String sid = note.getUuid();
        JSONObject to = NoteSync.json(note.getSentTo());
        boolean first = to.isEmpty();
        JSONObject update = new JSONObject();
        for (ChatSync.Contact p : people) {
            if (p.uid.equals(uid) || to.has(p.uid)) continue;
            to.put(p.uid, p.name);
            update.put("sentNotes/" + sid + "/to/" + p.uid, p.name);
            update.put("inbox/" + p.uid + "/" + sid, inboxEntry(uid));
        }
        if (to.length() > MAX) throw new IOException("A note can be sent to at most " + MAX + " people");
        if (update.isEmpty()) return;
        if (first) {
            NoteEntity copy = store.byId(noteId);
            copy.setSentMedia(prepareFiles(copy, Account.drive()));
            update.put("sentNotes/" + sid + "/from", uid);
            update.put("sentNotes/" + sid + "/fromName", myName);
            update.put("sentNotes/" + sid + "/note", sentValue(copy, 0));
            update.put("users/" + uid + "/sent/" + sid, true);
            Firebase.patch("", update);
            store.setSentMedia(noteId, copy.getSentMedia());
        } else {
            Firebase.patch("", update);
        }
        store.setSentTo(noteId, to.toString());
        NoteSync.schedule();
    }

    /** I stop sending it to {@code member}; with nobody left, it comes down altogether. Blocking. */
    static void stop(NoteEntity note, String member) throws IOException {
        String uid = Account.uid();
        if (uid == null) return;
        JSONObject to = NoteSync.json(note.getSentTo());
        to.remove(member);
        if (to.isEmpty()) {
            takeDown(uid, note);
            return;
        }
        Firebase.patch("", new JSONObject()
                .put("sentNotes/" + note.getUuid() + "/to/" + member, JSONObject.NULL)
                .put("inbox/" + member + "/" + note.getUuid(), JSONObject.NULL));
        NoteStore.get().setSentTo(note.getId(), to.toString());
    }

    /** Nobody gets it any more (or I deleted it): gone online, files too. Blocking. */
    static void takeDown(String uid, NoteEntity note) throws IOException {
        String sid = note.getUuid();
        JSONObject update = new JSONObject();
        for (String m : NoteSync.json(note.getSentTo()).keySet()) update.put("inbox/" + m + "/" + sid, JSONObject.NULL);
        update.put("users/" + uid + "/sent/" + sid, JSONObject.NULL);
        try {
            Firebase.patch("", update);
            Firebase.put("sentNotes/" + sid, JSONObject.NULL);
        } catch (Firebase.Error e) {
            if (!e.refused()) throw e; // Already gone.
        }
        Drive drive = Account.drive();
        JSONObject files = NoteSync.json(note.getSentMedia());
        for (String name : files.keySet()) {
            JSONObject e = files.optJSONObject(name);
            if (drive == null || e == null || e.optString("driveId").isEmpty()) continue;
            try {
                drive.delete(e.optString("driveId"));
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Couldn't remove a shared file", ex);
            }
        }
        NoteStore store = NoteStore.get();
        if (store.byId(note.getId()) != null) {
            store.setSentTo(note.getId(), null);
            store.setSentMedia(note.getId(), null);
        }
    }

    /** After my edit of a sent note synced: the people I sent it to get this version. Blocking. */
    static void publish(String uid, NoteEntity note, Drive drive) throws IOException {
        if (note.isDeleted()) {
            takeDown(uid, note);
            return;
        }
        NoteStore store = NoteStore.get();
        NoteEntity fresh = store.byId(note.getId());
        if (fresh == null || fresh.getSentTo() == null) return;
        if (drive != null) {
            fresh.setSentMedia(prepareFiles(fresh, drive));
            store.setSentMedia(fresh.getId(), fresh.getSentMedia());
        }
        String sid = fresh.getUuid();
        long rev = Firebase.get("sentNotes/" + sid + "/note/rev", null) instanceof Number n ? n.longValue() : 0;
        JSONObject update = new JSONObject().put("sentNotes/" + sid + "/note", sentValue(fresh, rev));
        for (String m : NoteSync.json(fresh.getSentTo()).keySet()) update.put("inbox/" + m + "/" + sid, inboxEntry(uid));
        try {
            Firebase.patch("", update);
        } catch (Firebase.Error e) {
            // A race between my devices: the next sync retries.
            if (!e.refused()) throw e;
            LOG.log(System.Logger.Level.WARNING, "Sent copy not updated this time", e);
        }
    }

    /** My note, as the people I sent it to see it: its files are the shared-by-link ones. */
    private static JSONObject sentValue(NoteEntity note, long currentRev) {
        NoteEntity view = new NoteEntity(note.getTitle(), note.getContent(), note.getTimestamp());
        view.setId(note.getId());
        view.setType(note.getType());
        view.setData(note.getData());
        view.setColor(note.getColor());
        view.setPinned(false);
        view.setUuid(note.getUuid());
        view.setRev(currentRev);
        view.setMedia(note.getSentMedia());
        return NoteSync.toRemote(view);
    }

    /**
     * The files the note uses, encrypted with their own keys and shared by link (the group-note
     * way), so recipients without my Drive can open them. Uploads only what's new.
     */
    private static String prepareFiles(NoteEntity note, Drive drive) throws IOException {
        JSONObject sent = NoteSync.json(note.getSentMedia());
        Path dir = Storage.notesDir().resolve(String.valueOf(note.getId()));
        Set<String> used = new HashSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (name.endsWith(".part") || name.equals(SketchEditor.STROKES_FILE) || name.equals(SketchEditor.PREVIEW_FILE)
                            || !NoteSync.uses(note, name)) continue;
                    used.add(name);
                    JSONObject have = sent.optJSONObject(name);
                    if (have != null && have.optLong("size") == Files.size(f)) continue;
                    if (drive == null) continue; // Text goes now; the files once Drive is connected.
                    String tag = NoteSync.groupTag(note.getUuid(), name);
                    ChatMedia.Meta meta = ChatMedia.prepare(f, tag);
                    meta.id = ChatMedia.upload(drive, tag);
                    sent.put(name, new JSONObject().put("driveId", meta.id).put("key", meta.key)
                            .put("size", Files.size(f)).put("mime", NoteSync.mimeOf(name)));
                }
            }
        }
        sent.keySet().removeIf(name -> !used.contains(name));
        return sent.isEmpty() ? null : sent.toString();
    }

    // ============================================================== receiving

    /** Brings in every note sent to me, drops the ones no longer sent, and refreshes my own sent ones. Blocking. */
    static void pull(String uid) throws IOException {
        NoteStore store = NoteStore.get();
        JSONObject inbox = Firebase.getObject("inbox/" + uid);
        Set<String> listed = new HashSet<>();
        if (inbox != null) {
            for (String sid : inbox.keySet()) {
                JSONObject sent;
                try {
                    sent = Firebase.getObject("sentNotes/" + sid);
                } catch (Firebase.Error e) {
                    if (!e.refused()) throw e;
                    // Taken down, or no longer sent to me: tidy my inbox.
                    try {
                        Firebase.put("inbox/" + uid + "/" + sid, JSONObject.NULL);
                    } catch (IOException ignored) {
                        // Next time.
                    }
                    continue;
                }
                JSONObject to = sent == null ? null : sent.optJSONObject("to");
                if (to == null || !to.has(uid)) continue;
                NoteSync.Remote remote = NoteSync.Remote.of(sid, sent.optJSONObject("note"));
                if (remote == null) continue;
                String from = sent.optString("fromName", null);
                listed.add(sid);
                NoteEntity local = store.byUuid(sid);
                if (local != null && !local.isReceived()) continue; // My own note: never replaced by a copy.
                if (remote.deleted) {
                    if (local != null) remove(local);
                    continue;
                }
                if (local == null) {
                    NoteEntity n = new NoteEntity("", "", remote.edited);
                    n.setUuid(sid);
                    n.setAccount(uid);
                    remote.copyInto(n);
                    n.setPinned(false);
                    n.setSharedFrom(from == null ? "someone" : from);
                    n.setId(store.insert(n));
                    NoteSync.writeSketch(n.getId(), remote.sketch);
                } else if (remote.rev > local.getRev() || !Objects.equals(from, local.getSharedFrom())) {
                    boolean pinned = local.isPinned();
                    int color = local.getColor();
                    remote.copyInto(local);
                    local.setPinned(pinned); // Pin and colour are mine to choose, here.
                    local.setColor(color);
                    if (from != null) local.setSharedFrom(from);
                    store.update(local);
                    NoteSync.writeSketch(local.getId(), remote.sketch);
                }
            }
        }
        for (NoteEntity n : store.receivedNotes(uid)) {
            if (!listed.contains(n.getUuid())) {
                if (NoteSync.OPEN.contains(n.getId())) {
                    NotesApp.forceCloseNote(n.getId(), "Access to this note was withdrawn");
                }
                remove(n);
            }
        }
        refreshMine(uid);
    }

    /** My notes sent from my other devices: who they went to, and their shared files. */
    private static void refreshMine(String uid) throws IOException {
        NoteStore store = NoteStore.get();
        JSONObject sent = Firebase.getObject("users/" + uid + "/sent");
        Set<String> mine = sent == null ? Set.of() : sent.keySet();
        for (String sid : mine) {
            NoteEntity local = store.byUuid(sid);
            if (local == null || local.isReceived()) continue;
            JSONObject online;
            try {
                online = Firebase.getObject("sentNotes/" + sid);
            } catch (Firebase.Error e) {
                if (!e.refused()) throw e;
                continue;
            }
            JSONObject to = new JSONObject();
            JSONObject list = online == null ? null : online.optJSONObject("to");
            if (list != null) for (String m : list.keySet()) if (list.opt(m) instanceof String name) to.put(m, name);
            if (to.isEmpty()) {
                // Everyone removed it themselves: nobody left to read it, so it comes down.
                takeDown(uid, local);
                continue;
            }
            String toJson = to.toString();
            if (!Objects.equals(toJson, local.getSentTo())) store.setSentTo(local.getId(), toJson);
            NoteSync.Remote remote = online == null ? null : NoteSync.Remote.of(sid, online.optJSONObject("note"));
            if (remote != null && local.getSentMedia() == null && remote.media != null) store.setSentMedia(local.getId(), remote.media);
        }
        // Taken down from another device.
        for (NoteEntity n : store.sentNotes(uid)) {
            if (!mine.contains(n.getUuid())) {
                store.setSentTo(n.getId(), null);
                store.setSentMedia(n.getId(), null);
            }
        }
    }

    private static void remove(NoteEntity note) {
        NoteData.deleteFiles(note.getId());
        NoteStore.get().purge(note.getId());
    }

    /** I don't want it any more: off my list, and not sent to me again. Blocking. */
    static void removeReceived(NoteEntity note) throws IOException {
        String uid = Account.uid();
        if (uid != null) {
            try {
                Firebase.patch("", new JSONObject()
                        .put("sentNotes/" + note.getUuid() + "/to/" + uid, JSONObject.NULL)
                        .put("inbox/" + uid + "/" + note.getUuid(), JSONObject.NULL));
            } catch (Firebase.Error e) {
                if (!e.refused()) throw e; // Already taken down.
            }
        }
        remove(note);
    }

    /** Their note becomes one of mine, mine to edit: files and all. Blocking. Returns its id. */
    static int saveCopy(NoteEntity received) {
        NoteStore store = NoteStore.get();
        NoteEntity copy = new NoteEntity(received.getTitle(), received.getContent(), System.currentTimeMillis());
        copy.setType(received.getType());
        copy.setData(received.getData());
        copy.setColor(received.getColor());
        copy.setAccount(Account.uid());
        int id = store.insert(copy);
        Path from = Storage.notesDir().resolve(String.valueOf(received.getId())), to = NoteData.dir(id);
        if (Files.isDirectory(from)) {
            try (Stream<Path> files = Files.list(from)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(f, to.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Couldn't copy a received note's files", e);
            }
        }
        NoteSync.schedule();
        return id;
    }

    static int count(String json) {
        return NoteSync.json(json).length();
    }

    private static JSONObject inboxEntry(String uid) {
        return new JSONObject().put("at", Firebase.now()).put("from", uid);
    }
}
