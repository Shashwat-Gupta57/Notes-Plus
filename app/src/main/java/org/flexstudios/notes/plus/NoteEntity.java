package org.flexstudios.notes.plus;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One note of any kind. {@link #type} says which editor owns it (see {@link NoteData}).
 *
 * {@code content} is always plain, searchable text: the body of a text or journal note, the
 * items of a list, the node labels of a mind map, the titles of recordings. Structured state
 * lives in {@code data} as JSON, and media (photos, sketches, recordings) in the note's own
 * folder, {@link NoteData#dir}.
 *
 * The sync columns belong to {@link NoteSync}: {@code uuid} is the note's identity in the
 * account (row ids differ per phone), {@code account} the uid it syncs to (null: this phone
 * only), {@code rev} the server revision this copy is based on, {@code dirty} unpushed edits,
 * {@code deleted} a tombstone waiting to be pushed, and {@code media} the Drive manifest of its
 * files. Editors never write them; they save through {@link NoteDao#saveContent}.
 *
 * {@code archived} and {@code folder} are how the note is filed: off the main grid, and under a
 * folder name (null: none). Both sync with my own notes, never with a group or sent note's copy.
 */
@Entity(tableName = "notes", indices = {@Index({"pinned", "timestamp"}), @Index(value = "uuid", unique = true)})
public class NoteEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String title;
    private String content;
    private long timestamp;
    private String type = NoteData.TEXT;
    private String data;
    /** Card tint, an index into {@link NoteData#COLORS}; 0 is the default surface. */
    private int color;
    private boolean pinned;

    private String uuid = NoteSync.newUuid();
    private String account;
    private long rev;
    private boolean dirty = true;
    private boolean deleted;
    private String media;
    /** A group note: its id online ({@code groupNotes/{groupId}}), shared with its members. */
    private String groupId;
    /** Members as JSON {uid: username}, for the card and the members screen. */
    private String groupMembers;
    /** Mine, sent to people ({@code sentNotes/{uuid}}): who, as JSON {uid: username}. */
    private String sentTo;
    /** The sent copy's files: encrypted and shared by link, as a manifest like {@link #media}. */
    private String sentMedia;
    /** Someone else's note sent to me: their @username. Read-only here; never pushed. */
    private String sharedFrom;
    private boolean archived;
    private String folder;

    public NoteEntity(String title, String content, long timestamp) {
        this.title = title;
        this.content = content;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getType() { return type == null ? NoteData.TEXT : type; }
    public void setType(String type) { this.type = type; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public long getRev() { return rev; }
    public void setRev(long rev) { this.rev = rev; }
    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public String getMedia() { return media; }
    public void setMedia(String media) { this.media = media; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getGroupMembers() { return groupMembers; }
    public void setGroupMembers(String groupMembers) { this.groupMembers = groupMembers; }
    public String getSentTo() { return sentTo; }
    public void setSentTo(String sentTo) { this.sentTo = sentTo; }
    public String getSentMedia() { return sentMedia; }
    public void setSentMedia(String sentMedia) { this.sentMedia = sentMedia; }
    public String getSharedFrom() { return sharedFrom; }
    public void setSharedFrom(String sharedFrom) { this.sharedFrom = sharedFrom; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    /** Someone else's note, sent to me. */
    boolean isReceived() { return sharedFrom != null; }

    /** For the list differ: anything a card shows. */
    boolean sameContentAs(NoteEntity o) {
        return java.util.Objects.equals(title, o.title) && java.util.Objects.equals(content, o.content)
                && java.util.Objects.equals(data, o.data) && timestamp == o.timestamp
                && color == o.color && pinned == o.pinned && getType().equals(o.getType())
                && java.util.Objects.equals(groupMembers, o.groupMembers)
                && java.util.Objects.equals(sentTo, o.sentTo) && java.util.Objects.equals(sharedFrom, o.sharedFrom)
                && archived == o.archived && java.util.Objects.equals(folder, o.folder);
    }
}
