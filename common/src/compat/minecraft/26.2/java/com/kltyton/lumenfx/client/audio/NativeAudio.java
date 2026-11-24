package com.kltyton.lumenfx.client.audio;

import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

/** Loads the platform audio operations shared by capture and source cue evaluation. */
public final class NativeAudio {
    private static final SharedLibrary LIBRARY = Library.loadNative(NativeAudio.class, "lumenfx", "lumenfx_audio", true);
    static {
        String path = LIBRARY.getPath();
        if (path == null) throw new UnsatisfiedLinkError("LumenFX audio library has no loadable filesystem path");
        System.load(path);
    }

    private NativeAudio() { }
    public static long initialize() { return LIBRARY.address(); }
}
