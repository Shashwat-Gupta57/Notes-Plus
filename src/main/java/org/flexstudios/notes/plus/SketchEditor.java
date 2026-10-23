package org.flexstudios.notes.plus;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sketch notes: draw with the mouse or a pen on a sheet of paper. The phone's SketchActivity.
 *
 * Strokes are kept as vectors in the note's folder ({@code sketch.json}, the phone's format),
 * so a sketch reopens fully editable; a rendered {@code sketch.png} beside it is what the card
 * shows. Ctrl+Z / Ctrl+Y undo and redo; P, M and E pick pen, marker and eraser.
 */
final class SketchEditor extends NoteEditor {
    static final String STROKES_FILE = "sketch.json";
    static final String PREVIEW_FILE = "sketch.png";

    private static final int[] INKS = {0xFF1C1C1E, 0xFF8E8E93, 0xFFFF3B30, 0xFFFF9500,
            0xFFFFCC00, 0xFF34C759, 0xFF007AFF, 0xFFAF52DE};
    private static final float[] SIZES = {3, 7, 16};

    private final DrawingCanvas canvas = new DrawingCanvas();
    private final TextField title = new TextField();
    private Path folder;
    private int revision;
    private final List<Region> inkViews = new ArrayList<>();
    private final List<Region> sizeViews = new ArrayList<>();
    private final Button penButton, markerButton, eraserButton, undoButton, redoButton;

