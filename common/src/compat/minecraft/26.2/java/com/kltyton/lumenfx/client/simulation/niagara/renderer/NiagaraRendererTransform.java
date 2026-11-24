package com.kltyton.lumenfx.client.simulation.niagara.renderer;

import com.kltyton.lumenfx.api.client.EffectHandle;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionfc;

/** Source positions cross the centimetre and axis boundary before the host's double world transform. */
public final class NiagaraRendererTransform {
    private final boolean localSpace;
    private double originX, originY, originZ, ownerX, ownerY, ownerZ, qx, qy, qz, qw, scaleX, scaleY, scaleZ;

    public NiagaraRendererTransform(boolean localSpace) { this.localSpace = localSpace; }

    public void prepare(EffectHandle.Pose pose, double originX, double originY, double originZ) {
        this.originX = originX; this.originY = originY; this.originZ = originZ;
        ownerX = pose.position().x; ownerY = pose.position().y; ownerZ = pose.position().z;
        qx = pose.x(); qy = pose.y(); qz = pose.z(); qw = pose.w();
        scaleX = pose.scaleX(); scaleY = pose.scaleY(); scaleZ = pose.scaleZ();
    }

    public Vec3 position(float sourceX, float sourceY, float sourceZ) {
        if (!localSpace) return new Vec3((sourceX + originX) * .01, (sourceZ + originZ) * .01, (sourceY + originY) * .01);
        double x = sourceX * .01 * scaleX, y = sourceZ * .01 * scaleY, z = sourceY * .01 * scaleZ;
        double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        return new Vec3(x + qw * tx + qy * tz - qz * ty + ownerX,
                y + qw * ty + qz * tx - qx * tz + ownerY,
                z + qw * tz + qx * ty - qy * tx + ownerZ);
    }

    public void rotation(Quaternionfc converted, Quaterniond output) {
        double x = converted.x(), y = converted.y(), z = converted.z(), w = converted.w();
        if (!localSpace) { output.set(x, y, z, w); return; }
        output.set(qw * x + qx * w + qy * z - qz * y, qw * y - qx * z + qy * w + qz * x,
                qw * z + qx * y - qy * x + qz * w, qw * w - qx * x - qy * y - qz * z);
    }
}
