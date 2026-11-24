package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteOrder;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Frame-owned forward atmosphere inputs for the native index-zero directional-light path. */
public final class ForwardAtmosphereTransmittanceBinding {
    public static final String SHADER_DEFINE = "LFX_FORWARD_ATMOSPHERE_TRANSMITTANCE";
    public static final String PARAMETERS = "LfxForwardAtmosphereParameters";
    public static final String TRANSMITTANCE = "LfxForwardAtmosphereTransmittance";
    public static final int UNIFORM_BYTES = 3 * 16;

    private ForwardAtmosphereTransmittanceBinding() { }

    public static boolean available(@Nullable Atmosphere atmosphere) {
        return atmosphere != null && atmosphere.atmosphereLightDirection() != null;
    }

    public static void layout(BindGroupLayout.Builder layout) {
        layout.withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER).withSampler(TRANSMITTANCE);
    }

    public static @Nullable GpuBufferSlice write(CommandEncoder encoder, @Nullable Atmosphere atmosphere) {
        if (atmosphere == null) return null;
        var source = atmosphere.atmosphereLightDirection();
        if (source == null) return null;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var allocation = encoder.transientMemory().allocateGpuMapped(UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = allocation.data().order(ByteOrder.nativeOrder());
            data.putFloat(atmosphere.translatedCenterX()).putFloat(atmosphere.translatedCenterY())
                    .putFloat(atmosphere.translatedCenterZ()).putFloat(0f);
            data.putFloat(atmosphere.bottomRadiusKm()).putFloat(atmosphere.topRadiusKm()).putFloat(0f).putFloat(0f);
            data.putFloat((float)source.x).putFloat((float)source.y).putFloat((float)source.z).putFloat(0f);
            return allocation.slice();
        }
    }

    public static void bind(RenderPass pass, @Nullable Atmosphere atmosphere, @Nullable GpuBufferSlice parameters) {
        if (atmosphere == null || atmosphere.atmosphereLightDirection() == null) return;
        pass.setUniform(PARAMETERS, Objects.requireNonNull(parameters,
                "Atmosphere uniforms require a current frame allocation"));
        pass.bindTexture(TRANSMITTANCE, atmosphere.transmittance(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }
}