    SketchEditor(String type, int noteId) {
        super(type, noteId);
        title.getStyleClass().addAll("bare", "bold");
        title.setStyle("-fx-font-size: 24px;");
        Links.install(title);
        title.setPromptText("Sketch title");
        title.setOnAction(e -> canvas.requestFocus());

        canvas.onChange = () -> {
            revision++;
            if (folder == null) withFolder(dir -> folder = dir);
            refreshHistory();
        };
        penButton = Ui.iconButton(Icons.NOTE_SKETCH, 44, 24, "text_secondary", "Pen (P)", () -> setTool(DrawingCanvas.PEN));
        markerButton = Ui.iconButton(Icons.BRUSH, 44, 24, "text_secondary", "Marker (M)", () -> setTool(DrawingCanvas.MARKER));
        eraserButton = Ui.iconButton(Icons.ERASER, 44, 24, "text_secondary", "Eraser (E)", () -> setTool(DrawingCanvas.ERASER));
        undoButton = Ui.iconButton(Icons.UNDO, 44, 24, "text_primary", "Undo (Ctrl+Z)", canvas::undo);
        redoButton = Ui.iconButton(Icons.REDO, 44, 24, "text_primary", "Redo (Ctrl+Y)", canvas::redo);
        Button clear = Ui.iconButton(Icons.DELETE, 44, 24, "text_secondary", "Clear",
                () -> Ui.confirm("Clear the sheet?", "Clear", canvas::clear));

        HBox sizes = new HBox();
        sizes.setAlignment(Pos.CENTER_LEFT);
        HBox tray = new HBox(penButton, markerButton, eraserButton, Ui.spacer(), sizes, undoButton, redoButton, clear);
        tray.setAlignment(Pos.CENTER_LEFT);
        tray.setPadding(new Insets(8, 8, 0, 8));
        HBox inks = new HBox();
        inks.setAlignment(Pos.CENTER_LEFT);
        inks.setPadding(new Insets(8, 16, 16, 16));
        ScrollPane inkScroll = new ScrollPane(inks);
        inkScroll.getStyleClass().add("no-bars");
        inkScroll.setFitToHeight(true);
        buildPalette(inks, sizes);
        setTool(DrawingCanvas.PEN);
        refreshHistory();

        StackPane paper = new StackPane(canvas);
        paper.getStyleClass().add("paper");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        clip.widthProperty().bind(paper.widthProperty());
        clip.heightProperty().bind(paper.heightProperty());
        canvas.setClip(clip);
        BorderPane.setMargin(paper, new Insets(0, 12, 0, 12));

        StackPane titleRow = new StackPane(title);
        titleRow.setPadding(new Insets(0, 20, 8, 20));
        BorderPane body = new BorderPane(paper);
        body.setTop(titleRow);
        body.setBottom(new VBox(tray, inkScroll));
        setUpNote(body);
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        title.setText(note.getTitle());
        if (note.getId() <= 0) {
            canvas.requestFocus();
            return;
        }
        folder = NoteData.dir(note.getId());
        try {
            revision = new JSONObject(note.getData()).optInt("rev", 0);
        } catch (Exception ignored) {
            revision = 0; // No data yet, or unreadable: start counting afresh.
        }
        Path strokes = folder.resolve(STROKES_FILE);
        if (!Files.exists(strokes)) return;
        NoteStore.load(s -> {
            try {
                return Files.readString(strokes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.getLogger("Sketch").log(System.Logger.Level.WARNING, "Could not read strokes", e);
                return null;
            }
        }, json -> {
            if (json != null) canvas.load(json);
            refreshHistory();
        });
    }

    @Override
    protected boolean collect(NoteEntity note) {
        note.setTitle(title.getText().trim());
        boolean drawn = canvas.hasInk();
        note.setData(new JSONObject().put("rev", revision).put("strokes", canvas.strokeCount()).toString());
        note.setContent(note.getTitle().isEmpty() ? "Sketch" : note.getTitle());
        if (folder != null) writeFiles(folder);
        return drawn || !note.getTitle().isEmpty();
    }

    @Override
    void onKey(KeyEvent e) {
        super.onKey(e);
        if (e.isConsumed() || title.isFocused() || readOnly) return;
        if (e.isShortcutDown() && e.getCode() == KeyCode.Z && !e.isShiftDown()) {
            e.consume();
            canvas.undo();
        } else if (e.isShortcutDown() && (e.getCode() == KeyCode.Y || (e.getCode() == KeyCode.Z && e.isShiftDown()))) {
            e.consume();
            canvas.redo();
        } else if (!e.isShortcutDown() && e.getCode() == KeyCode.P) {
            setTool(DrawingCanvas.PEN);
        } else if (!e.isShortcutDown() && e.getCode() == KeyCode.M) {
            setTool(DrawingCanvas.MARKER);
        } else if (!e.isShortcutDown() && e.getCode() == KeyCode.E) {
            setTool(DrawingCanvas.ERASER);
        }
    }

    /**
     * Draws the card picture for strokes that arrived from another device, on an unattached
     * sheet as wide as a phone's (the strokes are in its units). FX thread.
     */
    static void renderPreview(Path dir, String strokes) {
        DrawingCanvas view = new DrawingCanvas();
        view.resize(400, 1600);
        view.load(strokes);
        WritableImage preview = view.render(1080);
        Path target = dir.resolve(PREVIEW_FILE);
        try {
            if (preview == null) Files.deleteIfExists(target);
            else ImageIO.write(SwingFXUtils.fromFXImage(preview, null), "png", target.toFile());
        } catch (Exception e) {
            System.getLogger("Sketch").log(System.Logger.Level.WARNING, "Could not draw a synced sketch", e);
        }
    }

    /** Vectors for editing and a PNG for the card; the files are written off the FX thread. */
    private void writeFiles(Path dir) {
        String json = canvas.toJson();
        WritableImage preview = canvas.render(1080);
        var buffered = preview == null ? null : SwingFXUtils.fromFXImage(preview, null);
        NoteStore.write(s -> {
            try {
                Files.writeString(dir.resolve(STROKES_FILE), json, StandardCharsets.UTF_8);
                if (buffered != null) ImageIO.write(buffered, "png", dir.resolve(PREVIEW_FILE).toFile());
                else Files.deleteIfExists(dir.resolve(PREVIEW_FILE));
            } catch (Exception e) {
                System.getLogger("Sketch").log(System.Logger.Level.WARNING, "Could not save the sketch", e);
            }
        });
    }

    // ================================================================= tools

    private void buildPalette(HBox inks, HBox sizes) {
        for (int ink : INKS) {
            Region swatch = new Region();
            swatch.setMinSize(30, 30);
            swatch.setMaxSize(30, 30);
            swatch.setUserData(ink);
            HBox.setMargin(swatch, new Insets(0, 6, 0, 0));
            swatch.setOnMouseClicked(e -> {
                canvas.color = ink;
                if (canvas.tool == DrawingCanvas.ERASER) setTool(DrawingCanvas.PEN);
                refreshPalette();
            });
            inks.getChildren().add(swatch);
            inkViews.add(swatch);
        }
        for (float s : SIZES) {
            Region dot = new Region();
            double d = 10 + s * 1.1;
            dot.setMinSize(d, d);
            dot.setMaxSize(d, d);
            dot.setUserData(s);
            HBox.setMargin(dot, new Insets(0, 14, 0, 0));
            dot.setOnMouseClicked(e -> {
                canvas.width = s;
                refreshPalette();
            });
            Ui.tooltip(dot, s == SIZES[0] ? "Thin" : s == SIZES[1] ? "Medium" : "Thick");
            sizes.getChildren().add(dot);
            sizeViews.add(dot);
        }
        refreshPalette();
    }

    private void refreshPalette() {
        String ring = Theme.ref("text_primary");
        for (Region v : inkViews) {
            int ink = (int) v.getUserData();
            boolean on = ink == canvas.color && canvas.tool != DrawingCanvas.ERASER;
            v.setStyle("-fx-cursor: hand; -fx-background-radius: 100; -fx-background-color: "
                    + (on ? ring : "#00000033") + ", " + web(ink) + "; -fx-background-insets: 0, " + (on ? 3 : 1) + ";");
            v.setScaleX(on ? 1.12 : 1);
            v.setScaleY(on ? 1.12 : 1);
        }
        for (Region v : sizeViews) {
            boolean on = (float) v.getUserData() == canvas.width;
            v.setStyle("-fx-cursor: hand; -fx-background-radius: 100; -fx-background-color: "
                    + (on ? ring : Theme.ref("text_secondary")) + ";");
            v.setOpacity(on ? 1 : 0.5);
        }
    }

    private void setTool(int tool) {
        canvas.tool = tool;
        ((Ui.IconView) penButton.getGraphic()).color(tool == DrawingCanvas.PEN ? "brand_yellow" : "text_secondary");
        ((Ui.IconView) markerButton.getGraphic()).color(tool == DrawingCanvas.MARKER ? "brand_yellow" : "text_secondary");
        ((Ui.IconView) eraserButton.getGraphic()).color(tool == DrawingCanvas.ERASER ? "brand_yellow" : "text_secondary");
        refreshPalette();
    }

    private void refreshHistory() {
        undoButton.setOpacity(canvas.canUndo() ? 1 : 0.35);
        redoButton.setOpacity(canvas.canRedo() ? 1 : 0.35);
    }

    private static String web(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    private static Color color(int argb, double alpha) {
        return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, alpha);
    }

    // ============================================================== the view

    /**
     * A sheet of paper with smoothed strokes in dp (px here), so a sketch survives any screen
     * size and opens on the phone as drawn. Finished strokes live on one canvas; the stroke
     * being drawn on another above it, so a marker's transparency never stacks on itself.
     */
    /** A sketch sent to me: no drawing, no tools. */
    @Override
    protected void lockForReading() {
        super.lockForReading();
        canvas.locked = true;
        canvas.setCursor(javafx.scene.Cursor.DEFAULT);
        for (javafx.scene.Node n : new javafx.scene.Node[]{penButton, markerButton, eraserButton, undoButton, redoButton}) {
            javafx.scene.Parent bar = n.getParent();
            if (bar != null) {
                bar.setVisible(false);
                if (bar instanceof javafx.scene.layout.Region r) r.setManaged(false);
            }
        }
    }

    static final class DrawingCanvas extends StackPane {
        /** A sketch sent to me: shown, not drawn on. */
        boolean locked;

        static final int PEN = 0, MARKER = 1, ERASER = 2;
        private static final Color PAPER = Color.WHITE;

        private static final class Stroke {
            final int color, tool;
            final float width;
            final List<Float> points = new ArrayList<>();

            Stroke(int color, float width, int tool) {
                this.color = color;
                this.width = width;
                this.tool = tool;
            }
        }

        int color = INKS[0];
        float width = SIZES[0];
        int tool = PEN;
        Runnable onChange;

        private final Canvas base = new Canvas(), live = new Canvas();
        private final List<Stroke> strokes = new ArrayList<>();
        private final List<Stroke> undone = new ArrayList<>();
        private Stroke current;

        DrawingCanvas() {
            getChildren().addAll(base, live);
            setMinSize(0, 0);
            setFocusTraversable(true);
            setCursor(Cursor.CROSSHAIR);
            setOnMousePressed(e -> {
                if (!locked) pressed(e);
            });
            setOnMouseDragged(e -> {
                if (!locked) dragged(e);
            });
            setOnMouseReleased(e -> {
                if (!locked) released(e);
            });
        }

        boolean hasInk() {
            for (Stroke s : strokes) if (s.tool != ERASER) return true;
            return false;
        }

        int strokeCount() {
            return strokes.size();
        }

        boolean canUndo() {
            return !strokes.isEmpty();
        }

        boolean canRedo() {
            return !undone.isEmpty();
        }

        void undo() {
            if (strokes.isEmpty()) return;
            undone.add(strokes.remove(strokes.size() - 1));
            changed();
        }

        void redo() {
            if (undone.isEmpty()) return;
            strokes.add(undone.remove(undone.size() - 1));
            changed();
        }

        void clear() {
            strokes.clear();
            undone.clear();
            changed();
        }

        private void changed() {
            redrawBase();
            if (onChange != null) onChange.run();
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            if (base.getWidth() != getWidth() || base.getHeight() != getHeight()) {
                base.setWidth(getWidth());
                base.setHeight(getHeight());
                live.setWidth(getWidth());
                live.setHeight(getHeight());
                redrawBase();
            }
        }

        private void redrawBase() {
            GraphicsContext g = base.getGraphicsContext2D();
            g.setFill(PAPER);
            g.fillRect(0, 0, base.getWidth(), base.getHeight());
            for (Stroke s : strokes) draw(g, s, 1);
        }

        private static void draw(GraphicsContext g, Stroke s, double scale) {
            List<Float> p = s.points;
            if (p.size() < 2) return;
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.setStroke(s.tool == ERASER ? PAPER : color(s.color, s.tool == MARKER ? 110 / 255.0 : 1));
            g.setLineWidth((s.tool == MARKER ? s.width * 2.4 : s.tool == ERASER ? s.width * 3 : s.width) * scale);
            g.beginPath();
            g.moveTo(p.get(0) * scale, p.get(1) * scale);
            if (p.size() == 2) g.lineTo(p.get(0) * scale + 0.1, p.get(1) * scale);
            for (int i = 2; i + 1 < p.size(); i += 2) {
                double px = p.get(i - 2) * scale, py = p.get(i - 1) * scale;
                double x = p.get(i) * scale, y = p.get(i + 1) * scale;
                g.quadraticCurveTo(px, py, (px + x) / 2, (py + y) / 2);
            }
            g.stroke();
        }

        private void pressed(MouseEvent e) {
            requestFocus();
            if (e.getButton() != MouseButton.PRIMARY) return;
            current = new Stroke(color, width, tool);
            current.points.add((float) e.getX());
            current.points.add((float) e.getY());
            drawLive();
        }

        private void dragged(MouseEvent e) {
            if (current == null) return;
            current.points.add((float) e.getX());
            current.points.add((float) e.getY());
            drawLive();
        }

        private void released(MouseEvent e) {
            if (current == null) return;
            strokes.add(current);
            undone.clear();
            current = null;
            live.getGraphicsContext2D().clearRect(0, 0, live.getWidth(), live.getHeight());
            changed();
        }

        private void drawLive() {
            GraphicsContext g = live.getGraphicsContext2D();
            g.clearRect(0, 0, live.getWidth(), live.getHeight());
            if (current != null) draw(g, current, 1);
        }

        /**
         * The drawn part of the sheet, cropped to the ink plus a margin and no wider than
         * {@code maxWidth}, for the card. Cropping is what makes a small doodle fill its card
         * instead of floating in a tall white page.
         */
        WritableImage render(int maxWidth) {
            if (getWidth() == 0 || getHeight() == 0 || !hasInk()) return null;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Stroke st : strokes) {
                if (st.tool == ERASER) continue;
                double reach = st.width * (st.tool == MARKER ? 1.2 : 0.5);
                for (int i = 0; i + 1 < st.points.size(); i += 2) {
                    minX = Math.min(minX, st.points.get(i) - reach);
                    maxX = Math.max(maxX, st.points.get(i) + reach);
                    minY = Math.min(minY, st.points.get(i + 1) - reach);
                    maxY = Math.max(maxY, st.points.get(i + 1) + reach);
                }
            }
            double margin = 16;
            // Ink beyond the sheet (the window was made smaller since) still counts.
            double left = Math.max(0, minX - margin), top = Math.max(0, minY - margin);
            double right = maxX + margin, bottom = maxY + margin;
            // Never thinner than a third of the sheet, so a single line doesn't become a sliver.
            double minW = getWidth() / 3, minH = getWidth() / 3;
            if (right - left < minW) {
                double c = (left + right) / 2;
                left = Math.max(0, c - minW / 2);
                right = left + minW;
            }
            if (bottom - top < minH) {
                double c = (top + bottom) / 2;
                top = Math.max(0, c - minH / 2);
                bottom = top + minH;
            }
            double scale = Math.min(2, maxWidth / (right - left));
            Canvas out = new Canvas(Math.max(1, Math.round((right - left) * scale)),
                    Math.max(1, Math.round((bottom - top) * scale)));
            GraphicsContext g = out.getGraphicsContext2D();
            g.setFill(PAPER);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.translate(-left * scale, -top * scale);
            for (Stroke stroke : strokes) draw(g, stroke, scale);
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(PAPER);
            return out.snapshot(params, null);
        }

        String toJson() {
            JSONArray array = new JSONArray();
            for (Stroke s : strokes) {
                JSONArray pts = new JSONArray();
                for (Float f : s.points) pts.put(Math.round(f * 10) / 10.0);
                array.put(new JSONObject().put("c", s.color).put("w", s.width).put("t", s.tool).put("p", pts));
            }
            return array.toString();
        }

        void load(String json) {
            strokes.clear();
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    Stroke s = new Stroke(o.optInt("c", INKS[0]), (float) o.optDouble("w", 3), o.optInt("t", PEN));
                    JSONArray pts = o.optJSONArray("p");
                    for (int k = 0; pts != null && k < pts.length(); k++) s.points.add((float) pts.optDouble(k));
                    strokes.add(s);
                }
            } catch (Exception e) {
                System.getLogger("Sketch").log(System.Logger.Level.WARNING, "Unreadable strokes", e);
            }
            redrawBase();
        }
    }
}
