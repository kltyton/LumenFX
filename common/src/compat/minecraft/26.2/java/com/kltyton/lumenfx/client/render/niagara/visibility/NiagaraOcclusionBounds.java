package com.kltyton.lumenfx.client.render.niagara.visibility;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Projects the same component bounds used by the view relevance pass. */
record NiagaraOcclusionBounds(float minX, float minY, float maxX, float maxY, float nearestDepth, boolean nearPlane) {
    static @Nullable NiagaraOcclusionBounds projectWorld(Matrix4fc clip, Vec3 camera, NiagaraBounds world,
                                                         boolean zeroToOne) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, nearestDepth = 0;
        var corner = new Vector4f();
        int outside = 63;
        boolean near = false;
        for (int index = 0; index < 8; index++) {
            corner.set((float)(((index & 1) == 0 ? world.minX() : world.maxX()) * .01 - camera.x),
                    (float)(((index & 2) == 0 ? world.minZ() : world.maxZ()) * .01 - camera.y),
                    (float)(((index & 4) == 0 ? world.minY() : world.maxY()) * .01 - camera.z), 1);
            clip.transform(corner);
            float x = corner.x, y = corner.y, z = corner.z, w = corner.w;
            outside &= (x < -w ? 1 : 0) | (x > w ? 2 : 0) | (y < -w ? 4 : 0) | (y > w ? 8 : 0)
                    | (z < (zeroToOne ? 0 : -w) ? 16 : 0) | (z > w ? 32 : 0);
            if (w <= 0 || z > w) {
                near = true;
                continue;
            }
            float screenX = x / w * .5f + .5f;
            float screenY = y / w * .5f + .5f;
            float depth = z / w;
            minX = Math.min(minX, screenX); minY = Math.min(minY, screenY);
            maxX = Math.max(maxX, screenX); maxY = Math.max(maxY, screenY);
            nearestDepth = Math.max(nearestDepth, zeroToOne ? depth : depth * .5f + .5f);
        }
        if (outside != 0) return null;
        if (near) return new NiagaraOcclusionBounds(0, 0, 1, 1, 1, true);
        return new NiagaraOcclusionBounds(Math.clamp(minX, 0, 1), Math.clamp(minY, 0, 1),
                Math.clamp(maxX, 0, 1), Math.clamp(maxY, 0, 1), Math.clamp(nearestDepth, 0, 1), false);
    }
}
