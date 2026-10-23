package org.flexstudios.notes.plus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mind map notes: a central idea with branches.
 *
 * Tap selects, "Add idea" grows a branch from the selection, double-tap renames, drag moves,
 * hold opens the idea's options (colour, fold, link a note, connect to another idea, delete).
 * Pinch zooms, dragging empty space pans, double-tap on empty space shows everything, and
 * Tidy lays the whole map out as a balanced tree. The same {@link MindMapView} draws the
 * overview on the note's card.
 */
public class MindMapActivity extends NoteActivity {

    private MindMapView map;
    private EditText title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mind_map);
        map = findViewById(R.id.mindMap);
        title = findViewById(R.id.mindMapTitle);

        map.listener = new MindMapView.Listener() {
            @Override
            public void onRename(NoteData.MindNode node) {
                name(node, false);
            }

            @Override
            public void onOptions(NoteData.MindNode node) {
                showOptions(node);
            }

            @Override
            public void onOpenLink(NoteData.MindNode node) {
                openLinkedNote(node);
            }

            @Override
            public void onConnected() {
                Snackbar.make(map, "Connected", Snackbar.LENGTH_SHORT).show();
            }
        };
        findViewById(R.id.btnAddIdea).setOnClickListener(v -> {
            NoteData.MindNode parent = map.selected();
            addChild(parent != null ? parent : map.root());
        });
        findViewById(R.id.btnCenter).setOnClickListener(v -> map.fit(true));
        findViewById(R.id.btnTidy).setOnClickListener(v -> map.tidy());
        findViewById(R.id.btnShareMap).setOnClickListener(v -> shareAsImage());

        // Last: a new note is handed to onNoteLoaded immediately.
        setUpNote(findViewById(R.id.mindMapRoot), findViewById(R.id.toolbarMindMap));
    }

    @Override
    protected void lockForReading() {
        super.lockForReading();
        map.locked = true;
        for (int id : new int[]{R.id.btnAddIdea, R.id.btnTidy}) findViewById(id).setVisibility(View.GONE);
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        title.setText(note.getTitle());
        NoteData.MindMap loaded = NoteData.mindMap(note.getData());
        if (loaded.nodes.isEmpty()) loaded.nodes.add(new NoteData.MindNode(1, -1, "", 0, 0));
        map.setFolder(note.getId() > 0 ? NoteData.dir(this, note.getId()) : null);
        map.setMap(loaded);
        map.post(() -> map.fit(false));
        if (note.getId() <= 0) map.post(() -> name(map.root(), true));
    }

    @Override
    protected boolean collect(NoteEntity note) {
        note.setTitle(title.getText().toString().trim());
        note.setData(NoteData.mindMapJson(map.model));
        StringBuilder text = new StringBuilder();
        for (NoteData.MindNode n : map.model.nodes) {
            if (!n.text.isEmpty()) text.append(n.text).append('\n');
            if (!n.sub.isEmpty()) text.append(n.sub).append('\n');
        }
        note.setContent(text.toString().trim());
        return !text.toString().trim().isEmpty() || !note.getTitle().isEmpty();
    }

    @Nullable
    @Override
    protected Boolean wrapText() {
        return map.model.wrap;
    }

    @Override
    protected void setWrapText(boolean wrap) {
        map.model.wrap = wrap;
        map.invalidate();
    }

    // ================================================================ ideas

    private void addChild(NoteData.MindNode parent) {
        if (parent.collapsed) map.toggleCollapsed(parent);
        name(map.addChild(parent), true);
    }

    /**
     * Names an idea. For a new one, "Next idea" saves it and starts a sibling straight away,
     * so a brainstorm never leaves the keyboard.
     */
    private void name(NoteData.MindNode node, boolean isNew) {
        name(node, isNew, false);
    }

    /** {@code behind}: the idea was just put between another and its parent; abandoned, it unwinds. */
    private void name(NoteData.MindNode node, boolean isNew, boolean behind) {
        boolean root = node.parent < 0;
        Runnable discard = () -> {
            if (!isNew || root || !node.text.isEmpty()) return;
            if (behind) map.unwrap(node);
            else map.removeBranch(node);
        };
        java.util.function.Consumer<String> apply = text -> {
            node.text = text;
            map.select(node);
            map.invalidate();
        };
        Prompts.text(this, root ? "Central idea" : isNew ? "New idea" : "Rename", null,
                root ? "What is this map about?" : "An idea", node.text, Prompts.TEXT, "Done",
                apply, discard,
                isNew && !root ? "Next idea" : null,
                text -> {
                    apply.accept(text);
                    NoteData.MindNode parent = map.find(node.parent);
                    if (parent != null) name(map.addChild(parent), true);
                });
    }

    private void showOptions(NoteData.MindNode node) {
        boolean root = node.parent < 0;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Rename");
        actions.add(() -> name(node, false));
        labels.add("Add idea");
        actions.add(() -> addChild(node));
        if (!root) {
            labels.add("Add idea behind");
            actions.add(() -> {
                NoteData.MindNode behind = map.insertBehind(node);
                if (behind != null) name(behind, true, true);
            });
        }
        labels.add(node.sub.isEmpty() ? "Add sub-text" : "Edit sub-text");
        actions.add(() -> editSub(node));
        labels.add("Colour");
        actions.add(() -> pickColor(node));
        if (map.hasChildren(node)) {
            labels.add(node.collapsed ? "Unfold branch" : "Fold branch");
            actions.add(() -> map.toggleCollapsed(node));
        }
        if (node.hasLink()) {
            labels.add("Open linked note");
            actions.add(() -> openLinkedNote(node));
            labels.add("Remove note link");
            actions.add(() -> {
                node.setLink(null);
                map.invalidate();
            });
        } else {
            labels.add("Link to a note…");
            actions.add(() -> pickNoteToLink(node));
        }
        labels.add("Connect to another idea…");
        actions.add(() -> {
            map.startConnecting(node);
            Snackbar.make(map, "Tap the idea to connect to", Snackbar.LENGTH_LONG)
                    .setAction("Cancel", v -> map.startConnecting(null)).show();
        });
        if (map.hasCrossLinks(node)) {
            labels.add("Remove its connections");
            actions.add(() -> map.removeCrossLinks(node));
        }
        if (!root) {
            labels.add("Delete branch");
            actions.add(() -> map.removeBranch(node));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(node.text.isEmpty() ? "Idea" : node.text)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Sub-text: more about an idea, shown smaller under it. Empty removes it. */
    private void editSub(NoteData.MindNode node) {
        Prompts.text(this, "Sub-text", null, "More about this idea", node.sub,
                Prompts.TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE, "Done", text -> {
                    node.sub = text.trim();
                    map.select(node);
                    map.invalidate();
                }, null);
    }

    private void pickColor(NoteData.MindNode node) {
        String[] colorNames = {"Blue", "Green", "Orange", "Purple", "Red", "Teal", "Grey"};
        String[] names = new String[colorNames.length + 1];
        names[0] = node.parent < 0 ? "Yellow (default)" : "Same as its branch";
        System.arraycopy(colorNames, 0, names, 1, colorNames.length);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Colour")
                .setSingleChoiceItems(names, node.color, (d, which) -> {
                    node.color = which;
                    map.refresh();
                    d.dismiss();
                })
                .show();
    }

    /** Obsidian-style: an idea can point at any other note and open it. */
    private void pickNoteToLink(NoteData.MindNode node) {
        AppDatabase.IO.execute(() -> {
            List<NoteEntity> all = AppDatabase.getInstance(this).noteDao().getAllDirect();
            List<NoteEntity> others = new ArrayList<>();
            for (NoteEntity n : all) if (note == null || n.getId() != note.getId()) others.add(n);
            runOnUiThread(() -> {
                if (others.isEmpty()) {
                    Toast.makeText(this, "No other notes to link yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[others.size()];
                for (int i = 0; i < others.size(); i++) {
                    NoteEntity n = others.get(i);
                    String t = n.getTitle() == null || n.getTitle().isEmpty() ? "Untitled" : n.getTitle();
                    labels[i] = NoteData.kind(n.getType()).label + "  ·  " + t;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Link to a note")
                        .setItems(labels, (d, which) -> {
                            node.setLink(others.get(which));
                            map.invalidate();
                        })
                        .show();
            });
        });
    }

    private void openLinkedNote(NoteData.MindNode node) {
        int id = node.link;
        String uuid = node.linkUuid;
        AppDatabase.IO.execute(() -> {
            NoteDao dao = AppDatabase.getInstance(this).noteDao();
            NoteEntity found = uuid != null ? dao.getByUuid(uuid) : dao.getNoteById(id);
            NoteEntity target = found == null || found.isDeleted() ? null : found;
            runOnUiThread(() -> {
                if (target == null) {
                    Toast.makeText(this, "That note no longer exists", Toast.LENGTH_SHORT).show();
                    node.setLink(null);
                    map.invalidate();
                    return;
                }
                startActivity(NoteData.editor(this, target.getType(), target.getId()));
            });
        });
    }

    /** Renders the whole map to a PNG and opens the share sheet. */
    private void shareAsImage() {
        Bitmap image = map.renderAll();
        if (image == null) return;
        AppDatabase.IO.execute(() -> {
            try {
                File out = new File(VaultCrypto.shareDir(this), "mind-map.png");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", out);
                Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Share mind map")));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't share the map", Toast.LENGTH_SHORT).show());
            } finally {
                image.recycle();
            }
        });
    }

    // ============================================================== the view

    /**
     * Draws and edits a mind map. Positions are in dp, so a map looks the same on any screen.
     * With {@code android:enabled="false"} it only draws, fitted to its bounds (the card).
     */
    public static class MindMapView extends View {
        interface Listener {
            void onRename(NoteData.MindNode node);

            void onOptions(NoteData.MindNode node);

            void onOpenLink(NoteData.MindNode node);

            void onConnected();
        }

        static final int[] PALETTE = {R.color.tile_blue, R.color.tile_green, R.color.tile_orange,
                R.color.tile_purple, R.color.tile_red, R.color.tile_teal, R.color.tile_gray};
        private static final float MAX_TEXT_DP = 180, PAD_X_DP = 14, NODE_H_DP = 40, ROOT_H_DP = 52;
        private static final float COLUMN_DP = 230, ROW_DP = 64, GRID_DP = 24;

        NoteData.MindMap model = new NoteData.MindMap();
        Listener listener;
        /** A map sent to me: look, pan, zoom and fold; nothing else. */
        boolean locked;
        private final boolean interactive;
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private final Map<Long, Integer> colorOf = new HashMap<>();
        private final Map<Long, NoteData.MindNode> byId = new HashMap<>();
        private final Set<Long> hidden = new HashSet<>();
        private final Map<Long, Integer> hiddenCount = new HashMap<>();
        private float scale = 1f, tx, ty;
        private long selectedId = -1;
        private NoteData.MindNode connectFrom;
        private NoteData.MindNode dragging;
        private float lastX, lastY;
        private final GestureDetector gestures;
        private final ScaleGestureDetector scaler;
        /** Pictures in ideas (added on the desktop): drawn above the text, at most 160 x 120 dp. */
        private static final float PIC_W_DP = 160, PIC_H_DP = 120;
        private File folder;
        private final Map<String, Bitmap> pictures = new HashMap<>();
        private final Set<String> loading = new HashSet<>();
        /** Of the last {@link #bounds} call: the picture's size (if any) and the top of the text. */
        private float picW, picH, textTop;
        private static final float MAX_SUB_DP = 220, SUB_GAP_DP = 2;
        /** Each idea's text and sub-text, laid out once at scale 1 and drawn scaled. */
        private final Map<Long, Block> blocks = new HashMap<>();

        private static final class Block {
            String key;
            StaticLayout title, sub;
            /** Pixels at scale 1. */
            float w, h;
        }

        public MindMapView(Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
            density = getResources().getDisplayMetrics().density;
            android.content.res.TypedArray a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.enabled});
            interactive = a.getBoolean(0, true);
            a.recycle();
            stroke.setStyle(Paint.Style.STROKE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            dashed.setStyle(Paint.Style.STROKE);
            dashed.setStrokeCap(Paint.Cap.ROUND);
            dashed.setPathEffect(new DashPathEffect(new float[]{8 * density, 7 * density}, 0));
            dots.setStrokeCap(Paint.Cap.ROUND);

            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(@NonNull MotionEvent e) {
                    if (handleBadgeTap(e.getX(), e.getY())) return true;
                    NoteData.MindNode hit = hit(e.getX(), e.getY());
                    if (connectFrom != null) {
                        if (hit != null && hit != connectFrom) connect(connectFrom, hit);
                        connectFrom = null;
                        invalidate();
                        return true;
                    }
                    select(hit);
                    return true;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent e) {
                    NoteData.MindNode hit = hit(e.getX(), e.getY());
                    if (hit != null && listener != null && !locked) listener.onRename(hit);
                    else fit(true);
                    return true;
                }

                @Override
                public void onLongPress(@NonNull MotionEvent e) {
                    NoteData.MindNode hit = hit(e.getX(), e.getY());
                    if (hit == null || listener == null || locked) return;
                    dragging = null;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    select(hit);
                    listener.onOptions(hit);
                }
            });
            scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(@NonNull ScaleGestureDetector d) {
                    float next = Math.max(0.25f, Math.min(3f, scale * d.getScaleFactor()));
                    float f = next / scale;
                    tx = d.getFocusX() - (d.getFocusX() - tx) * f;
                    ty = d.getFocusY() - (d.getFocusY() - ty) * f;
                    scale = next;
                    invalidate();
                    return true;
                }
            });
        }

        // ------------------------------------------------------------ model edits

        /** The note's folder, where its ideas' pictures are. */
        void setFolder(@Nullable File dir) {
            if (java.util.Objects.equals(dir, folder)) return;
            folder = dir;
            pictures.clear();
            loading.clear();
        }

        /** An idea's picture, decoded in the background the first time (null until then). */
        @Nullable
        private Bitmap picture(String name) {
            Bitmap b = pictures.get(name);
            if (b != null || folder == null || !loading.add(name)) return b;
            File file = new File(folder, name);
            File dir = folder;
            int max = Math.round(PIC_W_DP * density * 2);
            AppDatabase.IO.execute(() -> {
                Bitmap decoded = null;
                try {
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 1;
                    while (Math.max(bounds.outWidth, bounds.outHeight) / (o.inSampleSize * 2) >= max) o.inSampleSize *= 2;
                    decoded = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
                } catch (Exception | OutOfMemoryError ignored) {
                    // Not here yet (still downloading) or not a picture: the placeholder stays.
                }
                Bitmap shown = decoded;
                post(() -> {
                    if (!dir.equals(folder)) return;
                    if (shown != null) pictures.put(name, shown);
                    else loading.remove(name); // Try again on a later draw, once it has arrived.
                    invalidate();
                });
            });
            return null;
        }

        void setMap(NoteData.MindMap map) {
            model = map;
            blocks.clear();
            refresh();
            if (!interactive && getWidth() > 0) fit(false);
        }

        /** Recomputes colours and folded branches after any structural change. */
        void refresh() {
            byId.clear();
            for (NoteData.MindNode n : model.nodes) byId.put(n.id, n);
            recolor();
            recomputeHidden();
            invalidate();
        }

        NoteData.MindNode root() {
            for (NoteData.MindNode n : model.nodes) if (n.parent < 0) return n;
            return model.nodes.isEmpty() ? null : model.nodes.get(0);
        }

        NoteData.MindNode find(long id) {
            return byId.get(id);
        }

        NoteData.MindNode selected() {
            NoteData.MindNode n = byId.get(selectedId);
            return n != null && !hidden.contains(n.id) ? n : null;
        }

        void select(NoteData.MindNode node) {
            selectedId = node == null ? -1 : node.id;
            invalidate();
        }

        boolean hasChildren(NoteData.MindNode node) {
            for (NoteData.MindNode n : model.nodes) if (n.parent == node.id) return true;
            return false;
        }

        void toggleCollapsed(NoteData.MindNode node) {
            node.collapsed = !node.collapsed;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            refresh();
        }

        /** Places a child beside its parent, below its existing siblings on that side. */
        NoteData.MindNode addChild(NoteData.MindNode parent) {
            long id = 1;
            for (NoteData.MindNode n : model.nodes) id = Math.max(id, n.id + 1);
            NoteData.MindNode root = root();
            float side;
            if (parent.parent < 0) {
                int right = 0, left = 0;
                for (NoteData.MindNode n : model.nodes) {
                    if (n.parent == parent.id) {
                        if (n.x >= parent.x) right++;
                        else left++;
                    }
                }
                side = right <= left ? 1 : -1;
            } else {
                side = Math.signum(parent.x - (root == null ? 0 : root.x));
                if (side == 0) side = 1;
            }
            float y = parent.y;
            boolean any = false;
            for (NoteData.MindNode n : model.nodes) {
                if (n.parent == parent.id && Math.signum(n.x - parent.x) == side) {
                    float below = n.y + Math.max(ROW_DP, sizeDp(n)[1] / 2 + 44);
                    y = any ? Math.max(y, below) : below;
                    any = true;
                }
            }
            float gap = Math.max(COLUMN_DP, sizeDp(parent)[0] / 2 + 110);
            NoteData.MindNode child = new NoteData.MindNode(id, parent.id, "", parent.x + side * gap, y);
            model.nodes.add(child);
            refresh();
            select(child);
            ensureVisible(child);
            return child;
        }

        /**
         * Puts a new idea between this one and its parent, where this one was; this one and its
         * branch step outward to make room. Null for the central idea.
         */
        @Nullable
        NoteData.MindNode insertBehind(NoteData.MindNode node) {
            if (node.parent < 0) return null;
            long id = 1;
            for (NoteData.MindNode n : model.nodes) id = Math.max(id, n.id + 1);
            float side = side(node);
            NoteData.MindNode mid = new NoteData.MindNode(id, node.parent, "", node.x, node.y);
            shiftBranch(node, side * COLUMN_DP);
            node.parent = id;
            model.nodes.add(mid);
            refresh();
            select(mid);
            ensureVisible(mid);
            return mid;
        }

        /** Undoes {@link #insertBehind}: the idea goes, its children rejoin its parent and step back. */
        void unwrap(NoteData.MindNode mid) {
            if (mid.parent < 0) return;
            float side = side(mid);
            for (NoteData.MindNode k : new ArrayList<>(model.nodes)) {
                if (k.parent != mid.id) continue;
                k.parent = mid.parent;
                shiftBranch(k, -side * COLUMN_DP);
            }
            model.nodes.remove(mid);
            model.links.removeIf(l -> l[0] == mid.id || l[1] == mid.id);
            selectedId = -1;
            refresh();
        }

        /** Moves an idea and everything under it sideways. */
        private void shiftBranch(NoteData.MindNode node, float dx) {
            List<Long> queue = new ArrayList<>();
            queue.add(node.id);
            node.x += dx;
            for (int q = 0; q < queue.size(); q++) {
                for (NoteData.MindNode k : model.nodes) {
                    if (k.parent == queue.get(q)) {
                        k.x += dx;
                        queue.add(k.id);
                    }
                }
            }
        }

        void removeBranch(NoteData.MindNode node) {
            if (node.parent < 0) return;
            Set<Long> doomed = new HashSet<>();
            doomed.add(node.id);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (NoteData.MindNode n : model.nodes) {
                    if (!doomed.contains(n.id) && doomed.contains(n.parent)) {
                        doomed.add(n.id);
                        grew = true;
                    }
                }
            }
            model.nodes.removeIf(n -> doomed.contains(n.id));
            model.links.removeIf(l -> doomed.contains(l[0]) || doomed.contains(l[1]));
            selectedId = -1;
            refresh();
        }

        void startConnecting(NoteData.MindNode from) {
            connectFrom = from;
            invalidate();
        }

        private void connect(NoteData.MindNode a, NoteData.MindNode b) {
            for (long[] l : model.links) {
                if ((l[0] == a.id && l[1] == b.id) || (l[0] == b.id && l[1] == a.id)) return;
            }
            model.links.add(new long[]{a.id, b.id});
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            if (listener != null) listener.onConnected();
        }

        boolean hasCrossLinks(NoteData.MindNode node) {
            for (long[] l : model.links) if (l[0] == node.id || l[1] == node.id) return true;
            return false;
        }

        void removeCrossLinks(NoteData.MindNode node) {
            model.links.removeIf(l -> l[0] == node.id || l[1] == node.id);
            invalidate();
        }

        /**
         * Lays the map out as a balanced tree: first-level ideas alternate right and left of
         * the centre, and every subtree gets vertical room in proportion to its leaves. The
         * ideas glide to their new places.
         */
        void tidy() {
            NoteData.MindNode root = root();
            if (root == null) return;
            Map<Long, float[]> target = new HashMap<>();
            target.put(root.id, new float[]{0, 0});
            List<NoteData.MindNode> right = new ArrayList<>(), left = new ArrayList<>();
            int i = 0;
            for (NoteData.MindNode n : model.nodes) {
                if (n.parent == root.id) (i++ % 2 == 0 ? right : left).add(n);
            }
            float rootW = sizeDp(root)[0];
            placeColumn(right, 1, 0, rootW, 0, target);
            placeColumn(left, -1, 0, rootW, 0, target);

            Map<Long, float[]> start = new HashMap<>();
            for (NoteData.MindNode n : model.nodes) start.put(n.id, new float[]{n.x, n.y});
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0, 1).setDuration(420);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            anim.addUpdateListener(v -> {
                float f = (float) v.getAnimatedValue();
                for (NoteData.MindNode n : model.nodes) {
                    float[] s = start.get(n.id), t = target.get(n.id);
                    if (s == null || t == null) continue;
                    n.x = s[0] + (t[0] - s[0]) * f;
                    n.y = s[1] + (t[1] - s[1]) * f;
                }
                invalidate();
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    fit(true);
                }
            });
            anim.start();
        }

        /** Stacks a column of ideas, each with vertical room for its own size or its branch's. */
        private void placeColumn(List<NoteData.MindNode> column, int side, float parentX, float parentW, float centerY,
                                 Map<Long, float[]> target) {
            float total = 0;
            for (NoteData.MindNode n : column) total += span(n);
            float y = centerY - total / 2;
            for (NoteData.MindNode n : column) {
                float span = span(n);
                float cy = y + span / 2;
                float w = sizeDp(n)[0];
                // Wide ideas push the next column out, so long text never runs under its neighbours.
                float x = parentX + side * Math.max(COLUMN_DP, parentW / 2 + w / 2 + 48);
                target.put(n.id, new float[]{x, cy});
                List<NoteData.MindNode> kids = new ArrayList<>();
                for (NoteData.MindNode k : model.nodes) if (k.parent == n.id) kids.add(k);
                placeColumn(kids, side, x, w, cy, target);
                y += span;
            }
        }

        /** Vertical room (dp) an idea needs: its own height plus the usual gap, or its branch's. */
        private float span(NoteData.MindNode n) {
            float own = sizeDp(n)[1] + ROW_DP - NODE_H_DP;
            if (n.collapsed) return own;
            float sum = 0;
            for (NoteData.MindNode k : model.nodes) if (k.parent == n.id) sum += span(k);
            return Math.max(own, sum);
        }

        /** An idea's width and height in dp. */
        private float[] sizeDp(NoteData.MindNode n) {
            bounds(n, 1, 0, 0);
            return new float[]{rect.width() / density, rect.height() / density};
        }

        // ---------------------------------------------------------- derived state

        /** Explicit colours win and are inherited downwards; first-level ideas get a palette. */
        private void recolor() {
            colorOf.clear();
            NoteData.MindNode root = root();
            if (root == null) return;
            int i = 0;
            for (NoteData.MindNode n : model.nodes) {
                if (n.parent == root.id) {
                    int index = n.color > 0 ? n.color - 1 : i % (PALETTE.length - 1);
                    colorOf.put(n.id, ContextCompat.getColor(getContext(), PALETTE[index]));
                    i++;
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (NoteData.MindNode n : model.nodes) {
                    if (colorOf.containsKey(n.id) || n.parent < 0) continue;
                    Integer inherited = colorOf.get(n.parent);
                    if (inherited == null) continue;
                    colorOf.put(n.id, n.color > 0
                            ? ContextCompat.getColor(getContext(), PALETTE[n.color - 1]) : inherited);
                    changed = true;
                }
            }
        }

        private void recomputeHidden() {
            hidden.clear();
            hiddenCount.clear();
            for (NoteData.MindNode n : model.nodes) {
                if (!n.collapsed) continue;
                int count = 0;
                List<Long> queue = new ArrayList<>();
                queue.add(n.id);
                for (int q = 0; q < queue.size(); q++) {
                    for (NoteData.MindNode k : model.nodes) {
                        if (k.parent == queue.get(q)) {
                            hidden.add(k.id);
                            queue.add(k.id);
                            count++;
                        }
                    }
                }
                hiddenCount.put(n.id, count);
            }
        }

        // ---------------------------------------------------------------- framing

        /** Zooms and centres so every visible idea fits. */
        void fit(boolean animate) {
            if (model.nodes.isEmpty() || getWidth() == 0) return;
            float[] b = contentBounds();
            float pad = (interactive ? 48 : 10) * density;
            float w = b[2] - b[0], h = b[3] - b[1];
            float target = Math.min((getWidth() - pad * 2) / Math.max(w, 1), (getHeight() - pad * 2) / Math.max(h, 1));
            target = Math.min(target, interactive ? 1.2f : 1f);
            float targetTx = getWidth() / 2f - (b[0] + w / 2) * target;
            float targetTy = getHeight() / 2f - (b[1] + h / 2) * target;
            if (!animate) {
                scale = target;
                tx = targetTx;
                ty = targetTy;
                invalidate();
                return;
            }
            float s0 = scale, x0 = tx, y0 = ty;
            final float finalTarget = target;
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0, 1).setDuration(300);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                scale = s0 + (finalTarget - s0) * f;
                tx = x0 + (targetTx - x0) * f;
                ty = y0 + (targetTy - y0) * f;
                invalidate();
            });
            anim.start();
        }

        /** Bounds of the visible ideas at scale 1, origin 0: {left, top, right, bottom} in px. */
        private float[] contentBounds() {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (NoteData.MindNode n : model.nodes) {
                if (hidden.contains(n.id)) continue;
                bounds(n, 1f, 0, 0);
                minX = Math.min(minX, rect.left);
                minY = Math.min(minY, rect.top);
                maxX = Math.max(maxX, rect.right);
                maxY = Math.max(maxY, rect.bottom);
            }
            return new float[]{minX, minY, maxX, maxY};
        }

        private void ensureVisible(NoteData.MindNode n) {
            bounds(n, scale, tx, ty);
            float margin = 32 * density;
            if (rect.right > getWidth() - margin) tx -= rect.right - getWidth() + margin;
            if (rect.left < margin) tx += margin - rect.left;
            if (rect.bottom > getHeight() - margin) ty -= rect.bottom - getHeight() + margin;
            if (rect.top < margin) ty += margin - rect.top;
        }

        /** The whole map as a picture, at full size on the theme's background. */
        Bitmap renderAll() {
            if (model.nodes.isEmpty()) return null;
            float[] b = contentBounds();
            float pad = 32 * density;
            float w = b[2] - b[0] + pad * 2, h = b[3] - b[1] + pad * 2;
            float s = Math.min(1f, 4096 / Math.max(w, h));
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            c.drawColor(ContextCompat.getColor(getContext(), R.color.app_background));
            drawMap(c, s, (pad - b[0]) * s, (pad - b[1]) * s, false);
            return bitmap;
        }

        // ---------------------------------------------------------------- geometry

        private void bounds(NoteData.MindNode n, float s, float ox, float oy) {
            boolean isRoot = n.parent < 0;
            Block b = block(n);
            float w = b.w + PAD_X_DP * 2 * density;
            float lineH = (isRoot ? ROOT_H_DP : NODE_H_DP) * density;
            // One line keeps the old height; more lines (wrapped, or a sub-text) grow the idea.
            float textH = Math.max(lineH, b.h + (isRoot ? 26 : 20) * density);
            float h = textH;
            picW = picH = 0;
            if (n.image != null) {
                // The desktop's geometry, so a map looks the same on both.
                Bitmap img = picture(n.image);
                float iw = img == null ? PIC_W_DP : img.getWidth(), ih = img == null ? PIC_H_DP * 0.66f : img.getHeight();
                float f = Math.min(PIC_W_DP / Math.max(1, iw), PIC_H_DP / Math.max(1, ih));
                if (img == null) f = Math.min(1, f);
                picW = iw * f * density;
                picH = ih * f * density;
                w = Math.max(w, picW + 16 * density);
                h = textH + picH + 8 * density;
            }
            float cx = n.x * density * s + ox, cy = n.y * density * s + oy;
            rect.set(cx - w * s / 2, cy - h * s / 2, cx + w * s / 2, cy + h * s / 2);
            textTop = rect.bottom - textH * s / 2 - b.h * s / 2;
            picW *= s;
            picH *= s;
        }

        /**
         * An idea's text, never cut short: wrapped at {@link #MAX_TEXT_DP} (sub-text at
         * {@link #MAX_SUB_DP}) when the map wraps, otherwise each line in full.
         */
        private Block block(NoteData.MindNode n) {
            boolean isRoot = n.parent < 0;
            Context c = getContext();
            int titleColor = isRoot ? ContextCompat.getColor(c, R.color.black)
                    : ContextCompat.getColor(c, n.text.isEmpty() ? R.color.text_secondary : R.color.text_primary);
            int subColor = isRoot ? 0xB3000000 : ContextCompat.getColor(c, R.color.text_secondary);
            String label = label(n);
            String key = label + '\u0000' + n.sub + '\u0000' + model.wrap + isRoot + titleColor + '/' + subColor;
            Block b = blocks.get(n.id);
            if (b != null && b.key.equals(key)) return b;
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize((isRoot ? 18 : 15) * density);
            tp.setFakeBoldText(isRoot);
            tp.setColor(titleColor);
            TextPaint sp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            sp.setTextSize((isRoot ? 13.5f : 12.5f) * density);
            sp.setColor(subColor);
            float tw = widest(label, tp), sw = n.sub.isEmpty() ? 0 : widest(n.sub, sp);
            if (model.wrap) {
                tw = Math.min(tw, MAX_TEXT_DP * density);
                sw = Math.min(sw, MAX_SUB_DP * density);
            }
            int width = Math.max(1, (int) Math.ceil(Math.max(tw, sw)));
            b = new Block();
            b.key = key;
            b.title = layout(label, tp, width);
            b.sub = n.sub.isEmpty() ? null : layout(n.sub, sp, width);
            b.w = Math.max(lineWidth(b.title), b.sub == null ? 0 : lineWidth(b.sub));
            b.h = b.title.getHeight() + (b.sub == null ? 0 : SUB_GAP_DP * density + b.sub.getHeight());
            blocks.put(n.id, b);
            return b;
        }

        private static float widest(String text, TextPaint paint) {
            float w = 0;
            for (String line : text.split("\n", -1)) w = Math.max(w, paint.measureText(line));
            return w;
        }

        private static StaticLayout layout(String text, TextPaint paint, int width) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
        }

        private static float lineWidth(StaticLayout l) {
            float w = 0;
            for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineWidth(i));
            return w;
        }

        private String label(NoteData.MindNode n) {
            if (!n.text.isEmpty()) return n.text;
            return n.parent < 0 ? "Central idea" : "Idea";
        }

        /** Which side of the centre an idea sits on, so its fold badge points outward. */
        private float side(NoteData.MindNode n) {
            NoteData.MindNode root = root();
            float s = root == null ? 1 : Math.signum(n.x - root.x);
            return s == 0 ? 1 : s;
        }

        private NoteData.MindNode hit(float x, float y) {
            for (int i = model.nodes.size() - 1; i >= 0; i--) {
                NoteData.MindNode n = model.nodes.get(i);
                if (hidden.contains(n.id)) continue;
                bounds(n, scale, tx, ty);
                if (rect.contains(x, y)) return n;
            }
            return null;
        }

        /** The "+N" fold badge and the link badge are tappable. */
        private boolean handleBadgeTap(float x, float y) {
            float r = 14 * density * Math.max(scale, 0.7f);
            for (NoteData.MindNode n : model.nodes) {
                if (hidden.contains(n.id)) continue;
                bounds(n, scale, tx, ty);
                if (n.collapsed) {
                    float bx = side(n) > 0 ? rect.right + r : rect.left - r;
                    if (Math.hypot(x - bx, y - rect.centerY()) < r * 1.3f) {
                        toggleCollapsed(n);
                        return true;
                    }
                }
                if (n.hasLink() && Math.hypot(x - rect.right, y - rect.top) < r * 1.3f) {
                    if (listener != null) listener.onOpenLink(n);
                    return true;
                }
            }
            return false;
        }

        // ------------------------------------------------------------------ drawing

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (!interactive || oldw == 0) fit(false);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (interactive) drawGrid(canvas);
            drawMap(canvas, scale, tx, ty, interactive);
        }

        /** A quiet dot grid that moves with the map, so panning feels physical. */
        private void drawGrid(Canvas canvas) {
            float step = GRID_DP * density * scale;
            if (step < 14 * density) return; // Too dense to stay calm when zoomed far out.
            dots.setColor(ContextCompat.getColor(getContext(), R.color.divider));
            dots.setStrokeWidth(2.2f * density);
            float startX = ((tx % step) + step) % step, startY = ((ty % step) + step) % step;
            for (float x = startX; x < getWidth(); x += step) {
                for (float y = startY; y < getHeight(); y += step) canvas.drawPoint(x, y, dots);
            }
        }

        private void drawMap(Canvas canvas, float s, float ox, float oy, boolean decorations) {
            Context c = getContext();
            int surface = ContextCompat.getColor(c, R.color.app_surface);
            int muted = ContextCompat.getColor(c, R.color.text_secondary);
            int accent = ContextCompat.getColor(c, R.color.accent_blue);

            // Branches.
            line.setStrokeWidth(Math.max(1.5f, 2.5f * s) * density);
            for (NoteData.MindNode n : model.nodes) {
                if (hidden.contains(n.id)) continue;
                NoteData.MindNode p = byId.get(n.parent);
                if (p == null) continue;
                float px = p.x * density * s + ox, py = p.y * density * s + oy;
                float nx = n.x * density * s + ox, ny = n.y * density * s + oy;
                float mid = (px + nx) / 2;
                path.reset();
                path.moveTo(px, py);
                path.cubicTo(mid, py, mid, ny, nx, ny);
                Integer color = colorOf.get(n.id);
                line.setColor(color == null ? muted : color);
                line.setAlpha(190);
                canvas.drawPath(path, line);
            }

            // Cross-links, dashed and arched, so they read as "related" rather than "child of".
            dashed.setStrokeWidth(Math.max(1.2f, 2f * s) * density);
            dashed.setColor(accent);
            dashed.setAlpha(170);
            for (long[] l : model.links) {
                NoteData.MindNode a = byId.get(l[0]), b = byId.get(l[1]);
                if (a == null || b == null || hidden.contains(a.id) || hidden.contains(b.id)) continue;
                float ax = a.x * density * s + ox, ay = a.y * density * s + oy;
                float bx = b.x * density * s + ox, by = b.y * density * s + oy;
                float mx = (ax + bx) / 2, my = (ay + by) / 2;
                float dx = bx - ax, dy = by - ay;
                path.reset();
                path.moveTo(ax, ay);
                path.quadTo(mx - dy * 0.25f, my + dx * 0.25f, bx, by);
                canvas.drawPath(path, dashed);
            }

            // Ideas.
            for (NoteData.MindNode n : model.nodes) {
                if (hidden.contains(n.id)) continue;
                bounds(n, s, ox, oy);
                boolean isRoot = n.parent < 0;
                Integer color = colorOf.get(n.id);
                float radius = n.image != null ? Math.min(rect.height() / 2, 16 * density * s) : rect.height() / 2;
                if (isRoot) {
                    fill.setColor(n.color > 0 ? ContextCompat.getColor(c, PALETTE[n.color - 1])
                            : ContextCompat.getColor(c, R.color.brand_yellow));
                    canvas.drawRoundRect(rect, radius, radius, fill);
                } else {
                    fill.setColor(surface);
                    canvas.drawRoundRect(rect, radius, radius, fill);
                    stroke.setColor(color == null ? muted : color);
                    stroke.setStrokeWidth(Math.max(1f, 2f * s) * density);
                    canvas.drawRoundRect(rect, radius, radius, stroke);
                }
                if (decorations && (n.id == selectedId || n == connectFrom)) {
                    stroke.setColor(n == connectFrom ? ContextCompat.getColor(c, R.color.tile_orange) : accent);
                    stroke.setStrokeWidth(3 * density);
                    float grow = 4 * density;
                    rect.inset(-grow, -grow);
                    canvas.drawRoundRect(rect, radius + grow, radius + grow, stroke);
                    rect.inset(grow, grow);
                }
                Block b = block(n);
                canvas.save();
                canvas.translate(rect.centerX() - b.title.getWidth() * s / 2, textTop);
                canvas.scale(s, s);
                b.title.draw(canvas);
                if (b.sub != null) {
                    canvas.translate(0, b.title.getHeight() + SUB_GAP_DP * density);
                    b.sub.draw(canvas);
                }
                canvas.restore();
                if (n.image != null) drawPicture(canvas, n.image, s);
                drawBadges(canvas, n, s, color == null ? muted : color);
            }
        }

        private final Rect picSource = new Rect();
        private final RectF picTarget = new RectF();
        private final Paint picPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        private void drawPicture(Canvas canvas, String name, float s) {
            Bitmap img = picture(name);
            float px = rect.centerX() - picW / 2, py = rect.top + 8 * density * s;
            picTarget.set(px, py, px + picW, py + picH);
            if (img != null) {
                picSource.set(0, 0, img.getWidth(), img.getHeight());
                canvas.drawBitmap(img, picSource, picTarget, picPaint);
            } else {
                fill.setColor(ContextCompat.getColor(getContext(), R.color.thumb_placeholder));
                canvas.drawRoundRect(picTarget, 10 * density * s, 10 * density * s, fill);
            }
        }

        /** "+N" on a folded idea (outward side) and an arrow on one that links to a note. */
        private void drawBadges(Canvas canvas, NoteData.MindNode n, float s, int color) {
            float r = 11 * density * Math.max(s, 0.6f);
            float left = rect.left, right = rect.right, top = rect.top, cy = rect.centerY();
            if (n.collapsed) {
                Integer count = hiddenCount.get(n.id);
                float bx = side(n) > 0 ? right + r * 1.2f : left - r * 1.2f;
                fill.setColor(color);
                canvas.drawCircle(bx, cy, r, fill);
                drawCentered(canvas, "+" + (count == null ? 0 : count), bx, cy, r);
            }
            if (n.hasLink()) {
                fill.setColor(ContextCompat.getColor(getContext(), R.color.accent_blue));
                canvas.drawCircle(right, top, r * 0.9f, fill);
                drawCentered(canvas, "↗", right, top, r * 0.9f);
            }
        }

        private void drawCentered(Canvas canvas, String s, float cx, float cy, float r) {
            labelPaint.setTextSize(r * 1.05f);
            labelPaint.setFakeBoldText(true);
            labelPaint.setColor(0xFFFFFFFF);
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            canvas.drawText(s, cx - labelPaint.measureText(s) / 2, cy - (fm.ascent + fm.descent) / 2, labelPaint);
        }

        // ------------------------------------------------------------------- touch

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!interactive) return false;
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = locked ? null : hit(e.getX(), e.getY());
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    dragging = null;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (scaler.isInProgress() || e.getPointerCount() > 1) break;
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    if (dragging != null) {
                        // A folded idea carries its hidden branch with it.
                        moveWithHidden(dragging, dx / (density * scale), dy / (density * scale));
                    } else {
                        tx += dx;
                        ty += dy;
                    }
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = null;
                    break;
                default:
                    break;
            }
            return true;
        }

        private void moveWithHidden(NoteData.MindNode node, float dx, float dy) {
            node.x += dx;
            node.y += dy;
            if (!node.collapsed) return;
            List<Long> queue = new ArrayList<>();
            queue.add(node.id);
            for (int q = 0; q < queue.size(); q++) {
                for (NoteData.MindNode k : model.nodes) {
                    if (k.parent == queue.get(q)) {
                        k.x += dx;
                        k.y += dy;
                        queue.add(k.id);
                    }
                }
            }
        }
    }
}
