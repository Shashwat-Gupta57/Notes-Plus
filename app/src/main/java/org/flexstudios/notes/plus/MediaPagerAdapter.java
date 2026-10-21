package org.flexstudios.notes.plus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Pages of the viewer. Nothing decrypted is ever written to disk here.
 *
 * Every page opens on its grid thumbnail, already in memory, so a swipe never shows a
 * spinner. Photos then swap to a screen-sized decode, and to a high-resolution one the
 * moment the user zooms past 1.6x, keeping the zoom where it is. Videos stream through
 * {@link VaultDataSource}, which decrypts only the ranges ExoPlayer reads, and only the page
 * on screen holds a player, so the hardware decoders are never exhausted.
 */
public class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.Page> {
    private static final String TAG = "MediaPagerAdapter";
    private static final float HI_RES_ZOOM = 1.6f;
    private static final int HI_RES_MAX_PX = 4096;

    /** What the activity needs to hear from a page. */
    public interface Host {
        void onPageTapped();

        void onControllerVisibility(boolean visible);
    }

    private final List<SecretItem> items;
    private final ExecutorService io;
    private final ThumbnailStore thumbnails;
    private final Host host;
    private final Set<Page> bound = new HashSet<>();
    /**
     * Where each video was left, and the frame it was left on, for this viewer session.
     * Swiping away and back resumes instead of restarting. The grid thumbnail stays the
     * first frame on purpose: a cell that changed with every viewing would be hard to find.
     */
    private final java.util.Map<String, Long> resumeAt = new java.util.HashMap<>();
    private final android.util.LruCache<String, Bitmap> lastFrames = new android.util.LruCache<>(6);
    private final int screenW, screenH;
    private int current = -1;

