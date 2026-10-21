package org.flexstudios.notes.plus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Produces gallery thumbnails without ever putting plaintext media on disk.
 *
 * A small JPEG is derived once from the original, stored encrypted under filesDir/thumbs2
 * (see {@link VaultCrypto}), and decoded into an in-memory LRU on demand. Video frames are
 * read through a decrypting {@link MediaDataSource}, so only the bytes the decoder asks for
 * are decrypted and no temp copy exists. A video's duration is measured on the same pass and
 * written to its row, for the grid's badge.
 */
public final class ThumbnailStore {

    private static final String TAG = "ThumbnailStore";
    /**
     * Versioned: v1 ("thumbs") was 384 px and ignored EXIF rotation, so portrait photos showed
     * sideways. Bumping the directory regenerates them once, lazily.
     */
    private static final String THUMB_DIR = "thumbs2";
    private static final String LEGACY_THUMB_DIR = "thumbs";
    /** Longest edge. Sharp at two columns on a 1440 px wide screen. */
    private static final int TARGET_PX = 512;
    private static final int JPEG_QUALITY = 82;
    /** Enough for the JPEG/HEIF header so the bounds pass can be rewound, not re-decrypted. */
    private static final int HEADER_PEEK_BYTES = 256 * 1024;

    public interface Callback {
        void onThumbnail(Bitmap bitmap);

        void onFailed();

        /** A video's length became known. Main thread. */
        default void onDuration(long durationMs) {}
    }

    private static final Callback IGNORE = new Callback() {
        @Override
        public void onThumbnail(Bitmap bitmap) {}

        @Override
        public void onFailed() {}
    };

    private static volatile ThumbnailStore instance;

    private final Context appContext;
    private final LruCache<String, Bitmap> memoryCache;
    private final Map<String, Long> durations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private ThumbnailStore(Context context) {
        this.appContext = context.getApplicationContext();
        // RGB_565 halves each thumbnail, so a sixth of the heap holds a few hundred of them:
        // several screens of scroll-back without touching disk.
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        this.memoryCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        executor.execute(() -> VaultCrypto.deleteRecursive(new File(appContext.getFilesDir(), LEGACY_THUMB_DIR)));
    }

    public static ThumbnailStore get(Context context) {
        if (instance == null) {
            synchronized (ThumbnailStore.class) {
                if (instance == null) instance = new ThumbnailStore(context);
            }
        }
        return instance;
    }

    /** Non-blocking cache probe, so a bind can paint instantly on a scroll-back. */
    public Bitmap peek(String fileName) {
        return memoryCache.get(fileName);
    }

    /** A duration measured this session but not yet reflected in the row the caller holds. */
    public long peekDuration(String fileName) {
        Long d = durations.get(fileName);
        return d == null ? 0 : d;
    }

    public Future<?> load(String fileName, boolean isVideo, Callback callback) {
        return load(fileName, isVideo, false, callback);
    }

