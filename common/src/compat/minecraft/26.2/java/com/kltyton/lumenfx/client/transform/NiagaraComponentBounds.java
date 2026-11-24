package com.kltyton.lumenfx.client.transform;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Source component box transformed to world space; distances remain in centimetres. */
public final class NiagaraComponentBounds {
    private NiagaraComponentBounds() { }

    public static NiagaraBounds world(EffectHandle.Pose pose, NiagaraBounds bounds) {
        return relative(pose, bounds, Vec3.ZERO);
    }

    public static NiagaraBounds relative(EffectHandle.Pose pose, NiagaraBounds bounds, Vec3 originCm) {
        var transform = NiagaraComponentMatrices.source(pose, originCm.x, originCm.y, originCm.z, true, new Matrix4d());
        var minimum = new Vector3d();
        var maximum = new Vector3d();
        transform.transformAab(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), minimum, maximum);
        return new NiagaraBounds(minimum.x, minimum.y, minimum.z, maximum.x, maximum.y, maximum.z);
    }

    public static double distanceSquared(EffectHandle.Pose pose, NiagaraBounds bounds, Vec3 point) {
        Matrix4d transform = NiagaraComponentMatrices.source(pose, 0, 0, 0, true, new Matrix4d());
        Vector3d center = center(transform, bounds);
        double x = (bounds.maxX() - bounds.minX()) * .5, y = (bounds.maxY() - bounds.minY()) * .5;
        double z = (bounds.maxZ() - bounds.minZ()) * .5;
        double ex = extent(x, y, z, transform.m00(), transform.m10(), transform.m20());
        double ey = extent(x, y, z, transform.m01(), transform.m11(), transform.m21());
        double ez = extent(x, y, z, transform.m02(), transform.m12(), transform.m22());
        double dx = Math.max(0, Math.abs(point.x * 100 - center.x) - ex);
        double dy = Math.max(0, Math.abs(point.z * 100 - center.y) - ey);
        double dz = Math.max(0, Math.abs(point.y * 100 - center.z) - ez);
        return dx * dx + dy * dy + dz * dz;
    }

    public static Vec3 center(EffectHandle.Pose pose, NiagaraBounds bounds) {
        var center = center(NiagaraComponentMatrices.source(pose, 0, 0, 0, true, new Matrix4d()), bounds);
        return new Vec3(center.x, center.y, center.z);
    }

    public static BoxSphere boxSphere(Matrix4dc transform, @Nullable NiagaraBounds bounds) {
        if (bounds == null)
            return new BoxSphere(new Vec3(transform.m30(), transform.m31(), transform.m32()), Vec3.ZERO, 0);
        Vector3d center = center(transform, bounds);
        double x = (bounds.maxX() - bounds.minX()) * .5;
        double y = (bounds.maxY() - bounds.minY()) * .5;
        double z = (bounds.maxZ() - bounds.minZ()) * .5;
        Vec3 extent = new Vec3(extent(x, y, z, transform.m00(), transform.m10(), transform.m20()),
                extent(x, y, z, transform.m01(), transform.m11(), transform.m21()),
                extent(x, y, z, transform.m02(), transform.m12(), transform.m22()));
        double scaleSquared = Math.max(lengthSquared(transform.m00(), transform.m10(), transform.m20()),
                Math.max(lengthSquared(transform.m01(), transform.m11(), transform.m21()),
                        lengthSquared(transform.m02(), transform.m12(), transform.m22())));
        double radius = Math.min(Math.sqrt(lengthSquared(x, y, z) * scaleSquared), extent.length());
        return new BoxSphere(new Vec3(center.x, center.y, center.z), extent, radius);
    }

    public record BoxSphere(Vec3 origin, Vec3 extent, double radius) { }

    private static Vector3d center(Matrix4dc transform, NiagaraBounds bounds) {
        return transform.transformPosition(new Vector3d((bounds.minX() + bounds.maxX()) * .5,
                (bounds.minY() + bounds.maxY()) * .5, (bounds.minZ() + bounds.maxZ()) * .5));
    }

    private static double extent(double x, double y, double z, double a, double b, double c) {
        return Math.abs(a) * x + Math.abs(b) * y + Math.abs(c) * z;
    }

    private static double lengthSquared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
