package org.flexstudios.notes.plus;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Album grid. Covers come from {@link ThumbnailStore} for the same reason the media grid
 * does: the old path decrypted the full-size cover into the cache and left it there.
 */
public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

    private List<AlbumItem> albums = new ArrayList<>();
    private final ThumbnailStore thumbnails;
    private final OnAlbumClickListener listener;
    private final OnAlbumLongClickListener longClickListener;

    public interface OnAlbumClickListener {
        void onAlbumClick(AlbumItem album);
    }

    public interface OnAlbumLongClickListener {
        void onAlbumLongClick(AlbumItem album);
    }

    public AlbumAdapter(ThumbnailStore thumbnails, OnAlbumClickListener listener,
                        OnAlbumLongClickListener longClickListener) {
        this.thumbnails = thumbnails;
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
        holder.bindCover(thumbnails, item);

        holder.itemView.setOnClickListener(v -> listener.onAlbumClick(item));
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onAlbumLongClick(item);
                return true;
            }
            return false;
        });
    }

    @Override
    public void onViewRecycled(@NonNull AlbumViewHolder holder) {
        super.onViewRecycled(holder);
        holder.recycle();
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    static class AlbumViewHolder extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView name, count;
        View skeleton;

        private Future<?> pending;
        private ObjectAnimator shimmer;
        private String boundFileName;

        AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.albumCover);
            name = itemView.findViewById(R.id.albumName);
            count = itemView.findViewById(R.id.albumItemCount);
            skeleton = itemView.findViewById(R.id.albumCoverSkeleton);
        }

        void bindCover(ThumbnailStore store, AlbumItem item) {
            recycle();

            if (item.coverFileName == null) {
                showPlaceholder();
                return;
            }

            boundFileName = item.coverFileName;
            Bitmap immediate = store.peek(boundFileName);
            if (immediate != null) {
                showBitmap(immediate);
                return;
            }

            cover.setImageDrawable(null);
            skeleton.setVisibility(View.VISIBLE);
            shimmer = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 1f, 0.4f);
            shimmer.setDuration(650);
            shimmer.setRepeatMode(ValueAnimator.REVERSE);
            shimmer.setRepeatCount(ValueAnimator.INFINITE);
            shimmer.start();

            final String requested = boundFileName;
            pending = store.load(requested, item.isVideo, new ThumbnailStore.Callback() {
                @Override
                public void onThumbnail(Bitmap bitmap) {
                    if (!requested.equals(boundFileName)) return;
                    showBitmap(bitmap);
                }

                @Override
                public void onFailed() {
                    if (!requested.equals(boundFileName)) return;
                    showPlaceholder();
                }
            });
        }

        private void showBitmap(Bitmap bitmap) {
            stopShimmer();
            skeleton.setVisibility(View.GONE);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageBitmap(bitmap);
        }

        private void showPlaceholder() {
            stopShimmer();
            skeleton.setVisibility(View.VISIBLE);
            cover.setScaleType(ImageView.ScaleType.CENTER);
            cover.setImageResource(R.drawable.ic_empty_gallery);
        }

        private void stopShimmer() {
            if (shimmer != null) {
                shimmer.cancel();
                shimmer = null;
            }
            skeleton.setAlpha(1f);
        }

        void recycle() {
            boundFileName = null;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            stopShimmer();
            cover.setImageDrawable(null);
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
