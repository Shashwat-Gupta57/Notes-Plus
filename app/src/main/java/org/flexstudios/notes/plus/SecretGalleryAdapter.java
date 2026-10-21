package org.flexstudios.notes.plus;

import android.graphics.Bitmap;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * The vault grid: day headers, square cells, selection.
 *
 * The whole filter is held at once as light {@link SecretItem} rows (see {@link Model}); only
 * thumbnails are lazy. Updates go through {@link AsyncListDiffer}, so an import, a delete or
 * a favourite animates in place instead of reloading the grid and losing the scroll position.
 *
 * Selection is kept by file name, so it covers items that are not on screen, and survives
 * the list being rebuilt underneath it.
 */
public class SecretGalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int TYPE_HEADER = 0;
    static final int TYPE_ITEM = 1;

    private static final Object PAYLOAD_SELECTION = new Object();
    private static final Object PAYLOAD_SIZE = new Object();
    private static final float SELECTED_SCALE = 0.86f;

    public interface Listener {
        void onItemClick(SecretItem item, View cell);

        /** Long-press entered selection mode on this position; drag-select may follow. */
        void onSelectionStarted(int position);

        void onSelectionChanged(int count, boolean allSelected);
    }

    /** A day's header row. */
    static final class DateHeader {
        final long day;
        final String label;

        DateHeader(long day, String label) {
            this.day = day;
            this.label = label;
        }
    }

    /** Display rows for one filter, built off the main thread by {@link #build}. */
    static final class Model {
        final List<Object> rows;
        final List<SecretItem> items;
        final Map<Long, List<String>> namesByDay;
        /** For every row, the index of the header above it; drives the fast-scroll label. */
        final int[] headerOf;

        Model(List<Object> rows, List<SecretItem> items, Map<Long, List<String>> namesByDay, int[] headerOf) {
            this.rows = rows;
            this.items = items;
            this.namesByDay = namesByDay;
            this.headerOf = headerOf;
        }

        static final Model EMPTY = new Model(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), new int[0]);
    }

    private static final DiffUtil.ItemCallback<Object> DIFF = new DiffUtil.ItemCallback<Object>() {
        @Override
        public boolean areItemsTheSame(@NonNull Object a, @NonNull Object b) {
            if (a instanceof DateHeader && b instanceof DateHeader) {
                return ((DateHeader) a).day == ((DateHeader) b).day;
            }
            return a instanceof SecretItem && b instanceof SecretItem
                    && ((SecretItem) a).fileName.equals(((SecretItem) b).fileName);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Object a, @NonNull Object b) {
            if (a instanceof DateHeader) return ((DateHeader) a).label.equals(((DateHeader) b).label);
            return ((SecretItem) a).sameContentAs((SecretItem) b);
        }
    };

    private final AsyncListDiffer<Object> differ = new AsyncListDiffer<>(this, DIFF);
    private final ThumbnailStore thumbnails;
    private final Listener listener;
    private final Set<String> selected = new HashSet<>();
    /** Selection when a drag began, so dragging back un-selects only what the drag added. */
    private Set<String> dragBase;
    private Model model = Model.EMPTY;
    private boolean selectionMode;
    private int cellSize;

    public SecretGalleryAdapter(ThumbnailStore thumbnails, Listener listener) {
        this.thumbnails = thumbnails;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ model

    /** Groups newest-first rows under day headers. Runs on a background thread. */
    static Model build(List<SecretItem> items, long now) {
        List<Object> rows = new ArrayList<>(items.size() + items.size() / 8 + 1);
        Map<Long, List<String>> byDay = new HashMap<>();
        int[] headerOf = new int[items.size() + items.size() + 1];
        Calendar cal = Calendar.getInstance();
        Labeler labeler = new Labeler(now);

        long currentDay = Long.MIN_VALUE;
        int headerIndex = -1;
        List<String> dayNames = null;
        for (SecretItem item : items) {
            long day = startOfDay(cal, item.dateTaken);
            if (day != currentDay) {
                currentDay = day;
                headerIndex = rows.size();
                rows.add(new DateHeader(day, labeler.label(day)));
                headerOf[headerIndex] = headerIndex;
                dayNames = new ArrayList<>();
                byDay.put(day, dayNames);
            }
            headerOf[rows.size()] = headerIndex;
            rows.add(item);
            dayNames.add(item.fileName);
        }
        int[] trimmed = new int[rows.size()];
        System.arraycopy(headerOf, 0, trimmed, 0, rows.size());
        return new Model(rows, items, byDay, trimmed);
    }

    /** Shows a new model, animating the difference. {@code done} runs once it is on screen. */
    public void submit(Model next, Runnable done) {
        differ.submitList(next.rows, () -> {
            model = next;
            // Drop selections for items that no longer exist in this filter.
            if (!selected.isEmpty()) {
                Set<String> present = new HashSet<>();
                for (SecretItem item : next.items) present.add(item.fileName);
                selected.retainAll(present);
                if (selected.isEmpty() && selectionMode) setSelectionMode(false);
                else notifySelectionChanged();
            }
            if (done != null) done.run();
        });
    }

    public List<SecretItem> items() {
        return model.items;
    }

    public Object rowAt(int position) {
        List<Object> rows = differ.getCurrentList();
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    /** The day label for any row, for the fast scroller's bubble. */
    public String labelAt(int position) {
        if (position < 0 || position >= model.headerOf.length) return "";
        Object header = rowAt(model.headerOf[position]);
        return header instanceof DateHeader ? ((DateHeader) header).label : "";
    }

    /** Cell edge in pixels for the current column count; rebinds sizes without reloading art. */
    public void setCellSize(int px) {
        if (px == cellSize) return;
        cellSize = px;
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SIZE);
    }

    // -------------------------------------------------------------- selection

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selected.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        notifySelectionChanged();
    }

    public List<String> selectedNames() {
        // Kept in grid order, so a share or a move processes items as the user sees them.
        List<String> out = new ArrayList<>(selected.size());
        for (SecretItem item : model.items) if (selected.contains(item.fileName)) out.add(item.fileName);
        return out;
    }

    public int selectedCount() {
        return selected.size();
    }

    public void selectAll(boolean select) {
        selected.clear();
        if (select) for (SecretItem item : model.items) selected.add(item.fileName);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        notifySelectionChanged();
    }

    public boolean isAllSelected() {
        return !model.items.isEmpty() && selected.size() == model.items.size();
    }

    void toggle(int position) {
        Object row = rowAt(position);
        if (!(row instanceof SecretItem)) return;
        String name = ((SecretItem) row).fileName;
        if (!selected.remove(name)) selected.add(name);
        notifyItemChanged(position, PAYLOAD_SELECTION);
        notifyHeaderOf(position);
        notifySelectionChanged();
        if (selected.isEmpty()) setSelectionMode(false);
    }

    /** Called by drag-select as the finger moves: everything between the two ends is selected. */
    void dragSelect(int anchor, int from, int to) {
        if (dragBase == null) dragBase = new HashSet<>(selected);
        int lo = Math.min(anchor, to), hi = Math.max(anchor, to);
        int touchedLo = Math.min(lo, Math.min(anchor, from)), touchedHi = Math.max(hi, Math.max(anchor, from));
        for (int i = touchedLo; i <= touchedHi; i++) {
            Object row = rowAt(i);
            if (!(row instanceof SecretItem)) continue;
            String name = ((SecretItem) row).fileName;
            boolean want = (i >= lo && i <= hi) || dragBase.contains(name);
            boolean has = selected.contains(name);
            if (want != has) {
                if (want) selected.add(name);
                else selected.remove(name);
                notifyItemChanged(i, PAYLOAD_SELECTION);
                notifyHeaderOf(i);
            }
        }
        notifySelectionChanged();
    }

    void endDrag() {
        dragBase = null;
    }

    private void toggleDay(DateHeader header) {
        List<String> names = model.namesByDay.get(header.day);
        if (names == null) return;
        boolean all = selected.containsAll(names);
        if (all) selected.removeAll(names);
        else selected.addAll(names);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        notifySelectionChanged();
        if (selected.isEmpty()) setSelectionMode(false);
    }

    private void notifyHeaderOf(int position) {
        if (position < model.headerOf.length) notifyItemChanged(model.headerOf[position], PAYLOAD_SELECTION);
    }

    private void notifySelectionChanged() {
        listener.onSelectionChanged(selected.size(), isAllSelected());
    }

    // ---------------------------------------------------------------- binding

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @Override
    public int getItemViewType(int position) {
        return rowAt(position) instanceof DateHeader ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_gallery_header, parent, false));
        }
        ItemHolder holder = new ItemHolder(inflater.inflate(R.layout.secret_item, parent, false));
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            Object row = rowAt(pos);
            if (!(row instanceof SecretItem)) return;
            if (selectionMode) toggle(pos);
            else listener.onItemClick((SecretItem) row, holder.content);
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (!(rowAt(pos) instanceof SecretItem)) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!selectionMode) setSelectionMode(true);
            Object row = rowAt(pos);
            if (row instanceof SecretItem && !selected.contains(((SecretItem) row).fileName)) toggle(pos);
            listener.onSelectionStarted(pos);
            return true;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        onBindViewHolder(holder, position, Collections.emptyList());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        Object row = rowAt(position);
        boolean onlySelection = !payloads.isEmpty() && !payloads.contains(PAYLOAD_SIZE);

        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).bind((DateHeader) row);
            return;
        }
        ItemHolder cell = (ItemHolder) holder;
        SecretItem item = (SecretItem) row;
        cell.applySize(cellSize);
        cell.applySelection(selectionMode, selected.contains(item.fileName), onlySelection);
        if (!payloads.isEmpty()) return; // Selection or size only: leave the artwork alone.
        cell.bind(thumbnails, item);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof ItemHolder) ((ItemHolder) holder).recycle();
    }

    // ----------------------------------------------------------------- holders

    final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView dateText;
        final View selectionContainer;
        final ImageView checkFilled;

        HeaderHolder(@NonNull View view) {
            super(view);
            dateText = view.findViewById(R.id.dateHeaderText);
            selectionContainer = view.findViewById(R.id.headerSelectionContainer);
            checkFilled = view.findViewById(R.id.headerCheckCircleFilled);
        }

        void bind(DateHeader header) {
            dateText.setText(header.label);
            selectionContainer.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            if (!selectionMode) return;
            List<String> names = model.namesByDay.get(header.day);
            boolean all = names != null && !names.isEmpty() && selected.containsAll(names);
            checkFilled.setVisibility(all ? View.VISIBLE : View.GONE);
            selectionContainer.setOnClickListener(v -> toggleDay(header));
        }
    }

    static final class ItemHolder extends RecyclerView.ViewHolder {
        final View content, scrim;
        final ImageView thumbnail, check, favourite;
        final TextView duration;
        private Future<?> pending;
        private String boundName;

        ItemHolder(@NonNull View view) {
            super(view);
            content = view.findViewById(R.id.cellContent);
            scrim = view.findViewById(R.id.cellScrim);
            thumbnail = view.findViewById(R.id.thumbnail);
            check = view.findViewById(R.id.cellCheck);
            favourite = view.findViewById(R.id.cellFavourite);
            duration = view.findViewById(R.id.cellDuration);
            content.setClipToOutline(true);
        }

        void applySize(int size) {
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (size > 0 && lp.height != size) {
                lp.height = size;
                itemView.setLayoutParams(lp);
            }
        }

        void applySelection(boolean mode, boolean isSelected, boolean animate) {
            check.setVisibility(mode ? View.VISIBLE : View.GONE);
            check.setImageResource(isSelected ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_empty);
            float scale = isSelected ? SELECTED_SCALE : 1f;
            scrim.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            if (animate) {
                content.animate().scaleX(scale).scaleY(scale).setDuration(160)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                content.animate().cancel();
                content.setScaleX(scale);
                content.setScaleY(scale);
            }
        }

        void bind(ThumbnailStore store, SecretItem item) {
            recycle();
            boundName = item.fileName;
            favourite.setVisibility(item.isFavourite ? View.VISIBLE : View.GONE);
            showDuration(item.isVideo, item.durationMs > 0 ? item.durationMs : store.peekDuration(item.fileName));

            Bitmap immediate = store.peek(item.fileName);
            boolean measure = item.isVideo && item.durationMs == 0;
            if (immediate != null && !measure) {
                thumbnail.setAlpha(1f);
                thumbnail.setImageBitmap(immediate);
                return;
            }
            if (immediate != null) {
                thumbnail.setAlpha(1f);
                thumbnail.setImageBitmap(immediate);
            }
            final String requested = item.fileName;
            pending = store.load(requested, item.isVideo, measure, new ThumbnailStore.Callback() {
                @Override
                public void onThumbnail(Bitmap bitmap) {
                    if (!requested.equals(boundName) || thumbnail.getDrawable() != null) return;
                    thumbnail.setImageBitmap(bitmap);
                    thumbnail.setAlpha(0f);
                    thumbnail.animate().alpha(1f).setDuration(140).start();
                }

                @Override
                public void onFailed() {
                    if (!requested.equals(boundName)) return;
                    thumbnail.setAlpha(0.5f);
                    thumbnail.setScaleType(ImageView.ScaleType.CENTER);
                    thumbnail.setImageResource(android.R.drawable.ic_menu_report_image);
                }

                @Override
                public void onDuration(long durationMs) {
                    if (requested.equals(boundName)) showDuration(true, durationMs);
                }
            });
        }

        private void showDuration(boolean isVideo, long ms) {
            if (!isVideo) {
                duration.setVisibility(View.GONE);
                return;
            }
            duration.setVisibility(View.VISIBLE);
            duration.setText(ms > 0 ? formatDuration(ms) : "");
        }

        void recycle() {
            boundName = null;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            thumbnail.animate().cancel();
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageDrawable(null);
        }
    }

    // ------------------------------------------------------------------ labels

    static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    private static long startOfDay(Calendar cal, long millis) {
        cal.setTimeInMillis(millis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** "Today", "Yesterday", "Monday", "12 March", "12 March 2023". */
    static final class Labeler {
        private final long today, yesterday, weekAgo;
        private final int thisYear;
        private final Calendar cal = Calendar.getInstance();
        private final SimpleDateFormat weekday = new SimpleDateFormat("EEEE", Locale.getDefault());
        private final SimpleDateFormat sameYear = new SimpleDateFormat("EEE, d MMMM", Locale.getDefault());
        private final SimpleDateFormat otherYear = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());

        Labeler(long now) {
            today = startOfDay(cal, now);
            cal.setTimeInMillis(today);
            cal.add(Calendar.DAY_OF_YEAR, -1);
            yesterday = cal.getTimeInMillis();
            cal.setTimeInMillis(today);
            cal.add(Calendar.DAY_OF_YEAR, -6);
            weekAgo = cal.getTimeInMillis();
            cal.setTimeInMillis(now);
            thisYear = cal.get(Calendar.YEAR);
        }

        String label(long day) {
            if (day == today) return "Today";
            if (day == yesterday) return "Yesterday";
            Date date = new Date(day);
            if (day >= weekAgo && day < today) return weekday.format(date);
            cal.setTimeInMillis(day);
            return cal.get(Calendar.YEAR) == thisYear ? sameYear.format(date) : otherYear.format(date);
        }
    }
}
