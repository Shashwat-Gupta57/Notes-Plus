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
 */
@Entity(tableName = "notes", indices = {@Index({"pinned", "timestamp"})})
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

    /** For the list differ: anything a card shows. */
    boolean sameContentAs(NoteEntity o) {
        return java.util.Objects.equals(title, o.title) && java.util.Objects.equals(content, o.content)
                && java.util.Objects.equals(data, o.data) && timestamp == o.timestamp
                && color == o.color && pinned == o.pinned && getType().equals(o.getType());
    }
}
