package com.kltyton.lumenfx.effect.unity;

import net.minecraft.world.phys.Vec3;

/** Static system transform relative to the selected Unity effect root. */
public record UnityEmitterTransform(Vec3 position, float x, float y, float z, float w, Vec3 scale, boolean shapeOnlyScale) {
    public UnityEmitterTransform(Vec3 position, float x, float y, float z, float w, Vec3 scale) {
        this(position, x, y, z, w, scale, false);
    }

    public UnityEmitterTransform {
        float norm = x * x + y * y + z * z + w * w;
        if (!finite(position) || !finite(scale) || !Float.isFinite(norm) || norm <= 0
                || scale.x <= 0 || scale.y <= 0 || scale.z <= 0
                || scale.x > Float.MAX_VALUE || scale.y > Float.MAX_VALUE || scale.z > Float.MAX_VALUE)
            throw new IllegalArgumentException("Invalid Unity emitter transform");
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
