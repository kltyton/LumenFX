package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.client.render.texture.ToonProfileBindings;
import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.render.decal.DecalBuffers;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.scene.surface.adaptive.AdaptiveParticleBuffer;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.subsurface.SubsurfaceProfiles;
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
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;

/** Resolves a rendered point-shadow cube into the source radial surface and transmission channels. */
public final class PointShadowProjectionRenderer implements AutoCloseable {
    private static final int BYTES = 576;
    private static final AtomicLong NEXT = new AtomicLong();
    public record DecalInputs(DecalBuffers.Frame textures, GpuBufferSlice view) { }
    private record Key(GpuFormat format, boolean host, boolean zeroToOne, int quality, boolean decals, boolean legacyTransmission,
                       boolean adaptive, boolean adaptiveTransmission, boolean spot, boolean toonTransmission) {
        boolean transmission() { return legacyTransmission || adaptiveTransmission; }
    }
    private record Inputs(GpuTextureView surface, GpuTextureView geometry, @Nullable GpuTextureView normal,
                          Matrix4fc depthTransform, int width, int height, boolean host, @Nullable DecalInputs decals,
                          @Nullable GpuTextureView subsurfaceData, @Nullable GpuTextureView adaptiveWords,
                          boolean adaptiveTransmission, @Nullable GpuTextureView toonData) { }
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();
    private @Nullable GpuSampler comparison;

    public void draw(ShadowDepthTarget cube, PointShadowMatrices matrices, ScenePointLight light,
                     PointShadowProjectionSettings settings, Matrix4fc viewToSourceWorld, Vec3 camera,
                     Surfaces surface, Matrix4fc projection, GpuTextureView sceneDepth, RenderTarget attenuation, boolean clear) {
        draw(cube, matrices, light, settings, viewToSourceWorld, camera, surface, projection, sceneDepth, attenuation, clear, null);
    }

    public void draw(ShadowDepthTarget cube, PointShadowMatrices matrices, ScenePointLight light,
                     PointShadowProjectionSettings settings, Matrix4fc viewToSourceWorld, Vec3 camera,
                     Surfaces surface, Matrix4fc projection, GpuTextureView sceneDepth, RenderTarget attenuation, boolean clear,
                     @Nullable DecalInputs decals) {
        draw(cube, matrices, light, settings, viewToSourceWorld, camera, surface, projection, sceneDepth, attenuation,
                clear, decals, null);
    }

    public void draw(ShadowDepthTarget cube, PointShadowMatrices matrices, ScenePointLight light,
                     PointShadowProjectionSettings settings, Matrix4fc viewToSourceWorld, Vec3 camera,
                     Surfaces surface, Matrix4fc projection, GpuTextureView sceneDepth, RenderTarget attenuation, boolean clear,
                     @Nullable DecalInputs decals, OpaqueParticleSurfaces.@Nullable Frame materials) {
        boolean transmits = light.data().transmitsThroughSurfaces() && materials != null;
        GpuTextureView legacy = transmits && materials.hasLightingFeature(MaterialDefinition.LEGACY_SUBSURFACE_PROFILE)
                ? materials.customData() : null;
        GpuTextureView adaptive = materials == null || materials.adaptive() == null ? null : materials.adaptive().sampled();
        boolean adaptiveTransmission = transmits && materials.hasLightingFeature(MaterialDefinition.SUBSTRATE_DIFFUSION);
        GpuTextureView toon = matrices.spot() && materials != null && materials.hasLightingFeature(MaterialDefinition.SUBSTRATE_TOON)
                ? materials.customData() : null;
        draw(cube, matrices, light, settings, viewToSourceWorld, camera,
                new Inputs(surface.albedo(), surface.positionDepth(), surface.normalAmbient(), projection,
                        surface.width(), surface.height(), false, decals, legacy, adaptive, adaptiveTransmission, toon),
                sceneDepth, attenuation, clear);
    }

