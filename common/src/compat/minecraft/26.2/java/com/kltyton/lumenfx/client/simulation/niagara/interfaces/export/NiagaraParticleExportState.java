package com.kltyton.lumenfx.client.simulation.niagara.interfaces.export;

import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport;
import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport.Particle;
import com.kltyton.lumenfx.effect.niagara.export.NiagaraParticleExportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One physical Export DI object's gathered particles and pre-tick handler. */
public final class NiagaraParticleExportState {
    public record Snapshot(NiagaraParticleExportDefinition definition, @Nullable NiagaraParticleExport callback, Vec3 offset) { }
    private final NiagaraParticleExportDefinition definition;
    private final ConcurrentLinkedQueue<Particle> particles = new ConcurrentLinkedQueue<>();
    private volatile @Nullable Snapshot current;

    public NiagaraParticleExportState(NiagaraParticleExportDefinition definition) { this.definition = definition; }
    public NiagaraParticleExportDefinition definition() { return definition; }

    public void capture(Map<String, NiagaraParticleExport> handlers, double x, double y, double z) {
        current = new Snapshot(definition, handlers.get(definition.parameter()), new Vec3(x, y, z));
    }

    public @Nullable Snapshot snapshot() { return current; }

    public boolean store(float x, float y, float z, float size, float vx, float vy, float vz) {
        var snapshot = current;
        return snapshot != null && snapshot.callback() != null
                && particles.add(new Particle(new Vec3(x, y, z), size, new Vec3(vx, vy, vz)));
    }

    public void postSimulate(@Nullable ClientLevel world) {
        if (particles.isEmpty()) return;
        var pending = new ArrayList<Particle>();
        for (Particle particle; (particle = particles.poll()) != null;) pending.add(particle);
        var snapshot = current;
        if (world != null && snapshot != null) deliver(world, snapshot, pending);
    }

    public static void deliver(ClientLevel world, Snapshot snapshot, List<Particle> particles) {
        var callback = snapshot.callback();
        if (callback != null && !particles.isEmpty())
            NiagaraParticleExportCallbacks.enqueue(world, callback, List.copyOf(particles), snapshot.offset());
    }
}
