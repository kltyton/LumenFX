package com.kltyton.lumenfx.client.transform;

import com.kltyton.lumenfx.api.client.EffectHandle;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;

/** Source-axis component SRT and the engine's safe inverse contract. */
public final class NiagaraComponentMatrices {
    private NiagaraComponentMatrices() { }

    public static Matrix4d source(EffectHandle.Pose pose, double originX, double originY, double originZ,
                                  boolean includeScale, Matrix4d output) {
        double x = -pose.x(), y = -pose.z(), z = -pose.y(), w = pose.w();
        double sx = includeScale ? pose.scaleX() : 1;
        double sy = includeScale ? pose.scaleZ() : 1;
        double sz = includeScale ? pose.scaleY() : 1;
        return srt(x, y, z, w, sx, sy, sz, pose.position().x * 100 - originX,
                pose.position().z * 100 - originY, pose.position().y * 100 - originZ, output);
    }

    public static Matrix4d srt(double x, double y, double z, double w, double sx, double sy, double sz,
                               double tx, double ty, double tz, Matrix4d output) {
        double x2 = x + x, y2 = y + y, z2 = z + z;
        double xx = x * x2, yy = y * y2, zz = z * z2;
        double xy = x * y2, xz = x * z2, yz = y * z2;
        double wx = w * x2, wy = w * y2, wz = w * z2;
        return output.identity().m00((1 - (yy + zz)) * sx).m01((xy + wz) * sx).m02((xz - wy) * sx)
                .m10((xy - wz) * sy).m11((1 - (xx + zz)) * sy).m12((yz + wx) * sy)
                .m20((xz + wy) * sz).m21((yz - wx) * sz).m22((1 - (xx + yy)) * sz)
                .m30(tx).m31(ty)
                .m32(tz);
    }

    public static Matrix4d inverse(Matrix4dc input, Matrix4d output) {
        boolean nil = Math.abs(input.m00()) <= 1.0e-8 && Math.abs(input.m01()) <= 1.0e-8 && Math.abs(input.m02()) <= 1.0e-8
                && Math.abs(input.m10()) <= 1.0e-8 && Math.abs(input.m11()) <= 1.0e-8 && Math.abs(input.m12()) <= 1.0e-8
                && Math.abs(input.m20()) <= 1.0e-8 && Math.abs(input.m21()) <= 1.0e-8 && Math.abs(input.m22()) <= 1.0e-8;
        double determinant = input.determinant();
        if (nil || determinant == 0 || !Double.isFinite(determinant)) return output.identity();
        return output.set(input).invert();
    }

    public static Matrix4d minecraft(EffectHandle.Pose pose, boolean includeScale, Matrix4d output) {
        source(pose, 0, 0, 0, includeScale, output);
        double xx = output.m00(), xy = output.m02(), xz = output.m01();
        double yx = output.m20(), yy = output.m22(), yz = output.m21();
        double zx = output.m10(), zy = output.m12(), zz = output.m11();
        return output.identity().m00(xx).m01(xy).m02(xz).m10(yx).m11(yy).m12(yz)
                .m20(zx).m21(zy).m22(zz).m30(pose.position().x).m31(pose.position().y).m32(pose.position().z);
    }

    public static Matrix4d relative(Matrix4dc absolute, double x, double y, double z, boolean previous, Matrix4d output) {
        double tx = absolute.m30() - x, ty = absolute.m31() - y, tz = absolute.m32() - z;
        if (previous) {
            double maximum = (1 << 23) * .25 - 1;
            tx = Math.clamp(tx, -maximum, maximum); ty = Math.clamp(ty, -maximum, maximum); tz = Math.clamp(tz, -maximum, maximum);
        }
        return output.set(absolute).m30(tx).m31(ty).m32(tz);
    }

    public static float axisScale(EffectHandle.Pose pose, int axis) {
        return switch (axis) {
            case 0 -> pose.scaleX();
            case 1 -> pose.scaleY();
            case 2 -> pose.scaleZ();
            default -> throw new IllegalArgumentException("Invalid component axis");
        };
    }

    public static float maximumScale(EffectHandle.Pose pose) {
        return Math.max(Math.abs(pose.scaleX()), Math.max(Math.abs(pose.scaleY()), Math.abs(pose.scaleZ())));
    }

    public static float inverseAxisScale(EffectHandle.Pose pose, int axis, boolean previousRibbon) {
        double x = pose.x(), y = pose.y(), z = pose.z(), w = pose.w();
        double a, b, c;
        switch (axis) {
            case 0 -> { a = 1 - 2 * (y * y + z * z); b = 2 * (x * y + w * z); c = 2 * (x * z - w * y); }
            case 1 -> { a = 2 * (x * y - w * z); b = 1 - 2 * (x * x + z * z); c = 2 * (y * z + w * x); }
            case 2 -> { a = 2 * (x * z + w * y); b = 2 * (y * z - w * x); c = 1 - 2 * (x * x + y * y); }
            default -> throw new IllegalArgumentException("Invalid component axis");
        }
        float scale = axisScale(pose, axis);
        float ax = (float)(a * scale), ay = (float)(b * scale), az = (float)(c * scale);
        float square = (ax * ax + ay * ay) + az * az;
        if (previousRibbon) return (float)(1 / Math.sqrt(Math.max(0.0001f, square)));
        float length = (float)Math.sqrt(square);
        return length > 0.0001f ? 1f / length : 0;
    }
}
