package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;

/** CPU configuration for the GPU Neighbor Query's per-particle slot and cell layout. */
public final class NiagaraNeighborQuery {
    public record Snapshot(int x, int y, int z, int slotsPerParticle, boolean countOnly, boolean persistentIds) {
        public int cells() { return Math.multiplyExact(Math.multiplyExact(x, y), z); }
    }

    public record Definition(int slotsPerParticle, boolean countOnly, boolean persistentIds) {
        public Definition {
            if (slotsPerParticle < 1) throw new IllegalArgumentException("Invalid Niagara Neighbor Query slots per particle");
        }
        public static Definition read(JsonObject value) {
            return new Definition(Math.toIntExact(value.get("slots_per_particle").getAsLong()),
                    value.get("count_only").getAsBoolean(), value.get("persistent_ids").getAsBoolean());
        }
    }

    private final Definition definition;
    private final long deviceCells;
    private volatile Snapshot state;

    public NiagaraNeighborQuery(Definition definition, long deviceCells) {
        this.definition = definition;
        this.deviceCells = deviceCells;
        state = new Snapshot(1, 1, 1, definition.slotsPerParticle(), definition.countOnly(), definition.persistentIds());
    }

    public Snapshot snapshot() { return state; }

    public boolean resize(int lanes, int x, int y, int z, int slots, NiagaraPlatformEnvironment platform) {
        if (lanes != 1 || x < 1 || y < 1 || z < 1 || slots < 1) return false;
        long max = Math.min(deviceCells, platform.neighborQueryCellMaximum());
        if ((long)x * y > max / z) return false;
        state = new Snapshot(x, y, z, slots, definition.countOnly(), definition.persistentIds());
        return true;
    }
}
