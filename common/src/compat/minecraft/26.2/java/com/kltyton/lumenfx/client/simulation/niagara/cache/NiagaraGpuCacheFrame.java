package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Sample;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A resolved cache frame is uploaded to the ordinary GPU renderer dataset on first use. */
public final class NiagaraGpuCacheFrame implements AutoCloseable {
    private final Emitter emitter;
    private final VectorDataSet data;
    private final int maximumRows;
    private boolean persistentIds;
    private int[] idIndex = new int[0];
    private @Nullable EmitterFrame saved;
    private @Nullable NiagaraGpuParticleBuffers particles;
    private @Nullable NiagaraGpuPrograms owner;
    private boolean dirty;

    public NiagaraGpuCacheFrame(Emitter emitter, int maximumRows) {
        this.emitter = emitter;
        this.maximumRows = maximumRows;
        int[] columns = new int[3];
        for (var attribute : emitter.attributes())
            columns[attribute.type()] = Math.max(columns[attribute.type()], attribute.component() + attribute.count());
        data = new VectorDataSet(columns[0], columns[1], columns[2]);
        persistentIds = emitter.requiresPersistentIdStorage();
    }

    public EmitterFrame read(NiagaraCachePlayer playback, int index, Sample sample, float delta, Pose pose, Vec3 tile) {
        var frame = playback.readEmitter(index, sample, delta, pose, tile, data, maximumRows);
        if (persistentIds || frame.particles().hasIdTable()) idIndex = playback.particleIndex(index, frame.particles(), data);
        saved = frame;
        dirty = true;
        return frame;
    }

    public NiagaraGpuParticleBuffers particles(CommandEncoder encoder, NiagaraGpuPrograms programs) {
        var buffer = Objects.requireNonNull(saved, "Niagara cache emitter has not read a frame").particles();
        if (owner != programs || !persistentIds && buffer.hasIdTable()) {
            if (particles != null) particles.close();
            particles = null;
            owner = null;
        }
        if (particles == null) {
            persistentIds |= buffer.hasIdTable();
            particles = new NiagaraGpuParticleBuffers(emitter.name() + ".SimCache", emitter.attributes(), programs, persistentIds);
            owner = programs;
            dirty = true;
        }
        if (dirty) {
            particles.uploadCache(encoder, programs.runtime(), data, buffer.acquireTag(), idIndex);
            dirty = false;
        }
        return particles;
    }

    public EmitterFrame saved() { return Objects.requireNonNull(saved); }
    public int count() { return data.count(); }

    public void restoreSimulation(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraGpuParticleBuffers target) {
        var buffer = saved().particles();
        target.uploadCache(encoder, programs.runtime(), data, buffer.acquireTag(), idIndex);
        if (persistentIds) programs.observeCachedParticleTag(buffer.acquireTag());
    }

    @Override public void close() {
        if (particles != null) { particles.close(); particles = null; owner = null; }
    }
}
