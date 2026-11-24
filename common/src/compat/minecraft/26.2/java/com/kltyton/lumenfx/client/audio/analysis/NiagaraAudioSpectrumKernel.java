package com.kltyton.lumenfx.client.audio.analysis;

import com.kltyton.lumenfx.api.client.NiagaraAudioBuses.PcmWindow;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioAnalysisDefinition;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** The source windowed power FFT and equal-energy pseudo constant-Q bands. */
final class NiagaraAudioSpectrumKernel {
    private record Band(int first, float[] weights) {
        float power(float[] spectrum) {
            float total = 0;
            for (int index = 0; index < weights.length; index++) total += weights[index] * spectrum[first + index];
            return total;
        }
    }

    private final int sampleRate, fft, windowSize;
    private final int[] reversal;
    private final float[] window, cosine, sine;
    private final Band[] bands;
    private @Nullable Object timeline;
    private int lastChannels, pendingFrames;
    private long nextFrame;
    private float[] pending = new float[0];

    NiagaraAudioSpectrumKernel(NiagaraAudioAnalysisDefinition settings, int sampleRate) {
        this.sampleRate = sampleRate;
        float minimum = Math.max(20, settings.minimumFrequency());
        float maximum = Math.max(minimum, Math.min(20000, settings.maximumFrequency()));
        float octaves = Math.max(.01f, (float)(Math.log(maximum / minimum) / Math.log(2)));
        float perOctave = Math.max(.01f, settings.resolution() / octaves);
        float ratio = (float)Math.pow(2, 1f / perOctave) - 1;
        float spacing = Math.max(.01f, ratio * minimum);
        int log = 8;
        while (log < 13 && (1 << log) < Math.ceil(3f * sampleRate / spacing)) log++;
        fft = 1 << log;
        windowSize = Math.min(1024, fft);
        window = blackman(windowSize);
        reversal = new int[fft];
        for (int index = 0; index < fft; index++) reversal[index] = Integer.reverse(index) >>> (Integer.SIZE - log);
        cosine = new float[fft / 2];
        sine = new float[fft / 2];
        for (int index = 0; index < cosine.length; index++) {
            double phase = -Math.PI * 2 * index / fft;
            cosine[index] = (float)Math.cos(phase);
            sine[index] = (float)Math.sin(phase);
        }
        float stretch = Math.clamp((float)sampleRate / fft / spacing, .5f, 2);
        bands = new Band[settings.resolution()];
        for (int index = 0; index < bands.length; index++) {
            float center = minimum * (float)Math.pow(2, index / perOctave);
            float bandwidth = Math.max(1.e-8f, stretch * center * ratio);
            bands[index] = band(center, bandwidth);
        }
    }

    int sampleRate() { return sampleRate; }

    float[] analyze(PcmWindow source, float floor) {
        int channels = source.channels(), bandsCount = bands.length;
        int count = Math.min(source.frames(), 16384 / channels);
        float[] output = new float[Math.multiplyExact(channels, bandsCount)];
        @Nullable Object incomingTimeline = source.timeline();
        long beginFrame = source.endFrame() - count;
        boolean continuous = incomingTimeline != null;
        boolean consecutive = continuous && incomingTimeline == timeline && channels == lastChannels
                && nextFrame >= beginFrame && nextFrame <= source.endFrame();
        int retained = consecutive ? pendingFrames : 0;
        int sourceOffset = source.frames() - count;
        if (consecutive) sourceOffset += Math.toIntExact(nextFrame - beginFrame);
        int frames = retained + source.frames() - sourceOffset;
        float[] interleaved = new float[Math.multiplyExact(frames, channels)];
        System.arraycopy(pending, 0, interleaved, 0, retained * channels);
        for (int row = retained; row < frames; row++)
            for (int channel = 0; channel < channels; channel++)
                interleaved[row * channels + channel] = source.sample(sourceOffset + row - retained, channel);
        var real = new float[fft];
        var imaginary = new float[fft];
        var spectrum = new float[fft / 2 + 1];
        int windows = 0;
        int offset = 0;
        for (; offset + windowSize <= frames; offset += windowSize / 2) {
            windows++;
            for (int channel = 0; channel < channels; channel++) {
                Arrays.fill(real, 0);
                Arrays.fill(imaginary, 0);
                for (int index = 0; index < windowSize; index++)
                    real[reversal[index]] = interleaved[(offset + index) * channels + channel] * window[index];
                transform(real, imaginary);
                for (int index = 0; index < spectrum.length; index++)
                    spectrum[index] = real[index] * real[index] + imaginary[index] * imaginary[index];
                for (int index = 0; index < bandsCount; index++) output[channel * bandsCount + index] += bands[index].power(spectrum);
            }
        }
        timeline = incomingTimeline;
        nextFrame = source.endFrame();
        lastChannels = channels;
        pendingFrames = continuous ? frames - offset : 0;
        if (pendingFrames != 0) {
            int samples = Math.multiplyExact(pendingFrames, channels);
            if (pending.length < samples) pending = new float[Math.multiplyExact(windowSize, channels)];
            System.arraycopy(interleaved, offset * channels, pending, 0, samples);
        }
        if (windows == 0) return output;

        float scale = 1f / Math.max(1f, -floor);
        for (int index = 0; index < output.length; index++) {
            float decibels = Math.max(floor, 10f * (float)Math.log10(output[index] / windows));
            output[index] = (decibels - floor) * scale;
        }
        return output;
    }

