package org.flexstudios.notes.plus;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.List;

/** Rounded bars from amplitude samples; the played part is drawn in the accent colour. */
final class WaveformView extends Region {
    private final Canvas canvas = new Canvas();
    private float[] wave = new float[0];
    private double progress;
    private Color playedColor, restColor;

    WaveformView(double height) {
        getChildren().add(canvas);
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
        setMinWidth(0);
        setMouseTransparent(true);
        Theme.dark.addListener((o, a, b) -> draw());
    }

    /** Colours other than the default teal and grey (a voice message in a blue bubble). */
    void setColors(Color played, Color rest) {
        playedColor = played;
        restColor = rest;
        draw();
    }

    void setWave(float[] samples, double played) {
        wave = samples == null ? new float[0] : samples;
        progress = played;
        draw();
    }

    /** While recording: the most recent samples scroll in from the right. */
    void setLive(List<Float> samples) {
        int count = Math.max(1, (int) (getWidth() / 5));
        float[] tail = new float[Math.min(count, samples.size())];
        for (int i = 0; i < tail.length; i++) tail[i] = samples.get(samples.size() - tail.length + i);
        wave = tail;
        progress = 1;
        draw();
    }

    @Override
    protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        return 0;
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth(), height = canvas.getHeight();
        g.clearRect(0, 0, width, height);
        if (wave.length == 0 || width <= 0) return;
        double gap = 2;
        double bar = Math.max(2, (width - gap * (wave.length - 1)) / wave.length);
        double mid = height / 2;
        Color played = playedColor != null ? playedColor : Theme.color("tile_teal");
        Color rest = restColor != null ? restColor : Theme.color("text_secondary");
        for (int i = 0; i < wave.length; i++) {
            double h = Math.max(3, wave[i] * height * 0.9);
            double x = i * (bar + gap);
            boolean done = (i + 0.5) / wave.length <= progress;
            g.setFill(done ? played : Color.color(rest.getRed(), rest.getGreen(), rest.getBlue(), 110 / 255.0));
            g.fillRoundRect(x, mid - h / 2, bar, h, bar, bar);
        }
    }
}
