package org.flexstudios.notes.plus;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every operation that moves a media item into, out of, or within the vault.
 *
 * These used to be re-implemented, slightly differently, in the gallery, the viewer and the
 * settings screen: one path scheduled a Drive backup after a restore and another didn't, one
 * invalidated the thumbnail and another left it behind. Each operation now exists once.
 *
 * No method here touches the UI or needs a special storage permission. Originals are removed
 * with {@link MediaStore#createDeleteRequest} (the caller shows the system confirmation),
 * and restored items are written through MediaStore, which needs no permission from Q on.
 */
public final class VaultMedia {

    private static final String TAG = "VaultMedia";
    private static final Pattern WHATSAPP_DATE = Pattern.compile("(IMG|VID)-(\\d{8})-WA");

    private VaultMedia() {}

    /** filesDir/vault, created on first use with a .nomedia marker. */
    public static File vaultDir(Context context) {
        File dir = new File(context.getFilesDir(), "vault");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create the vault directory");
        File noMedia = new File(dir, ".nomedia");
        if (!noMedia.exists()) {
            try {
                if (!noMedia.createNewFile()) Log.w(TAG, "Could not create .nomedia");
            } catch (IOException e) {
                Log.w(TAG, "Could not create .nomedia", e);
            }
        }
        return dir;
    }

    public static File fileOf(Context context, SecretEntity entity) {
        return new File(vaultDir(context), entity.getFileName());
    }

    // ------------------------------------------------------------------- import

    /** What became of one imported item. */
    public static final class Imported {
        public final SecretEntity entity;
        /** The original's MediaStore row, for deletion; null when it cannot be removed. */
        public final Uri original;

        Imported(SecretEntity entity, Uri original) {
            this.entity = entity;
            this.original = original;
        }
    }

    /**
     * Encrypts one picked item into the vault and records it.
     *
     * The original is left untouched: it is only safe to remove once the vault copy is
     * durably on disk, which {@link VaultCrypto#write} guarantees by the time this returns,
     * and the user has to confirm that removal anyway.
     */
    public static Imported importItem(Context context, Uri uri, int vaultId, Integer albumId)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String mimeType = resolver.getType(uri);
        boolean isVideo = mimeType != null && mimeType.startsWith("video");

        String path = queryString(resolver, uri, MediaStore.MediaColumns.DATA);
        String displayName = queryString(resolver, uri, OpenableColumns.DISPLAY_NAME);
        // Keep a path even when the provider hides the real one, so a later restore can
        // still give the file its name back.
        String originalPath = path != null ? path
                : displayName != null ? defaultRestoreDir(isVideo) + "/" + displayName : null;

        long dateTaken = recoverDateTaken(context, uri, path, isVideo);
        long dateAdded = System.currentTimeMillis();
        String fileName = (isVideo ? "VID_" : "IMG_") + dateAdded + "_"
                + UUID.randomUUID().toString().substring(0, 8);

        File target = new File(vaultDir(context), fileName);
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open " + uri);
            VaultCrypto.write(context, target, in);
        }

        SecretEntity entity = new SecretEntity(fileName, originalPath, dateTaken, dateAdded, isVideo, vaultId);
        entity.setAlbumId(albumId);
        if (isVideo) {
            // Free from MediaStore; otherwise the thumbnail pass measures it later.
            Long duration = queryLong(resolver, uri, MediaStore.MediaColumns.DURATION);
            if (duration != null && duration > 0) entity.setDurationMs(duration);
        }
        try {
            AppDatabase.getInstance(context).secretDao().insert(entity);
        } catch (RuntimeException e) {
            // Never leave an encrypted file that no row points at.
            if (!target.delete()) Log.w(TAG, "Could not remove orphan " + fileName);
            throw e;
        }
        return new Imported(entity, mediaStoreUri(context, uri, path));
    }

    /** The MediaStore row behind a picked item, or null when it is not one we can delete. */
    private static Uri mediaStoreUri(Context context, Uri picked, String path) {
        if (MediaStore.AUTHORITY.equals(picked.getAuthority())) return picked;
        if (path == null) return null;
        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor c = context.getContentResolver().query(files,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DATA + "=?", new String[]{path}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(files, c.getLong(0));
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve the original of " + picked, e);
        }
        return null;
    }

    private static long recoverDateTaken(Context context, Uri uri, String path, boolean isVideo) {
        if (!isVideo) {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    String date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    if (date == null) date = exif.getAttribute(ExifInterface.TAG_DATETIME);
                    if (date != null) {
                        Date d = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(date);
                        if (d != null) return d.getTime();
                    }
                }
            } catch (Exception ignored) {
                // No usable EXIF; fall through to the other sources.
            }
        }
        Long taken = queryLong(context.getContentResolver(), uri, MediaStore.MediaColumns.DATE_TAKEN);
        if (taken != null && taken > 0) return taken;
        if (path != null) {
            Matcher matcher = WHATSAPP_DATE.matcher(new File(path).getName());
            if (matcher.find()) {
                try {
                    Date d = new SimpleDateFormat("yyyyMMdd", Locale.US).parse(matcher.group(2));
                    if (d != null) return d.getTime();
                } catch (Exception ignored) {
                    // Not a real date after all.
                }
            }
            File f = new File(path);
            if (f.exists()) return f.lastModified();
        }
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ restore

    /**
     * Puts an item back into the phone's gallery and removes it from the vault.
     *
     * From Android 10 the file is written through MediaStore, which needs no permission and
     * makes it appear in gallery apps immediately. Below that it goes back to its original
     * path, never overwriting a file that has since taken that name.
     */
    public static void restoreToDevice(Context context, SecretEntity entity) throws Exception {
        File source = fileOf(context, entity);
        if (!source.exists()) throw new IOException("Vault file is missing");
        String name = displayNameOf(entity);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            restoreViaMediaStore(context, entity, source, name);
        } else {
            File target = entity.getOriginalPath() != null
                    ? new File(entity.getOriginalPath())
                    : new File(Environment.getExternalStoragePublicDirectory(
                            entity.isVideo() ? Environment.DIRECTORY_MOVIES
                                    : Environment.DIRECTORY_PICTURES), name);
            target = unusedName(target);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            try (OutputStream out = new FileOutputStream(target)) {
                VaultCrypto.decryptTo(context, source, out);
            }
            if (entity.getDateTaken() != 0 && !target.setLastModified(entity.getDateTaken())) {
                Log.w(TAG, "Could not restore the original date of " + target.getName());
            }
            MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, null, null);
        }
        remove(context, entity);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private static void restoreViaMediaStore(Context context, SecretEntity entity, File source,
                                             String name) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        boolean video = entity.isVideo();
        Uri collection = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name, video));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirOf(entity));
        if (entity.getDateTaken() != 0) values.put(MediaStore.MediaColumns.DATE_TAKEN, entity.getDateTaken());
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = resolver.insert(collection, values);
        if (item == null) throw new IOException("The gallery refused the file");
        try {
            try (OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) throw new IOException("Could not write to the gallery");
                VaultCrypto.decryptTo(context, source, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, done, null, null);
        } catch (Exception e) {
            resolver.delete(item, null, null);
            throw e;
        }
    }

    /**
     * The directory, relative to shared storage, an item came from — if MediaStore allows
     * that kind of media there. Otherwise a sensible default.
     */
    private static String relativeDirOf(SecretEntity entity) {
        String fallback = defaultRestoreDir(entity.isVideo());
        String original = entity.getOriginalPath();
        if (original == null) return fallback;
        String root = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        String parent = new File(original).getParent();
        if (parent == null) return fallback;
        String relative = parent.startsWith(root) ? parent.substring(root.length())
                : parent.startsWith("/") ? null : parent;
        if (relative == null || relative.isEmpty()) return fallback;
        String top = relative.split("/")[0];
        boolean allowed = Environment.DIRECTORY_DCIM.equals(top)
                || Environment.DIRECTORY_PICTURES.equals(top)
                || (entity.isVideo() && Environment.DIRECTORY_MOVIES.equals(top));
        return allowed ? relative : fallback;
    }

    private static String defaultRestoreDir(boolean video) {
        return (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Restored";
    }

    private static File unusedName(File target) {
        if (!target.exists()) return target;
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            File candidate = new File(target.getParentFile(), base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
    }

    // ------------------------------------------------------------- delete/share

    /** Removes an item from the vault: row, encrypted file and thumbnail. */
    public static void remove(Context context, SecretEntity entity) {
        AppDatabase.getInstance(context).secretDao().delete(entity);
        File file = fileOf(context, entity);
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file.getName());
        ThumbnailStore.get(context).invalidate(entity.getFileName());
        // A delete is just "the next restore point won't include it": schedule one.
        if (SecurityHelper.isAutoSyncEnabled(context)) SyncWorker.requestBackup(context);
    }

    /** Decrypted copy in the share directory, named so the receiving app knows its type. */
    public static File exportForShare(Context context, SecretEntity entity) throws IOException {
        return VaultCrypto.exportForShare(context, fileOf(context, entity), extensionOf(entity));
    }

    // ------------------------------------------------------------------ helpers

    private static String displayNameOf(SecretEntity entity) {
        String original = entity.getOriginalPath();
        if (original != null) {
            String name = new File(original).getName();
            if (!name.isEmpty()) return name;
        }
        return entity.getFileName() + extensionOf(entity);
    }

    private static String extensionOf(SecretEntity entity) {
        String original = entity.getOriginalPath();
        if (original != null) {
            String name = new File(original).getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) return name.substring(dot);
        }
        return entity.isVideo() ? ".mp4" : ".jpg";
    }

    private static String mimeOf(String name, boolean video) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) return mime;
        return video ? "video/mp4" : "image/jpeg";
    }

    private static String queryString(ContentResolver resolver, Uri uri, String column) {
        try (Cursor c = resolver.query(uri, new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(column);
                if (index >= 0 && !c.isNull(index)) return c.getString(index);
            }
        } catch (Exception ignored) {
            // Providers differ in which columns they expose; a missing one is not an error.
        }
        return null;
    }

    private static Long queryLong(ContentResolver resolver, Uri uri, String column) {
        try (Cursor c = resolver.query(uri, new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(column);
                if (index >= 0 && !c.isNull(index)) return c.getLong(index);
            }
        } catch (Exception ignored) {
            // As above.
        }
        return null;
    }
}
