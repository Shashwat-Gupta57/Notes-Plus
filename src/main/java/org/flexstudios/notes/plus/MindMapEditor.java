package org.flexstudios.notes.plus;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mind map notes: a central idea with branches. The phone's MindMapActivity.
 *
 * Click selects, "Add idea" (or Tab) grows a branch from the selection, double-click (or F2)
 * renames, drag moves, right-click opens the idea's options (colour, fold, link a note,
 * connect to another idea, delete). The wheel zooms, dragging empty space pans, double-click
 * on empty space shows everything, and Tidy lays the whole map out as a balanced tree.
 */
final class MindMapEditor extends NoteEditor {

    private final MindMapCanvas map = new MindMapCanvas(true);
    private final TextField title = new TextField();

    MindMapEditor(String type, int noteId) {
        super(type, noteId);
        title.getStyleClass().addAll("bare", "bold");
        title.setStyle("-fx-font-size: 24px;");
        Links.install(title);
        title.setPromptText("Mind map title");
        title.setOnAction(e -> map.requestFocus());

        map.listener = new MindMapCanvas.Listener() {
            @Override
            public void onRename(NoteData.MindNode node) {
                name(node, false);
            }

            @Override
            public void onOptions(NoteData.MindNode node) {
                showOptions(node);
            }

            @Override
            public void onOpenLink(NoteData.MindNode node) {
                openLinkedNote(node);
            }

            @Override
            public void onConnected() {
                Ui.snackbar("Connected", Ui.SHORT);
            }
        };
        map.addEventFilter(KeyEvent.KEY_PRESSED, this::mapKeys);

        Label hint = Ui.label("Double-click to rename · right-click for more · drag to move · Tab adds an idea · paste a picture into an idea",
                12, "secondary");
        hint.setOpacity(0.6);
        hint.setMouseTransparent(true);
        StackPane.setAlignment(hint, Pos.TOP_CENTER);
        StackPane.setMargin(hint, new Insets(4, 0, 0, 0));

        tidyButton = Ui.miniFab(Icons.NOTE_MINDMAP, "Tidy up the layout", map::tidy);
        HBox tools = new HBox(10,
                Ui.miniFab(Icons.CENTER, "Show everything", () -> map.fit(true)),
                tidyButton,
                Ui.miniFab(Icons.SAVE, "Save as a picture", this::saveAsImage));
        tools.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        tools.setPickOnBounds(false);
        StackPane.setAlignment(tools, Pos.BOTTOM_LEFT);
        StackPane.setMargin(tools, new Insets(20));

        add = Ui.extendedFab("Add idea", Icons.ADD, () -> {
            NoteData.MindNode parent = map.selected();
            addChild(parent != null ? parent : map.root());
        });
        Ui.tooltip(add, "Add idea (Tab)");
        StackPane.setAlignment(add, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(add, new Insets(24));

        this.hint = hint;
        StackPane stage = new StackPane(map, hint, tools, add);
        BorderPane body = new BorderPane(stage);
        VBoxed top = new VBoxed(title);
        body.setTop(top);
        // Last: a new note is handed to onNoteLoaded immediately.
        setUpNote(body);
    }

    /** The title row, padded like the phone's. */
    private static final class VBoxed extends StackPane {
        VBoxed(javafx.scene.Node child) {
            super(child);
            setPadding(new Insets(0, 20, 8, 20));
        }
    }

    @Override
    protected void onNoteLoaded(NoteEntity note) {
        title.setText(note.getTitle());
        NoteData.MindMap loaded = NoteData.mindMap(note.getData());
        if (loaded.nodes.isEmpty()) loaded.nodes.add(new NoteData.MindNode(1, -1, "", 0, 0));
        map.setMap(loaded);
        if (note.getId() > 0) map.setFolder(NoteData.dir(note.getId()));
        Platform.runLater(() -> {
            map.fit(false);
            map.requestFocus();
            if (note.getId() <= 0) name(map.root(), true);
        });
    }

    @Override
    protected boolean collect(NoteEntity note) {
        note.setTitle(title.getText().trim());
        note.setData(NoteData.mindMapJson(map.model));
        StringBuilder text = new StringBuilder();
        for (NoteData.MindNode n : map.model.nodes) {
            if (!n.text.isEmpty()) text.append(n.text).append('\n');
            if (!n.sub.isEmpty()) text.append(n.sub).append('\n');
        }
        note.setContent(text.toString().trim());
        return !text.toString().trim().isEmpty() || !note.getTitle().isEmpty();
    }

    private Button add, tidyButton;
    private Label hint;

    /** A map sent to me: nothing to add, move, rename, tidy or paste in. */
    @Override
    protected void lockForReading() {
        super.lockForReading();
        map.locked = true;
        for (javafx.scene.Node n : new javafx.scene.Node[]{add, tidyButton}) {
            n.setVisible(false);
            n.setManaged(false);
        }
        hint.setText("Drag to look around · scroll to zoom · Ctrl+click opens links");
    }

    /** Tab: a child of the selection. Enter: a sibling. F2: rename. Delete: the branch. */
    private void mapKeys(KeyEvent e) {
        if (readOnly) return;
        NoteData.MindNode selected = map.selected();
        if (e.isShortcutDown() && e.getCode() == KeyCode.V) {
            Clipboard cb = Clipboard.getSystemClipboard();
            File file = cb.hasFiles() ? cb.getFiles().stream().filter(RichBody::isPicture).findFirst().orElse(null) : null;
            Image image = file == null && cb.hasImage() ? cb.getImage() : null;
            if (file == null && image == null) return;
            e.consume();
            if (selected != null) {
                attach(selected, file, image);
            } else {
                // Nothing selected: the picture becomes a new idea off the centre.
                NoteData.MindNode idea = map.addChild(map.root());
                attach(idea, file, image);
            }
        } else if (e.getCode() == KeyCode.TAB) {
            e.consume();
            addChild(selected != null ? selected : map.root());
        } else if (e.getCode() == KeyCode.ENTER && selected != null) {
            e.consume();
            NoteData.MindNode parent = map.find(selected.parent);
            if (parent != null) addChild(parent);
            else name(selected, false);
        } else if (e.getCode() == KeyCode.F2 && selected != null) {
            e.consume();
            name(selected, false);
        } else if ((e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE)
                && selected != null && selected.parent >= 0) {
            e.consume();
            map.removeBranch(selected);
        } else if (e.isShortcutDown() && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.ADD)) {
            e.consume();
            map.zoomAt(map.getWidth() / 2, map.getHeight() / 2, 1.2);
        } else if (e.isShortcutDown() && (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT)) {
            e.consume();
            map.zoomAt(map.getWidth() / 2, map.getHeight() / 2, 1 / 1.2);
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.DIGIT0) {
            e.consume();
            map.fit(true);
        }
    }

