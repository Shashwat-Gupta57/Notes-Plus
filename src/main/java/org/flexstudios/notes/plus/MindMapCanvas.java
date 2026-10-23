package org.flexstudios.notes.plus;

import javafx.animation.Interpolator;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws and edits a mind map: the phone's MindMapView. Positions are in dp (here, px), so a
 * map looks the same on any screen and on the phone.
 *
 * Click selects, double-click renames (on empty space: shows everything), drag moves an idea,
 * dragging empty space pans, the wheel or a pinch zooms, right-click opens the idea's options.
 * Non-interactive, it only draws, fitted to its bounds: the overview on a note's card.
 */
final class MindMapCanvas extends Region {

    interface Listener {
        void onRename(NoteData.MindNode node);

        void onOptions(NoteData.MindNode node);

        void onOpenLink(NoteData.MindNode node);

        void onConnected();
    }

    static final String[] PALETTE = {"tile_blue", "tile_green", "tile_orange", "tile_purple", "tile_red",
            "tile_teal", "tile_gray"};
    private static final double MAX_TEXT = 180, PAD_X = 14, NODE_H = 40, ROOT_H = 52;
    private static final double COLUMN = 230, ROW = 64, GRID = 24;

    NoteData.MindMap model = new NoteData.MindMap();
    Listener listener;
    private final boolean interactive;
    private final Canvas canvas = new Canvas();
    private final Text measure = new Text();
    private final Map<Long, Color> colorOf = new HashMap<>();
    private final Map<Long, NoteData.MindNode> byId = new HashMap<>();
    private final Set<Long> hidden = new HashSet<>();
    private final Map<Long, Integer> hiddenCount = new HashMap<>();
    private double scale = 1, tx, ty;
    private long selectedId = -1;
    private NoteData.MindNode connectFrom;
    private NoteData.MindNode dragging;
    private double lastX, lastY, downX, downY;
    private boolean moved;
    private boolean fitted;

    /** Scratch rectangle of the last {@link #bounds} call: left, top, right, bottom... */
    private double rl, rt, rr, rb;
    /** ...and, for an idea with a picture, the picture's size and the text line's centre. */
    private double rpw, rph, rty;

    private static final double PIC_W = 160, PIC_H = 120;
    private java.nio.file.Path folder;
    private final Map<String, javafx.scene.image.Image> pictures = new HashMap<>();
    private final Set<String> loading = new HashSet<>();

    /** Live editors' canvases, redrawn when Ctrl changes so links get their underline. */
    private static final Set<MindMapCanvas> LIVE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    static void redrawAll() {
        for (MindMapCanvas c : LIVE) c.draw();
    }

    MindMapCanvas(boolean interactive) {
        this.interactive = interactive;
        if (interactive) LIVE.add(this);
        getChildren().add(canvas);
        setMinSize(0, 0);
        Theme.dark.addListener((o, a, b) -> refresh());
        if (interactive) {
            setFocusTraversable(true);
            setOnMousePressed(this::pressed);
            setOnMouseDragged(this::dragged);
            setOnMouseReleased(this::released);
            setOnMouseClicked(this::clicked);
            setOnScroll(this::scrolled);
            setOnZoom(this::zoomed);
            setOnContextMenuRequested(e -> {
                NoteData.MindNode hit = hit(e.getX(), e.getY());
                if (hit == null || listener == null) return;
                dragging = null;
                select(hit);
                listener.onOptions(hit);
            });
        } else {
            setMouseTransparent(true);
        }
    }

    /** Where the ideas' pictures are (the note's folder). */
    void setFolder(java.nio.file.Path dir) {
        folder = dir;
        draw();
    }

    /** The picture of an idea, loading it in the background the first time. */
    private javafx.scene.image.Image picture(String name) {
        javafx.scene.image.Image img = pictures.get(name);
        if (img != null || folder == null || !loading.add(name)) return img;
        Images.load(folder.resolve(name), 480, loaded -> {
            if (loaded == null) return;
            pictures.put(name, loaded);
            draw();
        });
        return null;
    }

