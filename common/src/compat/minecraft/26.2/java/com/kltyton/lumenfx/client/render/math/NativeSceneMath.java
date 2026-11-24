package com.kltyton.lumenfx.client.render.math;

import com.kltyton.lumenfx.client.simulation.niagara.NativeVectorMath;

/** Double scene rotations share the existing Windows intrinsic library with the CPU VM. */
public final class NativeSceneMath {
    static { NativeVectorMath.initialize(); }
    private NativeSceneMath() { }

    public static void rotatorQuaternion(double pitch, double yaw, double roll, double[] quaternion) {
        if (quaternion.length < 4) throw new IllegalArgumentException("Quaternion output needs four components");
        evaluateRotator(pitch, yaw, roll, quaternion);
    }

    private static native void evaluateRotator(double pitch, double yaw, double roll, double[] quaternion);
    public static native float power(float base, float exponent);
    public static native double tangent(double radians);
}
