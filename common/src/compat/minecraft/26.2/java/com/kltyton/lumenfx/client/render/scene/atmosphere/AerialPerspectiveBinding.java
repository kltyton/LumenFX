package com.kltyton.lumenfx.client.render.scene.atmosphere;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;

/** Binds the current camera volume to the base pass fog stage selected by the material. */
public record AerialPerspectiveBinding(NiagaraSceneInputs.AerialPerspective volume, GpuBufferSlice parameters) {
    static final int VIEW_BYTES = 176;
    public static final String DEFINE = "LFX_AERIAL_PERSPECTIVE";
    private static final String PARAMETERS = "ParticleAerialPerspective";
    private static final String SAMPLER = "ParticleAerialPerspectiveSampler";

    public static AerialPerspectiveBinding prepare(CommandEncoder encoder, NiagaraSceneInputs.AerialPerspective volume) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var uniform = encoder.transientMemory().allocateGpuMapped(VIEW_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            writeView(uniform.data().order(ByteOrder.nativeOrder()), volume);
            return new AerialPerspectiveBinding(volume, uniform.slice());
        }
    }

    static void writeView(ByteBuffer data, NiagaraSceneInputs.AerialPerspective volume) {
        int start = data.position();
        volume.worldToClip().get(start, data);
        volume.clipToWorld().get(start + 64, data);
        data.position(start + 128);
        data.putFloat(volume.startKm()).putFloat(1f / volume.depthKm())
                .putFloat(volume.slices()).putFloat(volume.reflection360() ? 2f : volume.orthographic() ? 1f : 0f);
        AtmosphereMaterialLighting.writeVector(data, volume.drawOriginOffsetBlocks(), volume.orthoHeightBlocks());
        AtmosphereMaterialLighting.writeVector(data, volume.cameraOffsetBlocks(), volume.holdout() ? 1f : 0f);
    }

    public static BindGroupLayout layout() {
        return BindGroupLayout.builder().withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER).withSampler(SAMPLER).build();
    }

    public void bind(RenderPass pass) {
        pass.setUniform(PARAMETERS, parameters);
        pass.bindTexture(SAMPLER, volume.volume(), volume.sampler());
    }
}
