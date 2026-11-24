package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Niagara's per-event spawn counts and maximum-events limit. */
final class NiagaraEventSpawns {
    private NiagaraEventSpawns() { }

    static int[] collect(NiagaraDefinition.EventHandler handler, VectorDataSet data, boolean active) {
        int limit = eventLimit(handler, data);
        int[] counts = new int[limit];
        int written = count(handler, limit, active, counts);
        return Arrays.copyOf(counts, written);
    }

    static int total(NiagaraDefinition.EventHandler handler, VectorDataSet data, boolean active) {
        return count(handler, eventLimit(handler, data), active, null);
    }

    private static int eventLimit(NiagaraDefinition.EventHandler handler, VectorDataSet data) {
        return handler.maximumEventsPerFrame() > 0 ? (int)Math.min(data.count(), handler.maximumEventsPerFrame()) : data.count();
    }

    /** CPU retains the per-event output. The GPU path requests the aggregate with a null destination. */
    private static int count(NiagaraDefinition.EventHandler handler, int limit, boolean active, int @Nullable [] counts) {
        int written = 0;
        long total = 0;
        for (int i = 0; i < limit; i++) {
            long requested = handler.spawnNumber();
            if (handler.randomSpawnNumber() && handler.minimumSpawnNumber() < requested) {
                long minimum = handler.minimumSpawnNumber();
                if (minimum < 0 || requested > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Invalid Niagara event spawn range");
                int range = (int) requested - (int) minimum + 1;
                int offset = 0;
                if (range > 0) {
                    float random = NiagaraSeed.next() / 32767.0f;
                    offset = Math.min((int) (random * range), range - 1);
                }
                requested = minimum + offset;
            }
            if (active && requested > 0 && total + requested < Integer.MAX_VALUE) {
                if (counts != null) counts[written++] = (int)requested;
                total += requested;
            }
        }
        return counts == null ? (int)total : written;
    }
}
