package org.flexstudios.notes.plus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * One Notes+ at a time. The running app listens on a loopback port (written, with a random
 * token, to {@code instance.json} in its own data folder); a second start with a file (a text
 * file double-clicked in Explorer) hands the path over and ends, so the file opens in the
 * window that is already there.
 */
final class Instance {

    private Instance() {}

    private static Path file() {
        return Storage.root().resolve("instance.json");
    }

    /** Sends the files to a running Notes+. True when one took them. */
    static boolean handOff(String[] args) {
        try {
            if (!Files.exists(file())) return false;
            org.json.JSONObject o = new org.json.JSONObject(Files.readString(file(), StandardCharsets.UTF_8));
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), o.getInt("port")), 800);
                s.setSoTimeout(2000);
                OutputStream out = s.getOutputStream();
                StringBuilder msg = new StringBuilder(o.getString("token")).append('\n');
                for (String a : args) msg.append(Path.of(a).toAbsolutePath()).append('\n');
                out.write(msg.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                s.shutdownOutput();
                return s.getInputStream().read() == 'K';
            }
        } catch (Exception e) {
            return false; // None running (or a stale file): this start becomes the app.
        }
    }

    /** Listens for files from later starts; {@code open} runs on the FX thread. */
    static void listen(Consumer<Path> open) {
        try {
            ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            String token = HexFormat.of().formatHex(raw);
            Files.writeString(file(), new org.json.JSONObject().put("port", server.getLocalPort()).put("token", token).toString(),
                    StandardCharsets.UTF_8);
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(3000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        if (!token.equals(in.readLine())) continue; // Not a Notes+ start.
                        String line;
                        while ((line = in.readLine()) != null) {
                            Path p = Path.of(line);
                            if (TextEditorView.openable(p)) javafx.application.Platform.runLater(() -> open.accept(p));
                        }
                        s.getOutputStream().write('K');
                    } catch (IOException ignored) {
                        // One bad start doesn't stop the listener.
                    }
                }
            }, "notes-instance");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            System.getLogger("Instance").log(System.Logger.Level.INFO, "Single instance unavailable", e);
        }
    }
}
