package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    /** Someone else's note sent to me: shown, never changed (pin and colour are mine). */
    protected boolean readOnly;
    private boolean createdHere;
    private String savedState;
    private View root;
    protected String syncStatus = "Saved";
    private final android.os.Handler inactivityHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoSaveRunnable = () -> {
        if (!readOnly && note != null && isModified()) {
            syncStatus = "Saving…";
            updateStatusText();
            save();
            syncStatus = "Saved";
            updateStatusText();
            android.content.Context app = getApplicationContext();
            AppDatabase.IO.execute(() -> NoteSync.schedule(app));
        }
    };
    private static final java.util.List<NoteActivity> ACTIVE_EDITORS = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The key of a locked note while it is open: the editor holds the text, the database only its envelope. */
    private NoteLock.Key lock;
    /** Every note, for links: what "[[" offers and which links lead somewhere. Loaded on start. */
    protected volatile java.util.List<NoteEntity> linkNotes = java.util.Collections.emptyList();
    /** The title as last saved: when it changes, the links to it are renamed. */
    private String linkedTitle;
    @Nullable private android.widget.HorizontalScrollView backlinksBar;

    protected void markDirty() {
        if (readOnly) return;
        syncStatus = "Unsaved changes";
        updateStatusText();
        inactivityHandler.removeCallbacks(autoSaveRunnable);
        inactivityHandler.postDelayed(autoSaveRunnable, 10_000);
    }

    protected boolean isModified() {
        if (note == null || readOnly) return false;
        NoteEntity probe = copy(note);
        collect(probe);
        return !state(probe).equals(savedState);
    }

    protected void updateStatusText() {}

    public static void forceCloseIfOpen(int noteId, String reason) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (NoteActivity a : ACTIVE_EDITORS) {
                if (a.note != null && a.note.getId() == noteId) {
                    a.readOnly = true;
                    a.inactivityHandler.removeCallbacks(a.autoSaveRunnable);
                    a.note = null;
                    NoteSync.OPEN.remove(noteId);
                    a.finish();
                    if (reason != null) {
                        android.widget.Toast.makeText(a.getApplicationContext(), reason, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

    /** Fill the editor from the note. Main thread. */
    protected abstract void onNoteLoaded(NoteEntity note);

    /** Copy the editor's state into the note. Return false if there is nothing worth keeping. */
    protected abstract boolean collect(NoteEntity note);

    /**
     * Sets up the toolbar and loads the note. Call it <b>last</b> in onCreate: a new note is
     * passed to {@link #onNoteLoaded} synchronously, so every view it touches must exist.
     */
    protected void setUpNote(View rootView, Toolbar toolbar) {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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
                if (!loaded.isReceived() && NoteLock.isLocked(loaded.getData())) {
                    NoteSync.OPEN.add(loaded.getId());
                    unlockThenShow(loaded);
                    return;
                }
                showLoaded(loaded);
            });
        });
    }

    /** Fills the editor with a loaded note (already decrypted, if it was locked). */
    private void showLoaded(NoteEntity loaded) {
        note = loaded;
        linkedTitle = loaded.getTitle();
        readOnly = loaded.isReceived();
        NoteSync.OPEN.add(note.getId());
        savedState = state(note);
        applyColor();
        invalidateOptionsMenu();
        onNoteLoaded(note);
        if (!readOnly) {
            NoteSync.WATCHERS.put(note.getId(), this::takeNewerIfUnchanged);
            markBaseline();
        }
        if (readOnly) {
            lockForReading();
            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle("From @" + loaded.getSharedFrom() + "  ·  read only");
            }
        } else if (loaded.isTemplate() && getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle("Template  ·  new notes start as this");
        }
    }

    /** A locked note: the key from the recent session, or asked for. Cancel closes the note. */
    private void unlockThenShow(NoteEntity loaded) {
        NoteLock.Key cached = NoteLock.session(this, loaded.getUuid());
        if (cached != null && NoteLock.fits(cached, loaded.getData())) {
            openLocked(loaded, cached);
            return;
        }
        String type = NoteLock.typeOf(loaded.getData());
        String title = loaded.getTitle() == null || loaded.getTitle().isEmpty() ? "Locked note" : loaded.getTitle();
        UnlockActivity.askForLock(this, type, title, "Enter this note's " + NoteLock.typeWord(type) + ".", value ->
                AppDatabase.IO.execute(() -> {
                    try {
                        NoteLock.Key k = NoteLock.open(loaded.getData(), value);
                        runOnUiThread(() -> openLocked(loaded, k));
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            android.widget.Toast.makeText(this, "That isn't this note's " + NoteLock.typeWord(type),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            unlockThenShow(loaded);
                        });
                    }
                }), () -> {
                    NoteSync.OPEN.remove(loaded.getId());
                    finish();
                });
    }

    /** Decrypting asks for the lock itself, even though the note is open: it's the lock's owner deciding. */
    private void askThenDecrypt() {
        NoteLock.Key k = lock;
        if (k == null) return;
        String word = NoteLock.typeWord(k.type);
        UnlockActivity.askForLock(this, k.type, "Decrypt this note", "Enter its " + word + " to decrypt it.", value ->
                AppDatabase.IO.execute(() -> {
                    boolean right;
                    try {
                        NoteLock.open(NoteLock.seal(k, "", ""), value);
                        right = true;
                    } catch (Exception e) {
                        right = false;
                    }
                    boolean ok = right;
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        if (ok) removeLock();
                        else android.widget.Toast.makeText(this, "That isn't this note's " + word,
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                }), () -> {});
    }

    private void openLocked(NoteEntity loaded, NoteLock.Key k) {
        if (isFinishing()) return;
        try {
            String[] plain = NoteLock.unseal(k, loaded.getData());
            loaded.setContent(plain[0]);
            loaded.setData(plain[1]);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Couldn't open this note", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        lock = k;
        NoteLock.opened(loaded.getUuid(), k);
        showLoaded(loaded);
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
            if (live.getId() <= 0) insert(live, copy(live));
            savedState = state(live);
            File dir = NoteData.dir(this, live.getId());
            runOnUiThread(() -> then.accept(dir));
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ACTIVE_EDITORS.add(this);
        loadLinks(); // Back from a linked note: it may link here now, or have a new title.
    }

    @Override
    protected void onStop() {
        super.onStop();
        ACTIVE_EDITORS.remove(this);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    /**
     * Sync waits until the user has finished with a note: an open note is never pushed or
     * overwritten, and leaving it is what queues the sync (after the last save has landed).
     */
    /**
     * "Unchanged" is measured against what this editor itself makes of the note once it is on
     * screen, not the stored text: editors normalise as they load (rounding, defaults), and
     * that alone must never count as an edit, or leaving an untouched note pushes a stale copy.
     * Then takes any newer version that arrived meanwhile.
     */
    private void markBaseline() {
        View anchor = root != null ? root : getWindow().getDecorView();
        anchor.post(() -> {
            if (note == null) return;
            NoteEntity probe = copy(note);
            collect(probe);
            savedState = state(probe);
            takeNewerIfUnchanged();
        });
    }

    /** A newer version arrived for this note: shown in place, if nothing was changed here. */
    private void takeNewerIfUnchanged() {
        if (note == null || readOnly || isFinishing()) return;
        NoteEntity probe = copy(note);
        collect(probe);
        if (!state(probe).equals(savedState)) return; // Edited here: this edit wins on leaving.
        final int id = note.getId();
        android.content.Context app = getApplicationContext();
        WRITES.execute(() -> {
            NoteEntity fresh = NoteSync.takeNewer(app, id);
            if (fresh == null) return;
            runOnUiThread(() -> {
                if (note == null || note.getId() != id || isFinishing()) return;
                if (NoteLock.isLocked(fresh.getData())) {
                    // Locked elsewhere with another lock: this editor can't show it; it stays as is.
                    if (lock == null || !NoteLock.fits(lock, fresh.getData())) return;
                    try {
                        String[] plain = NoteLock.unseal(lock, fresh.getData());
                        fresh.setContent(plain[0]);
                        fresh.setData(plain[1]);
                    } catch (Exception e) {
                        return;
                    }
                } else if (lock != null) {
                    lock = null; // The lock was removed on another device.
                    invalidateOptionsMenu();
                }
                note = fresh;
                savedState = state(fresh);
                applyColor();
                onNoteLoaded(fresh);
                markBaseline();
                android.widget.Toast.makeText(this, "Updated from your other device", android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (note != null) NoteSync.WATCHERS.remove(note.getId());
        if (note != null) NoteLock.left(this, note.getUuid());
        if (note != null) NoteSync.OPEN.remove(note.getId());
        if (isFinishing()) {
            android.content.Context app = getApplicationContext();
            WRITES.execute(() -> NoteSync.schedule(app));
        }
    }

    /** Inserts a new note into the signed-in account (if any), and marks it open here. */
    private void insert(NoteEntity live, NoteEntity snapshot) {
        snapshot.setAccount(Account.uid());
        live.setAccount(snapshot.getAccount());
        live.setId((int) AppDatabase.getInstance(this).noteDao().insert(snapshot));
        NoteSync.OPEN.add(live.getId());
    }

    /**
     * Makes the editor read-only, for a note sent to me. The default stops every text field from
     * being edited; editors with more to lock (drawing, the map, recording) add to it.
     */
    protected void lockForReading() {
        lockTextFields(root);
    }

    private static void lockTextFields(View v) {
        if (v instanceof android.widget.EditText) {
            android.widget.EditText e = (android.widget.EditText) v;
            e.setKeyListener(null);
            e.setFocusable(false);
            e.setLongClickable(true);
            e.setTextIsSelectable(true); // Still copyable.
        } else if (v instanceof android.widget.CompoundButton) {
            v.setEnabled(false);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) lockTextFields(g.getChildAt(i));
        }
    }

    /** Saves if anything changed. Safe to call any time on the main thread. */
    /**
     * Every note linking "[[from]]" now links "[[to]]", unless another note still has the old title
     * (then the links lead there). Received and locked notes are left as they are. Off the main thread.
     */
    private int renameLinks(int self, String from, String to) {
        NoteDao dao = AppDatabase.getInstance(this).noteDao();
        java.util.List<NoteEntity> all = dao.getAllDirect();
        if (NoteLinks.resolve(from, all) != null) return 0;
        int changed = 0;
        for (NoteEntity n : all) {
            if (n.getId() == self || n.isReceived() || NoteLock.isLocked(n.getData())) continue;
            String[] next = NoteLinks.renamed(n, from, to);
            if (next == null) continue;
            dao.saveContent(n.getId(), n.getTitle(), next[0], next[1], n.getColor(), n.isPinned(), System.currentTimeMillis());
            changed++;
        }
        return changed;
    }

    /** Every note (for "[[" and the links), and the notes linking here (the "Linked from" row). */
    protected void loadLinks() {
        final NoteEntity me = note;
        final boolean mine = !readOnly;
        AppDatabase.IO.execute(() -> {
            java.util.List<NoteEntity> all = AppDatabase.getInstance(this).noteDao().getAllDirect();
            java.util.List<NoteEntity> from = me == null || me.getId() <= 0 || !mine
                    ? java.util.Collections.emptyList() : NoteLinks.backlinks(me, all);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                linkNotes = all;
                showBacklinks(from);
                onLinksLoaded();
            });
        });
    }

    /** The notes for links have loaded: editors redraw their links. */
    protected void onLinksLoaded() {}

    /** "Linked from": a row of the notes linking here, above the editor's footer; hidden when none do. */
    private void showBacklinks(java.util.List<NoteEntity> from) {
        if (!(root instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) root;
        if (from.isEmpty()) {
            if (backlinksBar != null) backlinksBar.setVisibility(View.GONE);
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        if (backlinksBar == null) {
            backlinksBar = new android.widget.HorizontalScrollView(this);
            backlinksBar.setHorizontalScrollBarEnabled(false);
            View footer = findViewById(R.id.editorFooter);
            int at = footer != null && footer.getParent() == page ? page.indexOfChild(footer) : page.getChildCount();
            page.addView(backlinksBar, at, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16 * dp), Math.round(6 * dp), Math.round(16 * dp), Math.round(6 * dp));
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("Linked from");
        label.setTextSize(12);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        label.setPadding(0, 0, Math.round(8 * dp), 0);
        row.addView(label);
        for (NoteEntity n : from) {
            NoteData.Kind kind = NoteData.kind(n.getType());
            android.widget.TextView chip = new android.widget.TextView(this);
            String title = n.getTitle() == null || n.getTitle().trim().isEmpty() ? kind.label : n.getTitle().trim();
            chip.setText(title.length() > 40 ? title.substring(0, 40) + "…" : title);
            chip.setTextSize(13);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setPadding(Math.round(8 * dp), Math.round(5 * dp), Math.round(12 * dp), Math.round(5 * dp));
            android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(this, kind.icon);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(ContextCompat.getColor(this, kind.accent));
                icon.setBounds(0, 0, Math.round(16 * dp), Math.round(16 * dp));
                chip.setCompoundDrawablesRelative(icon, null, null, null);
                chip.setCompoundDrawablePadding(Math.round(6 * dp));
            }
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(14 * dp);
            bg.setColor(ContextCompat.getColor(this, R.color.app_surface));
            bg.setStroke(Math.round(dp), ContextCompat.getColor(this, R.color.divider));
            chip.setBackground(bg);
            chip.setOnClickListener(v -> startActivity(NoteData.editor(this, n.getType(), n.getId())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(6 * dp));
            row.addView(chip, lp);
        }
        backlinksBar.removeAllViews();
        backlinksBar.addView(row);
        backlinksBar.setVisibility(View.VISIBLE);
    }

    /** A tapped "[[title]]": a bar to open that note, or to make it when no note has that title. */
    protected void showLink(String title) {
        if (root == null) return;
        NoteEntity target = NoteLinks.resolve(title, linkNotes);
        String shown = "“" + title.trim() + "”";
        com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar.make(root,
                target != null ? shown : "No note called " + shown, com.google.android.material.snackbar.Snackbar.LENGTH_LONG);
        if (target != null) {
            bar.setAction("Open", v -> startActivity(NoteData.editor(this, target.getType(), target.getId())));
        } else if (!readOnly) {
            bar.setAction("Create", v -> AppDatabase.IO.execute(() -> {
                NoteEntity made = new NoteEntity(title.trim(), "", System.currentTimeMillis());
                made.setType(NoteData.TEXT);
                made.setAccount(Account.uid());
                made.setRev(0);
                made.setDirty(true);
                int id = (int) AppDatabase.getInstance(this).noteDao().insert(made);
                runOnUiThread(() -> startActivity(NoteData.editor(this, NoteData.TEXT, id)));
            }));
        }
        bar.show();
    }

    protected void save() {
        if (note == null) return;
        if (readOnly) {
            // Only my own choices about it: pinned, and its colour.
            final int id = note.getId(), color = note.getColor();
            final boolean pinned = note.isPinned();
            WRITES.execute(() -> {
                NoteDao dao = AppDatabase.getInstance(this).noteDao();
                dao.setPinned(id, pinned);
                dao.setColor(id, color);
            });
            return;
        }
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
        // A new title: the links to the old one follow it (not for locked notes, whose links can't be seen).
        final String renamedFrom = linkedTitle;
        final boolean renamed = lock == null && !NoteLinks.key(renamedFrom).isEmpty()
                && !NoteLinks.key(snapshot.getTitle()).isEmpty() && !NoteLinks.key(renamedFrom).equals(NoteLinks.key(snapshot.getTitle()));
        linkedTitle = snapshot.getTitle();
        if (lock != null) {
            try {
                snapshot.setData(NoteLock.seal(lock, snapshot.getContent(), snapshot.getData()));
                snapshot.setContent("");
            } catch (Exception e) {
                android.util.Log.e("NoteActivity", "Couldn't seal a locked note; not saved", e);
                savedState = null;
                return;
            }
        }
        WRITES.execute(() -> {
            // Checked here, on the serial writer, so an earlier insert has always landed.
            if (live.getId() <= 0) {
                insert(live, snapshot);
            } else {
                AppDatabase.getInstance(this).noteDao().saveContent(live.getId(), snapshot.getTitle(),
                        snapshot.getContent(), snapshot.getData(), snapshot.getColor(), snapshot.isPinned(),
                        snapshot.getTimestamp());
            }
            if (renamed) {
                int changed = renameLinks(live.getId(), renamedFrom, snapshot.getTitle());
                if (changed > 0) {
                    android.content.Context app = getApplicationContext();
                    runOnUiThread(() -> android.widget.Toast.makeText(app, "Updated links in " + changed
                            + (changed == 1 ? " note" : " notes"), android.widget.Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void delete(boolean thenFinish) {
        final NoteEntity doomed = copy(note);
        doomed.setId(note.getId());
        note = null;
        WRITES.execute(() -> {
            if (doomed.getId() <= 0) return;
            NoteSync.OPEN.remove(doomed.getId());
            AppDatabase.getInstance(this).noteDao().setDeleted(doomed.getId(), true);
            NoteSync.forget(getApplicationContext(), doomed);
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
        MenuItem lockItem = menu.findItem(R.id.action_lock);
        if (lockItem != null) {
            boolean can = note != null && !readOnly && NoteLock.lockable(note.getType())
                    && note.getGroupId() == null && SentNotes.count(note.getSentTo()) == 0;
            lockItem.setVisible(can);
            // Locked and opened: an open padlock in yellow (unlocked for now; stored copies stay
            // encrypted). Not locked: a grey padlock, to lock it.
            lockItem.setTitle(lock != null ? "Unlocked for now" : "Lock");
            lockItem.setIcon(lock != null ? R.drawable.ic_lock_open : R.drawable.ic_lock);
            androidx.core.view.MenuItemCompat.setIconTintList(lockItem,
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
                            lock != null ? R.color.brand_yellow : R.color.text_secondary)));
        }
        MenuItem changeLock = menu.findItem(R.id.action_change_lock);
        if (changeLock != null) changeLock.setVisible(lock != null);
        MenuItem removeLock = menu.findItem(R.id.action_remove_lock);
        if (removeLock != null) {
            removeLock.setVisible(lock != null);
            removeLock.setTitle("Decrypt…");
        }
        MenuItem wrap = menu.findItem(R.id.action_wrap);
        if (wrap != null) {
            Boolean on = note == null || readOnly ? null : wrapText();
            wrap.setVisible(on != null);
            wrap.setChecked(on != null && on);
        }
        MenuItem share = menu.findItem(R.id.action_share_group);
        if (share != null) {
            boolean shared = note != null && note.getGroupId() != null;
            share.setTitle(shared ? "People" : "Share with people");
            share.setVisible(note != null && !readOnly && lock == null && !NoteData.AUDIO.equals(note.getType()) && Account.uid() != null);
        }
        MenuItem send = menu.findItem(R.id.action_send);
        if (send != null) {
            int sent = note == null ? 0 : SentNotes.count(note.getSentTo());
            send.setTitle(sent > 0 ? "Sent to " + sent : "Send to people");
            send.setVisible(note != null && !readOnly && lock == null && note.getGroupId() == null && Account.uid() != null);
        }
        MenuItem link = menu.findItem(R.id.action_link);
        if (link != null) {
            link.setTitle(note != null && note.getGroupId() != null ? "Invite link" : "Share link");
            link.setVisible(note != null && !readOnly && lock == null && !NoteData.AUDIO.equals(note.getType()) && Account.uid() != null);
        }
        MenuItem copy = menu.findItem(R.id.action_save_copy);
        if (copy != null) copy.setVisible(readOnly);
        MenuItem delete = menu.findItem(R.id.action_delete);
        if (delete != null) delete.setTitle(readOnly ? "Remove" : "Delete");
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
        } else if (id == R.id.action_lock) {
            if (lock == null) startLocking();
            else lockedOptions();
            return true;
        } else if (id == R.id.action_change_lock) {
            chooseNewLock(); // Seals it under the new lock on the next save (right away).
            return true;
        } else if (id == R.id.action_remove_lock) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Decrypt this note?")
                    .setMessage("Its text will be stored unencrypted: here, in your account and on your other devices "
                            + "(and in its file, if it's linked to one on a computer). Tap the lock any time to encrypt it "
                            + "again with the same lock.")
                    .setPositiveButton("Decrypt", (d, w) -> askThenDecrypt())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        } else if (id == R.id.action_wrap) {
            Boolean on = wrapText();
            if (on != null) setWrapText(!on);
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_pin) {
            note.setPinned(!note.isPinned());
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_share_group) {
            // Save, let the write land, then share what was just saved.
            save();
            WRITES.execute(() -> runOnUiThread(() -> {
                if (note != null) GroupNotes.showSheet(this, note.getId(), () -> {});
            }));
            return true;
        } else if (id == R.id.action_send) {
            save();
            WRITES.execute(() -> runOnUiThread(() -> {
                if (note != null) SentNotes.showSheet(this, note.getId(), () -> {});
            }));
            return true;
        } else if (id == R.id.action_link) {
            save();
            WRITES.execute(() -> runOnUiThread(() -> {
                if (note == null) return;
                if (note.getGroupId() != null) ShareLinks.inviteSheet(this, note);
                else ShareLinks.sheet(this, note.getId());
            }));
            return true;
        } else if (id == R.id.action_save_copy) {
            final NoteEntity received = note;
            AppDatabase.IO.execute(() -> {
                int copyId = SentNotes.saveCopy(this, received);
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(this, "Saved as your own note", android.widget.Toast.LENGTH_SHORT).show();
                    startActivity(NoteData.editor(this, received.getType(), copyId));
                    finish();
                });
            });
            return true;
        } else if (id == R.id.action_color) {
            pickColor();
            return true;
        } else if (id == R.id.action_delete && readOnly) {
            SentNotes.confirmRemove(this, note, this::finish);
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

    // ==================================================================== lock

    /** Locks the note: with the key still open from earlier, with its last lock, or a new one. */
    private void startLocking() {
        if (note == null) return;
        String uuid = note.getUuid();
        NoteLock.Key open = NoteLock.session(this, uuid);
        if (open != null) {
            applyLock(open);
            return;
        }
        String lastType = NoteLock.lastType(this, uuid);
        if (lastType == null) {
            chooseNewLock();
            return;
        }
        String word = NoteLock.typeWord(lastType);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Lock this note")
                .setMessage("Lock it again with its last " + word + "?")
                .setPositiveButton("Use last " + word, (d, w) -> UnlockActivity.askForLock(this, lastType,
                        "Last " + word, "Enter it once.", value -> AppDatabase.IO.execute(() -> {
                            try {
                                NoteLock.Key k = NoteLock.lastKey(this, uuid, value);
                                runOnUiThread(() -> applyLock(k));
                            } catch (Exception e) {
                                runOnUiThread(() -> android.widget.Toast.makeText(this, "That isn't its last " + word,
                                        android.widget.Toast.LENGTH_SHORT).show());
                            }
                        }), null))
                .setNeutralButton("New lock", (d, w) -> chooseNewLock())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseNewLock() {
        String[] types = {"PIN", "PASSWORD", "PATTERN"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Lock with")
                .setItems(new String[]{"PIN", "Password", "Pattern"}, (d, which) ->
                        UnlockActivity.askForNewLock(this, types[which], "New lock for this note", first ->
                                UnlockActivity.askForNewLock(this, types[which], "Once more, to confirm", second -> {
                                    if (!first.equals(second)) {
                                        android.widget.Toast.makeText(this, "Those didn't match. Try again.",
                                                android.widget.Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    AppDatabase.IO.execute(() -> {
                                        try {
                                            NoteLock.Key k = NoteLock.newKey(types[which], first);
                                            runOnUiThread(() -> applyLock(k));
                                        } catch (Exception e) {
                                            runOnUiThread(() -> android.widget.Toast.makeText(this, "Couldn't lock it",
                                                    android.widget.Toast.LENGTH_SHORT).show());
                                        }
                                    });
                                })))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyLock(NoteLock.Key k) {
        if (note == null || isFinishing()) return;
        lock = k;
        NoteLock.opened(note.getUuid(), k);
        NoteLock.remember(this, note.getUuid(), k);
        savedState = null; // Written now, sealed.
        save();
        invalidateOptionsMenu();
        android.widget.Toast.makeText(this, "Locked. It opens only with this " + NoteLock.typeWord(k.type)
                + ", on every device.", android.widget.Toast.LENGTH_LONG).show();
    }

    private void lockedOptions() {
        int minutes = NoteLock.timeoutMinutes(this);
        String stays = minutes < 0 ? "until the app closes" : minutes == 0 ? "only while it's on screen"
                : "for " + minutes + (minutes == 1 ? " minute" : " minutes") + " after you leave it";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unlocked for now")
                .setMessage("It stays open " + stays + ", then needs its " + NoteLock.typeWord(lock.type) + " again. "
                        + "It stays encrypted all along in your account and on your other devices. "
                        + "To store it unencrypted, use ⋮ → Decrypt.")
                .setPositiveButton("Lock now", (d, w) -> {
                    NoteLock.forget(note.getUuid());
                    finish();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Decrypts the note for good; its lock is remembered, so the lock icon puts it back. */
    private void removeLock() {
        if (note == null || lock == null) return;
        lock = null;
        savedState = null;
        save();
        invalidateOptionsMenu();
        android.widget.Toast.makeText(this, "Decrypted. Tap the lock to encrypt it again.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** Whether long lines wrap, for the note types that offer the choice; null for the rest. */
    @Nullable
    protected Boolean wrapText() {
        return null;
    }

    protected void setWrapText(boolean wrap) {
    }

    private void pickColor() {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(40 * density), gap = Math.round(8 * density);
        android.widget.GridLayout row = new android.widget.GridLayout(this); // Thirteen: rows of five.
        row.setColumnCount(5);
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
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(0, 0, gap, gap);
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
        c.setUuid(n.getUuid());
        c.setAccount(n.getAccount());
        c.setRev(n.getRev());
        c.setMedia(n.getMedia());
        c.setGroupId(n.getGroupId());
        c.setGroupMembers(n.getGroupMembers());
        return c;
    }
}
