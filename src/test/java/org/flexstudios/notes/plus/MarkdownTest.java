package org.flexstudios.notes.plus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The note format reads and writes back unchanged, and reads what the phone and the web write. */
class MarkdownTest {

    private static String roundTrip(String line, int number) {
        return Markdown.write(Markdown.parse(line), number);
    }

    @Test
    void linesComeBackAsTheyWent() {
        for (String line : List.of(
                "plain words",
                "# Heading one", "## Heading two", "### Heading three",
                "> a quote with **bold**",
                "- an item", "- [ ] a task", "- [x] done",
                "**bold** and *italic* and ~~gone~~ and `code`",
                "<u>under</u> and <font color=\"#FF3B30\">red **bold** red</font>",
                "**bold *both***",
                "5 * 3 = 15, and a lone ** stays",
                "")) {
            assertEquals(line, roundTrip(line, 1), line);
        }
        assertEquals("3. third", roundTrip("3. third", 3));
    }

    @Test
    void readsOtherWritersMarks() {
        assertEquals(Markdown.Block.BULLET, Markdown.parse("• old bullet").block());
        assertEquals("- old bullet", roundTrip("• old bullet", 1));
        assertEquals(Markdown.Block.TASK_DONE, Markdown.parse("- [X] caps").block());
        Markdown.Para p = Markdown.parse("<s>struck</s>");
        assertTrue(p.runs().get(0).style().strike());
        assertEquals("~~struck~~", Markdown.write(p, 1));
    }

    @Test
    void stylesAreReadNotShown() {
        Markdown.Para p = Markdown.parse("a **b** *c* <u>d</u>");
        assertEquals("a b c d", p.text());
        assertTrue(p.runs().get(1).style().bold());
        assertTrue(p.runs().get(3).style().italic());
        assertTrue(p.runs().get(5).style().underline());
        assertFalse(p.runs().get(0).style().bold());
    }

    @Test
    void writingNestsMarks() {
        List<Markdown.Run> runs = List.of(
                new Markdown.Run("a", Markdown.Style.PLAIN.bold(true)),
                new Markdown.Run("b", Markdown.Style.PLAIN.bold(true).italic(true)),
                new Markdown.Run("c", Markdown.Style.PLAIN.italic(true)));
        String md = Markdown.write(runs);
        assertEquals(runs, Markdown.inline(md), md);
    }

    @Test
    void plainHasNoMarks() {
        assertEquals("Title\n• item\nbold", Markdown.plain("# Title\n- item\n**bold**"));
    }

    @Test
    void repairsWhatAndroid161Wrote() {
        assertEquals("Tom & Jerry 😀 <font color=\"#FF3B30\">red</font>",
                Markdown.repair("Tom &amp; Jerry &#128512; <span style=\"color:#ff3b30;\">red</span>"));
        assertEquals("nothing to do", Markdown.repair("nothing to do"));
    }
}
