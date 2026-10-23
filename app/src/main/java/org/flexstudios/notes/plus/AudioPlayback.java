package org.flexstudios.notes.plus;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plays one recording. WAV (what the desktop app records) streams its PCM straight into an
 * AudioTrack, which every phone plays; MediaPlayer's WAV support varies by device. Anything else
 * (the phone's own AAC) goes through MediaPlayer.
 */
abstract class AudioPlayback {

    protected Runnable onCompletion;

    abstract void start();

    abstract void pause();

    abstract boolean isPlaying();

    /** Milliseconds. */
    abstract int getCurrentPosition();

    abstract int getDuration();

    abstract void release();

    void setOnCompletionListener(Runnable done) {
        onCompletion = done;
    }

    /** Ready to start. Throws if the file can't be played. */
    static AudioPlayback open(File file) throws IOException {
        Wav wav = Wav.read(file);
        if (wav != null) {
            try {
                return new PcmPlayback(file, wav);
            } catch (Exception e) {
                // No AudioTrack for this layout on this phone: MediaPlayer may still manage it.
            }
        }
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(file.getAbsolutePath());
            p.prepare();
        } catch (Exception e) {
            p.release();
            throw new IOException("Can't play " + file.getName(), e);
        }
        return new Media(p);
    }

    private static final class Media extends AudioPlayback {
        private final MediaPlayer player;

        Media(MediaPlayer player) {
            this.player = player;
            player.setOnCompletionListener(mp -> {
                if (onCompletion != null) onCompletion.run();
            });
        }

        @Override void start() { player.start(); }
        @Override void pause() { player.pause(); }
        @Override boolean isPlaying() { return player.isPlaying(); }
        @Override int getCurrentPosition() { return player.getCurrentPosition(); }
        @Override int getDuration() { return player.getDuration(); }
        @Override void release() { player.release(); }
    }

    /** A WAV file's PCM layout. */
    static final class Wav {
        int channels, rate, bits;
        long dataStart, dataLength;

        /** Null if it isn't a PCM WAV this can play. */
        static Wav read(File file) {
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                byte[] head = new byte[12];
                if (in.read(head) != 12 || head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F'
                        || head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E') return null;
                Wav w = new Wav();
                boolean pcm = false;
                byte[] chunk = new byte[8];
                while (in.read(chunk) == 8) {
                    String id = new String(chunk, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                    long size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                    if (id.equals("fmt ")) {
                        byte[] fmt = new byte[16];
                        in.readFully(fmt);
                        ByteBuffer b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                        pcm = b.getShort(0) == 1;
                        w.channels = b.getShort(2);
                        w.rate = b.getInt(4);
                        w.bits = b.getShort(14);
                        in.seek(in.getFilePointer() + size - 16 + (size & 1));
                    } else if (id.equals("data")) {
                        w.dataStart = in.getFilePointer();
                        w.dataLength = Math.min(size, in.length() - w.dataStart);
                        break;
                    } else {
                        in.seek(in.getFilePointer() + size + (size & 1));
                    }
                }
                boolean playable = pcm && w.dataStart > 0 && (w.bits == 16 || w.bits == 8)
                        && (w.channels == 1 || w.channels == 2) && w.rate >= 4000 && w.rate <= 192000;
                return playable ? w : null;
            } catch (IOException e) {
                return null;
            }
        }

        int frameBytes() {
            return channels * bits / 8;
        }

        int durationMs() {
            return (int) (dataLength / frameBytes() * 1000L / rate);
        }
    }

    /** Streams the PCM on a thread of its own; pausing parks the thread. */
    private static final class PcmPlayback extends AudioPlayback {
        private final File file;
        private final Wav wav;
        private final AudioTrack track;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Object lock = new Object();
        private Thread feeder;
        private volatile boolean playing, released;

        PcmPlayback(File file, Wav wav) {
            this.file = file;
            this.wav = wav;
            int channelMask = wav.channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int encoding = wav.bits == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
            int buffer = Math.max(AudioTrack.getMinBufferSize(wav.rate, channelMask, encoding), wav.rate * wav.frameBytes() / 4);
            track = new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(wav.rate).setChannelMask(channelMask).setEncoding(encoding).build(),
                    buffer, AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                throw new IllegalStateException("AudioTrack not initialised");
            }
        }

        @Override
        void start() {
            synchronized (lock) {
                if (released || playing) return;
                playing = true;
                track.play();
                if (feeder == null) {
                    feeder = new Thread(this::feed, "wav-playback");
                    feeder.start();
                }
                lock.notifyAll();
            }
        }

        private void feed() {
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                in.seek(wav.dataStart);
                byte[] buf = new byte[Math.max(4096, wav.rate * wav.frameBytes() / 10 / wav.frameBytes() * wav.frameBytes())];
                long left = wav.dataLength - wav.dataLength % wav.frameBytes();
                while (left > 0 && !released) {
                    synchronized (lock) {
                        while (!playing && !released) lock.wait();
                    }
                    if (released) return;
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    left -= n;
                    int off = 0;
                    while (off < n && !released) {
                        int w = track.write(buf, off, n - off);
                        if (w < 0) return;
                        off += w;
                    }
                }
                // Let the last of it play out, then report the end.
                long total = wav.dataLength / wav.frameBytes();
                while (!released && track.getPlaybackHeadPosition() < total - wav.rate / 50) {
                    Thread.sleep(20);
                }
            } catch (IOException | InterruptedException ignored) {
                // Stopped or released.
            }
            if (released) return;
            main.post(() -> {
                if (released) return;
                playing = false;
                if (onCompletion != null) onCompletion.run();
            });
        }

        @Override
        void pause() {
            synchronized (lock) {
                if (!playing) return;
                playing = false;
                track.pause();
            }
        }

        @Override
        boolean isPlaying() {
            return playing;
        }

        @Override
        int getCurrentPosition() {
            long frames = released ? 0 : track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return (int) Math.min(getDuration(), frames * 1000L / wav.rate);
        }

        @Override
        int getDuration() {
            return wav.durationMs();
        }

        @Override
        void release() {
            synchronized (lock) {
                released = true;
                playing = false;
                lock.notifyAll();
            }
            if (feeder != null) feeder.interrupt();
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
            track.release();
        }
    }
}
