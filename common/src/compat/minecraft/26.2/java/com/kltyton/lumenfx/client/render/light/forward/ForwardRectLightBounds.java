package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.effect.render.light.scene.PointLightData;
import com.kltyton.lumenfx.effect.render.light.scene.RectLightShape;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** The emitter plane and the four native barn-door bounds, in positive-depth view space. */
final class ForwardRectLightBounds {
    private static final double MAX_BARN_COSINE = Math.cos(Math.toRadians(88));

    private ForwardRectLightBounds() { }

    static List<ForwardLightGrid.Plane> planes(PointLightData light, RectLightShape rect, Matrix4fc view,
                                              Vec3 sourceCamera, Vec3 center) {
        var negativeDirection = light.shader().direction();
        var direction = view.transformDirection(new Vector3f((float)negativeDirection.x, (float)negativeDirection.z,
                (float)negativeDirection.y));
        var normal = new Vec3(direction.x, direction.y, -direction.z);
        var front = new ForwardLightGrid.Plane(normal, -normal.dot(center));
        if (rect.barnLength() <= .5f || rect.barnCosine() <= MAX_BARN_COSINE) return List.of(front);

        var lightToWorld = light.worldToLight(new Matrix4d()).invert();
        double angle = Math.acos(rect.barnCosine());
        var horizontal = barn(rect.halfWidth(), rect.barnLength(), angle, light.radius());
        var vertical = barn(rect.halfHeight(), rect.barnLength(), angle, light.radius());
        Vec3[] h = corners(rect, horizontal, lightToWorld, view, sourceCamera);
        Vec3[] v = corners(rect, vertical, lightToWorld, view, sourceCamera);
        return List.of(front, plane(h[1], h[0], h[3]), plane(h[5], h[7], h[4]),
                plane(v[4], v[6], v[0]), plane(v[1], v[3], v[5]));
    }

    private record Barn(double extent, double depth) { }

    private static Barn barn(double halfSize, double length, double angle, double radius) {
        double size = halfSize * 2;
        double opposite = Math.sqrt(size * size + length * length - 2 * size * length * Math.cos(angle + Math.PI * .5));
        double auxiliary = Math.asin(Math.clamp(length * Math.sin(angle + Math.PI * .5) / opposite, -1, 1));
        double m = Math.tan(auxiliary), k = m * halfSize;
        double a = m * m + 1, b = 2 * m * k, c = k * k - radius * radius;
        double discriminant = b * b - 4 * a * c;
        double x = discriminant > 0.0001 ? (-b + Math.sqrt(discriminant)) / (2 * a) : -1;
        double y = m * x + k;
        if (Math.hypot(x + halfSize, y) >= opposite) return new Barn(x - halfSize, y);
        return new Barn(Math.sin(angle) * length, Math.cos(angle) * length);
    }

    private static Vec3[] corners(RectLightShape rect, Barn barn, Matrix4d lightToWorld, Matrix4fc view, Vec3 sourceCamera) {
        double x = barn.depth(), y = rect.halfWidth(), z = rect.halfHeight(), e = barn.extent();
        Vector3d[] points = {
                new Vector3d(0, y, z), new Vector3d(0, y, -z), new Vector3d(x, y + e, z + e), new Vector3d(x, y + e, -z - e),
                new Vector3d(0, -y, z), new Vector3d(0, -y, -z), new Vector3d(x, -y - e, z + e), new Vector3d(x, -y - e, -z - e)
        };
        Vec3[] result = new Vec3[points.length];
        var host = new Vector3f();
        for (int i = 0; i < points.length; i++) {
            var source = lightToWorld.transformPosition(points[i]);
            host.set((float)((source.x - sourceCamera.x) * .01), (float)((source.z - sourceCamera.z) * .01),
                    (float)((source.y - sourceCamera.y) * .01));
            view.transformPosition(host);
            result[i] = new Vec3(host.x * 100, host.y * 100, -host.z * 100);
        }
        return result;
    }

    private static ForwardLightGrid.Plane plane(Vec3 a, Vec3 b, Vec3 c) {
        var normal = b.subtract(a).cross(c.subtract(a)).normalize();
        return new ForwardLightGrid.Plane(normal, -normal.dot(a));
    }
}
