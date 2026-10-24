package org.flexstudios.notes.plus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * A plain diagnostic log, {@code logs/notes.log} in the app's data folder (%APPDATA%\Notes+),
 * for problems that only show with someone's own files. Lines only, no library; it starts over
 * past 1 MB. Never holds note text: counts, code points and positions only.
 */
final class Diag {

    private Diag() {}

    private static final long MAX = 1_000_000;

    static synchronized void log(String line) {
        try {
            Path dir = Storage.root().resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("notes.log");
            if (Files.exists(file) && Files.size(file) > MAX) Files.delete(file);
            Files.writeString(file, LocalDateTime.now().withNano(0) + "  " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // A log must never get in the way.
        }
    }

    /**
     * What in {@code text} can confuse caret movement: right-to-left letters, direction marks,
     * invisible characters, lone carriage returns, odd separators and control characters. Logged
     * (with where the first few are) only when there is any.
     */
    static void textReport(String what, String text) {
        if (text == null) return;
        int rtl = 0, marks = 0, invisible = 0, cr = 0, separators = 0, controls = 0;
        StringBuilder where = new StringBuilder();
        int line = 1, shown = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String kind = null;
            byte dir = Character.getDirectionality(cp);
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                rtl++;
                kind = "rtl";
            } else if (cp == 0x200E || cp == 0x200F || cp == 0x061C || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)) {
                marks++;
                kind = "mark";
            } else if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF || cp == 0x00AD || cp == 0x2060) {
                invisible++;
                kind = "invisible";
            } else if (cp == '\r') {
                cr++;
                kind = "cr";
            } else if (cp == 0x2028 || cp == 0x2029 || cp == 0x0085 || cp == 0x000B || cp == 0x000C) {
                separators++;
                kind = "separator";
            } else if (Character.isISOControl(cp) && cp != '\n' && cp != '\t') {
                controls++;
                kind = "control";
            }
            if (kind != null && shown < 12) {
                where.append(String.format(" %s U+%04X line %d;", kind, cp, line));
                shown++;
            }
            if (cp == '\n') line++;
            i += Character.charCount(cp);
        }
        if (rtl + marks + invisible + cr + separators + controls == 0) return;
        log(what + ": " + text.length() + " chars, " + line + " lines; rtl=" + rtl + " marks=" + marks + " invisible=" + invisible
                + " cr=" + cr + " separators=" + separators + " controls=" + controls + " |" + where);
    }
}
