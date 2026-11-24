package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import org.jspecify.annotations.Nullable;

/** Runtime fixed overrides and the emitter's last completed PostTick bounds. */
public final class NiagaraEmitterBounds {
    public record Snapshot(boolean localSpace, @Nullable NiagaraBounds bounds,
                           @Nullable NiagaraBounds fixedBounds, boolean dynamic) { }

    private final NiagaraDefinition.Emitter definition;
    private volatile @Nullable NiagaraBounds systemFixedBounds;
    private volatile @Nullable NiagaraBounds fixedBounds;
    private volatile Snapshot snapshot;
    private volatile Snapshot renderSnapshot;

    NiagaraEmitterBounds(NiagaraDefinition.Emitter definition, @Nullable NiagaraBounds systemFixedBounds) {
        this.definition = definition;
        this.systemFixedBounds = systemFixedBounds;
        snapshot = new Snapshot(definition.localSpace(), null, null, false);
        renderSnapshot = snapshot;
    }

    public Snapshot snapshot() { return snapshot; }
    public Snapshot renderSnapshot() { return renderSnapshot; }
    @Nullable NiagaraBounds fixedBounds() { return fixedBounds; }
    void fixedBounds(@Nullable NiagaraBounds value) { fixedBounds = value; }
    void systemFixedBounds(@Nullable NiagaraBounds value) { systemFixedBounds = value; }

    void publishRenderSnapshot() {
        Snapshot completed = snapshot;
        renderSnapshot = new Snapshot(completed.localSpace(), completed.bounds(), fixedBounds, completed.dynamic());
    }

    void postTick(@Nullable NiagaraBounds dynamicBounds) {
        NiagaraBounds override = fixedBounds;
        NiagaraBounds bounds;
        boolean dynamic = false;
        if (override != null) bounds = override;
        else if (systemFixedBounds != null) bounds = systemFixedBounds;
        else if (definition.boundsMode().equals("Fixed")) bounds = definition.fixedBounds();
        else if (definition.boundsMode().equals("Dynamic")) {
            boolean gpu = definition.simulation() instanceof NiagaraDefinition.GpuSimulation;
            bounds = gpu ? NiagaraBounds.DEFAULT : dynamicBounds;
            dynamic = !gpu && bounds != null;
        } else if (definition.boundsMode().equals("Programmable")) bounds = null;
        else throw new IllegalArgumentException("Unknown Niagara bounds mode " + definition.boundsMode());
        snapshot = new Snapshot(definition.localSpace(), bounds, override, dynamic);
    }

    void readCache(@Nullable NiagaraBounds recorded) {
        snapshot = new Snapshot(definition.localSpace(), recorded, fixedBounds, false);
    }
}