    public void draw(ShadowDepthTarget cube, PointShadowMatrices matrices, ScenePointLight light,
                     PointShadowProjectionSettings settings, Matrix4fc viewToSourceWorld, Vec3 camera,
                     ShaderpackMaterialSurfaces surface, GpuTextureView sceneDepth, RenderTarget attenuation, boolean clear) {
        var inverseProjection = new Matrix4f(Objects.requireNonNull(surface.materialProjection(), "Shadow projection needs the shaderpack material projection")).invert();
        draw(cube, matrices, light, settings, viewToSourceWorld, camera,
                new Inputs(surface.baseDepth(), surface.geometry(), null, inverseProjection,
                        surface.width(), surface.height(), true, null, null, null, false, null), sceneDepth, attenuation, clear);
    }

    private void draw(ShadowDepthTarget cube, PointShadowMatrices matrices, ScenePointLight light,
                      PointShadowProjectionSettings settings, Matrix4fc viewToSourceWorld, Vec3 camera,
                      Inputs surface, GpuTextureView sceneDepth, RenderTarget target, boolean clear) {
        RenderSystem.assertOnRenderThread();
        if (settings.quality() == 0) throw new IllegalArgumentException("Disabled shadow quality must not submit a shadow projection");
        if (surface.width() != target.width || surface.height() != target.height
                || sceneDepth.getWidth(0) != target.width || sceneDepth.getHeight(0) != target.height)
            throw new IllegalArgumentException("Shadow receivers and attenuation surface dimensions differ");
        var decals = surface.decals();
        if (decals != null && (decals.textures().width() != target.width || decals.textures().height() != target.height))
            throw new IllegalArgumentException("Shadow receivers and decal normal dimensions differ");
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        boolean spot = matrices.spot();
        if (!spot && comparison == null) comparison = PointShadowSampler.create();
        GpuBufferSlice uniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(BYTES,
                device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            matrices.writeSampling(data);
            viewToSourceWorld.get(data.position(), data); data.position(data.position() + 64);
            surface.depthTransform().get(data.position(), data); data.position(data.position() + 64);
            var shader = light.data().shader(); Vec3 origin = shader.worldPosition();
            data.putFloat((float)(origin.x - camera.x * 100)).putFloat((float)(origin.y - camera.z * 100))
                    .putFloat((float)(origin.z - camera.y * 100)).putFloat(shader.inverseRadius());
            data.putFloat(settings.depthBias()).putFloat(settings.slopeBias()).putFloat(settings.maximumSlopeBias()).putFloat(settings.sharpen());
            data.putFloat(settings.fade()).putFloat(1f / cube.size());
            matrices.writeLinearDepth(data);
            if (spot) {
                var settings2d = matrices.spotSettings();
                data.putFloat(settings2d.casterBias()).putFloat(settings2d.transitionScale())
                        .putFloat(settings2d.depthRange()).putFloat(settings2d.receiverBias());
            } else data.putLong(0L).putLong(0L);
            uniforms = mapped.slice();
        }
        if (clear) encoder.clearColorTexture(target.getColorTexture(), new Vector4f(1));
        boolean gl = device.backend instanceof com.mojang.blaze3d.opengl.GlDevice;
        boolean restoreSeamless = !spot && gl && !GL11C.glIsEnabled(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        if (restoreSeamless) GL11C.glEnable(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        try (var pass = encoder.createRenderPass(() -> "LumenFX point-shadow projection", target.getColorTextureView(), Optional.empty())) {
            var key = new Key(target.getColorTexture().getFormat(), surface.host(), device.getDeviceInfo().isZZeroToOne(),
                    settings.quality(), decals != null, surface.subsurfaceData() != null, surface.adaptiveWords() != null,
                    surface.adaptiveTransmission(), spot, surface.toonData() != null);
            pass.setPipeline(pipelines.computeIfAbsent(key, PointShadowProjectionRenderer::pipeline));
            pass.setUniform("PointShadowView", uniforms);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("PointShadowDepth", cube.sampled(), spot ? nearest : Objects.requireNonNull(comparison));
            if (surface.adaptiveWords() != null)
                pass.bindTexture(AdaptiveParticleBuffer.SAMPLER, surface.adaptiveWords(), nearest);
            if (surface.toonData() != null) {
                pass.bindTexture("ShadowToonData", surface.toonData(), nearest);
                ToonProfileBindings.bind(pass);
            }
            if (key.transmission()) {
                var atlas = Objects.requireNonNull(EffectTextures.get(SubsurfaceProfiles.ATLAS), "Native subsurface profiles were not prepared");
                if (!spot) pass.bindTexture("PointShadowRawDepth", cube.sampled(), nearest);
                if (surface.subsurfaceData() != null) pass.bindTexture("ShadowSubsurfaceData", surface.subsurfaceData(), nearest);
                pass.bindTexture("LfxSubsurfaceProfiles", atlas.view(), atlas.sampler());
            }
            pass.bindTexture("ShadowSurface", surface.surface(), nearest); pass.bindTexture("ShadowGeometry", surface.geometry(), nearest);
            if (!surface.host()) pass.bindTexture("ShadowNormal", Objects.requireNonNull(surface.normal()), nearest);
            pass.bindTexture("SceneDepth", sceneDepth, nearest);
            if (decals != null) {
                pass.setUniform("DecalReceiverView", decals.view());
                pass.bindTexture("ShadowDecalNormal", decals.textures().normal(), nearest);
            }
            pass.draw(3, 1, 0, 0);
        } finally { if (restoreSeamless) GL11C.glDisable(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS); }
    }

    private static RenderPipeline pipeline(Key key) {
        var bindings = BindGroupLayout.builder().withUniform("PointShadowView", UniformType.UNIFORM_BUFFER)
                .withSampler("PointShadowDepth").withSampler("ShadowSurface").withSampler("ShadowGeometry")
                .withSampler("SceneDepth");
        if (!key.host()) bindings.withSampler("ShadowNormal");
        if (key.adaptive()) bindings.withSampler(AdaptiveParticleBuffer.SAMPLER);
        if (key.decals()) bindings.withUniform("DecalReceiverView", UniformType.UNIFORM_BUFFER).withSampler("ShadowDecalNormal");
        if (key.transmission()) {
            if (!key.spot()) bindings.withSampler("PointShadowRawDepth");
            bindings.withSampler("LfxSubsurfaceProfiles");
        }
        if (key.legacyTransmission()) bindings.withSampler("ShadowSubsurfaceData");
        if (key.toonTransmission()) {
            bindings.withSampler("ShadowToonData");
            ToonProfileBindings.layout(bindings);
        }
        var builder = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/point_shadow/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/point_light_shadow"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withBindGroupLayout(bindings.build()).withShaderDefine("LFX_POINT_SHADOW_QUALITY", key.quality())
                .withShaderDefine("LFX_SHADOW_ZERO_TO_ONE", key.zeroToOne() ? 1 : 0)
                .withColorTargetState(new ColorTargetState(Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MIN)), key.format(), 12));
        if (key.host()) builder.withShaderDefine("LFX_SHADOW_HOST_MATERIAL");
        if (key.spot()) builder.withShaderDefine("LFX_SPOT_SHADOW");
        if (key.decals()) builder.withShaderDefine("LFX_SHADOW_DECALS");
        if (key.adaptive()) builder.withShaderDefine("LFX_SHADOW_ADAPTIVE_NORMAL");
        if (key.transmission()) builder.withShaderDefine("LFX_SHADOW_SUBSURFACE_TRANSMISSION");
        if (key.legacyTransmission()) builder.withShaderDefine("LFX_SHADOW_LEGACY_TRANSMISSION");
        if (key.adaptiveTransmission()) builder.withShaderDefine("LFX_SHADOW_ADAPTIVE_TRANSMISSION");
        if (key.toonTransmission()) builder.withShaderDefine("LFX_SHADOW_TOON_TRANSMISSION");
        return builder.build();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread(); pipelines.clear();
        if (comparison != null) { comparison.close(); comparison = null; }
    }
}
