package org.flexstudios.notes.plus;

import androidx.annotation.NonNull;

/**
 * One cell of the gallery and one page of the viewer: only the columns they draw.
 *
 * Room fills it straight from an indexed projection ({@link SecretDao#galleryRows}), so a vault
 * of tens of thousands of items costs a few megabytes and one query, and the whole list can be
 * held at once. That is what makes the fast scroller, "select all" and date headers exact
 * instead of applying to whichever page happened to be loaded.
 */
public class SecretItem {
    @NonNull
    public final String fileName;
    public final boolean isVideo;
    public final long dateTaken;
    public final boolean isFavourite;
    /** 0 until known; videos imported before 7.50 learn it when their thumbnail is built. */
    public final long durationMs;

    public SecretItem(@NonNull String fileName, boolean isVideo, long dateTaken,
                      boolean isFavourite, long durationMs) {
        this.fileName = fileName;
        this.isVideo = isVideo;
        this.dateTaken = dateTaken;
        this.isFavourite = isFavourite;
        this.durationMs = durationMs;
    }

    /** Same item, same pixels: what the list differ needs to skip a rebind. */
    boolean sameContentAs(SecretItem other) {
        return isFavourite == other.isFavourite && durationMs == other.durationMs
                && dateTaken == other.dateTaken && isVideo == other.isVideo;
    }
}
