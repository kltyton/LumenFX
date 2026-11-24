package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** View bindings for authored sky irradiance and precomputed diffuse illumination. */
public final class SceneIndirectDiffuse {
    private static final String UNIFORM = "LfxForwardIndirectDiffuse";

    public record Sample(GpuBufferSlice sky, @Nullable VolumetricLightmap lightmap, @Nullable GpuBufferSlice lightmapUniforms) {
        public void bind(RenderPass pass) {
            pass.setUniform(UNIFORM, sky);
            if (lightmap != null) VolumetricLightmapBindings.bind(pass, lightmap, Objects.requireNonNull(lightmapUniforms), true);
        }
    }

    private SceneIndirectDiffuse() { }

    public static void layout(BindGroupLayout.Builder bindings, boolean volumetricLightmap) {
        bindings.withUniform(UNIFORM, UniformType.UNIFORM_BUFFER);
        if (volumetricLightmap) VolumetricLightmapBindings.layout(bindings, true);
    }

    public static @Nullable VolumetricLightmap activeLightmap(SceneIndirectLighting settings, NiagaraPlatformEnvironment platform) {
        return platform.staticLightingAllowed() ? settings.lightmap() : null;
    }

    private static @Nullable SkyLightIrradiance basePassSky(SceneIndirectLighting settings, NiagaraPlatformEnvironment platform,
                                                          boolean translucent) {
        var sky = settings.skyLight();
        if (sky == null || !sky.affectsWorld() || sky.staticLighting()) return null;
        if (!sky.stationary()) return translucent ? sky : null;
        return Objects.requireNonNull(platform.console("r.SupportStationarySkylight")).bits() != 0
                || Objects.requireNonNull(platform.console("r.SupportAllShaderPermutations")).bits() != 0 ? sky : null;
    }

    public static boolean hasOpaqueBasePassLighting(SceneIndirectLighting settings, NiagaraPlatformEnvironment platform) {
        return activeLightmap(settings, platform) != null || basePassSky(settings, platform, false) != null;
    }

    public static @Nullable SkyLightIrradiance deferredSky(SceneIndirectLighting settings) {
        var sky = settings.skyLight();
        return sky != null && sky.affectsWorld() && !sky.staticLighting() && !sky.stationary() ? sky : null;
    }

    static Sample prepareDeferredSky(CommandEncoder encoder, SceneIndirectLighting settings,
                                     NiagaraPlatformEnvironment platform, int alignment) {
        return prepare(encoder, settings, platform, Vec3.ZERO, alignment, null, deferredSky(settings), 0);
    }

    public static Sample prepare(CommandEncoder encoder, SceneIndirectLighting settings, NiagaraPlatformEnvironment platform,
                                 Vec3 camera, int alignment, boolean translucent, int bottomUpHeight) {
        return prepare(encoder, settings, platform, camera, alignment, activeLightmap(settings, platform),
                basePassSky(settings, platform, translucent), bottomUpHeight);
    }

    private static Sample prepare(CommandEncoder encoder, SceneIndirectLighting settings, NiagaraPlatformEnvironment platform,
                                  Vec3 camera, int alignment, @Nullable VolumetricLightmap lightmap,
                                  @Nullable SkyLightIrradiance sky,
                                  int bottomUpHeight) {
        var scale = settings.precomputedColorScale();
        var lightmapUniforms = lightmap == null ? null : VolumetricLightmapBindings.uniforms(encoder, lightmap, camera, alignment, 0);
        GpuBufferSlice uniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(160, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            if (sky != null) {
                for (var coefficient : sky.coefficients())
                    data.putFloat(coefficient.x()).putFloat(coefficient.y()).putFloat(coefficient.z()).putFloat(coefficient.w());
                writeSkyColor(data, sky.color(), platform);
            } else for (int index = 0; index < 32; index++) data.putFloat(0);
            data.putFloat((float)scale.x).putFloat((float)scale.y).putFloat((float)scale.z)
                    .putFloat(sky != null && sky.staticShadowing() && lightmap != null && lightmap.skyBentNormal() ? 1 : 0);
            var luma = luminanceWeights(settings);
            data.putFloat((float)luma.x).putFloat((float)luma.y).putFloat((float)luma.z).putFloat(bottomUpHeight);
            uniforms = mapped.slice();
        }
        return new Sample(uniforms, lightmap, lightmapUniforms);
    }

    private static void writeSkyColor(ByteBuffer data, Vec3 authored, NiagaraPlatformEnvironment platform) {
        float intensity = Float.intBitsToFloat(Objects.requireNonNull(platform.console("r.SkylightIntensityMultiplier")).bits());
        var color = authored.scale(intensity);
        data.putFloat((float)color.x).putFloat((float)color.y).putFloat((float)color.z).putFloat(1);
    }

    private static Vec3 luminanceWeights(SceneIndirectLighting settings) {
        var sky = settings.skyLight();
        return sky != null && sky.reflection() != null ? sky.reflection().luminanceWeights()
                : new Vec3(.2126390059, .7151686788, .0721923154);
    }
}