    /** The idea under a screen point, for the editor's paste. */
    NoteData.MindNode at(double x, double y) {
        return hit(x, y);
    }

    // ------------------------------------------------------------ model edits

    void setMap(NoteData.MindMap map) {
        model = map;
        fitted = false;
        refresh();
        if (!interactive && getWidth() > 0) fit(false);
    }

    /** Recomputes colours and folded branches after any structural change. */
    void refresh() {
        byId.clear();
        for (NoteData.MindNode n : model.nodes) byId.put(n.id, n);
        recolor();
        recomputeHidden();
        draw();
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
        draw();
    }

    boolean hasChildren(NoteData.MindNode node) {
        for (NoteData.MindNode n : model.nodes) if (n.parent == node.id) return true;
        return false;
    }

    void toggleCollapsed(NoteData.MindNode node) {
        node.collapsed = !node.collapsed;
        refresh();
    }

    /** Places a child beside its parent, below its existing siblings on that side. */
    NoteData.MindNode addChild(NoteData.MindNode parent) {
        long id = 1;
        for (NoteData.MindNode n : model.nodes) id = Math.max(id, n.id + 1);
        NoteData.MindNode root = root();
        double side;
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
        double y = parent.y;
        boolean any = false;
        for (NoteData.MindNode n : model.nodes) {
            if (n.parent == parent.id && Math.signum(n.x - parent.x) == side) {
                y = any ? Math.max(y, n.y + ROW) : n.y + ROW;
                any = true;
            }
        }
        NoteData.MindNode child = new NoteData.MindNode(id, parent.id, "", parent.x + side * COLUMN, y);
        model.nodes.add(child);
        refresh();
        select(child);
        ensureVisible(child);
        return child;
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
        draw();
    }

    private void connect(NoteData.MindNode a, NoteData.MindNode b) {
        for (long[] l : model.links) {
            if ((l[0] == a.id && l[1] == b.id) || (l[0] == b.id && l[1] == a.id)) return;
        }
        model.links.add(new long[]{a.id, b.id});
        if (listener != null) listener.onConnected();
    }

    boolean hasCrossLinks(NoteData.MindNode node) {
        for (long[] l : model.links) if (l[0] == node.id || l[1] == node.id) return true;
        return false;
    }

    void removeCrossLinks(NoteData.MindNode node) {
        model.links.removeIf(l -> l[0] == node.id || l[1] == node.id);
        draw();
    }

    /**
     * Lays the map out as a balanced tree: first-level ideas alternate right and left of the
     * centre, and every subtree gets vertical room in proportion to its leaves. The ideas glide
     * to their new places.
     */
    void tidy() {
        NoteData.MindNode root = root();
        if (root == null) return;
        Map<Long, double[]> target = new HashMap<>();
        target.put(root.id, new double[]{0, 0});
        List<NoteData.MindNode> right = new ArrayList<>(), left = new ArrayList<>();
        int i = 0;
        for (NoteData.MindNode n : model.nodes) {
            if (n.parent == root.id) (i++ % 2 == 0 ? right : left).add(n);
        }
        placeColumn(right, 1, 1, 0, target);
        placeColumn(left, -1, 1, 0, target);

        Map<Long, double[]> start = new HashMap<>();
        for (NoteData.MindNode n : model.nodes) start.put(n.id, new double[]{n.x, n.y});
        Ui.animate(420, Interpolator.SPLINE(0.1, 0.8, 0.25, 1), f -> {
            for (NoteData.MindNode n : model.nodes) {
                double[] s = start.get(n.id), t = target.get(n.id);
                if (s == null || t == null) continue;
                n.x = s[0] + (t[0] - s[0]) * f;
                n.y = s[1] + (t[1] - s[1]) * f;
            }
            draw();
        }).setOnFinished(e -> fit(true));
    }

    private void placeColumn(List<NoteData.MindNode> column, int side, int depth, double centerY,
                             Map<Long, double[]> target) {
        double total = 0;
        for (NoteData.MindNode n : column) total += leaves(n);
        double y = centerY - total * ROW / 2;
        for (NoteData.MindNode n : column) {
            double span = leaves(n) * ROW;
            double cy = y + span / 2;
            target.put(n.id, new double[]{side * depth * COLUMN, cy});
            List<NoteData.MindNode> kids = new ArrayList<>();
            for (NoteData.MindNode k : model.nodes) if (k.parent == n.id) kids.add(k);
            placeColumn(kids, side, depth + 1, cy, target);
            y += span;
        }
    }

