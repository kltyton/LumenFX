package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.effect.render.volume.VolumeRendererSettings;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;

/** Source-space proxy and live material for one heterogeneous volume. */
public record NiagaraVolumeInstance(MaterialDefinition material, Matrix4d localToWorld, Matrix4d primitiveComponentToWorld,
                                    NiagaraBounds localBounds, Vec3 actorPosition, PrimitiveCustomData customPrimitiveData,
                                    int resolutionX, int resolutionY, int resolutionZ, VolumeRendererSettings settings) {
    public NiagaraVolumeInstance {
        localToWorld = new Matrix4d(localToWorld);
        primitiveComponentToWorld = new Matrix4d(primitiveComponentToWorld);
    }
    @Override public Matrix4d localToWorld() { return new Matrix4d(localToWorld); }
    @Override public Matrix4d primitiveComponentToWorld() { return new Matrix4d(primitiveComponentToWorld); }

    public boolean intersectsLight(Vec3 position, float radius) {
        double x = (localBounds.minX() + localBounds.maxX()) * .5;
        double y = (localBounds.minY() + localBounds.maxY()) * .5;
        double z = (localBounds.minZ() + localBounds.maxZ()) * .5;
        double hx = localBounds.maxX() - x, hy = localBounds.maxY() - y, hz = localBounds.maxZ() - z;
        double dx = axisDistance(position.x, x, y, z, hx, hy, hz, localToWorld.m00(), localToWorld.m10(), localToWorld.m20(), localToWorld.m30());
        double dy = axisDistance(position.y, x, y, z, hx, hy, hz, localToWorld.m01(), localToWorld.m11(), localToWorld.m21(), localToWorld.m31());
        double dz = axisDistance(position.z, x, y, z, hx, hy, hz, localToWorld.m02(), localToWorld.m12(), localToWorld.m22(), localToWorld.m32());
        return dx * dx + dy * dy + dz * dz < (double)radius * radius;
    }

    private static double axisDistance(double point, double x, double y, double z, double hx, double hy, double hz,
                                       double a, double b, double c, double translation) {
        double extent = Math.abs(a) * hx + Math.abs(b) * hy + Math.abs(c) * hz;
        return Math.max(0, Math.abs(point - (a * x + b * y + c * z + translation)) - extent);
    }
}
