package org.flexstudios.notes.plus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** A picture's line, its size and place. The desktop's PictureLineTest and the web's picline tests agree. */
public class PictureLineTest {

    private static String show(PictureLine p) {
        return p == null ? null : p.name + "/" + p.width + "/" + p.align;
    }

    @Test
    public void aPlainLine() {
        assertEquals("IMG_1_ab.png/0/left", show(PictureLine.parse("![[IMG_1_ab.png]]")));
    }

    @Test
    public void aWidth() {
        assertEquals("IMG_1_ab.png/60/left", show(PictureLine.parse("![[IMG_1_ab.png|60%]]")));
    }

    @Test
    public void aWidthAndPlace() {
        assertEquals("IMG_1_ab.png/35/center", show(PictureLine.parse("  ![[IMG_1_ab.png|35%|center]] ")));
    }

    @Test
    public void aPlaceAlone() {
        assertEquals("IMG_1_ab.png/0/right", show(PictureLine.parse("![[IMG_1_ab.png|right]]")));
    }

    @Test
    public void widthsSnapAndStayInRange() {
        assertEquals(60, PictureLine.parse("![[a.png|62%]]").width);
        assertEquals(0, PictureLine.parse("![[a.png|5%]]").width);
        assertEquals(0, PictureLine.parse("![[a.png|150%]]").width);
    }

    @Test
    public void junkIsIgnored() {
        assertEquals("a.png/0/left", show(PictureLine.parse("![[a.png|wide|300]]")));
    }

    @Test
    public void notPictures() {
        assertNull(PictureLine.parse("[[A note]]"));
        assertNull(PictureLine.parse("![[a/b.png]]"));
        assertNull(PictureLine.parse("text ![[a.png]]"));
    }

    @Test
    public void formatWritesOnlyWhatDiffers() {
        assertEquals("![[a.png]]", new PictureLine("a.png", 0, "left").format());
        assertEquals("![[a.png|60%]]", new PictureLine("a.png", 60, "left").format());
        assertEquals("![[a.png|60%|center]]", new PictureLine("a.png", 62, "center").format());
        assertEquals("![[a.png|right]]", new PictureLine("a.png", 0, "right").format());
    }

    @Test
    public void roundTrip() {
        for (String l : new String[] {"![[a.png]]", "![[a.png|60%]]", "![[a.png|25%|right]]", "![[a.png|center]]"}) {
            assertEquals(l, PictureLine.parse(l).format());
        }
    }
}
