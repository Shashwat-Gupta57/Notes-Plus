package org.flexstudios.notes.plus;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {
    private List<AlbumItem> albums = new ArrayList<>();
    private OnAlbumClickListener listener;
    private OnAlbumLongClickListener longClickListener;

    public interface OnAlbumClickListener {
        void onAlbumClick(AlbumItem album);
    }

    public interface OnAlbumLongClickListener {
        void onAlbumLongClick(AlbumItem album);
    }

    public AlbumAdapter(OnAlbumClickListener listener, OnAlbumLongClickListener longClickListener) {
        this.listener = listener;
        this.longClickListener = longClickListener;
    }

    public void setAlbums(List<AlbumItem> albums) {
        this.albums = albums;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_album_card, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        AlbumItem item = albums.get(position);
        holder.name.setText(item.name);
        holder.count.setText(item.count + " items");

        holder.cover.setTag(item.coverFileName);
        if (item.coverFileName != null) {
            File encryptedFile = new File(holder.itemView.getContext().getFilesDir(), "vault/" + item.coverFileName);
            decryptAndLoadCover(encryptedFile, holder.cover, item.isVideo, item.coverFileName);
        } else {
            holder.cover.setImageResource(R.drawable.ic_empty_gallery);
            holder.cover.setScaleType(ImageView.ScaleType.CENTER);
        }

        holder.itemView.setOnClickListener(v -> listener.onAlbumClick(item));
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onAlbumLongClick(item);
                return true;
            }
            return false;
        });
    }

    private void decryptAndLoadCover(File encryptedFile, ImageView imageView, boolean isVideo, String fileName) {
        File tempFile = new File(imageView.getContext().getCacheDir(), "thumb_album_" + fileName);
        if (tempFile.exists() && tempFile.length() > 0) {
            loadThumbnail(tempFile, imageView, isVideo, fileName);
            return;
        }

        new Thread(() -> {
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                        encryptedFile, imageView.getContext(), masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build();

                try (InputStream in = encryptedStorage.openFileInput();
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[16384];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                    out.flush();
                }

                imageView.post(() -> loadThumbnail(tempFile, imageView, isVideo, fileName));
            } catch (GeneralSecurityException | IOException e) {
                e.printStackTrace();
                imageView.post(() -> {
                    if (fileName.equals(imageView.getTag())) {
                        imageView.setImageResource(R.drawable.ic_empty_gallery);
                        imageView.setScaleType(ImageView.ScaleType.CENTER);
                    }
                });
            }
        }).start();
    }

    private void loadThumbnail(File tempFile, ImageView imageView, boolean isVideo, String expectedFileName) {
        if (!expectedFileName.equals(imageView.getTag())) return;

        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (isVideo) {
            Bitmap thumb = ThumbnailUtils.createVideoThumbnail(tempFile.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
            if (thumb != null) {
                imageView.setImageBitmap(thumb);
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_report_image);
            }
        } else {
            Glide.with(imageView.getContext())
                .load(tempFile)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .centerCrop()
                .into(imageView);
        }
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    static class AlbumViewHolder extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView name, count;

        AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.albumCover);
            name = itemView.findViewById(R.id.albumName);
            count = itemView.findViewById(R.id.albumItemCount);
        }
    }

    public static class AlbumItem {
        int id; // -1 for Favourites
        String name;
        int count;
        String coverFileName;
        boolean isVideo;

        public AlbumItem(int id, String name, int count, String coverFileName, boolean isVideo) {
            this.id = id;
            this.name = name;
            this.count = count;
            this.coverFileName = coverFileName;
            this.isVideo = isVideo;
        }
    }
}