    private int leaves(NoteData.MindNode n) {
        if (n.collapsed) return 1;
        int sum = 0;
        for (NoteData.MindNode k : model.nodes) if (k.parent == n.id) sum += leaves(k);
        return Math.max(1, sum);
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
                colorOf.put(n.id, Theme.color(PALETTE[index]));
                i++;
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (NoteData.MindNode n : model.nodes) {
                if (colorOf.containsKey(n.id) || n.parent < 0) continue;
                Color inherited = colorOf.get(n.parent);
                if (inherited == null) continue;
                colorOf.put(n.id, n.color > 0 ? Theme.color(PALETTE[n.color - 1]) : inherited);
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
        double[] b = contentBounds();
        double pad = interactive ? 48 : 10;
        double w = b[2] - b[0], h = b[3] - b[1];
        double target = Math.min((getWidth() - pad * 2) / Math.max(w, 1), (getHeight() - pad * 2) / Math.max(h, 1));
        target = Math.min(target, interactive ? 1.2 : 1);
        double targetTx = getWidth() / 2 - (b[0] + w / 2) * target;
        double targetTy = getHeight() / 2 - (b[1] + h / 2) * target;
        if (!animate) {
            scale = target;
            tx = targetTx;
            ty = targetTy;
            draw();
            return;
        }
        double s0 = scale, x0 = tx, y0 = ty;
        double finalTarget = target;
        Ui.animate(300, Interpolator.SPLINE(0.1, 0.7, 0.2, 1), f -> {
            scale = s0 + (finalTarget - s0) * f;
            tx = x0 + (targetTx - x0) * f;
            ty = y0 + (targetTy - y0) * f;
            draw();
        });
    }

    /** Bounds of the visible ideas at scale 1, origin 0: {left, top, right, bottom}. */
    private double[] contentBounds() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (NoteData.MindNode n : model.nodes) {
            if (hidden.contains(n.id)) continue;
            bounds(n, 1, 0, 0);
            minX = Math.min(minX, rl);
            minY = Math.min(minY, rt);
            maxX = Math.max(maxX, rr);
            maxY = Math.max(maxY, rb);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private void ensureVisible(NoteData.MindNode n) {
        bounds(n, scale, tx, ty);
        double margin = 32;
        if (rr > getWidth() - margin) tx -= rr - getWidth() + margin;
        if (rl < margin) tx += margin - rl;
        if (rb > getHeight() - margin) ty -= rb - getHeight() + margin;
        if (rt < margin) ty += margin - rt;
        draw();
    }

    /** The whole map as a picture, at twice its size on the theme's background. */
    WritableImage renderAll() {
        if (model.nodes.isEmpty()) return null;
        double[] b = contentBounds();
        double pad = 32;
        double w = b[2] - b[0] + pad * 2, h = b[3] - b[1] + pad * 2;
        double s = Math.min(2, 8192 / Math.max(w, h));
        Canvas out = new Canvas(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
        GraphicsContext g = out.getGraphicsContext2D();
        g.setFill(Theme.color("app_background"));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        drawMap(g, s, (pad - b[0]) * s, (pad - b[1]) * s, false);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Theme.color("app_background"));
        return out.snapshot(params, null);
    }

    // ---------------------------------------------------------------- geometry

    private static Font font(boolean root, double size) {
        return root ? Font.font("Roboto", FontWeight.BOLD, size) : Font.font("Roboto", size);
    }

    private double textWidth(String s, Font f) {
        measure.setFont(f);
        measure.setText(s);
        return measure.getLayoutBounds().getWidth();
    }

    private void bounds(NoteData.MindNode n, double s, double ox, double oy) {
        boolean isRoot = n.parent < 0;
        double w = Math.min(textWidth(label(n), font(isRoot, isRoot ? 18 : 15)), MAX_TEXT) + PAD_X * 2;
        double line = isRoot ? ROOT_H : NODE_H;
        double h = line;
        rpw = rph = 0;
        if (n.image != null) {
            javafx.scene.image.Image img = picture(n.image);
            double iw = img == null ? PIC_W : img.getWidth(), ih = img == null ? PIC_H * 0.66 : img.getHeight();
            double f = Math.min(1, Math.min(PIC_W / Math.max(1, iw), PIC_H / Math.max(1, ih)));
            rpw = iw * f;
            rph = ih * f;
            w = Math.max(w, rpw + 16);
            h = line + rph + 8;
        }
        double cx = n.x * s + ox, cy = n.y * s + oy;
        rl = cx - w * s / 2;
        rt = cy - h * s / 2;
        rr = cx + w * s / 2;
        rb = cy + h * s / 2;
        rty = rb - line * s / 2;
        rpw *= s;
        rph *= s;
    }

    private static String label(NoteData.MindNode n) {
        if (!n.text.isEmpty()) return n.text;
        return n.parent < 0 ? "Central idea" : "Idea";
    }

    /** Which side of the centre an idea sits on, so its fold badge points outward. */
    private double side(NoteData.MindNode n) {
        NoteData.MindNode root = root();
        double s = root == null ? 1 : Math.signum(n.x - root.x);
        return s == 0 ? 1 : s;
    }

    private NoteData.MindNode hit(double x, double y) {
        for (int i = model.nodes.size() - 1; i >= 0; i--) {
            NoteData.MindNode n = model.nodes.get(i);
            if (hidden.contains(n.id)) continue;
            bounds(n, scale, tx, ty);
            if (x >= rl && x <= rr && y >= rt && y <= rb) return n;
        }
        return null;
    }

    /** The "+N" fold badge and the link badge are clickable. */
    private boolean handleBadgeTap(double x, double y) {
        double r = 14 * Math.max(scale, 0.7);
        for (NoteData.MindNode n : model.nodes) {
            if (hidden.contains(n.id)) continue;
            bounds(n, scale, tx, ty);
            if (n.collapsed) {
                double bx = side(n) > 0 ? rr + r : rl - r;
                if (Math.hypot(x - bx, y - rty) < r * 1.3) {
                    toggleCollapsed(n);
                    return true;
                }
            }
            if (n.hasLink() && Math.hypot(x - rr, y - rt) < r * 1.3) {
                if (listener != null) listener.onOpenLink(n);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void layoutChildren() {
        if (canvas.getWidth() == getWidth() && canvas.getHeight() == getHeight()) return;
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        if (!interactive || !fitted) {
            fitted = getWidth() > 0;
            fit(false);
        }
        draw();
    }

    @Override
    protected double computePrefWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 0;
    }

    void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (interactive) drawGrid(g);
        drawMap(g, scale, tx, ty, interactive);
    }

    /** A quiet dot grid that moves with the map, so panning feels physical. */
    private void drawGrid(GraphicsContext g) {
        double step = GRID * scale;
        if (step < 14) return; // Too dense to stay calm when zoomed far out.
        g.setFill(Theme.color("divider"));
        double startX = ((tx % step) + step) % step, startY = ((ty % step) + step) % step;
        for (double x = startX; x < getWidth(); x += step) {
            for (double y = startY; y < getHeight(); y += step) g.fillOval(x - 1.1, y - 1.1, 2.2, 2.2);
        }
    }

    private void drawMap(GraphicsContext g, double s, double ox, double oy, boolean decorations) {
        Color surface = Theme.color("app_surface");
        Color ink = Theme.color("text_primary");
        Color muted = Theme.color("text_secondary");
        Color accent = Theme.color("accent_blue");
        g.setLineCap(StrokeLineCap.ROUND);

        // Branches.
        g.setLineDashes((double[]) null);
        g.setLineWidth(Math.max(1.5, 2.5 * s));
        for (NoteData.MindNode n : model.nodes) {
            if (hidden.contains(n.id)) continue;
            NoteData.MindNode p = byId.get(n.parent);
            if (p == null) continue;
            double px = p.x * s + ox, py = p.y * s + oy;
            double nx = n.x * s + ox, ny = n.y * s + oy;
            double mid = (px + nx) / 2;
            Color c = colorOf.getOrDefault(n.id, muted);
            g.setStroke(alpha(c, 190));
            g.beginPath();
            g.moveTo(px, py);
            g.bezierCurveTo(mid, py, mid, ny, nx, ny);
            g.stroke();
        }

        // Cross-links, dashed and arched, so they read as "related" rather than "child of".
        g.setLineWidth(Math.max(1.2, 2 * s));
        g.setLineDashes(8, 7);
        g.setStroke(alpha(accent, 170));
        for (long[] l : model.links) {
            NoteData.MindNode a = byId.get(l[0]), b = byId.get(l[1]);
            if (a == null || b == null || hidden.contains(a.id) || hidden.contains(b.id)) continue;
            double ax = a.x * s + ox, ay = a.y * s + oy;
            double bx = b.x * s + ox, by = b.y * s + oy;
            double mx = (ax + bx) / 2, my = (ay + by) / 2;
            double dx = bx - ax, dy = by - ay;
            g.beginPath();
            g.moveTo(ax, ay);
            g.quadraticCurveTo(mx - dy * 0.25, my + dx * 0.25, bx, by);
            g.stroke();
        }
        g.setLineDashes((double[]) null);

        // Ideas.
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        for (NoteData.MindNode n : model.nodes) {
            if (hidden.contains(n.id)) continue;
            bounds(n, s, ox, oy);
            boolean isRoot = n.parent < 0;
            Color color = colorOf.getOrDefault(n.id, muted);
            double w = rr - rl, h = rb - rt, radius = n.image != null ? Math.min(h / 2, 16 * s) : h / 2;
            if (isRoot) {
                g.setFill(n.color > 0 ? Theme.color(PALETTE[n.color - 1]) : Theme.color("brand_yellow"));
                g.fillRoundRect(rl, rt, w, h, radius * 2, radius * 2);
            } else {
                g.setFill(surface);
                g.fillRoundRect(rl, rt, w, h, radius * 2, radius * 2);
                g.setStroke(color);
                g.setLineWidth(Math.max(1, 2 * s));
                g.strokeRoundRect(rl, rt, w, h, radius * 2, radius * 2);
            }
            if (decorations && (n.id == selectedId || n == connectFrom)) {
                g.setStroke(n == connectFrom ? Theme.color("tile_orange") : accent);
                g.setLineWidth(3);
                double grow = 4;
                g.strokeRoundRect(rl - grow, rt - grow, w + grow * 2, h + grow * 2, (radius + grow) * 2, (radius + grow) * 2);
            }
            Font f = font(isRoot, (isRoot ? 18 : 15) * s);
            g.setFont(f);
            g.setFill(isRoot ? Color.BLACK : n.text.isEmpty() ? muted : ink);
            String shown = ellipsize(label(n), f, MAX_TEXT * s);
            g.fillText(shown, (rl + rr) / 2, rty);
            if (decorations && Links.ctrl.get() && !Links.find(n.text).isEmpty()) {
                double tw = textWidth(shown, f);
                g.setStroke(accent);
                g.setLineWidth(1.5);
                double uy = rty + f.getSize() * 0.55;
                g.strokeLine((rl + rr) / 2 - tw / 2, uy, (rl + rr) / 2 + tw / 2, uy);
            }
            if (n.image != null) {
                javafx.scene.image.Image img = picture(n.image);
                double px = (rl + rr) / 2 - rpw / 2, py = rt + 8 * s;
                if (img != null) {
                    g.save();
                    g.beginPath();
                    g.moveTo(px, py);
                    g.rect(px, py, rpw, rph);
                    g.closePath();
                    g.clip();
                    g.drawImage(img, px, py, rpw, rph);
                    g.restore();
                } else {
                    g.setFill(Theme.color("thumb_placeholder"));
                    g.fillRoundRect(px, py, rpw, rph, 10 * s, 10 * s);
                }
            }
            drawBadges(g, n, s, color);
        }
    }

    private String ellipsize(String text, Font f, double max) {
        if (textWidth(text, f) <= max) return text;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (textWidth(text.substring(0, mid) + "…", f) <= max) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + "…";
    }

    /** "+N" on a folded idea (outward side) and an arrow on one that links to a note. */
    private void drawBadges(GraphicsContext g, NoteData.MindNode n, double s, Color color) {
        double r = 11 * Math.max(s, 0.6);
        double cy = rty;
        if (n.collapsed) {
            Integer count = hiddenCount.get(n.id);
            double bx = side(n) > 0 ? rr + r * 1.2 : rl - r * 1.2;
            g.setFill(color);
            g.fillOval(bx - r, cy - r, r * 2, r * 2);
            drawCentered(g, "+" + (count == null ? 0 : count), bx, cy, r);
        }
        if (n.hasLink()) {
            g.setFill(Theme.color("accent_blue"));
            double br = r * 0.9;
            g.fillOval(rr - br, rt - br, br * 2, br * 2);
            drawCentered(g, "↗", rr, rt, br);
        }
    }

    private static void drawCentered(GraphicsContext g, String s, double cx, double cy, double r) {
        g.setFont(Font.font("Roboto", FontWeight.BOLD, r * 1.05));
        g.setFill(Color.WHITE);
        g.fillText(s, cx, cy);
    }

    private static Color alpha(Color c, int a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), a / 255.0);
    }

    // ------------------------------------------------------------------- mouse

    private void pressed(MouseEvent e) {
        requestFocus();
        if (e.getButton() != MouseButton.PRIMARY) return;
        dragging = hit(e.getX(), e.getY());
        // Ctrl+click on an idea with a link or an IP address: open it, or copy it.
        if (e.isShortcutDown() && dragging != null) {
            List<Links.Match> found = Links.find(dragging.text);
            if (!found.isEmpty()) {
                Links.activate(found.get(0));
                dragging = null;
                moved = true;
                return;
            }
        }
        lastX = downX = e.getX();
        lastY = downY = e.getY();
        moved = false;
    }

    private void dragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) < 4) return;
        moved = true;
        double dx = e.getX() - lastX, dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        if (dragging != null) {
            // A folded idea carries its hidden branch with it.
            moveWithHidden(dragging, dx / scale, dy / scale);
            setCursor(Cursor.MOVE);
        } else {
            tx += dx;
            ty += dy;
            setCursor(Cursor.CLOSED_HAND);
        }
        draw();
    }

    private void released(MouseEvent e) {
        setCursor(Cursor.DEFAULT);
        if (e.getButton() != MouseButton.PRIMARY) return;
        dragging = null;
        if (moved || e.getClickCount() > 1) return;
        if (handleBadgeTap(e.getX(), e.getY())) return;
        NoteData.MindNode hit = hit(e.getX(), e.getY());
        if (connectFrom != null) {
            if (hit != null && hit != connectFrom) connect(connectFrom, hit);
            connectFrom = null;
            draw();
            return;
        }
        select(hit);
    }

    private void clicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2 || moved) return;
        NoteData.MindNode hit = hit(e.getX(), e.getY());
        if (hit != null && listener != null) listener.onRename(hit);
        else fit(true);
    }

    /** The wheel zooms around the pointer; a touchpad's two-finger scroll pans. */
    private void scrolled(ScrollEvent e) {
        if (e.getTouchCount() > 0 && !e.isControlDown()) {
            tx += e.getDeltaX();
            ty += e.getDeltaY();
            draw();
        } else if (e.getDeltaY() != 0) {
            zoomAt(e.getX(), e.getY(), Math.pow(1.0018, e.getDeltaY()));
        }
        e.consume();
    }

    private void zoomed(ZoomEvent e) {
        zoomAt(e.getX(), e.getY(), e.getZoomFactor());
        e.consume();
    }

    void zoomAt(double fx, double fy, double factor) {
        double next = Math.max(0.25, Math.min(3, scale * factor));
        double f = next / scale;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = next;
        draw();
    }

    private void moveWithHidden(NoteData.MindNode node, double dx, double dy) {
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
