package com.kltyton.lumenfx.client.simulation.niagara.scalability;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** The actual game view, with the same normalized clip volume on each rendering backend. */
public final class NiagaraScalabilityView {
    private final Vec3 camera;
    private final List<Plane> planes = new ArrayList<>(6);

    private record Plane(float x, float y, float z, float offset) {
        boolean excludes(double px, double py, double pz, double radius) {
            return x * px + y * py + z * pz + offset < -radius;
        }
    }

    public NiagaraScalabilityView(Vec3 camera, Matrix4fc view, Matrix4fc projection, boolean zeroToOne) {
        this.camera = camera;
        var clip = new Matrix4f(projection).mul(view);
        if (zeroToOne) clip = new Matrix4f().m22(2).m32(-1).mul(clip);
        for (float sign : new float[] { -1, 1 }) {
            add(clip.m03() + sign * clip.m00(), clip.m13() + sign * clip.m10(), clip.m23() + sign * clip.m20(), clip.m33() + sign * clip.m30());
            add(clip.m03() + sign * clip.m01(), clip.m13() + sign * clip.m11(), clip.m23() + sign * clip.m21(), clip.m33() + sign * clip.m31());
            add(clip.m03() + sign * clip.m02(), clip.m13() + sign * clip.m12(), clip.m23() + sign * clip.m22(), clip.m33() + sign * clip.m32());
        }
    }

    private void add(float x, float y, float z, float offset) {
        float length = (float)Math.sqrt(x * x + y * y + z * z);
        if (length > 0 && Float.isFinite(length))
            planes.add(new Plane(x / length, y / length, z / length, offset / length));
    }

    public boolean intersects(Pose pose, NiagaraBounds componentBounds) {
        return intersectsWorldBounds(NiagaraComponentBounds.world(pose, componentBounds));
    }

    public boolean intersectsWorldBounds(NiagaraBounds world) {
        double centerX = (world.minX() + world.maxX()) * .005;
        double centerY = (world.minZ() + world.maxZ()) * .005;
        double centerZ = (world.minY() + world.maxY()) * .005;
        double extentX = (world.maxX() - world.minX()) * .005;
        double extentY = (world.maxZ() - world.minZ()) * .005;
        double extentZ = (world.maxY() - world.minY()) * .005;
        double radius = Math.sqrt(extentX * extentX + extentY * extentY + extentZ * extentZ);
        for (var plane : planes)
            if (plane.excludes(centerX - camera.x, centerY - camera.y, centerZ - camera.z, radius)) return false;
        return true;
    }
}
