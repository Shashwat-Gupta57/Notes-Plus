package org.flexstudios.notes.plus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class PatternView extends View {
    private static final int GRID_SIZE = 3;
    private Paint dotPaint;
    private Paint linePaint;
    private List<Dot> dots = new ArrayList<>();
    private List<Dot> selectedDots = new ArrayList<>();
    private Path linePath = new Path();
    private float lastTouchX, lastTouchY;
    private OnPatternListener listener;
    private OnTouchInteractionListener touchListener;

    public interface OnTouchInteractionListener {
        void onTouchStarted();
        void onTouchEnded();
    }

    public PatternView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.GRAY);
        dotPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#FFCC00")); 
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(12f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dots.clear();
        float cellWidth = w / (float) GRID_SIZE;
        float cellHeight = h / (float) GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                dots.add(new Dot(j * cellWidth + cellWidth / 2, i * cellHeight + cellHeight / 2, i * GRID_SIZE + j));
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Dot dot : dots) {
            dotPaint.setColor(selectedDots.contains(dot) ? Color.parseColor("#FFCC00") : Color.LTGRAY);
            canvas.drawCircle(dot.x, dot.y, 20f, dotPaint);
        }

        if (selectedDots.size() > 0) {
            linePath.reset();
            linePath.moveTo(selectedDots.get(0).x, selectedDots.get(0).y);
            for (int i = 1; i < selectedDots.size(); i++) {
                linePath.lineTo(selectedDots.get(i).x, selectedDots.get(i).y);
            }
            linePath.lineTo(lastTouchX, lastTouchY);
            canvas.drawPath(linePath, linePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (touchListener != null) touchListener.onTouchStarted();
                resetPattern();
                // fall through
            case MotionEvent.ACTION_MOVE:
                lastTouchX = x;
                lastTouchY = y;
                checkDotHit(x, y);
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                if (touchListener != null) touchListener.onTouchEnded();
                if (listener != null && !selectedDots.isEmpty()) {
                    listener.onPatternComplete(getPatternString());
                }
                lastTouchX = selectedDots.isEmpty() ? 0 : selectedDots.get(selectedDots.size() - 1).x;
                lastTouchY = selectedDots.isEmpty() ? 0 : selectedDots.get(selectedDots.size() - 1).y;
                invalidate();
                break;
        }
        return true;
    }

    private void checkDotHit(float x, float y) {
        for (Dot dot : dots) {
            if (!selectedDots.contains(dot)) {
                float dx = x - dot.x;
                float dy = y - dot.y;
                if (Math.sqrt(dx * dx + dy * dy) < 80) { 
                    if (!selectedDots.isEmpty()) {
                        Dot last = selectedDots.get(selectedDots.size() - 1);
                        Dot between = getDotBetween(last, dot);
                        if (between != null && !selectedDots.contains(between)) {
                            selectedDots.add(between);
                        }
                    }
                    selectedDots.add(dot);
                }
            }
        }
    }

    private Dot getDotBetween(Dot d1, Dot d2) {
        int r1 = d1.id / GRID_SIZE;
        int c1 = d1.id % GRID_SIZE;
        int r2 = d2.id / GRID_SIZE;
        int c2 = d2.id % GRID_SIZE;
        if (Math.abs(r1 - r2) % 2 == 0 && Math.abs(c1 - c2) % 2 == 0) {
            if (r1 == r2 || c1 == c2 || Math.abs(r1 - r2) == Math.abs(c1 - c2)) {
                int midR = (r1 + r2) / 2;
                int midC = (c1 + c2) / 2;
                return dots.get(midR * GRID_SIZE + midC);
            }
        }
        return null;
    }

    public void resetPattern() {
        selectedDots.clear();
        invalidate();
    }

    private String getPatternString() {
        StringBuilder sb = new StringBuilder();
        for (Dot dot : selectedDots) {
            sb.append(dot.id);
        }
        return sb.toString();
    }

    public void setOnPatternListener(OnPatternListener listener) {
        this.listener = listener;
    }

    public void setOnTouchInteractionListener(OnTouchInteractionListener touchListener) {
        this.touchListener = touchListener;
    }

    public interface OnPatternListener {
        void onPatternComplete(String pattern);
    }

    private static class Dot {
        float x, y;
        int id;
        Dot(float x, float y, int id) { this.x = x; this.y = y; this.id = id; }
    }
}