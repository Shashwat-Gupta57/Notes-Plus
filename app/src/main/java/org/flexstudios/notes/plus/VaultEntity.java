package org.flexstudios.notes.plus;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "vaults")
public class VaultEntity {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String name;
    private String lockType; // PIN, PASSWORD, PATTERN
    private String lockValue;
    private boolean isMain;
    private int sortOrder;
    
    // Background customization
    private String bgType; // "DEFAULT", "COLOR", "IMAGE"
    private String bgValue; // Hex color or filename

    public VaultEntity(String name, String lockType, String lockValue, boolean isMain, int sortOrder) {
        this.name = name;
        this.lockType = lockType;
        this.lockValue = lockValue;
        this.isMain = isMain;
        this.sortOrder = sortOrder;
        this.bgType = "DEFAULT";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLockType() { return lockType; }
    public void setLockType(String lockType) { this.lockType = lockType; }
    public String getLockValue() { return lockValue; }
    public void setLockValue(String lockValue) { this.lockValue = lockValue; }
    public boolean isMain() { return isMain; }
    public void setMain(boolean main) { isMain = main; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getBgType() { return bgType; }
    public void setBgType(String bgType) { this.bgType = bgType; }
    public String getBgValue() { return bgValue; }
    public void setBgValue(String bgValue) { this.bgValue = bgValue; }
}