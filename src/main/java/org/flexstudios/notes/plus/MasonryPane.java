package org.flexstudios.notes.plus;

import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * The phone's StaggeredGridLayoutManager: equal columns, each card dropped into the
 * shortest one, in order. The column count follows the window's width (two at least, like
 * the phone).
 */
final class MasonryPane extends Pane {
    private static final double GAP = 10, SIDE = 12, TOP = 5, BOTTOM = 101, COLUMN = 250;

    private int columns(double width) {
        return Math.max(2, (int) ((width - SIDE * 2 + GAP) / (COLUMN + GAP)));
    }

    @Override
    protected void layoutChildren() {
        place(getWidth(), true);
    }

    @Override
    protected double computePrefHeight(double width) {
        return place(width < 0 ? getWidth() : width, false);
    }

    @Override
    protected double computePrefWidth(double height) {
        return SIDE * 2 + COLUMN * 2 + GAP;
    }

    /** Lays the cards out (or only measures), returning the height they need. */
    private double place(double width, boolean apply) {
        int n = columns(width);
        double col = (width - SIDE * 2 - GAP * (n - 1)) / n;
        double[] heights = new double[n];
        for (Node child : getManagedChildren()) {
            int shortest = 0;
            for (int i = 1; i < n; i++) if (heights[i] < heights[shortest] - 0.5) shortest = i;
            double h = child.prefHeight(col);
            if (apply) child.resizeRelocate(SIDE + shortest * (col + GAP), TOP + heights[shortest], col, h);
            heights[shortest] += h + GAP;
        }
        double max = 0;
        for (double h : heights) max = Math.max(max, h);
        return TOP + max + BOTTOM;
    }
}
