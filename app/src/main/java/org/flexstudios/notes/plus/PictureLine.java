package org.flexstudios.notes.plus;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A picture between the words of a text note or journal: a line of its own,
 * {@code ![[IMG_x.png]]} (as it comes, the column's width at most), {@code ![[IMG_x.png|60%]]}
 * (60% of the column: 10 to 100, in steps of 5) or {@code ![[IMG_x.png|60%|center]]} (placed
 * in the middle, or {@code |right}; left is the default). The same class on the desktop, and the
 * web's picline.js, kept in step by the same PictureLineTest cases. No Java newer than 11 here.
 */
final class PictureLine {

    /** Group 1 is the name; anything after a "|" is the size and place. */
    static final Pattern LINE = Pattern.compile("^!\\[\\[([^\\]|/\\\\]+)((?:\\|[^\\]|]*)*)\\]\\]$");
    static final String LEFT = "left", CENTER = "center", RIGHT = "right";

    final String name;
    /** Percent of the column, or 0: as it comes. */
    int width;
    String align;

    PictureLine(String name, int width, String align) {
        this.name = name;
        this.width = snap(width);
        this.align = CENTER.equals(align) || RIGHT.equals(align) ? align : LEFT;
    }

    /** A width as kept: a multiple of 5 from 10 to 100, or 0 (as it comes). */
    static int snap(double pct) {
        if (pct <= 0) return 0;
        return (int) Math.max(10, Math.min(100, Math.round(pct / 5) * 5));
    }

    /** The picture a line holds, or null when the line isn't one. */
    static PictureLine parse(String line) {
        if (line == null) return null;
        Matcher m = LINE.matcher(line.trim());
        if (!m.matches()) return null;
        int width = 0;
        String align = LEFT;
        for (String part : m.group(2).split("\\|")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if (p.matches("\\d{1,3}%")) {
                int n = Integer.parseInt(p.substring(0, p.length() - 1));
                if (n >= 10 && n <= 100) width = n;
            } else if (CENTER.equals(p) || RIGHT.equals(p) || LEFT.equals(p)) {
                align = p;
            }
        }
        return new PictureLine(m.group(1), width, align);
    }

    /** True when the (trimmed) line is a picture. */
    static boolean is(String line) {
        return line != null && LINE.matcher(line.trim()).matches();
    }

    /** The line: only what differs from as-it-comes is written. */
    String format() {
        int w = snap(width);
        return "![[" + name + (w > 0 ? "|" + w + "%" : "") + (LEFT.equals(align) ? "" : "|" + align) + "]]";
    }
}
