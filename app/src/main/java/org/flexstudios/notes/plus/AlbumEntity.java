package org.flexstudios.notes.plus;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "albums")
public class AlbumEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String name;
    private int vaultId;
    private String coverFileName;

    public AlbumEntity(String name, int vaultId, String coverFileName) {
        this.name = name;
        this.vaultId = vaultId;
        this.coverFileName = coverFileName;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getVaultId() { return vaultId; }
    public void setVaultId(int vaultId) { this.vaultId = vaultId; }
    public String getCoverFileName() { return coverFileName; }
    public void setCoverFileName(String coverFileName) { this.coverFileName = coverFileName; }
}