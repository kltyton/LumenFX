package com.kltyton.lumenfx.effect.noise;

import net.minecraft.world.phys.Vec3;

/** Field reference, lookup tiling and seeded translation in world units. */
public record FieldSampling(VectorFieldVolume volume, Vec3 offsetScale, int seedX, int seedY, int seedZ,
                            boolean ownerScale, boolean tileX, boolean tileY, boolean tileZ) {
    public FieldSampling {
        if (volume == null || offsetScale == null || !Double.isFinite(offsetScale.x)
                || !Double.isFinite(offsetScale.y) || !Double.isFinite(offsetScale.z))
            throw new IllegalArgumentException("Invalid vector field sampling parameters");
    }
}
