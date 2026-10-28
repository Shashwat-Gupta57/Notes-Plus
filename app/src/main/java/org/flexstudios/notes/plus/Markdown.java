package org.flexstudios.notes.plus;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text notes' format: Markdown, line by line, exactly as the desktop (Markdown.java) and
 * the web (md.js) read and write it, with the few HTML tags Markdown lacks.
 *
 * <pre>
 * Paragraphs   # H1   ## H2   ### H3   &gt; quote   - item (also • * –)   1. item   - [ ] task   - [x] done
 * Inline       **bold**   *italic*   ~~strike~~   `code`   &lt;u&gt;underline&lt;/u&gt;   &lt;s&gt;strike&lt;/s&gt;
 *              &lt;font color="#RRGGBB"&gt;colour&lt;/font&gt;
 * </pre>
 *
 * The editor ({@link RichText}) shows and edits the result, never the marks; this class turns a
 * line into a {@link Para} and back. Writing nests the marks, so a note reads back unchanged.
 */
final class Markdown {

    private Markdown() {}

    enum Block {
        NORMAL, H1, H2, H3, QUOTE, BULLET, NUMBER, TASK, TASK_DONE;

        boolean isList() {
            return this == BULLET || this == NUMBER || this == TASK || this == TASK_DONE;
        }
    }

    /** How a run of characters looks. {@code color} is "#RRGGBB" or null. */
    static final class Style {
        static final Style PLAIN = new Style(false, false, false, false, false, null);
        final boolean bold, italic, underline, strike, code;
        @Nullable final String color;

