package org.flexstudios.notes.plus;

import java.util.Objects;
import java.util.UUID;

/**
 * One note of any kind, the same row as the phone's {@code notes} table. {@link #type} says
 * which editor owns it (see {@link NoteData}).
 *
 * {@code content} is always plain, searchable text: the body of a text or journal note, the
 * items of a list, the node labels of a mind map, the titles of recordings. Structured state
 * lives in {@code data} as JSON, and media (photos, sketches, recordings) in the note's own
 * folder, {@link NoteData#dir}.
 *
 * {@code uuid} is 32 hex digits, as the database rules require. The sync columns ({@code uuid}, {@code account}, {@code rev}, {@code dirty}, {@code deleted},
 * {@code media}, {@code groupId}, {@code groupMembers}) mean exactly what they mean on the phone,
 * so account sync can be added without a schema change. Editors never write them; they save
 * through {@link NoteStore#saveContent}.
 */
public class NoteEntity {
    private int id;
    private String title;
    private String content;
    private long timestamp;
    private String type = NoteData.TEXT;
    private String data;
    /** Card tint, an index into {@link NoteData#COLORS}; 0 is the default surface. */
    private int color;
    private boolean pinned;

    private String uuid = UUID.randomUUID().toString().replace("-", "");
    private String account;
    private long rev;
    private boolean dirty = true;
    private boolean deleted;
    private String media;
    private String groupId;
    private String groupMembers;

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

    /** Anything a card shows: the grid only rebuilds cards for which this is false. */
    boolean sameContentAs(NoteEntity o) {
        return Objects.equals(title, o.title) && Objects.equals(content, o.content)
                && Objects.equals(data, o.data) && timestamp == o.timestamp
                && color == o.color && pinned == o.pinned && getType().equals(o.getType())
                && Objects.equals(groupMembers, o.groupMembers);
    }

    /** A detached copy, for handing to the writer thread. */
    NoteEntity copy() {
        NoteEntity c = new NoteEntity(title, content, timestamp);
        c.id = id;
        c.type = type;
        c.data = data;
        c.color = color;
        c.pinned = pinned;
        c.uuid = uuid;
        c.account = account;
        c.rev = rev;
        c.dirty = dirty;
        c.deleted = deleted;
        c.media = media;
        c.groupId = groupId;
        c.groupMembers = groupMembers;
        return c;
    }
}
