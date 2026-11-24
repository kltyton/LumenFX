package com.kltyton.lumenfx.client.render.niagara;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Per-view macro UV projection for a Niagara sprite renderer. */
public final class ParticleMacroUV {
    private static final double WORLD_MAX_CM = 8796093022208.0;

    private ParticleMacroUV() { }

    public static float[] calculate(Vec3 actor, Vec3 camera, double radiusCm, Matrix4fc modelView, Matrix4fc projection,
                                    boolean lowerLeftOrigin) {
        if (radiusCm <= 0) return new float[] {0, 0, 1, 1};
        Matrix4f viewToWorld = new Matrix4f(modelView).invert();
        Vector3f right = viewToWorld.transformDirection(new Vector3f(1, 0, 0));
        Vector3f up = viewToWorld.transformDirection(new Vector3f(0, 1, 0));
        Vector3f origin = new Vector3f((float)(actor.x - camera.x), (float)(actor.y - camera.y), (float)(actor.z - camera.z));
        float radius = (float)(radiusCm / 100.0);
        Matrix4f worldToClip = new Matrix4f(projection).mul(modelView);
        Vector4f center = worldToClip.transform(new Vector4f(origin, 1));
        Vector4f rightPoint = worldToClip.transform(new Vector4f(new Vector3f(right).mul(radius).add(origin), 1));
        Vector4f upPoint = worldToClip.transform(new Vector4f(new Vector3f(up).mul(radius).add(origin), 1));
        double x = center.x / Math.max(center.w, .00001f);
        float ySign = lowerLeftOrigin ? 1 : -1;
        double y = ySign * center.y / Math.max(center.w, .00001f);
        double dx = Math.min(rightPoint.x / Math.max(rightPoint.w, .0001f) - x, WORLD_MAX_CM);
        double dy = Math.min(ySign * upPoint.y / Math.max(upPoint.w, .0001f) - y, WORLD_MAX_CM);
        float sx = 1, sy = 1;
        if (dx != 0 && dy != 0 && Double.isFinite(dx) && Double.isFinite(dy)) {
            sx = 1.0f / (float)dx;
            sy = -1.0f / (float)dy;
        }
        return new float[] {(float)x, (float)y, sx, sy};
    }
}
