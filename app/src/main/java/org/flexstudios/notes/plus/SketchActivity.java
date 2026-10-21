package org.flexstudios.notes.plus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sketch notes: draw with a finger on a sheet of paper.
 *
 * Strokes are kept as vectors in the note's folder ({@code sketch.json}), so a sketch reopens
 * fully editable with undo intact; a rendered {@code sketch.png} beside it is what the card
 * shows.
 */
public class SketchActivity extends NoteActivity {
    static final String STROKES_FILE = "sketch.json";
    static final String PREVIEW_FILE = "sketch.png";

    private static final int[] INKS = {0xFF1C1C1E, 0xFF8E8E93, 0xFFFF3B30, 0xFFFF9500,
            0xFFFFCC00, 0xFF34C759, 0xFF007AFF, 0xFFAF52DE};
    private static final float[] SIZES = {3, 7, 16};

    private DrawingView canvas;
    private EditText title;
    private File folder;
    private int revision;
    private final List<View> inkViews = new ArrayList<>();
    private final List<View> sizeViews = new ArrayList<>();
    private ImageButton penButton, markerButton, eraserButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sketch);
        canvas = findViewById(R.id.sketchCanvas);
        title = findViewById(R.id.sketchTitle);

        canvas.onChange = () -> {
            revision++;
            if (folder == null) withFolder(dir -> folder = dir);
            findViewById(R.id.btnUndo).setAlpha(canvas.canUndo() ? 1f : 0.35f);
            findViewById(R.id.btnRedo).setAlpha(canvas.canRedo() ? 1f : 0.35f);
        };
        findViewById(R.id.btnUndo).setOnClickListener(v -> canvas.undo());
        findViewById(R.id.btnRedo).setOnClickListener(v -> canvas.redo());
        findViewById(R.id.btnClear).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Clear the sheet?")
                .setPositiveButton("Clear", (d, w) -> canvas.clear())
                .setNegativeButton("Cancel", null)
                .show());
        penButton = findViewById(R.id.btnPen);
        markerButton = findViewById(R.id.btnMarker);
        eraserButton = findViewById(R.id.btnEraser);
        penButton.setOnClickListener(v -> setTool(DrawingView.PEN));
        markerButton.setOnClickListener(v -> setTool(DrawingView.MARKER));
        eraserButton.setOnClickListener(v -> setTool(DrawingView.ERASER));
        buildPalette();
        setTool(DrawingView.PEN);
        findViewById(R.id.btnUndo).setAlpha(0.35f);
        findViewById(R.id.btnRedo).setAlpha(0.35f);
        setUpNote(findViewById(R.id.sketchRoot), findViewById(R.id.toolbarSketch));
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        title.setText(note.getTitle());
        if (note.getId() <= 0) return;
        folder = NoteData.dir(this, note.getId());
        try {
            revision = new JSONObject(note.getData()).optInt("rev", 0);
        } catch (Exception ignored) {
            revision = 0; // No data yet, or unreadable: start counting afresh.
        }
        File strokes = new File(folder, STROKES_FILE);
        if (!strokes.exists()) return;
        AppDatabase.IO.execute(() -> {
            try (FileInputStream in = new FileInputStream(strokes)) {
                String json = new String(VaultCrypto.readAll(in), StandardCharsets.UTF_8);
                runOnUiThread(() -> canvas.load(json));
            } catch (Exception e) {
                Log.w("Sketch", "Could not read strokes", e);
            }
        });
    }

    @Override
    protected boolean collect(NoteEntity note) {
        note.setTitle(title.getText().toString().trim());
        boolean drawn = canvas.hasInk();
        try {
            note.setData(new JSONObject().put("rev", revision).put("strokes", canvas.strokeCount()).toString());
        } catch (Exception ignored) {
            // A JSON put on a fresh object cannot fail with these types.
        }
        note.setContent(note.getTitle().isEmpty() ? "Sketch" : note.getTitle());
        if (folder != null) writeFiles(folder);
        return drawn || !note.getTitle().isEmpty();
    }

    /**
     * Draws the card picture for strokes that arrived from another phone. Uses an unattached
     * view as wide as this screen, which is all {@link DrawingView#render} needs.
     */
    static void renderPreview(android.content.Context context, File dir, String strokes) {
        DrawingView view = new DrawingView(context, null);
        int width = context.getResources().getDisplayMetrics().widthPixels;
        view.layout(0, 0, width, width * 4);
        view.load(strokes);
        Bitmap preview = view.render(1080);
        File target = new File(dir, PREVIEW_FILE);
        if (preview == null) {
            VaultCrypto.deleteRecursive(target);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            preview.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.w("Sketch", "Could not draw a synced sketch", e);
        } finally {
            preview.recycle();
        }
    }

    /** Vectors for editing and a PNG for the card, written off the main thread. */
    private void writeFiles(File dir) {
        String json = canvas.toJson();
        Bitmap preview = canvas.render(1080);
        AppDatabase.IO.execute(() -> {
            try (FileOutputStream out = new FileOutputStream(new File(dir, STROKES_FILE))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w("Sketch", "Could not save strokes", e);
            }
            if (preview == null) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, PREVIEW_FILE))) {
                preview.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                Log.w("Sketch", "Could not save preview", e);
            } finally {
                preview.recycle();
            }
        });
    }

    // ================================================================= tools

    private void buildPalette() {
        LinearLayout inks = findViewById(R.id.inkRow);
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(30 * density), gap = Math.round(6 * density);
        for (int ink : INKS) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            inks.addView(swatch, lp);
            swatch.setOnClickListener(v -> {
                canvas.color = ink;
                if (canvas.tool == DrawingView.ERASER) setTool(DrawingView.PEN);
                refreshPalette();
            });
            swatch.setTag(ink);
            inkViews.add(swatch);
        }
        LinearLayout sizes = findViewById(R.id.sizeRow);
        for (float s : SIZES) {
            View dot = new View(this);
            int d = Math.round((10 + s * 1.1f) * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d, d);
            lp.setMarginEnd(Math.round(14 * density));
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            sizes.addView(dot, lp);
            dot.setTag(s);
            dot.setOnClickListener(v -> {
                canvas.width = s;
                refreshPalette();
            });
            sizeViews.add(dot);
        }
        refreshPalette();
    }

    private void refreshPalette() {
        float density = getResources().getDisplayMetrics().density;
        int ring = ContextCompat.getColor(this, R.color.text_primary);
        for (View v : inkViews) {
            int ink = (int) v.getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ink);
            boolean on = ink == canvas.color && canvas.tool != DrawingView.ERASER;
            g.setStroke(Math.round((on ? 3 : 1) * density), on ? ring : 0x33000000);
            v.setBackground(g);
            v.setScaleX(on ? 1.12f : 1f);
            v.setScaleY(on ? 1.12f : 1f);
        }
        for (View v : sizeViews) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            boolean on = (float) v.getTag() == canvas.width;
            g.setColor(on ? ring : ContextCompat.getColor(this, R.color.text_secondary));
            v.setBackground(g);
            v.setAlpha(on ? 1f : 0.5f);
        }
    }

    private void setTool(int tool) {
        canvas.tool = tool;
        int on = ContextCompat.getColor(this, R.color.brand_yellow);
        int off = ContextCompat.getColor(this, R.color.text_secondary);
        penButton.setColorFilter(tool == DrawingView.PEN ? on : off);
        markerButton.setColorFilter(tool == DrawingView.MARKER ? on : off);
        eraserButton.setColorFilter(tool == DrawingView.ERASER ? on : off);
        refreshPalette();
    }

    // ============================================================== the view

    /** A sheet of paper with smoothed strokes in dp, so a sketch survives any screen size. */
    public static class DrawingView extends View {
        static final int PEN = 0, MARKER = 1, ERASER = 2;
        private static final int PAPER = Color.WHITE;

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

        private final List<Stroke> strokes = new ArrayList<>();
        private final List<Stroke> undone = new ArrayList<>();
        private Stroke current;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        public DrawingView(Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
            density = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setLayerType(LAYER_TYPE_HARDWARE, null);
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
            invalidate();
            if (onChange != null) onChange.run();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            canvas.drawColor(PAPER);
            for (Stroke s : strokes) draw(canvas, s, density);
            if (current != null) draw(canvas, current, density);
        }

        private void draw(Canvas canvas, Stroke s, float scale) {
            List<Float> p = s.points;
            if (p.size() < 2) return;
            paint.setColor(s.tool == ERASER ? PAPER : s.color);
            paint.setAlpha(s.tool == MARKER ? 110 : 255);
            paint.setStrokeWidth((s.tool == MARKER ? s.width * 2.4f : s.tool == ERASER ? s.width * 3f : s.width) * scale);
            path.reset();
            path.moveTo(p.get(0) * scale, p.get(1) * scale);
            if (p.size() == 2) {
                path.lineTo(p.get(0) * scale + 0.1f, p.get(1) * scale);
            }
            for (int i = 2; i + 1 < p.size(); i += 2) {
                float px = p.get(i - 2) * scale, py = p.get(i - 1) * scale;
                float x = p.get(i) * scale, y = p.get(i + 1) * scale;
                path.quadTo(px, py, (px + x) / 2, (py + y) / 2);
            }
            canvas.drawPath(path, paint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX() / density, y = e.getY() / density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    current = new Stroke(color, width, tool);
                    current.points.add(x);
                    current.points.add(y);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (current == null) break;
                    for (int h = 0; h < e.getHistorySize(); h++) {
                        current.points.add(e.getHistoricalX(h) / density);
                        current.points.add(e.getHistoricalY(h) / density);
                    }
                    current.points.add(x);
                    current.points.add(y);
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    if (current != null) {
                        strokes.add(current);
                        undone.clear();
                        current = null;
                        changed();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    current = null;
                    invalidate();
                    break;
                default:
                    break;
            }
            return true;
        }

        /**
         * The drawn part of the sheet, cropped to the ink plus a margin and no wider than
         * {@code maxWidth}, for the card. Cropping is what makes a small doodle fill its card
         * instead of floating in a tall white page.
         */
        Bitmap render(int maxWidth) {
            if (getWidth() == 0 || getHeight() == 0 || !hasInk()) return null;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Stroke st : strokes) {
                if (st.tool == ERASER) continue;
                float reach = st.width * (st.tool == MARKER ? 1.2f : 0.5f);
                for (int i = 0; i + 1 < st.points.size(); i += 2) {
                    minX = Math.min(minX, st.points.get(i) - reach);
                    maxX = Math.max(maxX, st.points.get(i) + reach);
                    minY = Math.min(minY, st.points.get(i + 1) - reach);
                    maxY = Math.max(maxY, st.points.get(i + 1) + reach);
                }
            }
            float margin = 16;
            float viewW = getWidth() / density, viewH = getHeight() / density;
            float left = Math.max(0, minX - margin), top = Math.max(0, minY - margin);
            float right = Math.min(viewW, maxX + margin), bottom = Math.min(viewH, maxY + margin);
            // Never thinner than a third of the sheet, so a single line doesn't become a sliver.
            float minW = viewW / 3, minH = viewW / 3;
            if (right - left < minW) {
                float c = (left + right) / 2;
                left = Math.max(0, c - minW / 2);
                right = Math.min(viewW, left + minW);
            }
            if (bottom - top < minH) {
                float c = (top + bottom) / 2;
                top = Math.max(0, c - minH / 2);
                bottom = Math.min(viewH, top + minH);
            }
            float scale = Math.min(density, maxWidth / (right - left));
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round((right - left) * scale)),
                    Math.max(1, Math.round((bottom - top) * scale)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            c.drawColor(PAPER);
            c.translate(-left * scale, -top * scale);
            for (Stroke stroke : strokes) draw(c, stroke, scale);
            return bitmap;
        }

        String toJson() {
            JSONArray array = new JSONArray();
            try {
                for (Stroke s : strokes) {
                    JSONArray pts = new JSONArray();
                    for (Float f : s.points) pts.put(Math.round(f * 10) / 10.0);
                    array.put(new JSONObject().put("c", s.color).put("w", s.width).put("t", s.tool).put("p", pts));
                }
            } catch (Exception ignored) {
                // Plain numbers only.
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
                Log.w("Sketch", "Unreadable strokes", e);
            }
            invalidate();
        }
    }
}
