package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/** What the chat screens share: round avatars, times, the read-only banner, the Drive prompt. */
final class ChatUi {

    private ChatUi() {}

    /** A round photo over a coloured initial; the initial shows until (or unless) the photo loads. */
    static final class Avatar extends StackPane {
        private final Label initial = new Label();
        private final ImageView photo = new ImageView();
        private final double size;
        private String shown;

        Avatar(double size) {
            this.size = size;
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
            initial.setMinSize(size, size);
            initial.setAlignment(Pos.CENTER);
            photo.setFitWidth(size);
            photo.setFitHeight(size);
            photo.setPreserveRatio(false);
            photo.setClip(new Circle(size / 2, size / 2, size / 2));
            getChildren().addAll(initial, photo);
            setMouseTransparent(true);
        }

        /** {@code photoJson}: {"url"} or a chat photo's meta, or null. */
        void set(String name, String color, String photoJson) {
            String letter = name == null || name.replace("@", "").isEmpty() ? "?"
                    : name.replace("@", "").substring(0, 1).toUpperCase(Locale.getDefault());
            initial.setText(letter);
            initial.setStyle("-fx-background-color: " + Theme.ref(color) + "; -fx-background-radius: 100;"
                    + " -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.42) + "px;");
            if (Objects.equals(photoJson, shown)) return;
            shown = photoJson;
            photo.setImage(null);
            ChatMedia.loadAvatar(photoJson, image -> {
                if (Objects.equals(photoJson, shown)) photo.setImage(image);
            });
        }
    }

    /** "14:05" today, "Yesterday", the weekday this week, else the date. */
    static String when(long at) {
        if (at <= 0) return "";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        long days = (startOfDay(now) - startOfDay(then)) / 86_400_000L;
        if (days <= 0) return time(at);
        if (days == 1) return "Yesterday";
        if (days < 7) return new SimpleDateFormat("EEE", Locale.getDefault()).format(new Date(at));
        return new SimpleDateFormat(now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ? "d MMM" : "d MMM yyyy",
                Locale.getDefault()).format(new Date(at));
    }

    static String time(long at) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at));
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** The orange "open on another device" strip. */
    static Label readOnlyBanner() {
        Label banner = new Label();
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setPadding(new Insets(10, 16, 10, 16));
        banner.setStyle("-fx-background-color: " + Theme.ref("tile_orange") + "; -fx-text-fill: white; -fx-font-size: 14px;");
        banner.setVisible(false);
        banner.setManaged(false);
        return banner;
    }

    static void showBanner(Label banner, boolean show, String holder) {
        banner.setText("Open on " + holder + ". You can read here; it switches over when that device leaves.");
        banner.setVisible(show);
        banner.setManaged(show);
    }

    /** Photos, videos and backgrounds travel through the sender's own Drive. True if it's missing (and says so). */
    static boolean needsDrive() {
        if (Account.driveEmail() != null) return false;
        Ui.dialog().title("Connect Google Drive first")
                .message("Photos, videos, voice messages and backgrounds are encrypted and stored in your own Google Drive. "
                        + "Connect it in your Notes+ account.")
                .positive("Open account", () -> NotesApp.open(new AccountView()))
                .negative("Not now", null).width(440).show();
        return true;
    }

    /** A round button of the compose bar. */
    static javafx.scene.control.Button round(Icons.Icon icon, double size, String background, String iconColor, String tip,
                                             Runnable action) {
        javafx.scene.control.Button b = new javafx.scene.control.Button(null, Ui.icon(icon, Math.round(size * 0.5), iconColor));
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().setAll("chat-round");
        b.setStyle("-fx-background-color: " + Theme.ref(background) + ";");
        b.setAlignment(Pos.CENTER);
        b.setMinSize(size, size);
        b.setPrefSize(size, size);
        b.setMaxSize(size, size);
        b.setFocusTraversable(false);
        if (tip != null) Ui.tooltip(b, tip);
        b.setOnAction(e -> action.run());
        return b;
    }

    static Node spacer(double h) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinHeight(h);
        return r;
    }
}
