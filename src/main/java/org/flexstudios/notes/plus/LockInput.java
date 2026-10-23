package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.StackPane;

import java.util.function.Consumer;

/**
 * Entering one kind of lock: a PIN (digits, 4–6), a password, or a pattern. Used by the
 * first setup, the lock screen and the vault settings, so all three look and check alike.
 */
final class LockInput extends StackPane {
    static final String PIN = "PIN", PASSWORD = "PASSWORD", PATTERN = "PATTERN";
    static final String[] TYPES = {PIN, PASSWORD, PATTERN};

    final String type;
    private final PasswordField field;
    private final PatternView pattern;
    private String drawn = "";

    LockInput(String type, String hint, Consumer<String> onSubmit) {
        this.type = type;
        setAlignment(Pos.CENTER);
        if (PATTERN.equals(type)) {
            field = null;
            pattern = new PatternView(280);
            pattern.setOnPattern(p -> {
                drawn = p;
                if (onSubmit != null) onSubmit.accept(p);
            });
            getChildren().add(pattern);
        } else {
            pattern = null;
            field = new PasswordField();
            field.getStyleClass().setAll("text-input", "text-field", "lock-field");
            field.setPromptText(hint != null ? hint : PIN.equals(type) ? "PIN" : "Password");
            field.setMaxWidth(PIN.equals(type) ? 200 : 260);
            if (PIN.equals(type)) {
                field.setTextFormatter(new TextFormatter<String>(c ->
                        c.getControlNewText().matches("\\d{0,6}") ? c : null));
            }
            if (onSubmit != null) field.setOnAction(e -> onSubmit.accept(field.getText()));
            getChildren().add(field);
        }
    }

    String value() {
        return field != null ? field.getText() : drawn;
    }

    void clear() {
        drawn = "";
        if (field != null) field.clear();
        if (pattern != null) pattern.reset();
    }

    void focus() {
        if (field != null) Platform.runLater(field::requestFocus);
    }

    /** Null when the lock is acceptable, otherwise what is wrong with it (the phone's words). */
    static String problem(String type, String value) {
        if (value == null || value.isEmpty()) return "Lock cannot be empty";
        if (PIN.equals(type) && value.length() < 4) return "PIN must be at least 4 digits";
        if (PATTERN.equals(type) && value.length() < 4) return "Pattern must connect at least 4 dots";
        return null;
    }

    static String label(String type) {
        return PIN.equals(type) ? "PIN" : PASSWORD.equals(type) ? "Password" : "Pattern";
    }
}
