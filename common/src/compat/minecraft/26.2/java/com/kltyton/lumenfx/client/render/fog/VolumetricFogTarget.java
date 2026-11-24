package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer.VolumeMaterial;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** View-owned material, conservative-depth, RGBA history and ordered volume integration. */
public final class VolumetricFogTarget implements AutoCloseable {
    public static final int UNIFORM_BYTES = 416;
    public record Frame(GpuTextureView integrated, GpuBufferSlice uniforms, GpuBufferSlice heightUniforms) { }
    private enum Stage {
        DEPTH("conservative_depth", GpuFormat.R32_FLOAT, "SceneDepthSampler"),
        MATERIAL("material_setup", GpuFormat.RGBA16_FLOAT, "SceneDepthSampler"),
        HISTORY("history", GpuFormat.RGBA16_FLOAT, "ConservativeDepthSampler", "PreviousConservativeDepthSampler", "MaterialSampler", "HistorySampler");
        final String shader;
        final GpuFormat format;
        final String[] samplers;
        Stage(String shader, GpuFormat format, String... samplers) { this.shader = shader; this.format = format; this.samplers = samplers; }
    }
    private record History(FroxelGrid grid, Vec3 camera, Object world, @Nullable Object cameraOwner,
                           long cutVersion, long renderFrame, int width, int height, Matrix4f worldToClip) { }
    private static final EnumMap<Stage, RenderPipeline> PIPELINES = new EnumMap<>(Stage.class);
    private static RenderPipeline scatteringMaterialPipeline;
    private final VolumetricFog settings;
    private final FogIntegration integration;
    private ComputeTexture integratedOutput;
    private final Matrix4f adjustedPreviousWorldToClip = new Matrix4f();
    private TextureTarget material, medium, previous, spare, previousDepth, spareDepth;
    private @Nullable History history;
    private @Nullable History pendingHistory;
    private VolumetricParticleLights particleLights;
    private @Nullable FogDirectionalLightFunction directionalFunction;

    public VolumetricFogTarget(VolumetricFog settings, FogIntegration integration) {
        this.settings = settings;
        this.integration = integration;
    }

