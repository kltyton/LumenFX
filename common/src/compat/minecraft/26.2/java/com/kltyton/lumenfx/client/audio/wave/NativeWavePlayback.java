package com.kltyton.lumenfx.client.audio.wave;

import com.kltyton.lumenfx.client.audio.NativeAudio;
import java.nio.ByteBuffer;

final class NativeWavePlayback {
    static { NativeAudio.initialize(); }

    private NativeWavePlayback() { }

    static native long create(ByteBuffer pcm, int inputRate, int outputRate, int channels, int startFrame,
                              boolean looping, float pitch, float cutoff);
    static native void parameters(long handle, float pitch, float cutoff);
    static native long callback();
    static native int render(long handle, ByteBuffer output, int bytes);
    static native void destroy(long handle);
}
