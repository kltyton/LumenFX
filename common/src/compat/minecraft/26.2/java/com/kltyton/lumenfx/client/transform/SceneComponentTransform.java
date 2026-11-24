package com.kltyton.lumenfx.client.transform;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;

/** Source component SRT composition excludes shear and retains the negative-scale matrix branch. */
public final class SceneComponentTransform {
    private final Vector3d translation = new Vector3d(), scale = new Vector3d(1);
    private final Quaterniond rotation = new Quaterniond();
    private final Quaterniond relativeRotation = new Quaterniond();
    private @Nullable ComponentValue cachedAngles;
    private final Matrix4d matrix = new Matrix4d(), local = new Matrix4d();
    private final double[] quaternionScratch = new double[4];

    public static SceneComponentTransform authored(JsonObject scene) {
        boolean attached = scene.has("parent");
        var parent = attached ? authored(scene.getAsJsonObject("parent")) : new SceneComponentTransform();
        var result = new SceneComponentTransform();
        result.relative(sceneValue(scene, "location", ComponentValue.Kind.DOUBLE3),
                sceneValue(scene, "rotation", ComponentValue.Kind.ROTATOR),
                sceneValue(scene, "scale", ComponentValue.Kind.DOUBLE3),
                !attached || scene.get("absolute_location").getAsBoolean(),
                !attached || scene.get("absolute_rotation").getAsBoolean(),
                !attached || scene.get("absolute_scale").getAsBoolean(), parent);
        return result;
    }

    private static ComponentValue sceneValue(JsonObject scene, String name, ComponentValue.Kind kind) {
        var values = scene.getAsJsonArray(name);
        return ComponentValue.doubles(kind, values.get(0).getAsDouble(), values.get(1).getAsDouble(),
                values.get(2).getAsDouble(), 0);
    }

    public void owner(EffectHandle.Pose pose) {
        translation.set(pose.position().x * 100, pose.position().z * 100, pose.position().y * 100);
        scale.set(pose.scaleX(), pose.scaleZ(), pose.scaleY());
        rotation.set(-pose.x(), -pose.z(), -pose.y(), pose.w());
        updateMatrix();
    }

    public void relative(ComponentValue location, ComponentValue euler, ComponentValue relativeScale,
                         boolean absoluteLocation, boolean absoluteRotation, boolean absoluteScale,
                         SceneComponentTransform parent) {
        translation.set(location.number(0), location.number(1), location.number(2));
        scale.set(relativeScale.number(0), relativeScale.number(1), relativeScale.number(2));
        if (!euler.equals(cachedAngles)) {
            com.kltyton.lumenfx.client.render.math.NativeSceneMath.rotatorQuaternion(
                    euler.number(0), euler.number(1), euler.number(2), quaternionScratch);
            relativeRotation.set(quaternionScratch[0], quaternionScratch[1], quaternionScratch[2], quaternionScratch[3]);
            cachedAngles = euler;
        }
        rotation.set(relativeRotation);
        compose(absoluteLocation, absoluteRotation, absoluteScale, parent);
    }

    public void source(Vector3dc position, Quaterniondc orientation, Vector3dc scaling) {
        translation.set(position); rotation.set(orientation); scale.set(scaling);
        updateMatrix();
    }

    /** Source FTransform matrix constructor, including the reflected X axis. */
    public void source(Matrix4dc value) {
        matrix.set(value);
        translation.set(matrix.m30(), matrix.m31(), matrix.m32());
        scale.set(extractScale(0), extractScale(1), extractScale(2));
        if (value.determinant() < 0) {
            scale.x = -scale.x;
            matrix.m00(-matrix.m00()).m01(-matrix.m01()).m02(-matrix.m02());
        }
        quaternion(matrix, rotation, quaternionScratch);
        updateMatrix();
    }

    public void relative(Vector3dc position, Quaterniondc orientation, Vector3dc scaling, SceneComponentTransform parent) {
        translation.set(position); rotation.set(orientation); scale.set(scaling);
        compose(false, false, false, parent);
    }

    public void relative(Vector3dc position, Quaterniondc orientation, Vector3dc scaling,
                         boolean absoluteLocation, boolean absoluteRotation, boolean absoluteScale,
                         SceneComponentTransform parent) {
        translation.set(position); rotation.set(orientation); scale.set(scaling);
        compose(absoluteLocation, absoluteRotation, absoluteScale, parent);
    }

