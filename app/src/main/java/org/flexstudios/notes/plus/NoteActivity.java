package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base for every note editor (text, list, journal, mind map, sketch, audio).
 *
 * Owns the parts they all share so none of them re-implements it: loading the note or
 * starting a new one, saving on pause only when something changed, pin / colour / delete,
 * and throwing away a new note that was left empty.
 */
abstract class NoteActivity extends AppCompatActivity {

    /** One writer for all notes: an insert always lands before the update that follows it. */
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();

    protected NoteEntity note;
    private boolean createdHere;
    private String savedState;
    private View root;

    /** Fill the editor from the note. Main thread. */
    protected abstract void onNoteLoaded(NoteEntity note);

    /** Copy the editor's state into the note. Return false if there is nothing worth keeping. */
    protected abstract boolean collect(NoteEntity note);

    /**
     * Sets up the toolbar and loads the note. Call it <b>last</b> in onCreate: a new note is
     * passed to {@link #onNoteLoaded} synchronously, so every view it touches must exist.
     */
    protected void setUpNote(View rootView, Toolbar toolbar) {
        root = rootView;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().mutate().setTint(ContextCompat.getColor(this, R.color.text_primary));
        }

        int id = getIntent().getIntExtra(NoteData.EXTRA_ID, -1);
        String type = getIntent().getStringExtra(NoteData.EXTRA_TYPE);
        if (id <= 0) {
            note = new NoteEntity("", "", System.currentTimeMillis());
            note.setType(type == null ? NoteData.TEXT : type);
            createdHere = true;
            savedState = state(note);
            applyColor();
            onNoteLoaded(note);
            return;
        }
        AppDatabase.IO.execute(() -> {
            NoteEntity loaded = AppDatabase.getInstance(this).noteDao().getNoteById(id);
            runOnUiThread(() -> {
                if (loaded == null) {
                    finish();
                    return;
                }
                note = loaded;
                savedState = state(note);
                applyColor();
                invalidateOptionsMenu();
                onNoteLoaded(note);
            });
        });
    }

    /** The note's media folder, inserting the row first if this is a brand-new note. */
    protected void withFolder(java.util.function.Consumer<File> then) {
        if (note == null) return;
        if (note.getId() > 0) {
            then.accept(NoteData.dir(this, note.getId()));
            return;
        }
        collect(note);
        final NoteEntity live = note;
        WRITES.execute(() -> {
            if (live.getId() <= 0) live.setId((int) AppDatabase.getInstance(this).noteDao().insert(copy(live)));
            savedState = state(live);
            File dir = NoteData.dir(this, live.getId());
            runOnUiThread(() -> then.accept(dir));
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    /** Saves if anything changed. Safe to call any time on the main thread. */
    protected void save() {
        if (note == null) return;
        boolean keep = collect(note);
        if (!keep) {
            // A note made and abandoned here leaves nothing behind, files included.
            if (createdHere && note.getId() > 0) delete(false);
            return;
        }
        String state = state(note);
        if (state.equals(savedState)) return;
        savedState = state;
        note.setTimestamp(System.currentTimeMillis());
        final NoteEntity snapshot = copy(note);
        final NoteEntity live = note;
        WRITES.execute(() -> {
            // Checked here, on the serial writer, so an earlier insert has always landed.
            NoteDao dao = AppDatabase.getInstance(this).noteDao();
            if (live.getId() <= 0) {
                live.setId((int) dao.insert(snapshot));
            } else {
                snapshot.setId(live.getId());
                dao.update(snapshot);
            }
        });
    }

    private void delete(boolean thenFinish) {
        final NoteEntity doomed = copy(note);
        doomed.setId(note.getId());
        note = null;
        WRITES.execute(() -> {
            if (doomed.getId() > 0) AppDatabase.getInstance(this).noteDao().delete(doomed);
            NoteData.deleteFiles(this, doomed.getId());
        });
        if (thenFinish) finish();
    }

    // ===================================================================== menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.note_editor_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem pin = menu.findItem(R.id.action_pin);
        if (pin != null && note != null) {
            pin.setTitle(note.isPinned() ? "Unpin" : "Pin");
            androidx.core.view.MenuItemCompat.setIconTintList(pin,
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
                            note.isPinned() ? R.color.brand_yellow : R.color.text_secondary)));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (note == null) {
            return true;
        } else if (id == R.id.action_pin) {
            note.setPinned(!note.isPinned());
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_color) {
            pickColor();
            return true;
        } else if (id == R.id.action_delete) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Delete this note?")
                    .setPositiveButton("Delete", (d, w) -> delete(true))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pickColor() {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(40 * density), gap = Math.round(8 * density);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(gap * 3, gap * 2, gap * 3, 0);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Colour").setView(row).create();
        for (int i = 0; i < NoteData.COLORS.length; i++) {
            final int index = i;
            View swatch = new View(this);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(ContextCompat.getColor(this, i == 0 ? R.color.app_surface : NoteData.COLORS[i]));
            circle.setStroke(Math.round((i == note.getColor() ? 3 : 1) * density),
                    ContextCompat.getColor(this, i == note.getColor() ? R.color.brand_yellow : R.color.divider));
            swatch.setBackground(circle);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            row.addView(swatch, lp);
            swatch.setOnClickListener(v -> {
                note.setColor(index);
                applyColor();
                dialog.dismiss();
            });
        }
        dialog.show();
    }

    /** Tints the editor with the note's colour, so it matches its card. */
    protected void applyColor() {
        if (root == null || note == null) return;
        int c = note.getColor();
        int color = ContextCompat.getColor(this, c > 0 && c < NoteData.COLORS.length
                ? NoteData.COLORS[c] : R.color.app_background);
        root.setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        if (getSupportActionBar() != null) getSupportActionBar().setBackgroundDrawable(new ColorDrawable(color));
    }

    // ================================================================== helpers

    private static String state(NoteEntity n) {
        return n.getTitle() + "\u0000" + n.getContent() + "\u0000" + n.getData() + "\u0000"
                + n.getColor() + "\u0000" + n.isPinned();
    }

    private static NoteEntity copy(NoteEntity n) {
        NoteEntity c = new NoteEntity(n.getTitle(), n.getContent(), n.getTimestamp());
        c.setType(n.getType());
        c.setData(n.getData());
        c.setColor(n.getColor());
        c.setPinned(n.isPinned());
        return c;
    }
}
