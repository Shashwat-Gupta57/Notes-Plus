package org.flexstudios.notes.plus;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.binding.BooleanExpression;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * The phone's Material pieces, rebuilt once so every screen looks and behaves the same:
 * icons, buttons and FABs, dialogs (lists, single choice, confirmations), text prompts with an
 * outlined field, the bottom sheet, popup menus, snackbars, toasts and the date picker.
 *
 * Everything that floats is drawn inside the window, on {@link #layer}, the way the phone
 * draws over its activity: the same dim, the same corners, no native popups. Escape cancels
 * the topmost one ({@link #dismissTop}).
 */
final class Ui {

    private Ui() {}

    /** The current window's floating layer, over its screens. Set by {@link NotesApp} as windows take focus. */
    static StackPane layer;

    // ==================================================================== icons

    /** One of the phone's vector icons, at a size, tinted with a palette colour. */
    static final class IconView extends Pane {
        private final SVGPath path = new SVGPath();
        private final Scale scale = new Scale(1, 1, 0, 0);
        private final double size;

        IconView(Icons.Icon icon, double size) {
            this.size = size;
            path.getTransforms().add(scale);
            getChildren().add(path);
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
            setMouseTransparent(true);
            setIcon(icon);
        }

        void setIcon(Icons.Icon icon) {
            path.setContent(icon.path());
            double s = size / icon.viewport();
            scale.setX(s);
            scale.setY(s);
        }

        IconView color(String name) {
            path.setStyle("-fx-fill: " + Theme.ref(name) + ";");
            return this;
        }
    }

    static IconView icon(Icons.Icon icon, double size, String color) {
        return new IconView(icon, size).color(color);
    }

    // ================================================================== buttons

    /** A round, borderless icon button: the toolbar's actions. */
    static Button iconButton(Icons.Icon icon, double box, double iconSize, String color, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon(icon, iconSize, color));
        // Graphic only: an empty text slot beside the glyph pushes it off the circle's centre.
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.setAlignment(Pos.CENTER);
        b.getStyleClass().setAll("icon-button");
        b.setMinSize(box, box);
        b.setPrefSize(box, box);
        b.setMaxSize(box, box);
        b.setFocusTraversable(false);
        if (tip != null) tooltip(b, tip);
        b.setOnAction(e -> action.run());
        return b;
    }

    static Button iconButton(Icons.Icon icon, String color, String tip, Runnable action) {
        return iconButton(icon, 48, 24, color, tip, action);
    }

    /** The yellow "New" / "Add idea" button. */
    static Button extendedFab(String text, Icons.Icon icon, Runnable action) {
        Button b = new Button(text, icon(icon, 24, "on_brand_yellow"));
        b.getStyleClass().setAll("ext-fab");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** A small floating button in the theme's primary container colour. */
    static Button miniFab(Icons.Icon icon, String tip, Runnable action) {
        Button b = new Button(null, icon(icon, 24, "m3_on_primary_container"));
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().setAll("mini-fab");
        b.setAlignment(Pos.CENTER);
        b.setFocusTraversable(false);
        tooltip(b, tip);
        b.setOnAction(e -> action.run());
        return b;
    }

    static Button textButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().setAll("text-button");
        b.setOnAction(e -> action.run());
        return b;
    }

    static void tooltip(Node node, String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(500));
        Tooltip.install(node, t);
    }

    static Label label(String text, double size, String... styleClasses) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: " + size + "px;");
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    // ================================================================== overlays

    /** Something floating over the screen: a dialog, a sheet or a menu. */
    static final class Modal {
        final StackPane root = new StackPane();
        private final Region scrim = new Region();
        private Runnable onCancel;
        private Node returnFocus;
        private boolean closed;

        /** Closes it without running the cancel action. */
        void dismiss() {
            if (closed) return;
            closed = true;
            modals.remove(this);
            FadeTransition out = new FadeTransition(Duration.millis(110), root);
            out.setToValue(0);
            out.setOnFinished(e -> unparent(root));
            out.play();
            root.setMouseTransparent(true);
            if (returnFocus != null && returnFocus.getScene() != null) returnFocus.requestFocus();
        }

        /** Escape, or a click outside: closes and runs the cancel action. */
        void cancel() {
            if (closed) return;
            dismiss();
            if (onCancel != null) onCancel.run();
        }
    }

    private static final Deque<Modal> modals = new ArrayDeque<>();

    /** Something floats over the current window. */
    static boolean hasModal() {
        return topModal() != null;
    }

    /** Cancels the current window's topmost floating thing, if any. */
    static boolean dismissTop() {
        Modal top = topModal();
        if (top == null) return false;
        top.cancel();
        return true;
    }

    private static Modal topModal() {
        for (java.util.Iterator<Modal> it = modals.descendingIterator(); it.hasNext(); ) {
            Modal m = it.next();
            if (m.root.getParent() == layer) return m;
        }
        return null;
    }

    /** Takes a floating node off whichever window's layer it is on. */
    private static void unparent(Node n) {
        if (n.getParent() instanceof javafx.scene.layout.Pane p) p.getChildren().remove(n);
    }

    /** Shows {@code content} over everything; Escape or a click outside cancels it. */
    static Modal present(Node content, Pos pos, boolean dim, Runnable onCancel) {
        Modal m = new Modal();
        m.onCancel = onCancel;
        m.scrim.getStyleClass().add(dim ? "scrim" : "scrim-clear");
        m.scrim.setOnMouseClicked(e -> m.cancel());
        m.root.getChildren().addAll(m.scrim, content);
        StackPane.setAlignment(content, pos);
        Scene scene = layer.getScene();
        m.returnFocus = scene == null ? null : scene.getFocusOwner();
        m.root.setFocusTraversable(true);
        layer.getChildren().add(m.root);
        modals.addLast(m);
        m.root.requestFocus();
        return m;
    }

    private static void popIn(Modal m, Node card) {
        m.scrim.setOpacity(0);
        FadeTransition dim = new FadeTransition(Duration.millis(150), m.scrim);
        dim.setToValue(1);
        card.setOpacity(0);
        card.setScaleX(0.92);
        card.setScaleY(0.92);
        FadeTransition fade = new FadeTransition(Duration.millis(150), card);
        fade.setToValue(1);
        ScaleTransition grow = new ScaleTransition(Duration.millis(170), card);
        grow.setToX(1);
        grow.setToY(1);
        grow.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(dim, fade, grow).play();
    }

    // ================================================================== dialogs

    static DialogBuilder dialog() {
        return new DialogBuilder();
    }

    /** MaterialAlertDialogBuilder, as the phone uses it. */
    static final class DialogBuilder {
        private String title, message;
        private Node content;
        private List<String> items;
        private List<Runnable> itemActions;
        private String[] choices;
        private int checked = -1;
        private IntConsumer onChoice;
        private String positive, negative, neutral;
        private Runnable onPositive, onNegative, onNeutral;
        private BooleanExpression positiveDisabled;
        private Runnable keepOpen;
        private double width = 340;

        DialogBuilder title(String t) { title = t; return this; }
        DialogBuilder message(String m) { message = m; return this; }
        DialogBuilder content(Node c) { content = c; return this; }
        DialogBuilder width(double w) { width = w; return this; }

        DialogBuilder items(List<String> labels, List<Runnable> actions) {
            items = labels;
            itemActions = actions;
            return this;
        }

        DialogBuilder singleChoice(String[] options, int selected, IntConsumer onPick) {
            choices = options;
            checked = selected;
            onChoice = onPick;
            return this;
        }

        DialogBuilder positive(String label, Runnable action) { positive = label; onPositive = action; return this; }
        DialogBuilder negative(String label, Runnable action) { negative = label; onNegative = action; return this; }
        DialogBuilder neutral(String label, Runnable action) { neutral = label; onNeutral = action; return this; }
        DialogBuilder positiveDisabled(BooleanExpression when) { positiveDisabled = when; return this; }
        /** The positive button runs this and leaves the dialog up: it dismisses itself once done (a lookup, say). */
        DialogBuilder keepOpen(Runnable action) { keepOpen = action; return this; }

        Modal show() {
            VBox card = new VBox();
            card.getStyleClass().add("dialog");
            card.setMinWidth(280);
            card.setPrefWidth(width);
            card.setMaxWidth(Region.USE_PREF_SIZE);
            card.setMaxHeight(Region.USE_PREF_SIZE);
            boolean list = items != null || choices != null;
            boolean buttons = positive != null || negative != null || neutral != null;

            if (title != null) {
                Label t = new Label(title);
                t.getStyleClass().add("dialog-title");
                t.setWrapText(true);
                t.setPadding(new Insets(24, 24, list ? 12 : 16, 24));
                card.getChildren().add(t);
            } else {
                card.getChildren().add(gap(list ? 8 : 24));
            }
            if (message != null) {
                Label m = new Label(message);
                m.getStyleClass().add("dialog-message");
                m.setWrapText(true);
                m.setPadding(new Insets(0, 24, content != null || list ? 16 : 0, 24));
                card.getChildren().add(m);
            }
            if (content != null) {
                StackPane box = new StackPane(content);
                box.setPadding(new Insets(4, 24, 0, 24));
                card.getChildren().add(box);
            }

            Modal[] self = new Modal[1];
            if (items != null) {
                VBox rows = new VBox();
                for (int i = 0; i < items.size(); i++) {
                    int index = i;
                    HBox row = new HBox(new Label(items.get(i)));
                    row.getStyleClass().add("dialog-item");
                    row.setOnMouseClicked(e -> {
                        self[0].dismiss();
                        itemActions.get(index).run();
                    });
                    rows.getChildren().add(row);
                }
                card.getChildren().add(scrollIfLong(rows));
            }
            if (choices != null) {
                VBox rows = new VBox();
                for (int i = 0; i < choices.length; i++) {
                    int index = i;
                    Region ring = new Region();
                    ring.getStyleClass().add("radio-ring");
                    if (i == checked) ring.getStyleClass().add("on");
                    ring.setMinSize(20, 20);
                    ring.setMaxSize(20, 20);
                    HBox row = new HBox(16, ring, new Label(choices[i]));
                    row.getStyleClass().add("dialog-item");
                    row.setOnMouseClicked(e -> {
                        self[0].dismiss();
                        onChoice.accept(index);
                    });
                    rows.getChildren().add(row);
                }
                card.getChildren().add(scrollIfLong(rows));
            }

            if (buttons) {
                HBox bar = new HBox(8);
                bar.setAlignment(Pos.CENTER_RIGHT);
                bar.setPadding(new Insets(list ? 8 : 24, 18, 18, 18));
                if (neutral != null) {
                    bar.getChildren().addAll(textButton(neutral, () -> {
                        self[0].dismiss();
                        if (onNeutral != null) onNeutral.run();
                    }), spacer());
                }
                if (negative != null) {
                    bar.getChildren().add(textButton(negative, () -> {
                        self[0].dismiss();
                        if (onNegative != null) onNegative.run();
                    }));
                }
                if (positive != null) {
                    Button ok = textButton(positive, () -> {
                        if (keepOpen != null) {
                            keepOpen.run();
                            return;
                        }
                        self[0].dismiss();
                        if (onPositive != null) onPositive.run();
                    });
                    if (positiveDisabled != null) ok.disableProperty().bind(positiveDisabled);
                    ok.setDefaultButton(content == null);
                    bar.getChildren().add(ok);
                }
                card.getChildren().add(bar);
            } else {
                card.getChildren().add(gap(list ? 12 : 24));
            }

            Modal m = present(card, Pos.CENTER, true, onNegative);
            self[0] = m;
            popIn(m, card);
            return m;
        }

        private static Node scrollIfLong(VBox rows) {
            ScrollPane scroll = new ScrollPane(rows);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setMaxHeight(48 * 9.5);
            scroll.setPrefViewportHeight(Math.min(48 * 9.5, rows.getChildren().size() * 48));
            return scroll;
        }
    }

    private static Region gap(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        r.setPrefHeight(height);
        return r;
    }

    /** "Delete this note?" with a destructive-free pair of buttons, as the phone asks. */
    static void confirm(String title, String yes, Runnable onYes) {
        dialog().title(title).positive(yes, onYes).negative("Cancel", null).show();
    }

    // =================================================================== prompts

    /** An outlined, rounded text field with a floating label: the phone's TextInputLayout. */
    static final class Field extends StackPane {
        final TextField input;
        private final Label label = new Label();

        Field(String hint, String initial) {
            this(hint, initial, false);
        }

        Field(String hint, String initial, boolean password) {
            input = password ? new javafx.scene.control.PasswordField() : new TextField();
            getStyleClass().add("outlined-field");
            input.getStyleClass().setAll("text-input", "text-field", "field-input");
            input.setText(initial == null ? "" : initial);
            input.setMaxHeight(Double.MAX_VALUE);
            label.setText(hint);
            label.getStyleClass().add("field-label");
            label.setMouseTransparent(true);
            StackPane.setAlignment(label, Pos.CENTER_LEFT);
            StackPane.setMargin(label, new Insets(0, 0, 0, 12));
            getChildren().addAll(input, label);
            setMinHeight(56);
            setPrefHeight(56);
            input.focusedProperty().addListener((o, a, b) -> float_(true));
            input.textProperty().addListener((o, a, b) -> float_(true));
            float_(false);
        }

        private void float_(boolean animate) {
            boolean up = input.isFocused() || !input.getText().isEmpty();
            pseudoFocus(input.isFocused());
            label.setStyle("-fx-font-size: " + (up ? 12 : 16) + "px;");
            double y = up ? -28 : 0;
            if (!animate) {
                label.setTranslateY(y);
                return;
            }
            new Timeline(new KeyFrame(Duration.millis(120),
                    new KeyValue(label.translateYProperty(), y, Interpolator.EASE_OUT))).play();
        }

        private void pseudoFocus(boolean focused) {
            if (focused && !getStyleClass().contains("focused")) getStyleClass().add("focused");
            if (!focused) getStyleClass().remove("focused");
        }

        String text() {
            return input.getText() == null ? "" : input.getText().trim();
        }
    }

    /**
     * Asks for one line of text: the current text selected, Enter confirms, OK disabled while
     * the field is empty. With {@code neutral}, a third button also receives the text (e.g.
     * "Next idea").
     */
    static void prompt(String title, String message, String hint, String initial, String okLabel,
                       Consumer<String> onOk, Runnable onCancel, String neutral, Consumer<String> onNeutral) {
        Field field = new Field(hint, initial);
        DialogBuilder b = dialog().title(title).content(field).width(400)
                .positive(okLabel, () -> onOk.accept(field.text()))
                .negative("Cancel", onCancel)
                .positiveDisabled(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> field.text().isEmpty(), field.input.textProperty()));
        if (message != null) b.message(message);
        if (neutral != null && onNeutral != null) b.neutral(neutral, () -> onNeutral.accept(field.text()));
        Modal m = b.show();
        field.input.setOnAction(e -> {
            if (field.text().isEmpty()) return;
            m.dismiss();
            onOk.accept(field.text());
        });
        javafx.application.Platform.runLater(() -> {
            field.input.requestFocus();
            field.input.selectAll();
        });
    }

    static void prompt(String title, String hint, String initial, String okLabel, Consumer<String> onOk) {
        prompt(title, null, hint, initial, okLabel, onOk, null, null, null);
    }

    // ====================================================================== menu

    /** The toolbar's overflow menu, dropping from {@code anchor}'s top-right corner. */
    static void menu(Node anchor, List<String> labels, List<Runnable> actions) {
        VBox box = new VBox();
        box.getStyleClass().add("menu-popup");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Modal[] self = new Modal[1];
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            HBox row = new HBox(new Label(labels.get(i)));
            row.getStyleClass().add("menu-row");
            row.setOnMouseClicked(e -> {
                self[0].dismiss();
                actions.get(index).run();
            });
            box.getChildren().add(row);
        }
        Bounds b = layer.sceneToLocal(anchor.localToScene(anchor.getBoundsInLocal()));
        StackPane.setMargin(box, new Insets(b.getMinY() + 4, Math.max(8, layer.getWidth() - b.getMaxX() + 4), 0, 0));
        self[0] = present(box, Pos.TOP_RIGHT, false, null);
        box.setOpacity(0);
        box.setScaleY(0.8);
        box.setTranslateY(-12);
        FadeTransition fade = new FadeTransition(Duration.millis(120), box);
        fade.setToValue(1);
        ScaleTransition grow = new ScaleTransition(Duration.millis(150), box);
        grow.setToY(1);
        TranslateTransition drop = new TranslateTransition(Duration.millis(150), box);
        drop.setToY(0);
        new ParallelTransition(fade, grow, drop).play();
    }

    // ===================================================================== sheet

    /** A bottom sheet, sliding up, at most 640 wide like the phone's on a tablet. */
    static Modal sheet(Node content) {
        VBox sheet = new VBox(content);
        sheet.getStyleClass().add("sheet");
        sheet.setMaxWidth(640);
        sheet.setMaxHeight(Region.USE_PREF_SIZE);
        Modal m = present(sheet, Pos.BOTTOM_CENTER, true, null);
        m.scrim.setOpacity(0);
        FadeTransition dim = new FadeTransition(Duration.millis(200), m.scrim);
        dim.setToValue(1);
        sheet.setTranslateY(600);
        TranslateTransition up = new TranslateTransition(Duration.millis(260), sheet);
        up.setToY(0);
        up.setInterpolator(Interpolator.SPLINE(0.05, 0.7, 0.1, 1));
        new ParallelTransition(dim, up).play();
        return m;
    }

    // ================================================================= snackbar

    private static HBox snack;
    private static Consumer<Boolean> snackDismissed;
    private static PauseTransition snackTimer;

    static final Duration SHORT = Duration.millis(1500), LONG = Duration.millis(2750);

    /**
     * A snackbar at the bottom. {@code onDismissed} receives true when it went because its
     * action was clicked, false when it timed out or another took its place.
     */
    static void snackbar(String text, Duration length, String action, Runnable onAction, Consumer<Boolean> onDismissed) {
        closeSnack(false);
        HBox bar = new HBox();
        bar.getStyleClass().add("snackbar");
        bar.setMaxWidth(560);
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        Label l = new Label(text);
        l.setWrapText(true);
        bar.getChildren().addAll(l, spacer());
        if (action != null) {
            Button b = new Button(action);
            b.getStyleClass().setAll("snackbar-action");
            b.setOnAction(e -> {
                closeSnack(true);
                if (onAction != null) onAction.run();
            });
            bar.getChildren().add(b);
        }
        bar.setPickOnBounds(true);
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(bar, new Insets(0, 16, 16, 16));
        layer.getChildren().add(bar);
        snack = bar;
        snackDismissed = onDismissed;
        bar.setOpacity(0);
        bar.setTranslateY(20);
        FadeTransition fade = new FadeTransition(Duration.millis(150), bar);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(200), bar);
        rise.setToY(0);
        new ParallelTransition(fade, rise).play();
        snackTimer = new PauseTransition(length);
        snackTimer.setOnFinished(e -> closeSnack(false));
        snackTimer.play();
    }

    static void snackbar(String text, Duration length) {
        snackbar(text, length, null, null, null);
    }

    /** Closes the snackbar now (e.g. leaving the screen), reporting it as timed out. */
    static void closeSnack(boolean byAction) {
        if (snack == null) return;
        HBox bar = snack;
        Consumer<Boolean> done = snackDismissed;
        snack = null;
        snackDismissed = null;
        if (snackTimer != null) snackTimer.stop();
        FadeTransition out = new FadeTransition(Duration.millis(150), bar);
        out.setToValue(0);
        out.setOnFinished(e -> unparent(bar));
        out.play();
        if (done != null) done.accept(byAction);
    }

    /** A short message that comes and goes by itself. */
    static void toast(String text) {
        Label t = new Label(text);
        t.getStyleClass().add("toast");
        t.setMouseTransparent(true);
        StackPane.setAlignment(t, Pos.BOTTOM_CENTER);
        StackPane.setMargin(t, new Insets(0, 0, 72, 0));
        layer.getChildren().add(t);
        t.setOpacity(0);
        FadeTransition in = new FadeTransition(Duration.millis(150), t);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(2000));
        FadeTransition out = new FadeTransition(Duration.millis(250), t);
        out.setToValue(0);
        out.setOnFinished(e -> unparent(t));
        new javafx.animation.SequentialTransition(in, hold, out).play();
    }

    // =============================================================== date picker

    /** The phone's date picker dialog: a calendar, Cancel and OK. Midnight of the chosen day. */
    static void pickDate(long millis, LongConsumer onPick) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate[] chosen = {Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()};
        YearMonth[] shown = {YearMonth.from(chosen[0])};
        Label headline = label("", 32);
        Label month = label("", 14, "medium");
        GridPane grid = new GridPane();
        grid.setHgap(2);
        grid.setVgap(2);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            headline.setText(chosen[0].format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())));
            month.setText(shown[0].format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())));
            grid.getChildren().clear();
            DayOfWeek first = java.time.temporal.WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
            for (int c = 0; c < 7; c++) {
                Label w = new Label(first.plus(c).getDisplayName(TextStyle.NARROW, Locale.getDefault()));
                w.getStyleClass().add("cal-weekday");
                grid.add(w, c, 0);
            }
            LocalDate day = shown[0].atDay(1);
            int offset = (day.getDayOfWeek().getValue() - first.getValue() + 7) % 7;
            LocalDate today = LocalDate.now(zone);
            for (int d = 1; d <= shown[0].lengthOfMonth(); d++) {
                LocalDate date = shown[0].atDay(d);
                Button b = new Button(String.valueOf(d));
                b.getStyleClass().setAll("cal-day");
                if (date.equals(today)) b.getStyleClass().add("today");
                if (date.equals(chosen[0])) b.getStyleClass().add("chosen");
                b.setOnAction(e -> {
                    chosen[0] = date;
                    render[0].run();
                });
                int cell = offset + d - 1;
                grid.add(b, cell % 7, 1 + cell / 7);
            }
        };
        Button prev = iconButton(Icons.ARROW_BACK, 40, 20, "text_secondary", "Previous month", () -> {
            shown[0] = shown[0].minusMonths(1);
            render[0].run();
        });
        Button next = iconButton(Icons.ARROW_BACK, 40, 20, "text_secondary", "Next month", () -> {
            shown[0] = shown[0].plusMonths(1);
            render[0].run();
        });
        next.getGraphic().setRotate(180);
        render[0].run();
        HBox nav = new HBox(month, spacer(), prev, next);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.setPadding(new Insets(12, 0, 4, 0));
        VBox box = new VBox(label("Select date", 12, "secondary"), headline, nav, grid);
        box.setPadding(new Insets(0, 0, 0, 0));
        dialog().content(box).width(360)
                .positive("OK", () -> onPick.accept(chosen[0].atStartOfDay(zone).toInstant().toEpochMilli()))
                .negative("Cancel", null)
                .show();
    }

    // ================================================================ text helpers

    /**
     * Makes a bare TextArea as tall as its text (never below {@code minHeight}), so it reads
     * like the phone's growing EditText inside a scrolling page.
     */
    static void autoGrow(TextArea area, double minHeight, double lineSpacing) {
        Text measure = new Text();
        Runnable fit = () -> {
            measure.setFont(area.getFont());
            measure.setLineSpacing(lineSpacing);
            measure.setWrappingWidth(Math.max(1, area.getWidth() - 6));
            String t = area.getText() == null || area.getText().isEmpty() ? "X" : area.getText();
            measure.setText(t.endsWith("\n") ? t + "X" : t);
            double h = Math.max(minHeight, Math.ceil(measure.getLayoutBounds().getHeight()) + 6);
            // The area's own text can wrap a little narrower than the measure: never cut it off.
            Text inner = innerText(area, null);
            if (inner != null) h = Math.max(h, Math.ceil(inner.getLayoutBounds().getHeight()) + 12);
            if (Math.abs(h - area.getPrefHeight()) < 0.5) return;
            area.setMinHeight(h);
            area.setPrefHeight(h);
            area.setMaxHeight(h);
        };
        area.textProperty().addListener((o, a, b) -> fit.run());
        area.widthProperty().addListener((o, a, b) -> fit.run());
        area.fontProperty().addListener((o, a, b) -> fit.run());
        area.skinProperty().addListener((o, a, b) -> javafx.application.Platform.runLater(() -> innerText(area, fit)));
        area.setWrapText(true);
        fit.run();
    }

    /**
     * The text node a TextArea draws its words with (not its prompt). With {@code onResize}, it
     * is also told whenever that node's size changes (once per node).
     */
    static Text innerText(TextArea area, Runnable onResize) {
        for (Node n : area.lookupAll(".text")) {
            if (!(n instanceof Text t) || !java.util.Objects.equals(t.getText(), area.getText())) continue;
            if (onResize != null && t.getProperties().putIfAbsent("np-grow", Boolean.TRUE) == null) {
                t.layoutBoundsProperty().addListener((o, a, b) -> onResize.run());
                onResize.run();
            }
            return t;
        }
        return null;
    }

    /**
     * Left and Right move one character back or forward in the text, as Notepad does. JavaFX
     * moves the caret by what's on screen instead, and with right-to-left letters or direction
     * marks in a text it gets stuck or jumps between line ends. Ctrl (word) moves stay its own.
     */
    static void logicalArrows(javafx.scene.control.TextInputControl area) {
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() || e.isAltDown() || e.isMetaDown()) return;
            boolean left = e.getCode() == javafx.scene.input.KeyCode.LEFT, right = e.getCode() == javafx.scene.input.KeyCode.RIGHT;
            if (!left && !right) return;
            e.consume();
            if (e.isShiftDown()) {
                if (left) area.selectBackward();
                else area.selectForward();
            } else if (area.getSelection().getLength() > 0) {
                int to = left ? area.getSelection().getStart() : area.getSelection().getEnd();
                area.deselect();
                area.positionCaret(to);
            } else if (left) {
                area.backward();
            } else {
                area.forward();
            }
        });
    }

    /** Keeps the caret of a growing TextArea inside the page's scroll viewport while typing. */
    static void followCaret(TextArea area, ScrollPane scroll) {
        area.caretPositionProperty().addListener((o, a, b) -> javafx.application.Platform.runLater(() -> {
            if (!(area.getSkin() instanceof javafx.scene.control.skin.TextAreaSkin skin)) return;
            javafx.geometry.Rectangle2D c = skin.getCharacterBounds(Math.max(0, Math.min(area.getLength(), b.intValue()) - (b.intValue() > 0 ? 1 : 0)));
            if (c == null) return;
            Node content = scroll.getContent();
            Bounds inContent = content.sceneToLocal(area.localToScene(
                    new javafx.geometry.BoundingBox(c.getMinX(), c.getMinY(), 1, c.getHeight() + 24)));
            if (inContent == null) return;
            double viewH = scroll.getViewportBounds().getHeight();
            double total = content.getLayoutBounds().getHeight();
            if (total <= viewH) return;
            double top = scroll.getVvalue() * (total - viewH);
            if (inContent.getMaxY() > top + viewH) scroll.setVvalue((inContent.getMaxY() - viewH) / (total - viewH));
            else if (inContent.getMinY() < top) scroll.setVvalue(inContent.getMinY() / (total - viewH));
        }));
    }

    /** Wraps a label at most {@code lines} lines, ending with an ellipsis: maxLines. */
    static void clampLines(Label label, int lines, double lineSpacing) {
        label.setWrapText(true);
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        Runnable fit = () -> {
            Text t = new Text("Ag");
            t.setFont(label.getFont());
            double line = t.getLayoutBounds().getHeight() + lineSpacing;
            label.setMaxHeight(Math.ceil(line * lines + 1));
        };
        label.fontProperty().addListener((o, a, b) -> fit.run());
        fit.run();
    }

    /** True for the keys that mean "go back": Escape, and Alt+Left like a browser. */
    static boolean isBack(KeyEvent e) {
        return e.getCode() == KeyCode.ESCAPE || (e.isAltDown() && e.getCode() == KeyCode.LEFT);
    }

    /** Material tabs: equal labels with an indicator under the chosen one. */
    static final class Tabs extends HBox {
        final javafx.beans.property.IntegerProperty selected = new javafx.beans.property.SimpleIntegerProperty(0);

        Tabs(String color, String... labels) {
            getStyleClass().add("tabs");
            for (int i = 0; i < labels.length; i++) {
                int index = i;
                Label text = new Label(labels[i]);
                text.getStyleClass().add("tab-label");
                Region bar = new Region();
                bar.getStyleClass().add("tab-indicator");
                bar.setStyle("-fx-background-color: " + Theme.ref(color) + ";");
                StackPane tab = new StackPane(text, bar);
                StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
                tab.getStyleClass().add("tab-cell");
                tab.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(tab, Priority.ALWAYS);
                tab.setOnMouseClicked(e -> selected.set(index));
                getChildren().add(tab);
            }
            Runnable show = () -> {
                for (int i = 0; i < getChildren().size(); i++) {
                    StackPane tab = (StackPane) getChildren().get(i);
                    boolean on = i == selected.get();
                    tab.getChildren().get(1).setVisible(on);
                    tab.getChildren().get(0).setStyle("-fx-text-fill: " + Theme.ref(on ? color : "text_secondary") + ";");
                }
            };
            selected.addListener((o, a, b) -> show.run());
            show.run();
        }
    }

    /** Runs {@code step} with 0..1 over {@code ms}: the phone's ValueAnimator. */
    static javafx.animation.Transition animate(double ms, Interpolator interpolator, java.util.function.DoubleConsumer step) {
        javafx.animation.Transition t = new javafx.animation.Transition() {
            {
                setCycleDuration(Duration.millis(ms));
                setInterpolator(interpolator);
            }

            @Override
            protected void interpolate(double frac) {
                step.accept(frac);
            }
        };
        t.play();
        return t;
    }
}
