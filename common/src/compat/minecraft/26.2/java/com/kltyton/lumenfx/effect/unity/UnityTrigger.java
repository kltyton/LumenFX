package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record UnityTrigger(List<Collider> colliders, double radiusScale, Query query,
                           Action inside, Action outside, Action enter, Action exit) {
    public enum Query { DISABLED, ONE, ALL }
    public enum Action { IGNORE, KILL, CALLBACK }
    public enum Shape { SPHERE, BOX, CAPSULE, MESH, HOST }
    public record Collider(String name, Shape shape, Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, Vec3 size,
                           int direction, @Nullable StaticMeshGeometry mesh, boolean convex, String hostKey) {
        public Collider(String name, Shape shape, Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, Vec3 size) {
            this(name, shape, center, xAxis, yAxis, zAxis, size, -1, null, false, "");
        }
        public Collider(String name, Shape shape, Vec3 center, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, Vec3 size, int direction) {
            this(name, shape, center, xAxis, yAxis, zAxis, size, direction, null, false, "");
        }
        public static Collider host(String name, String hostKey) {
            return new Collider(name, Shape.HOST, Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0),
                    new Vec3(0, 0, 1), new Vec3(1, 1, 1), -1, null, false, hostKey);
        }
        public Collider {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(xAxis, "xAxis");
            Objects.requireNonNull(yAxis, "yAxis");
            Objects.requireNonNull(zAxis, "zAxis");
            Objects.requireNonNull(size, "size");
            hostKey = hostKey == null ? "" : hostKey;
            if (name.isBlank()) throw new IllegalArgumentException("Invalid Unity trigger collider name");
            if (shape == Shape.HOST) {
                if (hostKey.isBlank()) throw new IllegalArgumentException("Unity HOST trigger requires hostKey: " + name);
            } else {
                if (size.x <= 0 || size.y <= 0 || size.z <= 0
                        || !Double.isFinite(size.lengthSqr()) || !Double.isFinite(center.lengthSqr())
                        || !Double.isFinite(xAxis.lengthSqr()) || !Double.isFinite(yAxis.lengthSqr())
                        || !Double.isFinite(zAxis.lengthSqr())
                        || shape == Shape.CAPSULE && (direction < 0 || direction > 2)
                        || shape == Shape.MESH && (mesh == null || mesh.indexCount() < 3))
                    throw new IllegalArgumentException("Invalid Unity trigger collider " + name);
            }
        }
    }

    public UnityTrigger {
        colliders = List.copyOf(colliders);
        if (colliders.size() > Long.SIZE || radiusScale < 0 || !Double.isFinite(radiusScale))
            throw new IllegalArgumentException("Invalid Unity trigger module");
    }
}
