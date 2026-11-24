package com.kltyton.lumenfx.client.simulation.niagara;

import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

/** Platform intrinsic functions evaluated over a complete four-lane instruction batch. */
public final class NativeVectorMath {
    private static final SharedLibrary LIBRARY = Library.loadNative(NativeVectorMath.class, "lumenfx", "lumenfx_math", true);
    static {
        String path = LIBRARY.getPath();
        if (path == null) throw new UnsatisfiedLinkError("LumenFX math library has no loadable filesystem path");
        System.load(path);
    }

    private NativeVectorMath() { }
    public static long initialize() { return LIBRARY.address(); }

    static native void evaluate(int operation, int groups, int[] left, int leftOffset, int leftStride,
                                int[] right, int rightOffset, int rightStride, int[] destination);
    static native void evaluateSinCos(int groups, int[] input, int inputOffset, int inputStride,
                                      int[] sine, int[] cosine);
}
