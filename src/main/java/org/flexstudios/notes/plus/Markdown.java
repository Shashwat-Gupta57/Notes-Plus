package org.flexstudios.notes.plus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text notes' format: Markdown, line by line, as the web and the phone read it, with the
 * few HTML tags Markdown lacks.
 *
 * <pre>
 * Paragraphs   # H1   ## H2   ### H3   &gt; quote   - item (also • * –)   1. item   - [ ] task   - [x] done
 * Inline       **bold**   *italic*   ~~strike~~   `code`   &lt;u&gt;underline&lt;/u&gt;   &lt;s&gt;strike&lt;/s&gt;
 *              &lt;font color="#RRGGBB"&gt;colour&lt;/font&gt;
 * </pre>
 *
 * The editor ({@link RichArea}) shows and edits the result, never the marks; this class turns a
 * line into a {@link Para} and back. Writing nests the marks properly (a span that is bold and
 * then bold-italic is {@code **a *b***}), so what comes back in reads the same.
 */
final class Markdown {

    private Markdown() {}

    /** What kind of paragraph a line is. */
    enum Block {
        NORMAL, H1, H2, H3, QUOTE, BULLET, NUMBER, TASK, TASK_DONE;

        boolean isList() {
            return this == BULLET || this == NUMBER || this == TASK || this == TASK_DONE;
        }
    }

    /** How a run of characters looks. {@code color} is "#RRGGBB" or null. */
    record Style(boolean bold, boolean italic, boolean underline, boolean strike, boolean code, String color) {
        static final Style PLAIN = new Style(false, false, false, false, false, null);

        Style bold(boolean on) { return new Style(on, italic, underline, strike, code, color); }
        Style italic(boolean on) { return new Style(bold, on, underline, strike, code, color); }
        Style underline(boolean on) { return new Style(bold, italic, on, strike, code, color); }
        Style strike(boolean on) { return new Style(bold, italic, underline, on, code, color); }
        Style code(boolean on) { return new Style(bold, italic, underline, strike, on, color); }
        Style color(String c) { return new Style(bold, italic, underline, strike, code, c); }
    }

    record Run(String text, Style style) {}

    record Para(Block block, List<Run> runs) {
        String text() {
            StringBuilder b = new StringBuilder();
            for (Run r : runs) b.append(r.text());
            return b.toString();
        }
    }

    // ================================================================= reading

    private static final Pattern HEADING = Pattern.compile("^(#{1,3}) (.*)$");
    private static final Pattern TASK = Pattern.compile("^\\s*[-*•–]\\s*\\[([ xX])\\]\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•–] (.*)$");
    private static final Pattern NUMBER = Pattern.compile("^\\s*\\d+[.)] (.*)$");
    private static final Pattern COLOR = Pattern.compile("^<font color=[\"']?(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})[\"']?>", Pattern.CASE_INSENSITIVE);

    /** One line of a note as a paragraph. */
    static Para parse(String line) {
        Matcher m;
        if ((m = HEADING.matcher(line)).matches()) {
            Block b = m.group(1).length() == 1 ? Block.H1 : m.group(1).length() == 2 ? Block.H2 : Block.H3;
            return new Para(b, inline(m.group(2)));
        }
        if (line.startsWith("> ")) return new Para(Block.QUOTE, inline(line.substring(2)));
        if ((m = TASK.matcher(line)).matches()) {
            return new Para(m.group(1).equals(" ") ? Block.TASK : Block.TASK_DONE, inline(m.group(2)));
        }
        if ((m = BULLET.matcher(line)).matches()) return new Para(Block.BULLET, inline(m.group(1)));
        if ((m = NUMBER.matcher(line)).matches()) return new Para(Block.NUMBER, inline(m.group(1)));
        return new Para(Block.NORMAL, inline(line));
    }

