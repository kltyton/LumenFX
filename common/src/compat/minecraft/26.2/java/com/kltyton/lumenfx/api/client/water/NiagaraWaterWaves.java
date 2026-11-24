package com.kltyton.lumenfx.api.client.water;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** A water waves asset sampled in source world coordinates. */
public interface NiagaraWaterWaves {
    record Sample(float height, Vec3 normal) {
        public Sample { Objects.requireNonNull(normal); }
    }

    Sample sample(Vec3 worldPosition, float depth, float worldSeconds, boolean simple);
    float attenuation(Vec3 worldPosition, float depth, float targetDepth);
}
