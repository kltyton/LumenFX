package com.kltyton.lumenfx.client.audio.wave;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Editor-imported PCM16 with an explicit source speaker order. */
public final class NiagaraPcmWave {
    public static final class Format extends AudioFormat {
        Format(int rate, int channels) { super(rate, Short.SIZE, channels, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN); }
    }

    private final Format format;
    private final ByteBuffer pcm;

    private NiagaraPcmWave(Format format, ByteBuffer pcm) {
        this.format = format;
        this.pcm = pcm;
    }

    public static NiagaraPcmWave read(byte[] file) throws IOException {
        if (file.length < 36) throw new IOException("Truncated Niagara sound wave");
        var header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt(), version = header.getInt(), rate = header.getInt(), channels = header.getInt();
        int flags = header.getInt(), size = header.getInt();
        float volume = header.getFloat(), pitch = header.getFloat();
        if (magic != 0x5741464c || (version != 1 && version != 2) || rate <= 0
                || (flags & ~(version == 1 ? 1 : 3)) != 0 || size <= 0
                || channels < 1 || channels > 8 || size % (channels * Short.BYTES) != 0
                || file.length != 32L + channels * Integer.BYTES + size || !Float.isFinite(volume) || !Float.isFinite(pitch))
            throw new IOException("Invalid Niagara sound wave header");
        var speakers = new int[channels];
        int mask = 0;
        for (int channel = 0; channel < channels; channel++) {
            int speaker = header.getInt();
            if (speaker < 0 || speaker > 7 || (mask & 1 << speaker) != 0) throw new IOException("Invalid Niagara speaker layout");
            mask |= 1 << speaker;
            speakers[channel] = speaker;
        }
        var order = openAlOrder(speakers, mask);
        var pcm = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        if (Arrays.equals(speakers, order) && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            pcm.put(header);
        } else {
            var permutation = new int[channels];
            for (int channel = 0; channel < channels; channel++) {
                int source = 0;
                while (source < channels && order[channel] != speakers[source]) source++;
                if (source == channels) throw new IOException("Unsupported Niagara speaker layout");
                permutation[channel] = source * Short.BYTES;
            }
            int frameBytes = channels * Short.BYTES;
            for (int start = header.position(); start < file.length; start += frameBytes)
                for (int source : permutation) pcm.putShort(header.getShort(start + source));
        }
        return new NiagaraPcmWave(new Format(rate, channels), pcm.flip());
    }

    private static int[] openAlOrder(int[] speakers, int mask) throws IOException {
        boolean back = (mask & 0xc0) == 0xc0;
        return switch (speakers.length) {
            case 1 -> speakers;
            case 2 -> new int[]{0, 1};
            case 4 -> new int[]{0, 1, back ? 6 : 4, back ? 7 : 5};
            case 6 -> new int[]{0, 1, 2, 3, back ? 6 : 4, back ? 7 : 5};
            case 8 -> new int[]{0, 1, 2, 3, 6, 7, 4, 5};
            default -> throw new IOException("Unsupported Niagara PCM channel count " + speakers.length);
        };
    }

    public AudioStream stream(float seconds, boolean looping) {
        int frameSize = format.getFrameSize();
        int position = startFrame(seconds, looping) * frameSize;
        var input = pcm.duplicate();
        return new AudioStream() {
            { input.position(position); }

            @Override public AudioFormat getFormat() { return format; }
            @Override public ByteBuffer read(int requested) {
                if (!looping && !input.hasRemaining()) return null;
                int aligned = Math.max(frameSize, requested / frameSize * frameSize);
                int size = looping ? aligned : Math.min(aligned, input.remaining());
                var output = ByteBuffer.allocateDirect(size);
                while (output.hasRemaining()) {
                    if (!input.hasRemaining()) input.rewind();
                    int bytes = Math.min(output.remaining(), input.remaining());
                    int end = input.limit();
                    input.limit(input.position() + bytes);
                    output.put(input);
                    input.limit(end);
                }
                return output.flip();
            }
            @Override public void close() { }
        };
    }

    public NiagaraWavePlayback mixedStream(float seconds, boolean looping, int outputRate, float pitch, float cutoff,
                                           Consumer<Throwable> failureHandler) {
        return new NiagaraWavePlayback(pcm, (int)format.getFrameRate(), outputRate, format.getChannels(),
                startFrame(seconds, looping), looping, pitch, cutoff, failureHandler);
    }

    private int startFrame(float seconds, boolean looping) {
        int frames = pcm.limit() / format.getFrameSize();
        long start = (long)(Math.max(0, seconds) * (double)format.getFrameRate());
        return (int)(looping ? start % frames : Math.min(start, frames));
    }
}
