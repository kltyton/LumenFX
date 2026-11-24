package com.kltyton.lumenfx.api.client.groom;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.minecraft.world.phys.Vec3;

@FunctionalInterface
public interface NiagaraGroomOutput {
    /** Consumes borrowed guide buffers on the render thread in the supplied encoder's command order. */
    void publish(CommandEncoder encoder, String group, int points, GpuBufferSlice current, GpuBufferSlice previous,
                 Vec3 currentOffset, Vec3 previousOffset);
}
