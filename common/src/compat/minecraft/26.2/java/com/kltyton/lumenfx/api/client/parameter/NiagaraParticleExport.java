package com.kltyton.lumenfx.api.client.parameter;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Receives one batch from the particle export interface on the client world thread. */
@FunctionalInterface
public interface NiagaraParticleExport {
    record Particle(Vec3 position, float size, Vec3 velocity) { }

    /** Positions and velocities use the source simulation axes and units. The offset is in source centimeters. */
    void receive(List<Particle> particles, Vec3 simulationPositionOffset);
}