        Style(boolean bold, boolean italic, boolean underline, boolean strike, boolean code, @Nullable String color) {
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strike = strike;
            this.code = code;
            this.color = color;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Style)) return false;
            Style s = (Style) o;
            return bold == s.bold && italic == s.italic && underline == s.underline && strike == s.strike && code == s.code
                    && Objects.equals(color, s.color);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bold, italic, underline, strike, code, color);
        }
    }

    static final class Run {
        final String text;
        final Style style;

        Run(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    static final class Para {
        final Block block;
        final List<Run> runs;

        Para(Block block, List<Run> runs) {
            this.block = block;
            this.runs = runs;
        }

        String text() {
            StringBuilder b = new StringBuilder();
            for (Run r : runs) b.append(r.text);
            return b.toString();
        }
    }

    // ================================================================= reading

    private static final Pattern HEADING = Pattern.compile("^(#{1,3}) (.*)$");
    private static final Pattern TASK = Pattern.compile("^\\s*[-*•–]\\s*\\[([ xX])\\]\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•–] (.*)$");
    private static final Pattern NUMBER = Pattern.compile("^\\s*\\d+[.)] (.*)$");
    private static final Pattern COLOR = Pattern.compile("^<font color=[\"']?(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})[\"']?>", Pattern.CASE_INSENSITIVE);

    static Para parse(String line) {
        Matcher m;
        if ((m = HEADING.matcher(line)).matches()) {
            int n = m.group(1).length();
            return new Para(n == 1 ? Block.H1 : n == 2 ? Block.H2 : Block.H3, inline(m.group(2)));
        }
        if (line.startsWith("> ")) return new Para(Block.QUOTE, inline(line.substring(2)));
        if ((m = TASK.matcher(line)).matches()) return new Para(" ".equals(m.group(1)) ? Block.TASK : Block.TASK_DONE, inline(m.group(2)));
        if ((m = BULLET.matcher(line)).matches()) return new Para(Block.BULLET, inline(m.group(1)));
        if ((m = NUMBER.matcher(line)).matches()) return new Para(Block.NUMBER, inline(m.group(1)));
        return new Para(Block.NORMAL, inline(line));
    }

    /** The runs of one line. A mark opens only where it is closed again later on the line. */
    static List<Run> inline(String s) {
        List<Run> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean bold = false, italic = false, strike = false, code = false;
        int underline = 0, tagStrike = 0;
        List<String> colors = new ArrayList<>();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        int i = 0;
        while (i < s.length()) {
            Style now = new Style(bold, italic, underline > 0, strike || tagStrike > 0, code, colors.isEmpty() ? null : colors.get(colors.size() - 1));
            String rest = s.substring(i);
            String took = null;
            if (code) {
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
                text.append("**");
                i += 2;
                continue;
            } else if (rest.startsWith("~~") && (strike || s.indexOf("~~", i + 2) > 0)) {
                took = "~~";
                strike = !strike;
            } else if (rest.startsWith("*") && (italic || nextSingleStar(s, i + 1) > 0)) {
                took = "*";
                italic = !italic;
            } else if (lower.startsWith("<u>", i) && lower.indexOf("</u>", i) > 0) {
                took = "<u>";
                underline++;
            } else if (lower.startsWith("</u>", i) && underline > 0) {
                took = "</u>";
                underline--;
            } else if (lower.startsWith("<s>", i) && lower.indexOf("</s>", i) > 0) {
                took = "<s>";
                tagStrike++;
            } else if (lower.startsWith("</s>", i) && tagStrike > 0) {
                took = "</s>";
                tagStrike--;
            } else if (lower.startsWith("</font>", i) && !colors.isEmpty()) {
                took = "</font>";
                colors.remove(colors.size() - 1);
            } else {
                Matcher c = COLOR.matcher(rest);
                if (c.find() && lower.indexOf("</font>", i) > 0) {
                    took = c.group();
                    colors.add(c.group(1).toUpperCase(java.util.Locale.ROOT));
                }
            }
            if (took == null) {
                text.append(s.charAt(i));
                i++;
                continue;
            }
            if (text.length() > 0) {
                out.add(new Run(text.toString(), now));
                text.setLength(0);
            }
            i += took.length();
        }
        if (text.length() > 0) {
            out.add(new Run(text.toString(), new Style(bold, italic, underline > 0, strike || tagStrike > 0, code,
                    colors.isEmpty() ? null : colors.get(colors.size() - 1))));
        }
        return merge(out);
    }

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

    private static List<Run> merge(List<Run> runs) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs) {
            if (r.text.isEmpty()) continue;
            if (!out.isEmpty() && out.get(out.size() - 1).style.equals(r.style)) {
                Run last = out.remove(out.size() - 1);
                out.add(new Run(last.text + r.text, r.style));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    // ================================================================= writing

    static String write(Para p, int number) {
        String prefix;
        switch (p.block) {
            case H1: prefix = "# "; break;
            case H2: prefix = "## "; break;
            case H3: prefix = "### "; break;
            case QUOTE: prefix = "> "; break;
            case BULLET: prefix = "- "; break;
            case NUMBER: prefix = number + ". "; break;
            case TASK: prefix = "- [ ] "; break;
            case TASK_DONE: prefix = "- [x] "; break;
            default: prefix = "";
        }
        return prefix + write(p.runs);
    }

    /** The marks, nested: colour outside, then u, strike, bold, italic, code. */
    static String write(List<Run> runs) {
        StringBuilder out = new StringBuilder();
        List<String> open = new ArrayList<>();
        for (Run r : merge(runs)) {
            List<String> want = marks(r.style);
            int keep = 0;
            while (keep < open.size() && keep < want.size() && open.get(keep).equals(want.get(keep))) keep++;
            for (int k = open.size() - 1; k >= keep; k--) out.append(closing(open.get(k)));
            while (open.size() > keep) open.remove(open.size() - 1);
            for (int k = keep; k < want.size(); k++) {
                out.append(want.get(k));
                open.add(want.get(k));
            }
            out.append(r.text);
        }
        for (int k = open.size() - 1; k >= 0; k--) out.append(closing(open.get(k)));
        return out.toString();
    }

    private static List<String> marks(Style s) {
        List<String> m = new ArrayList<>();
        if (s.color != null) m.add("<font color=\"" + s.color + "\">");
        if (s.underline) m.add("<u>");
        if (s.strike) m.add("~~");
        if (s.bold) m.add("**");
        if (s.italic) m.add("*");
        if (s.code) m.add("`");
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
        switch (p.block) {
            case BULLET: return "• " + text;
            case TASK: return "☐ " + text;
            case TASK_DONE: return "☑ " + text;
            case NUMBER: return line.trim().replaceFirst("^(\\d+[.)]) .*$", "$1") + " " + text;
            default: return text;
        }
    }

    static String plain(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(plainLine(lines[i]));
        }
        return out.toString();
    }

    // ================================================================= repair

    private static final Pattern ENTITY = Pattern.compile("&(#\\d{2,7}|amp|lt|gt|quot|nbsp);");
    private static final Pattern SPAN_COLOR = Pattern.compile("<span style=\"color:\\s*(#[0-9A-Fa-f]{6});?\">(.*?)</span>");

    /**
     * Undoes what Notes+ 16.1 for Android wrote by mistake: characters as HTML entities
     * ({@code &#128512;}, {@code &amp;}) and colours as {@code <span style="color:…">}. Text
     * without them comes back as it was.
     */
    static String repair(@Nullable String text) {
        if (text == null || (text.indexOf('&') < 0 && !text.contains("<span"))) return text == null ? "" : text;
        Matcher m = SPAN_COLOR.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("<font color=\"" + m.group(1).toUpperCase(java.util.Locale.ROOT) + "\">" + m.group(2) + "</font>"));
        m.appendTail(sb);
        Matcher e = ENTITY.matcher(sb.toString());
        StringBuffer out = new StringBuffer();
        while (e.find()) {
            String g = e.group(1);
            String r;
            if (g.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(g.substring(1))));
            else if (g.equals("amp")) r = "&";
            else if (g.equals("lt")) r = "<";
            else if (g.equals("gt")) r = ">";
            else if (g.equals("quot")) r = "\"";
            else r = " ";
            e.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        e.appendTail(out);
        return out.toString();
    }
}