    public Frame prepare(FogView view, Vec3 camera, Object world, Object cameraOwner, GpuTextureView opaqueDepth,
                         List<ParticleLight> lights, List<ScenePointLight> localLights, Map<ScenePointLight, VolumeShadow> shadows,
                         LightFunctionAtlas.@Nullable Frame lightFunctions, @Nullable SceneDirectionalLight directional,
                         DirectionalShadowRenderer.@Nullable Frame directionalShadow, @Nullable VolumeMaterial directionalMaterial, float exposure) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        var capture = WorldHdrCapture.INSTANCE;
        int width = opaqueDepth.getWidth(0), height = opaqueDepth.getHeight(0);
        var grid = FroxelGrid.forView(width, height, settings.pixelSize(), settings.slices(),
                settings.maximumVolumeDimension(), device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA16_FLOAT),
                capture.nearClipCm(), settings.startCm(), settings.endCm(), settings.depthDistribution());
        long renderFrame = capture.renderFrameSequence();
        var last = history;
        boolean valid = !capture.fogViewPaused() && last != null && last.renderFrame() + 1 == renderFrame && last.world() == world
                && last.cameraOwner() == cameraOwner && last.cutVersion() == capture.cameraCutVersion();
        var encoder = device.createCommandEncoder();
        List<ParticleLight> scatteringLights = settings.sceneLighting()
                ? lights.stream().filter(light -> light.volumetricScattering() > 0).toList() : List.of();
        List<ScenePointLight> scatteringLocalLights = settings.sceneLighting() ? localLights.stream()
                .filter(light -> light.data().volumetricScattering() > 0 && light.data().radius() > 0).toList() : List.of();
        VolumetricParticleLights.Directional scatteringDirectional = null;
        if (settings.sceneLighting() && directional != null && directional.volumetricScattering() > 0) {
            var atlas = lightFunctions == null || lightFunctions.directional() == null ? null
                    : new LightFunctionAtlas.Sample(lightFunctions.texture(), lightFunctions.directional());
            FogDirectionalLightFunction.Frame projected = null;
            if (atlas == null && directionalMaterial != null) {
                if (directionalFunction == null) directionalFunction = new FogDirectionalLightFunction();
                float scale = Float.intBitsToFloat(Objects.requireNonNull(NiagaraPlatforms.snapshot().console(
                        "r.VolumetricFog.LightFunction.DirectionalLightSupersampleScale")).bits());
                projected = directionalFunction.prepare(encoder, view, camera, grid, settings.endCm(), scale, directional, directionalMaterial);
            }
            var color = settings.overrideLightColors() && settings.height().directional().startCm() >= 0
                    ? settings.height().directional().color().scale(directional.luminance() * directional.volumetricScattering())
                    : directional.color().scale(directional.volumetricScattering());
            scatteringDirectional = new VolumetricParticleLights.Directional(directional.direction(), color,
                    directional.castVolumetricShadow() ? directionalShadow : null, atlas, projected);
        }
        var skyLight = settings.skyLight();
        var precomputed = settings.sceneLighting() && NiagaraPlatforms.snapshot().staticLightingAllowed()
                ? settings.precomputedLighting() : null;
        boolean injectSky = settings.sceneLighting() && skyLight != null && skyLight.affectsWorld() && skyLight.volumetricScattering() > 0
                && (settings.overrideLightColors() || !skyLight.staticLighting());
        boolean injectPrecomputed = precomputed != null && (precomputed.scatteringIntensity() > 0
                || injectSky && skyLight != null && skyLight.staticShadowing());
        boolean injectLights = !scatteringLights.isEmpty() || !scatteringLocalLights.isEmpty() || scatteringDirectional != null
                || injectSky || injectPrecomputed;
        material = target(material, "LumenFX fog material", grid.atlas().width(), grid.atlas().height(), GpuFormat.RGBA16_FLOAT);
        if (injectLights) medium = target(medium, "LumenFX fog scattering coefficient",
                grid.atlas().width(), grid.atlas().height(), GpuFormat.RGBA16_FLOAT);
        spare = target(spare, "LumenFX fog current", grid.atlas().width(), grid.atlas().height(), GpuFormat.RGBA16_FLOAT);
        if (settings.conservativeDepth())
            spareDepth = target(spareDepth, "LumenFX fog conservative depth", grid.x(), grid.y(), GpuFormat.R32_FLOAT);
        GpuBufferSlice heightUniforms = HeightFogUniforms.write(encoder, settings.height(), view);
        GpuBufferSlice uniforms = uniforms(encoder, view, camera, width, height, grid, valid);
        if (settings.conservativeDepth()) draw(encoder, Stage.DEPTH, spareDepth, uniforms, heightUniforms,
                opaqueDepth, null, null, null, null);
        GpuTextureView currentDepth = settings.conservativeDepth() ? spareDepth.getColorTextureView() : opaqueDepth;
        GpuTextureView oldDepth = valid && previousDepth != null ? previousDepth.getColorTextureView() : currentDepth;
        draw(encoder, Stage.MATERIAL, material, uniforms, heightUniforms, opaqueDepth, null, null, null, null,
                injectLights ? medium.getColorTextureView() : null);
        if (injectLights) {
            if (particleLights == null) particleLights = new VolumetricParticleLights();
            var external = capture.shaderpackFrame();
            long frame = external == null ? capture.outputFrameIndex() : external.hostFrameIdentity();
            particleLights.inject(encoder, scatteringLights, scatteringLocalLights, shadows, lightFunctions, scatteringDirectional,
                    injectSky ? skyLight : null, injectPrecomputed ? precomputed : null, camera,
                    material, medium.getColorTextureView(), opaqueDepth, uniforms, view, grid, settings, frame,
                    currentDepth, oldDepth, exposure);
        }
        draw(encoder, Stage.HISTORY, spare, uniforms, heightUniforms, null, currentDepth, oldDepth,
                material.getColorTextureView(), valid ? previous.getColorTextureView() : material.getColorTextureView());
        if (integratedOutput == null || integratedOutput.width() != grid.atlas().width()
                || integratedOutput.height() != grid.atlas().height()) {
            var next = integration.createOutput(grid);
            if (integratedOutput != null) PhotonWorldRenderState.closeAtFrameEnd(integratedOutput);
            integratedOutput = next;
        }
        GpuTextureView integrated = integration.integrate(spare.getColorTextureView(), uniforms, grid, integratedOutput);
        if (settings.temporal()) {
            var worldToClip = new Matrix4f();
            view.copyUnjitteredWorldToClip(worldToClip);
            pendingHistory = new History(grid, camera, world, cameraOwner, capture.cameraCutVersion(), renderFrame,
                    width, height, worldToClip);
        }
        return new Frame(integrated, uniforms, heightUniforms);
    }

    public void finishFrame() {
        if (pendingHistory == null) return;
        TextureTarget old = previous;
        previous = spare;
        spare = old;
        if (settings.conservativeDepth()) {
            old = previousDepth;
            previousDepth = spareDepth;
            spareDepth = old;
        }
        history = pendingHistory;
        pendingHistory = null;
    }

    public void abortFrame() { pendingHistory = null; }

    private GpuBufferSlice uniforms(CommandEncoder encoder, FogView view, Vec3 camera, int width, int height,
                                    FroxelGrid grid, boolean valid) {
        var capture = WorldHdrCapture.INSTANCE;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        var last = valid ? java.util.Objects.requireNonNull(history) : null;
        FroxelGrid old = last == null ? grid : last.grid();
        if (last != null) adjustedPreviousWorldToClip.set(last.worldToClip()).translate((float)(camera.x - last.camera().x),
                (float)(camera.y - last.camera().y), (float)(camera.z - last.camera().z));
        else view.copyUnjitteredWorldToClip(adjustedPreviousWorldToClip);
        float currentRectScaleX = width / (float)(grid.x() * grid.pixelSize());
        float currentRectScaleY = height / (float)(grid.y() * grid.pixelSize());
        float oldRectScaleX = last == null ? currentRectScaleX : last.width() / (float)(old.x() * old.pixelSize());
        float oldRectScaleY = last == null ? currentRectScaleY : last.height() / (float)(old.y() * old.pixelSize());
        try (var allocation = encoder.transientMemory().allocateGpuMapped(UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = allocation.data().order(ByteOrder.nativeOrder());
            view.writeClipMatrices(data);
            adjustedPreviousWorldToClip.get(data);
            data.position(192);
            data.putFloat(grid.x()).putFloat(grid.y()).putFloat(grid.z()).putFloat(grid.atlas().columns());
            data.putFloat(grid.atlas().width()).putFloat(grid.atlas().height()).putFloat(1f / grid.atlas().width()).putFloat(1f / grid.atlas().height());
            data.putFloat(grid.depthScale()).putFloat(grid.depthOffset()).putFloat(grid.depthDistribution())
                    .putFloat(settings.nearFadeCm() > 0 ? 1f / settings.nearFadeCm() : 100000000f);
            data.putFloat((float)view.cameraHeightCm()).putFloat(valid ? settings.historyWeight() : 0)
                    .putFloat(settings.centerOffset()).putFloat(settings.centerThreshold());
            data.putFloat(capture.depthBias()).putFloat(capture.depthScaleCm())
                    .putFloat(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0).putFloat(settings.conservativeDepth() ? 1 : 0);
            data.putFloat(settings.endCm()).putFloat(settings.startCm()).putFloat(oldRectScaleX / currentRectScaleX).putFloat(oldRectScaleY / currentRectScaleY);
            data.putFloat((float)settings.emissive().x).putFloat((float)settings.emissive().y).putFloat((float)settings.emissive().z)
                    .putFloat(settings.emissiveScalesWithDensity() ? 1 : 0);
            data.putFloat(old.x()).putFloat(old.y()).putFloat(old.z()).putFloat(old.atlas().columns());
            data.putFloat(old.atlas().width()).putFloat(old.atlas().height()).putFloat(1f / old.atlas().width()).putFloat(1f / old.atlas().height());
            data.putFloat(grid.maximumU()).putFloat(grid.maximumV()).putFloat(old.maximumU()).putFloat(old.maximumV());
            data.putFloat(1).putFloat(1).putFloat(1).putFloat(1);
            view.writeForward(data);
            data.putFloat(settings.extinctionScale()).putFloat(grid.pixelSize()).putFloat(settings.inverseSquaredBiasScale()).putFloat(0);
            data.putFloat((float)settings.albedo().x).putFloat((float)settings.albedo().y).putFloat((float)settings.albedo().z)
                    .putFloat(settings.scatteringDistribution());
            return allocation.slice();
        }
    }

    private static TextureTarget target(TextureTarget old, String label, int width, int height, GpuFormat format) {
        if (old == null) return new TextureTarget(label, width, height, false, format);
        if (old.width != width || old.height != height) old.resize(width, height);
        return old;
    }

    private static RenderPipeline pipeline(Stage stage) {
        return PIPELINES.computeIfAbsent(stage, key -> {
            var bindings = BindGroupLayout.builder().withUniform("FogVolume", UniformType.UNIFORM_BUFFER)
                    .withUniform("ExponentialFog", UniformType.UNIFORM_BUFFER);
            for (String sampler : key.samplers) bindings.withSampler(sampler);
            var builder = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/fog/" + key.shader))
                    .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("fog/" + key.shader))
                    .withBindGroupLayout(bindings.build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), key.format, ColorTargetState.WRITE_ALL))
                    .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
            return builder.build();
        });
    }

    private static RenderPipeline scatteringMaterialPipeline() {
        if (scatteringMaterialPipeline == null) scatteringMaterialPipeline = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(pipeline(Stage.MATERIAL))
                .withLocation(LumenFx.id("pipeline/fog/material_scattering_setup"))
                .withShaderDefine("LFX_FOG_SCATTERING_OUTPUT")
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                .build();
        return scatteringMaterialPipeline;
    }

    private static void draw(CommandEncoder encoder, Stage stage, TextureTarget target, GpuBufferSlice uniforms,
                              GpuBufferSlice height, GpuTextureView sceneDepth, GpuTextureView conservative,
                              GpuTextureView previousConservative, GpuTextureView material,
                              GpuTextureView history) {
        draw(encoder, stage, target, uniforms, height, sceneDepth, conservative, previousConservative, material, history, null);
    }

    private static void draw(CommandEncoder encoder, Stage stage, TextureTarget target, GpuBufferSlice uniforms,
                              GpuBufferSlice height, GpuTextureView sceneDepth, GpuTextureView conservative,
                              GpuTextureView previousConservative, GpuTextureView material,
                              GpuTextureView history, GpuTextureView scattering) {
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX fog " + stage.shader)
                .withColorAttachment(target.getColorTextureView())
                .withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, target.width, target.height));
        if (scattering != null) descriptor.withColorAttachment(scattering);
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(scattering != null ? scatteringMaterialPipeline() : pipeline(stage));
            pass.setUniform("FogVolume", uniforms);
            pass.setUniform("ExponentialFog", height);
            for (String name : stage.samplers) {
                GpuTextureView source = switch (name) {
                    case "SceneDepthSampler" -> sceneDepth;
                    case "ConservativeDepthSampler" -> conservative;
                    case "PreviousConservativeDepthSampler" -> previousConservative;
                    case "MaterialSampler" -> material;
                    case "HistorySampler" -> history;
                    default -> throw new IllegalStateException("Unknown volume sampler " + name);
                };
                pass.bindTexture(name, source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
            pass.draw(3, 1, 0, 0);
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (integratedOutput != null) {
            integratedOutput.close();
            integratedOutput = null;
        }
        if (particleLights != null) { particleLights.close(); particleLights = null; }
        if (directionalFunction != null) { directionalFunction.close(); directionalFunction = null; }
        for (TextureTarget target : new TextureTarget[]{material, medium, previous, spare, previousDepth, spareDepth})
            if (target != null) target.destroyBuffers();
        material = medium = previous = spare = previousDepth = spareDepth = null;
        history = pendingHistory = null;
    }
}
