package com.kltyton.lumenfx.client.render.niagara.gpu.scene;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField.Mip;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Native mesh distance bricks sampled in the same normalized volume space as the scene compositor. */
final class NiagaraMeshDistanceVolume {
    private static final int BRICK = StaticMeshDistanceField.BRICK_SIZE;
    private static final int UNIQUE_TEXELS = BRICK - 1;
    private record Level(Mip mip, ByteBuffer bricks, float sx, float sy, float sz, float ax, float ay, float az) {
        Level(Mip mip) {
            this(mip, mip.bricks(), (float)mip.uvScale().x * mip.x(), (float)mip.uvScale().y * mip.y(),
                    (float)mip.uvScale().z * mip.z(), (float)mip.uvAdd().x * mip.x(), (float)mip.uvAdd().y * mip.y(),
                    (float)mip.uvAdd().z * mip.z());
        }

        float distance(float x, float y, float z) {
            float u = x * sx + ax, v = y * sy + ay, w = z * sz + az;
            int ix = (int)u, iy = (int)v, iz = (int)w;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= mip.x() || iy >= mip.y() || iz >= mip.z())
                return maximum();
            int brick = mip.brickIndex((iz * mip.y() + iy) * mip.x() + ix);
            if (brick == -1) return maximum();

            float px = Math.clamp((u - ix) * UNIQUE_TEXELS, 0, UNIQUE_TEXELS);
            float py = Math.clamp((v - iy) * UNIQUE_TEXELS, 0, UNIQUE_TEXELS);
            float pz = Math.clamp((w - iz) * UNIQUE_TEXELS, 0, UNIQUE_TEXELS);
            int x0 = (int)px, y0 = (int)py, z0 = (int)pz;
            int x1 = Math.min(x0 + 1, UNIQUE_TEXELS), y1 = Math.min(y0 + 1, UNIQUE_TEXELS);
            int z1 = Math.min(z0 + 1, UNIQUE_TEXELS), offset = brick * StaticMeshDistanceField.BRICK_BYTES;
            float fx = px - x0, fy = py - y0, fz = pz - z0;
            float front = lerp(fy, row(offset, x0, x1, y0, z0, fx), row(offset, x0, x1, y1, z0, fx));
            float back = lerp(fy, row(offset, x0, x1, y0, z1, fx), row(offset, x0, x1, y1, z1, fx));
            return lerp(fz, front, back) * mip.distanceScale() + mip.distanceBias();
        }

        float maximum() { return mip.distanceScale() + mip.distanceBias(); }

        private float row(int offset, int x0, int x1, int y, int z, float alpha) {
            int start = offset + (z * BRICK + y) * BRICK;
            return lerp(alpha, Byte.toUnsignedInt(bricks.get(start + x0)), Byte.toUnsignedInt(bricks.get(start + x1))) / 255f;
        }
    }

    private final Matrix4dc relativeWorldToVolume;
    private final double centerX, centerY, centerZ;
    private final float extentX, extentY, extentZ, scaleX, scaleY, scaleZ, scale;
    private final AABB bounds;
    private final List<Level> levels;

    static @Nullable NiagaraMeshDistanceVolume create(Transform pose, StaticMeshDistanceField field) {
        var localCenter = field.minimum().add(field.maximum()).scale(.5);
        var half = field.maximum().subtract(field.minimum()).scale(.5);
        double extent = Math.max(half.x, Math.max(half.y, half.z));
        var localToWorld = NiagaraComponentMatrices.srt(pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                pose.scaleX(), pose.scaleY(), pose.scaleZ(), pose.x(), pose.y(), pose.z(), new Matrix4d());
        var center = localToWorld.transformPosition(new Vector3d(localCenter.x, localCenter.y, localCenter.z));
        var volumeToRelativeWorld = localToWorld.m30(0).m31(0).m32(0).scale(extent);
        double determinant = volumeToRelativeWorld.determinant();
        if (determinant == 0 || !Double.isFinite(determinant) || !center.isFinite()) return null;
        return new NiagaraMeshDistanceVolume(volumeToRelativeWorld, center,
                (float)(half.x / extent), (float)(half.y / extent), (float)(half.z / extent), field);
    }

    private NiagaraMeshDistanceVolume(Matrix4d volumeToWorld, Vector3d center, float x, float y, float z,
                                      StaticMeshDistanceField field) {
        centerX = center.x;
        centerY = center.y;
        centerZ = center.z;
        extentX = x;
        extentY = y;
        extentZ = z;
        scaleX = length(volumeToWorld.m00(), volumeToWorld.m01(), volumeToWorld.m02());
        scaleY = length(volumeToWorld.m10(), volumeToWorld.m11(), volumeToWorld.m12());
        scaleZ = length(volumeToWorld.m20(), volumeToWorld.m21(), volumeToWorld.m22());
        scale = Math.min(scaleX, Math.min(scaleY, scaleZ));
        double hx = Math.abs(volumeToWorld.m00()) * x + Math.abs(volumeToWorld.m10()) * y + Math.abs(volumeToWorld.m20()) * z;
        double hy = Math.abs(volumeToWorld.m01()) * x + Math.abs(volumeToWorld.m11()) * y + Math.abs(volumeToWorld.m21()) * z;
        double hz = Math.abs(volumeToWorld.m02()) * x + Math.abs(volumeToWorld.m12()) * y + Math.abs(volumeToWorld.m22()) * z;
        bounds = new AABB(centerX - hx, centerY - hy, centerZ - hz, centerX + hx, centerY + hy, centerZ + hz);
        relativeWorldToVolume = volumeToWorld.invert();
        levels = field.mips().stream().map(Level::new).toList().reversed();
    }

    AABB bounds() { return bounds; }

    double distance(double worldX, double worldY, double worldZ, double maximum) {
        double dx = worldX - centerX, dy = worldY - centerY, dz = worldZ - centerZ;
        var m = relativeWorldToVolume;
        float x = (float)(m.m00() * dx + m.m10() * dy + m.m20() * dz);
        float y = (float)(m.m01() * dx + m.m11() * dy + m.m21() * dz);
        float z = (float)(m.m02() * dx + m.m12() * dy + m.m22() * dz);
        float boxX = (Math.abs(x) - extentX) * scaleX;
        float boxY = (Math.abs(y) - extentY) * scaleY;
        float boxZ = (Math.abs(z) - extentZ) * scaleZ;
        float outside = length(Math.max(boxX, 0), Math.max(boxY, 0), Math.max(boxZ, 0));
        float boxDistance = outside + Math.min(0, Math.max(boxX, Math.max(boxY, boxZ)));
        if (boxDistance >= maximum) return maximum;
        x = Math.clamp(x, -extentX, extentX);
        y = Math.clamp(y, -extentY, extentY);
        z = Math.clamp(z, -extentZ, extentZ);
        float distance = 0;
        for (Level level : levels) {
            distance = level.distance(x, y, z);
            if (Math.abs(distance) > .25f * level.maximum()) break;
        }
        return Math.max(boxDistance, distance * scale + outside);
    }

    private static float length(double x, double y, double z) { return (float)Math.sqrt(x * x + y * y + z * z); }
    private static float lerp(float alpha, float start, float end) { return start + alpha * (end - start); }
}
