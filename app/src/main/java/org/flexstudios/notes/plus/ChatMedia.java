package org.flexstudios.notes.plus;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Photos and videos in chat, and chat backgrounds.
 *
 * A file is encrypted on this phone with its own random key, uploaded to the sender's Google
 * Drive, and shared as "anyone with the link". The message carries the Drive id and the key;
 * only the two members can read messages, so only they can turn the public file back into a
 * picture. Someone who finds the link, or browses the Drive, sees noise.
 *
 * On phones the file is only ever kept encrypted ({@link #cached}). Images are decrypted into
 * memory to show; a video is decrypted into {@link #plainDir} to play and removed after.
 */
final class ChatMedia {

    private static final String TAG = "ChatMedia";
    static final long MAX_BYTES = 100L * 1024 * 1024;
    /** keyId stamped in the header; each file has its own key, so it identifies nothing. */
    private static final String KEY_ID = "00000000000000c1";

    private ChatMedia() {}

    /** What a message (or background) says about its file. JSON in Room and online. */
    static final class Meta {
        String id;       // Drive file id, once uploaded
        String key;      // base64 AES-256 key
        String mime;
        String name;
        long size, w, h, dur;
        /** Voice notes: their waveform, comma-separated levels 0..1. */
        String wave;
        /** View once: shown a single time, then gone (from the phone, and from the sender's Drive). */
        boolean once;

        boolean isVideo() {
            return mime != null && mime.startsWith("video/");
        }

        boolean isAudio() {
            return mime != null && mime.startsWith("audio/");
        }

        float[] waveform() {
            if (wave == null || wave.isEmpty()) return new float[0];
            String[] parts = wave.split(",");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    out[i] = Float.parseFloat(parts[i]);
                } catch (NumberFormatException e) {
                    out[i] = 0;
                }
            }
            return out;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                if (id != null) o.put("id", id);
                o.put("key", key);
                o.put("mime", mime == null ? "application/octet-stream" : mime);
                if (name != null) o.put("name", name);
                o.put("size", size);
                if (w > 0) o.put("w", w);
                if (h > 0) o.put("h", h);
                if (dur > 0) o.put("dur", dur);
                if (wave != null) o.put("wave", wave);
                if (once) o.put("once", true);
            } catch (Exception ignored) {
                // Plain values only.
            }
            return o;
        }

        @Nullable
        static Meta parse(@Nullable String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JSONObject o = new JSONObject(json);
                Meta m = new Meta();
                m.id = o.optString("id", null);
                m.key = o.optString("key", null);
                m.mime = o.optString("mime", "application/octet-stream");
                m.name = o.optString("name", null);
                m.size = o.optLong("size");
                m.w = o.optLong("w");
                m.h = o.optLong("h");
                m.dur = o.optLong("dur");
                m.wave = o.optString("wave", null);
                m.once = o.optBoolean("once", false);
                return m.key == null ? null : m;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ============================================================= sending

    /**
     * Encrypts a picked file into the outbox under {@code tag}, ready to upload even after the
     * app closes. Blocking.
     */
    static Meta prepare(Context context, Uri source, String tag) throws Exception {
        Meta m = new Meta();
        m.mime = context.getContentResolver().getType(source);
        if (m.mime == null) m.mime = "application/octet-stream";
        try (android.database.Cursor c = context.getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                m.name = c.getString(0);
                if (!c.isNull(1)) m.size = c.getLong(1);
            }
        }
        if (m.size > MAX_BYTES) throw new IOException("Files up to 100 MB can be sent");
        describe(context, source, m);
        byte[] key = VaultCrypto.randomBytes(32);
        m.key = Base64.encodeToString(key, Base64.NO_WRAP);
        File target = cached(context, tag);
        File part = new File(target.getPath() + ".part");
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException("Could not open that file");
            VaultCrypto.encrypt(in, out, secret(key), KEY_ID);
        }
        if (!part.renameTo(target)) throw new IOException("Could not store the file");
        if (m.size == 0) m.size = target.length();
        return m;
    }

    /** A voice note just recorded: encrypted into the outbox with its length and waveform. Blocking. */
    static Meta prepareVoice(Context context, File recording, long durationMs, float[] levels, String tag) throws Exception {
        Meta m = prepare(context, Uri.fromFile(recording), tag);
        m.mime = "audio/mp4";
        m.name = null;
        m.dur = durationMs;
        StringBuilder wave = new StringBuilder();
        for (float level : levels) {
            if (wave.length() > 0) wave.append(',');
            wave.append(Math.round(Math.max(0, Math.min(1, level)) * 100) / 100f);
        }
        m.wave = wave.toString();
        return m;
    }

    private static void describe(Context context, Uri source, Meta m) {
        try {
            if (m.mime.startsWith("image/")) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = context.getContentResolver().openInputStream(source)) {
                    BitmapFactory.decodeStream(in, null, bounds);
                }
                m.w = Math.max(0, bounds.outWidth);
                m.h = Math.max(0, bounds.outHeight);
            } else if (m.mime.startsWith("video/")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(context, source);
                    m.dur = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    m.w = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    m.h = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                        // Released either way.
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No dimensions for the picked file", e);
        }
    }

    /** Uploads the encrypted file for {@code tag} and opens it to anyone with the link. */
    static String upload(Context context, DriveStore drive, String tag) throws IOException {
        File file = cached(context, tag);
        if (!file.exists()) throw new IOException("The file to send is gone");
        String root = drive.folder(DriveStore.BACKUP_PATH.split("/")[0], "root");
        String folder = drive.folder("Shared", root);
        String id = drive.putFile(file, tag + ".bin", "application/octet-stream", folder);
        drive.shareWithLink(id);
        return id;
    }

    // ============================================================== photos

    /**
     * A profile photo: shrunk to 512 px, encrypted, uploaded and opened to anyone with the
     * link. Kept here under {@link #photoTag}. Blocking; needs Drive.
     */
    static Meta uploadPhoto(Context context, DriveStore drive, Uri source) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= 512) options.inSampleSize *= 2;
        android.graphics.Bitmap bitmap;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IOException("That isn't a picture");
        File jpeg = new File(plainDir(context), "photo_" + System.nanoTime() + ".jpg");
        try (OutputStream out = new FileOutputStream(jpeg)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
        } finally {
            bitmap.recycle();
        }
        String temp = "ptmp_" + System.nanoTime();
        try {
            Meta meta = prepare(context, Uri.fromFile(jpeg), temp);
            meta.mime = "image/jpeg";
            meta.name = null;
            meta.id = upload(context, drive, temp);
            if (!cached(context, temp).renameTo(cached(context, photoTag(meta.id)))) forget(context, temp);
            return meta;
        } finally {
            VaultCrypto.deleteRecursive(jpeg);
        }
    }

    static String photoTag(String driveId) {
        return "p_" + driveId;
    }

    /**
     * Shows a profile photo as a circle: {"url":...} (a Google photo) or a custom one
     * (downloaded and decrypted). Calls {@code onNone} when there is no photo to show, so the
     * caller keeps its initial-letter look.
     */
    static void loadAvatar(android.app.Activity activity, android.widget.ImageView view, @Nullable String json,
                           Runnable onNone) {
        view.setTag(json);
        if (json == null) {
            onNone.run();
            return;
        }
        String url = null;
        try {
            url = new JSONObject(json).optString("url", null);
        } catch (Exception ignored) {
            // Not a URL photo.
        }
        if (url != null) {
            com.bumptech.glide.Glide.with(activity).load(url).circleCrop().into(view);
            return;
        }
        Meta meta = Meta.parse(json);
        if (meta == null || meta.id == null) {
            onNone.run();
            return;
        }
        int size = Math.max(view.getWidth(), Math.round(96 * activity.getResources().getDisplayMetrics().density));
        com.bumptech.glide.Glide.with(activity).clear(view);
        view.setImageDrawable(null);
        AppDatabase.IO.execute(() -> {
            android.graphics.Bitmap circle = null;
            try {
                byte[] bytes = decryptBytes(activity, photoTag(meta.id), meta);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
                circle = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't show a profile photo", e);
            }
            android.graphics.Bitmap shown = circle;
            activity.runOnUiThread(() -> {
                if (!java.util.Objects.equals(json, view.getTag()) || activity.isDestroyed()) return;
                if (shown == null) {
                    onNone.run();
                    return;
                }
                androidx.core.graphics.drawable.RoundedBitmapDrawable round =
                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(activity.getResources(), shown);
                round.setCircular(true);
                view.setImageDrawable(round);
            });
        });
    }

    // ============================================================= reading

    /** The encrypted copy on this phone, downloaded from its public link if needed. Blocking. */
    static File ensure(Context context, String tag, Meta m) throws IOException {
        File file = cached(context, tag);
        if (file.exists() && file.length() > 0) return file;
        if (m.id == null) throw new IOException("Not uploaded yet");
        File part = new File(file.getPath() + ".part");
        URL url = new URL("https://drive.usercontent.google.com/download?id=" + Uri.encode(m.id)
                + "&export=download&confirm=t");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Download failed (" + connection.getResponseCode() + ")");
            }
            String type = connection.getContentType();
            if (type != null && type.startsWith("text/html")) throw new IOException("The file is no longer shared");
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(part)) {
                VaultCrypto.copy(in, out);
            }
        } finally {
            connection.disconnect();
        }
        if (!part.renameTo(file)) throw new IOException("Could not store the download");
        return file;
    }

    /** Whole file decrypted into memory: for pictures. Blocking. */
    static byte[] decryptBytes(Context context, String tag, Meta m) throws Exception {
        File file = ensure(context, tag, m);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 32 << 20));
        try (InputStream in = new FileInputStream(file)) {
            VaultCrypto.decrypt(in, out, resolver(m));
        }
        return out.toByteArray();
    }

    /** Decrypted into {@code target} (a group note's own folder). Blocking. */
    static void decryptTo(Context context, String tag, Meta m, File target) throws Exception {
        File file = ensure(context, tag, m);
        File part = new File(target.getPath() + ".part");
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(part)) {
            VaultCrypto.decrypt(in, out, resolver(m));
        }
        if (!part.renameTo(target)) {
            VaultCrypto.deleteRecursive(part);
            throw new IOException("Could not save " + target.getName());
        }
    }

    /** Decrypted into a temporary file, for the video player. Delete it when done. Blocking. */
    static File decryptToTemp(Context context, String tag, Meta m) throws Exception {
        File file = ensure(context, tag, m);
        File plain = new File(plainDir(context), tag + (m.isVideo() ? ".mp4"
                : "audio/wav".equals(m.mime) || "audio/x-wav".equals(m.mime) ? ".wav" : m.isAudio() ? ".m4a" : ".bin"));
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(plain)) {
            VaultCrypto.decrypt(in, out, resolver(m));
        }
        return plain;
    }

    // ============================================================= storage

    /** Encrypted copies (sent and received). Safe at rest: useless without the message. */
    static File cached(Context context, String tag) {
        File dir = new File(context.getFilesDir(), "chat_media");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create " + dir);
        return new File(dir, tag.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    /** Decrypted videos while they play. Emptied whenever the chat space opens. */
    static File plainDir(Context context) {
        File dir = new File(context.getCacheDir(), "chat_plain");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Could not create " + dir);
        return dir;
    }

    static void sweepPlain(Context context) {
        VaultCrypto.wipeContents(plainDir(context));
    }

    static void forget(Context context, String tag) {
        VaultCrypto.deleteRecursive(cached(context, tag));
    }

    // ============================================================= helpers

    private static VaultCrypto.KeyResolver resolver(Meta m) {
        SecretKey key = secret(Base64.decode(m.key, Base64.NO_WRAP));
        return new VaultCrypto.KeyResolver() {
            @Override
            public SecretKey resolveDataKey(String keyId) {
                return key;
            }

            @Override
            public SecretKey resolveLegacyKey(byte[] salt) throws Exception {
                throw new IOException("Not a chat file");
            }
        };
    }

    private static SecretKey secret(byte[] key) {
        return new SecretKeySpec(key, "AES");
    }

    private static long parse(@Nullable String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
