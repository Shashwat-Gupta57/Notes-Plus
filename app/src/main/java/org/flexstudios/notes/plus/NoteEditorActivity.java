package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Paint;
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
    private TextView footer;
    private String type;

    // List
    private final List<NoteData.TodoItem> items = new ArrayList<>();
    private ListAdapter listAdapter;
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
        if (NoteData.LIST.equals(type)) setUpList(note);
        else body.setText(note.getContent());
        if (NoteData.JOURNAL.equals(type)) setUpJournal(note);

        title.setHint(NoteData.JOURNAL.equals(type) ? "Give the day a title"
                : NoteData.LIST.equals(type) ? "List name" : "Title");
        body.addTextChangedListener(simpleWatcher(this::updateFooter));
        updateFooter();
        if (note.getId() <= 0) (NoteData.JOURNAL.equals(type) ? body : title).requestFocus();
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
        String words = body.getText().toString().trim();
        note.setContent(words);
        if (NoteData.JOURNAL.equals(type)) {
            note.setData(NoteData.journalJson(journal));
            return !words.isEmpty() || !t.isEmpty() || !journal.media.isEmpty() || !journal.mood.isEmpty();
        }
        return !words.isEmpty() || !t.isEmpty();
    }

    private void updateFooter() {
        if (note == null) return;
        String edited = note.getId() > 0
                ? "Edited " + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(note.getTimestamp()))
                : "New " + NoteData.kind(type).label.toLowerCase(Locale.getDefault());
        if (NoteData.LIST.equals(type)) {
            footer.setText(edited);
            return;
        }
        String text = body.getText().toString().trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        footer.setText(edited + "  ·  " + words + (words == 1 ? " word" : " words"));
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
        RecyclerView listView = findViewById(R.id.listItems);
        items.clear();
        items.addAll(NoteData.todos(note.getData()));
        wrapItems = NoteData.wraps(note.getData());
        if (items.isEmpty()) items.add(new NoteData.TodoItem("", false));
        if (listAdapter != null) {
            // Loaded again (a newer version from another device): same list, new items.
            listAdapter.notifyDataSetChanged();
            updateProgress();
            return;
        }

        listAdapter = new ListAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(listAdapter);

        ItemTouchHelper touch = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
                Collections.swap(items, a, b);
                listAdapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int pos = holder.getBindingAdapterPosition();
                if (pos < 0 || pos >= items.size()) return;
                items.remove(pos);
                listAdapter.notifyItemRemoved(pos);
                updateProgress();
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // The handle starts drags; long-press stays text selection.
            }
        });
        if (!readOnly) touch.attachToRecyclerView(listView);
        listAdapter.touch = touch;
        findViewById(R.id.listAdd).setOnClickListener(v -> addItemAt(items.size()));
        updateProgress();
    }

    /** A note sent to me: nothing to add, tick, reorder or re-date. */
    @Override
    protected void lockForReading() {
        super.lockForReading();
        for (int id : new int[]{R.id.listAdd, R.id.btnAddMedia}) {
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
        updateProgress();
    }

    private void updateProgress() {
        int done = 0, total = 0;
        for (NoteData.TodoItem item : items) {
            if (item.text.trim().isEmpty()) continue;
            total++;
            if (item.done) done++;
        }
        listProgress.setText(total == 0 ? "Nothing yet" : done == total ? "All done 🎉"
                : done + " of " + total + " done");
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
            // Still one item per line for the keyboard (Next adds the next item); only the view wraps.
            h.text.setHorizontallyScrolling(!wrapItems);
            h.text.setMaxLines(wrapItems ? Integer.MAX_VALUE : 1);
            h.text.setText(item.text);
            h.check.setChecked(item.done);
            h.styleDone(item.done);
            h.bound = item;
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
            NoteData.TodoItem bound;

            @android.annotation.SuppressLint("ClickableViewAccessibility")
            Holder(@NonNull View v) {
                super(v);
                text = v.findViewById(R.id.itemText);
                check = v.findViewById(R.id.itemCheck);
                if (readOnly) {
                    text.setKeyListener(null);
                    text.setFocusable(false);
                    text.setTextIsSelectable(true);
                    check.setEnabled(false);
                    v.findViewById(R.id.itemHandle).setVisibility(View.INVISIBLE);
                }
                text.addTextChangedListener(simpleWatcher(() -> {
                    if (bound == null) return;
                    bound.text = text.getText().toString();
                    updateProgress();
                }));
                check.setOnCheckedChangeListener((b, checked) -> {
                    if (bound == null) return;
                    bound.done = checked;
                    styleDone(checked);
                    updateProgress();
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

            void styleDone(boolean done) {
                text.setPaintFlags(done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                        : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                text.setAlpha(done ? 0.5f : 1f);
            }
        }
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
}
