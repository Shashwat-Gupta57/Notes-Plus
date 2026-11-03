package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The written note types:
 * <ul>
 *   <li><b>Text</b>: a title and words.</li>
 *   <li><b>List</b>: items to tick off; Enter adds the next one, the handle reorders, a swipe
 *       deletes.</li>
 *   <li><b>Journal</b>: a day, with a big date, a mood, words, and photos or videos.</li>
 * </ul>
 */
public class NoteEditorActivity extends NoteActivity {
    private static final int PICK_MEDIA = 41;
    private static final String[] MOODS = {"😄", "🙂", "😐", "😔",
            "😢", "😡", "😴", "🤩", "❤️"};

    private EditText title, body;
    private RichText rich;
    private TextView footer;
    private String type;

    // List
    private final List<NoteData.TodoItem> items = new ArrayList<>();
    private ListAdapter listAdapter;
    private RecyclerView listView;
    private View listAdd;
    private TextView listProgress;
    private LinearProgressIndicator listProgressBar;

    // Journal
    private NoteData.Journal journal;
    private MediaAdapter mediaAdapter;
    private LinearLayout moodRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_editor);
        title = findViewById(R.id.editTextTitle);
        body = findViewById(R.id.editTextContent);
        footer = findViewById(R.id.editorFooter);
        Toolbar toolbar = findViewById(R.id.toolbarEditor);
        setUpNote(findViewById(R.id.noteRoot), toolbar);
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        type = note.getType();
        title.setText(note.getTitle());
        title.addTextChangedListener(simpleWatcher(this::markDirty));
        body.addTextChangedListener(simpleWatcher(this::markDirty));
        if (NoteData.LIST.equals(type)) setUpList(note);
        else {
            rich = new RichText(body, this::markDirty);
            rich.setMarkdown(note.getContent());
            rich.setLinks(title -> NoteLinks.resolve(title, linkNotes) != null, this::showLink);
            if (!readOnly) LinkSuggest.attach(body, () -> linkNotes, () -> note == null ? 0 : note.getId());
            setUpFormatBar();
        }
        if (NoteData.JOURNAL.equals(type)) setUpJournal(note);

        title.setHint(NoteData.JOURNAL.equals(type) ? "Give the day a title"
                : NoteData.LIST.equals(type) ? "List name" : "Title");
        body.addTextChangedListener(simpleWatcher(this::updateFooter));
        updateFooter();
        if (note.getId() <= 0) (NoteData.JOURNAL.equals(type) ? body : title).requestFocus();
    }

    @Override
    protected void onLinksLoaded() {
        if (rich != null) rich.restyleLinks();
    }

    @Override
    protected boolean collect(NoteEntity note) {
        String t = title.getText().toString().trim();
        note.setTitle(t);
        if (NoteData.LIST.equals(type)) {
            StringBuilder text = new StringBuilder();
            List<NoteData.TodoItem> kept = new ArrayList<>();
            for (NoteData.TodoItem item : items) {
                if (item.text.trim().isEmpty()) continue;
                kept.add(item);
                text.append(item.text.trim()).append('\n');
            }
            note.setData(NoteData.todosJson(kept, wrapItems));
            note.setContent(text.toString().trim());
            return !kept.isEmpty() || !t.isEmpty();
        }
        String words = (rich != null ? rich.markdown() : body.getText().toString()).trim();
        note.setContent(words);
        if (NoteData.JOURNAL.equals(type)) {
            note.setData(NoteData.journalJson(journal));
            return !words.isEmpty() || !t.isEmpty() || !journal.media.isEmpty() || !journal.mood.isEmpty();
        }
        return !words.isEmpty() || !t.isEmpty();
    }

    @Override
    protected void updateStatusText() {
        updateFooter();
    }

    private void updateFooter() {
        if (note == null) return;
        String edited = note.getId() > 0
                ? "Edited " + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(note.getTimestamp()))
                : "New " + NoteData.kind(type).label.toLowerCase(Locale.getDefault());
        String statusText = syncStatus != null && !syncStatus.isEmpty() ? "  ·  " + syncStatus : "";
        if (NoteData.LIST.equals(type)) {
            footer.setText(edited + statusText);
            return;
        }
        String text = body.getText().toString().trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        footer.setText(edited + "  ·  " + words + (words == 1 ? " word" : " words") + statusText);
    }

    // ===================================================================== list

    /** List items wrap onto more lines (the default) or each stays on one line. */
    private boolean wrapItems = true;

    @Nullable
    @Override
    protected Boolean wrapText() {
        return NoteData.LIST.equals(type) ? wrapItems : null;
    }

    @Override
    protected void setWrapText(boolean wrap) {
        wrapItems = wrap;
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void setUpList(NoteEntity note) {
        body.setVisibility(View.GONE);
        findViewById(R.id.listSection).setVisibility(View.VISIBLE);
        listProgress = findViewById(R.id.listProgress);
        listProgressBar = findViewById(R.id.listProgressBar);
        items.clear();
        items.addAll(NoteData.todos(note.getData()));
        // Repeating items whose next day has come show unticked; saved with the next edit, if any
        // (every device works out the same, so nothing needs pushing just for this).
        Due.rollover(items, Due.today());
        wrapItems = NoteData.wraps(note.getData());
        if (items.isEmpty()) items.add(new NoteData.TodoItem("", false));
        if (listAdapter != null) {
            // Loaded again (a newer version from another device): same list, new items.
            listAdapter.notifyDataSetChanged();
            updateProgress();
            return;
        }

        listAdapter = new ListAdapter();
        listView = findViewById(R.id.listItems);
        listAdd = findViewById(R.id.listAdd);
        listView.setLayoutManager(new LinearLayoutManager(this));
        scrollAsList();

        ItemTouchHelper touch = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            // Only items move or swipe away: the page above them and "Add item" below stay put.
            @Override
            public int getDragDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder holder) {
                return holder.getBindingAdapter() == listAdapter ? super.getDragDirs(rv, holder) : 0;
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder holder) {
                return holder.getBindingAdapter() == listAdapter ? super.getSwipeDirs(rv, holder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                if (to.getBindingAdapter() != listAdapter) return false;
                int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
                Collections.swap(items, a, b);
                listAdapter.notifyItemMoved(a, b);
                markDirty();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int pos = holder.getBindingAdapterPosition();
                if (pos < 0 || pos >= items.size()) return;
                items.remove(pos);
                listAdapter.notifyItemRemoved(pos);
                updateProgress();
                markDirty();
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // The handle starts drags; long-press stays text selection.
            }
        });
        if (!readOnly) touch.attachToRecyclerView(listView);
        listAdapter.touch = touch;
        listAdd.setOnClickListener(v -> addItemAt(items.size()));
        if (readOnly) listAdd.setVisibility(View.GONE);
        updateProgress();
    }

    /**
     * A list scrolls as its own RecyclerView, so only the rows on screen exist and a list of 500
     * items opens and scrolls like a short one. (Inside the page's scroll view, with nested
     * scrolling off, every row was made and laid out at once.) The page above the items (title,
     * progress) and "Add item" below them ride along as its first and last rows.
     */
    private void scrollAsList() {
        androidx.core.widget.NestedScrollView scroll = findViewById(R.id.editorScroll);
        ViewGroup page = (ViewGroup) scroll.getChildAt(0);
        ViewGroup section = findViewById(R.id.listSection);
        section.removeView(listView);
        section.removeView(listAdd);
        scroll.removeView(page);
        ViewGroup root = (ViewGroup) scroll.getParent();
        int at = root.indexOfChild(scroll);
        root.removeView(scroll);
        listView.setLayoutParams(scroll.getLayoutParams());
        listView.setNestedScrollingEnabled(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, page.getPaddingBottom());
        listView.setItemViewCacheSize(12);
        page.setPadding(page.getPaddingLeft(), page.getPaddingTop(), page.getPaddingRight(),
                Math.round(8 * getResources().getDisplayMetrics().density));
        root.addView(listView, at);
        listView.setAdapter(new androidx.recyclerview.widget.ConcatAdapter(new OneRow(page), listAdapter, new OneRow(listAdd)));
    }

    /** One fixed view as a row of the list (the page above the items, "Add item" below). */
    private static final class OneRow extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View view;

        OneRow(View view) {
            this.view = view;
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    view.getLayoutParams() == null ? ViewGroup.LayoutParams.WRAP_CONTENT : view.getLayoutParams().height));
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return 1;
        }
    }

    /** A note sent to me: nothing to add, tick, reorder or re-date. */
    @Override
    protected void lockForReading() {
        super.lockForReading();
        if (listAdd != null) listAdd.setVisibility(View.GONE); // A row of the list now: not always findable.
        for (int id : new int[]{R.id.listAdd, R.id.btnAddMedia, R.id.formatBar}) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
        View date = findViewById(R.id.journalDate);
        if (date != null) date.setOnClickListener(null);
        if (moodRow != null) for (int i = 0; i < moodRow.getChildCount(); i++) moodRow.getChildAt(i).setOnClickListener(null);
    }

    private void addItemAt(int position) {
        items.add(position, new NoteData.TodoItem("", false));
        listAdapter.focusPosition = position;
        listAdapter.notifyItemInserted(position);
        listView.scrollToPosition(position + 1); // Made on screen, so it can take the focus (+1: the page row).
        updateProgress();
        markDirty();
    }

    private void updateProgress() {
        int done = 0, total = 0;
        for (NoteData.TodoItem item : items) {
            if (item.text.trim().isEmpty() || item.picture() != null) continue;
            total++;
            if (item.done) done++;
        }
        int overdue = Due.overdue(items, Due.today());
        listProgress.setText((total == 0 ? "Nothing yet" : done == total ? "All done 🎉"
                : done + " of " + total + " done") + (overdue > 0 ? "  ·  " + overdue + " overdue" : ""));
        listProgressBar.setProgressCompat(total == 0 ? 0 : done * 100 / total, true);
    }

    private final class ListAdapter extends RecyclerView.Adapter<ListAdapter.Holder> {
        ItemTouchHelper touch;
        int focusPosition = -1;

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_list_entry, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            NoteData.TodoItem item = items.get(position);
            h.bound = null;
            String picture = item.picture();
            h.column.setVisibility(picture == null ? View.VISIBLE : View.GONE);
            h.check.setVisibility(picture == null ? View.VISIBLE : View.INVISIBLE);
            h.picture.setVisibility(picture == null ? View.GONE : View.VISIBLE);
            if (picture != null) {
                // A picture item: shown, moved and swiped away like any item; nothing to type.
                File file = note == null || note.getId() <= 0 ? null
                        : new File(NoteData.dir(NoteEditorActivity.this, note.getId()), picture);
                if (file != null && file.exists()) Glide.with(h.picture).load(file).into(h.picture);
                else h.picture.setImageDrawable(null);
                h.text.setText("");
                h.bound = item;
                h.showDue(false);
                return;
            }
            Glide.with(h.picture).clear(h.picture);
            // Still one item per line for the keyboard (Next adds the next item); only the view wraps.
            h.text.setHorizontallyScrolling(!wrapItems);
            h.text.setMaxLines(wrapItems ? Integer.MAX_VALUE : 1);
            h.text.setText(item.text);
            h.check.setChecked(item.done);
            h.styleDone(item.done);
            h.bound = item;
            h.showDue(false);
            if (position == focusPosition) {
                focusPosition = -1;
                h.text.post(() -> {
                    h.text.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (imm != null) imm.showSoftInput(h.text, 0);
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final EditText text;
            final CheckBox check;
            final ImageView picture, clock;
            final View column;
            final TextView due;
            NoteData.TodoItem bound;

            @android.annotation.SuppressLint("ClickableViewAccessibility")
            Holder(@NonNull View v) {
                super(v);
                text = v.findViewById(R.id.itemText);
                check = v.findViewById(R.id.itemCheck);
                picture = v.findViewById(R.id.itemPicture);
                column = v.findViewById(R.id.itemColumn);
                due = v.findViewById(R.id.itemDue);
                clock = v.findViewById(R.id.itemClock);
                if (!readOnly) {
                    // The clock shows on the item being typed in; once it has a day, its pill takes over.
                    text.setOnFocusChangeListener((f, has) -> showDue(false));
                    LinkSuggest.attach(text, () -> linkNotes, () -> note == null ? 0 : note.getId());
                    View.OnClickListener open = c -> {
                        if (bound != null) showDueSheet(bound);
                    };
                    clock.setOnClickListener(open);
                    due.setOnClickListener(open);
                }
                if (readOnly) {
                    text.setKeyListener(null);
                    text.setFocusable(false);
                    text.setTextIsSelectable(true);
                    check.setEnabled(false);
                    v.findViewById(R.id.itemHandle).setVisibility(View.INVISIBLE);
                }
                text.addTextChangedListener(simpleWatcher(() -> {
                    if (bound == null || bound.picture() != null) return;
                    bound.text = text.getText().toString();
                    updateProgress();
                    markDirty();
                }));
                check.setOnCheckedChangeListener((b, checked) -> {
                    if (bound == null) return;
                    Due.tick(bound, checked, Due.today());
                    styleDone(checked);
                    showDue(true);
                    updateProgress();
                    markDirty();
                    if (checked) b.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                });
                text.setOnEditorActionListener((tv, action, event) -> {
                    boolean enter = action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
                    if (!enter) return false;
                    addItemAt(getBindingAdapterPosition() + 1);
                    return true;
                });
                v.findViewById(R.id.itemHandle).setOnTouchListener((hv, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touch.startDrag(this);
                    return false;
                });
            }

            /** The item's date pill (coloured: overdue, today, later) and, while typing, the clock. */
            void showDue(boolean animate) {
                NoteData.TodoItem item = bound;
                long today = Due.today();
                int s = item == null ? Due.NONE : Due.status(item, today);
                boolean was = due.getVisibility() == View.VISIBLE;
                if (s == Due.NONE) {
                    due.setVisibility(View.GONE);
                } else {
                    int color = ContextCompat.getColor(NoteEditorActivity.this, s == Due.OVERDUE ? R.color.destructive
                            : s == Due.TODAY ? R.color.tile_orange : R.color.text_secondary);
                    due.setText(Due.pill(item, today));
                    due.setTextColor(color);
                    due.setCompoundDrawableTintList(ColorStateList.valueOf(color));
                    GradientDrawable pill = new GradientDrawable();
                    pill.setCornerRadius(10 * getResources().getDisplayMetrics().density);
                    pill.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x24));
                    due.setBackground(pill);
                    due.setAlpha(s == Due.DONE ? 0.6f : 1f);
                    due.setVisibility(View.VISIBLE);
                    if (animate && !was) {
                        due.setScaleX(0.6f);
                        due.setScaleY(0.6f);
                        due.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                    }
                }
                clock.setVisibility(!readOnly && item != null && item.picture() == null && s == Due.NONE
                        && text.hasFocus() ? View.VISIBLE : View.GONE);
            }

            void styleDone(boolean done) {
                text.setPaintFlags(done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                        : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                text.setAlpha(done ? 0.5f : 1f);
            }
        }
    }

    // ================================================================== due dates

    /** The item's day, time and repeat: every tap applies at once, closing keeps it. */
    private void showDueSheet(NoteData.TodoItem item) {
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null && getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_due, null);
        sheet.setContentView(v);
        String words = item.text == null ? "" : item.text.trim();
        ((TextView) v.findViewById(R.id.dueItem)).setText(words.isEmpty() ? "This item" : words);
        TextView summary = v.findViewById(R.id.dueSummary);
        View remove = v.findViewById(R.id.dueRemove);
        ChipGroup days = v.findViewById(R.id.dueDays), times = v.findViewById(R.id.dueTimes), repeats = v.findViewById(R.id.dueRepeats);
        LinearLayout weekdays = v.findViewById(R.id.dueWeekdays);
        long today = Due.today();
        int wd = Due.weekday(today);
        long[] quick = {today, today + 1, wd >= 6 ? today : today + (6 - wd), today + (8 - wd)};
        String[] quickNames = {"Today", "Tomorrow", "This weekend", "Next week"};
        String[] kinds = {null, "daily", "weekdays", "weekly", "monthly"};
        String[] kindNames = {"Never", "Daily", "Weekdays", "Weekly", "Monthly"};

        Runnable[] refresh = new Runnable[1];
        Runnable changed = () -> {
            refresh[0].run();
            int at = items.indexOf(item);
            if (at >= 0) listAdapter.notifyItemChanged(at);
            updateProgress();
            markDirty();
        };
        List<Chip> dayChips = new ArrayList<>();
        for (int i = 0; i < quick.length; i++) {
            long d = quick[i];
            dayChips.add(dueChip(days, quickNames[i], () -> {
                Due.setDay(item, d);
                changed.run();
            }));
        }
        Chip pick = dueChip(days, "Pick a date…", () -> {
            int[] c = Due.civil(Due.dated(item) ? Due.day(item.due) : today);
            new DatePickerDialog(this, (picker, y, m, day) -> {
                Due.setDay(item, Due.day(y, m + 1, day));
                changed.run();
            }, c[0], c[1] - 1, c[2]).show();
            refresh[0].run(); // The chip stays as it was until a day is picked.
        });
        Chip time = dueChip(times, "Add time", () -> {
            int h = 9, min = 0;
            if (item.time != null) {
                h = Integer.parseInt(item.time.substring(0, 2));
                min = Integer.parseInt(item.time.substring(3));
            }
            new android.app.TimePickerDialog(this, (picker, hh, mm) -> {
                if (!Due.dated(item)) Due.setDay(item, today);
                item.time = String.format(Locale.ROOT, "%02d:%02d", hh, mm);
                changed.run();
            }, h, min, android.text.format.DateFormat.is24HourFormat(this)).show();
            refresh[0].run();
        });
        time.setChipIconResource(R.drawable.ic_clock);
        time.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));
        time.setChipIconVisible(true);
        time.setOnCloseIconClickListener(c -> {
            item.time = null;
            changed.run();
        });
        List<Chip> repeatChips = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            repeatChips.add(dueChip(repeats, kindNames[i], () -> {
                Due.setRepeat(item, kind, today);
                changed.run();
            }));
        }
        float density = getResources().getDisplayMetrics().density;
        TextView[] dayToggles = new TextView[8];
        String[] letters = {"M", "T", "W", "T", "F", "S", "S"};
        for (int d = 1; d <= 7; d++) {
            int weekday = d;
            TextView t = new TextView(this);
            t.setText(letters[d - 1]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(15);
            t.setContentDescription(Due.WEEKDAYS[d - 1]);
            t.setOnClickListener(c -> {
                Due.toggleWeekday(item, weekday, today);
                c.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                changed.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(40 * density), 1);
            lp.setMarginEnd(d < 7 ? Math.round(6 * density) : 0);
            weekdays.addView(t, lp);
            dayToggles[d] = t;
        }
        remove.setOnClickListener(c -> {
            Due.clear(item);
            changed.run();
            sheet.dismiss();
        });

        refresh[0] = () -> {
            boolean dated = Due.dated(item);
            long d = Due.day(item.due);
            summary.setText(Due.summary(item, today));
            remove.setVisibility(dated ? View.VISIBLE : View.GONE);
            // One chip lights up for the item's day ("Today" before "This weekend" on a Saturday).
            int lit = -1;
            for (int i = 0; i < quick.length && lit < 0; i++) if (dated && d == quick[i]) lit = i;
            for (int i = 0; i < quick.length; i++) dayChips.get(i).setChecked(i == lit);
            pick.setChecked(dated && lit < 0);
            pick.setText(dated && lit < 0 ? Due.when(d, today) : "Pick a date…");
            String t = Due.time(item.time);
            time.setText(t != null ? t : "Add time");
            time.setChecked(t != null);
            time.setCloseIconVisible(t != null);
            String kind = !Due.repeats(item) ? null : item.repeat.startsWith("weekly:") ? "weekly"
                    : item.repeat.startsWith("monthly:") ? "monthly" : item.repeat;
            for (int i = 0; i < kinds.length; i++) repeatChips.get(i).setChecked(java.util.Objects.equals(kinds[i], kind));
            weekdays.setVisibility("weekly".equals(kind) ? View.VISIBLE : View.GONE);
            boolean[] on = Due.weekDays(item.repeat);
            for (int w = 1; w <= 7; w++) {
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(ContextCompat.getColor(this, on[w] ? R.color.brand_yellow : R.color.app_surface));
                circle.setStroke(on[w] ? 0 : Math.round(density), ContextCompat.getColor(this, R.color.divider));
                dayToggles[w].setBackground(circle);
                dayToggles[w].setTextColor(ContextCompat.getColor(this, on[w] ? R.color.black : R.color.text_primary));
            }
        };
        refresh[0].run();
        sheet.show();
    }

    /** A chip in the due sheet, styled like the grid's filter chips; the sheet sets whether it's lit. */
    private Chip dueChip(ChipGroup group, String label, Runnable onClick) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setChipBackgroundColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ContextCompat.getColor(this, R.color.brand_yellow), ContextCompat.getColor(this, R.color.app_surface)}));
        chip.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ContextCompat.getColor(this, R.color.black), ContextCompat.getColor(this, R.color.text_primary)}));
        chip.setChipStrokeWidth(getResources().getDisplayMetrics().density);
        chip.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider)));
        chip.setOnClickListener(c -> onClick.run());
        group.addView(chip);
        return chip;
    }

    // ================================================================== journal

    private void setUpJournal(NoteEntity note) {
        journal = NoteData.journal(note.getData(), startOfDay(note.getTimestamp()));
        findViewById(R.id.journalHeader).setVisibility(View.VISIBLE);
        body.setHint("What happened today?");
        findViewById(R.id.journalDate).setOnClickListener(v -> pickDay());
        showDay();

        moodRow = findViewById(R.id.moodRow);
        moodRow.removeAllViews(); // Loaded again: no second row of moods.
        float density = getResources().getDisplayMetrics().density;
        for (String mood : MOODS) {
            TextView chip = new TextView(this);
            chip.setText(mood);
            chip.setTextSize(24);
            chip.setGravity(Gravity.CENTER);
            int size = Math.round(46 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(Math.round(6 * density));
            chip.setOnClickListener(v -> {
                journal.mood = mood.equals(journal.mood) ? "" : mood;
                showMoods();
                v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(90)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
            });
            moodRow.addView(chip, lp);
        }
        showMoods();

        RecyclerView strip = findViewById(R.id.journalMedia);
        strip.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mediaAdapter = new MediaAdapter();
        strip.setAdapter(mediaAdapter);
        findViewById(R.id.btnAddMedia).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, PICK_MEDIA);
        });
    }

    private void showMoods() {
        for (int i = 0; i < moodRow.getChildCount(); i++) {
            View chip = moodRow.getChildAt(i);
            boolean selected = MOODS[i].equals(journal.mood);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(selected ? ContextCompat.getColor(this, R.color.note_purple) : 0);
            chip.setBackground(bg);
            chip.setAlpha(journal.mood.isEmpty() || selected ? 1f : 0.45f);
        }
    }

    private void showDay() {
        Date d = new Date(journal.day);
        ((TextView) findViewById(R.id.journalDay)).setText(new SimpleDateFormat("d", Locale.getDefault()).format(d));
        ((TextView) findViewById(R.id.journalWeekday)).setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(d));
        ((TextView) findViewById(R.id.journalMonthYear)).setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d));
    }

    private void pickDay() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(journal.day);
        new DatePickerDialog(this, (picker, y, m, day) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.set(y, m, day, 0, 0, 0);
            chosen.set(Calendar.MILLISECOND, 0);
            journal.day = chosen.getTimeInMillis();
            showDay();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MEDIA || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        withFolder(dir -> AppDatabase.IO.execute(() -> {
            List<String> added = new ArrayList<>();
            for (Uri uri : uris) {
                String mime = getContentResolver().getType(uri);
                boolean video = mime != null && mime.startsWith("video");
                String name = (video ? "VID_" : "IMG_") + System.currentTimeMillis() + "_" + added.size()
                        + (video ? ".mp4" : ".jpg");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(new File(dir, name))) {
                    if (in == null) continue;
                    VaultCrypto.copy(in, out);
                    added.add(name);
                } catch (Exception e) {
                    android.util.Log.w("NoteEditor", "Could not add " + uri, e);
                }
            }
            runOnUiThread(() -> {
                int start = journal.media.size();
                journal.media.addAll(added);
                mediaAdapter.notifyItemRangeInserted(start, added.size());
                if (added.size() < uris.size()) {
                    Toast.makeText(this, "Some items couldn't be added", Toast.LENGTH_SHORT).show();
                }
            });
        }));
    }

    private final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float density = getResources().getDisplayMetrics().density;
            FrameLayout tile = new FrameLayout(parent.getContext());
            int size = Math.round(132 * density);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
            lp.setMarginEnd(Math.round(8 * density));
            tile.setLayoutParams(lp);
            tile.setBackgroundResource(R.drawable.cell_background);
            tile.setClipToOutline(true);
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
            ImageView play = new ImageView(parent.getContext());
            play.setImageResource(R.drawable.ic_play_big);
            play.setColorFilter(0xFFFFFFFF);
            int playSize = Math.round(36 * density);
            tile.addView(play, new FrameLayout.LayoutParams(playSize, playSize, Gravity.CENTER));
            return new Holder(tile, image, play);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String name = journal.media.get(position);
            File file = new File(NoteData.dir(NoteEditorActivity.this, note.getId()), name);
            Glide.with(h.image).load(file).centerCrop().into(h.image);
            h.play.setVisibility(NoteData.isVideo(name) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> openMedia(file, NoteData.isVideo(name)));
            h.itemView.setOnLongClickListener(v -> {
                if (readOnly) return false;
                new MaterialAlertDialogBuilder(NoteEditorActivity.this)
                        .setTitle("Remove from this entry?")
                        .setPositiveButton("Remove", (d, w) -> {
                            int pos = h.getBindingAdapterPosition();
                            if (pos < 0) return;
                            journal.media.remove(pos);
                            notifyItemRemoved(pos);
                            if (!file.delete()) android.util.Log.w("NoteEditor", "Could not delete " + file);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return journal == null ? 0 : journal.media.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image, play;

            Holder(View v, ImageView image, ImageView play) {
                super(v);
                this.image = image;
                this.play = play;
            }
        }
    }

    /** Full-screen view of one photo or video from the entry. */
    private void openMedia(File file, boolean video) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (video) {
            PlayerView view = new PlayerView(this);
            ExoPlayer player = new ExoPlayer.Builder(this).build();
            view.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
            player.prepare();
            player.play();
            dialog.setContentView(view);
            dialog.setOnDismissListener(d -> player.release());
        } else {
            PhotoView view = new PhotoView(this);
            Glide.with(this).load(file).into(view);
            view.setOnViewTapListener((v, x, y) -> dialog.dismiss());
            dialog.setContentView(view);
        }
        dialog.show();
    }

    // ================================================================== helpers

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static TextWatcher simpleWatcher(Runnable onChange) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                onChange.run();
            }
        };
    }
    /** Line spacing for text notes, this phone only (it is how the words are shown, not part of them). */
    private static final String PREF_SPACING = "note_line_spacing";

    private void setUpFormatBar() {
        View bar = findViewById(R.id.formatBar);
        if (bar == null) return;
        bar.setVisibility(View.VISIBLE);
        body.setLineSpacing(0f, SecurityHelper.getAppPrefs(this).getFloat(PREF_SPACING, 1.25f));
        TextView u = findViewById(R.id.btnFmtUnderline), s = findViewById(R.id.btnFmtStrike);
        u.setPaintFlags(u.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        s.setPaintFlags(s.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        findViewById(R.id.btnFmtBold).setOnClickListener(v -> rich.toggle(RichText.BOLD));
        findViewById(R.id.btnFmtItalic).setOnClickListener(v -> rich.toggle(RichText.ITALIC));
        u.setOnClickListener(v -> rich.toggle(RichText.UNDERLINE));
        s.setOnClickListener(v -> rich.toggle(RichText.STRIKE));
        findViewById(R.id.btnFmtCode).setOnClickListener(v -> rich.toggle(RichText.CODE));
        findViewById(R.id.btnFmtHeading).setOnClickListener(v -> showHeadingMenu());
        findViewById(R.id.btnFmtColor).setOnClickListener(v -> showColorMenu());
        findViewById(R.id.btnFmtSpacing).setOnClickListener(v -> showSpacingMenu());
        findViewById(R.id.btnFmtBullet).setOnClickListener(v -> rich.block(Markdown.Block.BULLET));
        findViewById(R.id.btnFmtNumber).setOnClickListener(v -> rich.block(Markdown.Block.NUMBER));
        findViewById(R.id.btnFmtTask).setOnClickListener(v -> rich.block(Markdown.Block.TASK));
    }

    private void showHeadingMenu() {
        String[] names = {"Normal text", "Heading 1", "Heading 2", "Heading 3", "Quote"};
        Markdown.Block[] kinds = {Markdown.Block.NORMAL, Markdown.Block.H1, Markdown.Block.H2, Markdown.Block.H3, Markdown.Block.QUOTE};
        Markdown.Block now = rich.blockAt();
        int checked = 0;
        for (int i = 0; i < kinds.length; i++) if (kinds[i] == now) checked = i;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Paragraph style · applies to the whole line")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    if (kinds[which] == Markdown.Block.NORMAL) {
                        if (now != Markdown.Block.NORMAL) rich.block(now);
                    } else if (kinds[which] != now) {
                        rich.block(kinds[which]);
                    }
                    d.dismiss();
                })
                .show();
    }

    private void showColorMenu() {
        String[] names = {"Default", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Grey"};
        String[] colors = {null, "#FF3B30", "#FF9500", "#FFCC00", "#34C759", "#0A84FF", "#AF52DE", "#8E8E93"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Text colour")
                .setItems(names, (d, which) -> rich.color(colors[which]))
                .show();
    }

    private void showSpacingMenu() {
        String[] names = {"Compact", "Normal", "Relaxed", "Wide"};
        float[] values = {1.0f, 1.25f, 1.5f, 1.8f};
        float now = SecurityHelper.getAppPrefs(this).getFloat(PREF_SPACING, 1.25f);
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - now) < 0.01f) checked = i;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Line spacing")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    SecurityHelper.getAppPrefs(this).edit().putFloat(PREF_SPACING, values[which]).apply();
                    body.setLineSpacing(0f, values[which]);
                    d.dismiss();
                })
                .show();
    }
}
