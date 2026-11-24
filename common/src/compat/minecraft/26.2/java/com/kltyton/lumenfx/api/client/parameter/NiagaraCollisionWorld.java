package com.kltyton.lumenfx.api.client.parameter;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Supplies the first blocking hit for a source collision channel, in Minecraft world coordinates. */
@FunctionalInterface
public interface NiagaraCollisionWorld {
    /** Called on the client thread. A null result means the segment had no blocking hit. */
    @Nullable Hit trace(Vec3 start, Vec3 end, int sourceChannel, boolean traceComplex);

    record Hit(Vec3 position, Vec3 normal, boolean inside, float friction, float restitution, int materialId) {
        public Hit {
            Objects.requireNonNull(position);
            Objects.requireNonNull(normal);
        }
    }
}
