package com.kltyton.lumenfx.client.simulation.niagara.stateless.math;

import com.kltyton.lumenfx.client.simulation.niagara.NativeVectorMath;

/** Scalar CRT functions and analytic particle loops use the existing native math library. */
public final class NativeStatelessMath {
    static { NativeVectorMath.initialize(); }
    private NativeStatelessMath() { }
    public static native void initializeNoise();
    public static native float[] noiseLookupData();
    public static native void preparePhysics(int[] controls, float[] input, float[] pose, float[] output);
    public static native void solvePhysics(int count, int emitterSeed, int moduleSeed, float delta,
                                          int[][] required, int[][] components, float[] parameters);
    public static native void subImage(int count, int emitterSeed, int moduleSeed, int mode,
                                      int[][] required, int[][] output, float[] parameters);
    public static native void prepareInitialOrientation(int mode, float[] input, float[] output);
    public static native void initialOrientation(int count, int emitterSeed, int moduleSeed, int[][] required, int[][] output, float[] parameters);
    public static native void applyMeshRotation(int count, int[][] angles, int[][] output);
    public static native void toSimulationRotation(int space, float[] pose, float[] output);
    public static native void decalOrientation(int count, int[][] angles, int[][] output, float[] rotation);
    public static native void prepareOrbit(int centerSpace, int rotationSpace, float[] input, float[] pose, float[] output);
    public static native void orbit(int count, int emitterSeed, int moduleSeed, int[][] required, int[][] output, float[] parameters);
    public static native void prepareShape(int[] controls, float[] input, float[] pose, int[] mode, float[] parameters);
    public static native void shape(int count, int emitterSeed, int moduleSeed, int[] mode, float[] parameters,
                                   int[][] required, int[][] offsets, int[][] output);
}
