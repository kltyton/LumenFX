package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.light.ParticleLightBuffers;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.scene.VolumetricLightmapBindings;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.light.shadow.VolumeLocalShadowBindings;
import com.kltyton.lumenfx.client.render.light.shadow.VolumeLocalShadowBindings.Kind;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog.Precomputed;
import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Injects Niagara simple lights, component local lights, and the view's directional light before temporal filtering. */
public final class VolumetricParticleLights implements AutoCloseable {
    private enum Source { SIMPLE, LOCAL, DIRECTIONAL, INDIRECT }
    private enum Function { NONE, ATLAS, PROJECTION }
    private record PipelineKey(Source source, Kind shadow, boolean rectTexture, Function function, boolean lightmap) {
        PipelineKey(Source source, Kind shadow, boolean rectTexture, Function function) {
            this(source, shadow, rectTexture, function, false);
        }
    }
    record Directional(Vec3 direction, Vec3 color, DirectionalShadowRenderer.@Nullable Frame shadow,
                       LightFunctionAtlas.@Nullable Sample function, FogDirectionalLightFunction.@Nullable Frame projected) { }
    private record Injection(RenderTarget material, GpuTextureView medium, GpuTextureView depth, GpuBufferSlice volume,
                             GpuTextureView conservativeDepth, GpuTextureView previousDepth, GpuBufferSlice sampling, int slices) {
        void bind(RenderPass pass) {
            pass.setUniform("FogVolume", volume);
            pass.setUniform("FogParticleLights", sampling);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("MediumSampler", medium, nearest);
            pass.bindTexture("SceneDepthSampler", depth, nearest);
            pass.bindTexture("ConservativeDepthSampler", conservativeDepth, nearest);
            pass.bindTexture("PreviousConservativeDepthSampler", previousDepth, nearest);
        }
    }
    private static final PipelineKey SIMPLE = new PipelineKey(Source.SIMPLE, Kind.NONE, false, Function.NONE);
    private static final Map<PipelineKey, RenderPipeline> PIPELINES = new HashMap<>();
    private final ParticleLightBuffers buffers = new ParticleLightBuffers();
    private final VolumeLocalShadowBindings shadowBindings = new VolumeLocalShadowBindings();

