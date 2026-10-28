package org.flexstudios.notes.plus;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.MotionEvent;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words as they look, in a text or journal note's EditText (WordPad-style): bold is bold, a
 * heading is big, a list has its bullets, and no mark is ever on screen. What is kept is
 * Markdown ({@link Markdown}), the same text the computer and the web read.
 *
 * Every line has a paragraph kind (kept in {@link #kinds}, one per line, and redrawn as spans after
 * each edit: bullets, numbers, tick boxes and the quote bar are drawn beside the line, never
 * typed). Characters carry ordinary spans: bold, italic, underline, strike, colour, code.
 * Word's habits: Enter continues a list (and ends it on an empty item), Backspace at the start
 * of a list item or heading makes it plain, typing "# ", "- ", "1. ", "[] " or "&gt; " at a line's
 * start turns it into one, and **bold**, *italic*, ~~strike~~ and `code` become formatted.
 */
final class RichText implements TextWatcher {

    static final int BOLD = 1, ITALIC = 2, UNDERLINE = 3, STRIKE = 4, CODE = 5;

    private final EditText edit;
    private final Runnable onChange;
    private final float dp;
    private final List<Markdown.Block> kinds = new ArrayList<>();
    private boolean applying;

    // What the edit in progress changes, from beforeTextChanged to afterTextChanged.
    private int changeStart, changeLine, removedBreaks;
    private String removed = "", inserted = "";
    private boolean lineWasEmpty;
    @Nullable private Markdown.Block nextKind;

    @SuppressLint("ClickableViewAccessibility")
    RichText(EditText edit, Runnable onChange) {
        this.edit = edit;
        this.onChange = onChange;
        this.dp = edit.getResources().getDisplayMetrics().density;
        edit.addTextChangedListener(this);
        edit.setOnTouchListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_UP || !tapTask(e)) return false;
            MotionEvent cancel = MotionEvent.obtain(e); // Or the press would still turn into a long press.
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            v.onTouchEvent(cancel);
            cancel.recycle();
            return true;
        });
    }

    // ================================================================ content

    /** Shows Markdown {@code text} formatted. */
    void setMarkdown(@Nullable String text) {
        String md = Markdown.repair(text);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        kinds.clear();
        String[] lines = md.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Markdown.Para p = Markdown.parse(lines[i]);
            if (i > 0) sb.append('\n');
            kinds.add(p.block);
            for (Markdown.Run r : p.runs) {
                int s = sb.length();
                sb.append(r.text);
                style(sb, s, sb.length(), r.style);
            }
        }
        applying = true;
        edit.setText(sb);
        applying = false;
        applyBlocks();
    }

    private static void style(android.text.Spannable sp, int a, int b, Markdown.Style st) {
        int f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        if (st.bold) sp.setSpan(new StyleSpan(Typeface.BOLD), a, b, f);
        if (st.italic) sp.setSpan(new StyleSpan(Typeface.ITALIC), a, b, f);
        if (st.underline) sp.setSpan(new UnderlineSpan(), a, b, f);
        if (st.strike) sp.setSpan(new StrikethroughSpan(), a, b, f);
        if (st.code) sp.setSpan(new CodeSpan(), a, b, f);
        if (st.color != null) {
            try {
                sp.setSpan(new ForegroundColorSpan(android.graphics.Color.parseColor(st.color)), a, b, f);
            } catch (IllegalArgumentException ignored) {
                // Not a colour: the default.
            }
        }
    }

    /** The whole text as Markdown. */
    String markdown() {
        Editable e = edit.getText();
        if (e == null) return "";
        normalise(e);
        StringBuilder out = new StringBuilder();
        int start = 0, number = 0;
        for (int i = 0; i < kinds.size(); i++) {
            int end = lineEnd(e, start);
            Markdown.Block kind = kinds.get(i);
            number = kind == Markdown.Block.NUMBER ? number + 1 : 0;
            if (i > 0) out.append('\n');
            out.append(Markdown.write(new Markdown.Para(kind, runs(e, start, end)), Math.max(1, number)));
            start = end + 1;
        }
        return out.toString();
    }

    /** The runs of {@code [a, b)}: a new run wherever a style starts or stops. */
    private static List<Markdown.Run> runs(Spanned e, int a, int b) {
        List<Markdown.Run> out = new ArrayList<>();
        if (b <= a) return out;
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(a);
        cuts.add(b);
        for (CharacterStyle s : e.getSpans(a, b, CharacterStyle.class)) {
            if (!ours(e, s)) continue;
            cuts.add(Math.max(a, Math.min(b, e.getSpanStart(s))));
            cuts.add(Math.max(a, Math.min(b, e.getSpanEnd(s))));
        }
        Integer prev = null;
        for (int cut : cuts) {
            if (prev != null && cut > prev) out.add(new Markdown.Run(e.subSequence(prev, cut).toString(), styleOf(e, prev, cut)));
            prev = cut;
        }
        return out;
    }

    private static boolean ours(Spanned e, Object s) {
        if ((e.getSpanFlags(s) & Spanned.SPAN_COMPOSING) != 0) return false; // The keyboard's underline while typing.
        return s instanceof StyleSpan || s instanceof UnderlineSpan || s instanceof StrikethroughSpan
                || s instanceof ForegroundColorSpan || s instanceof CodeSpan;
    }

    private static Markdown.Style styleOf(Spanned e, int a, int b) {
        boolean bold = false, italic = false, underline = false, strike = false, code = false;
        String color = null;
        for (CharacterStyle s : e.getSpans(a, b, CharacterStyle.class)) {
            if (!ours(e, s) || e.getSpanStart(s) > a || e.getSpanEnd(s) < b || e.getSpanStart(s) == e.getSpanEnd(s)) continue;
            if (s instanceof StyleSpan) {
                int st = ((StyleSpan) s).getStyle();
                if (st == Typeface.BOLD || st == Typeface.BOLD_ITALIC) bold = true;
                if (st == Typeface.ITALIC || st == Typeface.BOLD_ITALIC) italic = true;
            } else if (s instanceof UnderlineSpan) underline = true;
            else if (s instanceof StrikethroughSpan) strike = true;
            else if (s instanceof CodeSpan) code = true;
            else if (s instanceof ForegroundColorSpan) color = String.format("#%06X", ((ForegroundColorSpan) s).getForegroundColor() & 0xFFFFFF);
        }
        return new Markdown.Style(bold, italic, underline, strike, code, color);
    }

    // ================================================================ editing

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (applying) return;
        changeStart = start;
        changeLine = lineIndex(s, start);
        removed = s.subSequence(start, start + count).toString();
        removedBreaks = breaks(removed);
        int ls = lineStart(s, start);
        lineWasEmpty = lineEnd(s, ls) == ls;
        nextKind = changeLine + 1 < kinds.size() ? kinds.get(changeLine + 1) : null;
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (applying) return;
        inserted = s.subSequence(start, start + count).toString();
    }

    @Override
    public void afterTextChanged(Editable e) {
        if (applying) return;
        while (kinds.size() <= changeLine) kinds.add(Markdown.Block.NORMAL);
        Markdown.Block cur = kinds.get(changeLine);
        // Backspace at the start of a list item or heading: the line goes plain first (Word's way).
        if ("\n".equals(removed) && inserted.isEmpty() && nextKind != null && nextKind != Markdown.Block.NORMAL) {
            applying = true;
            e.insert(changeStart, "\n");
            applying = false;
            edit.setSelection(changeStart + 1);
            kinds.set(changeLine + 1, Markdown.Block.NORMAL);
            applyBlocks();
            onChange.run();
            return;
        }
        for (int k = 0; k < removedBreaks && changeLine + 1 < kinds.size(); k++) kinds.remove(changeLine + 1);
        // Enter on an empty item ends the list (and makes no new line).
        if ("\n".equals(inserted) && removed.isEmpty() && lineWasEmpty && (cur.isList() || cur == Markdown.Block.QUOTE)) {
            applying = true;
            e.delete(changeStart, changeStart + 1);
            applying = false;
            kinds.set(changeLine, Markdown.Block.NORMAL);
            applyBlocks();
            onChange.run();
            return;
        }
        int added = breaks(inserted);
        for (int k = 0; k < added; k++) {
            Markdown.Block next = cur == Markdown.Block.H1 || cur == Markdown.Block.H2 || cur == Markdown.Block.H3 ? Markdown.Block.NORMAL
                    : cur == Markdown.Block.TASK_DONE ? Markdown.Block.TASK : cur;
            kinds.add(changeLine + 1, next);
        }
        if (" ".equals(inserted) && cur == Markdown.Block.NORMAL) autoBlock(e);
        else if (inserted.length() == 1 && "*~`".contains(inserted)) autoInline(e);
        applyBlocks();
    }

    private static final Pattern NUMBERED = Pattern.compile("\\d+[.)]");

    /** "# ", "- ", "1. ", "[] ", "&gt; " at the start of a plain line. */
    private void autoBlock(Editable e) {
        int ls = lineStart(e, changeStart);
        String typed = e.subSequence(ls, changeStart).toString();
        Markdown.Block b;
        switch (typed) {
            case "#": b = Markdown.Block.H1; break;
            case "##": b = Markdown.Block.H2; break;
            case "###": b = Markdown.Block.H3; break;
            case "-": case "*": case "•": b = Markdown.Block.BULLET; break;
            case ">": b = Markdown.Block.QUOTE; break;
            case "[]": case "[ ]": b = Markdown.Block.TASK; break;
            case "[x]": b = Markdown.Block.TASK_DONE; break;
            default: b = NUMBERED.matcher(typed).matches() ? Markdown.Block.NUMBER : null;
        }
        if (b == null) return;
        applying = true;
        e.delete(ls, changeStart + 1);
        applying = false;
        kinds.set(changeLine, b);
    }

    private static final Pattern INLINE_MARK = Pattern.compile("(\\*\\*([^*]+)\\*\\*|(?<![*\\w])\\*([^*\\s][^*]*)\\*|~~([^~]+)~~|`([^`]+)`)$");

    /** **bold**, *italic*, ~~strike~~ and `code` typed with their marks. */
    private void autoInline(Editable e) {
        int ls = lineStart(e, changeStart), end = changeStart + 1;
        Matcher m = INLINE_MARK.matcher(e.subSequence(ls, end));
        if (!m.find()) return;
        String inner = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
        int from = ls + m.start();
        applying = true;
        e.replace(from, end, inner);
        applying = false;
        int what = m.group(2) != null ? BOLD : m.group(3) != null ? ITALIC : m.group(4) != null ? STRIKE : CODE;
        e.setSpan(make(what), from, from + inner.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); // What follows is plain.
        edit.setSelection(from + inner.length());
    }

    // ============================================================= formatting

    /** A style on or off: over the selection (on unless all of it has it), or for what's typed next. */
    void toggle(int what) {
        Editable e = edit.getText();
        if (e == null) return;
        int s = Math.min(edit.getSelectionStart(), edit.getSelectionEnd()), t = Math.max(edit.getSelectionStart(), edit.getSelectionEnd());
        if (s < 0) return;
        if (s == t) {
            if (activeAt(e, s, what)) splitAt(e, s, what);
            else e.setSpan(make(what), s, s, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        } else {
            boolean all = true;
            for (int i = s; i < t; i++) if (!has(e, i, what)) { all = false; break; }
            clear(e, s, t, what);
            if (!all) e.setSpan(make(what), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        onChange.run();
    }

    /** A colour ("#RRGGBB", or null for the default) over the selection or for what's typed next. */
    void color(@Nullable String hex) {
        Editable e = edit.getText();
        if (e == null) return;
        int s = Math.min(edit.getSelectionStart(), edit.getSelectionEnd()), t = Math.max(edit.getSelectionStart(), edit.getSelectionEnd());
        if (s < 0) return;
        if (s == t) {
            splitAt(e, s, 0);
            if (hex != null) e.setSpan(new ForegroundColorSpan(android.graphics.Color.parseColor(hex)), s, s, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        } else {
            clear(e, s, t, 0);
            if (hex != null) e.setSpan(new ForegroundColorSpan(android.graphics.Color.parseColor(hex)), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        onChange.run();
    }

    /** The selected lines (or the caret's) become {@code b}, or plain again if they all are already. */
    void block(Markdown.Block b) {
        Editable e = edit.getText();
        if (e == null) return;
        normalise(e);
        int first = lineIndex(e, Math.min(edit.getSelectionStart(), edit.getSelectionEnd()));
        int last = lineIndex(e, Math.max(edit.getSelectionStart(), edit.getSelectionEnd()));
        boolean all = true;
        for (int i = first; i <= last; i++) if (kinds.get(i) != b) all = false;
        for (int i = first; i <= last; i++) kinds.set(i, all ? Markdown.Block.NORMAL : b);
        applyBlocks();
        onChange.run();
    }

    private static Object make(int what) {
        switch (what) {
            case BOLD: return new StyleSpan(Typeface.BOLD);
            case ITALIC: return new StyleSpan(Typeface.ITALIC);
            case UNDERLINE: return new UnderlineSpan();
            case STRIKE: return new StrikethroughSpan();
            default: return new CodeSpan();
        }
    }

    /** Whether span {@code s} is a {@code what} (0: a colour). */
    private static boolean is(Object s, int what) {
        switch (what) {
            case 0: return s instanceof ForegroundColorSpan;
            case BOLD: return s instanceof StyleSpan && (((StyleSpan) s).getStyle() & Typeface.BOLD) != 0;
            case ITALIC: return s instanceof StyleSpan && (((StyleSpan) s).getStyle() & Typeface.ITALIC) != 0;
            case UNDERLINE: return s instanceof UnderlineSpan && !(s instanceof android.text.style.SuggestionSpan);
            case STRIKE: return s instanceof StrikethroughSpan;
            default: return s instanceof CodeSpan;
        }
    }

    private static boolean has(Spanned e, int i, int what) {
        for (Object s : e.getSpans(i, i + 1, CharacterStyle.class)) {
            if (is(s, what) && ours(e, s) && e.getSpanStart(s) <= i && e.getSpanEnd(s) > i) return true;
        }
        return false;
    }

    /** What typing at {@code at} would get: a span ending there inclusively, or covering it. */
    private static boolean activeAt(Spanned e, int at, int what) {
        for (Object s : e.getSpans(at, at, CharacterStyle.class)) {
            if (!is(s, what) || !ours(e, s)) continue;
            int a = e.getSpanStart(s), b = e.getSpanEnd(s), f = e.getSpanFlags(s) & Spanned.SPAN_POINT_MARK_MASK;
            if (a < at && b > at) return true;
            if (b == at && (f == Spanned.SPAN_EXCLUSIVE_INCLUSIVE || f == Spanned.SPAN_INCLUSIVE_INCLUSIVE)) return true;
            if (a == at && b == at) return true;
        }
        return false;
    }

    /** Spans of {@code what} stop at {@code at}: typing there is plain (for that style). */
    private static void splitAt(Editable e, int at, int what) {
        for (Object s : e.getSpans(at, at, CharacterStyle.class)) {
            if (!is(s, what) || !ours(e, s)) continue;
            int a = e.getSpanStart(s), b = e.getSpanEnd(s);
            e.removeSpan(s);
            if (a < at) e.setSpan(copy(s), a, at, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (b > at) e.setSpan(copy(s), at, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Takes {@code what} off {@code [s, t)}, keeping it either side. */
    private static void clear(Editable e, int s, int t, int what) {
        for (Object sp : e.getSpans(s, t, CharacterStyle.class)) {
            if (!is(sp, what) || !ours(e, sp)) continue;
            int a = e.getSpanStart(sp), b = e.getSpanEnd(sp);
            if (b <= s || a >= t) continue;
            e.removeSpan(sp);
            if (a < s) e.setSpan(copy(sp), a, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (b > t) e.setSpan(copy(sp), t, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static Object copy(Object s) {
        if (s instanceof StyleSpan) return new StyleSpan(((StyleSpan) s).getStyle());
        if (s instanceof ForegroundColorSpan) return new ForegroundColorSpan(((ForegroundColorSpan) s).getForegroundColor());
        if (s instanceof UnderlineSpan) return new UnderlineSpan();
        if (s instanceof StrikethroughSpan) return new StrikethroughSpan();
        return new CodeSpan();
    }

    // ================================================================= blocks

    /** As many kinds as lines (an edit the watcher couldn't follow is evened out). */
    private void normalise(Spanned e) {
        int lines = breaks(e) + 1;
        while (kinds.size() < lines) kinds.add(Markdown.Block.NORMAL);
        while (kinds.size() > lines) kinds.remove(kinds.size() - 1);
    }

    /**
     * Keeps formatting where it was put: a style never runs across a line break (Enter at the end
     * of struck words starts a plain line), and only the style being typed into, the one ending at
     * the caret, grows. Everything else stops at its last character.
     */
    private void tidySpans(Editable e) {
        int caret = edit.getSelectionEnd();
        for (CharacterStyle s : e.getSpans(0, e.length(), CharacterStyle.class)) {
            if (!ours(e, s)) continue;
            int a = e.getSpanStart(s), b = e.getSpanEnd(s), f = e.getSpanFlags(s) & Spanned.SPAN_POINT_MARK_MASK;
            if (a == b) {
                if (a != caret) e.removeSpan(s); // A style switched on here, then left unused.
                continue;
            }
            boolean growing = b == caret && (f == Spanned.SPAN_EXCLUSIVE_INCLUSIVE || f == Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            int nl = android.text.TextUtils.indexOf(e, '\n', a);
            if (nl >= 0 && nl < b) {
                e.removeSpan(s);
                for (int from = a; from < b; ) {
                    int end = android.text.TextUtils.indexOf(e, '\n', from);
                    if (end < 0 || end > b) end = b;
                    if (end > from) e.setSpan(copy(s), from, end, growing && end == caret ? Spanned.SPAN_EXCLUSIVE_INCLUSIVE : Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    from = end + 1;
                }
                continue;
            }
            int want = growing ? Spanned.SPAN_EXCLUSIVE_INCLUSIVE : Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            if (f != want) e.setSpan(s, a, b, want);
        }
    }

    /** Lays the paragraph kinds over the text: bullets, numbers, boxes, quote bars, heading sizes. */
    private void applyBlocks() {
        Editable e = edit.getText();
        if (e == null) return;
        normalise(e);
        tidySpans(e);
        for (Object o : e.getSpans(0, e.length(), BlockSpan.class)) e.removeSpan(o);
        for (Object o : e.getSpans(0, e.length(), HeadingSpan.class)) e.removeSpan(o);
        for (Object o : e.getSpans(0, e.length(), DoneSpan.class)) e.removeSpan(o);
        int start = 0, number = 0;
        for (int i = 0; i < kinds.size(); i++) {
            int end = lineEnd(e, start);
            Markdown.Block k = kinds.get(i);
            number = k == Markdown.Block.NUMBER ? number + 1 : 0;
            if (k != Markdown.Block.NORMAL) {
                try {
                    e.setSpan(new BlockSpan(k, number), start, Math.min(end + 1, e.length()), Spanned.SPAN_PARAGRAPH);
                } catch (RuntimeException ignored) {
                    // An empty last line can't hold a paragraph span: it shows plain until typed in.
                }
                if (end > start) {
                    if (k == Markdown.Block.H1 || k == Markdown.Block.H2 || k == Markdown.Block.H3) {
                        e.setSpan(new HeadingSpan(k == Markdown.Block.H1 ? 1.6f : k == Markdown.Block.H2 ? 1.35f : 1.15f), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
                    } else if (k == Markdown.Block.TASK_DONE) {
                        e.setSpan(new DoneSpan(), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
                    } else if (k == Markdown.Block.QUOTE) {
                        e.setSpan(new HeadingSpan(1f, false, true), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                }
            }
            start = end + 1;
        }
    }

    /** A tap on a task's box ticks it. */
    private boolean tapTask(MotionEvent ev) {
        Layout layout = edit.getLayout();
        Editable e = edit.getText();
        if (layout == null || e == null || !edit.isEnabled() || edit.getKeyListener() == null) return false;
        float x = ev.getX() - edit.getTotalPaddingLeft();
        if (x > 30 * dp) return false;
        int line = layout.getLineForVertical((int) (ev.getY() - edit.getTotalPaddingTop() + edit.getScrollY()));
        int index = lineIndex(e, layout.getLineStart(line));
        normalise(e);
        Markdown.Block k = kinds.get(index);
        if (k != Markdown.Block.TASK && k != Markdown.Block.TASK_DONE) return false;
        kinds.set(index, k == Markdown.Block.TASK ? Markdown.Block.TASK_DONE : Markdown.Block.TASK);
        applyBlocks();
        onChange.run();
        return true;
    }

    /** What the caret's line is (for the toolbar). */
    Markdown.Block blockAt() {
        Editable e = edit.getText();
        if (e == null) return Markdown.Block.NORMAL;
        normalise(e);
        return kinds.get(lineIndex(e, Math.max(0, edit.getSelectionStart())));
    }

    // ================================================================= helpers

    private static int breaks(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static int lineIndex(CharSequence s, int at) {
        int n = 0;
        for (int i = 0; i < Math.min(at, s.length()); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static int lineStart(CharSequence s, int at) {
        int i = Math.min(at, s.length());
        while (i > 0 && s.charAt(i - 1) != '\n') i--;
        return i;
    }

    private static int lineEnd(CharSequence s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != '\n') i++;
        return i;
    }

    // ================================================================== spans

    /** Inline code: monospace, on a soft grey. */
    static final class CodeSpan extends MetricAffectingSpan {
        @Override
        public void updateMeasureState(@NonNull TextPaint p) {
            p.setTypeface(Typeface.MONOSPACE);
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setTypeface(Typeface.MONOSPACE);
            p.bgColor = 0x30808080;
        }
    }

    /** A heading's size and weight (or, for a quote, its slant). */
    static final class HeadingSpan extends MetricAffectingSpan {
        private final float scale;
        private final boolean bold, italic;

        HeadingSpan(float scale) {
            this(scale, true, false);
        }

        HeadingSpan(float scale, boolean bold, boolean italic) {
            this.scale = scale;
            this.bold = bold;
            this.italic = italic;
        }

        private void apply(TextPaint p) {
            p.setTextSize(p.getTextSize() * scale);
            Typeface base = p.getTypeface();
            int style = (base == null ? 0 : base.getStyle()) | (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
            p.setTypeface(Typeface.create(base, style));
        }

        @Override
        public void updateMeasureState(@NonNull TextPaint p) {
            apply(p);
        }

        @Override
        public void updateDrawState(TextPaint p) {
            apply(p);
        }
    }

    /** A ticked task's words, faded. */
    static final class DoneSpan extends CharacterStyle {
        @Override
        public void updateDrawState(TextPaint p) {
            p.setAlpha(120);
        }
    }

    /** The bullet, number, tick box or quote bar beside a line, in its leading margin. */
    final class BlockSpan implements LeadingMarginSpan {
        final Markdown.Block kind;
        final int number;

        BlockSpan(Markdown.Block kind, int number) {
            this.kind = kind;
            this.number = number;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            if (kind.isList()) return (int) (28 * dp);
            if (kind == Markdown.Block.QUOTE) return (int) (16 * dp);
            return 0;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            int secondary = ContextCompat.getColor(edit.getContext(), R.color.text_secondary);
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            if (kind == Markdown.Block.QUOTE) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(ContextCompat.getColor(edit.getContext(), R.color.brand_yellow));
                c.drawRect(x + 2 * dp, top, x + 5 * dp, bottom, p);
            } else if (first && (kind == Markdown.Block.BULLET)) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(secondary);
                c.drawCircle(x + 12 * dp, (top + baseline) / 2f + 2 * dp, 3 * dp, p);
            } else if (first && kind == Markdown.Block.NUMBER) {
                p.setColor(secondary);
                boolean bold = p.isFakeBoldText();
                p.setFakeBoldText(true);
                Paint.Align align = p.getTextAlign();
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(number + ".", x + 22 * dp, baseline, p);
                p.setTextAlign(align);
                p.setFakeBoldText(bold);
            } else if (first && (kind == Markdown.Block.TASK || kind == Markdown.Block.TASK_DONE)) {
                float size = 16 * dp, cy = (top + baseline) / 2f + 2 * dp;
                RectF box = new RectF(x + 2 * dp, cy - size / 2, x + 2 * dp + size, cy + size / 2);
                if (kind == Markdown.Block.TASK_DONE) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ContextCompat.getColor(edit.getContext(), R.color.tile_green));
                    c.drawRoundRect(box, 3 * dp, 3 * dp, p);
                    p.setColor(0xFFFFFFFF);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * dp);
                    c.drawLine(box.left + 4 * dp, cy, box.left + 7 * dp, cy + 3 * dp, p);
                    c.drawLine(box.left + 7 * dp, cy + 3 * dp, box.right - 3 * dp, cy - 4 * dp, p);
                } else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * dp);
                    p.setColor(secondary);
                    c.drawRoundRect(box, 3 * dp, 3 * dp, p);
                }
            }
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }
}
