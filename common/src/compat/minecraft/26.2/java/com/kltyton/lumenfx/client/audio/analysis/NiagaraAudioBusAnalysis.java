package com.kltyton.lumenfx.client.audio.analysis;

import com.kltyton.lumenfx.api.client.NiagaraAudioBuses;
import com.kltyton.lumenfx.api.client.NiagaraAudioBuses.PcmWindow;
import com.kltyton.lumenfx.client.audio.capture.MinecraftMasterAudio;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioAnalysisDefinition;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/** Shares analyzed submix publications among all consumers with the same source settings. */
public final class NiagaraAudioBusAnalysis {
    public static final class Frame {
        public static final Frame EMPTY = new Frame(0, 0, false, CompletableFuture.completedFuture(new float[0]));
        private final int channels, resolution;
        private final boolean planar;
        private final CompletableFuture<float[]> samples;

        Frame(int channels, int resolution, boolean planar, CompletableFuture<float[]> samples) {
            this.channels = channels; this.resolution = resolution; this.planar = planar; this.samples = samples;
        }
        public int channels() { return channels; }
        public int resolution() { return resolution; }
        public int byteSize() { return Math.multiplyExact(Math.max(1, Math.multiplyExact(channels, resolution)), Float.BYTES); }
        public void write(ByteBuffer target) {
            float[] data = data();
            for (float sample : data) target.putFloat(sample);
            if (data.length == 0) target.putFloat(0);
        }
        public float sample(float coordinate, int channel) {
            if (channels == 0 || resolution == 0) return 0;
            float[] data = data();
            int selected = Math.clamp(channel, 0, channels - 1);
            float frame = Math.clamp(coordinate, 0f, 1f) * (resolution - 1);
            int low = (int)frame, high = Math.min(resolution - 1, low + 1);
            float fraction = frame - low;
            float first = data[planar ? selected * resolution + low : low * channels + selected];
            float second = data[planar ? selected * resolution + high : high * channels + selected];
            return first + (second - first) * fraction;
        }
        private float[] data() {
            try { return samples.join(); }
            catch (CompletionException failure) {
                throw new IllegalStateException("Unable to compute the published Niagara submix frame", failure.getCause());
            }
        }
    }

    private static final class Builder {
        @Nullable PcmWindow requested;
        @Nullable NiagaraAudioSpectrumKernel spectrum;
        @Nullable CompletableFuture<float[]> task;
        Frame published = Frame.EMPTY;
        boolean reset;

        Frame capture(NiagaraAudioAnalysisDefinition settings, @Nullable PcmWindow input) {
            if (input == null || input.frames() == 0) {
                reset = true;
                requested = null;
                return Frame.EMPTY;
            }
            if (input == requested) return published;
            // Finish the previous block before applying the next one to the continuous FFT history.
            if (task != null) task.join();
            if (reset) {
                spectrum = null;
                reset = false;
            }
            var scope = settings.spectrum() ? null : NiagaraScopeResampler.source();
            CompletableFuture<float[]> next = CompletableFuture.supplyAsync(() -> analyze(settings, input, scope), Util.backgroundExecutor());
            requested = input;
            task = next;
            return published = new Frame(input.channels(), settings.resolution(), settings.spectrum(), next);
        }

        private float[] analyze(NiagaraAudioAnalysisDefinition settings, PcmWindow input, @Nullable NiagaraScopeResampler scope) {
            if (settings.spectrum()) {
                var kernel = spectrum;
                if (kernel == null || kernel.sampleRate() != input.sampleRate())
                    spectrum = kernel = new NiagaraAudioSpectrumKernel(settings, input.sampleRate());
                return kernel.analyze(input, settings.noiseFloor());
            }
            int count = Math.min(input.frames(), MixedAudioHistory.scopeFrames(input.sampleRate(), settings.windowMilliseconds()));
            return java.util.Objects.requireNonNull(scope).resample(input, count, settings.resolution());
        }
    }

    private static final Map<ClientLevel, Map<NiagaraAudioAnalysisDefinition, Builder>> WORLDS = new IdentityHashMap<>();
    private NiagaraAudioBusAnalysis() { }

    public static Frame capture(@Nullable ClientLevel world, NiagaraAudioAnalysisDefinition settings) {
        if (world == null) return Frame.EMPTY;
        var input = NiagaraAudioBuses.window(world, settings.source(), settings.spectrum() ? 0 : settings.windowMilliseconds());
        if (input == null) input = MinecraftMasterAudio.window(world, settings);
        var builders = WORLDS.computeIfAbsent(world, ignored -> new HashMap<>());
        return builders.computeIfAbsent(settings, ignored -> new Builder()).capture(settings, input);
    }
    public static void clear(ClientLevel world) {
        MinecraftMasterAudio.clear(world);
        WORLDS.remove(world);
    }
    public static void clear() {
        MinecraftMasterAudio.clear();
        NiagaraScopeResampler.clear();
        WORLDS.clear();
    }
}
