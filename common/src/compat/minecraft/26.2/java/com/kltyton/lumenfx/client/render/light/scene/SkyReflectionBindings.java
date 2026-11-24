package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Authored sky cubemap and the source BRDF lookup for particle materials. */
public final class SkyReflectionBindings {
    private static final String UNIFORM = "LfxForwardSkyReflection";
    private static final String CUBE = "LfxForwardSkyCube";
    private static final String GF = "LfxForwardPreintegratedGF";
    private static final String DESTINATION = "LfxSkyBlendDestination";

    public record Sample(GpuBufferSlice uniforms, GpuTextureView cube, GpuSampler sampler,
                         @Nullable EffectTexture preintegrated, @Nullable EffectTexture blendDestination,
                         boolean normalize) {
        public void bind(RenderPass pass) {
            pass.setUniform(UNIFORM, uniforms);
            pass.bindTexture(CUBE, cube, sampler);
            if (preintegrated != null) pass.bindTexture(GF, preintegrated.view(), preintegrated.sampler());
            if (blendDestination != null) pass.bindTexture(DESTINATION, blendDestination.view(), sampler);
        }
    }

    private SkyReflectionBindings() { }

    public static void layout(BindGroupLayout.Builder layout, boolean preintegrated, boolean blend) {
        layout.withUniform(UNIFORM, UniformType.UNIFORM_BUFFER).withSampler(CUBE);
        if (preintegrated) layout.withSampler(GF);
        if (blend) layout.withSampler(DESTINATION);
    }

    public static SkyLightIrradiance.@Nullable Reflection reflection(@Nullable SkyLightIrradiance sky) {
        return sky != null && sky.affectsWorld() ? sky.reflection() : null;
    }

    public static @Nullable Sample prepare(CommandEncoder encoder, @Nullable SkyLightIrradiance sky,
                                           NiagaraPlatformEnvironment platform, int alignment) {
        if (sky == null) return null;
        var reflection = reflection(sky);
        if (reflection == null) return null;
        var color = sky.color().scale(scalar(platform, "r.SkylightIntensityMultiplier"));
        var luma = reflection.luminanceWeights();
        var blend = reflection.blend();
        var mixing = ReflectionLightmapMixing.read(platform);
        boolean normalize = sky.stationary() || sky.staticLighting();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat((float)color.x).putFloat((float)color.y).putFloat((float)color.z).putFloat(reflection.mipCount());
            data.putFloat(normalize ? mixing.scale() : 0).putFloat(normalize ? mixing.bias() : 0)
                    .putFloat(mixing.limit())
                    .putFloat(blend == null ? 0 : blend.fraction());
            data.putFloat((float)luma.x).putFloat((float)luma.y).putFloat((float)luma.z)
                    .putFloat((float)(reflection.averageBrightness() * luma.dot(color)));
            data.putFloat(normalize ? 1 : 0).putFloat(0).putFloat(0).putFloat(0);
            var gf = reflection.preintegrated() ? texture(reflection.preintegratedTexture()) : null;
            var cube = texture(reflection.texture());
            return new Sample(mapped.slice(), cube.view(), cube.sampler(), gf, blend == null ? null : texture(blend.texture()), normalize);
        }
    }

    private static EffectTexture texture(Identifier id) {
        return Objects.requireNonNull(EffectTextures.get(id), "Missing prepared sky reflection texture " + id);
    }

    private static int bits(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name)).bits();
    }

    private static float scalar(NiagaraPlatformEnvironment platform, String name) {
        return Float.intBitsToFloat(bits(platform, name));
    }
}