    private void compose(boolean absoluteLocation, boolean absoluteRotation, boolean absoluteScale, SceneComponentTransform parent) {
        if (!absoluteLocation || !absoluteRotation || !absoluteScale) {
            boolean negative = scale.x < 0 || scale.y < 0 || scale.z < 0 || parent.scale.x < 0 || parent.scale.y < 0 || parent.scale.z < 0;
            updateMatrix();
            local.set(matrix);
            matrix.set(parent.matrix).mul(local);
            if (!absoluteLocation) translation.set(matrix.m30(), matrix.m31(), matrix.m32());
            double sx = scale.x * parent.scale.x, sy = scale.y * parent.scale.y, sz = scale.z * parent.scale.z;
            if (!absoluteRotation) {
                if (negative) {
                    removeScale(matrix, sx, sy, sz);
                    quaternion(matrix, rotation, quaternionScratch);
                } else parent.rotation.mul(rotation, rotation);
            }
            if (!absoluteScale) scale.set(sx, sy, sz);
        }
        updateMatrix();
    }

    public Matrix4dc matrix() { return matrix; }
    public Vector3d scale(Vector3d destination) { return destination.set(scale); }
    public Quaterniond rotation(Quaterniond destination) { return destination.set(rotation); }

    public Matrix4d matrixNoScale(Matrix4d output) {
        return NiagaraComponentMatrices.srt(rotation.x, rotation.y, rotation.z, rotation.w, 1, 1, 1,
                translation.x, translation.y, translation.z, output);
    }

    private void updateMatrix() {
        NiagaraComponentMatrices.srt(rotation.x, rotation.y, rotation.z, rotation.w, scale.x, scale.y, scale.z,
                translation.x, translation.y, translation.z, matrix);
    }

    private double extractScale(int column) {
        double x = matrix.get(column, 0), y = matrix.get(column, 1), z = matrix.get(column, 2);
        double square = (x * x + y * y) + z * z;
        if (square <= 1.e-8) return 0;
        double length = Math.sqrt(square), inverse = 1 / length;
        matrix.set(column, 0, x * inverse).set(column, 1, y * inverse).set(column, 2, z * inverse);
        return length;
    }

    private static void removeScale(Matrix4d matrix, double sx, double sy, double sz) {
        for (int column = 0; column < 3; column++) {
            double x = matrix.get(column, 0), y = matrix.get(column, 1), z = matrix.get(column, 2);
            double square = (x * x + y * y) + z * z;
            double lengthInverse = square > 0 ? 1 / Math.sqrt(square) : 1;
            double desired = column == 0 ? sx : column == 1 ? sy : sz;
            double multiplier = (desired >= 0 ? 1 : -1) * lengthInverse;
            matrix.set(column, 0, x * multiplier).set(column, 1, y * multiplier).set(column, 2, z * multiplier);
        }
    }

    private static void quaternion(Matrix4d matrix, Quaterniond output, double[] values) {
        for (int column = 0; column < 3; column++)
            if (Math.abs(matrix.get(column, 0)) <= .0001 && Math.abs(matrix.get(column, 1)) <= .0001
                    && Math.abs(matrix.get(column, 2)) <= .0001) { output.identity(); return; }
        double trace = matrix.m00() + matrix.m11() + matrix.m22();
        if (trace > 0) {
            double inverse = 1 / Math.sqrt(trace + 1), s = .5 * inverse;
            output.set((matrix.m12() - matrix.m21()) * s, (matrix.m20() - matrix.m02()) * s,
                    (matrix.m01() - matrix.m10()) * s, .5 * (1 / inverse));
        } else {
            int i = matrix.m11() > matrix.m00() ? 1 : 0;
            if (matrix.m22() > matrix.get(i, i)) i = 2;
            int j = (i + 1) % 3, k = (j + 1) % 3;
            double inverse = 1 / Math.sqrt(matrix.get(i, i) - matrix.get(j, j) - matrix.get(k, k) + 1);
            values[i] = .5 * (1 / inverse);
            double s = .5 * inverse;
            values[3] = (matrix.get(j, k) - matrix.get(k, j)) * s;
            values[j] = (matrix.get(i, j) + matrix.get(j, i)) * s;
            values[k] = (matrix.get(i, k) + matrix.get(k, i)) * s;
            output.set(values[0], values[1], values[2], values[3]);
        }
        double size = (output.x * output.x + output.y * output.y) + (output.z * output.z + output.w * output.w);
        if (size >= 1.0e-8) {
            double inverse = 1 / Math.sqrt(size);
            output.set(output.x * inverse, output.y * inverse, output.z * inverse, output.w * inverse);
        } else output.identity();
    }
}
