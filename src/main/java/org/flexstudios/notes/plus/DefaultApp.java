package org.flexstudios.notes.plus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;

/**
 * Makes Notes+ the default app for a file type, for the person installing it: the per-user
 * {@code UserChoice} key Windows reads, with the hash Windows checks it against (the same
 * scheme Windows writes when someone picks an app in Settings). Without a matching hash Windows
 * ignores the choice and resets it, which is why it's computed rather than just written.
 */
final class DefaultApp {

    private DefaultApp() {}

    private static final String EXPERIENCE = "User Choice set via Windows User Experience {D18B6DD5-6124-4341-9318-804003BAFA0B}";

    /** Sets {@code progId} as the default for {@code ext} (".txt") for the current user. */
    static void set(String ext, String progId) throws Exception {
        String sid = userSid();
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\" + ext + "\\UserChoice";
        // Windows' newer key, where present, would win over the classic one.
        run("reg", "delete", key + "Latest", "/f");
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalDateTime minute = LocalDateTime.now().withSecond(0).withNano(0);
            String hash = hash(ext, sid, progId, minute);
            run("reg", "delete", key, "/f");
            Path reg = Files.createTempFile("notes-plus-default", ".reg");
            try {
                String body = "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\"
                        + ext + "\\UserChoice]\r\n\"ProgId\"=\"" + progId + "\"\r\n\"Hash\"=\"" + hash + "\"\r\n";
                byte[] b = body.getBytes(StandardCharsets.UTF_16LE);
                byte[] out = new byte[b.length + 2];
                out[0] = (byte) 0xFF;
                out[1] = (byte) 0xFE;
                System.arraycopy(b, 0, out, 2, b.length);
                Files.write(reg, out);
                run("reg", "import", reg.toString());
            } finally {
                Files.deleteIfExists(reg);
            }
            // The hash names the minute the key was written: written in the same minute, it holds.
            if (LocalDateTime.now().withSecond(0).withNano(0).equals(minute)) return;
        }
    }

    /** The UserChoice hash for one choice made at {@code minute} (local time). */
    static String hash(String ext, String sid, String progId, LocalDateTime minute) throws Exception {
        long fileTime = (minute.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 11_644_473_600_000L) * 10_000L;
        String date = String.format("%08x%08x", (fileTime >>> 32) & 0xFFFFFFFFL, fileTime & 0xFFFFFFFFL);
        String base = (ext + sid + progId + date + EXPERIENCE).toLowerCase(Locale.ROOT);
        return encode(base);
    }

    /** Windows' two-pass checksum over the UTF-16 text and its MD5, base64 of 8 bytes. */
    private static String encode(String text) throws Exception {
        byte[] data = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        byte[] md5 = MessageDigest.getInstance("MD5").digest(data);
        int lengthBase = text.length() * 2 + 2;
        int length = ((lengthBase & 4) <= 1 ? 1 : 0) + (lengthBase >>> 2) - 1;
        if (length <= 1) return "";
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int md51 = (int) ((((long) le(md5, 0)) | 1) + 0x69FB0000L);
        int md52 = (int) ((((long) le(md5, 4)) | 1) + 0x13DB0000L);
        int index = (length - 2) >>> 1;
        int counter = index + 1;

        // Pass one.
        int pdata = 0, outHash1 = 0, cache = 0, outHash2 = 0;
        while (counter-- > 0) {
            int r0 = in.getInt(pdata) + outHash1;
            int r1 = in.getInt(pdata + 4);
            pdata += 8;
            int r2a = r0 * md51 - 0x10FA9605 * (r0 >>> 16);
            int r2b = 0x79F8A395 * r2a + 0x689B6B9F * (r2a >>> 16);
            int r3 = 0xEA970001 * r2b - 0x3C101569 * (r2b >>> 16);
            int r4 = r3 + r1;
            int r5 = cache + r3;
            int r6a = r4 * md52 - 0x3CE8EC25 * (r4 >>> 16);
            int r6b = 0x59C3AF2D * r6a - 0x2232E0F1 * (r6a >>> 16);
            outHash1 = 0x1EC90001 * r6b + 0x35BD1EC9 * (r6b >>> 16);
            outHash2 = r5 + outHash1;
            cache = outHash2;
        }
        int a1 = outHash1, a2 = outHash2;

        // Pass two.
        md51 = le(md5, 0) | 1;
        md52 = le(md5, 4) | 1;
        counter = index + 1;
        pdata = 0;
        outHash1 = 0;
        cache = 0;
        while (counter-- > 0) {
            int r0 = in.getInt(pdata) + outHash1;
            pdata += 8;
            int r1a = r0 * md51;
            int r1b = 0xB1110000 * r1a - 0x30674EEF * (r1a >>> 16);
            int r2a = 0x5B9F0000 * r1b - 0x78F7A461 * (r1b >>> 16);
            int r2b = 0x12CEB96D * (r2a >>> 16) - 0x46930000 * r2a;
            int r3 = 0x1D830000 * r2b + 0x257E1D83 * (r2b >>> 16);
            int r4a = md52 * (r3 + in.getInt(pdata - 4));
            int r4b = 0x16F50000 * r4a - 0x5D8BE90B * (r4a >>> 16);
            int r5a = 0x96FF0000 * r4b - 0x2C7C6901 * (r4b >>> 16);
            int r5b = 0x2B890000 * r5a + 0x7C932B89 * (r5a >>> 16);
            outHash1 = 0x9F690000 * r5b - 0x405B6097 * (r5b >>> 16);
            outHash2 = outHash1 + cache + r3;
            cache = outHash2;
        }
        ByteBuffer out = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(outHash1 ^ a1).putInt(outHash2 ^ a2);
        return Base64.getEncoder().encodeToString(out.array());
    }

    private static int le(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** The signed-in person's SID (S-1-5-21-…). */
    static String userSid() throws Exception {
        Process p = new ProcessBuilder(System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32\\whoami.exe", "/user", "/fo", "csv", "/nh").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        int at = out.lastIndexOf("\"S-");
        if (at < 0) throw new IOException("Couldn't read the user's SID");
        return out.substring(at + 1, out.indexOf('"', at + 1));
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }
}
