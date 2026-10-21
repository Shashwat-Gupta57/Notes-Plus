package org.flexstudios.notes.plus;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface NoteDao {
    @Insert
    long insert(NoteEntity note);

    @Update
    void update(NoteEntity note);

    @Delete
    void delete(NoteEntity note);

    /** Pinned first, then newest. Served by index_notes_pinned_timestamp. Tombstones hidden. */
    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY pinned DESC, timestamp DESC")
    LiveData<List<NoteEntity>> getAllNotes();

    /** For pickers (linking a mind-map idea to a note). */
    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY pinned DESC, timestamp DESC")
    List<NoteEntity> getAllDirect();

    @Query("SELECT * FROM notes WHERE id = :noteId")
    NoteEntity getNoteById(int noteId);

    @Query("SELECT * FROM notes WHERE uuid = :uuid")
    NoteEntity getByUuid(String uuid);

    /**
     * An editor's save: content only, so it can never overwrite sync state that changed while
     * the editor was open.
     */
    @Query("UPDATE notes SET title = :title, content = :content, data = :data, color = :color, "
            + "pinned = :pinned, timestamp = :timestamp, dirty = 1 WHERE id = :noteId")
    void saveContent(int noteId, String title, String content, String data, int color, boolean pinned, long timestamp);

    @Query("UPDATE notes SET pinned = :pinned, dirty = 1 WHERE id = :noteId")
    void setPinned(int noteId, boolean pinned);

    @Query("UPDATE notes SET color = :color, dirty = 1 WHERE id = :noteId")
    void setColor(int noteId, int color);

    /** Hidden at once; removed for good by {@link NoteSync} once the account has heard of it. */
    @Query("UPDATE notes SET deleted = :deleted, dirty = 1 WHERE id = :noteId")
    void setDeleted(int noteId, boolean deleted);

    // ------------------------------------------------------------------- sync

    @Query("SELECT * FROM notes WHERE account = :account AND dirty = 1")
    List<NoteEntity> dirtyNotes(String account);

    @Query("SELECT COUNT(*) FROM notes WHERE account = :account AND dirty = 1")
    int countDirty(String account);

    /** Notes on this phone outside {@code account}: offered when signing in. */
    @Query("SELECT COUNT(*) FROM notes WHERE deleted = 0 AND (account IS NULL OR account != :account)")
    int countOutside(String account);

    /** Brings every other note on this phone into {@code account}, as new notes there. */
    @Query("UPDATE notes SET account = :account, rev = 0, dirty = 1, media = NULL "
            + "WHERE account IS NULL OR account != :account")
    void adopt(String account);

    /** Leaving an account but keeping its notes: they become this phone's own again. */
    @Query("UPDATE notes SET account = NULL, rev = 0, dirty = 1, media = NULL WHERE account = :account")
    void release(String account);

    @Query("SELECT * FROM notes WHERE account = :account")
    List<NoteEntity> notesOf(String account);

    /** Deleted notes nobody else needs to hear about: purge right away. */
    @Query("SELECT * FROM notes WHERE deleted = 1 AND account IS NULL")
    List<NoteEntity> localTombstones();

    /** The server took {@code rev}; still dirty if the note was edited again meanwhile. */
    @Query("UPDATE notes SET rev = :rev, dirty = CASE WHEN timestamp = :timestamp THEN 0 ELSE dirty END "
            + "WHERE id = :noteId")
    void markSynced(int noteId, long rev, long timestamp);

    /** A new Drive manifest, which the account has to hear about. */
    @Query("UPDATE notes SET media = :media, dirty = 1 WHERE id = :noteId")
    void setMedia(int noteId, String media);

    @Query("DELETE FROM notes WHERE id = :noteId")
    void purge(int noteId);
}
