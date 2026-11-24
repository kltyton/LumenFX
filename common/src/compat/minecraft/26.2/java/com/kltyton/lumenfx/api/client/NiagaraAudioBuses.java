package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.client.audio.analysis.MixedAudioHistory;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Publishes actual interleaved submix PCM windows from a host's audio callback. */
public final class NiagaraAudioBuses {
    public static final class PcmWindow {
        private final int sampleRate, channels;
        private final float[] samples;
        private final @Nullable Object timeline;
        private final long endFrame;
        public PcmWindow(int sampleRate, int channels, float[] samples) { this(sampleRate, channels, samples, null, 0); }
        public PcmWindow(int sampleRate, int channels, float[] samples, @Nullable Object timeline, long endFrame) {
            if (sampleRate <= 0 || channels <= 0 || samples.length % channels != 0
                    || (timeline != null && endFrame < samples.length / channels))
                throw new IllegalArgumentException("Invalid Niagara submix PCM layout");
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.samples = samples.clone();
            this.timeline = timeline;
            this.endFrame = endFrame;
        }
        public int sampleRate() { return sampleRate; }
        public int channels() { return channels; }
        public int frames() { return samples.length / channels; }
        public @Nullable Object timeline() { return timeline; }
        public long endFrame() { return endFrame; }
        public float sample(int frame, int channel) { return samples[frame * channels + channel]; }
    }

    private static final class Bus {
        @Nullable PcmWindow snapshot;
        @Nullable MixedAudioHistory history;
    }
    private static final Map<ClientLevel, Map<String, Bus>> WORLDS = new IdentityHashMap<>();
    private NiagaraAudioBuses() { }

    /** An empty source path refers to the master submix. New windows replace the previous signal for that bus. */
    public static void publish(ClientLevel world, String sourcePath, int sampleRate, int channels, float[] samples) {
        var window = new PcmWindow(sampleRate, channels, samples);
        synchronized (WORLDS) {
            Bus bus = bus(world, sourcePath);
            bus.history = null;
            bus.snapshot = window;
        }
    }
    /** Feed the final interleaved block for a bus. firstFrame is on that mixer's continuous sample clock. */
    public static void publishMixedBlock(ClientLevel world, String sourcePath, int sampleRate, int channels,
                                         long firstFrame, float[] samples) {
        synchronized (WORLDS) {
            Bus bus = bus(world, sourcePath);
            var history = bus.history;
            if (history == null || !history.matches(sampleRate, channels))
                bus.history = history = new MixedAudioHistory(sampleRate, channels);
            history.append(firstFrame, samples);
            bus.snapshot = null;
        }
    }

    public static @Nullable PcmWindow window(ClientLevel world, String sourcePath) { return window(world, sourcePath, 0); }
    public static @Nullable PcmWindow window(ClientLevel world, String sourcePath, float minimumMilliseconds) {
        synchronized (WORLDS) {
            var buses = WORLDS.get(world);
            Bus bus = buses == null ? null : buses.get(SourceNames.canonical(sourcePath));
            if (bus == null) return null;
            var history = bus.history;
            return history == null ? bus.snapshot : history.window(minimumMilliseconds);
        }
    }

    public static void clear(ClientLevel world, String sourcePath) {
        synchronized (WORLDS) {
            var buses = WORLDS.get(world);
            if (buses != null) {
                buses.remove(SourceNames.canonical(sourcePath));
                if (buses.isEmpty()) WORLDS.remove(world);
            }
        }
    }

    private static Bus bus(ClientLevel world, String sourcePath) {
        return WORLDS.computeIfAbsent(world, ignored -> new HashMap<>())
                .computeIfAbsent(SourceNames.canonical(sourcePath), ignored -> new Bus());
    }
    public static void clear(ClientLevel world) {
        synchronized (WORLDS) { WORLDS.remove(world); }
    }
    public static void clear() {
        synchronized (WORLDS) { WORLDS.clear(); }
    }
}