    public MediaPagerAdapter(Context context, List<SecretItem> items, ExecutorService io, Host host) {
        this.items = items;
        this.io = io;
        this.host = host;
        this.thumbnails = ThumbnailStore.get(context);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    @NonNull
    @Override
    public Page onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Page page = new Page(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media_pager, parent, false));
        page.photo.setOnViewTapListener((v, x, y) -> host.onPageTapped());
        page.photo.setOnOutsidePhotoTapListener(v -> host.onPageTapped());
        page.poster.setOnClickListener(v -> host.onPageTapped());
        page.photo.setMaximumScale(8f);
        page.photo.setOnScaleChangeListener((factor, fx, fy) -> {
            if (!page.hiRes && page.photo.getScale() > HI_RES_ZOOM) loadHiRes(page);
        });
        page.player.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        host.onControllerVisibility(visibility == View.VISIBLE));
        return page;
    }

    @Override
    public void onBindViewHolder(@NonNull Page page, int position) {
        page.release();
        SecretItem item = items.get(position);
        page.item = item;
        bound.add(page);

        page.photo.setVisibility(View.GONE);
        page.player.setVisibility(View.GONE);
        showPoster(page, item);
        if (item.isVideo) {
            if (position == current) startPlayer(page);
        } else {
            loadImage(page, screenW, screenH, false);
        }
    }

    // ---------------------------------------------------------------- photos

    private void showPoster(Page page, SecretItem item) {
        Bitmap left = lastFrames.get(item.fileName);
        Bitmap thumb = left != null ? left : thumbnails.peek(item.fileName);
        if (thumb != null) {
            page.poster.setImageBitmap(thumb);
            page.poster.setVisibility(View.VISIBLE);
            page.progress.setVisibility(View.GONE);
            return;
        }
        page.poster.setImageDrawable(null);
        page.poster.setVisibility(View.VISIBLE);
        page.progress.setVisibility(View.VISIBLE);
        thumbnails.load(item.fileName, item.isVideo, new ThumbnailStore.Callback() {
            @Override
            public void onThumbnail(Bitmap bitmap) {
                if (page.item != item || page.poster.getVisibility() != View.VISIBLE) return;
                page.poster.setImageBitmap(bitmap);
                page.progress.setVisibility(View.GONE);
            }

            @Override
            public void onFailed() {
                if (page.item == item) page.progress.setVisibility(View.GONE);
            }
        });
    }

    /** Decrypts into memory, then lets Glide decode at the size asked for. No caches. */
    private void loadImage(Page page, int width, int height, boolean keepZoom) {
        final SecretItem item = page.item;
        Context context = page.itemView.getContext().getApplicationContext();
        io.execute(() -> {
            byte[] bytes;
            try {
                bytes = VaultCrypto.readAll(context, new File(VaultMedia.vaultDir(context), item.fileName));
            } catch (IOException e) {
                Log.w(TAG, "Could not open " + item.fileName, e);
                page.itemView.post(() -> {
                    if (page.item == item) page.progress.setVisibility(View.GONE);
                });
                return;
            }
            page.itemView.post(() -> {
                if (page.item != item) return; // Recycled for another page meanwhile.
                Glide.with(page.photo)
                        .load(bytes)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .override(width, height)
                        // Never upscale: small images stay small in memory; PhotoView fits them.
                        .downsample(DownsampleStrategy.CENTER_INSIDE)
                        .into(new CustomTarget<Drawable>() {
                            @Override
                            public void onResourceReady(@NonNull Drawable resource,
                                                        @Nullable Transition<? super Drawable> t) {
                                if (page.item != item) return;
                                Matrix zoom = new Matrix();
                                if (keepZoom) page.photo.getSuppMatrix(zoom);
                                page.photo.setImageDrawable(resource);
                                if (keepZoom) page.photo.setSuppMatrix(zoom);
                                page.photo.setVisibility(View.VISIBLE);
                                page.poster.setVisibility(View.GONE);
                                page.progress.setVisibility(View.GONE);
                            }

                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {
                                // The PhotoView keeps whatever it shows until rebound.
                            }
                        });
            });
        });
    }

    private void loadHiRes(Page page) {
        page.hiRes = true;
        int longest = Math.min(HI_RES_MAX_PX, Math.max(screenW, screenH) * 3);
        loadImage(page, longest, longest, true);
    }

    // ---------------------------------------------------------------- videos

    /** Plays the selected page and frees every other page's decoder. */
    public void onPageSelected(int position) {
        current = position;
        for (Page page : bound) {
            int pos = page.getBindingAdapterPosition();
            if (page.item == null || !page.item.isVideo) continue;
            if (pos == position) startPlayer(page);
            else stopPlayer(page);
        }
    }

    /** Releases the decoder, remembering the position and the frame on screen. */
    @OptIn(markerClass = UnstableApi.class)
    private void stopPlayer(Page page) {
        if (page.exo == null || page.item == null) {
            page.stopPlayer();
            return;
        }
        long position = page.exo.getCurrentPosition();
        boolean ended = page.exo.getPlaybackState() == Player.STATE_ENDED;
        if (ended || position < 500) {
            resumeAt.remove(page.item.fileName);
            lastFrames.remove(page.item.fileName);
        } else {
            resumeAt.put(page.item.fileName, position);
            View surface = page.player.getVideoSurfaceView();
            if (surface instanceof android.view.TextureView) {
                android.view.TextureView texture = (android.view.TextureView) surface;
                // Half size: a poster shown for a moment needn't cost a full-screen bitmap.
                Bitmap frame = texture.getWidth() > 0
                        ? texture.getBitmap(texture.getWidth() / 2, texture.getHeight() / 2) : null;
                if (frame != null) {
                    lastFrames.put(page.item.fileName, frame);
                    page.poster.setImageBitmap(frame);
                }
            }
        }
        page.stopPlayer();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer(Page page) {
        if (page.exo != null) {
            page.exo.play();
            return;
        }
        Context context = page.itemView.getContext();
        File file = new File(VaultMedia.vaultDir(context), page.item.fileName);
        ExoPlayer player = new ExoPlayer.Builder(context).build();
        player.setMediaSource(new ProgressiveMediaSource.Factory(
                () -> new VaultDataSource(context.getApplicationContext()))
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(file))));
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                page.poster.animate().alpha(0f).setDuration(120)
                        .withEndAction(() -> {
                            page.poster.setVisibility(View.GONE);
                            page.poster.setAlpha(1f);
                        }).start();
                page.progress.setVisibility(View.GONE);
            }
        });
        Long resume = resumeAt.get(page.item.fileName);
        if (resume != null) player.seekTo(resume);
        player.prepare();
        player.setPlayWhenReady(true);
        page.exo = player;
        page.player.setPlayer(player);
        page.player.setVisibility(View.VISIBLE);
    }

    public void pauseAll() {
        for (Page page : bound) if (page.exo != null) page.exo.pause();
    }

    /** True when the page on screen is zoomed, so a vertical drag pans instead of dismissing. */
    public boolean isZoomed(int position) {
        for (Page page : bound) {
            if (page.getBindingAdapterPosition() == position && page.photo.getVisibility() == View.VISIBLE) {
                return page.photo.getScale() > 1.05f;
            }
        }
        return false;
    }

    @Override
    public void onViewRecycled(@NonNull Page page) {
        super.onViewRecycled(page);
        stopPlayer(page);
        page.release();
        bound.remove(page);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void releaseAll() {
        for (Page page : bound) stopPlayer(page);
        for (Page page : bound) page.release();
        bound.clear();
    }

    static final class Page extends RecyclerView.ViewHolder {
        final PhotoView photo;
        final PlayerView player;
        final ImageView poster;
        final ProgressBar progress;
        SecretItem item;
        ExoPlayer exo;
        boolean hiRes;

        Page(@NonNull View view) {
            super(view);
            photo = view.findViewById(R.id.pagerImageView);
            player = view.findViewById(R.id.pagerPlayerView);
            poster = view.findViewById(R.id.pagerPoster);
            progress = view.findViewById(R.id.pagerProgressBar);
        }

        void stopPlayer() {
            if (exo == null) return;
            player.setPlayer(null);
            exo.release();
            exo = null;
            player.setVisibility(View.GONE);
            poster.setVisibility(View.VISIBLE);
        }

        void release() {
            stopPlayer();
            item = null;
            hiRes = false;
            photo.setImageDrawable(null);
        }
    }

    /**
     * Feeds ExoPlayer from a vault file, decrypting only the byte ranges it reads.
     * The URI is the encrypted file's own path.
     */
    @OptIn(markerClass = UnstableApi.class)
    static final class VaultDataSource extends BaseDataSource {
        private final Context context;
        private SeekableByteChannel channel;
        private Uri uri;
        private long remaining;
        private boolean opened;

        VaultDataSource(Context context) {
            super(false);
            this.context = context;
        }

        @Override
        public long open(@NonNull DataSpec spec) throws IOException {
            uri = spec.uri;
            transferInitializing(spec);
            String path = uri.getPath();
            if (path == null) throw new IOException("No path in " + uri);
            channel = VaultCrypto.openSeekable(context, new File(path));
            long available = channel.size() - spec.position;
            if (available < 0) {
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
            channel.position(spec.position);
            remaining = spec.length == C.LENGTH_UNSET ? available : Math.min(spec.length, available);
            opened = true;
            transferStarted(spec);
            return remaining;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            int read = channel.read(ByteBuffer.wrap(buffer, offset, (int) Math.min(length, remaining)));
            if (read < 0) return C.RESULT_END_OF_INPUT;
            remaining -= read;
            bytesTransferred(read);
            return read;
        }

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public void close() throws IOException {
            uri = null;
            try {
                if (channel != null) channel.close();
            } finally {
                channel = null;
                if (opened) {
                    opened = false;
                    transferEnded();
                }
            }
        }
    }
}