    /**
     * The runs of one line. A mark opens only where it is closed again later on the line, so a
     * lone asterisk ("5 * 3") stays an asterisk.
     */
    static List<Run> inline(String s) {
        List<Run> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean bold = false, italic = false, strike = false, code = false;
        int underline = 0, tagStrike = 0;
        List<String> colors = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            Style now = new Style(bold, italic, underline > 0, strike || tagStrike > 0, code,
                    colors.isEmpty() ? null : colors.get(colors.size() - 1));
            String rest = s.substring(i);
            String took = null;
            if (code) {
                // Inside `code` only the closing backtick means anything.
                if (rest.startsWith("`")) {
                    took = "`";
                    code = false;
                }
            } else if (rest.startsWith("`") && s.indexOf('`', i + 1) > 0) {
                took = "`";
                code = true;
            } else if (rest.startsWith("**") && (bold || s.indexOf("**", i + 2) > 0)) {
                took = "**";
                bold = !bold;
            } else if (rest.startsWith("**") && !italic) {
                // A pair that opens nothing is two asterisks, never an empty italic.
                text.append("**");
                i += 2;
                continue;
            } else if (rest.startsWith("~~") && (strike || s.indexOf("~~", i + 2) > 0)) {
                took = "~~";
                strike = !strike;
            } else if (rest.startsWith("*") && (italic || nextSingleStar(s, i + 1) > 0)) {
                took = "*";
                italic = !italic;
            } else if (startsWithIgnoreCase(rest, "<u>") && s.toLowerCase().indexOf("</u>", i) > 0) {
                took = "<u>";
                underline++;
            } else if (startsWithIgnoreCase(rest, "</u>") && underline > 0) {
                took = "</u>";
                underline--;
            } else if (startsWithIgnoreCase(rest, "<s>") && s.toLowerCase().indexOf("</s>", i) > 0) {
                took = "<s>";
                tagStrike++;
            } else if (startsWithIgnoreCase(rest, "</s>") && tagStrike > 0) {
                took = "</s>";
                tagStrike--;
            } else if (startsWithIgnoreCase(rest, "</font>") && !colors.isEmpty()) {
                took = "</font>";
                colors.remove(colors.size() - 1);
            } else {
                Matcher c = COLOR.matcher(rest);
                if (c.find() && s.toLowerCase().indexOf("</font>", i) > 0) {
                    took = c.group();
                    colors.add(c.group(1).toUpperCase());
                }
            }
            if (took == null) {
                text.append(s.charAt(i));
                i++;
                continue;
            }
            if (!text.isEmpty()) {
                out.add(new Run(text.toString(), now));
                text.setLength(0);
            }
            i += took.length();
        }
        if (!text.isEmpty()) {
            out.add(new Run(text.toString(), new Style(bold, italic, underline > 0, strike || tagStrike > 0, code,
                    colors.isEmpty() ? null : colors.get(colors.size() - 1))));
        }
        return merge(out);
    }

    /** The next lone "*" (not half of "**") after {@code from}, or -1. */
    private static int nextSingleStar(String s, int from) {
        for (int k = from; k < s.length(); k++) {
            if (s.charAt(k) != '*') continue;
            if (k + 1 < s.length() && s.charAt(k + 1) == '*') {
                k++;
                continue;
            }
            return k;
        }
        return -1;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** Neighbouring runs that look the same become one. */
    private static List<Run> merge(List<Run> runs) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs) {
            if (r.text().isEmpty()) continue;
            if (!out.isEmpty() && out.get(out.size() - 1).style().equals(r.style())) {
                Run last = out.remove(out.size() - 1);
                out.add(new Run(last.text() + r.text(), r.style()));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    // ================================================================= writing

    /** One paragraph as a line; {@code number} is its place in a numbered list (from 1). */
    static String write(Para p, int number) {
        String prefix = switch (p.block()) {
            case H1 -> "# ";
            case H2 -> "## ";
            case H3 -> "### ";
            case QUOTE -> "> ";
            case BULLET -> "- ";
            case NUMBER -> number + ". ";
            case TASK -> "- [ ] ";
            case TASK_DONE -> "- [x] ";
            case NORMAL -> "";
        };
        return prefix + write(p.runs());
    }

    /** The marks, opened and closed so they nest: colour outside, then u, s, bold, italic, code. */
    static String write(List<Run> runs) {
        StringBuilder out = new StringBuilder();
        List<String> open = new ArrayList<>(); // Marks open now, outermost first.
        for (Run r : merge(runs)) {
            List<String> want = marks(r.style());
            // Keep the longest prefix of what's open that is still wanted; close the rest.
            int keep = 0;
            while (keep < open.size() && keep < want.size() && open.get(keep).equals(want.get(keep))) keep++;
            for (int k = open.size() - 1; k >= keep; k--) out.append(closing(open.get(k)));
            while (open.size() > keep) open.remove(open.size() - 1);
            for (int k = keep; k < want.size(); k++) {
                out.append(want.get(k));
                open.add(want.get(k));
            }
            out.append(r.text());
        }
        for (int k = open.size() - 1; k >= 0; k--) out.append(closing(open.get(k)));
        return out.toString();
    }

    private static List<String> marks(Style s) {
        List<String> m = new ArrayList<>();
        if (s.color() != null) m.add("<font color=\"" + s.color() + "\">");
        if (s.underline()) m.add("<u>");
        if (s.strike()) m.add("~~");
        if (s.bold()) m.add("**");
        if (s.italic()) m.add("*");
        if (s.code()) m.add("`");
        return m;
    }

    private static String closing(String mark) {
        if (mark.startsWith("<font")) return "</font>";
        if (mark.equals("<u>")) return "</u>";
        return mark;
    }

    // ================================================================== plain

    /** A line without its marks: for cards, word counts and anything that shows plain text. */
    static String plainLine(String line) {
        Para p = parse(line);
        String text = p.text();
        return switch (p.block()) {
            case BULLET -> "• " + text;
            case TASK -> "☐ " + text;
            case TASK_DONE -> "☑ " + text;
            case NUMBER -> line.trim().replaceFirst("^(\\d+[.)]) .*$", "$1") + " " + text;
            default -> text;
        };
    }

    /** A whole text without marks, line by line. */
    static String plain(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(plainLine(lines[i]));
        }
        return out.toString();
    }
}