    private void transform(float[] real, float[] imaginary) {
        for (int length = 2; length <= fft; length <<= 1) {
            int half = length >>> 1, step = fft / length;
            for (int start = 0; start < fft; start += length) for (int index = 0; index < half; index++) {
                int first = start + index, second = first + half, phase = index * step;
                float x = real[second] * cosine[phase] - imaginary[second] * sine[phase];
                float y = real[second] * sine[phase] + imaginary[second] * cosine[phase];
                real[second] = real[first] - x;
                imaginary[second] = imaginary[first] - y;
                real[first] += x;
                imaginary[first] += y;
            }
        }
    }

    private Band band(float center, float bandwidth) {
        int usefulBins = fft / 2 + 1;
        if (center - bandwidth > sampleRate || center > 2f * sampleRate) return new Band(0, new float[0]);
        float span = bandwidth * (float)Math.sqrt(-2 * Math.log(1.e-8));
        float nyquist = sampleRate * .5f;
        int first = (int)Math.ceil(fft * Math.clamp(center - span, 0, nyquist) / sampleRate);
        int last = (int)Math.floor(fft * Math.clamp(center + span, 0, nyquist) / sampleRate);
        if (first >= usefulBins) return new Band(0, new float[0]);
        var weights = new float[Math.min(usefulBins - first, Math.max(1, last - first + 1))];
        float sum = 0, squared = bandwidth * bandwidth;
        for (int index = 0; index < weights.length; index++) {
            float delta = (first + index) * (float)sampleRate / fft - center;
            float weight = (float)Math.exp(-.5 * delta * delta / squared);
            weights[index] = Float.isFinite(weight) ? weight : 0;
            sum += weights[index];
        }
        float digital = Math.max(1f / fft, bandwidth / sampleRate);
        if (sum < .95f) {
            float location = fft * center / sampleRate - first;
            int lower = (int)Math.floor(location), upper = lower + 1;
            if (lower >= 0 && upper < weights.length) {
                weights[lower] = 1 - (location - lower);
                weights[upper] = 1 - weights[lower];
                digital = 1f / fft;
            } else if (lower >= 0 && lower < weights.length) {
                weights[lower] = 1;
                digital = 1f / fft;
            } else if (upper >= 0 && upper < weights.length) {
                weights[upper] = 1;
                digital = 1f / fft;
            }
        }
        float normalization = 1f / (digital * fft * (float)Math.sqrt(Math.PI * 2));
        for (int index = 0; index < weights.length; index++) weights[index] *= normalization;
        return new Band(first, weights);
    }

    private static float[] blackman(int frames) {
        var values = new float[frames];
        float step = 2 * (float)Math.PI / (frames - 1), phase = 0;
        int middle = frames / 2;
        for (int index = 0; index <= middle; index++) {
            values[index] = (float)(.42f - .5 * (float)Math.cos(phase) + .08 * (float)Math.cos(2 * phase));
            phase += step;
        }
        for (int index = middle + 1; index < frames; index++) values[index] = values[middle - (index - middle)];
        return values;
    }
}
