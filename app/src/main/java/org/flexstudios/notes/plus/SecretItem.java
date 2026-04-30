package org.flexstudios.notes.plus;

import java.io.File;

public class SecretItem {
    private File file;
    private boolean isVideo;

    public SecretItem(File file, boolean isVideo) {
        this.file = file;
        this.isVideo = isVideo;
    }

    public File getFile() {
        return file;
    }

    public boolean isVideo() {
        return isVideo;
    }

    public String getName() {
        return file.getName();
    }
}