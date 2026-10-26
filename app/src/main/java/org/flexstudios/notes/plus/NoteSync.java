package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the notes of the signed-in {@link Account} in step with the Realtime Database, and
 * their photos, videos and recordings with the user's own Google Drive.
 *
 * <p><b>Model.</b> {@code users/{uid}/notes/{uuid}} holds a note's text and structure plus
 * {@code rev}, which goes up by one with every write (the rules enforce it). A local row
 * remembers the {@code rev} it is based on. Attachments live in Drive under
 * {@code Notes-plus/Attachments/{uuid}/}; the database only holds their manifest.
 *
 * <p><b>Rules.</b>
 * <ul>
 *   <li>Push is a transaction that only succeeds if the server is still at our {@code rev}.
 *       Otherwise both sides changed: the server's version replaces the note and ours is
 *       kept beside it as a "conflict" copy. No edit is ever lost.</li>
 *   <li>Pull only overwrites notes with no unpushed edits; a dirty note is left for the push
 *       to resolve.</li>
 *   <li>A note open in an editor ({@link #OPEN}) is neither pushed nor overwritten. Sync
 *       waits until the user has finished with it.</li>
 *   <li>Deletes are tombstones ({@code deleted: true}), kept 30 days so every phone hears.</li>
 * </ul>
 */
final class NoteSync {

    private static final String TAG = "NoteSync";
    static final String UNIQUE = "np-notes-sync";
    private static final String UNIQUE_PERIODIC = "np-notes-pull";
    private static final String PREF_LAST_PULL = "notes_last_pull_";
    private static final String PREF_LAST_SYNC = "notes_last_sync_";
    private static final String PREF_LAST_SWEEP = "notes_last_sweep_";
    static final String PREF_DRIVE_ERROR = "notes_drive_error";
    private static final String CHANNEL = "notes_sync";
    private static final int NOTIFICATION_ID = 7401;
    private static final String ATTACHMENTS = "Attachments";
    private static final long TOMBSTONE_TTL_MS = TimeUnit.DAYS.toMillis(30);
    private static final long TIMEOUT_S = 60;

    /** Ids of notes open in an editor right now. */
    static final Set<Integer> OPEN = ConcurrentHashMap.newKeySet();
    /** Newer versions of open notes, held until their editor says it has changed nothing. */
    private static final Map<Integer, Remote> NEWER = new ConcurrentHashMap<>();
    /** Open editors to tell when a newer version of their note arrives (run on the main thread). */
    static final Map<Integer, Runnable> WATCHERS = new ConcurrentHashMap<>();

    /** One sync at a time, worker or live listener. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    private NoteSync() {}

    /** True while a sync pass runs: the notes grid shows "Syncing…" and warns before editing. */
    static final androidx.lifecycle.MutableLiveData<Boolean> SYNCING = new androidx.lifecycle.MutableLiveData<>(false);

    static String newUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ============================================================ scheduling

    /** Queues a sync once there is a network. Cheap to call; does nothing when signed out. */
    static void schedule(Context context) {
        if (Account.uid() == null) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Job.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // Linear and short: a later change is appended behind a failed run's retry.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build();
        // Appended, so a change made while a run is in progress is picked up by the next one.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    /** A safety net for phones that are never opened: pull every six hours. */
    static void schedulePeriodic(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(Job.class, 6, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE);
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC);
    }

    public static final class Job extends Worker {
        public Job(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        private boolean foreground;

        @NonNull
        @Override
        public Result doWork() {
            try {
                run(getApplicationContext(), this::goForeground);
                return Result.success();
            } catch (Exception e) {
                Log.w(TAG, "Sync failed; will retry", e);
                return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
            }
        }

        /**
         * Only once a file actually has to move: a big video can outlast a background job's
         * ten minutes, and a foreground job keeps going when the app is closed.
         */
        private void goForeground() {
            if (foreground) return;
            foreground = true;
            Context context = getApplicationContext();
            android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(new android.app.NotificationChannel(CHANNEL,
                        "Notes sync", android.app.NotificationManager.IMPORTANCE_LOW));
            }
            android.app.Notification notification = new androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("Syncing notes")
                    .setContentText("Photos and recordings")
                    .setProgress(0, 0, true)
                    .setOngoing(true)
                    .setSilent(true)
                    .build();
            try {
                setForegroundAsync(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? new androidx.work.ForegroundInfo(NOTIFICATION_ID, notification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                        : new androidx.work.ForegroundInfo(NOTIFICATION_ID, notification));
            } catch (Exception e) {
                Log.i(TAG, "Running without foreground promotion", e);
            }
        }
    }

    // ======================================================== local deletes

    /**
     * The tail of a delete, once Undo has expired: the files go, and the row too unless the
     * account still has to hear about it (then the next push sends the tombstone and purges).
     */
    static void forget(Context context, NoteEntity note) {
        Context app = context.getApplicationContext();
        AppDatabase.IO.execute(() -> {
            NoteData.deleteFiles(app, note.getId());
            NoteDao dao = AppDatabase.getInstance(app).noteDao();
            NoteEntity row = dao.getNoteById(note.getId());
            if (row != null && row.isDeleted() && (row.getAccount() == null || row.getRev() == 0)) {
                dao.purge(row.getId());
            } else {
                schedule(app);
            }
        });
    }

    /** Signing out: the account's notes become this phone's own, or leave it. Blocking. */
    static void leave(Context context, String uid, boolean keepNotes) {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        for (NoteEntity n : dao.receivedNotes(uid)) {
            NoteData.deleteFiles(context, n.getId());
            dao.purge(n.getId());
        }
        if (keepNotes) {
            dao.release(uid);
        } else {
            for (NoteEntity n : dao.notesOf(uid)) {
                NoteData.deleteFiles(context, n.getId());
                dao.purge(n.getId());
            }
        }
        prefs(context).edit().remove(PREF_LAST_PULL + uid).remove(PREF_LAST_SYNC + uid)
                .remove(PREF_LAST_SWEEP + uid).remove(PREF_DRIVE_ERROR).apply();
    }

    /** "Synced 2 min ago", "3 waiting to sync", for the account screen. 0 if never. */
    static long lastSynced(Context context, String uid) {
        return prefs(context).getLong(PREF_LAST_SYNC + uid, 0);
    }

    @Nullable
    static String driveError(Context context) {
        return prefs(context).getString(PREF_DRIVE_ERROR, null);
    }

    // ================================================================= run

    /** One full pass: pull, upload new attachments, push, download missing ones. Blocking. */
    static void run(Context context, Runnable onTransfer) throws Exception {
        String uid = Account.uid();
        if (uid == null) return;
        LOCK.lock();
        SYNCING.postValue(true);
        try {
            NoteDao dao = AppDatabase.getInstance(context).noteDao();
            for (NoteEntity t : dao.localTombstones()) {
                if (!OPEN.contains(t.getId())) dao.purge(t.getId());
            }
            DatabaseReference notes = Account.notesOf(uid);
            pull(context, uid, notes);
            try {
                pullGroups(context, uid);
            } catch (Exception e) {
                Log.w(TAG, "Group notes not fetched this time", e);
            }
            try {
                SentNotes.pull(context, uid);
            } catch (Exception e) {
                Log.w(TAG, "Sent notes not fetched this time", e);
            }
            DriveStore drive = Account.drive(context);
            if (drive != null) {
                try {
                    uploadAttachments(context, uid, drive, onTransfer);
                } catch (IOException e) {
                    driveFailed(context, e);
                    drive = null;
                }
            }
            try {
                ShareLinks.refresh(uid); // Which notes have links: they are published again as they go up.
            } catch (Exception e) {
                Log.w(TAG, "Links not checked this time", e);
            }
            push(context, uid, notes, drive);
            try {
                // Group notes' files come from their public links: no Drive needed for those.
                downloadAttachments(context, uid, drive, onTransfer);
            } catch (IOException e) {
                driveFailed(context, e);
            }
            sweepTombstones(context, uid, notes);
            prefs(context).edit().putLong(PREF_LAST_SYNC + uid, System.currentTimeMillis()).apply();
        } finally {
            SYNCING.postValue(false);
            LOCK.unlock();
        }
    }

    // ================================================================ pull

    private static void pull(Context context, String uid, DatabaseReference notes) throws Exception {
        SharedPreferences prefs = prefs(context);
        long since = prefs.getLong(PREF_LAST_PULL + uid, 0);
        DataSnapshot changed = Tasks.await(notes.orderByChild("updatedAt").startAt(since).get(),
                TIMEOUT_S, TimeUnit.SECONDS);
        long newest = since, waiting = Long.MAX_VALUE;
        for (DataSnapshot child : changed.getChildren()) {
            Remote remote = Remote.of(child);
            if (remote == null) continue;
            if (!apply(context, uid, remote)) waiting = Math.min(waiting, remote.updatedAt);
            newest = Math.max(newest, remote.updatedAt);
        }
        // A change held back for an open note is fetched again next time.
        prefs.edit().putLong(PREF_LAST_PULL + uid, Math.min(newest, waiting)).apply();
    }

    /**
     * Every group note I'm a member of ({@code groupIndex/{uid}}), brought in like my own notes.
     * One I was removed from (or that is gone) stays here as my own note.
     */
    private static void pullGroups(Context context, String uid) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        DataSnapshot index = Tasks.await(Account.root().child("groupIndex").child(uid).get(), TIMEOUT_S, TimeUnit.SECONDS);
        Set<String> listed = new HashSet<>();
        for (DataSnapshot entry : index.getChildren()) {
            String gid = entry.getKey();
            if (gid == null) continue;
            DataSnapshot group;
            try {
                group = Tasks.await(Account.root().child("groupNotes").child(gid).get(), TIMEOUT_S, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (!ChatSync.refused(e)) throw e;
                continue; // No longer a member: handled below, as not listed.
            }
            if (!group.child("members").hasChild(uid)) continue;
            listed.add(gid);
            String members = GroupNotes.membersJson(group.child("members"));
            Remote remote = Remote.of(group.child("note"));
            if (remote != null) {
                remote.uuid = gid;
                apply(context, uid, remote, gid, members);
            }
            NoteEntity local = dao.getByUuid(gid);
            if (local != null && !java.util.Objects.equals(members, local.getGroupMembers())) {
                dao.setGroupMembers(local.getId(), members);
            }
        }
        for (NoteEntity n : dao.groupNotes(uid)) {
            if (!listed.contains(n.getGroupId()) && !OPEN.contains(n.getId())) dao.leaveGroup(n.getId());
        }
    }

    static final int SKIP = 0, WAIT = 1, INSERT = 2, OVERWRITE = 3, REMOVE = 4;

    /** What a pulled version does to the local copy. Pure, so it is tested on its own. */
    @VisibleForTesting
    static int decide(boolean exists, boolean open, boolean dirty, long localRev,
                      long remoteRev, boolean remoteDeleted) {
        if (!exists) return remoteDeleted ? SKIP : INSERT;
        if (open) return WAIT;
        if (dirty) return SKIP; // The push sees the newer rev and resolves it as a conflict.
        if (remoteRev <= localRev) return SKIP;
        return remoteDeleted ? REMOVE : OVERWRITE;
    }

    /** Applies one pulled note. False when it had to wait because the note is open. */
    private static boolean apply(Context context, String uid, Remote remote) {
        return apply(context, uid, remote, null, null);
    }

    /** {@code groupId}: it came from a group note (with these members), not my own notes. */
    private static boolean apply(Context context, String uid, Remote remote, @Nullable String groupId,
                                 @Nullable String members) {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        NoteEntity local = dao.getByUuid(remote.uuid);
        // A note I shared became a group note: its old place among my own notes no longer counts.
        if (groupId == null && local != null && local.getGroupId() != null) return true;
        if (local != null && !uid.equals(local.getAccount())) {
            // This phone's own copy of a note it once shared with the account: it keeps its
            // text, under a new identity, and the account's version comes in beside it.
            if (sameContent(context, local, remote)) {
                local.setAccount(uid);
                local.setRev(remote.rev);
                local.setDirty(false);
                local.setMedia(remote.media);
                dao.update(local);
                return true;
            }
            local.setUuid(newUuid());
            dao.update(local);
            local = null;
        }
        int action = decide(local != null, local != null && OPEN.contains(local.getId()),
                local != null && local.isDirty(), local == null ? 0 : local.getRev(), remote.rev, remote.deleted);
        switch (action) {
            case WAIT:
                // Open here: if this device hasn't changed it, the editor takes the newer version
                // in place (see takeNewer); if it has, the edit replaces this one on leaving.
                if (!local.isDirty() && remote.rev > local.getRev()) {
                    NEWER.put(local.getId(), remote);
                    Runnable watcher = WATCHERS.get(local.getId());
                    if (watcher != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(watcher);
                }
                return false;
            case INSERT: {
                NoteEntity note = new NoteEntity("", "", remote.edited);
                note.setUuid(remote.uuid);
                note.setAccount(uid);
                remote.copyInto(note, context);
                note.setGroupId(groupId);
                note.setGroupMembers(members);
                note.setId((int) dao.insert(note));
                writeSketch(context, note.getId(), remote.sketch);
                return true;
            }
            case OVERWRITE:
                remote.copyInto(local, context);
                if (groupId != null) local.setGroupMembers(members);
                dao.update(local);
                writeSketch(context, local.getId(), remote.sketch);
                return true;
            case REMOVE:
                NoteData.deleteFiles(context, local.getId());
                dao.purge(local.getId());
                return true;
            default:
                return true;
        }
    }

    /**
     * The newer version that arrived for an open note, written in, for an editor whose note is
     * unchanged. Blocking. Null when there is none (or this device has changed the note since).
     */
    @Nullable
    static NoteEntity takeNewer(Context context, int noteId) {
        Remote remote = NEWER.remove(noteId);
        if (remote == null || remote.deleted) return null;
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        NoteEntity local = dao.getNoteById(noteId);
        if (local == null || local.isDirty() || remote.rev <= local.getRev()) return null;
        remote.copyInto(local, context);
        dao.update(local);
        writeSketch(context, local.getId(), remote.sketch);
        return local;
    }

    // ================================================================ push

    private static void push(Context context, String uid, DatabaseReference notes,
                             @Nullable DriveStore drive) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        Set<Integer> done = new HashSet<>();
        // Conflicts add copies, which need pushing too; a few rounds always settle.
        for (int round = 0; round < 3; round++) {
            boolean any = false;
            for (NoteEntity note : dao.dirtyNotes(uid)) {
                if (OPEN.contains(note.getId()) || !done.add(note.getId())) continue;
                any = true;
                pushOne(context, uid, notes, note, drive, true);
            }
            if (!any) return;
        }
    }

    private static void pushOne(Context context, String uid, DatabaseReference notes, NoteEntity note,
                                @Nullable DriveStore drive, boolean mayRetry) throws Exception {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        if (note.isDeleted() && note.getSentTo() != null) {
            try {
                SentNotes.takeDown(context, uid, note);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't take a sent note down; next time", e);
                return;
            }
        }
        if (note.isDeleted() && note.getRev() == 0) {
            ShareLinks.afterPush(context, uid, note); // A link made before its first sync goes too.
            dao.purge(note.getId()); // Never reached the account: nothing to tell it.
            return;
        }
        String groupId = note.getGroupId();
        if (groupId != null && note.isDeleted()) {
            // Deleting a group note here means leaving it; the others keep it.
            GroupNotes.leave(context, note);
            dao.purge(note.getId());
            return;
        }
        Map<String, Object> value = toRemote(context, note);
        long expected = note.getRev();
        DatabaseReference target = groupId != null
                ? Account.root().child("groupNotes").child(groupId).child("note") : notes.child(note.getUuid());
        Outcome outcome = transact(target, current -> {
            Object rev = current.child("rev").getValue();
            if (current.getValue() != null && (!(rev instanceof Number) || ((Number) rev).longValue() != expected)) {
                return false;
            }
            current.setValue(value);
            return true;
        });
        if (outcome.committed) {
            if (groupId == null) ShareLinks.afterPush(context, uid, note); // Its link shows this version (or goes, deleted).
            if (note.isDeleted()) {
                if (drive != null) trashAttachments(drive, note);
                dao.purge(note.getId());
            } else {
                dao.markSynced(note.getId(), expected + 1, note.getTimestamp());
                if (groupId != null) GroupNotes.announce(note);
                if (note.getSentTo() != null) {
                    try {
                        SentNotes.publish(context, uid, note, drive);
                    } catch (Exception e) {
                        Log.w(TAG, "The sent copy waits for the next sync", e);
                    }
                }
            }
            return;
        }
        Remote remote = outcome.snapshot == null ? null : Remote.of(outcome.snapshot);
        if (remote == null) return;
        if (groupId != null) remote.uuid = note.getUuid();
        if (note.isDeleted()) {
            // Deleted here, edited elsewhere: the edit wins, the note comes back.
            if (remote.deleted) {
                dao.purge(note.getId());
            } else {
                remote.copyInto(note, context);
                note.setDeleted(false);
                dao.update(note);
                writeSketch(context, note.getId(), remote.sketch);
            }
            return;
        }
        if (remote.deleted || sameContent(context, note, remote)) {
            // Deleted elsewhere but edited here: the edit wins. Same text: nothing to merge.
            note.setRev(remote.rev);
            if (!remote.deleted) {
                note.setMedia(remote.media);
                note.setDirty(false);
            }
            dao.update(note);
            if (remote.deleted && mayRetry) pushOne(context, uid, notes, note, drive, false);
            return;
        }
        // Changed elsewhere and edited here: this device's edit is the later intent, so it
        // replaces the account's version (no "conflict" copy), based on the rev it now has.
        note.setRev(remote.rev);
        dao.update(note);
        if (mayRetry) pushOne(context, uid, notes, note, drive, false);
    }

    @VisibleForTesting
    static String conflictTitle(@Nullable String title) {
        String base = title == null || title.isEmpty() ? "Untitled" : title;
        return base + " (conflict, " + Build.MODEL + ")";
    }

    static Map<String, Object> toRemote(Context context, NoteEntity note) {
        Map<String, Object> m = new HashMap<>();
        m.put("rev", note.getRev() + 1);
        m.put("updatedAt", ServerValue.TIMESTAMP);
        m.put("device", Build.MODEL == null ? "" : clip(Build.MODEL, 64));
        if (note.isDeleted()) {
            m.put("deleted", true);
            return m;
        }
        m.put("type", note.getType());
        m.put("title", note.getTitle() == null ? "" : note.getTitle());
        m.put("content", note.getContent() == null ? "" : note.getContent());
        String data = portable(context, note);
        if (data != null) m.put("data", data);
        m.put("color", note.getColor());
        m.put("pinned", note.isPinned());
        m.put("edited", note.getTimestamp());
        // How it's filed is mine alone: never part of a group note, and only shared across devices when opted in.
        if (note.getGroupId() == null && Account.isSyncFoldersArchive(context)) {
            if (note.isArchived()) m.put("archived", true);
            if (note.getFolder() != null && !note.getFolder().isEmpty()) m.put("folder", clip(note.getFolder(), 100));
        }
        if (NoteData.SKETCH.equals(note.getType())) {
            String sketch = readSketch(context, note.getId());
            if (sketch != null) m.put("sketch", sketch);
        }
        Map<String, Object> media = new HashMap<>();
        JSONObject manifest = json(note.getMedia());
        for (Iterator<String> it = manifest.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject e = manifest.optJSONObject(name);
            if (e == null || e.optString("driveId").isEmpty()) continue;
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", name);
            entry.put("driveId", e.optString("driveId"));
            entry.put("size", e.optLong("size"));
            if (!e.optString("mime").isEmpty()) entry.put("mime", e.optString("mime"));
            if (e.optString("sha256").length() == 64) entry.put("sha256", e.optString("sha256"));
            if (!e.optString("key").isEmpty()) entry.put("key", e.optString("key"));
            for (String k : new String[]{"w", "h", "durationMs"}) if (e.optLong(k) > 0) entry.put(k, e.optLong(k));
            media.put(key(name), entry);
        }
        if (!media.isEmpty()) m.put("media", media);
        return m;
    }

    /** Mind-map links by uuid, so they point at the same note on every phone. */
    @Nullable
    private static String portable(Context context, NoteEntity note) {
        String data = note.getData();
        if (!NoteData.MINDMAP.equals(note.getType()) || data == null || !data.contains("\"link\"")) return data;
        NoteData.MindMap map = NoteData.mindMap(data);
        boolean changed = false;
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        for (NoteData.MindNode n : map.nodes) {
            if (n.link == 0 || n.linkUuid != null) continue;
            NoteEntity target = dao.getNoteById(n.link);
            n.linkUuid = target == null ? null : target.getUuid();
            if (target == null) n.link = 0;
            changed = true;
        }
        return changed ? NoteData.mindMapJson(map) : data;
    }

    // ========================================================== transaction

    private interface Mutation {
        /** Writes {@code current} and returns true, or returns false to abort. */
        boolean apply(MutableData current);
    }

    private static final class Outcome {
        boolean committed;
        DataSnapshot snapshot;
    }

    /** {@link DatabaseReference#runTransaction}, blocking. */
    private static Outcome transact(DatabaseReference ref, Mutation mutation) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Outcome outcome = new Outcome();
        DatabaseError[] error = new DatabaseError[1];
        ref.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData current) {
                // The first guess runs on a cold cache (null); writing is right then, and the
                // server sends the real value back if the guess was wrong.
                return mutation.apply(current) ? Transaction.success(current) : Transaction.abort();
            }

            @Override
            public void onComplete(@Nullable DatabaseError e, boolean committed, @Nullable DataSnapshot snapshot) {
                error[0] = e;
                outcome.committed = committed;
                outcome.snapshot = snapshot;
                latch.countDown();
            }
        }, false);
        if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) throw new TimeoutException("No answer from the database");
        if (error[0] != null) throw error[0].toException();
        return outcome;
    }

    // ========================================================== attachments

    /** Uploads files the note uses that Drive doesn't have yet; drops ones it no longer uses. */
    private static void uploadAttachments(Context context, String uid, DriveStore drive, Runnable onTransfer)
            throws IOException {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        String root = null;
        for (NoteEntity note : dao.notesOf(uid)) {
            if (note.isDeleted() || OPEN.contains(note.getId()) || note.isReceived()) continue;
            JSONObject manifest = json(note.getMedia());
            boolean changed = false;
            for (File file : attachments(context, note)) {
                if (!uses(note, file.getName()) || manifest.has(file.getName())) continue;
                if (note.getGroupId() != null) {
                    // Shared with the group: encrypted, and opened to anyone with the link, like
                    // a chat photo. The key travels in the note, which only members can read.
                    onTransfer.run();
                    String tag = groupTag(note.getGroupId(), file.getName());
                    try {
                        ChatMedia.Meta meta = ChatMedia.prepare(context, android.net.Uri.fromFile(file), tag);
                        meta.id = ChatMedia.upload(context, drive, tag);
                        manifest.put(file.getName(), new JSONObject().put("driveId", meta.id).put("key", meta.key)
                                .put("size", file.length()).put("mime", mimeOf(file.getName())));
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                    changed = true;
                    continue;
                }
                if (root == null) root = attachmentsRoot(drive);
                String folder = drive.folder(note.getUuid(), root);
                String mime = mimeOf(file.getName());
                onTransfer.run();
                String id = drive.putFile(file, file.getName(), mime, folder);
                try {
                    manifest.put(file.getName(), describe(file, mime).put("driveId", id));
                } catch (Exception e) {
                    throw new IOException(e);
                }
                changed = true;
            }
            for (Iterator<String> it = manifest.keys(); it.hasNext(); ) {
                String name = it.next();
                if (uses(note, name)) continue;
                JSONObject gone = manifest.optJSONObject(name);
                if (gone != null && !gone.optString("driveId").isEmpty()) {
                    try {
                        drive.deleteFile(gone.optString("driveId"));
                    } catch (IOException e) {
                        // In a group, another member's file isn't mine to delete; it just drops out.
                        if (note.getGroupId() == null) throw e;
                    }
                }
                it.remove();
                changed = true;
            }
            if (changed) dao.setMedia(note.getId(), manifest.toString());
        }
    }

    /** Fetches files a note uses that this phone doesn't have yet. {@code drive}: null if not connected. */
    private static void downloadAttachments(Context context, String uid, @Nullable DriveStore drive, Runnable onTransfer)
            throws IOException {
        NoteDao dao = AppDatabase.getInstance(context).noteDao();
        for (NoteEntity note : dao.notesOf(uid)) {
            if (note.isDeleted() || OPEN.contains(note.getId()) || note.getMedia() == null) continue;
            // Group notes' and received notes' files are encrypted and shared by link: no Drive needed.
            boolean group = note.getGroupId() != null || note.isReceived();
            if (!group && drive == null) continue;
            JSONObject manifest = json(note.getMedia());
            File dir = null;
            for (Iterator<String> it = manifest.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONObject entry = manifest.optJSONObject(name);
                if (entry == null || !uses(note, name) || !safeName(name)) continue;
                if (dir == null) dir = NoteData.dir(context, note.getId());
                File target = new File(dir, name);
                if (target.exists() && target.length() == entry.optLong("size", target.length())) continue;
                onTransfer.run();
                fetchOne(context, note, name, entry, target, drive);
            }
        }
    }

    /**
     * Fetches one file of a note now, e.g. a recording tapped before the sync got to it (open
     * notes are skipped by the sync). Blocking. False if the note doesn't list it or it is in my
     * Drive and Drive isn't connected here.
     */
    static boolean fetch(Context context, int noteId, String name) throws IOException {
        NoteEntity note = AppDatabase.getInstance(context).noteDao().getNoteById(noteId);
        if (note == null || note.getMedia() == null || !safeName(name)) return false;
        JSONObject entry = json(note.getMedia()).optJSONObject(name);
        if (entry == null) return false;
        boolean keyed = note.getGroupId() != null || note.isReceived() || !entry.optString("key").isEmpty();
        DriveStore drive = keyed ? null : Account.drive(context);
        if (!keyed && drive == null) return false;
        fetchOne(context, note, name, entry, new File(NoteData.dir(context, note.getId()), name), drive);
        return true;
    }

    private static void fetchOne(Context context, NoteEntity note, String name, JSONObject entry, File target,
                                 @Nullable DriveStore drive) throws IOException {
        boolean group = note.getGroupId() != null || note.isReceived();
        if (group || !entry.optString("key").isEmpty()) {
            try {
                ChatMedia.Meta meta = ChatMedia.Meta.parse(new JSONObject()
                        .putOpt("id", entry.optString("driveId")).putOpt("key", entry.optString("key"))
                        .putOpt("mime", entry.optString("mime")).toString());
                if (meta == null) return;
                ChatMedia.decryptTo(context, groupTag(note.getUuid(), name), meta, target);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
            return;
        }
        if (drive == null) return;
        File part = new File(target.getParentFile(), name + ".part");
        drive.downloadFile(entry.optString("driveId"), part);
        if (!target.delete() && target.exists()) Log.w(TAG, "Could not replace " + name);
        if (!part.renameTo(target)) {
            VaultCrypto.deleteRecursive(part);
            throw new IOException("Could not save " + name);
        }
    }

    /**
     * A file counts while the note names it: in its data (journal media, recordings, mind-map
     * pictures) or in its text, where the desktop puts pictures pasted between the words as
     * {@code ![[IMG_….png]]} lines.
     */
    static boolean uses(NoteEntity note, String name) {
        return (note.getData() != null && note.getData().contains(name))
                || (note.getContent() != null && note.getContent().contains(name));
    }

    /** Where a group note's file is kept encrypted on this phone. */
    static String groupTag(String groupId, String name) {
        return "gn_" + groupId + "_" + key(name);
    }

    private static void trashAttachments(DriveStore drive, NoteEntity note) {
        JSONObject manifest = json(note.getMedia());
        for (Iterator<String> it = manifest.keys(); it.hasNext(); ) {
            JSONObject entry = manifest.optJSONObject(it.next());
            if (entry == null) continue;
            try {
                drive.deleteFile(entry.optString("driveId"));
            } catch (IOException e) {
                Log.w(TAG, "Could not remove an attachment", e);
            }
        }
    }

    /** Account deletion: the whole attachments folder. Blocking; quietly skips if no Drive. */
    static void eraseAttachments(Context context) {
        DriveStore drive = Account.drive(context);
        if (drive == null) return;
        try {
            String app = drive.findFile(DriveStore.BACKUP_PATH.split("/")[0], "root");
            String folder = app == null ? null : drive.findFile(ATTACHMENTS, app);
            if (folder != null) drive.deleteFile(folder);
        } catch (IOException e) {
            Log.w(TAG, "Could not remove the attachments folder", e);
        }
    }

    private static String attachmentsRoot(DriveStore drive) throws IOException {
        return drive.folder(ATTACHMENTS, drive.folder(DriveStore.BACKUP_PATH.split("/")[0], "root"));
    }

    private static File[] attachments(Context context, NoteEntity note) {
        File dir = new File(new File(context.getFilesDir(), "notes"), String.valueOf(note.getId()));
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(".part")
                && !f.getName().equals(SketchActivity.STROKES_FILE) && !f.getName().equals(SketchActivity.PREVIEW_FILE));
        return files == null ? new File[0] : files;
    }

    private static JSONObject describe(File file, String mime) throws Exception {
        JSONObject entry = new JSONObject().put("size", file.length()).put("mime", mime).put("sha256", sha256(file));
        if (mime.startsWith("image/")) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth > 0) entry.put("w", bounds.outWidth).put("h", bounds.outHeight);
        } else if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getPath());
                entry.put("durationMs", parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                long w = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                if (w > 0) {
                    entry.put("w", w).put("h", parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "No metadata for " + file.getName(), e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Released either way.
                }
            }
        }
        return entry;
    }

    private static void driveFailed(Context context, IOException e) {
        Log.w(TAG, "Drive attachments failed", e);
        String message = e instanceof UserRecoverableAuthIOException || e instanceof GoogleAuthIOException
                || e instanceof DriveStore.AuthException
                ? "Google Drive needs reconnecting" : e instanceof DriveStore.QuotaException
                ? "Google Drive is full" : null;
        if (message != null) prefs(context).edit().putString(PREF_DRIVE_ERROR, message).apply();
    }

    // ============================================================ tombstones

    /** Once a week, drop tombstones every phone has had 30 days to hear about. */
    private static void sweepTombstones(Context context, String uid, DatabaseReference notes) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_LAST_SWEEP + uid, 0) < TimeUnit.DAYS.toMillis(7)) return;
        try {
            DataSnapshot old = Tasks.await(notes.orderByChild("updatedAt").endAt(now - TOMBSTONE_TTL_MS).get(),
                    TIMEOUT_S, TimeUnit.SECONDS);
            Map<String, Object> drop = new HashMap<>();
            for (DataSnapshot child : old.getChildren()) {
                if (Boolean.TRUE.equals(child.child("deleted").getValue(Boolean.class))) drop.put(child.getKey(), null);
            }
            if (!drop.isEmpty()) Tasks.await(notes.updateChildren(drop), TIMEOUT_S, TimeUnit.SECONDS);
            prefs.edit().putLong(PREF_LAST_SWEEP + uid, now).apply();
        } catch (Exception e) {
            Log.w(TAG, "Tombstone sweep failed", e);
        }
    }

    // ================================================================= live

    private static Query liveQuery;
    private static ChildEventListener liveListener;
    private static DatabaseReference liveGroups;
    private static com.google.firebase.database.ValueEventListener groupListener;
    private static DatabaseReference liveInbox;
    private static com.google.firebase.database.ValueEventListener inboxListener;

    /** While the notes grid is visible, other phones' edits arrive as they are made. */
    static synchronized void startLive(Context context) {
        String uid = Account.uid();
        if (uid == null || liveListener != null) return;
        Context app = context.getApplicationContext();
        long since = prefs(app).getLong(PREF_LAST_PULL + uid, 0);
        liveQuery = Account.notesOf(uid).orderByChild("updatedAt").startAt(since);
        liveListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previous) {
                arrived(app, uid, snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previous) {
                arrived(app, uid, snapshot);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {}

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previous) {}

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Live updates stopped", error.toException());
            }
        };
        // A group note changed by someone else bumps my index entry for it: fetch.
        liveGroups = Account.root().child("groupIndex").child(uid);
        groupListener = new com.google.firebase.database.ValueEventListener() {
            private boolean first = true;

            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (first) {
                    first = false; // The current state; the scheduled sync below covers it.
                    return;
                }
                schedule(app);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        liveGroups.addValueEventListener(groupListener);
        liveInbox = Account.root().child("inbox").child(uid);
        inboxListener = new com.google.firebase.database.ValueEventListener() {
            private boolean first = true;

            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (first) {
                    first = false;
                    return;
                }
                schedule(app);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        liveInbox.addValueEventListener(inboxListener);
        liveQuery.addChildEventListener(liveListener);
        schedule(app);
    }

    static synchronized void stopLive() {
        if (liveGroups != null && groupListener != null) liveGroups.removeEventListener(groupListener);
        liveGroups = null;
        if (liveInbox != null && inboxListener != null) liveInbox.removeEventListener(inboxListener);
        liveInbox = null;
        inboxListener = null;
        groupListener = null;
        if (liveQuery != null && liveListener != null) liveQuery.removeEventListener(liveListener);
        liveQuery = null;
        liveListener = null;
    }

    private static void arrived(Context context, String uid, DataSnapshot snapshot) {
        Remote remote = Remote.of(snapshot);
        if (remote == null) return;
        AppDatabase.IO.execute(() -> {
            if (!uid.equals(Account.uid())) return;
            // A worker may hold the lock through a long upload; never park a shared IO thread
            // behind it. The queued run pulls this change after it.
            if (!LOCK.tryLock()) {
                schedule(context);
                return;
            }
            try {
                apply(context, uid, remote);
            } finally {
                LOCK.unlock();
            }
        });
    }

    // =============================================================== remote

    /** One note as the database holds it. */
    static final class Remote {
        String uuid, type, title, content, data, sketch, media, folder;
        long rev, updatedAt, edited;
        int color;
        boolean pinned, deleted, archived;
        /** One of my own notes (users/{uid}/notes): only these say how the note is filed. */
        boolean mine;

        @Nullable
        static Remote of(DataSnapshot s) {
            if (s.getKey() == null || !s.hasChild("rev")) return null;
            Remote r = new Remote();
            r.uuid = s.getKey();
            r.rev = number(s.child("rev"));
            r.updatedAt = number(s.child("updatedAt"));
            r.deleted = Boolean.TRUE.equals(s.child("deleted").getValue(Boolean.class));
            r.type = string(s.child("type"), NoteData.TEXT);
            r.title = string(s.child("title"), "");
            r.content = string(s.child("content"), "");
            r.data = s.child("data").getValue(String.class);
            r.sketch = s.child("sketch").getValue(String.class);
            r.color = (int) number(s.child("color"));
            r.pinned = Boolean.TRUE.equals(s.child("pinned").getValue(Boolean.class));
            r.archived = Boolean.TRUE.equals(s.child("archived").getValue(Boolean.class));
            r.folder = s.child("folder").getValue(String.class);
            if (r.folder != null && r.folder.isEmpty()) r.folder = null;
            DatabaseReference parent = s.getRef().getParent();
            r.mine = parent != null && "notes".equals(parent.getKey());
            r.edited = number(s.child("edited"));
            if (r.edited == 0) r.edited = r.updatedAt;
            JSONObject manifest = new JSONObject();
            for (DataSnapshot m : s.child("media").getChildren()) {
                String name = m.child("name").getValue(String.class);
                if (name == null) continue;
                try {
                    JSONObject e = new JSONObject().put("driveId", string(m.child("driveId"), ""))
                            .put("size", number(m.child("size"))).put("mime", string(m.child("mime"), ""));
                    if (m.hasChild("sha256")) e.put("sha256", string(m.child("sha256"), ""));
                    if (m.hasChild("key")) e.put("key", string(m.child("key"), ""));
                    for (String k : new String[]{"w", "h", "durationMs"}) if (m.hasChild(k)) e.put(k, number(m.child(k)));
                    manifest.put(name, e);
                } catch (Exception ignored) {
                    // Plain values only.
                }
            }
            r.media = manifest.length() == 0 ? null : manifest.toString();
            return r;
        }

        /** Replaces the local copy's content and marks it in step with this version. */
        void copyInto(NoteEntity note, Context context) {
            note.setType(type);
            note.setTitle(title);
            note.setContent(content);
            note.setData(data);
            note.setColor(color);
            note.setPinned(pinned);
            if (mine && Account.isSyncFoldersArchive(context)) {
                note.setArchived(archived);
                note.setFolder(folder);
            }
            note.setTimestamp(edited);
            note.setRev(rev);
            note.setMedia(media);
            note.setDirty(false);
            note.setDeleted(false);
        }
    }

    private static boolean sameContent(Context context, NoteEntity n, Remote r) {
        return java.util.Objects.equals(n.getTitle(), r.title) && java.util.Objects.equals(n.getContent(), r.content)
                && java.util.Objects.equals(n.getData(), r.data) && n.getColor() == r.color
                && n.isPinned() == r.pinned && n.getType().equals(r.type)
                && (!r.mine || !Account.isSyncFoldersArchive(context) || (n.isArchived() == r.archived && java.util.Objects.equals(n.getFolder(), r.folder)));
    }

    // ============================================================== sketches

    @Nullable
    private static String readSketch(Context context, int noteId) {
        File file = new File(new File(new File(context.getFilesDir(), "notes"), String.valueOf(noteId)),
                SketchActivity.STROKES_FILE);
        if (!file.exists() || file.length() > 1_000_000) return null;
        try (InputStream in = new FileInputStream(file)) {
            return new String(VaultCrypto.readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Could not read a sketch", e);
            return null;
        }
    }

    /** Strokes arrive as vectors; the card's picture is drawn here from them. */
    static void writeSketch(Context context, int noteId, @Nullable String strokes) {
        if (strokes == null) return;
        File dir = NoteData.dir(context, noteId);
        try (FileOutputStream out = new FileOutputStream(new File(dir, SketchActivity.STROKES_FILE))) {
            out.write(strokes.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Could not write a sketch", e);
            return;
        }
        SketchActivity.renderPreview(context, dir, strokes);
    }

    // =============================================================== helpers

    private static void copyFiles(File from, File to) {
        File[] files = from.listFiles(File::isFile);
        if (files == null) return;
        for (File f : files) {
            try (InputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(new File(to, f.getName()))) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            } catch (IOException e) {
                Log.w(TAG, "Could not copy " + f.getName(), e);
            }
        }
    }

    /** Database keys can't hold . # $ [ ] / ; the real name travels in the entry. */
    @VisibleForTesting
    static String key(String name) {
        return name.replaceAll("[.#$\\[\\]/]", "_");
    }

    /** A file name from the account is only trusted to name a file inside the note's folder. */
    @VisibleForTesting
    static boolean safeName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".")
                && !name.equals("..") && name.length() <= 200;
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) return mime;
        if (ext.equals("m4a") || ext.equals("aac")) return "audio/mp4";
        return NoteData.isVideo(name) ? "video/mp4" : "application/octet-stream";
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private static JSONObject json(@Nullable String s) {
        if (s == null || s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static long number(DataSnapshot s) {
        Object v = s.getValue();
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private static String string(DataSnapshot s, String fallback) {
        Object v = s.getValue();
        return v instanceof String ? (String) v : fallback;
    }

    private static long parse(@Nullable String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static SharedPreferences prefs(Context context) {
        return SecurityHelper.getAppPrefs(context);
    }
}
