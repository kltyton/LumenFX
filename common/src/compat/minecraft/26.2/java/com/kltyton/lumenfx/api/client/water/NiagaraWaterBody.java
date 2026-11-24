package com.kltyton.lumenfx.api.client.water;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** An immutable query source in UE world centimeters, with Z up. Queries may run on simulation workers. */
public interface NiagaraWaterBody {
    int index();

    QueryResult query(Vec3 worldPosition, Flags flags);

    record Flags(boolean location, boolean normal, boolean velocity, boolean depth, boolean waves, boolean simpleWaves) {
        public Flags {
            location |= depth;
            simpleWaves &= waves;
        }
    }

    sealed interface QueryResult permits Surface, QueryError { }

    record Surface(Vec3 planeLocation, Vec3 planeNormal, Vec3 surfaceLocation, Vec3 surfaceNormal, float depth,
                   Vec3 velocity, float waveHeight, boolean excluded) implements QueryResult {
        public Surface {
            Objects.requireNonNull(planeLocation);
            Objects.requireNonNull(planeNormal);
            Objects.requireNonNull(surfaceLocation);
            Objects.requireNonNull(surfaceNormal);
            Objects.requireNonNull(velocity);
        }
    }

    record QueryError(String reason) implements QueryResult {
        public QueryError {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A water query error needs a reason");
        }
    }
}
