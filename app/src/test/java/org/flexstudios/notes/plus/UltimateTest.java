package org.flexstudios.notes.plus;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Ultimate notes' blocks and words. The desktop's UltimateTest and the web's ultimate tests agree. */
public class UltimateTest {

    private static final String MIXED = "{\"blocks\":[{\"id\":\"a\",\"kind\":\"text\",\"md\":\"Hello **you**\\n![[IMG_1_ab.png|60%]]\"},"
            + "{\"id\":\"b\",\"kind\":\"list\",\"items\":[{\"text\":\"Milk\",\"done\":true},{\"text\":\" \",\"done\":false},{\"text\":\"Eggs\",\"done\":false}]},"
            + "{\"id\":\"c\",\"kind\":\"mindmap\",\"nodes\":[{\"id\":1,\"parent\":-1,\"text\":\"Me\",\"x\":0,\"y\":0,\"sub\":\"ideal\"},{\"id\":2,\"parent\":1,\"text\":\"Calm\",\"x\":230,\"y\":0}],\"links\":[],\"h\":400},"
            + "{\"id\":\"d\",\"kind\":\"audio\",\"recordings\":[{\"file\":\"rec_1.wav\",\"label\":\"\",\"duration\":1000,\"createdAt\":1,\"wave\":[0.5]},{\"file\":\"rec_2.wav\",\"label\":\"Walk\",\"duration\":1,\"createdAt\":2,\"wave\":[]}]},"
            + "{\"id\":\"e\",\"kind\":\"sketch\",\"x\":1}]}";

    private static final String INSIDE = "{\"blocks\":[{\"id\":\"m\",\"kind\":\"mindmap\",\"nodes\":[{\"id\":1,\"parent\":-1,\"text\":\"Me\",\"x\":0,\"y\":0,\"blocks\":["
            + "{\"kind\":\"list\",\"items\":[{\"text\":\"Breathe\",\"done\":true},{\"text\":\"Walk\",\"done\":false}]},"
            + "{\"kind\":\"audio\",\"recordings\":[{\"file\":\"r.wav\",\"label\":\"Om\",\"duration\":1,\"createdAt\":1,\"wave\":[]}]}]},"
            + "{\"id\":2,\"parent\":1,\"text\":\"Calm\",\"x\":1,\"y\":1}],\"links\":[]}]}";

    @Test
    public void insideAnIdea() throws Exception {
        List<JSONObject> b = Ultimate.blocks(INSIDE);
        assertEquals("- Me\n- [x] Breathe\n- [ ] Walk\n🎙 Om\n- Calm", Ultimate.content(b));
        NoteData.MindMap map = NoteData.mindMap(b.get(0).toString());
        assertEquals("☑ 1/2 · 🎙 1", Ultimate.insideSummary(Ultimate.inside(map.nodes.get(0))));
        assertEquals(null, map.nodes.get(1).blocks);
        assertEquals(2, new JSONObject(NoteData.mindMapJson(map)).getJSONArray("nodes").getJSONObject(0).getJSONArray("blocks").length());
    }

    @Test
    public void indents() throws Exception {
        assertEquals(0, Ultimate.indent(new JSONObject()));
        assertEquals(2, Ultimate.indent(new JSONObject().put("indent", 2)));
        assertEquals(4, Ultimate.indent(new JSONObject().put("indent", 9)));
        assertEquals(0, Ultimate.indent(new JSONObject().put("indent", -1)));
        assertEquals(0, Ultimate.indent(new JSONObject().put("indent", "x")));
        assertEquals(2, Ultimate.blocks("{\"blocks\":[{\"id\":\"a\",\"kind\":\"list\",\"indent\":2,\"items\":[]}]}").get(0).optInt("indent"));
    }

    @Test
    public void everyKindsWordsInOrder() {
        assertEquals("Hello **you**\n![[IMG_1_ab.png|60%]]\n- [x] Milk\n- [ ] Eggs\n- Me\n- ideal\n- Calm\n🎙 Recording\n🎙 Walk",
                Ultimate.content(Ultimate.blocks(MIXED)));
    }

    @Test
    public void unknownKindsAreKept() {
        List<JSONObject> b = Ultimate.blocks(Ultimate.blocksJson(Ultimate.blocks(MIXED)));
        assertEquals(5, b.size());
        assertEquals("sketch", Ultimate.kind(b.get(4)));
        assertEquals(1, b.get(4).optInt("x"));
        assertEquals(400, Ultimate.mapHeight(b.get(2)));
    }

    @Test
    public void junkIsSkipped() {
        List<JSONObject> b = Ultimate.blocks("{\"blocks\":[1,null,{\"kind\":5},{\"id\":\"\",\"kind\":\"text\",\"md\":\"  \"}]}");
        assertEquals(1, b.size());
        assertEquals("text", Ultimate.kind(b.get(0)));
        assertFalse(b.get(0).optString("id").isEmpty());
        assertEquals("", Ultimate.content(b));
    }

    @Test
    public void notJson() {
        assertEquals(0, Ultimate.blocks("oops").size());
        assertEquals(0, Ultimate.blocks(null).size());
    }

    @Test
    public void mapHeights() throws Exception {
        assertEquals(320, Ultimate.mapHeight(new JSONObject()));
        assertEquals(160, Ultimate.mapHeight(new JSONObject().put("h", 50)));
        assertEquals(1200, Ultimate.mapHeight(new JSONObject().put("h", 5000)));
        assertEquals(320, Ultimate.mapHeight(new JSONObject().put("h", "abc")));
        assertEquals(334, Ultimate.mapHeight(new JSONObject().put("h", 333.6)));
    }

    @Test
    public void aDay() throws Exception {
        long day = new GregorianCalendar(2026, Calendar.NOVEMBER, 26).getTimeInMillis();
        assertEquals("Thursday, 26 November 2026", Ultimate.dayWords(day));
        assertEquals("### Thursday, 26 November 2026 🙂\n### Thursday, 26 November 2026", Ultimate.content(List.of(
                new JSONObject().put("kind", "day").put("day", day).put("mood", "🙂"),
                new JSONObject().put("kind", "day").put("day", day).put("mood", ""))));
    }
}
