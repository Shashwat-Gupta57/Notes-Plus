package org.flexstudios.notes.plus;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.ServerValue;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Notes sent to people: not a group note, just my note, which they can read.
 *
 * <p>Online a sent note is {@code sentNotes/{uuid}}: {@code from}, {@code fromName}, {@code to}
 * {uid: username}, and {@code note}, the same shape as my own notes. Only I write it; each of my
 * edits is published there after it syncs, so they always see the current version. Its photos and
 * recordings are encrypted and shared by link, like a group note's. {@code inbox/{uid}/{uuid}}
 * tells a recipient's phones it's there; {@code users/{me}/sent/{uuid}} tells my other phones I
 * sent it, so their edits are published too.
 *
 * <p>On their phones it shows among their notes and under "Shared with me", with my @username,
 * read-only ({@link NoteEntity#getSharedFrom}). They can save a copy of their own, or remove it.
 */
final class SentNotes {

    private static final String TAG = "SentNotes";
    private static final long TIMEOUT_S = 30;
    static final int MAX = 50;

    private SentNotes() {}

    // ================================================================ sending

    /** Sends {@code noteId} to these people (or to more people). Blocking. */
    static void send(Context context, int noteId, List<ChatSync.Contact> people) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        NoteEntity note = dao.getNoteById(noteId);
        String uid = Account.uid();
        if (note == null || uid == null) throw new IOException("Sign in first");
        if (note.isReceived()) throw new IOException("Only the person who wrote it can send it on");
        if (note.getGroupId() != null) throw new IOException("This is a group note: invite people to it instead");
        String myName = await(Account.root().child("users").child(uid).child("profile").child("username").get())
                .getValue(String.class);
        if (myName == null) throw new IOException("Choose a username in your account first");
        if (!uid.equals(note.getAccount())) {
            // A note from before signing in: it joins the account first, so it has a home online.
            note.setAccount(uid);
            note.setDirty(true);
            dao.update(note);
        }

        String sid = note.getUuid();
        JSONObject to = json(note.getSentTo());
        Map<String, Object> update = new HashMap<>();
        boolean first = to.length() == 0;
        for (ChatSync.Contact p : people) {
            if (p.uid.equals(uid) || to.has(p.uid)) continue;
            to.put(p.uid, p.name);
            update.put("sentNotes/" + sid + "/to/" + p.uid, p.name);
            update.put("inbox/" + p.uid + "/" + sid, inboxEntry(uid));
        }
        if (to.length() > MAX) throw new IOException("A note can be sent to at most " + MAX + " people");
        if (update.isEmpty()) return;
        if (first) {
            NoteEntity copy = dao.getNoteById(noteId);
            copy.setSentMedia(prepareFiles(context, copy));
            update.put("sentNotes/" + sid + "/from", uid);
            update.put("sentNotes/" + sid + "/fromName", myName);
            update.put("sentNotes/" + sid + "/note", sentValue(context, copy, 0));
            update.put("users/" + uid + "/sent/" + sid, true);
            await(Account.root().updateChildren(update));
            dao.setSentMedia(noteId, copy.getSentMedia());
        } else {
            await(Account.root().updateChildren(update));
        }
        dao.setSentTo(noteId, to.toString());
        NoteSync.schedule(context);
    }

    /** I stop sending it to {@code member}; with nobody left, it comes down altogether. Blocking. */
    static void stop(Context context, NoteEntity note, String member) throws Exception {
        String uid = Account.uid();
        if (uid == null) return;
        JSONObject to = json(note.getSentTo());
        to.remove(member);
        if (to.length() == 0) {
            takeDown(context, uid, note);
            return;
        }
        Map<String, Object> update = new HashMap<>();
        update.put("sentNotes/" + note.getUuid() + "/to/" + member, null);
        update.put("inbox/" + member + "/" + note.getUuid(), null);
        await(Account.root().updateChildren(update));
        AppDatabase.getInstance(context).noteDao().setSentTo(note.getId(), to.toString());
    }

    /** Nobody gets it any more (or I deleted it): gone online, files too. Blocking. */
    static void takeDown(Context context, String uid, NoteEntity note) throws Exception {
        String sid = note.getUuid();
        JSONObject to = json(note.getSentTo());
        Map<String, Object> update = new HashMap<>();
        for (Iterator<String> it = to.keys(); it.hasNext(); ) update.put("inbox/" + it.next() + "/" + sid, null);
        update.put("users/" + uid + "/sent/" + sid, null);
        try {
            if (!update.isEmpty()) await(Account.root().updateChildren(update));
            await(Account.root().child("sentNotes").child(sid).removeValue());
        } catch (java.util.concurrent.ExecutionException e) {
            if (!ChatSync.refused(e)) throw e; // Already gone.
        }
        DriveStore drive = Account.drive(context);
        JSONObject files = json(note.getSentMedia());
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            JSONObject e = files.optJSONObject(it.next());
            if (drive == null || e == null || e.optString("driveId").isEmpty()) continue;
            try {
                drive.deleteFile(e.optString("driveId"));
            } catch (IOException ex) {
                Log.w(TAG, "Couldn't remove a shared file", ex);
            }
        }
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        if (dao.getNoteById(note.getId()) != null) {
            dao.setSentTo(note.getId(), null);
            dao.setSentMedia(note.getId(), null);
        }
    }

    /**
     * After my edit of a sent note synced: the people I sent it to get this version. Blocking;
     * called by {@link NoteSync} with the sync lock held.
     */
    static void publish(Context context, String uid, NoteEntity note, @Nullable DriveStore drive) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        if (note.isDeleted()) {
            takeDown(context, uid, note);
            return;
        }
        NoteEntity fresh = dao.getNoteById(note.getId());
        if (fresh == null || fresh.getSentTo() == null) return;
        if (drive != null) {
            fresh.setSentMedia(prepareFiles(context, fresh));
            dao.setSentMedia(fresh.getId(), fresh.getSentMedia());
        }
        String sid = fresh.getUuid();
        Long rev = await(Account.root().child("sentNotes").child(sid).child("note").child("rev").get()).getValue(Long.class);
        Map<String, Object> update = new HashMap<>();
        update.put("sentNotes/" + sid + "/note", sentValue(context, fresh, rev == null ? 0 : rev));
        JSONObject to = json(fresh.getSentTo());
        for (Iterator<String> it = to.keys(); it.hasNext(); ) update.put("inbox/" + it.next() + "/" + sid, inboxEntry(uid));
        try {
            await(Account.root().updateChildren(update));
        } catch (java.util.concurrent.ExecutionException e) {
            // Someone else's newer version, or a race between my phones: the next sync retries.
            if (!ChatSync.refused(e)) throw e;
            Log.w(TAG, "Sent copy not updated this time", e);
        }
    }

    /** My note, as the people I sent it to see it: its files are the shared-by-link ones. */
    private static Map<String, Object> sentValue(Context context, NoteEntity note, long currentRev) {
        NoteEntity view = new NoteEntity(note.getTitle(), note.getContent(), note.getTimestamp());
        view.setId(note.getId());
        view.setType(note.getType());
        view.setData(note.getData());
        view.setColor(note.getColor());
        view.setPinned(false);
        view.setUuid(note.getUuid());
        view.setRev(currentRev);
        view.setMedia(note.getSentMedia());
        return NoteSync.toRemote(context, view);
    }

    /**
     * The files the note uses, encrypted with their own keys and shared by link (the group-note
     * way), so recipients without my Drive can open them. Uploads only what's new. Blocking.
     */
    private static String prepareFiles(Context context, NoteEntity note) throws Exception {
        JSONObject sent = json(note.getSentMedia());
        File dir = NoteData.dir(context, note.getId());
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(".part")
                && !f.getName().equals(SketchActivity.STROKES_FILE) && !f.getName().equals(SketchActivity.PREVIEW_FILE));
        Set<String> used = new HashSet<>();
        DriveStore drive = null;
        if (files != null) {
            for (File f : files) {
                if (!NoteSync.uses(note, f.getName())) continue;
                used.add(f.getName());
                JSONObject have = sent.optJSONObject(f.getName());
                if (have != null && have.optLong("size") == f.length()) continue;
                if (drive == null) drive = Account.drive(context);
                if (drive == null) break; // Text goes now; the files once Drive is connected.
                String tag = NoteSync.groupTag(note.getUuid(), f.getName());
                ChatMedia.Meta meta = ChatMedia.prepare(context, Uri.fromFile(f), tag);
                meta.id = ChatMedia.upload(context, drive, tag);
                sent.put(f.getName(), new JSONObject().put("driveId", meta.id).put("key", meta.key)
                        .put("size", f.length()).put("mime", NoteSync.mimeOf(f.getName())));
            }
        }
        for (Iterator<String> it = sent.keys(); it.hasNext(); ) if (!used.contains(it.next())) it.remove();
        return sent.length() == 0 ? null : sent.toString();
    }

    // ============================================================== receiving

    /**
     * Brings in every note sent to me, keeps my sent notes' recipients known on this phone, and
     * drops the ones no longer sent to me. Blocking; called by {@link NoteSync#run}.
     */
    static void pull(Context context, String uid) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        DataSnapshot inbox = await(Account.root().child("inbox").child(uid).get());
        Set<String> listed = new HashSet<>();
        for (DataSnapshot entry : inbox.getChildren()) {
            String sid = entry.getKey();
            if (sid == null) continue;
            DataSnapshot sent;
            try {
                sent = await(Account.root().child("sentNotes").child(sid).get());
            } catch (java.util.concurrent.ExecutionException e) {
                if (!ChatSync.refused(e)) throw e;
                // Taken down, or no longer sent to me: tidy my inbox.
                Account.root().child("inbox").child(uid).child(sid).removeValue();
                continue;
            }
            if (!sent.child("to").hasChild(uid)) continue;
            NoteSync.Remote remote = NoteSync.Remote.of(sent.child("note"));
            if (remote == null) continue;
            remote.uuid = sid;
            String from = sent.child("fromName").getValue(String.class);
            listed.add(sid);
            NoteEntity local = dao.getByUuid(sid);
            if (local != null && !local.isReceived()) continue; // My own note: never replaced by a copy.
            if (remote.deleted) {
                if (local != null) {
                    if (NoteSync.OPEN.contains(local.getId())) {
                        NoteActivity.forceCloseIfOpen(local.getId(), "Access to this shared note was withdrawn");
                    }
                    remove(context, local);
                }
                continue;
            }
            if (local == null) {
                NoteEntity n = new NoteEntity("", "", remote.edited);
                n.setUuid(sid);
                n.setAccount(uid);
                remote.copyInto(n, context);
                n.setPinned(false);
                n.setSharedFrom(from == null ? "someone" : from);
                n.setId((int) dao.insert(n));
                NoteSync.writeSketch(context, n.getId(), remote.sketch);
            } else if (remote.rev > local.getRev() || !java.util.Objects.equals(from, local.getSharedFrom())) {
                boolean pinned = local.isPinned();
                int color = local.getColor();
                remote.copyInto(local, context);
                local.setPinned(pinned); // Pin and colour are mine to choose, on this phone.
                local.setColor(color);
                if (from != null) local.setSharedFrom(from);
                dao.update(local);
                NoteSync.writeSketch(context, local.getId(), remote.sketch);
            }
        }
        for (NoteEntity n : dao.receivedNotes(uid)) {
            if (!listed.contains(n.getUuid())) {
                if (NoteSync.OPEN.contains(n.getId())) {
                    NoteActivity.forceCloseIfOpen(n.getId(), "Access to this shared note was withdrawn");
                }
                remove(context, n);
            }
        }
        refreshMine(context, uid);
    }

    /** My notes sent from my other phones: who they went to, and their shared files. */
    private static void refreshMine(Context context, String uid) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        DataSnapshot sent = await(Account.root().child("users").child(uid).child("sent").get());
        Set<String> mine = new HashSet<>();
        for (DataSnapshot s : sent.getChildren()) {
            String sid = s.getKey();
            if (sid == null) continue;
            mine.add(sid);
            NoteEntity local = dao.getByUuid(sid);
            if (local == null || local.isReceived()) continue;
            DataSnapshot online;
            try {
                online = await(Account.root().child("sentNotes").child(sid).get());
            } catch (java.util.concurrent.ExecutionException e) {
                if (!ChatSync.refused(e)) throw e;
                continue;
            }
            JSONObject to = new JSONObject();
            for (DataSnapshot m : online.child("to").getChildren()) {
                String name = m.getValue(String.class);
                if (m.getKey() != null && name != null) to.put(m.getKey(), name);
            }
            if (to.length() == 0) {
                // Everyone removed it themselves: nobody left to read it, so it comes down.
                takeDown(context, uid, local);
                continue;
            }
            String toJson = to.toString();
            if (!java.util.Objects.equals(toJson, local.getSentTo())) dao.setSentTo(local.getId(), toJson);
            NoteSync.Remote remote = NoteSync.Remote.of(online.child("note"));
            if (remote != null && local.getSentMedia() == null && remote.media != null) {
                dao.setSentMedia(local.getId(), remote.media);
            }
        }
        // Taken down from another phone.
        for (NoteEntity n : dao.sentNotes(uid)) {
            if (!mine.contains(n.getUuid())) {
                dao.setSentTo(n.getId(), null);
                dao.setSentMedia(n.getId(), null);
            }
        }
    }

    /** A received note leaves this phone. */
    private static void remove(Context context, NoteEntity note) {
        NoteData.deleteFiles(context, note.getId());
        AppDatabase.getInstance(context).noteDao().purge(note.getId());
    }

    /** I don't want it any more: off my list, and not sent to me again. Blocking. */
    static void removeReceived(Context context, NoteEntity note) throws Exception {
        String uid = Account.uid();
        if (uid != null) {
            Map<String, Object> update = new HashMap<>();
            update.put("sentNotes/" + note.getUuid() + "/to/" + uid, null);
            update.put("inbox/" + uid + "/" + note.getUuid(), null);
            try {
                await(Account.root().updateChildren(update));
            } catch (java.util.concurrent.ExecutionException e) {
                if (!ChatSync.refused(e)) throw e; // Already taken down.
            }
        }
        remove(context, note);
    }

    /** Their note becomes one of mine, mine to edit: files and all. Blocking. Returns its id. */
    static int saveCopy(Context context, NoteEntity received) {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        NoteEntity copy = new NoteEntity(received.getTitle(), received.getContent(), System.currentTimeMillis());
        copy.setType(received.getType());
        copy.setData(received.getData());
        copy.setColor(received.getColor());
        copy.setAccount(Account.uid());
        int id = (int) dao.insert(copy);
        File from = NoteData.dir(context, received.getId()), to = NoteData.dir(context, id);
        File[] files = from.listFiles(File::isFile);
        if (files != null) {
            for (File f : files) {
                try (java.io.InputStream in = new java.io.FileInputStream(f);
                     java.io.OutputStream out = new java.io.FileOutputStream(new File(to, f.getName()))) {
                    VaultCrypto.copy(in, out);
                } catch (IOException e) {
                    Log.w(TAG, "Couldn't copy " + f.getName(), e);
                }
            }
        }
        NoteSync.schedule(context);
        return id;
    }

    // ================================================================ screens

    /** "Send to people": who has it, add people, stop sending it to someone. */
    static void showSheet(Activity activity, int noteId, Runnable beforeSending) {
        if (Account.uid() == null) {
            Toast.makeText(activity, "Sign in to your account to send notes", Toast.LENGTH_LONG).show();
            return;
        }
        beforeSending.run();
        AppDatabase.IO.execute(() -> {
            NoteEntity note = noteId > 0 ? AppDatabase.getInstance(activity).noteDao().getNoteById(noteId) : null;
            activity.runOnUiThread(() -> sheet(activity, noteId, note));
        });
    }

    private static void sheet(Activity activity, int noteId, @Nullable NoteEntity note) {
        if (note == null) {
            Toast.makeText(activity, "Write something first, then send it", Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject to = json(note.getSentTo());
        List<String> uids = new ArrayList<>();
        for (Iterator<String> it = to.keys(); it.hasNext(); ) uids.add(it.next());
        Collections.sort(uids, (a, b) -> to.optString(a).compareToIgnoreCase(to.optString(b)));
        String[] rows = new String[uids.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = "@" + to.optString(uids.get(i)) + "  ·  tap to stop";
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(uids.isEmpty() ? "Send to people" : "Sent to " + uids.size())
                .setNegativeButton("Close", null)
                .setPositiveButton("Send to…", (d, w) -> askPeople(activity, noteId));
        if (uids.isEmpty()) {
            dialog.setMessage("They'll find it under \"Shared with me\" in their notes, with your @username. "
                    + "They can read it but not change it, and they always see your latest version.");
        } else {
            dialog.setItems(rows, (d, which) -> confirmStop(activity, note, uids.get(which), to.optString(uids.get(which))));
        }
        dialog.show();
    }

    private static void askPeople(Activity activity, int noteId) {
        Prompts.text(activity, "Send to", "Usernames, separated by commas.", "Usernames", null,
                Prompts.TEXT, "Send", raw -> ChatSync.resolvePeople(raw, people -> AppDatabase.IO.execute(() -> {
                    String error = null;
                    try {
                        send(activity, noteId, people);
                    } catch (Exception e) {
                        error = e.getMessage() == null ? "Couldn't send it" : e.getMessage();
                    }
                    String message = error;
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            message != null ? message : "Sent. It's in their notes, under Shared with me.",
                            Toast.LENGTH_LONG).show());
                }), error -> Toast.makeText(activity, error, Toast.LENGTH_LONG).show()), null);
    }

    private static void confirmStop(Activity activity, NoteEntity note, String uid, String name) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Stop sending to @" + name + "?")
                .setMessage("It disappears from their notes. They keep any copy they saved themselves.")
                .setPositiveButton("Stop", (d, w) -> AppDatabase.IO.execute(() -> {
                    try {
                        stop(activity, AppDatabase.getInstance(activity).noteDao().getNoteById(note.getId()), uid);
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, "Couldn't change that right now",
                                Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "Remove" on a received note: with what it means. {@code after} runs once it's gone. */
    static void confirmRemove(Activity activity, NoteEntity note, Runnable after) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Remove this note?")
                .setMessage("@" + note.getSharedFrom() + " keeps it; you won't get it any more. Save a copy first if you want one.")
                .setPositiveButton("Remove", (d, w) -> AppDatabase.IO.execute(() -> {
                    try {
                        removeReceived(activity, note);
                        activity.runOnUiThread(after);
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, "Couldn't remove it right now",
                                Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================================================ helpers

    static int count(@Nullable String json) {
        return json(json).length();
    }

    private static Map<String, Object> inboxEntry(String uid) {
        Map<String, Object> m = new HashMap<>();
        m.put("at", ServerValue.TIMESTAMP);
        m.put("from", uid);
        return m;
    }

    private static JSONObject json(@Nullable String s) {
        try {
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static <T> T await(com.google.android.gms.tasks.Task<T> task) throws Exception {
        return Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS);
    }
}
