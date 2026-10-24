package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;

import java.util.List;

/**
 * A folder on the All grid: its name and count, and its newest notes as small cards: one
 * filling the tile, two halves, one tall beside two, or four in a square (the last saying how
 * many more there are). Each small card is the note's real card, scaled down. A click opens
 * the folder; right-click renames or removes it.
 */
final class FolderTile extends VBox {

    private static final double CELLS_HEIGHT = 210, GAP = 6, SCALE = 0.55;

    private final List<NoteEntity> notes;
    private final Pane cells = new Pane();
    private double builtFor = -1;

    FolderTile(String name, List<NoteEntity> notes, Runnable open, Runnable manage) {
        this.notes = notes;
        getStyleClass().add("note-card");
        setStyle("-fx-background-color: " + Theme.ref("divider") + ", " + Theme.ref("app_surface") + ";");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(36);
        clip.setArcHeight(36);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        setPadding(new Insets(14));
        setSpacing(10);

        Label title = new Label(name);
        title.getStyleClass().add("card-title");
        Ui.clampLines(title, 1, 0);
        Label count = new Label(notes.isEmpty() ? "Empty" : notes.size() + (notes.size() == 1 ? " note" : " notes"));
        count.getStyleClass().add("card-meta");
        HBox head = new HBox(8, Ui.icon(Icons.FOLDER, 22, "brand_yellow"), title, Ui.spacer(), count);
        head.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(head);
        if (!notes.isEmpty()) {
            cells.setMinHeight(CELLS_HEIGHT);
            cells.setPrefHeight(CELLS_HEIGHT);
            cells.setMaxHeight(CELLS_HEIGHT);
            getChildren().add(cells);
            cells.widthProperty().addListener((o, a, b) -> build(b.doubleValue()));
        }
        setStyle(getStyle() + " -fx-cursor: hand;");
        setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()) open.run();
        });
        setOnContextMenuRequested(e -> manage.run());
        Ui.tooltip(this, "Open the folder · right-click to rename or remove");
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    /** Lays the small cards out for {@code width}: x, y, w, h per card. */
    private void build(double width) {
        if (width <= 0 || Math.abs(width - builtFor) < 1) return;
        builtFor = width;
        int n = Math.min(4, notes.size());
        double half = (width - GAP) / 2, halfH = (CELLS_HEIGHT - GAP) / 2;
        double[][] spots = switch (n) {
            case 1 -> new double[][]{{0, 0, width, CELLS_HEIGHT}};
            case 2 -> new double[][]{{0, 0, half, CELLS_HEIGHT}, {half + GAP, 0, half, CELLS_HEIGHT}};
            case 3 -> new double[][]{{0, 0, half, CELLS_HEIGHT}, {half + GAP, 0, half, halfH}, {half + GAP, halfH + GAP, half, halfH}};
            default -> new double[][]{{0, 0, half, halfH}, {half + GAP, 0, half, halfH}, {0, halfH + GAP, half, halfH},
                    {half + GAP, halfH + GAP, half, halfH}};
        };
        cells.getChildren().clear();
        NoteCard.Listener none = new NoteCard.Listener() {
            @Override
            public void onOpen(NoteEntity note) {
            }

            @Override
            public void onOptions(NoteEntity note) {
            }
        };
        for (int i = 0; i < n; i++) {
            double[] s = spots[i];
            NoteCard card = new NoteCard(notes.get(i), none);
            card.setMouseTransparent(true);
            double w = s[2] / SCALE;
            card.setMinWidth(w);
            card.setPrefWidth(w);
            card.setMaxWidth(w);
            card.getTransforms().add(new Scale(SCALE, SCALE, 0, 0));
            StackPane cell = new StackPane(new Group(card));
            StackPane.setAlignment(cell.getChildren().get(0), Pos.TOP_LEFT);
            cell.setLayoutX(s[0]);
            cell.setLayoutY(s[1]);
            cell.resize(s[2], s[3]);
            cell.setMinSize(s[2], s[3]);
            cell.setMaxSize(s[2], s[3]);
            Rectangle clip = new Rectangle(s[2], s[3]);
            clip.setArcWidth(20);
            clip.setArcHeight(20);
            cell.setClip(clip);
            String tint = NoteData.tint(notes.get(i).getColor(), "gallery_background");
            cell.setStyle("-fx-background-color: " + Theme.ref(tint) + "; -fx-background-radius: 10;");
            cells.getChildren().add(cell);
            if (i == 3 && notes.size() > 4) {
                Label more = new Label("+" + (notes.size() - 3));
                more.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: white;");
                StackPane shade = new StackPane(more);
                shade.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 10;");
                shade.setLayoutX(s[0]);
                shade.setLayoutY(s[1]);
                shade.resize(s[2], s[3]);
                shade.setMinSize(s[2], s[3]);
                shade.setMaxSize(s[2], s[3]);
                cells.getChildren().add(shade);
            }
        }
    }
}
