package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * The notes app: a grid of cards, filters by type, search, and the "New" sheet.
 *
 * Also the front door of two hidden spaces: a long swipe on the grid, held for two seconds,
 * opens a lock screen (see {@link #setupSecretGesture}): to the left for one, to the right for
 * the other. Both directions belong to the gesture, so there is no swipe-to-delete; notes are
 * deleted from their options or their editor.
 */
public class MainActivity extends AppCompatActivity {
    /** Sent by a vault screen whose auto-lock expired: show the lock again on top of the notes. */
    public static final String EXTRA_RELOCK = "relock";

    private NoteAdapter adapter;
    private AppDatabase database;
    private LinearLayout emptyState;
    private TextView emptyText;
    private final ExecutorService executor = AppDatabase.IO;

    private List<NoteEntity> allNotes = new ArrayList<>();
    private String filterType;
    /** The "Shared with me" filter: notes sent to me by others. */
    private static final String SHARED = "SHARED";
    private String query = "";

    private final Handler secretHandler = new Handler(Looper.getMainLooper());
    private Runnable secretRunnable;
    private boolean isSecretTriggering = false;
    private float initialTouchX, initialTouchY;
    private boolean secretToRight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!SecurityHelper.isSetupDone(this)) {
            startActivity(new Intent(this, SetupLockActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // "Syncing…" under the title while notes come in from the account.
        NoteSync.SYNCING.observe(this, on -> {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle(Boolean.TRUE.equals(on) && Account.uid() != null ? "Syncing…" : null);
            }
        });

        emptyState = findViewById(R.id.emptyStateNotes);
        emptyText = findViewById(R.id.emptyStateText);
        RecyclerView recyclerView = findViewById(R.id.recyclerViewNotes);
        StaggeredGridLayoutManager grid = new StaggeredGridLayoutManager(
                getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2, StaggeredGridLayoutManager.VERTICAL);
        grid.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        recyclerView.setLayoutManager(grid);
        recyclerView.setItemViewCacheSize(12);

        adapter = new NoteAdapter();
        recyclerView.setAdapter(adapter);
        adapter.setListener(new NoteAdapter.Listener() {
            @Override
            public void onOpen(NoteEntity note) {
                openWhenSafe(note);
            }

            @Override
            public void onOptions(NoteEntity note) {
                showNoteOptions(note);
            }
        });

        database = AppDatabase.getInstance(this);
        database.noteDao().getAllNotes().observe(this, notes -> {
            allNotes = notes;
            applyFilter();
        });

        buildFilterChips();
        ExtendedFloatingActionButton fab = findViewById(R.id.fabAddNote);
        fab.setOnClickListener(v -> showNewSheet());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 8) fab.shrink();
                else if (dy < -8) fab.extend();
            }
        });

        setupSecretGesture(recyclerView);

        handleRelock(getIntent());
        Account.init(this);
    }

    // ================================================================ content

    private void applyFilter() {
        List<NoteEntity> shown = new ArrayList<>();
        String q = query.toLowerCase(Locale.getDefault());
        for (NoteEntity n : allNotes) {
            if (SHARED.equals(filterType) ? !n.isReceived() : filterType != null && !filterType.equals(n.getType())) continue;
            if (!q.isEmpty()) {
                String hay = ((n.getTitle() == null ? "" : n.getTitle()) + "\n"
                        + (n.getContent() == null ? "" : n.getContent())).toLowerCase(Locale.getDefault());
                if (!hay.contains(q)) continue;
            }
            shown.add(n);
        }
        adapter.submitList(shown);
        emptyState.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(!q.isEmpty() ? "Nothing matches “" + query + "”"
                : SHARED.equals(filterType) ? "Nothing shared with you yet. When someone sends you a note, it shows up here."
                : filterType != null ? "No " + NoteData.kind(filterType).plural.toLowerCase(Locale.getDefault()) + " yet"
                : "No notes yet. Tap New to start.");
    }

    private void buildFilterChips() {
        ChipGroup chips = findViewById(R.id.filterChips);
        addChip(chips, "All", null, true);
        addChip(chips, "Shared with me", SHARED, false);
        for (NoteData.Kind kind : NoteData.KINDS) addChip(chips, kind.plural, kind.type, false);
    }

    private void addChip(ChipGroup group, String label, String type, boolean checked) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setChecked(checked);
        chip.setChipBackgroundColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ContextCompat.getColor(this, R.color.brand_yellow), ContextCompat.getColor(this, R.color.app_surface)}));
        chip.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ContextCompat.getColor(this, R.color.black), ContextCompat.getColor(this, R.color.text_primary)}));
        chip.setChipStrokeWidth(0);
        if (SHARED.equals(type)) {
            chip.setChipIconResource(R.drawable.ic_person);
            chip.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tile_teal)));
            chip.setChipIconVisible(true);
        } else if (type != null) {
            chip.setChipIconResource(NoteData.kind(type).icon);
            chip.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, NoteData.kind(type).accent)));
            chip.setChipIconVisible(true);
        }
        chip.setOnClickListener(v -> {
            filterType = type;
            applyFilter();
        });
        group.addView(chip);
    }

    // ================================================================ actions

    /** The "New" sheet: one tile per note type. */
    private void showNewSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_new_note, null);
        ViewGroup grid = view.findViewById(R.id.newNoteGrid);
        for (NoteData.Kind kind : NoteData.KINDS) {
            View tile = LayoutInflater.from(this).inflate(R.layout.item_new_note, grid, false);
            ImageView icon = tile.findViewById(R.id.newIcon);
            icon.setImageResource(kind.icon);
            icon.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, kind.accent)));
            ((TextView) tile.findViewById(R.id.newLabel)).setText(kind.label);
            ((TextView) tile.findViewById(R.id.newDescription)).setText(kind.description);
            tile.setOnClickListener(v -> {
                sheet.dismiss();
                startActivity(NoteData.editor(this, kind.type, -1));
            });
            grid.addView(tile);
        }
        sheet.setContentView(view);
        sheet.show();
    }

    private void showNoteOptions(NoteEntity note) {
        String title = note.getTitle() == null || note.getTitle().isEmpty()
                ? NoteData.kind(note.getType()).label : note.getTitle();
        if (note.isReceived()) {
            // Someone else's note: mine to pin, colour, copy or put away, not to change.
            String[] options = {note.isPinned() ? "Unpin" : "Pin to top", "Colour", "Save a copy", "Remove"};
            new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setItems(options, (d, which) -> {
                        if (which == 0) executor.execute(() -> database.noteDao().setPinned(note.getId(), !note.isPinned()));
                        else if (which == 1) pickColor(note);
                        else if (which == 2) executor.execute(() -> {
                            SentNotes.saveCopy(this, note);
                            runOnUiThread(() -> Toast.makeText(this, "Saved as your own note", Toast.LENGTH_SHORT).show());
                        });
                        else SentNotes.confirmRemove(this, note, () -> {});
                    })
                    .show();
            return;
        }
        boolean canSend = Account.uid() != null && note.getGroupId() == null;
        int sent = SentNotes.count(note.getSentTo());
        String[] options = canSend
                ? new String[]{note.isPinned() ? "Unpin" : "Pin to top", "Colour",
                sent > 0 ? "Sent to " + sent + "…" : "Send to people…", "Delete"}
                : new String[]{note.isPinned() ? "Unpin" : "Pin to top", "Colour", "Delete"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(options, (d, which) -> {
                    if (which == 0) executor.execute(() -> database.noteDao().setPinned(note.getId(), !note.isPinned()));
                    else if (which == 1) pickColor(note);
                    else if (canSend && which == 2) SentNotes.showSheet(this, note.getId(), () -> {});
                    else deleteWithUndo(note);
                })
                .show();
    }

    private void pickColor(NoteEntity note) {
        String[] names = {"None", "Yellow", "Green", "Blue", "Purple", "Pink", "Grey"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Colour")
                .setSingleChoiceItems(names, note.getColor(), (d, which) -> {
                    executor.execute(() -> database.noteDao().setColor(note.getId(), which));
                    d.dismiss();
                })
                .show();
    }

    /**
     * Hidden now; its files go, and the account hears of it, only once Undo has expired.
     */
    private void deleteWithUndo(NoteEntity note) {
        // Held back from sync while Undo is on screen, so a quick sync can't make it final.
        NoteSync.OPEN.add(note.getId());
        executor.execute(() -> database.noteDao().setDeleted(note.getId(), true));
        Snackbar.make(findViewById(R.id.recyclerViewNotes), "Note deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo", v -> executor.execute(() -> database.noteDao().setDeleted(note.getId(), false)))
                .addCallback(new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar bar, int event) {
                        NoteSync.OPEN.remove(note.getId());
                        if (event != DISMISS_EVENT_ACTION) NoteSync.forget(getApplicationContext(), note);
                    }
                })
                .show();
    }

    // ================================================================== menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.notes_menu, menu);
        MenuItem search = menu.findItem(R.id.action_search);
        SearchView view = (SearchView) search.getActionView();
        if (view != null) {
            view.setQueryHint("Search notes");
            view.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String q) {
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String q) {
                    query = q == null ? "" : q.trim();
                    applyFilter();
                    return true;
                }
            });
        }
        MenuItem account = menu.findItem(R.id.action_account);
        if (account != null) {
            androidx.core.view.MenuItemCompat.setIconTintList(account, ColorStateList.valueOf(
                    ContextCompat.getColor(this, Account.user() != null ? R.color.brand_yellow : R.color.text_primary)));
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_account) {
            startActivity(new Intent(this, AccountActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ============================================================= lifecycle

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleRelock(intent);
    }

    /**
     * Opens a note, unless a sync is bringing changes in: then it asks first, because what is
     * typed now can replace what is arriving from the other devices (or leave a conflict copy).
     */
    private void openWhenSafe(NoteEntity note) {
        if (!Boolean.TRUE.equals(NoteSync.SYNCING.getValue()) || Account.uid() == null || note.isReceived()) {
            startActivity(NoteData.editor(this, note.getType(), note.getId()));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Notes are syncing")
                .setMessage("Changes from your other devices are coming in. Wait a moment so this note is up to date.\n\n"
                        + "Editing it now may overwrite what's arriving, or leave a conflict copy.")
                .setPositiveButton("Wait", null)
                .setNegativeButton("Edit anyway", (d, w) ->
                        startActivity(NoteData.editor(this, note.getType(), note.getId())))
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.onForeground(this);
        invalidateOptionsMenu();
    }

    /** Live while the grid is on screen: edits from other phones appear as they happen. */
    @Override
    protected void onStart() {
        super.onStart();
        if (database != null) NoteSync.startLive(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        NoteSync.stopLive();
    }

    private void handleRelock(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_RELOCK, false)) {
            intent.removeExtra(EXTRA_RELOCK);
            startActivity(new Intent(this, UnlockActivity.class)
                    .putExtra(UnlockActivity.EXTRA_REALM, intent.getStringExtra(UnlockActivity.EXTRA_REALM)));
        }
    }

    // ========================================================= secret gesture

    private void setupSecretGesture(RecyclerView recyclerView) {
        recyclerView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getX();
                        initialTouchY = event.getY();
                        isSecretTriggering = false;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float dx = initialTouchX - event.getX();
                        float dy = Math.abs(initialTouchY - event.getY());

                        if (Math.abs(dx) > 50 && Math.abs(dx) > dy * 2 && !isSecretTriggering) {
                            isSecretTriggering = true;
                            secretToRight = dx < 0;
                            startSecretTimer();
                            return true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        cancelSecretTimer();
                        break;
                }
                return isSecretTriggering;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    cancelSecretTimer();
                }
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
        });
    }

    private void startSecretTimer() {
        secretRunnable = this::triggerSecretAction;
        secretHandler.postDelayed(secretRunnable, 2000);
    }

    private void cancelSecretTimer() {
        if (secretRunnable != null) {
            secretHandler.removeCallbacks(secretRunnable);
            secretRunnable = null;
        }
        isSecretTriggering = false;
    }

    private void triggerSecretAction() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(100);
            }
        }
        if (!secretToRight) {
            startActivity(new Intent(this, UnlockActivity.class));
            return;
        }
        // The first visit creates the chat space's main vault before its lock screen shows.
        executor.execute(() -> {
            ChatVaults.ensureMain(getApplicationContext());
            runOnUiThread(() -> startActivity(new Intent(this, UnlockActivity.class)
                    .putExtra(UnlockActivity.EXTRA_REALM, UnlockActivity.REALM_CHAT)));
        });
    }
}
