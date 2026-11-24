package com.kltyton.lumenfx.effect.render.light.scene;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Authored environment and precomputed illumination for the current view. */
public record SceneIndirectLighting(@Nullable SkyLightIrradiance skyLight, @Nullable VolumetricLightmap lightmap,
                                    Vec3 precomputedColorScale, @Nullable LocalReflectionCaptures reflectionCaptures) {
    public static final SceneIndirectLighting NONE = new SceneIndirectLighting(null, null, new Vec3(1, 1, 1));

    public SceneIndirectLighting(@Nullable SkyLightIrradiance skyLight, @Nullable VolumetricLightmap lightmap,
                                 Vec3 precomputedColorScale) {
        this(skyLight, lightmap, precomputedColorScale, null);
    }

    public SceneIndirectLighting {
        Objects.requireNonNull(precomputedColorScale);
        if (!Double.isFinite(precomputedColorScale.x) || !Double.isFinite(precomputedColorScale.y)
                || !Double.isFinite(precomputedColorScale.z))
            throw new IllegalArgumentException("Invalid precomputed indirect lighting color scale");
    }
}
