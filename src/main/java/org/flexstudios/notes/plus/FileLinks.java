package org.flexstudios.notes.plus;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Text files on this computer kept in step with notes ("Save to notes" in the Editor).
 *
 * A link is note uuid → file path, here only (paths mean nothing on another device), in
 * {@code file_links.json} with the hash of what both sides last agreed on. A sync pass:
 * <ul>
 *   <li>same text on both sides: nothing to do;</li>
 *   <li>only the note changed (edited here, or pulled from the phone): the file is rewritten;</li>
 *   <li>only the file changed (edited outside, or in the Editor): the note takes it, and the
 *       account sync carries it to the phone;</li>
 *   <li>both changed: the note wins, and the file's version is kept beside it as
 *       "&lt;name&gt; (before Notes+ sync).txt", so nothing is lost.</li>
 * </ul>
 * A locked note's file holds only its envelope, as opaque base64 ({@link #armor}); unlocking the
 * note in the app never touches it. Its text reaches the file only when the note is decrypted
 * (⋮ → Decrypt). Notes open in an editor wait.
 */
final class FileLinks {

    private FileLinks() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-links");
        t.setDaemon(true);
        return t;
    });
    /** Told (on the FX thread) when a linked file was rewritten from its note. */
    static volatile Consumer<Path> onFileWritten = p -> {};

    private static Path store() {
        return Storage.root().resolve("file_links.json");
    }

    private static synchronized JSONObject links() {
        try {
            return Files.exists(store()) ? new JSONObject(Files.readString(store(), StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void save(JSONObject links) {
        try {
            Path tmp = store().resolveSibling("file_links.json.tmp");
            Files.writeString(tmp, links.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, store(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.getLogger("FileLinks").log(System.Logger.Level.WARNING, "Couldn't save file links", e);
        }
    }

    // ================================================================= links

    /** Links a note to a file whose text it was just made from. */
    static void link(String uuid, Path file, String text) {
        JSONObject all = links();
        all.put(uuid, new JSONObject().put("path", file.toAbsolutePath().toString()).put("hash", hash(normalize(text))));
        save(all);
    }

    /**
     * A linked note was deleted: its file goes to the Recycle Bin (never deleted outright, so a
     * mistake can be undone there), and the link with it.
     */
    static void trash(String uuid) {
        Path file = pathOf(uuid);
        if (file == null) return;
        unlink(uuid);
        try {
            if (Files.exists(file) && java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                java.awt.Desktop.getDesktop().moveToTrash(file.toFile());
            }
        } catch (Exception e) {
            System.getLogger("FileLinks").log(System.Logger.Level.WARNING, "Couldn't move a linked file to the Recycle Bin", e);
        }
    }

    static void unlink(String uuid) {
        JSONObject all = links();
        all.remove(uuid);
        save(all);
    }

    /** The file a note is linked to, or null. */
    static Path pathOf(String uuid) {
        JSONObject l = uuid == null ? null : links().optJSONObject(uuid);
        return l == null ? null : Path.of(l.getString("path"));
    }

    /** The note a file is linked to, or null. */
    static String noteFor(Path file) {
        String want = file.toAbsolutePath().normalize().toString();
        JSONObject all = links();
        for (String uuid : all.keySet()) {
            JSONObject l = all.optJSONObject(uuid);
            if (l != null && Path.of(l.getString("path")).toAbsolutePath().normalize().toString().equalsIgnoreCase(want)) return uuid;
        }
        return null;
    }

    // ================================================================== sync

    /** Runs a pass soon, on its own thread. */
    static void syncLater() {
        IO.execute(FileLinks::syncAll);
    }

    /** One pass over every link. Blocking. */
    static synchronized void syncAll() {
        JSONObject all = links();
        boolean changed = false;
        for (String uuid : new ArrayList<>(all.keySet())) {
            JSONObject l = all.optJSONObject(uuid);
            if (l == null) continue;
            try {
                NoteEntity n = NoteStore.get().byUuid(uuid);
                if (n == null || n.isDeleted()) {
                    all.remove(uuid); // The note is gone; the file stays as it is.
                    changed = true;
                    continue;
                }
                if (NoteSync.OPEN.contains(n.getId())) continue;
                Path file = Path.of(l.getString("path"));
                if (!Files.exists(file)) continue; // Moved or on a drive that's away: kept for later.
                Text f = read(file);
                String fileText = normalize(f.text), noteText = render(n);
                String fileHash = hash(fileText), noteHash = hash(noteText), last = l.optString("hash");
                if (fileHash.equals(noteHash)) {
                    if (!noteHash.equals(last)) {
                        l.put("hash", noteHash);
                        changed = true;
                    }
                } else if (fileHash.equals(last)) {
                    write(file, noteText, f);
                    l.put("hash", noteHash);
                    changed = true;
                } else if (noteHash.equals(last) && take(n, fileText)) {
                    l.put("hash", fileHash);
                    changed = true;
                } else {
                    // Both moved on (or a locked note's file was edited in the clear): the note
                    // wins, and the file's own version is kept beside it.
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    Path aside = file.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + " (before Notes+ sync)"
                            + (dot > 0 ? name.substring(dot) : ".txt"));
                    Files.copy(file, aside, StandardCopyOption.REPLACE_EXISTING);
                    write(file, noteText, f);
                    l.put("hash", noteHash);
                    changed = true;
                }
            } catch (Exception e) {
                System.getLogger("FileLinks").log(System.Logger.Level.WARNING, "Couldn't sync a linked file", e);
            }
        }
        if (changed) save(all);
    }

    /** The file's text for a note: a locked note's envelope armoured, a list as checkboxes, else its text. */
    static String render(NoteEntity n) {
        if (NoteLock.isLocked(n.getData())) return armor(n.getData());
        if (NoteData.LIST.equals(n.getType())) {
            StringBuilder out = new StringBuilder();
            for (NoteData.TodoItem item : NoteData.todos(n.getData())) {
                if (out.length() > 0) out.append('\n');
                out.append(item.done ? "[x] " : "[ ] ").append(item.text);
            }
            return out.toString();
        }
        return n.getContent() == null ? "" : n.getContent();
    }

    /** The note takes the file's text. False when it can't (a locked note and plain text). */
    private static boolean take(NoteEntity n, String text) {
        boolean locked = NoteLock.isLocked(n.getData());
        String content = n.getContent(), data = n.getData();
        if (isArmored(text)) {
            String envelope = unarmor(text);
            if (envelope == null) return false;
            content = "";
            data = envelope;
        } else if (locked) {
            return false;
        } else if (NoteData.LIST.equals(n.getType())) {
            List<NoteData.TodoItem> items = new ArrayList<>();
            for (String line : text.split("\n")) {
                String t = line.strip();
                if (t.isEmpty()) continue;
                boolean done = t.startsWith("[x]") || t.startsWith("[X]");
                if (t.startsWith("[ ]") || done) t = t.substring(3).strip();
                items.add(new NoteData.TodoItem(t, done));
            }
            data = NoteData.todosJson(items, NoteData.wraps(n.getData()));
            StringBuilder c = new StringBuilder();
            for (NoteData.TodoItem i : items) c.append(i.text).append('\n');
            content = c.toString().trim();
        } else {
            content = text;
        }
        NoteStore.get().saveContent(n.getId(), n.getTitle(), content, data, n.getColor(), n.isPinned(), System.currentTimeMillis());
        NoteStore.get().notifyChanged();
        NoteSync.schedule();
        return true;
    }

    // ================================================================ armour

    /*
     * A locked note's file is one block of base64 and nothing else: no name, no header, no
     * field names, so the file alone doesn't even say what made it. Inside (big-endian):
     *   version (1) | lock type (1: 0 PIN, 1 password, 2 pattern) | iterations (4) | salt (16)
     *   | check (32) | iv (12) | ciphertext with its GCM tag (16+)
     */
    private static final String[] TYPES = {"PIN", "PASSWORD", "PATTERN"};
    private static final int MIN_BYTES = 1 + 1 + 4 + 16 + 32 + 12 + 16;

    static boolean isArmored(String text) {
        return unarmor(text) != null;
    }

    /** A locked note's envelope as file text. */
    static String armor(String envelope) {
        try {
            JSONObject o = new JSONObject(envelope);
            byte[] salt = Base64.getDecoder().decode(o.getString("salt")), iv = Base64.getDecoder().decode(o.getString("iv"));
            byte[] check = HexFormat.of().parseHex(o.getString("check")), ct = Base64.getDecoder().decode(o.getString("ct"));
            int type = java.util.Arrays.asList(TYPES).indexOf(o.optString("type", "PASSWORD"));
            ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + salt.length + check.length + iv.length + ct.length);
            b.put((byte) 1).put((byte) Math.max(0, type)).putInt(o.optInt("iter", NoteLock.ITERATIONS))
                    .put(salt).put(check).put(iv).put(ct);
            return Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(b.array());
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable lock envelope", e);
        }
    }

    /** The envelope in a locked note's file, or null if the text isn't one. */
    static String unarmor(String text) {
        if (text == null) return null;
        String b64 = text.replaceAll("\\s", "");
        if (b64.length() < 100 || !b64.matches("[A-Za-z0-9+/]+={0,2}")) return null;
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            if (all.length < MIN_BYTES || all[0] != 1 || all[1] < 0 || all[1] > 2) return null;
            ByteBuffer b = ByteBuffer.wrap(all);
            b.get();
            String type = TYPES[b.get()];
            int iter = b.getInt();
            if (iter < 10_000 || iter > 10_000_000) return null;
            byte[] salt = new byte[16], check = new byte[32], iv = new byte[12], ct = new byte[b.remaining() - 60];
            b.get(salt).get(check).get(iv).get(ct);
            Base64.Encoder e = Base64.getEncoder();
            return new JSONObject().put("lock", 1).put("type", type).put("iter", iter).put("salt", e.encodeToString(salt))
                    .put("check", HexFormat.of().formatHex(check)).put("iv", e.encodeToString(iv)).put("ct", e.encodeToString(ct))
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================= files

    /** A text file as read: its text and how it was stored, so a rewrite keeps the same form. */
    record Text(String text, Charset charset, boolean bom, boolean crlf) {}

    /** UTF-8 (with or without a BOM), or Windows-1252 when it isn't valid UTF-8. */
    static Text read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        ByteBuffer buf = ByteBuffer.wrap(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0));
        Charset cs = StandardCharsets.UTF_8;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString();
        } catch (CharacterCodingException e) {
            cs = Charset.forName("windows-1252");
            text = new String(bytes, cs);
        }
        return new Text(text, cs, bom, text.contains("\r\n"));
    }

    /** Writes text in the form the file had (charset, BOM, line endings); new files: UTF-8, CRLF. */
    static void write(Path file, String text, Text like) throws IOException {
        boolean crlf = like == null || like.crlf() || !like.text().contains("\n");
        String out = crlf ? text.replace("\r\n", "\n").replace("\n", "\r\n") : text;
        Charset cs = like == null ? StandardCharsets.UTF_8 : like.charset();
        byte[] body = out.getBytes(cs);
        Path tmp = file.resolveSibling(file.getFileName() + ".notes-plus.tmp");
        try (var o = Files.newOutputStream(tmp)) {
            if (like != null && like.bom()) o.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            o.write(body);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        javafx.application.Platform.runLater(() -> onFileWritten.accept(file));
    }

    static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n");
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
