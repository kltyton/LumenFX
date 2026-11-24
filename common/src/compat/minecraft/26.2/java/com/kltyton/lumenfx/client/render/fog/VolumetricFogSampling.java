package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import java.nio.ByteBuffer;

/** Frame-family Halton offsets with the source float operation order and 1024-frame wrap. */
final class VolumetricFogSampling {
    private VolumetricFogSampling() { }

    static void writeOffsets(ByteBuffer output, long frame, VolumetricFog settings) {
        for (int sample = 0; sample < 16; sample++) {
            if (sample >= settings.historyMissSamples()) {
                output.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            } else if (settings.jitter() && settings.temporal()) {
                int index = (int)((frame - sample) & 1023);
                output.putFloat(halton(index, 2)).putFloat(halton(index, 3)).putFloat(halton(index, 5)).putFloat(0);
            } else output.putFloat(.5f).putFloat(.5f).putFloat(.5f).putFloat(0);
        }
    }

    private static float halton(int index, int base) {
        float result = 0;
        float inverse = 1.0f / base;
        float fraction = inverse;
        while (index > 0) {
            result += (index % base) * fraction;
            index /= base;
            fraction *= inverse;
        }
        return result;
    }
}
