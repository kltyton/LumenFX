package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuCountEstimate;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Renderer access is independent of simulation buffer rotation, writes and persistent-ID ownership. */
public interface NiagaraGpuParticleView {
    NiagaraGpuDataBuffer current();
    GpuBufferSlice countBuffer();
    int currentCountOffset();
    NiagaraGpuCountEstimate estimate();
}
