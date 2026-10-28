package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A note in the grid: the phone's note_card, bound the way NoteAdapter binds it. Every card
 * previews its note the way its type reads best: text shows its words, a list its first items
 * with their ticks, a mind map a live overview, a journal its date and first photo, a sketch
 * the drawing, an audio note its waveform.
 */
final class NoteCard extends VBox {
    private static final int LIST_PREVIEW = 5;

    interface Listener {
        void onOpen(NoteEntity note);

        void onOptions(NoteEntity note);
    }

    final NoteEntity note;
    private final VBox inner = new VBox();
    private final Label title = new Label();

    NoteCard(NoteEntity note, Listener listener) {
        this.note = note;
        getStyleClass().add("note-card");
        String tint = NoteData.tint(note.getColor(), "app_surface");
        setStyle("-fx-background-color: " + Theme.ref("divider") + ", " + Theme.ref(tint) + ";");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(36);
        clip.setArcHeight(36);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        inner.setPadding(new Insets(14));
        title.getStyleClass().add("card-title");
        Ui.clampLines(title, 2, 0);
        String t = note.getTitle() == null ? "" : note.getTitle();
        title.setText(t);
        getChildren().add(inner);

        if (NoteLock.isLocked(note.getData())) {
            // Locked: the title (if any) and a lock; nothing of what is inside.
            addTitle("Locked note");
            HBox locked = new HBox(6, Ui.icon(Icons.LOCK, 16, "text_secondary"), new Label("Locked"));
            locked.getStyleClass().add("card-body");
            locked.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            VBox.setMargin(locked, new Insets(4, 0, 0, 0));
            inner.getChildren().add(locked);
        } else switch (note.getType()) {
            case NoteData.LIST -> bindList();
            case NoteData.MINDMAP -> bindMindMap();
            case NoteData.JOURNAL -> bindJournal();
            case NoteData.SKETCH -> bindSketch();
            case NoteData.AUDIO -> bindAudio();
            default -> bindText();
        }
        footer();

        setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 1 && e.isStillSincePress()) {
                listener.onOpen(note);
            }
        });
        setOnContextMenuRequested(e -> listener.onOptions(note));
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    // =================================================================== types

    private void addTitle(String fallback) {
        if (title.getText().isEmpty()) {
            if (fallback == null) return;
            title.setText(fallback);
        }
        inner.getChildren().add(title);
    }

    private Label body(String text, int lines) {
        Label body = new Label(text);
        body.getStyleClass().add("card-body");
        Ui.clampLines(body, lines, 2);
        VBox.setMargin(body, new Insets(4, 0, 0, 0));
        return body;
    }

    private void meta(String text) {
        Label meta = new Label(text);
        meta.getStyleClass().add("card-meta");
        VBox.setMargin(meta, new Insets(6, 0, 0, 0));
        inner.getChildren().add(meta);
    }

    private void bindText() {
        String body = plain(note.getContent());
        String picture = firstPicture(note.getContent());
        if (picture != null) photoCover(picture);
        addTitle(body.isEmpty() && picture == null ? "Empty note" : null);
        if (!body.isEmpty()) inner.getChildren().add(body(body, picture == null ? 8 : 4));
    }

    /** The words without the pictures' embed lines. */
    static String plain(String content) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (RichBody.EMBED.matcher(line.trim()).matches()) continue;
            out.append(Markdown.plainLine(line)).append('\n');
        }
        return out.toString().trim().replaceAll("\n{3,}", "\n\n");
    }

    static String firstPicture(String content) {
        if (content == null) return null;
        for (String line : content.split("\n")) {
            var m = RichBody.EMBED.matcher(line.trim());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private void photoCover(String name) {
        StackPane cover = cover(140);
        getChildren().add(0, cover);
        Images.load(NoteData.dir(note.getId()).resolve(name), 600, img -> {
            if (img != null) cover.getChildren().add(picture(img, true));
        });
    }

    private void bindList() {
        List<NoteData.TodoItem> items = NoteData.todos(note.getData());
        addTitle("List");
        VBox list = new VBox();
        VBox.setMargin(list, new Insets(4, 0, 0, 0));
        int done = 0, tasks = 0;
        for (NoteData.TodoItem item : items) {
            if (item.picture() != null) continue;
            tasks++;
            if (item.done) done++;
        }
        for (int i = 0; i < Math.min(LIST_PREVIEW, items.size()); i++) {
            NoteData.TodoItem item = items.get(i);
            Label row = new Label(item.picture() != null ? "🖼  Picture" : (item.done ? "☑  " : "☐  ") + item.text);
            row.getStyleClass().add("card-row");
            if (item.done) row.getStyleClass().add("done");
            row.setMaxWidth(Double.MAX_VALUE);
            list.getChildren().add(row);
        }
        inner.getChildren().add(list);
        if (tasks > 0) {
            String more = items.size() > LIST_PREVIEW ? "  ·  +" + (items.size() - LIST_PREVIEW) + " more" : "";
            meta(done == tasks ? "All done" + more : done + " of " + tasks + " done" + more);
        }
    }

    private void bindMindMap() {
        NoteData.MindMap map = NoteData.mindMap(note.getData());
        MindMapCanvas view = new MindMapCanvas(false);
        view.setMinHeight(130);
        view.setPrefHeight(130);
        view.setMaxHeight(130);
        view.setMap(map);
        view.setFolder(NoteData.dir(note.getId()));
        getChildren().add(0, view);
        if (title.getText().isEmpty()) {
            for (NoteData.MindNode n : map.nodes) if (n.parent < 0 && !n.text.isEmpty()) title.setText(n.text);
        }
        addTitle(null);
        meta(map.nodes.size() + (map.nodes.size() == 1 ? " idea" : " ideas"));
    }

    private void bindJournal() {
        NoteData.Journal journal = NoteData.journal(note.getData(), note.getTimestamp());
        Date day = new Date(journal.day);
        Label dayNumber = new Label(new SimpleDateFormat("d", Locale.getDefault()).format(day));
        dayNumber.getStyleClass().add("light");
        dayNumber.setStyle("-fx-font-size: 34px; -fx-text-fill: " + Theme.ref("tile_purple") + ";");
        Label weekday = Ui.label(new SimpleDateFormat("EEEE", Locale.getDefault()).format(day), 13, "bold");
        Label month = Ui.label(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(day), 12, "secondary");
        VBox words = new VBox(weekday, month);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox date = new HBox(8, dayNumber, words);
        if (!journal.mood.isEmpty()) date.getChildren().add(Emoji.view(journal.mood, 26));
        date.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(date, new Insets(0, 0, 6, 0));
        inner.getChildren().add(date);

        String pasted = firstPicture(note.getContent());
        if (!journal.media.isEmpty()) {
            String first = journal.media.get(0);
            if (NoteData.isVideo(first)) {
                StackPane cover = cover(140);
                getChildren().add(0, cover);
                videoCover(cover);
            } else {
                photoCover(first);
            }
        } else if (pasted != null) {
            photoCover(pasted);
        }
        addTitle(null);
        String body = plain(note.getContent());
        if (!body.isEmpty()) inner.getChildren().add(body(body, journal.media.isEmpty() && pasted == null ? 6 : 3));
        if (journal.media.size() > 1) meta(journal.media.size() + " photos & videos");
    }

    private void bindSketch() {
        Path png = NoteData.dir(note.getId()).resolve(SketchEditor.PREVIEW_FILE);
        if (Files.exists(png)) {
            double[] ratio = {0.75};
            StackPane cover = new StackPane() {
                @Override
                protected double computePrefHeight(double width) {
                    return Math.min(280, width * ratio[0]);
                }

                @Override
                public Orientation getContentBias() {
                    return Orientation.HORIZONTAL;
                }
            };
            cover.setStyle("-fx-background-color: white;");
            getChildren().add(0, cover);
            Images.load(png, 800, img -> {
                if (img == null) return;
                ratio[0] = img.getHeight() / Math.max(1, img.getWidth());
                cover.getChildren().add(picture(img, false));
                cover.requestLayout();
                requestLayout();
            });
            addTitle(null);
        } else {
            addTitle("Sketch");
        }
    }

    private void bindAudio() {
        List<NoteData.Recording> recordings = NoteData.recordings(note.getData());
        long total = 0;
        for (NoteData.Recording r : recordings) total += r.durationMs;
        addTitle(recordings.isEmpty() ? "Audio note" : recordings.get(0).label);
        if (!recordings.isEmpty()) {
            WaveformView wave = new WaveformView(34);
            wave.setWave(recordings.get(0).wave, 0);
            VBox.setMargin(wave, new Insets(8, 0, 0, 0));
            inner.getChildren().add(wave);
            meta(recordings.size() + (recordings.size() == 1 ? " recording" : " recordings")
                    + "  ·  " + NoteData.formatDuration(total));
        }
    }

    private void footer() {
        NoteData.Kind kind = NoteData.kind(note.getType());
        String when = relative(note.getTimestamp());
        int people = note.getGroupId() == null ? 0 : GroupNotes.count(note.getGroupMembers());
        int sent = SentNotes.count(note.getSentTo());
        Label date = new Label(note.isReceived() ? "@" + note.getSharedFrom() + "  ·  " + when
                : people > 0 ? "Shared · " + people + "  ·  " + when
                : sent > 0 ? "Sent to " + sent + "  ·  " + when : when);
        date.getStyleClass().add("card-date");
        HBox.setHgrow(date, Priority.ALWAYS);
        date.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(6, Ui.icon(kind.icon(), 14, kind.accent()), date);
        if (note.isPinned()) row.getChildren().add(Ui.icon(Icons.PIN, 14, "brand_yellow"));
        row.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(row, new Insets(10, 0, 0, 0));
        inner.getChildren().add(row);
    }

    // ================================================================== covers

    private static StackPane cover(double height) {
        StackPane cover = new StackPane();
        cover.getStyleClass().add("media-tile");
        cover.setStyle("-fx-background-radius: 0;");
        cover.setMinHeight(height);
        cover.setPrefHeight(height);
        cover.setMaxHeight(height);
        return cover;
    }

    /** Videos show the placeholder and a play mark (there is no frame grabber on the desktop). */
    static void videoCover(StackPane cover) {
        cover.getChildren().add(Ui.icon(Icons.PLAY_BIG, 36, "text_secondary"));
    }

    /**
     * A picture filling its parent: cropped to fill, or shown whole. A layer of its own,
     * because a stylesheet background on the parent would win over one set from code.
     */
    static Region picture(Image img, boolean crop) {
        Region r = new Region();
        r.setBackground(imageBackground(img, crop));
        r.setMouseTransparent(true);
        r.setMinSize(0, 0);
        return r;
    }

    /** A picture as a region's background: cropped to fill, or shown whole. */
    static Background imageBackground(Image img, boolean crop) {
        return new Background(new BackgroundImage(img, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, !crop, crop)));
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
}
