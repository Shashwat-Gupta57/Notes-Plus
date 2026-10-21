package org.flexstudios.notes.plus;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.function.Consumer;

/**
 * The app's small dialogs, so every prompt looks and behaves the same: Material 3 dialog,
 * an outlined rounded field, the keyboard already open, the current text selected, Done on
 * the keyboard confirms, and OK stays disabled while the field is empty.
 */
final class Prompts {

    private Prompts() {}

    /** Plain single-line text. */
    static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    static final int URL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;

    /**
     * Asks for one line of text.
     *
     * @param message  shown above the field, or null
     * @param initial  pre-filled and selected, or null
     * @param onCancel or null
     */
    static AlertDialog text(Activity activity, String title, @Nullable String message, String hint,
                            @Nullable String initial, int inputType, String okLabel,
                            Consumer<String> onOk, @Nullable Runnable onCancel) {
        return text(activity, title, message, hint, initial, inputType, okLabel, onOk, onCancel, null, null);
    }

    /** As {@link #text}, with a third button that also receives the text (e.g. "Next idea"). */
    static AlertDialog text(Activity activity, String title, @Nullable String message, String hint,
                            @Nullable String initial, int inputType, String okLabel,
                            Consumer<String> onOk, @Nullable Runnable onCancel,
                            @Nullable String neutralLabel, @Nullable Consumer<String> onNeutral) {
        TextInputLayout field = field(activity, hint, initial, inputType);
        TextInputEditText input = (TextInputEditText) field.getEditText();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(padded(activity, field))
                .setPositiveButton(okLabel, (d, w) -> onOk.accept(textOf(input)))
                .setNegativeButton("Cancel", (d, w) -> {
                    if (onCancel != null) onCancel.run();
                });
        if (message != null) builder.setMessage(message);
        if (neutralLabel != null && onNeutral != null) {
            builder.setNeutralButton(neutralLabel, (d, w) -> onNeutral.accept(textOf(input)));
        }
        AlertDialog dialog = builder.create();
        wire(dialog, input);
        dialog.show();
        return dialog;
    }

    /** An outlined, rounded text field, for prompts that need more than one input. */
    static TextInputLayout field(Context context, String hint, @Nullable String initial, int inputType) {
        TextInputLayout layout = new TextInputLayout(context, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint(hint);
        float density = context.getResources().getDisplayMetrics().density;
        float radius = 14 * density;
        layout.setBoxCornerRadii(radius, radius, radius, radius);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (initial != null) input.setText(initial);
        layout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if ((inputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        return layout;
    }

    /** Dialog content padding matching Material 3's 24dp sides. */
    static FrameLayout padded(Context context, android.view.View content) {
        float density = context.getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(context);
        int side = Math.round(24 * density);
        box.setPadding(side, Math.round(8 * density), side, 0);
        box.addView(content);
        return box;
    }

    /**
     * Keyboard up and text selected on open; Done confirms; OK disabled while empty.
     * Works for any dialog whose positive button should follow one field.
     */
    static void wire(AlertDialog dialog, TextInputEditText input) {
        dialog.setOnShowListener(d -> {
            android.widget.Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(!textOf(input).isEmpty());
            input.addTextChangedListener(NoteEditorActivity.simpleWatcher(
                    () -> ok.setEnabled(!textOf(input).isEmpty())));
            input.setOnEditorActionListener((v, action, e) -> {
                if (action != EditorInfo.IME_ACTION_DONE || !ok.isEnabled()) return false;
                ok.performClick();
                return true;
            });
            input.requestFocus();
            input.selectAll();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
    }

    /** An in-progress dialog: a title, a line of status and a bar. Replaces ProgressDialog. */
    static AlertDialog progress(Activity activity, String title) {
        LinearProgressIndicator bar = new LinearProgressIndicator(activity);
        bar.setIndeterminate(true);
        float density = activity.getResources().getDisplayMetrics().density;
        bar.setTrackCornerRadius(Math.round(3 * density));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage("Starting…")
                .setView(padded(activity, bar))
                .setCancelable(false)
                .create();
        dialog.show();
        return dialog;
    }

    static String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
