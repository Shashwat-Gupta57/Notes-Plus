package org.flexstudios.notes.plus;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The elements of an Ultimate note (see {@link Ultimate}), shown in {@link NoteEditorActivity} under its
 * title as one page, without frames: words ({@link RichText}), checklists, mind maps
 * ({@link MindMapActivity.MindMapView}, taller or shorter by dragging the bar under them), voice recordings
 * and days. Any element can sit inside another: nested under a list item or words ({@code indent}), or
 * inside a mind map's idea (its {@code blocks}, opened in a sheet that is itself such a page). "/" alone on a
 * line, the bar above the formatting row ({@link #addBar}), a list item's + and an idea's menu put things in;
 * the handle beside an element nests, moves or removes it. The desktop's UltimateBlocks, the web's
 * ed-ultimate.js.
 */
@SuppressLint("ViewConstructor")
final class UltimateBlocks extends LinearLayout {

    interface Host {
        void withFolder(Consumer<File> then);

        /** The note's folder, or null while it has none (a new note). */
        @Nullable
        File folder();

        int noteId();

        boolean readOnly();

        /** Something changed: saved a moment later. */
        void changed();

        /** Links and pictures for a text canvas's words, as in a text note. */
        void setUpText(EditText field, RichText rich);

        /** The caret went into these words: the formatting row works on them now. */
        void textFocused(EditText field, RichText rich);

        void openPicture(File file);

        /** Opens the picker; the pictures (now in the note's folder) go to {@code into}. */
        void pickPictures(Consumer<List<String>> into);

        /** Runs {@code then} once the microphone may be used (asking first if needed). */
        void withMicrophone(Runnable then);
    }

    private static final String[] MOODS = {"😄", "🙂", "😐", "😔", "😢", "😡", "😴", "🤩", "❤️"};
    private static final String[] KINDS = {Ultimate.TEXT, Ultimate.LIST, Ultimate.MINDMAP, Ultimate.AUDIO, Ultimate.DAY};
    private static final String[] LABELS = {"Text", "Checklist", "Mind map", "Audio", "Day"};
    private static final int[] ICONS = {R.drawable.ic_note_text, R.drawable.ic_note_list, R.drawable.ic_note_mindmap,
            R.drawable.ic_note_audio, R.drawable.ic_note_journal};
    private static final int[] ACCENTS = {R.color.tile_orange, R.color.tile_green, R.color.tile_blue, R.color.tile_teal, R.color.tile_purple};

    private final Activity activity;
    private final Host host;
    private final float density;
    private final List<Block> blocks = new ArrayList<>();
    private Block focused;
    /** The note's own page (its first element is always words); otherwise what's inside an idea. */
    private final boolean root;

    UltimateBlocks(Activity activity, Host host) {
        this(activity, host, true);
    }

    UltimateBlocks(Activity activity, Host host, boolean root) {
        super(activity);
        this.activity = activity;
        this.host = host;
        this.root = root;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private int color(int res) {
        return ContextCompat.getColor(getContext(), res);
    }

    private static int index(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
        return -1;
    }

    private static JSONObject put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
            // Only for non-finite numbers, which none of these are.
        }
        return o;
    }

    // ================================================================ the note

    void load(@Nullable String data, @Nullable String content) {
        destroy();
        blocks.clear();
        removeAllViews();
        focused = null;
        List<JSONObject> stored = Ultimate.blocks(data);
        if (stored.isEmpty() || (root && !Ultimate.TEXT.equals(Ultimate.kind(stored.get(0))))) {
            JSONObject first = put(put(new JSONObject(), "id", Ultimate.newId()), "kind", Ultimate.TEXT);
            put(first, "md", stored.isEmpty() && content != null ? content : "");
            stored.add(0, first);
        }
        for (JSONObject b : stored) {
            Block block = make(b);
            blocks.add(block);
            addView(block);
        }
        refreshChrome();
    }

    /** What is kept: every canvas, but words left empty after the first. */
    private List<JSONObject> kept() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject b = valueOf(blocks.get(i));
            if ((i > 0 || !root) && Ultimate.TEXT.equals(Ultimate.kind(b)) && b.optString("md").isEmpty()) continue;
            out.add(b);
        }
        return out;
    }

    /** The page's elements as a JSON array (what's inside an idea), or null when there's nothing. */
    @Nullable
    String array() {
        List<JSONObject> out = kept();
        if (out.isEmpty()) return null;
        JSONArray a = new JSONArray();
        for (JSONObject b : out) a.put(b);
        return a.toString();
    }

    /** The note's data as it stands (for undo). */
    String json() {
        return Ultimate.blocksJson(kept());
    }

    /** Fills the note: its blocks and their words. Whether there's anything to keep. */
    boolean collect(NoteEntity note) {
        List<JSONObject> out = kept();
        boolean canvases = false;
        for (JSONObject b : out) canvases |= !Ultimate.TEXT.equals(Ultimate.kind(b));
        note.setData(Ultimate.blocksJson(out));
        note.setContent(Ultimate.content(out));
        String title = note.getTitle() == null ? "" : note.getTitle();
        return canvases || !note.getContent().trim().isEmpty() || !title.isEmpty();
    }

    void focusStart() {
        if (!blocks.isEmpty()) blocks.get(0).focusIn(false);
    }

    void restyleLinks() {
        for (Block b : blocks) if (b instanceof TextBlock) ((TextBlock) b).rich.restyleLinks();
    }

    /** Leaving or pausing: a recording in progress is finished (and kept), playing stops. */
    void pause() {
        for (Block b : blocks) b.pause();
    }

    void destroy() {
        for (Block b : blocks) b.destroy();
    }

    /** Pictures chosen in the picker, now in the note's folder: into the words in use, or words of their own. */
    void addPictures(List<String> names) {
        if (names.isEmpty()) return;
        TextBlock target = focused instanceof TextBlock && blocks.contains(focused) ? (TextBlock) focused : (TextBlock) add(Ultimate.TEXT, false);
        target.addPictures(names);
        host.changed();
    }

    /** The bar that adds canvases, for just above the formatting row. */
    View addBar() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        for (int i = 0; i <= KINDS.length; i++) {
            boolean picture = i == KINDS.length;
            String label = picture ? "Picture" : LABELS[i];
            TextView chip = new TextView(getContext());
            chip.setText(label);
            chip.setTextSize(14);
            chip.setTextColor(color(R.color.text_primary));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(picture ? R.drawable.ic_photo : ICONS[i], 0, 0, 0);
            chip.setCompoundDrawablePadding(dp(6));
            chip.setCompoundDrawableTintList(ColorStateList.valueOf(color(picture ? R.color.tile_red : ACCENTS[i])));
            chip.setPadding(dp(12), dp(8), dp(14), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(100));
            bg.setColor(ColorUtils.setAlphaComponent(color(R.color.text_primary), 0x10));
            chip.setBackground(bg);
            chip.setContentDescription("Add " + label.toLowerCase(Locale.ROOT));
            String kind = picture ? null : KINDS[i];
            chip.setOnClickListener(v -> {
                if (kind == null) host.pickPictures(this::addPictures);
                else insertKind(kind);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(color(R.color.app_surface));
        scroll.addView(row);
        return scroll;
    }

    // ================================================================ arrange

    private Block make(JSONObject b) {
        switch (Ultimate.kind(b)) {
            case Ultimate.TEXT: return new TextBlock(b);
            case Ultimate.LIST: return new ListBlock(b);
            case Ultimate.MINDMAP: return new MapBlock(b);
            case Ultimate.AUDIO: return new AudioBlock(b);
            case Ultimate.DAY: return new DayBlock(b);
            default: return new UnknownBlock(b);
        }
    }

    private static JSONObject blank(String kind, int indent) {
        JSONObject b = put(put(new JSONObject(), "id", Ultimate.newId()), "kind", kind);
        if (Ultimate.TEXT.equals(kind)) put(b, "md", "");
        if (Ultimate.LIST.equals(kind)) put(b, "items", new JSONArray().put(put(put(new JSONObject(), "text", ""), "done", false)));
        if (Ultimate.MINDMAP.equals(kind)) put(b, "h", Ultimate.MAP_H);
        if (Ultimate.DAY.equals(kind)) put(put(b, "day", Ultimate.dayStart(System.currentTimeMillis())), "mood", "");
        if (indent > 0) put(b, "indent", Math.min(4, indent));
        return b;
    }

    /** As stored: its data, and how far in it sits. */
    private static JSONObject valueOf(Block b) {
        JSONObject v = b.value();
        if (b.indent > 0) put(v, "indent", b.indent);
        else v.remove("indent");
        return v;
    }

    private Block place(int at, JSONObject b) {
        Block block = make(b);
        blocks.add(at, block);
        addView(block, at);
        return block;
    }

    private void drop(Block b) {
        b.destroy();
        blocks.remove(b);
        removeView(b);
        if (focused == b) focused = null;
    }

    private void arrived(Block made, boolean focus) {
        focused = made;
        refreshChrome();
        host.changed();
        if (focus) post(() -> {
            made.requestRectangleOnScreen(new android.graphics.Rect(0, 0, made.getWidth(), Math.min(made.getHeight(), dp(360))));
            made.focusIn(true);
        });
    }

    /** A new element after the one in use (or at the end), as far in; with {@code focus}, it takes the caret. */
    private Block add(String kind, boolean focus) {
        int at = focused != null && blocks.contains(focused) ? blocks.indexOf(focused) + 1 : blocks.size();
        Block made = place(at, blank(kind, focused != null ? focused.indent : 0));
        arrived(made, focus);
        return made;
    }

    /** The bar's choice: after the line being typed in (splitting the words), or after the element in use. */
    private void insertKind(String kind) {
        if (focused instanceof TextBlock && blocks.contains(focused) && ((TextBlock) focused).field.hasFocus()) {
            TextBlock t = (TextBlock) focused;
            splitInsert(t, kind, t.rich.splitAtLine());
        } else {
            add(kind, true);
        }
    }

    /** Words split at a line around a new element: before, the element, then the rest. */
    private void splitInsert(TextBlock t, String kind, String[] parts) {
        int i = blocks.indexOf(t);
        JSONObject b = valueOf(t);
        drop(t);
        int at = i;
        if (!parts[0].isEmpty() || (root && i == 0)) place(at++, put(b, "md", parts[0]));
        Block made = place(at++, blank(kind, t.indent));
        if (!parts[1].isEmpty() || at >= blocks.size()) place(at, put(blank(Ultimate.TEXT, t.indent), "md", parts[1]));
        arrived(made, true);
    }

    /** Something new inside a list item: the list splits after it, and the new element sits under it. */
    private void nestUnderItem(ListBlock l, int i, String kind) {
        List<NoteData.TodoItem> items = new ArrayList<>(l.items);
        int at = blocks.indexOf(l), indent = l.indent;
        JSONObject b = valueOf(l);
        drop(l);
        JSONObject head = Ultimate.block(b.optString("id"), Ultimate.LIST, NoteData.todosJson(new ArrayList<>(items.subList(0, i + 1))));
        if (indent > 0) put(head, "indent", indent);
        place(at, head);
        boolean picture = "picture".equals(kind);
        Block made = place(at + 1, blank(picture ? Ultimate.TEXT : kind, indent + 1));
        if (items.size() > i + 1) {
            JSONObject tail = Ultimate.block(Ultimate.newId(), Ultimate.LIST, NoteData.todosJson(new ArrayList<>(items.subList(i + 1, items.size()))));
            if (indent > 0) put(tail, "indent", indent);
            place(at + 2, tail);
        }
        arrived(made, !picture);
        if (picture) host.pickPictures(names -> ((TextBlock) made).addPictures(names));
    }

    /** Two neighbours of the same kind at the same depth read as one: they become one. */
    private void mergeAt(int i) {
        if (i <= 0 || i >= blocks.size()) return;
        Block a = blocks.get(i - 1), c = blocks.get(i);
        if (a.indent != c.indent || !a.kind.equals(c.kind)) return;
        JSONObject b;
        if (a instanceof ListBlock && c instanceof ListBlock) {
            List<NoteData.TodoItem> all = new ArrayList<>(((ListBlock) a).items);
            all.addAll(((ListBlock) c).items);
            b = Ultimate.block(a.id, Ultimate.LIST, NoteData.todosJson(all));
        } else if (a instanceof TextBlock && c instanceof TextBlock) {
            String x = ((TextBlock) a).rich.markdown().trim(), y = ((TextBlock) c).rich.markdown().trim();
            b = put(put(put(new JSONObject(), "id", a.id), "kind", Ultimate.TEXT), "md", !x.isEmpty() && !y.isEmpty() ? x + "\n" + y : x + y);
        } else {
            return;
        }
        if (a.indent > 0) put(b, "indent", a.indent);
        drop(a);
        drop(c);
        place(i - 1, b);
    }

    private void setIndent(Block b, int indent) {
        b.indent = Math.max(0, Math.min(4, indent));
        b.paintIndent();
        host.changed();
    }

    /** "What goes here?": the kinds (and pictures, when offered). */
    private void kindMenu(boolean picture, Consumer<String> pick) {
        String[] hints = {"Just words", "Things to tick off", "Ideas and how they connect", "Record and play back", "A date and a mood"};
        List<String> labels = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (int i = 0; i < KINDS.length; i++) {
            labels.add(LABELS[i] + "  ·  " + hints[i]);
            kinds.add(KINDS[i]);
        }
        if (picture) {
            labels.add("Picture  ·  From your phone");
            kinds.add("picture");
        }
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Put here")
                .setItems(labels.toArray(new String[0]), (d, which) -> pick.accept(kinds.get(which)))
                .show();
    }

    /** The handle's menu: add below, nest, bring out, move, remove. */
    private void options(Block b) {
        int i = blocks.indexOf(b);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Add below…");
        actions.add(() -> kindMenu(true, kind -> {
            focused = b;
            if ("picture".equals(kind)) host.pickPictures(this::addPictures);
            else add(kind, true);
        }));
        if (b.indent < 4 && i > 0) {
            labels.add("Nest further in  →");
            actions.add(() -> setIndent(b, b.indent + 1));
        }
        if (b.indent > 0) {
            labels.add("←  Bring out");
            actions.add(() -> setIndent(b, b.indent - 1));
        }
        if (i > 0) {
            labels.add("Move up");
            actions.add(() -> move(b, -1));
        }
        if (i < blocks.size() - 1) {
            labels.add("Move down");
            actions.add(() -> move(b, 1));
        }
        labels.add("Remove");
        actions.add(() -> remove(b));
        new MaterialAlertDialogBuilder(getContext())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void move(Block b, int dir) {
        int i = blocks.indexOf(b), j = i + dir;
        if (j < 0 || j >= blocks.size()) return;
        blocks.remove(i);
        blocks.add(j, b);
        removeView(b);
        addView(b, j);
        // Where it left and where it landed, like neighbours merge (two lists meeting become one).
        mergeAt(Math.min(i, j) + (dir > 0 ? 0 : 1));
        if (blocks.contains(b)) {
            mergeAt(blocks.indexOf(b));
            if (blocks.contains(b)) mergeAt(blocks.indexOf(b) + 1);
        }
        refreshChrome();
        host.changed();
        post(() -> b.requestRectangleOnScreen(new android.graphics.Rect(0, 0, b.getWidth(), Math.min(b.getHeight(), dp(360)))));
    }

    private void remove(Block b) {
        Runnable go = () -> {
            int i = blocks.indexOf(b);
            drop(b);
            boolean words = false;
            for (Block x : blocks) words |= x instanceof TextBlock;
            if (!words || (root && !(blocks.get(0) instanceof TextBlock))) place(0, blank(Ultimate.TEXT, 0));
            mergeAt(i);
            refreshChrome();
            host.changed();
        };
        List<JSONObject> one = new ArrayList<>();
        one.add(b.value());
        if (!(b instanceof DayBlock) && Ultimate.content(one).trim().isEmpty()) {
            go.run();
            return;
        }
        int k = index(b.kind);
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Remove this " + (k < 0 ? "part" : LABELS[k].toLowerCase(Locale.ROOT)) + " and what's in it?")
                .setPositiveButton("Remove", (d, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshChrome() {
        for (int i = 0; i < blocks.size(); i++) blocks.get(i).chrome(i == 0, i == blocks.size() - 1);
    }

    // ================================================================= blocks

    /**
     * One element of the page: [how far in][its thread, when nested][its handle][itself]. No frame: it
     * reads as part of the page.
     */
    private abstract class Block extends LinearLayout {
        final String id, kind;
        int indent;
        private final View spacer = new View(getContext());
        private final View thread = new View(getContext());
        final LinearLayout content = new LinearLayout(getContext());

        Block(JSONObject b) {
            super(UltimateBlocks.this.getContext());
            setOrientation(HORIZONTAL);
            id = b.optString("id");
            kind = Ultimate.kind(b);
            indent = Ultimate.indent(b);
            GradientDrawable line = new GradientDrawable();
            line.setCornerRadius(dp(1));
            line.setColor(ColorUtils.setAlphaComponent(color(R.color.tile_indigo), 0x66));
            thread.setBackground(line);
            TextView handle = new TextView(getContext());
            handle.setText("⋮⋮");
            handle.setTextSize(12);
            handle.setLetterSpacing(-0.3f);
            handle.setTextColor(color(R.color.text_secondary));
            handle.setAlpha(0.45f);
            handle.setGravity(Gravity.CENTER_HORIZONTAL);
            handle.setPadding(0, dp(9), 0, dp(9));
            handle.setContentDescription("Nest, move or remove");
            handle.setOnClickListener(v -> options(this));
            if (host.readOnly()) handle.setVisibility(INVISIBLE);
            content.setOrientation(VERTICAL);
            addView(spacer, new LayoutParams(0, 1));
            LayoutParams tl = new LayoutParams(dp(2), -1);
            tl.setMargins(0, dp(6), dp(10), dp(6));
            addView(thread, tl);
            addView(handle, new LayoutParams(dp(20), -2));
            addView(content, new LayoutParams(0, -2, 1));
            setPadding(0, dp(1), dp(16), dp(1));
            paintIndent();
        }

        void paintIndent() {
            spacer.getLayoutParams().width = dp(indent * 22);
            spacer.requestLayout();
            thread.setVisibility(indent > 0 ? VISIBLE : GONE);
        }

        ImageView icon(int res, String description, Runnable action) {
            ImageView v = new ImageView(getContext());
            v.setImageResource(res);
            v.setImageTintList(ColorStateList.valueOf(color(R.color.text_secondary)));
            v.setPadding(dp(9), dp(9), dp(9), dp(9));
            v.setContentDescription(description);
            TypedValue ripple = new TypedValue();
            getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            v.setBackgroundResource(ripple.resourceId);
            v.setOnClickListener(x -> action.run());
            v.setLayoutParams(new LayoutParams(dp(38), dp(38)));
            return v;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) focused = this;
            return false;
        }

        void chrome(boolean first, boolean last) {}

        /** Its own content, in the flow. */
        void canvas(View body) {
            content.addView(body, new LayoutParams(-1, -2));
        }

        abstract JSONObject value();

        void focusIn(boolean isNew) {
            requestFocus();
        }

        void pause() {}

        void destroy() {}
    }

    // ------------------------------------------------------------------ words

    private final class TextBlock extends Block {
        final EditText field = new EditText(getContext());
        final RichText rich;

        TextBlock(JSONObject b) {
            super(b);
            field.setBackground(null);
            field.setGravity(Gravity.TOP);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setLineSpacing(0f, 1.25f);
            field.setPadding(0, dp(6), 0, dp(6));
            field.setTextColor(color(R.color.text_body));
            field.setHintTextColor(color(R.color.text_secondary));
            field.setTextSize(18);
            rich = new RichText(field, host::changed);
            host.setUpText(field, rich);
            rich.setMarkdown(b.optString("md", ""));
            if (host.readOnly()) {
                field.setFocusable(false);
                field.setLongClickable(true);
            }
            field.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    focused = this;
                    host.textFocused(field, rich);
                }
            });
            // "/" alone on a line: what goes there, splitting the words at that line.
            field.addTextChangedListener(new TextWatcher() {
                private boolean busy;

                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

                @Override
                public void afterTextChanged(Editable s) {
                    int c = field.getSelectionStart();
                    if (busy || host.readOnly() || c <= 0 || c > s.length() || s.charAt(c - 1) != '/') return;
                    int from = c - 1, to = c;
                    while (from > 0 && s.charAt(from - 1) != '\n') from--;
                    while (to < s.length() && s.charAt(to) != '\n') to++;
                    if (!s.subSequence(from, to).toString().trim().equals("/")) return;
                    busy = true;
                    s.delete(c - 1, c);
                    busy = false;
                    post(() -> {
                        String[] parts = rich.splitAtLine();
                        kindMenu(true, kind -> {
                            if ("picture".equals(kind)) host.pickPictures(TextBlock.this::addPictures);
                            else splitInsert(TextBlock.this, kind, parts);
                        });
                    });
                }
            });
            canvas(field);
        }

        @Override
        void chrome(boolean first, boolean last) {
            field.setHint(first && root ? "Start writing. Type / for a checklist, a mind map, voice…" : "");
            field.setMinHeight(first && last ? dp(200) : 0);
        }

        void addPictures(List<String> names) {
            StringBuilder md = new StringBuilder(rich.markdown());
            for (String n : names) {
                if (md.length() > 0 && md.charAt(md.length() - 1) != '\n') md.append('\n');
                md.append(new PictureLine(n, 0, PictureLine.LEFT).format()).append('\n');
            }
            rich.setMarkdown(md.toString());
            field.requestFocus();
            field.setSelection(field.length());
        }

        @Override
        JSONObject value() {
            return put(put(put(new JSONObject(), "id", id), "kind", Ultimate.TEXT), "md", rich.markdown().trim());
        }

        @Override
        void focusIn(boolean isNew) {
            if (host.readOnly()) return;
            field.requestFocus();
            if (isNew) {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, 0);
            }
        }
    }

    // -------------------------------------------------------------- checklist

    private final class ListBlock extends Block {
        final List<NoteData.TodoItem> items = new ArrayList<>();
        private final LinearLayout rows = new LinearLayout(getContext());
        private final TextView progress = new TextView(getContext());
        /** "Add item": shown while an item is being typed in (it takes no room otherwise). */
        private final LinearLayout add = new LinearLayout(getContext());

        ListBlock(JSONObject b) {
            super(b);
            items.addAll(NoteData.todos(b.toString()));
            if (items.isEmpty()) items.add(new NoteData.TodoItem("", false));
            rows.setOrientation(VERTICAL);
            for (NoteData.TodoItem item : items) rows.addView(row(item));
            progress.setTextSize(12);
            progress.setTextColor(color(R.color.text_secondary));
            add.setGravity(Gravity.CENTER_VERTICAL);
            add.setVisibility(GONE);
            ImageView plus = new ImageView(getContext());
            plus.setImageResource(R.drawable.ic_add);
            plus.setImageTintList(ColorStateList.valueOf(color(R.color.tile_green)));
            TextView words = new TextView(getContext());
            words.setText("Add item");
            words.setTextSize(15);
            words.setTextColor(color(R.color.text_secondary));
            add.addView(plus, new LayoutParams(dp(22), dp(22)));
            LayoutParams wl = new LayoutParams(-2, -2);
            wl.setMarginStart(dp(14));
            add.addView(words, wl);
            add.setPadding(dp(8), 0, 0, 0);
            add.setOnClickListener(v -> addAt(items.size()));
            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(VERTICAL);
            body.addView(rows);
            if (!host.readOnly()) body.addView(add, new LayoutParams(-1, dp(40)));
            showProgress();
            canvas(body);
        }

        private View row(NoteData.TodoItem item) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.item_list_entry, rows, false);
            v.findViewById(R.id.itemHandle).setVisibility(GONE);
            v.findViewById(R.id.itemDue).setVisibility(GONE);
            v.findViewById(R.id.itemPicture).setVisibility(GONE);
            CheckBox check = v.findViewById(R.id.itemCheck);
            EditText text = v.findViewById(R.id.itemText);
            ImageView remove = v.findViewById(R.id.itemClock);
            remove.setImageResource(R.drawable.ic_close);
            remove.setContentDescription("Delete item");
            remove.setVisibility(host.readOnly() ? GONE : VISIBLE);
            remove.setOnClickListener(x -> {
                removeAt(items.indexOf(item));
                if (items.isEmpty()) addAt(0);
            });
            if (!host.readOnly()) {
                // Something inside this item (another list, a map, voice…): it sits under it.
                ImageView inside = icon(R.drawable.ic_add, "Put something inside this item",
                        () -> kindMenu(true, kind -> nestUnderItem(this, items.indexOf(item), kind)));
                inside.setImageTintList(ColorStateList.valueOf(color(R.color.tile_indigo)));
                inside.setAlpha(0.7f);
                ViewGroup line = (ViewGroup) remove.getParent();
                line.addView(inside, line.indexOfChild(remove), new LayoutParams(dp(36), dp(36)));
            }
            text.setOnFocusChangeListener((x, has) -> {
                if (has) add.setVisibility(VISIBLE);
                else post(() -> {
                    boolean any = false;
                    for (int r = 0; r < rows.getChildCount(); r++) any |= ((View) rows.getChildAt(r).getTag()).hasFocus();
                    add.setVisibility(any ? VISIBLE : GONE);
                });
            });
            text.setText(item.text);
            check.setChecked(item.done);
            style(text, item.done);
            if (host.readOnly()) {
                check.setEnabled(false);
                text.setFocusable(false);
            }
            check.setOnCheckedChangeListener((c, on) -> {
                item.done = on;
                style(text, on);
                showProgress();
                host.changed();
            });
            text.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

                @Override
                public void afterTextChanged(Editable s) {
                    int nl = s.toString().indexOf('\n');
                    if (nl >= 0) {
                        // Enter: the words after the caret start the next item.
                        String after = s.subSequence(nl + 1, s.length()).toString().replace("\n", " ");
                        s.delete(nl, s.length());
                        int i = items.indexOf(item);
                        addAt(i + 1);
                        if (!after.isEmpty()) items.get(i + 1).text = after;
                        return;
                    }
                    item.text = s.toString();
                    showProgress();
                    host.changed();
                }
            });
            text.setOnKeyListener((x, code, e) -> {
                if (code != KeyEvent.KEYCODE_DEL || e.getAction() != KeyEvent.ACTION_DOWN || text.length() > 0 || items.size() < 2) return false;
                int i = items.indexOf(item);
                removeAt(i);
                focusItem(Math.max(0, i - 1));
                return true;
            });
            v.setTag(text);
            return v;
        }

        private void style(TextView text, boolean done) {
            text.setPaintFlags(done ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            text.setAlpha(done ? 0.5f : 1f);
        }

        private void addAt(int i) {
            NoteData.TodoItem item = new NoteData.TodoItem("", false);
            items.add(i, item);
            rows.addView(row(item), i);
            showProgress();
            focusItem(i);
            host.changed();
        }

        private void removeAt(int i) {
            if (i < 0 || i >= items.size()) return;
            items.remove(i);
            rows.removeViewAt(i);
            showProgress();
            host.changed();
        }

        private void focusItem(int i) {
            if (i < 0 || i >= rows.getChildCount()) return;
            EditText t = (EditText) rows.getChildAt(i).getTag();
            t.post(() -> {
                t.requestFocus();
                t.setSelection(t.length());
            });
        }

        private void showProgress() {
            int all = 0, done = 0;
            for (NoteData.TodoItem x : items) {
                if (x.text.trim().isEmpty()) continue;
                all++;
                if (x.done) done++;
            }
            progress.setText(all == 0 ? "" : done == all ? "All done" : done + " of " + all + " done");
            progress.setVisibility(all == 0 ? GONE : VISIBLE);
        }

        @Override
        JSONObject value() {
            List<NoteData.TodoItem> kept = new ArrayList<>();
            for (NoteData.TodoItem x : items) if (!x.text.trim().isEmpty()) kept.add(x);
            return Ultimate.block(id, Ultimate.LIST, NoteData.todosJson(kept));
        }

        @Override
        void focusIn(boolean isNew) {
            focusItem(0);
        }
    }

    // --------------------------------------------------------------- mind map

    private final class MapBlock extends Block {
        private final MindMapActivity.MindMapView map = new MindMapActivity.MindMapView(getContext(), null);
        private final FrameLayout stage = new FrameLayout(getContext());
        private int height;

        @SuppressLint("ClickableViewAccessibility")
        MapBlock(JSONObject b) {
            super(b);
            height = Ultimate.mapHeight(b);
            NoteData.MindMap loaded = NoteData.mindMap(b.toString());
            if (loaded.nodes.isEmpty()) loaded.nodes.add(new NoteData.MindNode(1, -1, "", 0, 0));
            map.setMap(loaded);
            map.locked = host.readOnly();
            map.setFolder(host.folder());
            map.insideLabel = n -> Ultimate.insideSummary(Ultimate.inside(n));
            map.listener = new MindMapActivity.MindMapView.Listener() {
                @Override public void onRename(NoteData.MindNode node) { name(node, false); }
                @Override public void onOptions(NoteData.MindNode node) { options(node); }
                @Override public void onOpenLink(NoteData.MindNode node) {}
                @Override public void onConnected() { host.changed(); }
                @Override public void onSelected(@Nullable NoteData.MindNode node) {}
                @Override public void onOpenInside(NoteData.MindNode node) { openInside(MapBlock.this, node, null); }

                @Override
                public void onViewPicture(NoteData.MindNode node) {
                    File dir = host.folder();
                    if (dir != null && node.image != null) host.openPicture(new File(dir, node.image));
                }
            };
            // The map takes its own touches (the page doesn't scroll from it); a drag moved something.
            map.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !host.readOnly()) host.changed();
                return false;
            });
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(0); // Part of the page: no ground of its own.
            stage.setBackground(bg);
            stage.setClipToOutline(true);
            stage.addView(map, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout tools = new LinearLayout(getContext());
            tools.addView(fab(R.drawable.ic_center, "Show everything", () -> map.fit(true)));
            if (!host.readOnly()) {
                tools.addView(fab(R.drawable.ic_note_mindmap, "Tidy up the layout", () -> {
                    map.tidy();
                    host.changed();
                }));
                tools.addView(fab(R.drawable.ic_add, "Add idea", () -> {
                    NoteData.MindNode s = map.selected();
                    addChild(s != null ? s : map.root());
                }));
            }
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            tl.setMargins(0, 0, dp(8), dp(8));
            stage.addView(tools, tl);

            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(VERTICAL);
            body.addView(stage, new LayoutParams(-1, dp(height)));
            if (!host.readOnly()) body.addView(grip(), new LayoutParams(-1, dp(22)));
            canvas(body);
            // The first layout gives it a size: then everything is shown.
            map.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                    if (r - l <= 0) return;
                    map.removeOnLayoutChangeListener(this);
                    map.post(() -> map.fit(false));
                }
            });
        }

        private View fab(int res, String description, Runnable action) {
            ImageView v = new ImageView(getContext());
            v.setImageResource(res);
            v.setImageTintList(ColorStateList.valueOf(color(R.color.text_primary)));
            v.setPadding(dp(9), dp(9), dp(9), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color(R.color.app_surface));
            bg.setStroke(dp(1), color(R.color.divider));
            v.setBackground(bg);
            v.setElevation(dp(2));
            v.setContentDescription(description);
            v.setOnClickListener(x -> action.run());
            LayoutParams lp = new LayoutParams(dp(40), dp(40));
            lp.setMarginStart(dp(8));
            v.setLayoutParams(lp);
            return v;
        }

        /** The bar under the map: dragged up or down, the map gets shorter or taller. */
        @SuppressLint("ClickableViewAccessibility")
        private View grip() {
            FrameLayout grip = new FrameLayout(getContext());
            View bar = new View(getContext());
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(3));
            g.setColor(color(R.color.divider));
            bar.setBackground(g);
            grip.addView(bar, new FrameLayout.LayoutParams(dp(48), dp(5), Gravity.CENTER));
            grip.setContentDescription("Drag to make the map taller or shorter");
            float[] start = new float[2];
            grip.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        start[0] = e.getRawY();
                        start[1] = height;
                        bar.setAlpha(0.6f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        height = Math.round(Math.max(Ultimate.MAP_MIN_H, Math.min(Ultimate.MAP_MAX_H, start[1] + (e.getRawY() - start[0]) / density)));
                        ViewGroup.LayoutParams lp = stage.getLayoutParams();
                        lp.height = dp(height);
                        stage.setLayoutParams(lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        bar.setAlpha(1f);
                        if (height != start[1]) host.changed();
                        return true;
                    default:
                        return true;
                }
            });
            return grip;
        }

        private void addChild(NoteData.MindNode parent) {
            if (host.readOnly()) return;
            if (parent.collapsed) map.toggleCollapsed(parent);
            name(map.addChild(parent), true);
            host.changed();
        }

        private void name(NoteData.MindNode node, boolean isNew) {
            boolean root = node.parent < 0;
            Runnable discard = () -> {
                if (isNew && !root && node.text.isEmpty()) map.removeBranch(node);
            };
            Consumer<String> apply = text -> {
                node.text = text;
                map.select(node);
                map.refresh();
                host.changed();
            };
            Prompts.text(activity, root ? "Central idea" : isNew ? "New idea" : "Rename", null,
                    root ? "What is this map about?" : "An idea", node.text, Prompts.TEXT, "Done", apply, discard,
                    isNew && !root ? "Next idea" : null, text -> {
                        apply.accept(text);
                        NoteData.MindNode parent = map.find(node.parent);
                        if (parent != null) name(map.addChild(parent), true);
                    });
        }

        private void options(NoteData.MindNode node) {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add("Rename");
            actions.add(() -> name(node, false));
            labels.add("Add idea");
            actions.add(() -> addChild(node));
            labels.add(node.sub.isEmpty() ? "Add sub-text" : "Edit sub-text");
            actions.add(() -> Prompts.text(activity, "Sub-text", null, "More about this idea", node.sub, Prompts.TEXT, "Done", text -> {
                node.sub = text.trim();
                map.refresh();
                host.changed();
            }, null));
            if (map.hasChildren(node)) {
                labels.add(node.collapsed ? "Unfold branch" : "Fold branch");
                actions.add(() -> {
                    map.toggleCollapsed(node);
                    host.changed();
                });
            }
            if (node.blocks != null) {
                labels.add("Open what's inside");
                actions.add(() -> openInside(this, node, null));
            }
            labels.add("Put inside this idea…");
            actions.add(() -> kindMenu(false, kind -> openInside(this, node, kind)));
            labels.add("Add another central idea");
            actions.add(() -> {
                NoteData.MindNode r = map.addRoot();
                if (r != null) name(r, true);
                host.changed();
            });
            if (map.canRemove(node)) {
                labels.add(node.parent < 0 ? "Delete this central idea and its ideas" : "Delete branch");
                actions.add(() -> {
                    map.removeBranch(node);
                    host.changed();
                });
            }
            new MaterialAlertDialogBuilder(getContext())
                    .setTitle(node.text.isEmpty() ? "Idea" : node.text)
                    .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                    .show();
        }

        @Override
        JSONObject value() {
            return put(Ultimate.block(id, Ultimate.MINDMAP, NoteData.mindMapJson(map.model)), "h", height);
        }

        @Override
        void focusIn(boolean isNew) {
            if (isNew && !host.readOnly()) name(map.root(), true);
        }
    }

    // ------------------------------------------------------------------ audio

    private final class AudioBlock extends Block {
        private final List<NoteData.Recording> recordings = new ArrayList<>();
        private final LinearLayout list = new LinearLayout(getContext());
        private final TextView empty = new TextView(getContext());
        private final ImageView record = new ImageView(getContext());
        private final TextView timer = new TextView(getContext());
        private final AudioNoteActivity.WaveformView live = new AudioNoteActivity.WaveformView(getContext(), null);
        private final List<Float> amplitudes = new ArrayList<>();
        private final Handler ticker = new Handler(Looper.getMainLooper());
        private final List<Runnable> painters = new ArrayList<>();
        private MediaRecorder recorder;
        private File file;
        private long started;
        private AudioPlayback player;
        private NoteData.Recording playing;

        AudioBlock(JSONObject b) {
            super(b);
            recordings.addAll(NoteData.recordings(b.toString()));
            list.setOrientation(VERTICAL);
            empty.setText("Tap the microphone to record");
            empty.setVisibility(GONE);
            empty.setTextColor(color(R.color.text_secondary));
            empty.setTextSize(14);
            record.setImageResource(R.drawable.ic_mic);
            record.setImageTintList(ColorStateList.valueOf(color(R.color.tile_red)));
            record.setPadding(dp(9), dp(9), dp(9), dp(9));
            GradientDrawable rb = new GradientDrawable();
            rb.setShape(GradientDrawable.OVAL);
            rb.setColor(ColorUtils.setAlphaComponent(color(R.color.tile_red), 0x26));
            record.setBackground(rb);
            record.setContentDescription("Record");
            record.setOnClickListener(v -> {
                if (recorder != null) stop();
                else host.withMicrophone(this::start);
            });
            timer.setTextSize(15);
            timer.setTypeface(Typeface.DEFAULT_BOLD);
            timer.setTextColor(color(R.color.text_primary));
            LinearLayout recRow = new LinearLayout(getContext());
            recRow.setGravity(Gravity.CENTER_VERTICAL);
            recRow.addView(record, new LayoutParams(dp(40), dp(40)));
            LayoutParams tl = new LayoutParams(-2, -2);
            tl.setMarginStart(dp(12));
            recRow.addView(timer, tl);
            LayoutParams ll = new LayoutParams(0, dp(34), 1);
            ll.setMarginStart(dp(12));
            ll.setMarginEnd(dp(8));
            recRow.addView(live, ll);
            timer.setVisibility(GONE);
            live.setVisibility(GONE);
            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(VERTICAL);
            body.addView(empty);
            body.addView(list);
            if (!host.readOnly()) {
                LayoutParams rl = new LayoutParams(-1, -2);
                rl.topMargin = dp(10);
                body.addView(recRow, rl);
            }
            rebuild();
            canvas(body);
        }

        private void start() {
            stopPlayback();
            host.withFolder(dir -> {
                file = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
                MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(getContext()) : new MediaRecorder();
                try {
                    r.setAudioSource(MediaRecorder.AudioSource.MIC);
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    r.setAudioEncodingBitRate(128_000);
                    r.setAudioSamplingRate(44_100);
                    r.setOutputFile(file.getAbsolutePath());
                    r.prepare();
                    r.start();
                } catch (Exception e) {
                    Log.w("Ultimate", "Could not start recording", e);
                    r.release();
                    Toast.makeText(getContext(), "The microphone isn't available", Toast.LENGTH_SHORT).show();
                    return;
                }
                recorder = r;
                started = SystemClock.elapsedRealtime();
                amplitudes.clear();
                record.setImageResource(R.drawable.ic_stop);
                timer.setVisibility(VISIBLE);
                live.setVisibility(VISIBLE);
                timer.setText("0:00");
                activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                rebuild();
                ticker.post(sampler);
            });
        }

        private final Runnable sampler = new Runnable() {
            @Override
            public void run() {
                if (recorder == null) return;
                amplitudes.add(Math.min(1f, (float) Math.sqrt(recorder.getMaxAmplitude() / 32767f)));
                live.setLive(amplitudes);
                timer.setText(SecretGalleryAdapter.formatDuration(SystemClock.elapsedRealtime() - started));
                ticker.postDelayed(this, 60);
            }
        };

        private void stop() {
            if (recorder == null) return;
            ticker.removeCallbacks(sampler);
            long duration = SystemClock.elapsedRealtime() - started;
            try {
                recorder.stop();
            } catch (RuntimeException tooShort) {
                duration = 0; // Stopped within a fraction of a second: no audio.
            }
            recorder.release();
            recorder = null;
            activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            record.setImageResource(R.drawable.ic_mic);
            timer.setVisibility(GONE);
            live.setVisibility(GONE);
            if (duration < 500) {
                if (file != null && !file.delete()) Log.w("Ultimate", "Could not discard an empty take");
                rebuild();
                return;
            }
            float[] wave = new float[48];
            for (int i = 0; i < wave.length && !amplitudes.isEmpty(); i++) {
                int from = i * amplitudes.size() / wave.length, to = Math.max(from + 1, (i + 1) * amplitudes.size() / wave.length);
                for (int k = from; k < to && k < amplitudes.size(); k++) wave[i] = Math.max(wave[i], amplitudes.get(k));
            }
            recordings.add(0, new NoteData.Recording(file.getName(), "Recording " + (recordings.size() + 1), duration,
                    System.currentTimeMillis(), wave));
            rebuild();
            host.changed();
        }

        private void rebuild() {
            painters.clear();
            list.removeAllViews();
            for (NoteData.Recording r : recordings) list.addView(row(r));
            empty.setVisibility(GONE);
        }

        private View row(NoteData.Recording r) {
            ImageView play = new ImageView(getContext());
            play.setImageResource(R.drawable.ic_play_big);
            play.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            play.setPadding(dp(9), dp(9), dp(9), dp(9));
            GradientDrawable pb = new GradientDrawable();
            pb.setCornerRadius(dp(10));
            pb.setColor(color(R.color.tile_teal));
            play.setBackground(pb);
            play.setContentDescription("Play");
            play.setOnClickListener(v -> toggle(r));
            TextView label = new TextView(getContext());
            label.setText(r.label.isEmpty() ? "Recording" : r.label);
            label.setTextSize(15);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(color(R.color.text_primary));
            label.setOnClickListener(v -> {
                if (host.readOnly()) return;
                Prompts.text(activity, "Name this recording", null, "Name", r.label, Prompts.TEXT, "Save", text -> {
                    r.label = text.trim();
                    rebuild();
                    host.changed();
                }, null);
            });
            AudioNoteActivity.WaveformView wave = new AudioNoteActivity.WaveformView(getContext(), null);
            TextView meta = new TextView(getContext());
            meta.setText(SecretGalleryAdapter.formatDuration(r.durationMs) + "  ·  "
                    + new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(r.createdAt)));
            meta.setTextSize(12);
            meta.setTextColor(color(R.color.text_secondary));
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(VERTICAL);
            texts.addView(label);
            texts.addView(wave, new LayoutParams(-1, dp(24)));
            texts.addView(meta);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.addView(play, new LayoutParams(dp(44), dp(44)));
            LayoutParams xl = new LayoutParams(0, -2, 1);
            xl.setMarginStart(dp(12));
            row.addView(texts, xl);
            if (!host.readOnly()) row.addView(icon(R.drawable.ic_close, "Delete recording", () ->
                    new MaterialAlertDialogBuilder(getContext())
                            .setTitle("Delete \"" + label.getText() + "\"?")
                            .setPositiveButton("Delete", (d, w) -> {
                                if (playing == r) stopPlayback();
                                recordings.remove(r);
                                rebuild();
                                host.changed();
                            })
                            .setNegativeButton("Cancel", null)
                            .show()));
            Runnable paint = () -> {
                boolean on = playing == r && player != null;
                float played = on && player.getDuration() > 0 ? player.getCurrentPosition() / (float) player.getDuration() : 0;
                wave.setWave(r.wave, played);
                play.setImageResource(on && player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_big);
            };
            painters.add(paint);
            paint.run();
            return row;
        }

        private final Runnable progress = new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                for (Runnable p : painters) p.run();
                ticker.postDelayed(this, 50);
            }
        };

        private void toggle(NoteData.Recording r) {
            if (playing == r && player != null) {
                if (player.isPlaying()) player.pause();
                else player.start();
                for (Runnable p : painters) p.run();
                return;
            }
            stopPlayback();
            File dir = host.folder();
            File f = dir == null ? null : new File(dir, r.file);
            if (f == null || !f.exists() || f.length() == 0) {
                fetchThenPlay(r);
                return;
            }
            try {
                player = AudioPlayback.open(f); // WAV from the desktop plays through AudioTrack.
            } catch (Exception e) {
                Toast.makeText(getContext(), "Couldn't play that recording", Toast.LENGTH_SHORT).show();
                return;
            }
            player.setOnCompletionListener(this::stopPlayback);
            player.start();
            playing = r;
            ticker.post(progress);
        }

        /** A recording from another device that hasn't come down yet: fetch it now, then play it. */
        private void fetchThenPlay(NoteData.Recording r) {
            int id = host.noteId();
            if (id <= 0) return;
            Toast.makeText(getContext(), "Getting this recording…", Toast.LENGTH_SHORT).show();
            AppDatabase.IO.execute(() -> {
                String problem = null;
                try {
                    if (!NoteSync.fetch(getContext().getApplicationContext(), id, r.file)) {
                        problem = Account.driveEmail(getContext()) == null
                                ? "Connect Google Drive in your account to get recordings from your other devices"
                                : "This recording hasn't been uploaded from its device yet";
                    }
                } catch (Exception e) {
                    problem = "Couldn't download this recording. Check your connection";
                }
                String message = problem;
                post(() -> {
                    if (message != null) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                    else if (host.folder() != null && new File(host.folder(), r.file).exists()) toggle(r);
                });
            });
        }

        private void stopPlayback() {
            ticker.removeCallbacks(progress);
            if (player != null) {
                player.release();
                player = null;
            }
            playing = null;
            for (Runnable p : painters) p.run();
        }

        @Override
        JSONObject value() {
            return Ultimate.block(id, Ultimate.AUDIO, NoteData.recordingsJson(recordings));
        }

        @Override
        void pause() {
            stop();
            if (player != null && player.isPlaying()) player.pause();
        }

        @Override
        void destroy() {
            stop();
            stopPlayback();
        }
    }

    // -------------------------------------------------------------------- day

    private final class DayBlock extends Block {
        private long day;
        private String mood;
        private final TextView num = new TextView(getContext()), weekday = new TextView(getContext()), month = new TextView(getContext());
        private final LinearLayout moods = new LinearLayout(getContext());
        private final HorizontalScrollView moodScroll = new HorizontalScrollView(getContext());
        private final TextView moodNow = new TextView(getContext());

        DayBlock(JSONObject b) {
            super(b);
            day = (long) b.optDouble("day", 0);
            if (day == 0) day = Ultimate.dayStart(System.currentTimeMillis());
            mood = b.optString("mood", "");
            num.setTextSize(38);
            num.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            num.setTextColor(color(R.color.tile_purple));
            weekday.setTextSize(16);
            weekday.setTypeface(Typeface.DEFAULT_BOLD);
            weekday.setTextColor(color(R.color.text_primary));
            month.setTextSize(16);
            month.setTextColor(color(R.color.text_secondary));
            LinearLayout date = new LinearLayout(getContext());
            date.setGravity(Gravity.BOTTOM);
            date.addView(num);
            LayoutParams wl = new LayoutParams(-2, -2);
            wl.setMarginStart(dp(10));
            wl.bottomMargin = dp(7);
            date.addView(weekday, wl);
            LayoutParams ml0 = new LayoutParams(-2, -2);
            ml0.setMarginStart(dp(6));
            ml0.bottomMargin = dp(7);
            date.addView(month, ml0);
            date.setContentDescription("Change the day");
            date.setOnClickListener(v -> {
                if (host.readOnly()) return;
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(day);
                new DatePickerDialog(getContext(), (picker, y, m, d) -> {
                    Calendar chosen = Calendar.getInstance();
                    chosen.set(y, m, d, 0, 0, 0);
                    chosen.set(Calendar.MILLISECOND, 0);
                    day = chosen.getTimeInMillis();
                    showDay();
                    host.changed();
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
            });
            for (String m : MOODS) {
                TextView chip = new TextView(getContext());
                chip.setText(m);
                chip.setTextSize(22);
                chip.setGravity(Gravity.CENTER);
                chip.setOnClickListener(v -> {
                    if (host.readOnly()) return;
                    mood = m.equals(mood) ? "" : m;
                    showMoods();
                    moodScroll.setVisibility(GONE);
                    host.changed();
                });
                LayoutParams lp = new LayoutParams(dp(42), dp(42));
                lp.setMarginEnd(dp(4));
                moods.addView(chip, lp);
            }
            moodScroll.setHorizontalScrollBarEnabled(false);
            moodScroll.addView(moods);
            moodScroll.setVisibility(GONE);
            // Its mood beside it; a tap shows the moods to choose from.
            moodNow.setTextSize(22);
            moodNow.setGravity(Gravity.CENTER);
            moodNow.setContentDescription("How was it?");
            moodNow.setOnClickListener(v -> {
                if (!host.readOnly()) moodScroll.setVisibility(moodScroll.getVisibility() == VISIBLE ? GONE : VISIBLE);
            });
            showDay();
            showMoods();
            LinearLayout line = new LinearLayout(getContext());
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(date);
            line.addView(moodNow, new LayoutParams(dp(44), dp(44)));
            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(VERTICAL);
            body.setPadding(0, dp(8), 0, dp(2));
            body.addView(line);
            LayoutParams ml = new LayoutParams(-1, -2);
            ml.topMargin = dp(6);
            body.addView(moodScroll, ml);
            canvas(body);
        }

        private void showDay() {
            Date d = new Date(day);
            num.setText(new SimpleDateFormat("d", Locale.getDefault()).format(d));
            weekday.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(d));
            month.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d));
        }

        private void showMoods() {
            moodNow.setText(mood.isEmpty() ? "☺" : mood);
            moodNow.setTextColor(color(R.color.text_secondary));
            moodNow.setAlpha(mood.isEmpty() ? (host.readOnly() ? 0f : 0.45f) : 1f);
            for (int i = 0; i < moods.getChildCount(); i++) {
                View chip = moods.getChildAt(i);
                boolean on = MOODS[i].equals(mood);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(on ? color(R.color.note_purple) : 0);
                chip.setBackground(bg);
                chip.setAlpha(mood.isEmpty() || on ? 1f : 0.45f);
            }
        }

        @Override
        JSONObject value() {
            return put(put(put(put(new JSONObject(), "id", id), "kind", Ultimate.DAY), "day", day), "mood", mood);
        }
    }

    // ---------------------------------------------------------------- inside

    /**
     * What's inside an idea: a sheet over the page holding a page of its own (to any depth). Every change
     * goes into the idea at once, and its badge follows.
     */
    private void openInside(MapBlock owner, NoteData.MindNode node, @Nullable String kind) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        UltimateBlocks[] inner = new UltimateBlocks[1];
        Runnable sync = () -> {
            node.blocks = inner[0].array();
            owner.map.refresh();
            owner.map.invalidate();
        };
        inner[0] = new UltimateBlocks(activity, new Host() {
            @Override public void withFolder(Consumer<File> then) { host.withFolder(then); }
            @Nullable @Override public File folder() { return host.folder(); }
            @Override public int noteId() { return host.noteId(); }
            @Override public boolean readOnly() { return host.readOnly(); }

            @Override
            public void changed() {
                host.changed();
                post(sync);
            }

            @Override public void setUpText(EditText field, RichText rich) { host.setUpText(field, rich); }
            @Override public void textFocused(EditText field, RichText rich) { host.textFocused(field, rich); }
            @Override public void openPicture(File file) { host.openPicture(file); }
            @Override public void pickPictures(Consumer<List<String>> into) { host.pickPictures(into); }
            @Override public void withMicrophone(Runnable then) { host.withMicrophone(then); }
        }, false);
        inner[0].load("{\"blocks\":" + (node.blocks == null ? "[]" : node.blocks) + "}", null);

        TextView eyebrow = new TextView(getContext());
        eyebrow.setText("INSIDE THE IDEA");
        eyebrow.setTextSize(11);
        eyebrow.setLetterSpacing(0.08f);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setTextColor(color(R.color.tile_indigo));
        TextView title = new TextView(getContext());
        title.setText(node.text.isEmpty() ? "Idea" : node.text);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
        scroll.addView(inner[0]);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(4), dp(18), 0, 0);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.setMarginStart(dp(20));
        box.addView(eyebrow, hl);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginStart(dp(20));
        tl.bottomMargin = dp(6);
        box.addView(title, tl);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f)));
        if (!host.readOnly()) box.addView(inner[0].addBar(), new LinearLayout.LayoutParams(-1, -2));
        sheet.setContentView(box);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.setOnDismissListener(d -> {
            sync.run();
            inner[0].destroy();
            host.changed();
        });
        sheet.show();
        if (kind != null) inner[0].post(() -> inner[0].add(kind, true));
    }

    // ---------------------------------------------------------------- unknown

    /** Made by a newer version: shown as such and kept exactly as it is. */
    private final class UnknownBlock extends Block {
        private final JSONObject raw;

        UnknownBlock(JSONObject b) {
            super(b);
            raw = b;
            TextView t = new TextView(getContext());
            t.setText("This part was made by a newer Notes+. Update to see it here.");
            t.setTextColor(color(R.color.text_secondary));
            canvas(t);
        }

        @NonNull
        @Override
        JSONObject value() {
            return raw;
        }
    }
}
