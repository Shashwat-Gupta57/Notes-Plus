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

    /** Pinned first, then newest. Served by index_notes_pinned_timestamp. */
    @Query("SELECT * FROM notes ORDER BY pinned DESC, timestamp DESC")
    LiveData<List<NoteEntity>> getAllNotes();

    /** For pickers (linking a mind-map idea to a note). */
    @Query("SELECT * FROM notes ORDER BY pinned DESC, timestamp DESC")
    List<NoteEntity> getAllDirect();

    @Query("SELECT * FROM notes WHERE id = :noteId")
    NoteEntity getNoteById(int noteId);

    @Query("UPDATE notes SET pinned = :pinned WHERE id = :noteId")
    void setPinned(int noteId, boolean pinned);

    @Query("UPDATE notes SET color = :color WHERE id = :noteId")
    void setColor(int noteId, int color);
}
