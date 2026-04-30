package org.flexstudios.notes.plus;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface SecretDao {
    @Insert
    long insert(SecretEntity secret);

    @Update
    void update(SecretEntity secret);

    @Delete
    void delete(SecretEntity secret);

    @Query("SELECT * FROM secrets ORDER BY dateTaken DESC, dateAdded DESC")
    LiveData<List<SecretEntity>> getAllSecrets();

    @Query("SELECT * FROM secrets ORDER BY dateTaken DESC, dateAdded DESC")
    List<SecretEntity> getAllSecretsDirect();

    @Query("SELECT * FROM secrets WHERE id = :id")
    SecretEntity getSecretById(int id);
    
    @Query("SELECT * FROM secrets WHERE fileName = :fileName LIMIT 1")
    SecretEntity getSecretByFileName(String fileName);
}