package org.flexstudios.notes.plus;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The notes grid. Every card previews its note the way its type reads best: text shows its
 * words, a list its first items with their ticks, a mind map a live overview, a journal its
 * date and first photo, a sketch the drawing, an audio note its waveform.
 */
public class NoteAdapter extends ListAdapter<NoteEntity, RecyclerView.ViewHolder> {
    private static final int LIST_PREVIEW = 5;
    /** A folder on the All grid stands in the list as a note of this type, titled with its name. */
    static final String FOLDER = "FOLDER";
    private static final int VIEW_NOTE = 0, VIEW_FOLDER = 1;

    public interface Listener {
        void onOpen(NoteEntity note);

        void onOptions(NoteEntity note);

        default void onFolder(String name) {}

        default void onFolderOptions(String name) {}
    }

    /** Each folder tile's notes, by folder name (newest first, as the grid sorts them). */
    private final java.util.Map<String, List<NoteEntity>> folders = new java.util.HashMap<>();

    /**
     * The grid's items with folder tiles first: {@code folders} (name → its notes) become one
     * item each. A tile's content says what it shows, so the differ redraws it when that changes.
     */
    void submit(List<NoteEntity> loose, java.util.Map<String, List<NoteEntity>> byFolder) {
        folders.clear();
        folders.putAll(byFolder);
        List<NoteEntity> items = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, List<NoteEntity>> f : byFolder.entrySet()) {
            NoteEntity tile = new NoteEntity(f.getKey(), "", 0);
            tile.setType(FOLDER);
            tile.setId(-1 - (f.getKey().hashCode() & 0x3fffffff));
            StringBuilder sig = new StringBuilder();
            for (NoteEntity n : f.getValue()) {
                sig.append(n.getId()).append(':').append(n.getTimestamp()).append(':').append(n.getColor())
                        .append(':').append(n.getTitle()).append(':').append(n.getContent() == null ? 0 : n.getContent().hashCode())
                        .append(':').append(n.getData() == null ? 0 : n.getData().hashCode()).append(';');
            }
            tile.setContent(sig.toString());
            items.add(tile);
        }
        items.addAll(loose);
        submitList(items);
    }

    @Override
    public int getItemViewType(int position) {
        return FOLDER.equals(getItem(position).getType()) ? VIEW_FOLDER : VIEW_NOTE;
    }

    private Listener listener;

    public NoteAdapter() {
        super(DIFF);
        setHasStableIds(true);
    }

    private static final DiffUtil.ItemCallback<NoteEntity> DIFF = new DiffUtil.ItemCallback<NoteEntity>() {
        @Override
        public boolean areItemsTheSame(@NonNull NoteEntity a, @NonNull NoteEntity b) {
            return a.getId() == b.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull NoteEntity a, @NonNull NoteEntity b) {
            return a.sameContentAs(b);
        }
    };

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public NoteEntity getNoteAt(int position) {
        return getItem(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getId();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_FOLDER) {
            FolderCard tile = new FolderCard(parent.getContext());
            tile.itemView.setOnClickListener(v -> {
                int pos = tile.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) listener.onFolder(getItem(pos).getTitle());
            });
            tile.itemView.setOnLongClickListener(v -> {
                int pos = tile.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return false;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                listener.onFolderOptions(getItem(pos).getTitle());
                return true;
            });
            return tile;
        }
        Card card = new Card(LayoutInflater.from(parent.getContext()).inflate(R.layout.note_card, parent, false));
        card.itemView.setOnClickListener(v -> {
            int pos = card.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) listener.onOpen(getItem(pos));
        });
        card.itemView.setOnLongClickListener(v -> {
            int pos = card.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || listener == null) return false;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            listener.onOptions(getItem(pos));
            return true;
        });
        return card;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        NoteEntity item = getItem(position);
        if (holder instanceof FolderCard) bindFolder((FolderCard) holder, item.getTitle());
        else bind((Card) holder, item);
    }

    /** A folder tile: name, count, and its newest notes as small cards. */
    private void bindFolder(FolderCard f, String name) {
        List<NoteEntity> inside = folders.get(name);
        if (inside == null) inside = java.util.Collections.emptyList();
        f.name.setText(name);
        f.count.setText(inside.isEmpty() ? "Empty" : inside.size() + (inside.size() == 1 ? " note" : " notes"));
        f.cells.removeAllViews();
        f.cells.setVisibility(inside.isEmpty() ? View.GONE : View.VISIBLE);
        Context ctx = f.itemView.getContext();
        int n = Math.min(4, inside.size());
        for (int i = 0; i < n; i++) {
            FolderCells.Cell cell = new FolderCells.Cell(ctx);
            cell.setBackgroundColor(ContextCompat.getColor(ctx, R.color.app_background));
            View v = LayoutInflater.from(ctx).inflate(R.layout.note_card, cell, false);
            bind(new Card(v), inside.get(i));
            cell.addView(v);
            if (i == 3 && inside.size() > 4) {
                TextView more = new TextView(ctx);
                more.setText("+" + (inside.size() - 3));
                more.setTextSize(22);
                more.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                more.setTextColor(0xFFFFFFFF);
                more.setGravity(android.view.Gravity.CENTER);
                more.setBackgroundColor(0x73000000);
                cell.addView(more);
            }
            f.cells.addView(cell);
        }
    }

    private void bind(Card c, NoteEntity note) {
        Context ctx = c.itemView.getContext();
        String type = note.getType();
        NoteData.Kind kind = NoteData.kind(type);

        int tint = note.getColor() > 0 && note.getColor() < NoteData.COLORS.length
                ? NoteData.COLORS[note.getColor()] : R.color.app_surface;
        c.card.setCardBackgroundColor(ContextCompat.getColor(ctx, tint));

        c.cover.setVisibility(View.GONE);
        c.cover.setBackground(null);
        c.mindMap.setVisibility(View.GONE);
        c.journalDate.setVisibility(View.GONE);
        c.list.setVisibility(View.GONE);
        c.wave.setVisibility(View.GONE);
        c.meta.setVisibility(View.GONE);
        c.body.setVisibility(View.GONE);
        Glide.with(c.cover).clear(c.cover);

        String title = note.getTitle() == null ? "" : note.getTitle();
        c.title.setText(title);
        c.title.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

        if (NoteLock.isLocked(note.getData())) {
            // Locked: the title (if any) and a lock; nothing of what is inside.
            c.body.setText("\uD83D\uDD12  Locked");
            c.body.setVisibility(View.VISIBLE);
            if (title.isEmpty()) {
                c.title.setText("Locked note");
                c.title.setVisibility(View.VISIBLE);
            }
        } else switch (type) {
            case NoteData.LIST: bindList(c, note); break;
            case NoteData.MINDMAP: bindMindMap(c, note); break;
            case NoteData.JOURNAL: bindJournal(c, note); break;
            case NoteData.SKETCH: bindSketch(c, note); break;
            case NoteData.AUDIO: bindAudio(c, note); break;
            default: bindText(c, note); break;
        }

        c.typeIcon.setImageResource(kind.icon);
        c.typeIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, kind.accent)));
        String when = relative(note.getTimestamp());
        int people = note.getGroupId() == null ? 0 : GroupNotes.count(note.getGroupMembers());
        int sent = SentNotes.count(note.getSentTo());
        c.date.setText(note.isReceived() ? "@" + note.getSharedFrom() + "  ·  " + when
                : people > 0 ? "Shared · " + people + "  ·  " + when
                : sent > 0 ? "Sent to " + sent + "  ·  " + when : when);
        c.pinned.setVisibility(note.isPinned() ? View.VISIBLE : View.GONE);
    }

    private void bindText(Card c, NoteEntity note) {
        String body = Markdown.plain(Markdown.repair(note.getContent()));
        c.body.setText(body);
        c.body.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
        if (c.title.getVisibility() == View.GONE && body.isEmpty()) {
            c.title.setText("Empty note");
            c.title.setVisibility(View.VISIBLE);
        }
    }

    private void bindList(Card c, NoteEntity note) {
        List<NoteData.TodoItem> items = NoteData.todos(note.getData());
        c.list.setVisibility(View.VISIBLE);
        int done = 0, tasks = 0;
        for (NoteData.TodoItem item : items) {
            if (item.picture() != null) continue;
            tasks++;
            if (item.done) done++;
        }
        for (int i = 0; i < c.listRows.length; i++) {
            TextView row = c.listRows[i];
            if (i >= items.size()) {
                row.setVisibility(View.GONE);
                continue;
            }
            NoteData.TodoItem item = items.get(i);
            row.setVisibility(View.VISIBLE);
            row.setText(item.picture() != null ? "🖼  Picture" : (item.done ? "☑  " : "☐  ") + item.text);
            row.setPaintFlags(item.done ? row.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : row.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            row.setAlpha(item.done ? 0.5f : 1f);
        }
        if (tasks > 0) {
            c.meta.setVisibility(View.VISIBLE);
            String more = items.size() > LIST_PREVIEW ? "  ·  +" + (items.size() - LIST_PREVIEW) + " more" : "";
            c.meta.setText(done == tasks ? "All done" + more : done + " of " + tasks + " done" + more);
        }
        if (c.title.getVisibility() == View.GONE) {
            c.title.setText("List");
            c.title.setVisibility(View.VISIBLE);
        }
    }

    private void bindMindMap(Card c, NoteEntity note) {
        NoteData.MindMap map = NoteData.mindMap(note.getData());
        List<NoteData.MindNode> nodes = map.nodes;
        c.mindMap.setVisibility(View.VISIBLE);
        c.mindMap.setFolder(NoteData.dir(c.mindMap.getContext(), note.getId()));
        c.mindMap.setMap(map);
        c.mindMap.post(() -> c.mindMap.fit(false));
        if (c.title.getVisibility() == View.GONE) {
            for (NoteData.MindNode n : nodes) {
                if (n.parent < 0 && !n.text.isEmpty()) {
                    c.title.setText(n.text);
                    c.title.setVisibility(View.VISIBLE);
                }
            }
        }
        c.meta.setVisibility(View.VISIBLE);
        c.meta.setText(nodes.size() + (nodes.size() == 1 ? " idea" : " ideas"));
    }

    private void bindJournal(Card c, NoteEntity note) {
        NoteData.Journal journal = NoteData.journal(note.getData(), note.getTimestamp());
        Date day = new Date(journal.day);
        c.journalDate.setVisibility(View.VISIBLE);
        c.day.setText(new SimpleDateFormat("d", Locale.getDefault()).format(day));
        c.weekday.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(day));
        c.month.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(day));
        c.mood.setText(journal.mood);
        if (!journal.media.isEmpty()) {
            File first = new File(NoteData.dir(c.itemView.getContext(), note.getId()), journal.media.get(0));
            c.cover.setVisibility(View.VISIBLE);
            coverSize(c, false);
            Glide.with(c.cover).load(first).centerCrop().into(c.cover);
            if (journal.media.size() > 1) {
                c.meta.setVisibility(View.VISIBLE);
                c.meta.setText(journal.media.size() + " photos & videos");
            }
        }
        String body = Markdown.plain(Markdown.repair(note.getContent()));
        c.body.setText(body);
        c.body.setMaxLines(journal.media.isEmpty() ? 6 : 3);
        c.body.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindSketch(Card c, NoteEntity note) {
        File png = new File(NoteData.dir(c.itemView.getContext(), note.getId()), SketchActivity.PREVIEW_FILE);
        if (png.exists()) {
            c.cover.setVisibility(View.VISIBLE);
            coverSize(c, true);
            c.cover.setBackgroundColor(0xFFFFFFFF);
            // The file is rewritten in place, so the note's edit time is the cache key.
            Glide.with(c.cover).load(png).signature(new ObjectKey(note.getTimestamp())).into(c.cover);
        }
        if (c.title.getVisibility() == View.GONE && !png.exists()) {
            c.title.setText("Sketch");
            c.title.setVisibility(View.VISIBLE);
        }
    }

    private void bindAudio(Card c, NoteEntity note) {
        List<NoteData.Recording> recordings = NoteData.recordings(note.getData());
        long total = 0;
        for (NoteData.Recording r : recordings) total += r.durationMs;
        if (!recordings.isEmpty()) {
            c.wave.setVisibility(View.VISIBLE);
            c.wave.setWave(recordings.get(0).wave, 0);
            c.meta.setVisibility(View.VISIBLE);
            c.meta.setText(recordings.size() + (recordings.size() == 1 ? " recording" : " recordings")
                    + "  ·  " + SecretGalleryAdapter.formatDuration(total));
        }
        if (c.title.getVisibility() == View.GONE) {
            c.title.setText(recordings.isEmpty() ? "Audio note" : recordings.get(0).label);
            c.title.setVisibility(View.VISIBLE);
        }
    }

    /**
     * A photo cover is a fixed-height crop; a sketch is shown whole at its own shape (the
     * saved preview is already cropped to the ink), up to a sensible height.
     */
    private static void coverSize(Card c, boolean whole) {
        ViewGroup.LayoutParams lp = c.cover.getLayoutParams();
        float density = c.cover.getResources().getDisplayMetrics().density;
        lp.height = whole ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.round(140 * density);
        c.cover.setLayoutParams(lp);
        c.cover.setAdjustViewBounds(whole);
        c.cover.setMaxHeight(whole ? Math.round(280 * density) : Integer.MAX_VALUE);
        c.cover.setScaleType(whole ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
    }

    /** "14:32" today, "Yesterday", a weekday this week, then "12 Mar" or "12 Mar 2023". */
    static String relative(long millis) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Date date = new Date(millis);
        boolean sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR);
        int days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR);
        if (sameYear && days == 0) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        if (sameYear && days == 1) return "Yesterday";
        if (sameYear && days > 1 && days < 7) return new SimpleDateFormat("EEEE", Locale.getDefault()).format(date);
        return new SimpleDateFormat(sameYear ? "d MMM" : "d MMM yyyy", Locale.getDefault()).format(date);
    }

    /** A folder on the All grid: built in code, a card with a header and {@link FolderCells}. */
    static final class FolderCard extends RecyclerView.ViewHolder {
        final TextView name, count;
        final FolderCells cells;

        FolderCard(Context context) {
            this(context, new MaterialCardView(context));
        }

        private FolderCard(Context context, MaterialCardView card) {
            super(card);
            float d = context.getResources().getDisplayMetrics().density;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = Math.round(5 * d);
            lp.setMargins(m, m, m, m);
            card.setLayoutParams(lp);
            card.setRadius(18 * d);
            card.setCardElevation(0);
            card.setStrokeColor(ContextCompat.getColor(context, R.color.divider));
            card.setStrokeWidth(Math.max(1, Math.round(0.5f * d)));
            card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.app_surface));
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            int p = Math.round(14 * d);
            column.setPadding(p, p, p, p);
            LinearLayout head = new LinearLayout(context);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.ic_folder);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_yellow)));
            head.addView(icon, new LinearLayout.LayoutParams(Math.round(22 * d), Math.round(22 * d)));
            name = new TextView(context);
            name.setTextSize(16);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            count = new TextView(context);
            count.setTextSize(12);
            count.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            head.addView(count);
            column.addView(head);
            cells = new FolderCells(context);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = Math.round(10 * d);
            column.addView(cells, cl);
            card.addView(column);
        }
    }

    static final class Card extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView cover, typeIcon, pinned;
        final MindMapActivity.MindMapView mindMap;
        final View journalDate;
        final TextView day, weekday, month, mood, title, body, meta, date;
        final LinearLayout list;
        final TextView[] listRows = new TextView[LIST_PREVIEW];
        final AudioNoteActivity.WaveformView wave;

        Card(@NonNull View v) {
            super(v);
            card = (MaterialCardView) v;
            cover = v.findViewById(R.id.cardCover);
            mindMap = v.findViewById(R.id.cardMindMap);
            journalDate = v.findViewById(R.id.cardJournalDate);
            day = v.findViewById(R.id.cardDay);
            weekday = v.findViewById(R.id.cardWeekday);
            month = v.findViewById(R.id.cardMonth);
            mood = v.findViewById(R.id.cardMood);
            title = v.findViewById(R.id.cardTitle);
            body = v.findViewById(R.id.cardBody);
            list = v.findViewById(R.id.cardList);
            wave = v.findViewById(R.id.cardWave);
            meta = v.findViewById(R.id.cardMeta);
            typeIcon = v.findViewById(R.id.cardTypeIcon);
            date = v.findViewById(R.id.cardDate);
            pinned = v.findViewById(R.id.cardPinned);
            for (int i = 0; i < LIST_PREVIEW; i++) {
                TextView row = new TextView(v.getContext());
                row.setTextSize(14);
                row.setMaxLines(1);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setTextColor(ContextCompat.getColor(v.getContext(), R.color.text_body));
                row.setPadding(0, 2, 0, 2);
                list.addView(row);
                listRows[i] = row;
            }
        }
    }
}
