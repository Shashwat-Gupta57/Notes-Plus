package org.flexstudios.notes.plus;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "secrets")
public class SecretEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String fileName;
    private String originalPath;
    private long dateTaken;
    private long dateAdded;
    private boolean isVideo;
    private int vaultId;

    public SecretEntity(String fileName, String originalPath, long dateTaken, long dateAdded, boolean isVideo, int vaultId) {
        this.fileName = fileName;
        this.originalPath = originalPath;
        this.dateTaken = dateTaken;
        this.dateAdded = dateAdded;
        this.isVideo = isVideo;
        this.vaultId = vaultId;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public long getDateTaken() { return dateTaken; }
    public void setDateTaken(long dateTaken) { this.dateTaken = dateTaken; }
    public long getDateAdded() { return dateAdded; }
    public void setDateAdded(long dateAdded) { this.dateAdded = dateAdded; }
    public boolean isVideo() { return isVideo; }
    public void setVideo(boolean video) { isVideo = video; }
    public int getVaultId() { return vaultId; }
    public void setVaultId(int vaultId) { this.vaultId = vaultId; }
}