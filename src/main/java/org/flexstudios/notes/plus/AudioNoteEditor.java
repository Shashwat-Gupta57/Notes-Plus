package org.flexstudios.notes.plus;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Audio notes: one note holds any number of recordings, each with its own waveform. The
 * phone's AudioNoteActivity.
 *
 * Amplitude is sampled while recording, so the waveform (here and on the card) costs nothing
 * to draw later. Ctrl+R starts and stops a recording.
 */
final class AudioNoteEditor extends NoteEditor {
    private static final int WAVE_SAMPLES = 48;
    private static final long SAMPLE_MS = 60;

    private final TextField title = new TextField();
    private final List<NoteData.Recording> recordings = new ArrayList<>();
    private final VBox list = new VBox(8);
    private final Label emptyHint = Ui.label("Click the microphone to record", 16, "secondary");
    private final Button recordButton;
    private final Ui.IconView recordIcon = Ui.icon(Icons.MIC, 34, "white");
    private final Label recordTimer = new Label();
    private final WaveformView liveWave = new WaveformView(56);

    private AudioRecorder recorder;
    private Path recordingFile;
    private long recordStart;
    private final List<Float> amplitudes = new ArrayList<>();
    private final Timeline sampler = new Timeline(new KeyFrame(Duration.millis(SAMPLE_MS), e -> sample()));

    private MediaPlayer player;
    private NoteData.Recording playing;
    private final AnimationTimer progress = new AnimationTimer() {
        @Override
        public void handle(long now) {
            showRows();
        }
    };

