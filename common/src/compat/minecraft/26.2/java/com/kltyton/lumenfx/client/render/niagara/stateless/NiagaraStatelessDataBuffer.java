package com.kltyton.lumenfx.client.render.niagara.stateless;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDataBuffer;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDataUpload;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleView;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuCountEstimate;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One analytic result allocation borrows its count slot for the current render frame. */
public final class NiagaraStatelessDataBuffer implements NiagaraGpuParticleView, AutoCloseable {
    private final String name;
    private final List<NiagaraDefinition.Attribute> attributes;
    private final NiagaraGpuCountEstimate estimate = new NiagaraGpuCountEstimate();
    private NiagaraGpuDataBuffer data;
    private @Nullable GpuBufferSlice counters;
    private @Nullable GpuBuffer cpuCounter;
    private int countOffset = -1;
    private boolean generationClaimed;
    private boolean gpuCountAvailable;
    private boolean closed;

    public NiagaraStatelessDataBuffer(String name, List<NiagaraDefinition.Attribute> attributes) {
        this.name = name;
        this.attributes = List.copyOf(attributes);
        data = new NiagaraGpuDataBuffer(name, 0, this.attributes);
    }

    public void prepare(ComputeRuntime runtime, GpuBufferSlice counters, int offset, int activeEstimate) {
        prepare(runtime, counters, offset, activeEstimate, true);
    }
    void prepare(ComputeRuntime runtime, GpuBufferSlice counters, int offset, int activeEstimate, boolean clearCounter) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara analytic data buffer is closed");
        if (offset < 0 || ((long)offset + 1) * Integer.BYTES > counters.length() || activeEstimate < 0)
            throw new IllegalArgumentException("Invalid Niagara analytic result count slot");
        if (activeEstimate > NiagaraGpuDataBuffer.maximumParticleCapacity(runtime, attributes))
            throw new IllegalArgumentException("Niagara analytic result exceeds the device particle capacity");
        if ((long)activeEstimate + 1 > data.stride()) {
            NiagaraGpuDataBuffer replacement = new NiagaraGpuDataBuffer(name, activeEstimate, attributes);
            data.close();
            data = replacement;
        }
        this.counters = counters;
        countOffset = offset;
        gpuCountAvailable = true;
        generationClaimed = false;
        estimate.generationEstimate(activeEstimate);
        if (clearCounter) runtime.clearBuffer(counters.slice((long)offset * Integer.BYTES, Integer.BYTES), 0);
    }

    /** The host renderers consume a GPU word even when the native CPU path owns the known result count. */
    public void prepareWithoutGpuCount(ComputeRuntime runtime, int activeEstimate) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara analytic data buffer is closed");
        if (cpuCounter == null) cpuCounter = RenderSystem.getDevice().createBuffer(() -> name + ".CpuResultCount",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), Integer.BYTES);
        prepare(runtime, cpuCounter.slice(), 0, activeEstimate);
        gpuCountAvailable = false;
        estimate.generationEstimate(0);
    }
    public boolean gpuCountAvailable() { requireFrame(); return gpuCountAvailable; }
    public void noData() { requireFrame(); estimate.generationEstimate(0); }

    public void upload(CommandEncoder encoder, VectorDataSet particles) {
        GpuBufferSlice counters = requireFrame();
        NiagaraGpuDataUpload.data(encoder, particles, data);
        NiagaraGpuDataUpload.count(encoder, counters, countOffset, particles.count());
        estimate.cpuCount(particles.count());
    }

    public boolean claimGeneration() {
        requireFrame();
        if (generationClaimed) return false;
        generationClaimed = true;
        return true;
    }

    public void retire() {
        RenderSystem.assertOnRenderThread();
        counters = null;
        countOffset = -1;
        generationClaimed = false;
        gpuCountAvailable = false;
        estimate.reset();
    }

    @Override public NiagaraGpuDataBuffer current() { requireFrame(); return data; }
    @Override public GpuBufferSlice countBuffer() { return requireFrame(); }
    @Override public int currentCountOffset() { requireFrame(); return countOffset; }
    @Override public NiagaraGpuCountEstimate estimate() { requireFrame(); return estimate; }

    private GpuBufferSlice requireFrame() {
        RenderSystem.assertOnRenderThread();
        GpuBufferSlice current = counters;
        if (closed || current == null) throw new IllegalStateException("Niagara analytic result is outside its frame lifetime");
        return current;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        data.close();
        if (cpuCounter != null) { cpuCounter.close(); cpuCounter = null; }
        retire();
    }
}
