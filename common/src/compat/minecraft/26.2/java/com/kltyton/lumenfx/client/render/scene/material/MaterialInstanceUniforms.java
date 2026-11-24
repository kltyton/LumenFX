package com.kltyton.lumenfx.client.render.scene.material;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/** A draw consumes one complete parameter snapshot after the host's mapped upload has closed. */
public final class MaterialInstanceUniforms {
    private MaterialInstanceUniforms() { }
    public static @Nullable GpuBufferSlice write(CommandEncoder encoder, MaterialDefinition material) {
        var parameters = material.instanceParameters();
        var state = parameters.publication();
        var dynamics = parameters.dynamicValues();
        var edits = dynamics == null ? null : dynamics.snapshot();
        MaterialTextureBindings.prepare(encoder, material, state, edits);
        if (parameters.byteSize() == 0) return null;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(parameters.byteSize(), alignment, GpuBuffer.USAGE_UNIFORM)) {
            parameters.write(state, edits, mapped.data().order(ByteOrder.nativeOrder()), VfxEngine.INSTANCE::toonProfileIndex);
            return mapped.slice();
        }
    }
}
