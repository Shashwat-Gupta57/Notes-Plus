package org.flexstudios.notes.plus;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface VaultDao {
    @Insert
    long insert(VaultEntity vault);

    @Update
    void update(VaultEntity vault);

    @Delete
    void delete(VaultEntity vault);

    @Query("SELECT * FROM vaults ORDER BY sortOrder ASC")
    LiveData<List<VaultEntity>> getAllVaults();

    @Query("SELECT * FROM vaults ORDER BY sortOrder ASC")
    List<VaultEntity> getAllVaultsDirect();

    @Query("SELECT * FROM vaults WHERE id = :id")
    VaultEntity getVaultById(int id);

    @Query("SELECT * FROM vaults WHERE isMain = 1 LIMIT 1")
    VaultEntity getMainVault();

    /** Cross-device identity lookup; replaces matching vaults by display name. */
    @Query("SELECT * FROM vaults WHERE uuid = :uuid LIMIT 1")
    VaultEntity getVaultByUuid(String uuid);

    @Query("SELECT COUNT(*) FROM secrets WHERE vaultId = :vaultId")
    int getSecretCountForVault(int vaultId);

    @Query("DELETE FROM vaults")
    void deleteAllVaults();
}