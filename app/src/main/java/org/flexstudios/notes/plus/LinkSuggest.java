package org.flexstudios.notes.plus;

import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * Typing "[[" in a note offers the titles of other notes, under the caret: a tap writes
 * "[[Title]]". The list narrows as more is typed and goes away at "]]", a new line, or no match.
 */
final class LinkSuggest implements TextWatcher {

    private static final int MAX = 8;

    private final EditText edit;
    private final Supplier<List<NoteEntity>> notes;
    private final IntSupplier self;
    private PopupWindow popup;
    private boolean writing;

    private LinkSuggest(EditText edit, Supplier<List<NoteEntity>> notes, IntSupplier self) {
        this.edit = edit;
        this.notes = notes;
        this.self = self;
    }

    static void attach(EditText edit, Supplier<List<NoteEntity>> notes, IntSupplier self) {
        LinkSuggest s = new LinkSuggest(edit, notes, self);
        edit.addTextChangedListener(s);
        edit.setOnFocusChangeListener(chain(edit.getOnFocusChangeListener(), (v, has) -> {
            if (!has) s.hide();
        }));
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener before, View.OnFocusChangeListener after) {
        return (v, has) -> {
            if (before != null) before.onFocusChange(v, has);
            after.onFocusChange(v, has);
        };
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable e) {
        if (writing) return;
        int caret = edit.getSelectionEnd();
        if (caret < 0 || !edit.hasFocus()) {
            hide();
            return;
        }
        int lineStart = Math.max(0, e.toString().lastIndexOf('\n', caret - 1) + 1);
        Matcher m = NoteLinks.TYPING.matcher(e.subSequence(lineStart, caret));
        if (!m.find()) {
            hide();
            return;
        }
        List<String> titles = NoteLinks.suggestions(m.group(1), notes.get(), MAX, self.getAsInt());
        if (titles.isEmpty()) {
            hide();
            return;
        }
        show(titles, lineStart + m.start(1), caret);
    }

    private void show(List<String> titles, int from, int caret) {
        float dp = edit.getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(edit.getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6 * dp);
        list.setPadding(0, pad, 0, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(edit.getContext(), R.color.app_surface));
        bg.setCornerRadius(14 * dp);
        bg.setStroke(Math.round(dp), ContextCompat.getColor(edit.getContext(), R.color.divider));
        list.setBackground(bg);
        list.setElevation(8 * dp);
        TypedValue ripple = new TypedValue();
        edit.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (String title : titles) {
            TextView row = new TextView(edit.getContext());
            row.setText(title);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setTextSize(15);
            row.setTextColor(ContextCompat.getColor(edit.getContext(), R.color.text_primary));
            row.setPadding(Math.round(14 * dp), Math.round(9 * dp), Math.round(14 * dp), Math.round(9 * dp));
            row.setBackgroundResource(ripple.resourceId);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_note_text, 0, 0, 0);
            row.setCompoundDrawablePadding(Math.round(10 * dp));
            if (row.getCompoundDrawablesRelative()[0] != null) {
                row.getCompoundDrawablesRelative()[0].mutate().setTint(ContextCompat.getColor(edit.getContext(), R.color.accent_blue));
                row.getCompoundDrawablesRelative()[0].setBounds(0, 0, Math.round(18 * dp), Math.round(18 * dp));
                row.setCompoundDrawablesRelative(row.getCompoundDrawablesRelative()[0], null, null, null);
            }
            row.setOnClickListener(v -> choose(title, from));
            list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        int width = Math.min(Math.round(300 * dp), edit.getWidth());
        if (popup == null) {
            popup = new PopupWindow(list, width, LinearLayout.LayoutParams.WRAP_CONTENT, false);
            popup.setOutsideTouchable(true);
            popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
            popup.setElevation(8 * dp);
        } else {
            popup.setContentView(list);
        }
        // Under the caret's line, inside the screen.
        Layout layout = edit.getLayout();
        int[] at = new int[2];
        edit.getLocationOnScreen(at);
        int x = at[0], y = at[1];
        if (layout != null) {
            int line = layout.getLineForOffset(caret);
            x += Math.round(layout.getPrimaryHorizontal(caret)) + edit.getPaddingLeft() - edit.getScrollX();
            y += layout.getLineBottom(line) + edit.getPaddingTop() - edit.getScrollY() + Math.round(4 * dp);
        }
        int screen = edit.getResources().getDisplayMetrics().widthPixels;
        x = Math.max(Math.round(8 * dp), Math.min(x - Math.round(16 * dp), screen - width - Math.round(8 * dp)));
        if (popup.isShowing()) popup.update(x, y, width, -1);
        else popup.showAtLocation(edit, Gravity.TOP | Gravity.START, x, y);
    }

    /** Writes "Title]]" over what was typed after "[[" (and past a "]]" already there). */
    private void choose(String title, int from) {
        Editable e = edit.getText();
        if (e == null) return;
        int caret = Math.max(from, edit.getSelectionEnd());
        int end = caret;
        if (e.length() >= caret + 2 && e.subSequence(caret, caret + 2).toString().equals("]]")) end = caret + 2;
        writing = true;
        e.replace(from, end, title + "]]");
        writing = false;
        edit.setSelection(Math.min(e.length(), from + title.length() + 2));
        hide();
    }

    private void hide() {
        if (popup != null && popup.isShowing()) popup.dismiss();
    }
}
