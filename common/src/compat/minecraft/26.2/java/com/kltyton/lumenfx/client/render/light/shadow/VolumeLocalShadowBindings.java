package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/** Shares the native local-light depth comparison inputs across volume consumers. */
public final class VolumeLocalShadowBindings implements AutoCloseable {
    public enum Kind {
        NONE, CUBE, SPOT;

        public static Kind of(@Nullable VolumeShadow shadow) {
            return shadow == null ? NONE : shadow.matrices().spot() ? SPOT : CUBE;
        }

        public void configure(RenderPipeline.Builder pipeline, BindGroupLayout.Builder bindings) {
            if (this == NONE) return;
            pipeline.withShaderDefine(this == SPOT ? "LFX_VOLUME_SPOT_SHADOW" : "LFX_VOLUME_CUBE_SHADOW");
            bindings.withUniform("VolumeLocalShadow", UniformType.UNIFORM_BUFFER).withSampler("VolumeLocalShadowDepth");
        }
    }

    private @Nullable GpuSampler cubeSampler;

    public static GpuBufferSlice uniforms(CommandEncoder encoder, VolumeShadow shadow, int alignment) {
        float inverseSize = 1f / shadow.depth().size();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(PointShadowMatrices.BYTES + 32, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            shadow.matrices().writeSampling(data);
            float bias = shadow.matrices().spot() ? shadow.matrices().spotSettings().casterBias() : .03f * 512f * inverseSize;
            data.putFloat(inverseSize).putFloat(bias);
            shadow.matrices().writeLinearDepth(data);
            data.putFloat(shadow.strength()).putInt(0).putLong(0);
            return mapped.slice();
        }
    }

    public void bind(RenderPass pass, VolumeShadow shadow, GpuBufferSlice uniforms) {
        GpuSampler sampler;
        if (shadow.matrices().spot()) sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        else {
            if (cubeSampler == null) cubeSampler = PointShadowSampler.create();
            sampler = cubeSampler;
        }
        pass.setUniform("VolumeLocalShadow", uniforms);
        pass.bindTexture("VolumeLocalShadowDepth", shadow.depth().sampled(), sampler);
    }

    @Override public void close() {
        if (cubeSampler != null) { cubeSampler.close(); cubeSampler = null; }
    }
}
