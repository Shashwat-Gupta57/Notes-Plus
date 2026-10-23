package org.flexstudios.notes.plus;

import javafx.application.Platform;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Keeps the notes of the signed-in {@link Account} in step with the Realtime Database, and
 * their photos, videos and recordings with the user's own Google Drive: the phone's NoteSync,
 * rule for rule, so the two can share one account.
 *
 * <p><b>Model.</b> {@code users/{uid}/notes/{uuid}} holds a note's text and structure plus
 * {@code rev}, which goes up by one with every write (the rules enforce it). A local row
 * remembers the {@code rev} it is based on. Attachments live in Drive under
 * {@code Notes-plus/Attachments/{uuid}/}; the database only holds their manifest.
 *
 * <p><b>Rules.</b>
 * <ul>
 *   <li>Push only lands if the server is still at our {@code rev} (an ETag compare-and-set,
 *       the REST form of the phone's transaction). Otherwise both sides changed: the server's
 *       version replaces the note and ours is kept beside it as a "conflict" copy.</li>
 *   <li>Pull only overwrites notes with no unpushed edits.</li>
 *   <li>A note open in an editor ({@link #OPEN}) is neither pushed nor overwritten.</li>
 *   <li>Deletes are tombstones ({@code deleted: true}), kept 30 days so every device hears.</li>
 * </ul>
 *
 * Group notes sync their words here too; their files are encrypted the chat way, which only
 * the phone reads for now.
 */
final class NoteSync {

    private NoteSync() {}

    private static final System.Logger LOG = System.getLogger("NoteSync");
    private static final String PREF_LAST_PULL = "notes_last_pull_";
    private static final String PREF_LAST_SYNC = "notes_last_sync_";
    private static final String PREF_LAST_SWEEP = "notes_last_sweep_";
    static final String PREF_DRIVE_ERROR = "notes_drive_error";
    private static final long TOMBSTONE_TTL_MS = TimeUnit.DAYS.toMillis(30);

    /** Ids of notes open in an editor right now. */
    static final Set<Integer> OPEN = ConcurrentHashMap.newKeySet();

    private static final ScheduledExecutorService SYNC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notes-sync");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static volatile boolean running;
    private static int failures;
    private static final List<Runnable> OBSERVERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static String newUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ============================================================ scheduling

    /** Queues a sync shortly. Cheap to call; does nothing when signed out. */
    static void schedule() {
        schedule(1500);
    }

    private static void schedule(long delayMs) {
        if (Account.uid() == null || !QUEUED.compareAndSet(false, true)) return;
        SYNC.schedule(NoteSync::job, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Runs on the FX thread when a sync starts or ends (the account screen's status). */
    static void observe(Runnable r) {
        OBSERVERS.add(r);
    }

    static boolean isRunning() {
        return running;
    }

    private static void job() {
        QUEUED.set(false);
        running = true;
        Platform.runLater(() -> OBSERVERS.forEach(Runnable::run));
        try {
            run();
            failures = 0;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Sync failed; will retry", e);
            // Linear and short, like the phone: a later change is picked up by the retry.
            if (++failures <= 5) schedule(15_000L * failures);
        } finally {
            running = false;
            Platform.runLater(() -> OBSERVERS.forEach(Runnable::run));
        }
    }

    /** A safety net while the app stays open: pull every six hours. */
    static void startPeriodic() {
        SYNC.scheduleAtFixedRate(() -> schedule(0), 6, 6, TimeUnit.HOURS);
    }

    // ======================================================== local deletes

    /** Signing out: the account's notes become this computer's own, or leave it. Blocking. */
    static void leave(String uid, boolean keepNotes) {
        NoteStore store = NoteStore.get();
        // Notes sent to me were never mine to keep.
        for (NoteEntity n : store.receivedNotes(uid)) {
            NoteData.deleteFiles(n.getId());
            store.purge(n.getId());
        }
        if (keepNotes) {
            store.release(uid);
        } else {
            for (NoteEntity n : store.notesOf(uid)) {
                NoteData.deleteFiles(n.getId());
                store.purge(n.getId());
            }
        }
        Prefs.put(PREF_LAST_PULL + uid, null);
        Prefs.put(PREF_LAST_SYNC + uid, null);
        Prefs.put(PREF_LAST_SWEEP + uid, null);
        Prefs.put(PREF_DRIVE_ERROR, null);
    }

    static long lastSynced(String uid) {
        return Prefs.getLong(PREF_LAST_SYNC + uid);
    }

    static String driveError() {
        return Prefs.get(PREF_DRIVE_ERROR);
    }

    // ================================================================= run

    /** One full pass: pull, upload new attachments, push, download missing ones. Blocking. */
    static synchronized void run() throws Exception {
        String uid = Account.uid();
        if (uid == null) return;
        NoteStore store = NoteStore.get();
        for (NoteEntity t : store.localTombstones()) if (!OPEN.contains(t.getId())) store.purge(t.getId());
        pull(uid);
        try {
            pullGroups(uid);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Group notes not fetched this time", e);
        }
        try {
            SentNotes.pull(uid);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Sent notes not fetched this time", e);
        }
        Drive drive = Account.drive();
        if (drive != null) {
            try {
                uploadAttachments(uid, drive);
            } catch (IOException e) {
                driveFailed(e);
                drive = null;
            }
        }
        push(uid, drive);
        try {
            // Group notes' and received notes' files come from their links: no Drive needed for those.
            downloadAttachments(uid, drive);
        } catch (IOException e) {
            driveFailed(e);
        }
        sweepTombstones(uid);
        Prefs.put(PREF_LAST_SYNC + uid, System.currentTimeMillis());
        // Notes changed on the phone reach their linked files on this computer.
        FileLinks.syncLater();
    }

    // ================================================================ pull

    private static String notesPath(String uid) {
        return "users/" + uid + "/notes";
    }

    private static void pull(String uid) throws Exception {
        long since = Prefs.getLong(PREF_LAST_PULL + uid);
        Object changed = Firebase.get(notesPath(uid), Firebase.query("updatedAt", "startAt", since));
        long newest = since, waiting = Long.MAX_VALUE;
        if (changed instanceof JSONObject all) {
            for (String key : all.keySet()) {
                Remote remote = Remote.of(key, all.optJSONObject(key));
                if (remote == null) continue;
                if (!apply(uid, remote, null, null)) waiting = Math.min(waiting, remote.updatedAt);
                newest = Math.max(newest, remote.updatedAt);
            }
        }
        // A change held back for an open note is fetched again next time.
        Prefs.put(PREF_LAST_PULL + uid, Math.min(newest, waiting));
    }

    /**
     * Every group note I'm a member of ({@code groupIndex/{uid}}), brought in like my own notes.
     * One I was removed from (or that is gone) stays here as my own note.
     */
    private static void pullGroups(String uid) throws Exception {
        NoteStore store = NoteStore.get();
        JSONObject index = Firebase.getObject("groupIndex/" + uid);
        Set<String> listed = new HashSet<>();
        if (index != null) {
            for (String gid : index.keySet()) {
                JSONObject group;
                try {
                    group = Firebase.getObject("groupNotes/" + gid);
                } catch (Firebase.Error e) {
                    if (!e.refused()) throw e;
                    continue; // No longer a member: handled below, as not listed.
                }
                JSONObject members = group == null ? null : group.optJSONObject("members");
                if (members == null || !members.has(uid)) continue;
                listed.add(gid);
                String membersJson = membersJson(members);
                Remote remote = Remote.of(gid, group.optJSONObject("note"));
                if (remote != null) apply(uid, remote, gid, membersJson);
                NoteEntity local = store.byUuid(gid);
                if (local != null && !Objects.equals(membersJson, local.getGroupMembers())) {
                    store.setGroupMembers(local.getId(), membersJson);
                }
            }
        }
        for (NoteEntity n : store.groupNotes(uid)) {
            if (!listed.contains(n.getGroupId()) && !OPEN.contains(n.getId())) store.leaveGroup(n.getId());
        }
    }

    private static String membersJson(JSONObject members) {
        JSONObject out = new JSONObject();
        for (String k : members.keySet()) if (members.opt(k) instanceof String name) out.put(k, name);
        return out.toString();
    }

    static final int SKIP = 0, WAIT = 1, INSERT = 2, OVERWRITE = 3, REMOVE = 4;

    /** What a pulled version does to the local copy. Pure, so it is tested on its own. */
    static int decide(boolean exists, boolean open, boolean dirty, long localRev, long remoteRev, boolean remoteDeleted) {
        if (!exists) return remoteDeleted ? SKIP : INSERT;
        if (open) return WAIT;
        if (dirty) return SKIP; // The push sees the newer rev and resolves it as a conflict.
        if (remoteRev <= localRev) return SKIP;
        return remoteDeleted ? REMOVE : OVERWRITE;
    }

    /** Applies one pulled note; {@code groupId}: it came from a group note. False when it had to wait. */
    private static boolean apply(String uid, Remote remote, String groupId, String members) {
        NoteStore store = NoteStore.get();
        NoteEntity local = store.byUuid(remote.uuid);
        // A note I shared became a group note: its old place among my own notes no longer counts.
        if (groupId == null && local != null && local.getGroupId() != null) return true;
        if (local != null && !uid.equals(local.getAccount())) {
            // This computer's own copy of a note it once shared with the account: it keeps its
            // text, under a new identity, and the account's version comes in beside it.
            if (sameContent(local, remote)) {
                local.setAccount(uid);
                local.setRev(remote.rev);
                local.setDirty(false);
                local.setMedia(remote.media);
                store.update(local);
                return true;
            }
            local.setUuid(newUuid());
            store.update(local);
            local = null;
        }
        int action = decide(local != null, local != null && OPEN.contains(local.getId()),
                local != null && local.isDirty(), local == null ? 0 : local.getRev(), remote.rev, remote.deleted);
        switch (action) {
            case WAIT:
                return false;
            case INSERT: {
                NoteEntity note = new NoteEntity("", "", remote.edited);
                note.setUuid(remote.uuid);
                note.setAccount(uid);
                remote.copyInto(note);
                note.setGroupId(groupId);
                note.setGroupMembers(members);
                note.setId(store.insert(note));
                writeSketch(note.getId(), remote.sketch);
                return true;
            }
            case OVERWRITE:
                remote.copyInto(local);
                if (groupId != null) local.setGroupMembers(members);
                store.update(local);
                writeSketch(local.getId(), remote.sketch);
                return true;
            case REMOVE:
                NoteData.deleteFiles(local.getId());
                FileLinks.trash(local.getUuid()); // Deleted on another device: its file here goes too.
                store.purge(local.getId());
                return true;
            default:
                return true;
        }
    }

    // ================================================================ push

    private static void push(String uid, Drive drive) throws Exception {
        NoteStore store = NoteStore.get();
        Set<Integer> done = new HashSet<>();
        // Conflicts add copies, which need pushing too; a few rounds always settle.
        for (int round = 0; round < 3; round++) {
            boolean any = false;
            for (NoteEntity note : store.dirtyNotes(uid)) {
                if (OPEN.contains(note.getId()) || !done.add(note.getId())) continue;
                any = true;
                pushOne(uid, note, drive, true);
            }
            if (!any) return;
        }
    }

    private static void pushOne(String uid, NoteEntity note, Drive drive, boolean mayRetry) throws Exception {
        NoteStore store = NoteStore.get();
        if (note.isDeleted() && note.getSentTo() != null) {
            try {
                SentNotes.takeDown(uid, note);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Couldn't take a sent note down; next time", e);
                return;
            }
        }
        if (note.isDeleted() && note.getRev() == 0) {
            store.purge(note.getId()); // Never reached the account: nothing to tell it.
            return;
        }
        String groupId = note.getGroupId();
        if (groupId != null && note.isDeleted()) {
            // Deleting a group note here means leaving it; the others keep it.
            leaveGroup(uid, groupId);
            store.purge(note.getId());
            return;
        }
        JSONObject value = toRemote(note);
        long expected = note.getRev();
        String target = groupId != null ? "groupNotes/" + groupId + "/note" : notesPath(uid) + "/" + note.getUuid();

        JSONObject theirs = null;
        boolean committed = false;
        for (int attempt = 0; attempt < 5 && !committed; attempt++) {
            Firebase.Tagged current = Firebase.getTagged(target);
            JSONObject now = current.value() instanceof JSONObject o ? o : null;
            if (now != null && now.optLong("rev", -1) != expected) {
                theirs = now;
                break;
            }
            committed = Firebase.putIf(target, value, current.etag()) == null;
        }
        if (committed) {
            if (note.isDeleted()) {
                if (drive != null) trashAttachments(drive, note);
                store.purge(note.getId());
            } else {
                store.markSynced(note.getId(), expected + 1, note.getTimestamp());
                if (groupId != null) announce(groupId, note.getGroupMembers());
                if (note.getSentTo() != null) {
                    try {
                        SentNotes.publish(uid, note, drive);
                    } catch (Exception e) {
                        LOG.log(System.Logger.Level.WARNING, "The sent copy waits for the next sync", e);
                    }
                }
            }
            return;
        }
        Remote remote = theirs == null ? null : Remote.of(note.getUuid(), theirs);
        if (remote == null) return;
        if (note.isDeleted()) {
            // Deleted here, edited elsewhere: the edit wins, the note comes back.
            if (remote.deleted) {
                store.purge(note.getId());
            } else {
                remote.copyInto(note);
                note.setDeleted(false);
                store.update(note);
                writeSketch(note.getId(), remote.sketch);
            }
            return;
        }
        if (remote.deleted || sameContent(note, remote)) {
            // Deleted elsewhere but edited here: the edit wins. Same text: nothing to merge.
            note.setRev(remote.rev);
            if (!remote.deleted) {
                note.setMedia(remote.media);
                note.setDirty(false);
            }
            store.update(note);
            if (remote.deleted && mayRetry) pushOne(uid, note, drive, false);
            return;
        }
        // Changed elsewhere and edited here: this device's edit is the later intent, so it
        // replaces the account's version (no "conflict" copy), based on the rev it now has.
        note.setRev(remote.rev);
        store.update(note);
        if (mayRetry) pushOne(uid, note, drive, false);
    }

    static String conflictTitle(String title) {
        String base = title == null || title.isEmpty() ? "Untitled" : title;
        return base + " (conflict, " + device() + ")";
    }

    static String device() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            try {
                name = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                name = "Computer";
            }
        }
        return clip(name, 64);
    }

    static JSONObject toRemote(NoteEntity note) {
        JSONObject m = new JSONObject();
        m.put("rev", note.getRev() + 1);
        m.put("updatedAt", Firebase.now());
        m.put("device", device());
        if (note.isDeleted()) {
            m.put("deleted", true);
            return m;
        }
        m.put("type", note.getType());
        m.put("title", note.getTitle() == null ? "" : note.getTitle());
        m.put("content", note.getContent() == null ? "" : note.getContent());
        String data = portable(note);
        if (data != null) m.put("data", data);
        m.put("color", note.getColor());
        m.put("pinned", note.isPinned());
        m.put("edited", note.getTimestamp());
        if (NoteData.SKETCH.equals(note.getType())) {
            String sketch = readSketch(note.getId());
            if (sketch != null) m.put("sketch", sketch);
        }
        JSONObject media = new JSONObject();
        JSONObject manifest = json(note.getMedia());
        for (String name : manifest.keySet()) {
            JSONObject e = manifest.optJSONObject(name);
            if (e == null || e.optString("driveId").isEmpty()) continue;
            JSONObject entry = new JSONObject().put("name", name).put("driveId", e.optString("driveId"))
                    .put("size", e.optLong("size"));
            if (!e.optString("mime").isEmpty()) entry.put("mime", e.optString("mime"));
            if (e.optString("sha256").length() == 64) entry.put("sha256", e.optString("sha256"));
            if (!e.optString("key").isEmpty()) entry.put("key", e.optString("key"));
            for (String k : new String[]{"w", "h", "durationMs"}) if (e.optLong(k) > 0) entry.put(k, e.optLong(k));
            media.put(key(name), entry);
        }
        if (!media.isEmpty()) m.put("media", media);
        return m;
    }

    /** Mind-map links by uuid, so they point at the same note on every device. */
    private static String portable(NoteEntity note) {
        String data = note.getData();
        if (!NoteData.MINDMAP.equals(note.getType()) || data == null || !data.contains("\"link\"")) return data;
        NoteData.MindMap map = NoteData.mindMap(data);
        boolean changed = false;
        for (NoteData.MindNode n : map.nodes) {
            if (n.link == 0 || n.linkUuid != null) continue;
            NoteEntity target = NoteStore.get().byId(n.link);
            n.linkUuid = target == null ? null : target.getUuid();
            if (target == null) n.link = 0;
            changed = true;
        }
        return changed ? NoteData.mindMapJson(map) : data;
    }

    // ============================================================ group notes

    /** Tells every member's devices that the group note changed. */
    static void announce(String gid, String membersJson) {
        JSONObject members = json(membersJson);
        JSONObject update = new JSONObject();
        for (String m : members.keySet()) update.put("groupIndex/" + m + "/" + gid, new JSONObject().put("at", Firebase.now()));
        if (update.isEmpty()) return;
        try {
            Firebase.patch("", update);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not announce a group note", e);
        }
    }

    /** I leave; the others keep it. */
    static void leaveGroup(String uid, String gid) throws IOException {
        try {
            Object size = Firebase.get("groupNotes/" + gid + "/size", null);
            long n = size instanceof Number num ? num.longValue() : 1;
            Firebase.patch("", new JSONObject()
                    .put("groupNotes/" + gid + "/members/" + uid, JSONObject.NULL)
                    .put("groupNotes/" + gid + "/size", Math.max(0, n - 1))
                    .put("groupIndex/" + uid + "/" + gid, JSONObject.NULL));
        } catch (Firebase.Error e) {
            if (!e.refused()) throw e; // Already out of it.
        }
    }

    // ========================================================== attachments

    /**
     * The files a note's own folder holds that sync: everything but the sketch's own files.
     * A file counts while the note names it, in its data or (pasted pictures) its text.
     */
    private static List<Path> attachments(NoteEntity note) {
        Path dir = Storage.notesDir().resolve(String.valueOf(note.getId()));
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(f -> {
                String n = f.getFileName().toString();
                return !n.endsWith(".part") && !n.equals(SketchEditor.STROKES_FILE) && !n.equals(SketchEditor.PREVIEW_FILE);
            }).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static boolean uses(NoteEntity note, String name) {
        return (note.getData() != null && note.getData().contains(name))
                || (note.getContent() != null && note.getContent().contains(name));
    }

    /** Uploads files the note uses that Drive doesn't have yet; drops ones it no longer uses. */
    private static void uploadAttachments(String uid, Drive drive) throws IOException {
        NoteStore store = NoteStore.get();
        String root = null;
        for (NoteEntity note : store.notesOf(uid)) {
            if (note.isDeleted() || OPEN.contains(note.getId()) || note.isReceived()) continue;
            JSONObject manifest = json(note.getMedia());
            boolean changed = false;
            for (Path file : attachments(note)) {
                String name = file.getFileName().toString();
                if (!uses(note, name) || manifest.has(name)) continue;
                if (note.getGroupId() != null) {
                    // Shared with the group: encrypted, and opened to anyone with the link, like a
                    // chat photo. The key travels in the note, which only members can read.
                    String tag = groupTag(note.getGroupId(), name);
                    ChatMedia.Meta meta = ChatMedia.prepare(file, tag);
                    meta.id = ChatMedia.upload(drive, tag);
                    manifest.put(name, new JSONObject().put("driveId", meta.id).put("key", meta.key)
                            .put("size", Files.size(file)).put("mime", mimeOf(name)));
                    changed = true;
                    continue;
                }
                if (root == null) root = drive.attachmentsRoot();
                String folder = drive.folder(note.getUuid(), root);
                String mime = mimeOf(name);
                String id = drive.putFile(file, name, mime, folder);
                manifest.put(name, describe(file, mime).put("driveId", id));
                changed = true;
            }
            for (Iterator<String> it = manifest.keys(); it.hasNext(); ) {
                String name = it.next();
                if (uses(note, name)) continue;
                JSONObject gone = manifest.optJSONObject(name);
                if (gone != null && !gone.optString("driveId").isEmpty()) {
                    try {
                        drive.delete(gone.optString("driveId"));
                    } catch (IOException e) {
                        // In a group, another member's file isn't mine to delete; it just drops out.
                        if (note.getGroupId() == null) throw e;
                    }
                }
                it.remove();
                changed = true;
            }
            if (changed) store.setMedia(note.getId(), manifest.toString());
        }
    }

    /**
     * Fetches files a note uses that this computer doesn't have yet. Encrypted ones (group notes,
     * notes sent to me) come from their public links; my own from my Drive ({@code drive} null:
     * not connected, so those wait).
     */
    private static void downloadAttachments(String uid, Drive drive) throws IOException {
        for (NoteEntity note : NoteStore.get().notesOf(uid)) {
            if (note.isDeleted() || OPEN.contains(note.getId()) || note.getMedia() == null) continue;
            JSONObject manifest = json(note.getMedia());
            Path dir = null;
            boolean fetched = false;
            for (String name : manifest.keySet()) {
                JSONObject entry = manifest.optJSONObject(name);
                if (entry == null || !uses(note, name) || !safeName(name)) continue;
                boolean keyed = !entry.optString("key").isEmpty();
                if (!keyed && drive == null) continue;
                if (dir == null) dir = NoteData.dir(note.getId());
                Path target = dir.resolve(name);
                if (Files.exists(target) && Files.size(target) == entry.optLong("size", Files.size(target))) continue;
                if (keyed) {
                    ChatMedia.Meta meta = ChatMedia.Meta.parse(new JSONObject().put("id", entry.optString("driveId"))
                            .put("key", entry.optString("key")).put("mime", entry.optString("mime")).toString());
                    if (meta == null) continue;
                    ChatMedia.decryptTo(groupTag(note.getUuid(), name), meta, target);
                } else {
                    drive.download(entry.optString("driveId"), target);
                }
                fetched = true;
            }
            // The grid shows the pictures as soon as they are here.
            if (fetched) NoteStore.get().notifyChanged();
        }
    }

    /**
     * Fetches one file of a note now, e.g. a recording clicked before the sync got to it (open
     * notes are skipped by the sync). Blocking. False if the note doesn't list it, or it is in my
     * Drive and Drive isn't connected here.
     */
    static boolean fetch(int noteId, String name) throws IOException {
        NoteEntity note = NoteStore.get().byId(noteId);
        if (note == null || note.getMedia() == null || !safeName(name)) return false;
        JSONObject entry = json(note.getMedia()).optJSONObject(name);
        if (entry == null) return false;
        Path target = NoteData.dir(note.getId()).resolve(name);
        if (!entry.optString("key").isEmpty()) {
            ChatMedia.Meta meta = ChatMedia.Meta.parse(new JSONObject().put("id", entry.optString("driveId"))
                    .put("key", entry.optString("key")).put("mime", entry.optString("mime")).toString());
            if (meta == null) return false;
            ChatMedia.decryptTo(groupTag(note.getUuid(), name), meta, target);
            return true;
        }
        Drive drive = Account.drive();
        if (drive == null) return false;
        drive.download(entry.optString("driveId"), target);
        return true;
    }

    private static void trashAttachments(Drive drive, NoteEntity note) {
        JSONObject manifest = json(note.getMedia());
        for (String name : manifest.keySet()) {
            JSONObject entry = manifest.optJSONObject(name);
            if (entry == null) continue;
            try {
                drive.delete(entry.optString("driveId"));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not remove an attachment", e);
            }
        }
    }

    /** Account deletion: the whole attachments folder. Quietly skips if no Drive. */
    static void eraseAttachments() {
        Drive drive = Account.drive();
        if (drive == null) return;
        try {
            String app = drive.find(Drive.APP_FOLDER, "root", true);
            String folder = app == null ? null : drive.find(Drive.ATTACHMENTS, app, true);
            if (folder != null) drive.delete(folder);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not remove the attachments folder", e);
        }
    }

    private static JSONObject describe(Path file, String mime) throws IOException {
        JSONObject entry = new JSONObject().put("size", Files.size(file)).put("mime", mime).put("sha256", sha256(file));
        if (mime.startsWith("image/")) {
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader r = readers.next();
                    r.setInput(in);
                    entry.put("w", r.getWidth(0)).put("h", r.getHeight(0));
                    r.dispose();
                }
            } catch (Exception ignored) {
                // No size: the phone measures it itself.
            }
        } else if (mime.startsWith("audio/")) {
            try {
                AudioFileFormat f = AudioSystem.getAudioFileFormat(file.toFile());
                long ms = (long) (f.getFrameLength() / f.getFormat().getFrameRate() * 1000);
                if (ms > 0) entry.put("durationMs", ms);
            } catch (Exception ignored) {
                // Not a format Java reads (e.g. the phone's AAC): no duration.
            }
        }
        return entry;
    }

    private static void driveFailed(IOException e) {
        LOG.log(System.Logger.Level.WARNING, "Drive attachments failed", e);
        String message = e instanceof Drive.AuthException ? "Google Drive needs reconnecting"
                : e instanceof Drive.QuotaException ? "Google Drive is full" : null;
        if (message != null) Prefs.put(PREF_DRIVE_ERROR, message);
    }

    // ============================================================ tombstones

    /** Once a week, drop tombstones every device has had 30 days to hear about. */
    private static void sweepTombstones(String uid) {
        long now = System.currentTimeMillis();
        if (now - Prefs.getLong(PREF_LAST_SWEEP + uid) < TimeUnit.DAYS.toMillis(7)) return;
        try {
            Object old = Firebase.get(notesPath(uid), Firebase.query("updatedAt", "endAt", now - TOMBSTONE_TTL_MS));
            JSONObject drop = new JSONObject();
            if (old instanceof JSONObject all) {
                for (String k : all.keySet()) {
                    JSONObject n = all.optJSONObject(k);
                    if (n != null && n.optBoolean("deleted")) drop.put(k, JSONObject.NULL);
                }
            }
            if (!drop.isEmpty()) Firebase.patch(notesPath(uid), drop);
            Prefs.put(PREF_LAST_SWEEP + uid, now);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Tombstone sweep failed", e);
        }
    }

    // ================================================================= live

    private static Firebase.Listener liveNotes, liveGroups, liveInbox;

    /** While the app is open and signed in, other devices' edits arrive as they are made. */
    static synchronized void startLive() {
        String uid = Account.uid();
        if (uid == null || liveNotes != null) return;
        long since = Prefs.getLong(PREF_LAST_PULL + uid);
        // Any change (and the stream's first answer) queues a sync, which pulls incrementally.
        liveNotes = Firebase.listen(notesPath(uid), Firebase.query("updatedAt", "startAt", since), (e, d) -> schedule(300));
        liveGroups = Firebase.listen("groupIndex/" + uid, null, (e, d) -> schedule(300));
        liveInbox = Firebase.listen("inbox/" + uid, null, (e, d) -> schedule(300));
        schedule();
    }

    static synchronized void stopLive() {
        if (liveNotes != null) liveNotes.close();
        if (liveGroups != null) liveGroups.close();
        if (liveInbox != null) liveInbox.close();
        liveNotes = liveGroups = liveInbox = null;
    }

    // =============================================================== remote

    /** One note as the database holds it. */
    static final class Remote {
        String uuid, type, title, content, data, sketch, media;
        long rev, updatedAt, edited;
        int color;
        boolean pinned, deleted;

        static Remote of(String key, JSONObject s) {
            if (key == null || s == null || !s.has("rev")) return null;
            Remote r = new Remote();
            r.uuid = key;
            r.rev = s.optLong("rev");
            r.updatedAt = s.optLong("updatedAt");
            r.deleted = s.optBoolean("deleted");
            r.type = s.opt("type") instanceof String t ? t : NoteData.TEXT;
            r.title = s.opt("title") instanceof String t ? t : "";
            r.content = s.opt("content") instanceof String t ? t : "";
            r.data = s.opt("data") instanceof String t ? t : null;
            r.sketch = s.opt("sketch") instanceof String t ? t : null;
            r.color = s.optInt("color");
            r.pinned = s.optBoolean("pinned");
            r.edited = s.optLong("edited");
            if (r.edited == 0) r.edited = r.updatedAt;
            JSONObject manifest = new JSONObject();
            JSONObject media = s.optJSONObject("media");
            if (media != null) {
                for (String k : media.keySet()) {
                    JSONObject m = media.optJSONObject(k);
                    String name = m == null ? null : m.optString("name", null);
                    if (name == null) continue;
                    JSONObject e = new JSONObject().put("driveId", m.optString("driveId", ""))
                            .put("size", m.optLong("size")).put("mime", m.optString("mime", ""));
                    if (m.has("sha256")) e.put("sha256", m.optString("sha256"));
                    if (m.has("key")) e.put("key", m.optString("key"));
                    for (String f : new String[]{"w", "h", "durationMs"}) if (m.has(f)) e.put(f, m.optLong(f));
                    manifest.put(name, e);
                }
            }
            r.media = manifest.isEmpty() ? null : manifest.toString();
            return r;
        }

        /** Replaces the local copy's content and marks it in step with this version. */
        void copyInto(NoteEntity note) {
            note.setType(type);
            note.setTitle(title);
            note.setContent(content);
            note.setData(data);
            note.setColor(color);
            note.setPinned(pinned);
            note.setTimestamp(edited);
            note.setRev(rev);
            note.setMedia(media);
            note.setDirty(false);
            note.setDeleted(false);
        }
    }

    private static boolean sameContent(NoteEntity n, Remote r) {
        return Objects.equals(n.getTitle(), r.title) && Objects.equals(n.getContent(), r.content)
                && Objects.equals(n.getData(), r.data) && n.getColor() == r.color
                && n.isPinned() == r.pinned && n.getType().equals(r.type);
    }

    // ============================================================== sketches

    private static String readSketch(int noteId) {
        Path file = Storage.notesDir().resolve(String.valueOf(noteId)).resolve(SketchEditor.STROKES_FILE);
        try {
            if (!Files.exists(file) || Files.size(file) > 1_000_000) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Strokes arrive as vectors; the card's picture is drawn here from them. */
    static void writeSketch(int noteId, String strokes) {
        if (strokes == null) return;
        Path dir = NoteData.dir(noteId);
        try {
            Files.writeString(dir.resolve(SketchEditor.STROKES_FILE), strokes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not write a sketch", e);
            return;
        }
        CountDownLatch drawn = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                SketchEditor.renderPreview(dir, strokes);
            } finally {
                drawn.countDown();
            }
        });
        try {
            drawn.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =============================================================== helpers

    private static void copyFiles(Path from, Path to) {
        try (Stream<Path> files = Files.list(from)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, to.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not copy a note's files", e);
        }
    }

    /** Where an encrypted group (or sent) note's file is cached: the phone's very tag. */
    static String groupTag(String groupId, String name) {
        return "gn_" + groupId + "_" + key(name);
    }

    /** Database keys can't hold . # $ [ ] / ; the real name travels in the entry. */
    static String key(String name) {
        return name.replaceAll("[.#$\\[\\]/]", "_");
    }

    /** A file name from the account is only trusted to name a file inside the note's folder. */
    static boolean safeName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".")
                && !name.equals("..") && name.length() <= 200;
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "mp4", "m4v" -> "video/mp4";
            case "3gp" -> "video/3gpp";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "m4a", "aac" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            default -> NoteData.isVideo(name) ? "video/mp4" : "application/octet-stream";
        };
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static JSONObject json(String s) {
        if (s == null || s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
