package org.flexstudios.notes.plus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

/**
 * The gallery grid's three touch behaviours, each self-contained and attached to the grid's
 * RecyclerView:
 *
 * <ul>
 *   <li>{@link PinchColumns}: pinch to change the column count, with an animated reflow.</li>
 *   <li>{@link DragSelect}: after a long-press, drag across cells to select a range; the
 *       grid scrolls by itself near the top and bottom edges.</li>
 *   <li>{@link FastScroller}: a thumb on the right edge that jumps through years of items,
 *       with a bubble naming the day under it.</li>
 * </ul>
 */
final class GalleryGestures {

    private GalleryGestures() {}

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    // =================================================================== pinch

    static final class PinchColumns extends RecyclerView.SimpleOnItemTouchListener {
        interface Listener {
            void onColumnsChanged(int columns);
        }

        static final int MIN = 2, MAX = 6;
        private static final float STEP = 1.22f;

        private final RecyclerView recycler;
        private final GridLayoutManager layout;
        private final Listener listener;
        private final ScaleGestureDetector detector;
        private float accumulated = 1f;
        private boolean scaling;

        PinchColumns(RecyclerView recycler, GridLayoutManager layout, Listener listener) {
            this.recycler = recycler;
            this.layout = layout;
            this.listener = listener;
            this.detector = new ScaleGestureDetector(recycler.getContext(),
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                            scaling = true;
                            accumulated = 1f;
                            return true;
                        }

                        @Override
                        public boolean onScale(@NonNull ScaleGestureDetector d) {
                            accumulated *= d.getScaleFactor();
                            if (accumulated > STEP) {
                                change(-1); // Spread fingers: bigger cells, fewer columns.
                                accumulated = 1f;
                            } else if (accumulated < 1 / STEP) {
                                change(+1);
                                accumulated = 1f;
                            }
                            return true;
                        }

                        @Override
                        public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                            scaling = false;
                        }
                    });
        }

        private void change(int delta) {
            int next = Math.max(MIN, Math.min(MAX, layout.getSpanCount() + delta));
            if (next == layout.getSpanCount()) return;
            // Keep the item under the user's eyes where it is while the grid reflows.
            int anchor = layout.findFirstVisibleItemPosition();
            View anchorView = layout.findViewByPosition(anchor);
            int offset = anchorView == null ? 0 : anchorView.getTop();

            TransitionManager.beginDelayedTransition(recycler, new AutoTransition().setDuration(220));
            layout.setSpanCount(next);
            listener.onColumnsChanged(next);
            layout.scrollToPositionWithOffset(Math.max(anchor, 0), offset);
            recycler.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            detector.onTouchEvent(e);
            return scaling || e.getPointerCount() > 1;
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            detector.onTouchEvent(e);
        }
    }

    // ============================================================ drag select

    static final class DragSelect extends RecyclerView.SimpleOnItemTouchListener {
        private final RecyclerView recycler;
        private final SecretGalleryAdapter adapter;
        private final float edge, maxSpeed;
        private boolean active;
        private int anchor, last;
        private float lastX, lastY;
        private int scrollSpeed;

        private final Runnable autoScroll = new Runnable() {
            @Override
            public void run() {
                if (!active || scrollSpeed == 0) return;
                recycler.scrollBy(0, scrollSpeed);
                extendTo(lastX, lastY);
                recycler.postOnAnimation(this);
            }
        };

        DragSelect(RecyclerView recycler, SecretGalleryAdapter adapter) {
            this.recycler = recycler;
            this.adapter = adapter;
            this.edge = dp(recycler.getContext(), 72);
            this.maxSpeed = dp(recycler.getContext(), 22);
        }

        /** The long-pressed cell becomes the anchor of the range. */
        void start(int position) {
            active = true;
            anchor = last = position;
            recycler.getParent().requestDisallowInterceptTouchEvent(true);
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            if (!active) return false;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) stop();
            return active;
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    lastX = e.getX();
                    lastY = e.getY();
                    extendTo(lastX, lastY);
                    updateAutoScroll(lastY);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stop();
                    break;
                default:
                    break;
            }
        }

        private void extendTo(float x, float y) {
            View child = recycler.findChildViewUnder(x, y);
            if (child == null) return;
            int position = recycler.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION || position == last) return;
            if (!(adapter.rowAt(position) instanceof SecretItem)) return;
            adapter.dragSelect(anchor, last, position);
            last = position;
            recycler.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }

        private void updateAutoScroll(float y) {
            int height = recycler.getHeight();
            int speed = 0;
            if (y < edge) speed = -(int) (maxSpeed * (1 - Math.max(y, 0) / edge));
            else if (y > height - edge) speed = (int) (maxSpeed * (1 - Math.max(height - y, 0) / edge));
            boolean wasScrolling = scrollSpeed != 0;
            scrollSpeed = speed;
            if (speed != 0 && !wasScrolling) recycler.postOnAnimation(autoScroll);
        }

        private void stop() {
            if (!active) return;
            active = false;
            scrollSpeed = 0;
            recycler.removeCallbacks(autoScroll);
            adapter.endDrag();
        }
    }

    // ============================================================ fast scroller

    /** Draws over the grid; touches outside the thumb fall through to it. */
    @SuppressLint("ViewConstructor")
    static final class FastScroller extends View {
        interface LabelProvider {
            String labelAt(int position);
        }

        private static final long HIDE_DELAY_MS = 1400;

        private final RecyclerView recycler;
        private final LabelProvider labels;
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float thumbWidth, thumbHeight, touchWidth, margin, bubblePad, bubbleHeight;
        private float thumbTop;
        private boolean dragging;
        private String label = "";
        private final Runnable hide = () -> animate().alpha(0f).setDuration(250).start();

        FastScroller(RecyclerView recycler, LabelProvider labels) {
            super(recycler.getContext());
            this.recycler = recycler;
            this.labels = labels;
            Context c = recycler.getContext();
            thumbWidth = dp(c, 6);
            thumbHeight = dp(c, 52);
            touchWidth = dp(c, 40);
            margin = dp(c, 4);
            bubblePad = dp(c, 14);
            bubbleHeight = dp(c, 40);
            thumbPaint.setColor(ContextCompat.getColor(c, R.color.text_secondary));
            bubblePaint.setColor(ContextCompat.getColor(c, R.color.brand_yellow));
            textPaint.setColor(ContextCompat.getColor(c, R.color.black));
            textPaint.setTextSize(dp(c, 15));
            textPaint.setFakeBoldText(true);
            setAlpha(0f);

            recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (dy == 0 && !dragging) return;
                    sync();
                    if (scrollable()) show();
                }
            });
        }

        /** Adds the scroller on top of the grid inside its FrameLayout parent. */
        static FastScroller attach(RecyclerView recycler, LabelProvider labels) {
            FastScroller scroller = new FastScroller(recycler, labels);
            ViewGroup parent = (ViewGroup) recycler.getParent();
            parent.addView(scroller, parent.indexOfChild(recycler) + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return scroller;
        }

        /** Worth showing only when there is a lot to scroll through. */
        private boolean scrollable() {
            return recycler.computeVerticalScrollRange() > recycler.getHeight() * 4;
        }

        private void show() {
            removeCallbacks(hide);
            animate().cancel();
            setAlpha(1f);
            if (!dragging) postDelayed(hide, HIDE_DELAY_MS);
        }

        private void sync() {
            int range = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent();
            float fraction = range <= 0 ? 0 : recycler.computeVerticalScrollOffset() / (float) range;
            thumbTop = recycler.getPaddingTop() + fraction * (usableHeight());
            invalidate();
        }

        private float usableHeight() {
            return getHeight() - recycler.getPaddingTop() - recycler.getPaddingBottom() - thumbHeight;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (getAlpha() == 0f) return;
            float right = getWidth() - margin;
            float width = dragging ? thumbWidth * 1.6f : thumbWidth;
            rect.set(right - width, thumbTop, right, thumbTop + thumbHeight);
            thumbPaint.setAlpha(dragging ? 255 : 170);
            canvas.drawRoundRect(rect, width, width, thumbPaint);

            if (dragging && !label.isEmpty()) {
                float textWidth = textPaint.measureText(label);
                float bubbleRight = right - touchWidth;
                float top = Math.max(thumbTop + thumbHeight / 2 - bubbleHeight / 2, 0);
                rect.set(bubbleRight - textWidth - bubblePad * 2, top, bubbleRight, top + bubbleHeight);
                canvas.drawRoundRect(rect, bubbleHeight / 2, bubbleHeight / 2, bubblePaint);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                canvas.drawText(label, rect.left + bubblePad,
                        rect.centerY() - (fm.ascent + fm.descent) / 2, textPaint);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    boolean onThumb = getAlpha() > 0.5f && e.getX() > getWidth() - touchWidth
                            && e.getY() > thumbTop - thumbHeight && e.getY() < thumbTop + thumbHeight * 2;
                    if (!onThumb || !scrollable()) return false;
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    show();
                    scrollTo(e.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    scrollTo(e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    dragging = false;
                    show();
                    invalidate();
                    return true;
                default:
                    return dragging;
            }
        }

        private void scrollTo(float y) {
            RecyclerView.Adapter<?> adapter = recycler.getAdapter();
            if (adapter == null || adapter.getItemCount() == 0) return;
            float fraction = (y - recycler.getPaddingTop() - thumbHeight / 2) / usableHeight();
            fraction = Math.max(0f, Math.min(1f, fraction));
            int position = Math.round(fraction * (adapter.getItemCount() - 1));
            ((GridLayoutManager) recycler.getLayoutManager()).scrollToPositionWithOffset(position, 0);
            String next = labels.labelAt(position);
            if (!next.equals(label)) {
                label = next;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            thumbTop = recycler.getPaddingTop() + fraction * usableHeight();
            invalidate();
        }
    }
}
