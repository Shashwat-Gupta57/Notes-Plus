package org.flexstudios.notes.plus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 300;
    
    private NoteAdapter adapter;
    private AppDatabase database;
    private LinearLayout emptyState;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Handler secretHandler = new Handler();
    private Runnable secretRunnable;
    private boolean isSecretTriggering = false;
    private float initialTouchX, initialTouchY;

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

        emptyState = findViewById(R.id.emptyStateNotes);
        RecyclerView recyclerView = findViewById(R.id.recyclerViewNotes);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);

        adapter = new NoteAdapter();
        recyclerView.setAdapter(adapter);

        database = AppDatabase.getInstance(this);
        database.noteDao().getAllNotes().observe(this, notes -> {
            adapter.submitList(notes);
            emptyState.setVisibility(notes.isEmpty() ? View.VISIBLE : View.GONE);
        });

        FloatingActionButton fab = findViewById(R.id.fabAddNote);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, NoteEditorActivity.class);
            startActivity(intent);
        });

        adapter.setOnItemClickListener(note -> {
            Intent intent = new Intent(MainActivity.this, NoteEditorActivity.class);
            intent.putExtra(NoteEditorActivity.EXTRA_ID, note.getId());
            startActivity(intent);
        });

        setupSecretGesture(recyclerView);
        // Permission check removed from here, will be handled when entering Secret Gallery

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                NoteEntity deletedNote = adapter.getNoteAt(position);
                executor.execute(() -> {
                    database.noteDao().delete(deletedNote);
                    runOnUiThread(() -> {
                        Snackbar.make(recyclerView, "Note deleted", Snackbar.LENGTH_LONG)
                                .setAction("Undo", v -> {
                                    executor.execute(() -> database.noteDao().insert(deletedNote));
                                }).show();
                    });
                });
            }
        }).attachToRecyclerView(recyclerView);
    }

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

                        // Detect left swipe (50px threshold) and ensure it's primarily horizontal
                        if (dx > 50 && dx > dy * 2 && !isSecretTriggering) {
                            isSecretTriggering = true;
                            startSecretTimer();
                            return true; // Intercept: stop RecyclerView from scrolling
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
        startActivity(new Intent(this, UnlockActivity.class));
    }
}