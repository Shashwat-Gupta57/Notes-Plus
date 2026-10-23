package org.flexstudios.notes.plus;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Records the default microphone to a WAV file (16-bit mono PCM), streaming to disk as it
 * goes. {@link #takePeak} is the phone's MediaRecorder.getMaxAmplitude: the loudest sample
 * since the last call, 0..32767, which is what the waveform is sampled from.
 *
 * WAV rather than the phone's AAC because Java has no AAC encoder; both play on both.
 */
final class AudioRecorder {
    private static final float RATE = 22050f;

    private TargetDataLine line;
    private RandomAccessFile out;
    private Thread thread;
    private volatile boolean running;
    private volatile int peak;
    private long bytes;

    void start(Path file) throws Exception {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        TargetDataLine l = AudioSystem.getTargetDataLine(format);
        l.open(format);
        out = new RandomAccessFile(file.toFile(), "rw");
        out.setLength(0);
        out.write(new byte[44]); // The header, filled in once the length is known.
        bytes = 0;
        line = l;
        running = true;
        l.start();
        thread = new Thread(this::pump, "notes-recorder");
        thread.setDaemon(true);
        thread.start();
    }

    private void pump() {
        byte[] buffer = new byte[2048];
        while (running) {
            int n = line.read(buffer, 0, buffer.length);
            if (n <= 0) continue;
            int loudest = peak;
            for (int i = 0; i + 1 < n; i += 2) {
                int sample = Math.abs((short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)));
                if (sample > loudest) loudest = sample;
            }
            peak = Math.min(32767, loudest);
            try {
                out.write(buffer, 0, n);
                bytes += n;
            } catch (IOException e) {
                running = false;
            }
        }
    }

    /** The loudest sample since the last call. */
    int takePeak() {
        int p = peak;
        peak = 0;
        return p;
    }

    /** Stops and finishes the file. */
    void stop() {
        running = false;
        if (line != null) {
            line.stop();
            line.close();
        }
        try {
            if (thread != null) thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            out.seek(0);
            out.write(header(bytes));
            out.close();
        } catch (IOException e) {
            System.getLogger("AudioRecorder").log(System.Logger.Level.WARNING, "Could not finish the recording", e);
        }
    }

    private static byte[] header(long dataLength) {
        int byteRate = (int) RATE * 2;
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes()).putInt((int) (36 + dataLength)).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt((int) RATE).putInt(byteRate).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt((int) dataLength)
                .array();
    }
}
