package com.kltyton.lumenfx.client.audio.capture;

import com.kltyton.lumenfx.client.audio.NativeAudio;
import org.jspecify.annotations.Nullable;

/** The WASAPI process-loopback source used for the current Minecraft process. */
final class WindowsProcessAudio {
    static { NativeAudio.initialize(); }

    private WindowsProcessAudio() { }
    static native long open(int frames, int sampleRate, int channels, int channelMask);
    static native float @Nullable [] read(long handle, int frames, long[] publication);
    static native void close(long handle);
}