    AudioNoteEditor(String type, int noteId) {
        super(type, noteId);
        title.getStyleClass().addAll("bare", "bold");
        title.setStyle("-fx-font-size: 24px;");
        Links.install(title);
        title.setPromptText("Audio note title");
        sampler.setCycleCount(Timeline.INDEFINITE);

        list.setPadding(new Insets(0, 16, 16, 16));
        list.setMaxWidth(780);
        StackPane page = new StackPane(list);
        StackPane.setAlignment(list, Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        emptyHint.setMouseTransparent(true);
        StackPane middle = new StackPane(scroll, emptyHint);

        recordTimer.getStyleClass().add("light");
        recordTimer.setStyle("-fx-font-size: 36px;");
        recordTimer.setVisible(false);
        recordTimer.setManaged(false);
        liveWave.setVisible(false);
        liveWave.setManaged(false);
        liveWave.setMaxWidth(716);
        VBox.setMargin(liveWave, new Insets(8, 32, 16, 32));
        recordButton = new Button(null, recordIcon);
        recordButton.getStyleClass().setAll("round-fab");
        recordButton.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        recordButton.setStyle("-fx-background-color: " + Theme.ref("tile_red") + ";");
        recordButton.setMinSize(76, 76);
        recordButton.setMaxSize(76, 76);
        recordButton.setFocusTraversable(false);
        Ui.tooltip(recordButton, "Record (Ctrl+R)");
        recordButton.setOnAction(e -> toggleRecording());
        VBox recorderBox = new VBox(recordTimer, liveWave, recordButton);
        recorderBox.setAlignment(Pos.CENTER);
        recorderBox.setPadding(new Insets(12, 0, 28, 0));

        // The title sits in the same centred column as the recordings.
        StackPane titleBox = new StackPane(title);
        titleBox.setMaxWidth(780);
        titleBox.setPadding(new Insets(0, 20, 8, 20));
        StackPane titleRow = new StackPane(titleBox);
        BorderPane body = new BorderPane(middle);
        body.setTop(titleRow);
        body.setBottom(recorderBox);
        // Last: a new note is handed to onNoteLoaded immediately.
        setUpNote(body);
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        title.setText(note.getTitle());
        recordings.clear();
        recordings.addAll(NoteData.recordings(note.getData()));
        rebuild();
    }

    @Override
    protected boolean collect(NoteEntity note) {
        note.setTitle(title.getText().trim());
        note.setData(NoteData.recordingsJson(recordings));
        StringBuilder text = new StringBuilder();
        for (NoteData.Recording r : recordings) if (!r.label.isEmpty()) text.append(r.label).append('\n');
        note.setContent(text.toString().trim());
        return !recordings.isEmpty() || !note.getTitle().isEmpty();
    }

    @Override
    void onKey(KeyEvent e) {
        super.onKey(e);
        if (!e.isConsumed() && !readOnly && e.isShortcutDown() && e.getCode() == KeyCode.R) {
            e.consume();
            toggleRecording();
        }
    }

    /** Recordings sent to me: play them, nothing else. */
    @Override
    protected void lockForReading() {
        super.lockForReading();
        recordButton.getParent().setVisible(false);
        ((javafx.scene.layout.Region) recordButton.getParent()).setManaged(false);
    }

    /** Leaving finishes a recording in progress (and keeps it); losing focus does not. */
    @Override
    void onDestroy() {
        if (recorder != null) stopRecording();
        stopPlayback();
        super.onDestroy();
    }

    private void updateEmpty() {
        emptyHint.setVisible(recordings.isEmpty() && recorder == null);
    }

    // ================================================================ record

    private void toggleRecording() {
        if (recorder != null) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        stopPlayback();
        withFolder(dir -> {
            recordingFile = dir.resolve("rec_" + System.currentTimeMillis() + ".wav");
            AudioRecorder r = new AudioRecorder();
            try {
                r.start(recordingFile);
            } catch (Exception e) {
                System.getLogger("AudioNote").log(System.Logger.Level.WARNING, "Could not start recording", e);
                Ui.toast("The microphone isn't available");
                try {
                    Files.deleteIfExists(recordingFile);
                } catch (Exception ignored) {
                    // Nothing was written.
                }
                return;
            }
            recorder = r;
            recordStart = System.nanoTime();
            amplitudes.clear();
            recordIcon.setIcon(Icons.STOP);
            Ui.tooltip(recordButton, "Stop (Ctrl+R)");
            show(liveWave, true);
            show(recordTimer, true);
            recordTimer.setText("0:00");
            updateEmpty();
            sampler.play();
        });
    }

    private void sample() {
        if (recorder == null) return;
        float level = (float) Math.min(1, Math.sqrt(recorder.takePeak() / 32767.0));
        amplitudes.add(level);
        liveWave.setLive(amplitudes);
        recordTimer.setText(NoteData.formatDuration(elapsed()));
    }

    private long elapsed() {
        return (System.nanoTime() - recordStart) / 1_000_000;
    }

    private void stopRecording() {
        if (recorder == null) return;
        sampler.stop();
        long duration = elapsed();
        recorder.stop();
        recorder = null;
        recordIcon.setIcon(Icons.MIC);
        Ui.tooltip(recordButton, "Record (Ctrl+R)");
        show(liveWave, false);
        show(recordTimer, false);

        if (duration < 500) {
            try {
                Files.deleteIfExists(recordingFile);
            } catch (Exception e) {
                System.getLogger("AudioNote").log(System.Logger.Level.WARNING, "Could not discard empty take", e);
            }
            updateEmpty();
            return;
        }
        String label = "Recording " + (recordings.size() + 1);
        NoteData.Recording r = new NoteData.Recording(recordingFile.getFileName().toString(), label, duration,
                System.currentTimeMillis(), downsample(amplitudes, WAVE_SAMPLES));
        recordings.add(0, r);
        rebuild();
        save(false);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
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

    // ================================================================== play

    /** A recording from another device that hasn't come down yet: fetch it now, then play it. */
    private void fetchThenPlay(NoteData.Recording r, Path file) {
        Ui.toast("Getting this recording…");
        int id = note.getId();
        Account.BG.execute(() -> {
            String problem;
            try {
                problem = NoteSync.fetch(id, r.file) ? null : Account.drive() == null
                        ? "Connect Google Drive in your account to get recordings from your other devices"
                        : "This recording hasn't been uploaded from its device yet";
            } catch (Exception e) {
                problem = "Couldn't download this recording. Check your connection";
            }
            String message = problem;
            javafx.application.Platform.runLater(() -> {
                if (message != null || !Files.exists(file)) Ui.toast(message != null ? message : "This recording isn't here yet");
                else toggle(r);
            });
        });
    }

    private void toggle(NoteData.Recording r) {
        if (playing == r && player != null) {
            if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
            else player.play();
            return;
        }
        stopPlayback();
        Path file = NoteData.dir(note.getId()).resolve(r.file);
        if (!Files.exists(file)) {
            fetchThenPlay(r, file);
            return;
        }
        MediaPlayer p;
        try {
            p = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (Exception e) {
            Ui.toast("Couldn't play that recording");
            return;
        }
        p.setOnError(() -> {
            Ui.toast("Couldn't play that recording");
            stopPlayback();
        });
        p.setOnEndOfMedia(this::stopPlayback);
        p.statusProperty().addListener((o, a, b) -> showRows());
        player = p;
        playing = r;
        p.play();
        progress.start();
    }

    private void stopPlayback() {
        progress.stop();
        if (player != null) {
            player.dispose();
            player = null;
        }
        playing = null;
        showRows();
    }

    // ================================================================== rows

    private final List<Row> rows = new ArrayList<>();

    private void rebuild() {
        rows.clear();
        list.getChildren().clear();
        for (NoteData.Recording r : recordings) {
            Row row = new Row(r);
            rows.add(row);
            list.getChildren().add(row);
        }
        showRows();
        updateEmpty();
    }

    private void showRows() {
        for (Row row : rows) row.show();
    }

    /** One recording: play button, name, waveform, length and date. Right-click to delete. */
    private final class Row extends HBox {
        final NoteData.Recording r;
        final Ui.IconView glyph = Ui.icon(Icons.PLAY_BIG, 24, "white");
        final Label label = Ui.label("", 16, "bold");
        final WaveformView wave = new WaveformView(28);

        Row(NoteData.Recording r) {
            this.r = r;
            getStyleClass().add("recording-card");
            StackPane play = new StackPane(glyph);
            play.setStyle("-fx-background-color: " + Theme.ref("tile_teal") + "; -fx-background-radius: 8; -fx-cursor: hand;");
            play.setMinSize(48, 48);
            play.setMaxSize(48, 48);
            play.setOnMouseClicked(e -> toggle(r));
            label.setStyle(label.getStyle() + "-fx-cursor: hand;");
            label.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                List<Links.Match> found = Links.find(r.label);
                if (e.isShortcutDown() && !found.isEmpty()) Links.activate(found.get(0));
                else rename(r);
            });
            Ui.tooltip(label, "Rename");
            Label meta = Ui.label(NoteData.formatDuration(r.durationMs) + "  ·  "
                    + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(r.createdAt)), 12, "secondary");
            VBox.setMargin(wave, new Insets(6, 0, 0, 0));
            VBox.setMargin(meta, new Insets(4, 0, 0, 0));
            VBox texts = new VBox(label, wave, meta);
            HBox.setHgrow(texts, Priority.ALWAYS);
            getChildren().addAll(play, texts);
            setOnContextMenuRequested(e -> Ui.confirm("Delete \"" + r.label + "\"?", "Delete", () -> {
                if (playing == r) stopPlayback();
                recordings.remove(r);
                Path f = NoteData.dir(note.getId()).resolve(r.file);
                NoteStore.write(s -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (Exception ex) {
                        System.getLogger("AudioNote").log(System.Logger.Level.WARNING, "Could not delete " + f, ex);
                    }
                });
                rebuild();
            }));
        }

        void show() {
            boolean isPlaying = playing == r && player != null;
            double fraction = 0;
            if (isPlaying) {
                Duration total = player.getTotalDuration();
                if (total != null && !total.isUnknown() && total.toMillis() > 0) {
                    fraction = player.getCurrentTime().toMillis() / total.toMillis();
                }
            }
            wave.setWave(r.wave, fraction);
            label.setText(r.label);
            glyph.setIcon(isPlaying && player.getStatus() == MediaPlayer.Status.PLAYING ? Icons.PAUSE : Icons.PLAY_BIG);
        }
    }

    private void rename(NoteData.Recording r) {
        Ui.prompt("Name this recording", "Name", r.label, "Save", name -> {
            r.label = name;
            showRows();
        });
    }
}
