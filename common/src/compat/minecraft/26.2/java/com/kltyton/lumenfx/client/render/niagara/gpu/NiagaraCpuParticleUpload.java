package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Objects;

/** Publishes CPU VM component bits into the existing GPU render-dataset container. */
public final class NiagaraCpuParticleUpload implements AutoCloseable {
    private final NiagaraGpuParticleBuffers buffers;
    private final List<NiagaraDefinition.Attribute> attributes;
    private final ComputeRuntime runtime;

    public NiagaraCpuParticleUpload(String name, List<NiagaraDefinition.Attribute> attributes, ComputeRuntime runtime) {
        this.attributes = List.copyOf(attributes);
        this.runtime = runtime;
        buffers = new NiagaraGpuParticleBuffers(name, this.attributes, runtime);
    }

    public NiagaraGpuParticleBuffers buffers() { return buffers; }

    public void upload(CommandEncoder encoder, VectorDataSet source, boolean particleSource) {
        RenderSystem.assertOnRenderThread();
        if (particleSource) for (var attribute : attributes)
            if (source.componentCount(attribute.type()) < Math.addExact(attribute.component(), attribute.count()))
                throw new IllegalArgumentException("CPU particle layout does not contain " + attribute.name());
        int count = particleSource ? source.count() : 0;
        buffers.begin(runtime, count);
        boolean published = false;
        try {
            var target = Objects.requireNonNull(buffers.output());
            if (particleSource && count > 0) NiagaraGpuDataUpload.data(encoder, source, target);
            NiagaraGpuDataUpload.count(encoder, buffers.counts(), buffers.writeCountOffset(), count);
            buffers.stageMetadata(0, 0, buffers.estimate().current(), count);
            buffers.estimate().cpuCount(count);
            buffers.publish();
            published = true;
            buffers.publishRenderData();
        } finally {
            if (!published) buffers.cancelBeforeDispatch();
        }
    }

    @Override public void close() { buffers.close(); }
}
