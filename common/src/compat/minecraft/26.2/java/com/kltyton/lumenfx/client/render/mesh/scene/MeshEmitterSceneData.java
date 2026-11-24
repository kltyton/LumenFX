package com.kltyton.lumenfx.client.render.mesh.scene;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** CPU-upload branch for an emitter-source mesh with default facing and no locked axis. */
public final class MeshEmitterSceneData {
    public static final int BYTES = 256;
    private final Matrix4f current, previous, worldToPrevious;
    private final float determinant;
    private final float highX, highY, highZ;

    private MeshEmitterSceneData(Matrix4f current, Matrix4f previous, Matrix4f worldToPrevious, float highX, float highY, float highZ) {
        this.current = current;
        this.previous = previous;
        this.worldToPrevious = worldToPrevious;
        this.highX = highX; this.highY = highY; this.highZ = highZ;
        determinant = current.determinant3x3();
    }

    public static boolean applies(MeshRendererSettings settings, boolean emitterSource) {
        return emitterSource && settings.facing() == MeshRendererSettings.Facing.DEFAULT && !settings.lockedAxisEnabled()
                && settings.sceneTransforms() != MeshRendererSettings.SceneTransforms.LEGACY;
    }

    public static MeshEmitterSceneData prepare(MeshRendererSettings.Entry mesh, boolean localSpace, boolean precise,
                                               float[] position, float[] rotation, float[] scale,
                                               float[] oldPosition, float[] oldRotation, float[] oldScale,
                                               Vec3 simulationOrigin, EffectHandle.Pose owner, EffectHandle.Pose oldOwner, Vec3 camera) {
        Matrix4d ownerMatrix = owner(owner), oldOwnerMatrix = owner(oldOwner);
        var pivot = new org.joml.Vector3d(mesh.pivot().x, mesh.pivot().y, mesh.pivot().z);
        if (mesh.pivotSpace() == MeshRendererSettings.PivotSpace.LOCAL
                || localSpace && mesh.pivotSpace() == MeshRendererSettings.PivotSpace.SIMULATION)
            ownerMatrix.transformDirection(pivot);
        Vector3f meshOffset = new Vector3f((float)pivot.x, (float)pivot.y, (float)pivot.z);
        Matrix4d local = transform(position, rotation, scale, mesh, meshOffset);
        if (!localSpace) {
            local.m30(local.m30() + simulationOrigin.x).m31(local.m31() + simulationOrigin.y).m32(local.m32() + simulationOrigin.z);
            local = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(ownerMatrix, new Matrix4d()).mul(local);
        }
        Matrix4d oldLocal = precise ? transform(oldPosition, oldRotation, oldScale, mesh, meshOffset) : new Matrix4d(local);
        if (!localSpace) {
            oldLocal.m30(oldLocal.m30() + simulationOrigin.x).m31(oldLocal.m31() + simulationOrigin.y).m32(oldLocal.m32() + simulationOrigin.z);
            oldLocal = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(oldOwnerMatrix, new Matrix4d()).mul(oldLocal);
        }
        // Both uploads are relative to the current primitive's high origin.
        double highX = (float)ownerMatrix.m30(), highY = (float)ownerMatrix.m31(), highZ = (float)ownerMatrix.m32();
        Matrix4f current = relative(ownerMatrix, highX, highY, highZ, false).mul(new Matrix4f(local));
        Matrix4f previous = relative(oldOwnerMatrix, highX, highY, highZ, true).mul(new Matrix4f(oldLocal));
        orthogonalize(current);
        orthogonalize(previous);
        Matrix4d relativeOwner = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.relative(
                ownerMatrix, highX, highY, highZ, false, new Matrix4d());
        Matrix4d relativeOldOwner = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.relative(
                oldOwnerMatrix, highX, highY, highZ, true, new Matrix4d());
        Matrix4d inverse = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(relativeOwner, new Matrix4d());
        Matrix4f worldToPrevious = new Matrix4f(relativeOldOwner.mul(inverse));
        return new MeshEmitterSceneData(current, previous, worldToPrevious, (float)highX, (float)highY, (float)highZ);
    }

    public void write(ByteBuffer data) {
        int start = data.position();
        current.get(start, data);
        previous.get(start + 64, data);
        data.position(start + 128);
        CpuSceneTransformCodec.writeRotationScale(current, data);
        CpuSceneTransformCodec.writeRotationScale(previous, data);
        data.putFloat(determinant < 0 ? -1 : 1).putFloat(determinant == 0 ? 1 : 0).putFloat(0).putFloat(0);
        data.putFloat(highX).putFloat(highY).putFloat(highZ).putFloat(0);
        int previousWorldOffset = data.position();
        worldToPrevious.get(previousWorldOffset, data);
        data.position(previousWorldOffset + 64);
    }

    private static Matrix4f relative(Matrix4d matrix, double x, double y, double z, boolean clamp) {
        return new Matrix4f(com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.relative(matrix, x, y, z, clamp, new Matrix4d()));
    }

    private static Matrix4d owner(EffectHandle.Pose pose) {
        return com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.source(pose, 0, 0, 0, true, new Matrix4d());
    }

    private static Matrix4d transform(float[] position, float[] rotation, float[] scale,
                                      MeshRendererSettings.Entry mesh, Vector3f offset) {
        float sum = (rotation[0] * rotation[0] + rotation[1] * rotation[1])
                + (rotation[2] * rotation[2] + rotation[3] * rotation[3]);
        float x = 0, y = 0, z = 0, w = 1;
        if (sum >= 1.0e-8f) {
            float inverse = 1f / (float)Math.sqrt(sum);
            x = rotation[0] * inverse; y = rotation[1] * inverse;
            z = rotation[2] * inverse; w = rotation[3] * inverse;
        }
        var other = mesh.rotation();
        float qx = w * other.x(), qy = w * other.y(), qz = w * other.z(), qw = w * other.w();
        qx += x * other.w(); qy += x * -other.z(); qz += x * other.y(); qw += x * -other.x();
        qx += y * other.z(); qy += y * other.w(); qz += y * -other.x(); qw += y * -other.y();
        qx += z * -other.y(); qy += z * other.x(); qz += z * other.w(); qw += z * -other.z();
        return com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.srt(qx, qy, qz, qw,
                scale[0] * (float)mesh.scale().x, scale[1] * (float)mesh.scale().y,
                scale[2] * (float)mesh.scale().z, position[0] + offset.x, position[1] + offset.y, position[2] + offset.z, new Matrix4d());
    }

    private static void orthogonalize(Matrix4f matrix) {
        Vector3f x = new Vector3f(matrix.m00(), matrix.m01(), matrix.m02());
        Vector3f y = new Vector3f(matrix.m10(), matrix.m11(), matrix.m12());
        Vector3f z = new Vector3f(matrix.m20(), matrix.m21(), matrix.m22());
        if (x.x == 0 && x.y == 0 && x.z == 0 || y.x == 0 && y.y == 0 && y.z == 0) return;
        subtract(y, x, dot(y, x) / dot(x, x));
        subtract(z, x, dot(z, x) / dot(x, x));
        subtract(z, y, dot(z, y) / dot(y, y));
        matrix.m00(x.x).m01(x.y).m02(x.z).m10(y.x).m11(y.y).m12(y.z).m20(z.x).m21(z.y).m22(z.z);
    }

    private static float dot(Vector3f first, Vector3f second) { return first.x * second.x + first.y * second.y + first.z * second.z; }
    private static void subtract(Vector3f target, Vector3f direction, float scale) {
        target.x -= direction.x * scale; target.y -= direction.y * scale; target.z -= direction.z * scale;
    }
}