    @Override
    protected Boolean wrapText() {
        return map.model.wrap;
    }

    @Override
    protected void setWrapText(boolean wrap) {
        map.model.wrap = wrap;
        map.refresh();
    }

    // ================================================================ ideas

    private void addChild(NoteData.MindNode parent) {
        if (parent.collapsed) map.toggleCollapsed(parent);
        name(map.addChild(parent), true);
    }

    /**
     * Names an idea. For a new one, "Next idea" saves it and starts a sibling straight away,
     * so a brainstorm never leaves the keyboard.
     */
    private void name(NoteData.MindNode node, boolean isNew) {
        name(node, isNew, false);
    }

    /** {@code behind}: the idea was just put between another and its parent; abandoned, it unwinds. */
    private void name(NoteData.MindNode node, boolean isNew, boolean behind) {
        boolean root = node.parent < 0;
        Runnable discard = () -> {
            if (isNew && !root && node.text.isEmpty()) {
                if (behind) map.unwrap(node);
                else map.removeBranch(node);
            }
            map.requestFocus();
        };
        Consumer<String> apply = text -> {
            node.text = text;
            map.select(node);
            map.refresh();
            map.requestFocus();
        };
        Ui.prompt(root ? "Central idea" : isNew ? "New idea" : "Rename", null,
                root ? "What is this map about?" : "An idea", node.text, "Done",
                apply, discard,
                isNew && !root ? "Next idea" : null,
                text -> {
                    apply.accept(text);
                    NoteData.MindNode parent = map.find(node.parent);
                    if (parent != null) name(map.addChild(parent), true);
                });
    }

    private void showOptions(NoteData.MindNode node) {
        boolean root = node.parent < 0;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Rename");
        actions.add(() -> name(node, false));
        labels.add("Add idea");
        actions.add(() -> addChild(node));
        if (!root) {
            labels.add("Add idea behind");
            actions.add(() -> {
                NoteData.MindNode behind = map.insertBehind(node);
                if (behind != null) name(behind, true, true);
            });
        }
        labels.add(node.sub.isEmpty() ? "Add sub-text" : "Edit sub-text");
        actions.add(() -> editSub(node));
        labels.add("Colour");
        actions.add(() -> pickColor(node));
        if (map.hasChildren(node)) {
            labels.add(node.collapsed ? "Unfold branch" : "Fold branch");
            actions.add(() -> map.toggleCollapsed(node));
        }
        if (node.hasLink()) {
            labels.add("Open linked note");
            actions.add(() -> openLinkedNote(node));
            labels.add("Remove note link");
            actions.add(() -> {
                node.setLink(null);
                map.draw();
            });
        } else {
            labels.add("Link to a note…");
            actions.add(() -> pickNoteToLink(node));
        }
        labels.add(node.image == null ? "Add a picture…" : "Change picture…");
        actions.add(() -> choosePicture(node));
        if (node.image != null) {
            labels.add("View picture");
            actions.add(() -> MediaViewer.open(NoteData.dir(note.getId()).resolve(node.image), false));
            labels.add("Remove picture");
            actions.add(() -> {
                node.image = null;
                map.refresh();
            });
        }
        labels.add("Connect to another idea…");
        actions.add(() -> {
            map.startConnecting(node);
            Ui.snackbar("Click the idea to connect to", Ui.LONG, "Cancel", () -> map.startConnecting(null), null);
        });
        if (map.hasCrossLinks(node)) {
            labels.add("Remove its connections");
            actions.add(() -> map.removeCrossLinks(node));
        }
        if (!root) {
            labels.add("Delete branch");
            actions.add(() -> map.removeBranch(node));
        }
        Ui.dialog().title(node.text.isEmpty() ? "Idea" : node.text).items(labels, actions).show();
    }

