package com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

final class GeometryTransforms {
    static final Transform IDENTITY = new Transform(0, 0, 0, 0, 0, 0, 1, 1, 1, 1);

    private GeometryTransforms() { }

    static Transform translated(Transform value, Vec3 offset) {
        return new Transform(value.x() + offset.x, value.y() + offset.y, value.z() + offset.z,
                value.qx(), value.qy(), value.qz(), value.qw(), value.scaleX(), value.scaleY(), value.scaleZ());
    }

    static Transform compose(Transform local, Transform parent) {
        var sourceParent = new SceneComponentTransform();
        sourceParent.source(translation(parent), rotation(parent), scale(parent));
        var sourceLocal = new SceneComponentTransform();
        sourceLocal.relative(translation(local), rotation(local), scale(local), sourceParent);
        var matrix = sourceLocal.matrix();
        var q = sourceLocal.rotation(new Quaterniond());
        var s = sourceLocal.scale(new Vector3d());
        return new Transform(matrix.m30(), matrix.m31(), matrix.m32(), q.x, q.y, q.z, q.w, s.x, s.y, s.z);
    }

    static Transform inverse(Transform value) {
        var s = new Vector3d(reciprocal(value.scaleX()), reciprocal(value.scaleY()), reciprocal(value.scaleZ()));
        if (s.lengthSquared() == 0) return IDENTITY;
        var q = rotation(value).conjugate();
        var t = q.transform(translation(value).mul(s).negate());
        return new Transform(t.x, t.y, t.z, q.x, q.y, q.z, q.w, s.x, s.y, s.z);
    }

    static void packedMatrices(Transform transform, float[] matrix, float[] inverse, int offset) {
        var position = new Vector3f((float)transform.x(), (float)transform.y(), (float)transform.z());
        var rotation = new Quaternionf((float)transform.qx(), (float)transform.qy(), (float)transform.qz(), (float)transform.qw());
        var scale = new Vector3f((float)transform.scaleX(), (float)transform.scaleY(), (float)transform.scaleZ());
        var scratch = new Matrix4f();
        scratch.translationRotateScale(position, rotation, scale);
        pack(scratch, matrix, offset);
        scale.set(reciprocal(scale.x), reciprocal(scale.y), reciprocal(scale.z));
        if (scale.lengthSquared() == 0) scratch.identity();
        else {
            rotation.conjugate();
            rotation.transform(position.mul(scale).negate());
            scratch.translationRotateScale(position, rotation, scale);
        }
        pack(scratch, inverse, offset);
    }

    private static void pack(Matrix4f matrix, float[] output, int offset) {
        output[offset] = matrix.m00(); output[offset + 1] = matrix.m10();
        output[offset + 2] = matrix.m20(); output[offset + 3] = matrix.m30();
        output[offset + 4] = matrix.m01(); output[offset + 5] = matrix.m11();
        output[offset + 6] = matrix.m21(); output[offset + 7] = matrix.m31();
        output[offset + 8] = matrix.m02(); output[offset + 9] = matrix.m12();
        output[offset + 10] = matrix.m22(); output[offset + 11] = matrix.m32();
    }

    static boolean equivalent(Transform left, Transform right) {
        boolean sameRotation = near(left.qx(), right.qx()) && near(left.qy(), right.qy()) && near(left.qz(), right.qz()) && near(left.qw(), right.qw());
        boolean oppositeRotation = near(left.qx(), -right.qx()) && near(left.qy(), -right.qy()) && near(left.qz(), -right.qz()) && near(left.qw(), -right.qw());
        return (sameRotation || oppositeRotation) && near(left.x(), right.x()) && near(left.y(), right.y()) && near(left.z(), right.z())
                && near(left.scaleX(), right.scaleX()) && near(left.scaleY(), right.scaleY()) && near(left.scaleZ(), right.scaleZ());
    }

    static Quaterniond rotation(Transform value) {
        return new Quaterniond(value.qx(), value.qy(), value.qz(), value.qw());
    }

    private static Vector3d translation(Transform value) { return new Vector3d(value.x(), value.y(), value.z()); }
    private static Vector3d scale(Transform value) { return new Vector3d(value.scaleX(), value.scaleY(), value.scaleZ()); }
    private static boolean near(double a, double b) { return Math.abs(a - b) <= 1e-4; }
    private static double reciprocal(double value) { return Math.abs(value) <= 1e-8 ? 0 : 1 / value; }
    private static float reciprocal(float value) { return Math.abs(value) <= 1e-8f ? 0f : 1f / value; }
}
