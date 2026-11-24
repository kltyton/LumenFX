package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;

/** Matches the opaque velocity pass's component transform comparison in source centimeters. */
public final class NiagaraVelocityPolicy {
    private static final double TOLERANCE = (double)0.0001f;
    private NiagaraVelocityPolicy() { }

    public static boolean moved(EffectHandle.Pose current, EffectHandle.Pose previous) {
        var a = current.position(); var b = previous.position();
        if (Math.abs((a.x - b.x) * 100) > TOLERANCE || Math.abs((a.y - b.y) * 100) > TOLERANCE
                || Math.abs((a.z - b.z) * 100) > TOLERANCE) return true;
        for (int element = 0; element < 9; element++)
            if (Math.abs(rotation(current, element) * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.axisScale(current, element % 3)
                    - rotation(previous, element) * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.axisScale(previous, element % 3)) > TOLERANCE)
                return true;
        return false;
    }

    private static double rotation(EffectHandle.Pose pose, int element) {
        double x = pose.x(), y = pose.y(), z = pose.z(), w = pose.w();
        return switch (element) {
            case 0 -> 1 - 2*(y*y + z*z);
            case 1 -> 2*(x*y - w*z);
            case 2 -> 2*(x*z + w*y);
            case 3 -> 2*(x*y + w*z);
            case 4 -> 1 - 2*(x*x + z*z);
            case 5 -> 2*(y*z - w*x);
            case 6 -> 2*(x*z - w*y);
            case 7 -> 2*(y*z + w*x);
            case 8 -> 1 - 2*(x*x + y*y);
            default -> throw new IllegalArgumentException("Invalid transform matrix element");
        };
    }
}
