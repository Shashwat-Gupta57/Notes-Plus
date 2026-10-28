package org.flexstudios.notes.plus;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class MarkdownTest {

    private static String roundTrip(String text) {
        StringBuilder out = new StringBuilder();
        int number = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Markdown.Para p = Markdown.parse(lines[i]);
            number = p.block == Markdown.Block.NUMBER ? number + 1 : 0;
            if (i > 0) out.append('\n');
            out.append(Markdown.write(p, number));
        }
        return out.toString();
    }

    @Test
    public void readsBackUnchanged() {
        String note = "# Shopping and plans\n"
                + "Some **bold**, *italic*, <u>underlined</u>, ~~struck~~ and `code`, in <font color=\"#FF3B30\">red</font>.\n"
                + "## Today\n- milk\n- **eggs** with *style*\n1. first\n2. second\n- [ ] call mum\n- [x] write tests\n"
                + "> a quote here\n### Small heading\n**bold *and italic***\nplain closing line";
        assertEquals(note, roundTrip(note));
    }

    @Test
    public void loneMarksStayText() {
        assertEquals("2 ** 3 and a * b", Markdown.parse("2 ** 3 and a * b").text());
    }

    @Test
    public void styles() {
        List<Markdown.Run> runs = Markdown.inline("a **b *c*** d");
        assertEquals(4, runs.size());
        assertEquals(true, runs.get(1).style.bold);
        assertEquals(true, runs.get(2).style.bold && runs.get(2).style.italic);
    }

    @Test
    public void repairsWhat161Wrote() {
        assertEquals("Tom & Jerry 😀 <font color=\"#FF3B30\">red</font>",
                Markdown.repair("Tom &amp; Jerry &#128512; <span style=\"color:#ff3b30;\">red</span>"));
        assertEquals("nothing to do", Markdown.repair("nothing to do"));
    }

    @Test
    public void plain() {
        assertEquals("Title\n• milk\n☑ done\n2. two", Markdown.plain("# Title\n- milk\n- [x] done\n2. two"));
    }
}
