package com.kltyton.lumenfx.client.simulation.niagara.interfaces.export;

import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport;
import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport.Particle;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Delivers particle export batches at the world simulation barrier. */
public final class NiagaraParticleExportCallbacks {
    private record Delivery(NiagaraParticleExport callback, List<Particle> particles, Vec3 offset) { }
    private static final Map<ClientLevel, ArrayDeque<Delivery>> PENDING = new IdentityHashMap<>();

    private NiagaraParticleExportCallbacks() { }

    static synchronized void enqueue(ClientLevel world, NiagaraParticleExport callback, List<Particle> particles, Vec3 offset) {
        PENDING.computeIfAbsent(world, ignored -> new ArrayDeque<>()).addLast(new Delivery(callback, particles, offset));
    }

    public static void drain(ClientLevel world) {
        ArrayDeque<Delivery> batch;
        synchronized (NiagaraParticleExportCallbacks.class) { batch = PENDING.remove(world); }
        if (batch == null) return;
        try {
            for (Delivery delivery; (delivery = batch.pollFirst()) != null;)
                delivery.callback().receive(delivery.particles(), delivery.offset());
        } catch (RuntimeException | Error failure) {
            synchronized (NiagaraParticleExportCallbacks.class) {
                var newlyPending = PENDING.remove(world);
                if (newlyPending != null) batch.addAll(newlyPending);
                if (!batch.isEmpty()) PENDING.put(world, batch);
            }
            throw failure;
        }
    }

    public static synchronized void clear(ClientLevel world) { PENDING.remove(world); }
    public static synchronized void clear() { PENDING.clear(); }
}
