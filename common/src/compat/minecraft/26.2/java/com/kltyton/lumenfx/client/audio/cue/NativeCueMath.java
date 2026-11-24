package com.kltyton.lumenfx.client.audio.cue;

import com.kltyton.lumenfx.client.audio.NativeAudio;

/** Scalar source-platform arithmetic used by the audio cue parser. */
final class NativeCueMath {
    static { NativeAudio.initialize(); }
    private NativeCueMath() { }
    static native float oscillator(float center, float amplitude, float frequency, float offset, float playback);
}
