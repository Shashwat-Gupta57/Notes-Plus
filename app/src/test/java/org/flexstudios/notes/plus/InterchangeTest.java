package org.flexstudios.notes.plus;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Notes in from Keep, Apple Notes and Markdown, and out to Markdown and back. The desktop's test is the same. */
public class InterchangeTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private int named;
    private final Interchange.Namer namer = (prefix, ext) -> prefix + "_" + (++named) + "." + ext;

    private static Interchange.Entry file(String path, String text) {
        return new Interchange.Entry(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private Interchange.Found read(Interchange.Entry... entries) {
        return Interchange.read(Arrays.asList(entries), namer, 1_000L);
    }

    private static Interchange.Note titled(Interchange.Found found, String title) {
        for (Interchange.Note n : found.notes) if (n.title.equals(title)) return n;
        throw new AssertionError("No note " + title);
    }

    @Test
    public void readsGoogleKeep() {
        Interchange.Found found = read(
                file("Takeout/Keep/Shopping.json", "{\"color\":\"GREEN\",\"isTrashed\":false,\"isPinned\":true,\"isArchived\":false,"
                        + "\"title\":\"Shopping\",\"userEditedTimestampUsec\":1700000000000000,\"createdTimestampUsec\":1690000000000000,"
                        + "\"listContent\":[{\"text\":\"Milk\",\"isChecked\":false},{\"text\":\"Eggs\",\"isChecked\":true}],"
                        + "\"labels\":[{\"name\":\"Home\"}]}"),
                file("Takeout/Keep/Idea.json", "{\"color\":\"RED\",\"isTrashed\":false,\"isPinned\":false,\"isArchived\":true,"
                        + "\"title\":\"Idea\",\"userEditedTimestampUsec\":1700000000000000,\"textContent\":\"A garden\\non the roof\","
                        + "\"attachments\":[{\"filePath\":\"roof.png\",\"mimetype\":\"image/png\"}]}"),
                file("Takeout/Keep/Old.json", "{\"isTrashed\":true,\"title\":\"Old\",\"textContent\":\"gone\",\"userEditedTimestampUsec\":1}"),
                new Interchange.Entry("Takeout/Keep/roof.png", PNG),
                file("Takeout/Keep/Labels.txt", "Home"));
        Interchange.Note list = titled(found, "Shopping");
        assertEquals(NoteData.LIST, list.type);
        assertTrue(list.pinned);
        assertEquals(2, list.color);
        assertEquals("Home", list.folder);
        assertEquals(1_700_000_000_000L, list.edited);
        List<NoteData.TodoItem> items = NoteData.todos(list.data);
        assertEquals("Milk", items.get(0).text);
        assertTrue(items.get(1).done);
        Interchange.Note idea = titled(found, "Idea");
        assertTrue(idea.archived);
        assertEquals(8, idea.color);
        assertEquals("A garden\non the roof\n\n![[IMG_1.png]]", idea.content);
        assertArrayEquals(PNG, idea.files.get("IMG_1.png"));
        assertEquals(1, found.trashed);
        assertTrue(found.summary().startsWith("Found 3 notes"));
    }

    @Test
    public void readsMarkdownFolders() {
        Interchange.Found found = read(
                file("Vault/Work/Plan.md", "---\ntitle: The plan\nupdated: 2026-11-03T10:00:00Z\ntags: [a]\n---\n# Not the title\nSee [[Trip to Japan]].\n\n![[pic.png]]\n"),
                file("Vault/Trip to Japan.md", "# Trip to Japan\n\nTokyo, then Kyoto."),
                file("Vault/Todo.md", "- [ ] Book flights 📅 2026-11-09 ⏰ 7:30 🔁 every mon, wed\n- [x] Passport\n"),
                new Interchange.Entry("Vault/attachments/pic.png", PNG),
                new Interchange.Entry("Vault/.obsidian/app.json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, found.notes.size());
        Interchange.Note plan = titled(found, "The plan");
        assertEquals("Work", plan.folder);
        assertEquals(Interchange.date("2026-11-03T10:00:00Z"), plan.edited);
        assertEquals("# Not the title\nSee [[Trip to Japan]].\n\n![[IMG_1.png]]", plan.content);
        Interchange.Note trip = titled(found, "Trip to Japan");
        assertNull(trip.folder);
        assertEquals("Tokyo, then Kyoto.", trip.content);
        Interchange.Note todo = titled(found, "Todo");
        assertEquals(NoteData.LIST, todo.type);
        NoteData.TodoItem book = NoteData.todos(todo.data).get(0);
        assertEquals("Book flights", book.text);
        assertEquals("2026-11-09", book.due);
        assertEquals("07:30", book.time);
        assertEquals("weekly:1,3", book.repeat);
    }

    @Test
    public void readsAppleNotesHtml() {
        String html = "<html><head><title>Recipes</title><style>p{}</style></head><body>"
                + "<h1>Recipes</h1><div><b>Pancakes</b> are <i>easy</i> &amp; quick</div>"
                + "<ul><li>Flour</li><li>Milk</li></ul>"
                + "<ul class=\"checklist\"><li class=\"checked\">Buy eggs</li><li>Whisk</li></ul>"
                + "<div><a href=\"https://example.com/pancakes\">the video</a></div>"
                + "<img src=\"data:image/png;base64,iVBORw0KGgo=\"></body></html>";
        Interchange.Found found = read(file("iCloud Notes/Food/Recipes.html", html), file("iCloud Notes/Notes/Call mum.txt", "Sunday"));
        Interchange.Note n = titled(found, "Recipes");
        assertNull(titled(found, "Call mum").folder);
        assertEquals("Recipes", n.title);
        assertEquals("Food", n.folder);
        assertTrue(n.content, n.content.contains("**Pancakes** are *easy* & quick"));
        assertTrue(n.content, n.content.contains("- Flour\n- Milk"));
        assertTrue(n.content, n.content.contains("- [x] Buy eggs\n- [ ] Whisk"));
        assertTrue(n.content, n.content.contains("the video (https://example.com/pancakes)"));
        assertTrue(n.content, n.content.endsWith("![[IMG_1.png]]"));
        assertEquals(8, n.files.get("IMG_1.png").length);
    }

    @Test
    public void exportReadsBackAsTheSameNotes() {
        List<Interchange.Note> notes = new ArrayList<>();
        Interchange.Note text = note(NoteData.TEXT, "Meeting", "Agenda\n\n![[IMG_9.png]]\n\nSee [[Plan]]");
        text.folder = "Work";
        text.pinned = true;
        text.color = 3;
        text.edited = Interchange.date("2026-11-03T09:00:00Z");
        notes.add(text);
        NoteData.TodoItem gym = new NoteData.TodoItem("Gym", false);
        gym.due = "2026-11-09";
        gym.time = "07:30";
        gym.repeat = "weekly:1,3,5";
        notes.add(note(NoteData.LIST, "Week", "Gym\nMilk", NoteData.todosJson(Arrays.asList(gym, new NoteData.TodoItem("Milk", true)))));
        Interchange.Note old = note(NoteData.TEXT, "Old idea", "Was good");
        old.archived = true;
        notes.add(old);
        Interchange.Note tpl = note(NoteData.TEXT, "Weekly review", "- [ ] Inbox zero");
        tpl.template = true;
        notes.add(tpl);
        notes.add(note(NoteData.MINDMAP, "Trip", "Trip\nTokyo",
                "{\"nodes\":[{\"id\":1,\"parent\":-1,\"text\":\"Trip\",\"x\":0,\"y\":0,\"sub\":\"Spring\"},{\"id\":2,\"parent\":1,\"text\":\"Tokyo\",\"x\":1,\"y\":1}],\"links\":[]}"));
        notes.add(note(NoteData.JOURNAL, "Monday", "Rained", "{\"day\":" + Interchange.date("2026-11-02") + ",\"mood\":\"🙂\",\"media\":[]}"));
        Interchange.Note secret = note(NoteData.TEXT, "Passwords", "");
        secret.locked = true;
        notes.add(secret);

        Map<String, byte[]> files = new HashMap<>();
        files.put("IMG_9.png", PNG);
        List<Interchange.Entry> zip = Interchange.write(notes, (n, name) -> files.get(name), "Notes+ 2026-11-03");
        List<String> paths = new ArrayList<>();
        for (Interchange.Entry e : zip) paths.add(e.path);
        assertTrue(paths.toString(), paths.contains("Notes+ 2026-11-03/Work/Meeting.md"));
        assertTrue(paths.contains("Notes+ 2026-11-03/Archived/Old idea.md"));
        assertTrue(paths.contains("Notes+ 2026-11-03/Templates/Weekly review.md"));
        assertTrue(paths.contains("Notes+ 2026-11-03/attachments/IMG_9.png"));
        assertTrue(paths.contains("Notes+ 2026-11-03/Not exported.md"));
        String meeting = text(zip, "Notes+ 2026-11-03/Work/Meeting.md");
        assertTrue(meeting, meeting.contains("![](../attachments/IMG_9.png)"));
        assertTrue(text(zip, "Notes+ 2026-11-03/Week.md").contains("- [ ] Gym 📅 2026-11-09 ⏰ 07:30 🔁 every mon, wed, fri"));

        Interchange.Found back = Interchange.read(zip, namer, 1L);
        assertEquals(6, back.notes.size());
        Interchange.Note m = titled(back, "Meeting");
        assertEquals("Work", m.folder);
        assertTrue(m.pinned);
        assertEquals(3, m.color);
        assertEquals(text.edited, m.edited);
        assertEquals("Agenda\n\n![[IMG_1.png]]\n\nSee [[Plan]]", m.content);
        NoteData.TodoItem g = NoteData.todos(titled(back, "Week").data).get(0);
        assertEquals("2026-11-09", g.due);
        assertEquals("07:30", g.time);
        assertEquals("weekly:1,3,5", g.repeat);
        assertTrue(NoteData.todos(titled(back, "Week").data).get(1).done);
        assertTrue(titled(back, "Old idea").archived);
        assertTrue(titled(back, "Weekly review").template);
        Interchange.Note trip = titled(back, "Trip");
        assertEquals(NoteData.MINDMAP, trip.type);
        NoteData.MindMap map = NoteData.mindMap(trip.data);
        assertEquals("Spring", map.nodes.get(0).sub);
        assertEquals(map.nodes.get(0).id, map.nodes.get(1).parent);
        Interchange.Note day = titled(back, "Monday");
        assertEquals(NoteData.JOURNAL, day.type);
        assertEquals("🙂", NoteData.journal(day.data, 0).mood);
        assertEquals("Rained", day.content);
    }

    @Test
    public void namesAreSafeAndTheSameNoteIsRecognised() {
        assertEquals("a b c", Interchange.safe("a/b:c"));
        assertEquals("Untitled", Interchange.safe("..."));
        assertEquals(Interchange.sameKey("Plan ", "Some  words\n"), Interchange.sameKey("plan", "some words"));
        assertFalse(Interchange.sameKey("Plan", "a").equals(Interchange.sameKey("Plan", "b")));
        assertEquals(Interchange.sameKey("A", "x\n\n![[IMG_1.png]]"), Interchange.sameKey("A", "x\n\n![[IMG_2.png]]"));
        assertEquals("weekdays", Interchange.repeatFrom("every weekday", "2026-11-02"));
        assertEquals("monthly:31", Interchange.repeatFrom("every month on the 31st", "2026-10-31"));
    }

    private static Interchange.Note note(String type, String title, String content) {
        return note(type, title, content, null);
    }

    private static Interchange.Note note(String type, String title, String content, String data) {
        Interchange.Note n = new Interchange.Note();
        n.type = type;
        n.title = title;
        n.content = content;
        n.data = data;
        return n;
    }

    private static String text(List<Interchange.Entry> zip, String path) {
        for (Interchange.Entry e : zip) if (e.path.equals(path)) return new String(e.bytes, StandardCharsets.UTF_8);
        throw new AssertionError("No " + path);
    }
}
