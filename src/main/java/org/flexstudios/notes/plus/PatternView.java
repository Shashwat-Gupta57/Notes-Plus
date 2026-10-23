package org.flexstudios.notes.plus;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The phone's pattern lock: nine dots, drag through at least four. The pattern is the dots'
 * numbers in order ("0" top-left … "8" bottom-right), and passing over a dot between two
 * picked ones picks it too, exactly as on the phone.
 */
final class PatternView extends Region {
    private static final int GRID = 3;
    private static final Color YELLOW = Color.web("#FFCC00");

    private final Canvas canvas = new Canvas();
    private final List<double[]> dots = new ArrayList<>();
    private final List<Integer> selected = new ArrayList<>();
    private double lastX, lastY;
    private boolean drawing;
    private Consumer<String> listener;

    PatternView(double size) {
        getChildren().add(canvas);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setStyle("-fx-cursor: hand;");
        setOnMousePressed(this::pressed);
        setOnMouseDragged(this::dragged);
        setOnMouseReleased(this::released);
    }

    void setOnPattern(Consumer<String> l) {
        listener = l;
    }

    void reset() {
        selected.clear();
        drawing = false;
        draw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        dots.clear();
        double cw = getWidth() / GRID, ch = getHeight() / GRID;
        for (int i = 0; i < GRID; i++) for (int j = 0; j < GRID; j++) dots.add(new double[]{j * cw + cw / 2, i * ch + ch / 2});
        draw();
    }

    private void pressed(MouseEvent e) {
        selected.clear();
        drawing = true;
        track(e);
    }

    private void dragged(MouseEvent e) {
        if (drawing) track(e);
    }

    private void released(MouseEvent e) {
        drawing = false;
        if (!selected.isEmpty()) {
            double[] last = dots.get(selected.get(selected.size() - 1));
            lastX = last[0];
            lastY = last[1];
        }
        draw();
        if (listener != null && !selected.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int id : selected) sb.append(id);
            listener.accept(sb.toString());
        }
    }

    private void track(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
        double hitRadius = Math.min(getWidth(), getHeight()) / GRID * 0.32;
        for (int id = 0; id < dots.size(); id++) {
            if (selected.contains(id)) continue;
            double[] d = dots.get(id);
            if (Math.hypot(lastX - d[0], lastY - d[1]) < hitRadius) {
                if (!selected.isEmpty()) {
                    Integer between = between(selected.get(selected.size() - 1), id);
                    if (between != null && !selected.contains(between)) selected.add(between);
                }
                selected.add(id);
            }
        }
        draw();
    }

    private static Integer between(int a, int b) {
        int r1 = a / GRID, c1 = a % GRID, r2 = b / GRID, c2 = b % GRID;
        if (Math.abs(r1 - r2) % 2 == 0 && Math.abs(c1 - c2) % 2 == 0
                && (r1 == r2 || c1 == c2 || Math.abs(r1 - r2) == Math.abs(c1 - c2))) {
            return (r1 + r2) / 2 * GRID + (c1 + c2) / 2;
        }
        return null;
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (!selected.isEmpty()) {
            g.setStroke(YELLOW);
            g.setLineWidth(6);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.beginPath();
            double[] first = dots.get(selected.get(0));
            g.moveTo(first[0], first[1]);
            for (int i = 1; i < selected.size(); i++) {
                double[] d = dots.get(selected.get(i));
                g.lineTo(d[0], d[1]);
            }
            if (drawing) g.lineTo(lastX, lastY);
            g.stroke();
        }
        for (int id = 0; id < dots.size(); id++) {
            double[] d = dots.get(id);
            g.setFill(selected.contains(id) ? YELLOW : Color.web("#CCCCCC"));
            g.fillOval(d[0] - 10, d[1] - 10, 20, 20);
        }
    }
}
