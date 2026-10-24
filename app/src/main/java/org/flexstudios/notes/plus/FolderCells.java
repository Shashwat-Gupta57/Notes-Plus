package org.flexstudios.notes.plus;

import android.content.Context;
import android.graphics.Outline;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * A folder tile's small cards: one filling it, two halves, one tall beside two, or four in a
 * square. Each small card is a real note card laid out at full size and drawn scaled down, so
 * it previews its note exactly as the grid does. Touches go to the tile, never the small cards.
 */
final class FolderCells extends ViewGroup {

    static final float SCALE = 0.55f;
    private final int height, gap;

    FolderCells(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        height = Math.round(200 * d);
        gap = Math.round(6 * d);
    }

    /** Where cell {@code i} of {@code n} goes in {@code w} × height: {x, y, w, h}. */
    private int[] spot(int i, int n, int w) {
        int half = (w - gap) / 2, halfH = (height - gap) / 2;
        switch (n) {
            case 1:
                return new int[]{0, 0, w, height};
            case 2:
                return new int[]{i == 0 ? 0 : half + gap, 0, half, height};
            case 3:
                return i == 0 ? new int[]{0, 0, half, height} : new int[]{half + gap, i == 1 ? 0 : halfH + gap, half, halfH};
            default:
                return new int[]{i % 2 == 0 ? 0 : half + gap, i < 2 ? 0 : halfH + gap, half, halfH};
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), n = Math.min(4, getChildCount());
        for (int i = 0; i < n; i++) {
            int[] s = spot(i, n, w);
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(s[2], MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(s[3], MeasureSpec.EXACTLY));
        }
        for (int i = n; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = Math.min(4, getChildCount());
        for (int i = 0; i < n; i++) {
            int[] s = spot(i, n, r - l);
            getChildAt(i).layout(s[0], s[1], s[0] + s[2], s[1] + s[3]);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    /** One cell: its card laid out at full size, scaled into the cell, rounded off. */
    static final class Cell extends FrameLayout {
        Cell(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            float radius = 10 * d;
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (i == 0) {
                    c.measure(MeasureSpec.makeMeasureSpec(Math.round(w / SCALE), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                } else {
                    c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                }
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.layout(0, 0, c.getMeasuredWidth(), c.getMeasuredHeight());
                if (i == 0) {
                    c.setPivotX(0);
                    c.setPivotY(0);
                    c.setScaleX(SCALE);
                    c.setScaleY(SCALE);
                }
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return true; // The tile takes it.
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }
    }
}