    /**
     * Resolves the thumbnail for a vault file. Callbacks run on the main thread. Returns the
     * pending task so a recycled cell can cancel it, or null if served from memory.
     *
     * @param measureDuration for a video whose length is not yet recorded
     */
    public Future<?> load(final String fileName, final boolean isVideo, final boolean measureDuration,
                          final Callback callback) {
        Bitmap cached = memoryCache.get(fileName);
        boolean needDuration = isVideo && measureDuration && !durations.containsKey(fileName);
        if (cached != null && !needDuration) {
            callback.onThumbnail(cached);
            return null;
        }
        return executor.submit(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = cached != null ? cached : resolveBlocking(fileName, isVideo);
                if (needDuration) measureDuration(fileName);
            } catch (Throwable t) {
                Log.w(TAG, "Thumbnail failed for " + fileName, t);
            }
            if (bitmap != null) memoryCache.put(fileName, bitmap);
            final Bitmap result = bitmap;
            final long duration = peekDuration(fileName);
            main.post(() -> {
                if (result != null) callback.onThumbnail(result);
                else callback.onFailed();
                if (duration > 0) callback.onDuration(duration);
            });
        });
    }

    /** Warms the memory cache for cells about to scroll into view. */
    public void prefetch(SecretItem item) {
        if (memoryCache.get(item.fileName) == null) load(item.fileName, item.isVideo, IGNORE);
    }

    /** Drops the stored and cached thumbnail for a file that no longer exists. */
    public void invalidate(String fileName) {
        memoryCache.remove(fileName);
        File thumb = new File(thumbDir(), fileName);
        if (thumb.exists() && !thumb.delete()) Log.w(TAG, "Could not delete thumb " + fileName);
    }

    /** Removes every stored thumbnail. Used after a restore replaces the vault wholesale. */
    public void clearAll() {
        memoryCache.evictAll();
        VaultCrypto.wipeContents(thumbDir());
    }

    // ---------------------------------------------------------------- internals

    private Bitmap resolveBlocking(String fileName, boolean isVideo) throws Exception {
        // Interning gives a per-file lock, so two cells for the same item cannot both
        // generate the same thumbnail.
        synchronized (fileName.intern()) {
            Bitmap cached = memoryCache.get(fileName);
            if (cached != null) return cached;

            File thumbFile = new File(thumbDir(), fileName);
            if (thumbFile.exists() && thumbFile.length() > 0) {
                Bitmap stored = decodeEncrypted(thumbFile);
                if (stored != null) return stored;
                // Corrupt or truncated: fall through and rebuild it.
                if (!thumbFile.delete()) Log.w(TAG, "Could not delete stale thumb " + fileName);
            }

            File source = new File(VaultMedia.vaultDir(appContext), fileName);
            if (!source.exists()) return null;

            Bitmap generated = isVideo ? frameFromVideo(source) : downscaleImage(source);
            if (generated == null) return null;

            VaultCrypto.write(appContext, thumbFile,
                    out -> generated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out));
            return generated;
        }
    }

    /**
     * Decodes an image without materialising the full-size bitmap, upright.
     *
     * The bounds pass reads only the header, so the stream is buffered and rewound rather
     * than decrypted twice. Orientation comes from a separate short read of the EXIF block.
     */
    private Bitmap downscaleImage(File encryptedSource) throws Exception {
        int rotation = exifRotation(encryptedSource);
        try (InputStream raw = VaultCrypto.openRead(appContext, encryptedSource);
             BufferedInputStream in = new BufferedInputStream(raw, HEADER_PEEK_BYTES)) {
            in.mark(HEADER_PEEK_BYTES);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded;
            try {
                in.reset();
                decoded = BitmapFactory.decodeStream(in, null, opts);
            } catch (IOException headerTooLarge) {
                // Metadata ran past the peek window (rare, e.g. huge embedded previews).
                try (InputStream again = VaultCrypto.openRead(appContext, encryptedSource)) {
                    decoded = BitmapFactory.decodeStream(again, null, opts);
                }
            }
            return rotate(fit(decoded), rotation);
        }
    }

    private int exifRotation(File encryptedSource) {
        try (InputStream in = VaultCrypto.openRead(appContext, encryptedSource)) {
            return new ExifInterface(in).getRotationDegrees();
        } catch (Exception e) {
            return 0; // No EXIF (PNG, GIF, screenshots): upright as stored.
        }
    }

    /** Reads the first frame and the duration straight out of the encrypted file. */
    private Bitmap frameFromVideo(File encryptedSource) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (MediaDataSource source = VaultCrypto.mediaDataSource(appContext, encryptedSource)) {
            retriever.setDataSource(source);
            recordDuration(encryptedSource.getName(), retriever);
            return fit(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC));
        } finally {
            release(retriever);
        }
    }

    /** Duration only; reads the container header, a few kilobytes. */
    private void measureDuration(String fileName) {
        if (durations.containsKey(fileName)) return;
        File source = new File(VaultMedia.vaultDir(appContext), fileName);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (MediaDataSource ds = VaultCrypto.mediaDataSource(appContext, source)) {
            retriever.setDataSource(ds);
            recordDuration(fileName, retriever);
        } catch (Exception e) {
            durations.put(fileName, 0L); // Unreadable; don't try again this session.
        } finally {
            release(retriever);
        }
    }

    private void recordDuration(String fileName, MediaMetadataRetriever retriever) {
        try {
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = value == null ? 0 : Long.parseLong(value);
            durations.put(fileName, ms);
            if (ms > 0) AppDatabase.getInstance(appContext).secretDao().setDuration(fileName, ms);
        } catch (Exception e) {
            Log.w(TAG, "No duration for " + fileName, e);
        }
    }

    private static void release(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Exception ignored) {
            // Nothing actionable.
        }
    }

    private Bitmap decodeEncrypted(File thumbFile) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = VaultCrypto.openRead(appContext, thumbFile)) {
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private File thumbDir() {
        File dir = new File(appContext.getFilesDir(), THUMB_DIR);
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create thumb dir");
        return dir;
    }

    private static int sampleSizeFor(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= TARGET_PX) sample *= 2;
        return sample;
    }

    private static Bitmap fit(Bitmap source) {
        if (source == null) return null;
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= TARGET_PX) return source;
        float scale = TARGET_PX / (float) longest;
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private static Bitmap rotate(Bitmap source, int degrees) {
        if (source == null || degrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) source.recycle();
        return rotated;
    }
}
