package org.flexstudios.notes.plus;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AlbumDao {
    @Insert
    long insert(AlbumEntity album);

    @Update
    void update(AlbumEntity album);

    @Delete
    void delete(AlbumEntity album);

    @Query("SELECT * FROM albums WHERE vaultId = :vaultId ORDER BY id DESC")
    LiveData<List<AlbumEntity>> getAlbumsForVault(int vaultId);

    @Query("SELECT * FROM albums WHERE vaultId = :vaultId ORDER BY id DESC")
    List<AlbumEntity> getAlbumsForVaultDirect(int vaultId);

    @Query("SELECT * FROM albums WHERE id = :id")
    AlbumEntity getAlbumById(int id);

    /** Cross-device identity lookup; replaces matching albums by display name. */
    @Query("SELECT * FROM albums WHERE uuid = :uuid LIMIT 1")
    AlbumEntity getAlbumByUuid(String uuid);

    @Query("DELETE FROM albums WHERE vaultId = :vaultId")
    void deleteAlbumsForVault(int vaultId);

    @Query("SELECT * FROM albums")
    List<AlbumEntity> getAllAlbumsDirect();

    @Query("DELETE FROM albums")
    void deleteAllAlbums();
}