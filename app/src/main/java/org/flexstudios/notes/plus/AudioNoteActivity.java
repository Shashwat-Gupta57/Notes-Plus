package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Audio notes: one note holds any number of recordings, each with its own waveform.
 *
 * Recordings are AAC in the note's folder. Amplitude is sampled while recording, so the
 * waveform (here and on the card) costs nothing to draw later.
 */
public class AudioNoteActivity extends NoteActivity {
    private static final int RECORD_PERMISSION = 51;
    private static final int WAVE_SAMPLES = 48;
    private static final long SAMPLE_MS = 60;

    private EditText title;
    private final List<NoteData.Recording> recordings = new ArrayList<>();
    private RecordingAdapter adapter;
    private FloatingActionButton recordButton;
    private TextView recordTimer, emptyHint;
    private WaveformView liveWave;

    private MediaRecorder recorder;
    private File recordingFile;
    private long recordStart;
    private final List<Float> amplitudes = new ArrayList<>();
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private NoteData.Recording playing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_note);
        title = findViewById(R.id.audioTitle);
        recordButton = findViewById(R.id.btnRecord);
        recordTimer = findViewById(R.id.recordTimer);
        emptyHint = findViewById(R.id.audioEmpty);
        liveWave = findViewById(R.id.liveWave);
        RecyclerView list = findViewById(R.id.recordingList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordingAdapter();
        list.setAdapter(adapter);
        recordButton.setOnClickListener(v -> {
            if (recorder != null) stopRecording();
            else startRecording();
        });

        // Last: a new note is handed to onNoteLoaded immediately, which needs the adapter.
        setUpNote(findViewById(R.id.audioRoot), findViewById(R.id.toolbarAudio));
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        title.setText(note.getTitle());
        recordings.clear();
        recordings.addAll(NoteData.recordings(note.getData()));
        adapter.notifyDataSetChanged();
        updateEmpty();
    }

    @Override
    protected boolean collect(NoteEntity note) {
        note.setTitle(title.getText().toString().trim());
        note.setData(NoteData.recordingsJson(recordings));
        StringBuilder text = new StringBuilder();
        for (NoteData.Recording r : recordings) if (!r.label.isEmpty()) text.append(r.label).append('\n');
        note.setContent(text.toString().trim());
        return !recordings.isEmpty() || !note.getTitle().isEmpty();
    }

    private void updateEmpty() {
        emptyHint.setVisibility(recordings.isEmpty() && recorder == null ? View.VISIBLE : View.GONE);
    }

    // ================================================================ record

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION);
            return;
        }
        stopPlayback();
        withFolder(dir -> {
            recordingFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
            MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this) : new MediaRecorder();
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(128_000);
                r.setAudioSamplingRate(44_100);
                r.setOutputFile(recordingFile.getAbsolutePath());
                r.prepare();
                r.start();
            } catch (Exception e) {
                Log.w("AudioNote", "Could not start recording", e);
                r.release();
                Toast.makeText(this, "The microphone isn't available", Toast.LENGTH_SHORT).show();
                return;
            }
            recorder = r;
            recordStart = SystemClock.elapsedRealtime();
            amplitudes.clear();
            recordButton.setImageResource(R.drawable.ic_stop);
            liveWave.setVisibility(View.VISIBLE);
            recordTimer.setVisibility(View.VISIBLE);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateEmpty();
            ticker.post(sampler);
        });
    }

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) return;
            float level = Math.min(1f, (float) Math.sqrt(recorder.getMaxAmplitude() / 32767f));
            amplitudes.add(level);
            liveWave.setLive(amplitudes);
            recordTimer.setText(SecretGalleryAdapter.formatDuration(SystemClock.elapsedRealtime() - recordStart));
            ticker.postDelayed(this, SAMPLE_MS);
        }
    };

    private void stopRecording() {
        if (recorder == null) return;
        ticker.removeCallbacks(sampler);
        long duration = SystemClock.elapsedRealtime() - recordStart;
        try {
            recorder.stop();
        } catch (RuntimeException tooShort) {
            // Stopping within a fraction of a second has no audio; discard it.
            duration = 0;
        }
        recorder.release();
        recorder = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recordButton.setImageResource(R.drawable.ic_mic);
        liveWave.setVisibility(View.GONE);
        recordTimer.setVisibility(View.GONE);

        if (duration < 500) {
            if (recordingFile != null && !recordingFile.delete()) Log.w("AudioNote", "Could not discard empty take");
            updateEmpty();
            return;
        }
        String label = "Recording " + (recordings.size() + 1);
        NoteData.Recording r = new NoteData.Recording(recordingFile.getName(), label, duration,
                System.currentTimeMillis(), downsample(amplitudes, WAVE_SAMPLES));
        recordings.add(0, r);
        adapter.notifyItemInserted(0);
        updateEmpty();
        save();
    }

    private static float[] downsample(List<Float> values, int count) {
        float[] out = new float[count];
        if (values.isEmpty()) return out;
        for (int i = 0; i < count; i++) {
            int from = i * values.size() / count, to = Math.max(from + 1, (i + 1) * values.size() / count);
            float peak = 0;
            for (int k = from; k < to && k < values.size(); k++) peak = Math.max(peak, values.get(k));
            out[i] = peak;
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == RECORD_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        }
    }

    // ================================================================== play

    private void toggle(NoteData.Recording r) {
        if (playing == r && player != null) {
            if (player.isPlaying()) player.pause();
            else player.start();
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlayback();
        File file = new File(NoteData.dir(this, note.getId()), r.file);
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(file.getAbsolutePath());
            p.prepare();
        } catch (Exception e) {
            p.release();
            Toast.makeText(this, "Couldn't play that recording", Toast.LENGTH_SHORT).show();
            return;
        }
        p.setOnCompletionListener(mp -> {
            stopPlayback();
        });
        p.start();
        player = p;
        playing = r;
        ticker.post(progress);
        adapter.notifyDataSetChanged();
    }

    private final Runnable progress = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            adapter.notifyItemChanged(recordings.indexOf(playing), "progress");
            ticker.postDelayed(this, 50);
        }
    };

    private void stopPlayback() {
        ticker.removeCallbacks(progress);
        if (player != null) {
            player.release();
            player = null;
        }
        playing = null;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        if (recorder != null) stopRecording();
        if (player != null && player.isPlaying()) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }

    private final class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recording, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            NoteData.Recording r = recordings.get(position);
            boolean isPlaying = playing == r && player != null;
            float fraction = isPlaying && player.getDuration() > 0
                    ? player.getCurrentPosition() / (float) player.getDuration() : 0;
            h.wave.setWave(r.wave, fraction);
            if (!payloads.isEmpty()) return;
            h.label.setText(r.label);
            h.meta.setText(SecretGalleryAdapter.formatDuration(r.durationMs) + "  ·  "
                    + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(r.createdAt)));
            h.play.setImageResource(isPlaying && player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_big);
            h.play.setOnClickListener(v -> toggle(r));
            h.label.setOnClickListener(v -> rename(r));
            h.itemView.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(AudioNoteActivity.this)
                        .setTitle("Delete \"" + r.label + "\"?")
                        .setPositiveButton("Delete", (d, w) -> {
                            if (playing == r) stopPlayback();
                            int pos = recordings.indexOf(r);
                            recordings.remove(r);
                            notifyItemRemoved(pos);
                            File f = new File(NoteData.dir(AudioNoteActivity.this, note.getId()), r.file);
                            if (!f.delete()) Log.w("AudioNote", "Could not delete " + f);
                            updateEmpty();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            onBindViewHolder(h, position, new ArrayList<>());
        }

        @Override
        public int getItemCount() {
            return recordings.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageButton play;
            final TextView label, meta;
            final WaveformView wave;

            Holder(@NonNull View v) {
                super(v);
                play = v.findViewById(R.id.recPlay);
                label = v.findViewById(R.id.recLabel);
                meta = v.findViewById(R.id.recMeta);
                wave = v.findViewById(R.id.recWave);
            }
        }
    }

    private void rename(NoteData.Recording r) {
        Prompts.text(this, "Name this recording", null, "Name", r.label, Prompts.TEXT, "Save", name -> {
            r.label = name;
            adapter.notifyItemChanged(recordings.indexOf(r));
        }, null);
    }

    // ============================================================== the view

    /** Rounded bars from amplitude samples; the played part is drawn in the accent colour. */
    public static class WaveformView extends View {
        private float[] wave = new float[0];
        private float progress;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final float density;
        /** 0: the defaults (teal played, grey rest). */
        private int playedColor, restColor;

        public WaveformView(Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
            density = getResources().getDisplayMetrics().density;
        }

        /** For a coloured background (a chat bubble): the bar colours to use. */
        void setColors(int played, int rest) {
            playedColor = played;
            restColor = rest;
            invalidate();
        }

        void setWave(float[] samples, float played) {
            wave = samples == null ? new float[0] : samples;
            progress = played;
            invalidate();
        }

        /** While recording: the most recent samples scroll in from the right. */
        void setLive(List<Float> samples) {
            int count = Math.max(1, (int) (getWidth() / (5 * density)));
            float[] tail = new float[Math.min(count, samples.size())];
            for (int i = 0; i < tail.length; i++) tail[i] = samples.get(samples.size() - tail.length + i);
            wave = tail;
            progress = 1f;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (wave.length == 0) return;
            float gap = 2 * density;
            float width = Math.max(2 * density, (getWidth() - gap * (wave.length - 1)) / wave.length);
            float mid = getHeight() / 2f;
            int played = playedColor != 0 ? playedColor : ContextCompat.getColor(getContext(), R.color.tile_teal);
            int rest = restColor != 0 ? restColor : ContextCompat.getColor(getContext(), R.color.text_secondary);
            for (int i = 0; i < wave.length; i++) {
                float h = Math.max(3 * density, wave[i] * getHeight() * 0.9f);
                float x = i * (width + gap);
                bar.set(x, mid - h / 2, x + width, mid + h / 2);
                boolean done = (i + 0.5f) / wave.length <= progress;
                paint.setColor(done ? played : rest);
                paint.setAlpha(done ? 255 : 110);
                canvas.drawRoundRect(bar, width / 2, width / 2, paint);
            }
        }
    }
}
