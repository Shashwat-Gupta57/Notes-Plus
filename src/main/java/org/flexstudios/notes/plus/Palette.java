package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ctrl+K: one search over notes and commands. Open a note, start one (or one from a template), go to
 * a folder or filter, change the theme, without the mouse. Never offered over the hidden spaces
 * ({@link NotesApp} only opens it on the notes grid or a note), and it lists notes only.
 *
 * Matching is the web's palette.js, case for case: every typed letter in order, ranked higher at the
 * start and after a space, a run of letters together higher still; words inside a note rank below its name.
 */
final class Palette {

    private Palette() {}

    private static final int MAX_ROWS = 50;

    /** One thing it can do. */
    private record Item(String group, String label, String detail, Icons.Icon icon, String accent,
                        String words, long when, Runnable run) {}

    /** A match: how good, and which letters of the label (for bold). */
    record Match(int score, int[] at) {}

    /**
     * How well {@code query} matches {@code text}, or null. Letters in order; a run in a row, a start
     * or a word start count more; nearer the start counts more.
     */
    static Match match(String query, String text) {
        String q = query.toLowerCase(Locale.ROOT).trim(), t = text.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return new Match(0, new int[0]);
        int i = t.indexOf(q);
        if (i >= 0) {
            int[] at = new int[q.length()];
            for (int k = 0; k < at.length; k++) at[k] = i + k;
            boolean wordStart = i == 0 || !Character.isLetterOrDigit(t.charAt(i - 1));
            return new Match(1000 + (i == 0 ? 300 : wordStart ? 200 : 0) - Math.min(i, 100) - Math.min(t.length(), 200) / 10, at);
        }
        int[] at = new int[q.length()];
        int score = 0, from = 0, last = -2;
        for (int k = 0; k < q.length(); k++) {
            char c = q.charAt(k);
            if (c == ' ') {
                at[k] = -1;
                continue;
            }
            int found = t.indexOf(c, from);
            if (found < 0) return null;
            at[k] = found;
            score += 10;
            if (found == last + 1) score += 15;
            if (found == 0 || !Character.isLetterOrDigit(t.charAt(found - 1))) score += 20;
            last = found;
            from = found + 1;
        }
        return new Match(score - Math.min(t.length(), 200) / 10, at);
    }

    /** Opens it in {@code main}'s window (null: a note's own window, so no filters to go to). */
    static void show(MainView main) {
        NoteStore.load(NoteStore::all, notes -> open(main, notes));
    }

