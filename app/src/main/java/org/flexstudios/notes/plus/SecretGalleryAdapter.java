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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKeys;

public class SecretGalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private List<Object> displayItems = new ArrayList<>();
    private final Set<String> selectedFileNames = new HashSet<>();
    private boolean isSelectionMode = false;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(SecretItem item);
        void onItemLongClick(SecretItem item);
        void onSelectionChanged(int count);
        void onAllItemsSelectionChanged(boolean allSelected);
    }

    public SecretGalleryAdapter(List<SecretItem> items, List<SecretEntity> entities, OnItemClickListener listener) {
        this.listener = listener;
        updateData(items, entities);
    }

    public void updateData(List<SecretItem> items, List<SecretEntity> entities) {
        displayItems.clear();
        if (entities == null || entities.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault());
        String lastDate = "";

        for (SecretEntity entity : entities) {
            String dateStr = fmt.format(new Date(entity.getDateTaken()));

            if (!dateStr.equals(lastDate)) {
                displayItems.add(dateStr);
                lastDate = dateStr;
            }

            for (SecretItem item : items) {
                if (item.getFile().getName().equals(entity.getFileName())) {
                    displayItems.add(item);
                    break;
                }
            }
        }
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean enabled) {
        if (this.isSelectionMode == enabled) return;
        this.isSelectionMode = enabled;
        if (!enabled) selectedFileNames.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(selectedFileNames.size());
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void toggleSelection(int position) {
        Object obj = displayItems.get(position);
        if (!(obj instanceof SecretItem)) return;
        
        SecretItem item = (SecretItem) obj;
        String name = item.getFile().getName();
        if (selectedFileNames.contains(name)) {
            selectedFileNames.remove(name);
        } else {
            selectedFileNames.add(name);
        }
        
        notifyItemChanged(position, "selection_update");
        updateHeaderStates();
        checkGlobalSelection();
        
        if (listener != null) listener.onSelectionChanged(selectedFileNames.size());
        
        if (selectedFileNames.isEmpty() && isSelectionMode) {
            setSelectionMode(false);
        }
    }

    private void checkGlobalSelection() {
        if (listener != null) {
            listener.onAllItemsSelectionChanged(isAllSelected());
        }
    }

    public boolean isAllSelected() {
        int totalItems = 0;
        for (Object obj : displayItems) {
            if (obj instanceof SecretItem) totalItems++;
        }
        return totalItems > 0 && selectedFileNames.size() == totalItems;
    }

    public void selectAll(boolean select) {
        selectedFileNames.clear();
        if (select) {
            for (Object obj : displayItems) {
                if (obj instanceof SecretItem) {
                    selectedFileNames.add(((SecretItem) obj).getFile().getName());
                }
            }
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedFileNames.size());
            listener.onAllItemsSelectionChanged(select);
        }
    }

    private void updateHeaderStates() {
        // Find headers and refresh them based on their children's selection
        for (int i = 0; i < displayItems.size(); i++) {
            if (displayItems.get(i) instanceof String) {
                notifyItemChanged(i, "selection_update");
            }
        }
    }

    private void toggleDateSelection(String date) {
        List<SecretItem> itemsInDate = getItemsForDate(date);
        boolean allInDateSelected = true;
        for (SecretItem item : itemsInDate) {
            if (!selectedFileNames.contains(item.getFile().getName())) {
                allInDateSelected = false;
                break;
            }
        }

        if (allInDateSelected) {
            for (SecretItem item : itemsInDate) selectedFileNames.remove(item.getFile().getName());
        } else {
            for (SecretItem item : itemsInDate) selectedFileNames.add(item.getFile().getName());
        }
        
        notifyDataSetChanged();
        checkGlobalSelection();
        if (listener != null) listener.onSelectionChanged(selectedFileNames.size());
    }

    private List<SecretItem> getItemsForDate(String date) {
        List<SecretItem> items = new ArrayList<>();
        boolean foundDate = false;
        for (Object obj : displayItems) {
            if (obj instanceof String) {
                if (foundDate) break;
                if (obj.equals(date)) foundDate = true;
            } else if (foundDate && obj instanceof SecretItem) {
                items.add((SecretItem) obj);
            }
        }
        return items;
    }

    public List<SecretItem> getSelectedItems() {
        List<SecretItem> selected = new ArrayList<>();
        for (Object obj : displayItems) {
            if (obj instanceof SecretItem) {
                SecretItem item = (SecretItem) obj;
                if (selectedFileNames.contains(item.getFile().getName())) {
                    selected.add(item);
                }
            }
        }
        return selected;
    }

    @Override
    public int getItemViewType(int position) {
        return (displayItems.get(position) instanceof String) ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.secret_item, parent, false);
            return new ItemViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        onBindViewHolder(holder, position, new ArrayList<>());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        Object item = displayItems.get(position);

        if (holder instanceof HeaderViewHolder) {
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            String date = (String) item;
            headerHolder.dateText.setText(date);
            
            headerHolder.selectionContainer.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
            
            if (isSelectionMode) {
                List<SecretItem> itemsInDate = getItemsForDate(date);
                boolean allSelected = !itemsInDate.isEmpty();
                for (SecretItem si : itemsInDate) {
                    if (!selectedFileNames.contains(si.getFile().getName())) {
                        allSelected = false;
                        break;
                    }
                }
                headerHolder.checkCircleFilled.setVisibility(allSelected ? View.VISIBLE : View.GONE);
                headerHolder.selectionContainer.setOnClickListener(v -> toggleDateSelection(date));
            }

        } else if (holder instanceof ItemViewHolder) {
            ItemViewHolder itemHolder = (ItemViewHolder) holder;
            SecretItem secretItem = (SecretItem) item;
            String fileName = secretItem.getFile().getName();

            boolean isSelected = selectedFileNames.contains(fileName);

            itemHolder.checkCircleBase.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
            itemHolder.checkCircleFilled.setVisibility(isSelectionMode && isSelected ? View.VISIBLE : View.GONE);

            if (payloads.contains("selection_update")) return;

            itemHolder.thumbnail.setTag(fileName);
            itemHolder.thumbnail.setImageDrawable(null);
            itemHolder.playIcon.setVisibility(secretItem.isVideo() ? View.VISIBLE : View.GONE);
            
            decryptAndLoadThumbnail(secretItem, itemHolder.thumbnail);

            itemHolder.itemView.setOnClickListener(v -> {
                if (isSelectionMode) {
                    toggleSelection(position);
                } else {
                    listener.onItemClick(secretItem);
                }
            });

            itemHolder.itemView.setOnLongClickListener(v -> {
                if (!isSelectionMode) {
                    setSelectionMode(true);
                    toggleSelection(position);
                }
                return true;
            });
        }
    }

    private void decryptAndLoadThumbnail(SecretItem item, ImageView imageView) {
        final String fileName = item.getFile().getName();
        File encryptedFile = item.getFile();
        File tempFile = new File(imageView.getContext().getCacheDir(), "thumb_" + encryptedFile.getName());
        
        if (tempFile.exists() && tempFile.length() > 0) {
            loadThumbnailFromTemp(item, tempFile, imageView, fileName);
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

                imageView.post(() -> loadThumbnailFromTemp(item, tempFile, imageView, fileName));
            } catch (GeneralSecurityException | IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void loadThumbnailFromTemp(SecretItem item, File tempFile, ImageView imageView, String expectedFileName) {
        if (!expectedFileName.equals(imageView.getTag())) return;

        if (item.isVideo()) {
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
        return displayItems.size();
    }

    public static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail, playIcon, checkCircleBase, checkCircleFilled;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            playIcon = itemView.findViewById(R.id.playIcon);
            checkCircleBase = itemView.findViewById(R.id.checkCircleBase);
            checkCircleFilled = itemView.findViewById(R.id.checkCircleFilled);
        }
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView dateText;
        View selectionContainer;
        ImageView checkCircleBase, checkCircleFilled;

        public HeaderViewHolder(@NonNull View view) {
            super(view);
            dateText = view.findViewById(R.id.dateHeaderText);
            selectionContainer = view.findViewById(R.id.headerSelectionContainer);
            checkCircleBase = view.findViewById(R.id.headerCheckCircleBase);
            checkCircleFilled = view.findViewById(R.id.headerCheckCircleFilled);
        }
    }
}