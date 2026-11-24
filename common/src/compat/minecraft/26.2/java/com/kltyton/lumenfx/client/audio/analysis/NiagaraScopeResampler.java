package com.kltyton.lumenfx.client.audio.analysis;

import com.kltyton.lumenfx.api.client.NiagaraAudioBuses.PcmWindow;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** The finite, zero-padded, fixed-point polyphase filter used by the UE audio platform's offline resampler. */
final class NiagaraScopeResampler {
    private static final Identifier FILTER = Identifier.fromNamespaceAndPath("lumenfx", "niagara/audio/oscilloscope.lfsr");
    private static final int PHASE_BITS = 12, PHASE_SCALE = 1 << PHASE_BITS;
    private static @Nullable NiagaraScopeResampler current;
    private final int increment;
    private final float[] impulse;

    private NiagaraScopeResampler(int increment, float[] impulse) {
        this.increment = increment;
        this.impulse = impulse;
    }

    static synchronized NiagaraScopeResampler source() {
        if (current == null) current = load();
        return current;
    }

    static synchronized void clear() { current = null; }

    private static NiagaraScopeResampler load() {
        try (var input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(FILTER).open()) {
            byte[] header = input.readNBytes(4 * Integer.BYTES);
            if (header.length != 4 * Integer.BYTES) throw new IOException("Missing Niagara oscilloscope filter header");
            ByteBuffer data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (data.getInt() != 0x5253464c || data.getInt() != 1) throw new IOException("Invalid Niagara oscilloscope filter ABI");
            int increment = data.getInt(), count = data.getInt();
            if (increment < 1 || increment > 524_287 || count < 3 || count > 524_289)
                throw new IOException("Invalid Niagara oscilloscope filter dimensions");
            int size = Math.multiplyExact(count, Float.BYTES);
            byte[] samples = input.readNBytes(size);
            if (samples.length != size || input.read() != -1) throw new IOException("Invalid Niagara oscilloscope filter data");
            float[] coefficients = new float[count];
            ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(coefficients);
            return new NiagaraScopeResampler(increment, coefficients);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load the source Niagara oscilloscope filter " + FILTER, failure);
        }
    }

    float[] resample(PcmWindow source, int frames, int resolution) {
        int channels = source.channels();
        float[] output = new float[Math.multiplyExact(channels, resolution)];
        if (frames == 0) return output;
        float sourceRate = source.sampleRate();
        float destinationRate = sourceRate * ((float)resolution / frames);
        double ratio = destinationRate / sourceRate;
        if (ratio < 1.0 / 256 || ratio > 256) throw new IllegalArgumentException("Niagara oscilloscope resampling ratio exceeds the source range");
        double step = 1.0 / ratio;
        double scale = Math.min(1.0, ratio);
        double frequencyStep = increment * scale;
        int filterStep = fixed(frequencyStep), filterEnd = (impulse.length - 2) << PHASE_BITS;
        int inputStart = source.frames() - frames;
        double[] left = new double[channels], right = new double[channels];
        int center = 0;
        double phase = 0;
        for (int row = 0; row < resolution; row++) {
            double cutoff = (long)center * channels + phase + step;
            long end = (long)frames * channels;
            if (channels == 1 ? cutoff > end : cutoff >= end) break;
            int fraction = fixed(phase * frequencyStep);
            Arrays.fill(left, 0);
            Arrays.fill(right, 0);
            accumulate(source, frames, inputStart, center, 0, -1, fraction, filterStep, filterEnd, left);
            accumulate(source, frames, inputStart, center, 1, 1, filterStep - fraction, filterStep, filterEnd, right);
            for (int channel = 0; channel < channels; channel++)
                output[row * channels + channel] = (float)(scale * (left[channel] + right[channel]));
            phase += step;
            int advance = (int)Math.floor(phase);
            phase -= advance;
            center += advance;
        }
        return output;
    }

    private void accumulate(PcmWindow source, int frames, int inputStart, int center, int sideOffset,
                            int direction, int phase, int step, int end, double[] sum) {
        int terms = (end - phase) / step;
        int index = phase + terms * step;
        int row = center + sideOffset + direction * terms;
        int stop = direction > 0 ? 1 : 0;
        while (index >= stop) {
            if (row >= 0 && row < frames) {
                int coefficient = index >> PHASE_BITS;
                double fraction = (index & (PHASE_SCALE - 1)) / (double)PHASE_SCALE;
                double weight = impulse[coefficient] + fraction * (impulse[coefficient + 1] - impulse[coefficient]);
                for (int channel = 0; channel < sum.length; channel++)
                    sum[channel] += weight * source.sample(inputStart + row, channel);
            }
            row -= direction;
            index -= step;
        }
    }

    private static int fixed(double value) { return (int)Math.rint(value * PHASE_SCALE); }
}
