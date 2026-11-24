package com.kltyton.lumenfx.client.audio.analysis;

import com.kltyton.lumenfx.api.client.NiagaraAudioBuses.PcmWindow;
import org.jspecify.annotations.Nullable;

/** A frame-aligned ring for a host's final mixed callback blocks, guarded by the bus registry. */
public final class MixedAudioHistory {
    private static final int SPECTRUM_SAMPLES = 16_384;
    private final int sampleRate, channels;
    private float[] ring;
    private int cursor, available;
    private long endFrame;
    private Object timeline = new Object();
    private @Nullable PcmWindow published;

    public MixedAudioHistory(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0) throw new IllegalArgumentException("Invalid mixed audio format");
        this.sampleRate = sampleRate;
        this.channels = channels;
        int frames = Math.max(Math.ceilDiv(SPECTRUM_SAMPLES, channels), scopeFrames(sampleRate, 400));
        ring = new float[Math.multiplyExact(frames, channels)];
    }

    public boolean matches(int rate, int count) { return rate == sampleRate && count == channels; }

    public void append(long firstFrame, float[] samples) {
        if (firstFrame < 0 || samples.length % channels != 0) throw new IllegalArgumentException("Unaligned mixed audio block");
        if (samples.length == 0) return;
        long nextFrame = Math.addExact(firstFrame, samples.length / channels);
        if (firstFrame != endFrame) {
            available = cursor = 0;
            timeline = new Object();
        }
        int length = Math.min(samples.length, ring.length);
        int source = samples.length - length;
        int first = Math.min(length, ring.length - cursor);
        System.arraycopy(samples, source, ring, cursor, first);
        System.arraycopy(samples, source + first, ring, 0, length - first);
        cursor = (int)(((long)cursor + length) % ring.length);
        available = (int)Math.min(ring.length, (long)available + length);
        endFrame = nextFrame;
        published = null;
    }

    public PcmWindow window(float minimumMilliseconds) {
        int desired = Math.multiplyExact(scopeFrames(sampleRate, minimumMilliseconds), channels);
        if (desired > ring.length) {
            float[] expanded = new float[desired];
            copy(expanded);
            ring = expanded;
            cursor = available;
        }
        var snapshot = published;
        if (snapshot == null) {
            float[] samples = new float[available];
            copy(samples);
            published = snapshot = new PcmWindow(sampleRate, channels, samples, timeline, endFrame);
        }
        return snapshot;
    }

    public static int scopeFrames(int sampleRate, float millis) {
        double frames = Math.ceil(sampleRate * (millis / 1000f));
        if (frames <= 0) return 0;
        if (frames > Integer.MAX_VALUE - 3) throw new IllegalArgumentException("Niagara audio time window is too large");
        return ((int)frames + 3) & ~3;
    }

    private void copy(float[] destination) {
        int start = (int)(((long)cursor + ring.length - available) % ring.length);
        int first = Math.min(available, ring.length - start);
        System.arraycopy(ring, start, destination, 0, first);
        System.arraycopy(ring, 0, destination, first, available - first);
    }
}
