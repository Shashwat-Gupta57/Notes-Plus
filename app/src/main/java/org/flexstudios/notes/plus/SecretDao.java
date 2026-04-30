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

    @Query("SELECT * FROM secrets WHERE vaultId = :vaultId ORDER BY dateTaken DESC, dateAdded DESC")
    LiveData<List<SecretEntity>> getSecretsForVault(int vaultId);

    @Query("SELECT * FROM secrets WHERE vaultId = :vaultId ORDER BY dateTaken DESC, dateAdded DESC")
    List<SecretEntity> getSecretsForVaultDirect(int vaultId);

    @Query("SELECT * FROM secrets WHERE id = :id")
    SecretEntity getSecretById(int id);
    
    @Query("SELECT * FROM secrets WHERE fileName = :fileName LIMIT 1")
    SecretEntity getSecretByFileName(String fileName);

    @Query("SELECT * FROM secrets WHERE isFavourite = 1 AND vaultId = :vaultId ORDER BY dateTaken DESC")
    LiveData<List<SecretEntity>> getFavouritesForVault(int vaultId);

    @Query("SELECT * FROM secrets WHERE albumId = :albumId AND vaultId = :vaultId ORDER BY dateTaken DESC")
    LiveData<List<SecretEntity>> getSecretsForAlbum(int albumId, int vaultId);

    @Query("SELECT COUNT(*) FROM secrets WHERE isFavourite = 1 AND vaultId = :vaultId")
    int getFavouriteCount(int vaultId);

    @Query("SELECT COUNT(*) FROM secrets WHERE albumId = :albumId")
    int getSecretCountForAlbum(int albumId);

    @Query("SELECT * FROM secrets WHERE albumId = :albumId ORDER BY id DESC LIMIT 1")
    SecretEntity getLatestForAlbum(int albumId);
    
    @Query("SELECT * FROM secrets WHERE isFavourite = 1 AND vaultId = :vaultId ORDER BY id DESC LIMIT 1")
    SecretEntity getLatestFavourite(int vaultId);

    @Query("UPDATE secrets SET albumId = NULL WHERE albumId = :albumId")
    void unsetAlbumId(int albumId);
}