    private static void open(MainView main, List<NoteEntity> every) {
        // Hidden notes never show here: not their titles, folders or templates.
        List<NoteEntity> notes = new ArrayList<>();
        for (NoteEntity n : every) if (!n.isHidden()) notes.add(n);
        List<Item> items = new ArrayList<>();
        for (NoteEntity n : notes) {
            if (n.isDeleted()) continue;
            NoteData.Kind kind = NoteData.kind(n.getType());
            boolean locked = NoteLock.isLocked(n.getData());
            String title = n.getTitle() == null ? "" : n.getTitle().trim();
            String words = locked || n.getContent() == null ? "" : n.getContent();
            if (title.isEmpty()) title = locked ? "Locked note" : firstLine(words, kind.label());
            String where = n.isTemplate() ? "Template" : n.isArchived() ? "Archived"
                    : n.getFolder() != null ? n.getFolder() : kind.label();
            NoteEntity note = n;
            items.add(new Item(n.isTemplate() ? "Templates" : "Notes", title, where + "  ·  " + NoteCard.relative(n.getTimestamp()),
                    n.isTemplate() ? Icons.TEMPLATE : kind.icon(), kind.accent(), words.length() > 4000 ? words.substring(0, 4000) : words,
                    n.getTimestamp(), () -> go(main, () -> NotesApp.open(NoteData.editor(note.getType(), note.getId())))));
        }
        // The open note's backlinks: the notes that link to it.
        NoteEditor open = NotesApp.topEditor();
        if (open != null && open.note != null && open.note.getId() > 0) {
            for (NoteEntity b : NoteLinks.backlinks(open.note, notes)) {
                NoteData.Kind kind = NoteData.kind(b.getType());
                String t = b.getTitle() == null || b.getTitle().isBlank() ? kind.label() : b.getTitle().trim();
                items.add(new Item("Linking here", t, "Links to this note", kind.icon(), kind.accent(), "backlink linked",
                        b.getTimestamp() + 1, () -> go(main, () -> NotesApp.open(NoteData.editor(b.getType(), b.getId())))));
            }
        }
        for (NoteData.Kind kind : NoteData.KINDS) {
            items.add(new Item("New", "New " + kind.label().toLowerCase(Locale.getDefault()), kind.description(), kind.icon(), kind.accent(),
                    "", 0, () -> go(main, () -> NotesApp.open(NoteData.editor(kind.type(), -1)))));
        }
        for (NoteEntity t : Templates.of(notes)) {
            NoteData.Kind kind = NoteData.kind(t.getType());
            items.add(new Item("New", "New from template: " + Templates.name(t), kind.label() + " template", Icons.TEMPLATE,
                    kind.accent(), "", 0, () -> go(main, () -> MainView.newFromTemplate(t))));
        }
        if (main != null) {
            items.add(goTo(main, "All notes", null, Icons.EMPTY_NOTES, "brand_yellow"));
            for (String f : NoteFolders.all(notes)) items.add(goTo(main, f, "FOLDER:" + f, Icons.FOLDER, "brand_yellow"));
            if (!Templates.of(notes).isEmpty()) items.add(goTo(main, "Templates", "TEMPLATES", Icons.TEMPLATE, "tile_purple"));
            items.add(goTo(main, "Shared with me", "SHARED", Icons.PERSON, "tile_teal"));
            items.add(goTo(main, "Archived", "ARCHIVED", Icons.ARCHIVE, "text_secondary"));
            for (NoteData.Kind kind : NoteData.KINDS) items.add(goTo(main, kind.plural(), kind.type(), kind.icon(), kind.accent()));
            items.add(new Item("Other", "Search notes", "Ctrl+F", Icons.SEARCH, "text_secondary", "find", 0,
                    () -> go(main, main::startSearchFromPalette)));
        }
        for (int i = 0; i < Theme.MODES.length; i++) {
            String mode = Theme.MODES[i];
            boolean on = mode.equals(Theme.mode());
            items.add(new Item("Theme", "Theme: " + Theme.MODE_NAMES[i], on ? "In use" : "Colours", Icons.PALETTE, "tile_purple",
                    "appearance dark light colour color", 0, () -> Theme.setMode(mode)));
        }
        items.add(new Item("Other", "Account", Account.signedIn() ? "Profile, sync, sign out" : "Sign in to sync your notes",
                Icons.PERSON, "brand_yellow", "sign in profile sync", 0, () -> go(main, () -> NotesApp.open(new AccountView()))));
        items.add(new Item("Other", "Import notes…", "From Google Keep, Apple Notes or Markdown", Icons.SAVE, "tile_blue",
                "keep apple obsidian markdown takeout", 0, ImportExport::chooseImport));
        items.add(new Item("Other", "Export all notes…", "A zip of Markdown", Icons.SAVE, "tile_blue",
                "backup markdown zip download", 0, ImportExport::chooseExport));
        items.add(new Item("Other", "Keyboard shortcuts", "Ctrl+K, Ctrl+N, Ctrl+F…", Icons.SEARCH, "text_secondary",
                "keys help", 0, Palette::shortcuts));

        TextField field = new TextField();
        field.setPromptText("Search notes, or type a command…");
        field.getStyleClass().add("palette-field");
        VBox rows = new VBox();
        rows.getStyleClass().add("palette-rows");
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(430);
        scroll.setPrefViewportHeight(430);
        Label hint = Ui.label("↑↓ to move  ·  Enter to open  ·  Esc to close", 12, "secondary");
        hint.setPadding(new Insets(8, 16, 10, 16));
        VBox card = new VBox(field, scroll, hint);
        card.getStyleClass().add("palette");
        card.setMaxWidth(620);
        card.setMaxHeight(Double.MAX_VALUE);
        StackPane.setMargin(card, new Insets(72, 16, 0, 16));

        List<Item> shown = new ArrayList<>();
        List<HBox> shownRows = new ArrayList<>();
        int[] selected = {0};
        Ui.Modal[] modal = new Ui.Modal[1];
        Runnable[] render = new Runnable[1];

        Runnable runSelected = () -> {
            if (shown.isEmpty()) return;
            Item item = shown.get(Math.max(0, Math.min(selected[0], shown.size() - 1)));
            modal[0].dismiss();
            item.run().run();
        };
        java.util.function.IntConsumer select = i -> {
            if (shownRows.isEmpty()) return;
            selected[0] = Math.max(0, Math.min(i, shownRows.size() - 1));
            for (int k = 0; k < shownRows.size(); k++) {
                if (k == selected[0]) {
                    if (!shownRows.get(k).getStyleClass().contains("selected")) shownRows.get(k).getStyleClass().add("selected");
                } else {
                    shownRows.get(k).getStyleClass().remove("selected");
                }
            }
            HBox row = shownRows.get(selected[0]);
            double h = rows.getHeight() - scroll.getViewportBounds().getHeight();
            if (h > 0) {
                double top = row.getBoundsInParent().getMinY(), bottom = row.getBoundsInParent().getMaxY();
                double viewTop = scroll.getVvalue() * h, viewBottom = viewTop + scroll.getViewportBounds().getHeight();
                if (top < viewTop) scroll.setVvalue(top / h);
                else if (bottom > viewBottom) scroll.setVvalue((bottom - scroll.getViewportBounds().getHeight()) / h);
            }
        };

        render[0] = () -> {
            String q = field.getText().trim();
            List<Object[]> found = new ArrayList<>(); // {item, match, score}
            if (q.isEmpty()) {
                // Nothing typed: the newest notes, then the ways to start one.
                items.stream().filter(it -> it.group().equals("Linking here")).forEach(it -> found.add(new Object[]{it, new Match(0, new int[0]), 0}));
                items.stream().filter(it -> it.group().equals("Notes")).sorted(Comparator.comparingLong(Item::when).reversed())
                        .limit(6).forEach(it -> found.add(new Object[]{it, new Match(0, new int[0]), 0}));
                items.stream().filter(it -> it.group().equals("New"))
                        .forEach(it -> found.add(new Object[]{it, new Match(0, new int[0]), 0}));
            } else {
                for (Item it : items) {
                    Match m = match(q, it.label());
                    int score;
                    if (m != null) score = m.score();
                    else if (!it.words().isEmpty() && it.words().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                        m = new Match(0, new int[0]);
                        score = 100; // In its words, not its name.
                    } else continue;
                    found.add(new Object[]{it, m, score});
                }
                // Groups ordered by their best match; within a group, best first.
                Map<String, Integer> best = new LinkedHashMap<>();
                for (Object[] f : found) best.merge(((Item) f[0]).group(), (Integer) f[2], Math::max);
                found.sort((a, b) -> {
                    String ga = ((Item) a[0]).group(), gb = ((Item) b[0]).group();
                    if (!ga.equals(gb)) return Integer.compare(best.get(gb), best.get(ga));
                    return Integer.compare((Integer) b[2], (Integer) a[2]);
                });
            }
            rows.getChildren().clear();
            shown.clear();
            shownRows.clear();
            String group = null;
            for (Object[] f : found) {
                if (shown.size() >= MAX_ROWS) break;
                Item it = (Item) f[0];
                if (!it.group().equals(group)) {
                    group = it.group();
                    Label heading = Ui.label(group.toUpperCase(Locale.ROOT), 11, "secondary");
                    heading.setPadding(new Insets(10, 16, 4, 16));
                    rows.getChildren().add(heading);
                }
                HBox row = row(it, (Match) f[1]);
                int index = shown.size();
                row.setOnMouseMoved(e -> {
                    if (selected[0] != index) select.accept(index);
                });
                row.setOnMouseClicked(e -> {
                    selected[0] = index;
                    runSelected.run();
                });
                rows.getChildren().add(row);
                shown.add(it);
                shownRows.add(row);
            }
            if (shown.isEmpty()) {
                Label none = Ui.label("Nothing matches “" + q + "”", 14, "secondary");
                none.setPadding(new Insets(18, 16, 18, 16));
                rows.getChildren().add(none);
            }
            scroll.setVvalue(0);
            select.accept(0);
        };

        field.textProperty().addListener((o, a, b) -> render[0].run());
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN) {
                e.consume();
                select.accept(selected[0] + 1);
            } else if (e.getCode() == KeyCode.UP) {
                e.consume();
                select.accept(selected[0] - 1);
            } else if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                runSelected.run();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.K) {
                e.consume();
                modal[0].dismiss();
            }
        });
        render[0].run();
        modal[0] = Ui.present(card, Pos.TOP_CENTER, true, null);
        card.setOpacity(0);
        card.setTranslateY(-8);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(120), card);
        fade.setToValue(1);
        javafx.animation.TranslateTransition drop = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(140), card);
        drop.setToY(0);
        new javafx.animation.ParallelTransition(fade, drop).play();
        javafx.application.Platform.runLater(field::requestFocus);
    }

    private static Item goTo(MainView main, String label, String filter, Icons.Icon icon, String accent) {
        return new Item("Go to", label, "Show on the notes grid", icon, accent, "", 0, () -> go(main, () -> main.showFilter(filter)));
    }

    /** Leaves any open notes in this window (each saves as it goes), then does it. */
    private static void go(MainView main, Runnable then) {
        NotesApp.toHome();
        then.run();
    }

    private static HBox row(Item it, Match m) {
        StackPane tile = new StackPane(Ui.icon(it.icon(), 18, "white"));
        tile.setStyle("-fx-background-color: " + Theme.ref(it.accent()) + "; -fx-background-radius: 7;");
        tile.setMinSize(30, 30);
        tile.setMaxSize(30, 30);
        TextFlow label = new TextFlow();
        boolean[] bold = new boolean[it.label().length()];
        for (int a : m.at()) if (a >= 0 && a < bold.length) bold[a] = true;
        int start = 0;
        for (int k = 1; k <= bold.length; k++) {
            if (k == bold.length || bold[k] != bold[start]) {
                Text t = new Text(it.label().substring(start, k));
                t.getStyleClass().add(bold[start] ? "palette-hit" : "palette-text");
                label.getChildren().add(t);
                start = k;
            }
        }
        Label detail = Ui.label(it.detail(), 12, "secondary");
        VBox texts = new VBox(1, label, detail);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(12, tile, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("palette-row");
        return row;
    }

    private static String firstLine(String words, String fallback) {
        for (String line : words.split("\n")) {
            String t = Markdown.plain(line).trim();
            if (!t.isEmpty()) return t.length() > 80 ? t.substring(0, 80) + "…" : t;
        }
        return fallback;
    }

    private static void shortcuts() {
        Ui.dialog().title("Keyboard shortcuts").message(String.join("\n",
                "Ctrl+K   Search notes and commands",
                "Ctrl+N   New note",
                "Ctrl+F   Search the grid",
                "Ctrl+Shift+N   New window",
                "Ctrl+W   Close the note (or window)",
                "Ctrl+=  /  Ctrl+-  /  Ctrl+0   Zoom a note",
                "Ctrl+Enter   Tick a list item",
                "Ctrl+D   A list item's day",
                "Esc   Back")).positive("OK", null).show();
    }
}
