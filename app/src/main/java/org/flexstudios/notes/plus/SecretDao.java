package org.flexstudios.notes.plus;

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
    List<SecretEntity> getSecretsForVaultDirect(int vaultId);

    // ---- The gallery and the viewer read these light rows, newest first, in one query.
    // ---- All three are served by index_secrets_vaultId_dateTaken_dateAdded.

    String ROW = "SELECT fileName, isVideo, dateTaken, isFavourite, durationMs FROM secrets ";
    String NEWEST_FIRST = " ORDER BY dateTaken DESC, dateAdded DESC";

    @Query(ROW + "WHERE vaultId = :vaultId" + NEWEST_FIRST)
    List<SecretItem> galleryRows(int vaultId);

    @Query(ROW + "WHERE vaultId = :vaultId AND isFavourite = 1" + NEWEST_FIRST)
    List<SecretItem> favouriteRows(int vaultId);

    @Query(ROW + "WHERE vaultId = :vaultId AND albumId = :albumId" + NEWEST_FIRST)
    List<SecretItem> albumRows(int vaultId, int albumId);

    @Query("UPDATE secrets SET durationMs = :durationMs WHERE fileName = :fileName")
    void setDuration(String fileName, long durationMs);

    @Query("UPDATE secrets SET isFavourite = :favourite WHERE fileName IN (:fileNames)")
    void setFavourite(List<String> fileNames, boolean favourite);

    @Query("UPDATE secrets SET albumId = :albumId WHERE fileName IN (:fileNames)")
    void setAlbum(List<String> fileNames, Integer albumId);

    @Query("SELECT * FROM secrets WHERE fileName IN (:fileNames)")
    List<SecretEntity> getByFileNames(List<String> fileNames);

    @Query("SELECT * FROM secrets WHERE id = :id")
    SecretEntity getSecretById(int id);
    
    @Query("SELECT * FROM secrets WHERE fileName = :fileName LIMIT 1")
    SecretEntity getSecretByFileName(String fileName);

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

    @Query("UPDATE secrets SET syncedLocally = 1")
    void markAllAsSynced();

    @Query("SELECT * FROM secrets")
    List<SecretEntity> getAllSecretsDirect();
}