    /** Sub-text: more about an idea, shown smaller under it. Empty removes it. */
    private void editSub(NoteData.MindNode node) {
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(node.sub);
        area.setPromptText("More about this idea");
        area.setWrapText(true);
        area.setPrefRowCount(4);
        Ui.dialog().title("Sub-text").content(area).width(420)
                .positive("Done", () -> {
                    node.sub = area.getText().trim();
                    map.select(node);
                    map.refresh();
                    map.requestFocus();
                })
                .negative("Cancel", map::requestFocus)
                .show();
        Platform.runLater(area::requestFocus);
    }

    private void pickColor(NoteData.MindNode node) {
        String[] colorNames = {"Blue", "Green", "Orange", "Purple", "Red", "Teal", "Grey"};
        String[] names = new String[colorNames.length + 1];
        names[0] = node.parent < 0 ? "Yellow (default)" : "Same as its branch";
        System.arraycopy(colorNames, 0, names, 1, colorNames.length);
        Ui.dialog().title("Colour").singleChoice(names, node.color, which -> {
            node.color = which;
            map.refresh();
        }).show();
    }

    // ============================================================== pictures

    private void choosePicture(NoteData.MindNode node) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add a picture");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures",
                "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
        File file = chooser.showOpenDialog(NotesApp.stage());
        if (file != null) attach(node, file, null);
    }

    /** Keeps the picture in the note's folder and shows it in the idea. */
    private void attach(NoteData.MindNode node, File file, Image image) {
        String ext = file == null ? ".png" : file.getName().substring(Math.max(0, file.getName().lastIndexOf('.'))).toLowerCase();
        String name = "IMG_" + System.currentTimeMillis() + "_" + node.id + ext;
        var buffered = image == null ? null : SwingFXUtils.fromFXImage(image, null);
        withFolder(dir -> NoteStore.load(s -> {
            try {
                if (file != null) java.nio.file.Files.copy(file.toPath(), dir.resolve(name));
                else ImageIO.write(buffered, "png", dir.resolve(name).toFile());
                return dir;
            } catch (Exception ex) {
                return null;
            }
        }, saved -> {
            if (saved == null) {
                Ui.toast("Couldn't add that picture");
                return;
            }
            map.setFolder(saved);
            node.image = name;
            map.select(node);
            map.refresh();
        }));
    }

    /** Obsidian-style: an idea can point at any other note and open it. */
    private void pickNoteToLink(NoteData.MindNode node) {
        NoteStore.load(NoteStore::all, all -> {
            List<NoteEntity> others = new ArrayList<>();
            for (NoteEntity n : all) if (note == null || n.getId() != note.getId()) others.add(n);
            if (others.isEmpty()) {
                Ui.toast("No other notes to link yet");
                return;
            }
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (NoteEntity n : others) {
                String t = n.getTitle() == null || n.getTitle().isEmpty() ? "Untitled" : n.getTitle();
                labels.add(NoteData.kind(n.getType()).label() + "  ·  " + t);
                actions.add(() -> {
                    node.setLink(n);
                    map.draw();
                });
            }
            Ui.dialog().title("Link to a note").items(labels, actions).show();
        });
    }

    private void openLinkedNote(NoteData.MindNode node) {
        int id = node.link;
        String uuid = node.linkUuid;
        NoteStore.load(s -> {
            NoteEntity found = uuid != null ? s.byUuid(uuid) : s.byId(id);
            return found == null || found.isDeleted() ? null : found;
        }, target -> {
            if (target == null) {
                Ui.toast("That note no longer exists");
                node.setLink(null);
                map.draw();
                return;
            }
            NotesApp.open(NoteData.editor(target.getType(), target.getId()));
        });
    }

    /** Renders the whole map and saves it as a PNG where the user chooses. */
    private void saveAsImage() {
        WritableImage image = map.renderAll();
        if (image == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save mind map as a picture");
        String name = title.getText().trim().isEmpty() ? "mind-map" : title.getText().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        chooser.setInitialFileName(name + ".png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG picture", "*.png"));
        File out = chooser.showSaveDialog(NotesApp.stage());
        if (out == null) return;
        var buffered = SwingFXUtils.fromFXImage(image, null);
        NoteStore.IO.execute(() -> {
            try {
                ImageIO.write(buffered, "png", out);
                Platform.runLater(() -> Ui.toast("Saved " + out.getName()));
            } catch (Exception e) {
                Platform.runLater(() -> Ui.toast("Couldn't save the picture"));
            }
        });
    }
}
