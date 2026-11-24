package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;

/** Traces against a renderer-owned acceleration structure on the active GPU. */
@FunctionalInterface
public interface NiagaraHardwareTrace {
    /**
     * The count buffer begins with the number of reserved rays, which must be clamped to maximum.
     * Each 32-byte request contains world origin, maximum distance, normalized direction and collision group.
     * Each 32-byte result contains world position, positive hit distance, world normal and padding.
     * Positions and distances use source world centimeters. Results are cleared before this call.
     * Collision groups identify primitives and apply to all of their instances. Discard a same-group hit and
     * continue strictly beyond its distance within the retrace limit. A miss or a hit in another group ends the ray.
     */
    void trace(CommandEncoder encoder, NiagaraGpuView view, GpuBufferSlice requests, GpuBufferSlice counts,
               GpuBufferSlice results, int maximum, int maxRetraces);
}