    public void inject(CommandEncoder encoder, List<ParticleLight> lights, List<ScenePointLight> localLights,
                       Map<ScenePointLight, VolumeShadow> shadows, LightFunctionAtlas.@Nullable Frame lightFunctions,
                       @Nullable Directional directional, @Nullable SkyLightIrradiance sky,
                       @Nullable Precomputed precomputed,
                       Vec3 camera, RenderTarget material,
                       GpuTextureView medium, GpuTextureView depth, GpuBufferSlice volume,
                       FogView view, FroxelGrid grid, VolumetricFog settings, long frame,
                       GpuTextureView conservativeDepth, GpuTextureView previousConservativeDepth, float exposure) {
        RenderSystem.assertOnRenderThread();
        if (lights.isEmpty() && localLights.isEmpty() && directional == null && sky == null
                && precomputed == null) return;
        if (!Float.isFinite(exposure)) {
            if (lights.stream().anyMatch(light -> light.inverseExposureBlend() != 0)
                    || localLights.stream().anyMatch(light -> light.data().shader().inverseExposureBlend() != 0))
                throw new IllegalArgumentException("Fog lighting requires scene exposure for inverse-exposure lights");
            exposure = 1;
        }
        var batches = buffers.uploadWorld(encoder, lights, camera, Integer.MAX_VALUE / grid.z());
        var projection = new Matrix4f();
        view.copyUnjitteredWorldToClip(projection);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice sampling;
        try (var allocation = encoder.transientMemory().allocateGpuMapped(352, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = allocation.data().order(ByteOrder.nativeOrder());
            projection.get(0, data); data.position(64);
            data.putInt(0).putInt(grid.z()).putInt(settings.historyMissSamples()).putInt((int)(frame & 7));
            data.putFloat(exposure).putFloat(settings.jitter() ? settings.sampleJitterMultiplier() : 0)
                    .putFloat(settings.temporal() ? 1 : 0).putFloat(settings.lightSoftFading());
            VolumetricFogSampling.writeOffsets(data, frame, settings);
            sampling = allocation.slice();
        }
        var injection = new Injection(material, medium, depth, volume, conservativeDepth, previousConservativeDepth, sampling, grid.z());
        for (var batch : batches) {
            try (var pass = encoder.createRenderPass(() -> "LumenFX particle volume lights", material.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(PIPELINES.computeIfAbsent(SIMPLE, VolumetricParticleLights::pipeline));
                injection.bind(pass);
                pass.setUniform("ParticleLights", batch.buffer());
                pass.draw(6, Math.multiplyExact(batch.lights(), grid.z()), 0, 0);
            }
        }
        for (var light : localLights) {
            var shadow = light.data().castVolumetricShadow() ? shadows.get(light) : null;
            var function = lightFunctions == null ? null : lightFunctions.sample(light);
            injectLocal(encoder, injection, light, shadow, function, camera, alignment, settings.rectLightTexture());
        }
        if (directional != null) injectDirectional(encoder, injection, directional, alignment);
        if (sky != null || precomputed != null)
            injectIndirect(encoder, injection, sky, precomputed, camera, settings, alignment);
    }

    private void injectLocal(CommandEncoder encoder, Injection injection, ScenePointLight light,
                             @Nullable VolumeShadow shadow, LightFunctionAtlas.@Nullable Sample function,
                             Vec3 camera, int alignment, boolean rectLightTexture) {
        var shader = light.data().shader();
        var rect = shader.rect();
        var textureId = rect == null || !rectLightTexture ? null : rect.texture();
        var texture = textureId == null ? null : Objects.requireNonNull(EffectTextures.get(textureId),
                "Rect light source texture was not prepared");
        var shadowKind = Kind.of(shadow);
        GpuBufferSlice uniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(128, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var position = shader.worldPosition();
            vector(data, position.x * .01 - camera.x, position.z * .01 - camera.y, position.y * .01 - camera.z, light.data().radius());
            var color = shader.color().scale(light.data().volumetricScattering());
            vector(data, color.x, color.y, color.z, shader.inverseExposureBlend());
            var direction = shader.direction();
            var cone = shader.cone();
            vector(data, direction.x, direction.y, direction.z, cone == null ? -1 : cone.cosineOuter());
            var tangent = shader.tangent();
            vector(data, tangent.x, tangent.y, tangent.z, shader.sourceLength());
            vector(data, shader.sourceRadius(), shader.softSourceRadius(), shader.falloffExponent(), shader.inverseSquared() ? 1 : 0);
            if (rect == null) {
                vector(data, 0, 0, 0, 0);
                vector(data, 1, 1, 0, 0);
            } else {
                vector(data, rect.halfWidth(), rect.halfHeight(), rect.barnCosine(), rect.barnLength());
                vector(data, rect.scaleX(), rect.scaleY(), rect.offsetX(), rect.offsetY());
            }
            vector(data, cone == null ? 0 : cone.inverseCosineDifference(), 0, 0, 0);
            uniforms = mapped.slice();
        }
        var shadowUniforms = shadow == null ? null : VolumeLocalShadowBindings.uniforms(encoder, shadow, alignment);
        var functionUniforms = function == null ? null : function.uniforms(encoder);
        var key = new PipelineKey(Source.LOCAL, shadowKind, texture != null, function == null ? Function.NONE : Function.ATLAS);
        try (var pass = encoder.createRenderPass(() -> "LumenFX fog component light", injection.material().getColorTextureView(), Optional.empty())) {
            pass.setPipeline(PIPELINES.computeIfAbsent(key, VolumetricParticleLights::pipeline));
            injection.bind(pass);
            pass.setUniform("FogLocalLight", uniforms);
            if (texture != null) pass.bindTexture("LfxRectSourceTexture", texture.view(), texture.sampler());
            if (function != null) function.bind(pass, Objects.requireNonNull(functionUniforms));
            if (shadow != null) shadowBindings.bind(pass, shadow, Objects.requireNonNull(shadowUniforms));
            pass.draw(6, injection.slices(), 0, 0);
        }
    }

    private static void injectDirectional(CommandEncoder encoder, Injection injection, Directional directional, int alignment) {
        GpuBufferSlice light;
        var projected = directional.projected();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(96, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var direction = directional.direction();
            var color = directional.color();
            vector(data, direction.x, direction.y, direction.z, 0);
            vector(data, color.x, color.y, color.z, 0);
            if (projected != null) projected.projection().get(32, data);
            else for (int index = 0; index < 16; index++) data.putFloat(0);
            light = mapped.slice();
        }
        var shadow = directional.shadow();
        var shadowUniforms = DirectionalShadowRenderer.uniforms(encoder, shadow, DirectionalShadowRenderer.FORWARD_CASCADE_LIMIT);
        var function = directional.function();
        var functionUniforms = function == null ? null : function.uniforms(encoder);
        var kind = function != null ? Function.ATLAS : projected != null ? Function.PROJECTION : Function.NONE;
        var key = new PipelineKey(Source.DIRECTIONAL, Kind.NONE, false, kind);
        try (var pass = encoder.createRenderPass(() -> "LumenFX fog directional light", injection.material().getColorTextureView(), Optional.empty())) {
            pass.setPipeline(PIPELINES.computeIfAbsent(key, VolumetricParticleLights::pipeline));
            injection.bind(pass);
            pass.setUniform("FogDirectionalLight", light);
            DirectionalShadowRenderer.bind(pass, shadowUniforms, shadow, injection.depth());
            if (function != null) function.bind(pass, Objects.requireNonNull(functionUniforms));
            if (projected != null) pass.bindTexture("FogDirectionalFunction", projected.texture(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(6, injection.slices(), 0, 0);
        }
    }

    private static void injectIndirect(CommandEncoder encoder, Injection injection, @Nullable SkyLightIrradiance sky,
                                       @Nullable Precomputed precomputed,
                                       Vec3 camera, VolumetricFog settings, int alignment) {
        GpuBufferSlice uniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(96, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            for (int band = 0; band < 3; band++) {
                if (sky == null) vector(data, 0, 0, 0, 0);
                else {
                    var coefficient = sky.coefficients().get(band);
                    vector(data, coefficient.x(), coefficient.y(), coefficient.z(), coefficient.w());
                }
            }
            float intensity = Float.intBitsToFloat(Objects.requireNonNull(
                    NiagaraPlatforms.snapshot().console("r.SkylightIntensityMultiplier")).bits());
            var color = sky == null ? Vec3.ZERO : sky.color();
            var scaledColor = color.scale(intensity);
            float scattering = sky == null ? 0f : sky.volumetricScattering();
            vector(data, scaledColor.x, scaledColor.y, scaledColor.z, scattering);
            var fog = settings.height().inscattering();
            vector(data, fog.x, fog.y, fog.z, settings.overrideLightColors() ? 1 : 0);
            data.putInt(sky != null && sky.staticShadowing() ? 1 : 0)
                    .putInt(precomputed != null && precomputed.lightmap().skyBentNormal() ? 1 : 0).putInt(0).putInt(0);
            uniforms = mapped.slice();
        }
        var lightmapUniforms = precomputed == null ? null : VolumetricLightmapBindings.uniforms(encoder, precomputed.lightmap(),
                camera, alignment, (float) (precomputed.scatteringIntensity() / Math.PI));
        var key = new PipelineKey(Source.INDIRECT, Kind.NONE, false, Function.NONE, precomputed != null);
        try (var pass = encoder.createRenderPass(() -> "LumenFX fog indirect light", injection.material().getColorTextureView(), Optional.empty())) {
            pass.setPipeline(PIPELINES.computeIfAbsent(key, VolumetricParticleLights::pipeline));
            injection.bind(pass);
            pass.setUniform("FogSkyLight", uniforms);
            if (precomputed != null) VolumetricLightmapBindings.bind(pass, precomputed.lightmap(),
                    Objects.requireNonNull(lightmapUniforms), false);
            pass.draw(6, injection.slices(), 0, 0);
        }
    }

    private static void vector(ByteBuffer target, double x, double y, double z, double w) {
        target.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)w);
    }

    private static RenderPipeline pipeline(PipelineKey key) {
        String name = switch (key.source()) {
            case SIMPLE -> "particle_lights";
            case DIRECTIONAL -> "directional_light";
            case INDIRECT -> key.lightmap() ? "indirect_lightmap" : "indirect_sky";
            case LOCAL -> switch (key.shadow()) {
                case NONE -> "local_light";
                case CUBE -> "local_light_cube";
                case SPOT -> "local_light_spot";
            };
        };
        var bindings = BindGroupLayout.builder().withUniform("FogVolume", UniformType.UNIFORM_BUFFER)
                .withUniform("FogParticleLights", UniformType.UNIFORM_BUFFER)
                .withSampler("MediumSampler").withSampler("SceneDepthSampler")
                .withSampler("ConservativeDepthSampler").withSampler("PreviousConservativeDepthSampler");
        var pipeline = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/fog/" + name
                        + (key.rectTexture() ? "_texture" : "") + switch (key.function()) {
                            case NONE -> ""; case ATLAS -> "_atlas"; case PROJECTION -> "_projection";
                        }))
                .withVertexShader(LumenFx.id("core/fog_particle_light")).withFragmentShader(LumenFx.id("fog/particle_lights"))
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA16_FLOAT, 7));
        switch (key.source()) {
            case SIMPLE -> bindings.withUniform("ParticleLights", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT);
            case LOCAL -> {
                pipeline.withShaderDefine("LFX_FOG_LOCAL_LIGHT");
                bindings.withUniform("FogLocalLight", UniformType.UNIFORM_BUFFER);
            }
            case DIRECTIONAL -> {
                pipeline.withShaderDefine("LFX_FOG_DIRECTIONAL_LIGHT");
                bindings.withUniform("FogDirectionalLight", UniformType.UNIFORM_BUFFER);
                DirectionalShadowRenderer.layout(bindings);
            }
            case INDIRECT -> {
                pipeline.withShaderDefine("LFX_FOG_INDIRECT_LIGHT");
                bindings.withUniform("FogSkyLight", UniformType.UNIFORM_BUFFER);
                if (key.lightmap()) {
                    pipeline.withShaderDefine("LFX_FOG_VOLUMETRIC_LIGHTMAP");
                    VolumetricLightmapBindings.layout(bindings, false);
                }
            }
        }
        key.shadow().configure(pipeline, bindings);
        if (key.rectTexture()) {
            pipeline.withShaderDefine("LFX_RECT_LIGHT_TEXTURE");
            bindings.withSampler("LfxRectSourceTexture");
        }
        switch (key.function()) {
            case ATLAS -> {
                pipeline.withShaderDefine("LFX_LOCAL_LIGHT_FUNCTION");
                LightFunctionAtlas.Sample.layout(bindings);
            }
            case PROJECTION -> {
                pipeline.withShaderDefine("LFX_FOG_PROJECTED_FUNCTION");
                bindings.withSampler("FogDirectionalFunction");
            }
            case NONE -> { }
        }
        return pipeline.withBindGroupLayout(bindings.build())
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    @Override public void close() {
        try { buffers.close(); }
        finally { shadowBindings.close(); }
    }
}
