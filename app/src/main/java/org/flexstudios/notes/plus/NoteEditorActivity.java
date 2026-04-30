package org.flexstudios.notes.plus;

import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoteEditorActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "org.flexstudios.notes.plus.EXTRA_ID";

    private EditText editTextTitle;
    private EditText editTextContent;
    private int noteId = -1;
    private AppDatabase database;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_editor);

        editTextTitle = findViewById(R.id.editTextTitle);
        editTextContent = findViewById(R.id.editTextContent);
        Toolbar toolbar = findViewById(R.id.toolbarEditor);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }

        database = AppDatabase.getInstance(this);

        if (getIntent().hasExtra(EXTRA_ID)) {
            noteId = getIntent().getIntExtra(EXTRA_ID, -1);
            executor.execute(() -> {
                NoteEntity note = database.noteDao().getNoteById(noteId);
                runOnUiThread(() -> {
                    if (note != null) {
                        editTextTitle.setText(note.getTitle());
                        editTextContent.setText(note.getContent());
                    }
                });
            });
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        saveNote();
        finish();
        return true;
    }

    @Override
    public void onBackPressed() {
        saveNote();
        super.onBackPressed();
    }

    private void saveNote() {
        String title = editTextTitle.getText().toString().trim();
        String content = editTextContent.getText().toString().trim();

        if (title.isEmpty() && content.isEmpty()) {
            return;
        }

        if (title.isEmpty()) {
            title = "Untitled Note";
        }

        long timestamp = System.currentTimeMillis();
        NoteEntity note = new NoteEntity(title, content, timestamp);

        if (noteId == -1) {
            executor.execute(() -> database.noteDao().insert(note));
        } else {
            note.setId(noteId);
            executor.execute(() -> database.noteDao().update(note));
        }
    }
}