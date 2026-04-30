package org.flexstudios.notes.plus;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;
import com.bumptech.glide.Glide;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.ViewHolder> {
    private final List<SecretItem> items;
    private final Map<Integer, ExoPlayer> players = new HashMap<>();

    public MediaPagerAdapter(List<SecretItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media_pager, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SecretItem item = items.get(position);
        holder.imageView.setVisibility(View.GONE);
        holder.playerView.setVisibility(View.GONE);
        holder.progressBar.setVisibility(View.VISIBLE);
        
        if (item.isVideo()) {
            setupVideo(holder, item, position);
        } else {
            setupImage(holder, item);
        }
    }

    private void setupImage(ViewHolder holder, SecretItem item) {
        new Thread(() -> {
            try {
                File decrypted = decryptToTemp(holder.itemView.getContext(), item.getFile());
                holder.imageView.post(() -> {
                    holder.imageView.setVisibility(View.VISIBLE);
                    holder.progressBar.setVisibility(View.GONE);
                    Glide.with(holder.imageView).load(decrypted).into(holder.imageView);
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void setupVideo(ViewHolder holder, SecretItem item, int position) {
        Context context = holder.itemView.getContext();
        
        new Thread(() -> {
            try {
                File decrypted = decryptToTemp(context, item.getFile());
                holder.playerView.post(() -> {
                    ExoPlayer player = new ExoPlayer.Builder(context).build();
                    holder.playerView.setPlayer(player);
                    players.put(position, player);
                    
                    holder.playerView.setVisibility(View.VISIBLE);
                    holder.progressBar.setVisibility(View.GONE);
                    
                    player.setMediaItem(MediaItem.fromUri(decrypted.getAbsolutePath()));
                    player.prepare();
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private File decryptToTemp(Context context, File encryptedFile) throws GeneralSecurityException, IOException {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        EncryptedFile encryptedStorage = new EncryptedFile.Builder(
                encryptedFile, context, masterKeyAlias, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build();

        File tempFile = new File(context.getCacheDir(), "pager_" + encryptedFile.getName());
        if (tempFile.exists()) return tempFile;

        try (InputStream in = encryptedStorage.openFileInput();
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        }
        return tempFile;
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        int pos = holder.getAbsoluteAdapterPosition();
        ExoPlayer player = players.remove(pos);
        if (player != null) {
            player.release();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void releaseAll() {
        for (ExoPlayer player : players.values()) {
            player.release();
        }
        players.clear();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        PlayerView playerView;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.pagerImageView);
            playerView = itemView.findViewById(R.id.pagerPlayerView);
            progressBar = itemView.findViewById(R.id.pagerProgressBar);
        }
    }
}