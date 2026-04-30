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

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    LiveData<List<NoteEntity>> getAllNotes();

    @Query("SELECT * FROM notes WHERE id = :noteId")
    NoteEntity getNoteById(int noteId);
}