package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Bake;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Cache;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Fog;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Ray;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSampler;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer.Frame;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeCompute.ShadowInputs;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeCompute.DirectionalShadowInputs;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.fog.FogView;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCollectionUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialInstanceUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialViewUniforms;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.client.render.scene.material.SceneColorCopy;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.render.texture.virtual.MaterialVirtualTextureBindings;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.client.render.precision.WorldOriginUniforms;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.effect.render.fog.FogSettings;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.volume.VolumeTracingSettings;
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
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Draws the live source material through the Niagara volume proxy against the current opaque depth. */
public final class NiagaraVolumeRenderer implements AutoCloseable {
    private enum MaterialMatrixInverse { FLOAT32, DOUBLE_THEN_FLOAT }
    private record PipelineKey(Identifier fragment, GpuFormat format, boolean lightPass, boolean surfaceShadow, boolean spot,
                               boolean directionalShadow, Set<String> textures,
                               boolean materialInstance, int materialView, boolean sceneColor, List<String> collections,
                               List<VirtualTextureRead> virtualTextures, Fog fog) { }
    private record DrawView(CommandEncoder encoder, RenderTarget target, GpuTextureView depth, Matrix4fc projection,
                            Matrix4fc inverseProjection, Matrix4fc inverseView, Vec3 camera, Vec3 cameraForward,
                            int sample, VolumeTracingSettings tracing) { }
    private record DrawVolume(NiagaraVolumeInstance volume, Matrix4d localToWorld, Matrix4d worldToLocal,
                              VolumeLightingGrid lighting, @Nullable VolumePreshadingGrid baking, @Nullable PreparedVolumeMaterial source,
                              GpuBufferSlice clock, @Nullable GpuBufferSlice instance, @Nullable GpuBufferSlice materialView,
                              Map<String, GpuBufferSlice> collections, VolumeFogInputs fog, @Nullable Atmosphere atmosphere) {
        NiagaraVolumeCompute.DrawUniforms uniforms(GpuBufferSlice view) {
            return new NiagaraVolumeCompute.DrawUniforms(view, clock, instance, materialView, collections, atmosphere);
        }
    }
    private static final AtomicInteger NEXT = new AtomicInteger();
    private final Map<PipelineKey, RenderPipeline> pipelines = new HashMap<>();
    private final NiagaraVolumeCompute compute = new NiagaraVolumeCompute();
    private Map<Identifier, PreparedVolumeMaterial> materials = Map.of();
    private final SceneColorCopy sceneColor = new SceneColorCopy();
    private final FogView fogView = new FogView();
    private @Nullable GpuSampler shadowSampler;
    private int frameIndex;

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionfc orientation, RenderTarget target, Vec3 camera,
                     Map<ScenePointLight, VolumeShadow> volumeShadows) {
        draw(frame, modelView, orientation, target, camera, volumeShadows, null);
    }

    public void draw(EffectFrame frame, Matrix4fc modelView, Quaternionfc orientation, RenderTarget target, Vec3 camera,
                     Map<ScenePointLight, VolumeShadow> volumeShadows, @Nullable Atmosphere atmosphere) {
        RenderSystem.assertOnRenderThread();
        if (frame.volumes().isEmpty()) return;
        var platform = NiagaraPlatforms.snapshot();
        if (!VolumeTracingSettings.enabled(platform)) return;
        var depth = Objects.requireNonNull(target.getDepthTextureView(), "Niagara volume rendering requires opaque depth");
        var capture = WorldHdrCapture.INSTANCE;
        var tracing = VolumeTracingSettings.resolve(platform);
        var atmosphereLighting = AtmosphereMaterialLighting.resolve(atmosphere, frame.directionalLights(), platform);
        var projection = new Matrix4f();
        capture.copyProjection(projection);
        var inverseProjection = new Matrix4f(projection).invert();
        var inverseView = new Matrix4f(modelView).invert();
        var cameraForwardVector = new Vector3f(0, 0, -1).mulDirection(inverseView).normalize();
        var cameraForward = new Vec3(cameraForwardVector.x, cameraForwardVector.z, cameraForwardVector.y);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        fogView.prepare(modelView, camera);
        Map<FogSettings, VolumeFogInputs> fogInputs = new HashMap<>();
        Map<ScenePointLight, ShadowInputs> shadows = new HashMap<>();
        volumeShadows.forEach((light, shadow) -> shadows.put(light, shadowInputs(encoder, shadow)));
        Map<SceneDirectionalLight, DirectionalShadowInputs> directionalShadows = new HashMap<>();
        for (var light : frame.directionalLights()) {
            if (light.volumetricScattering() > 0)
                directionalShadows.put(light, directionalShadowInputs(encoder, directionalShadow(light), depth));
        }
        boolean samplesScene = frame.volumes().stream().anyMatch(volume -> volume.material().sceneColor());
        if (samplesScene) sceneColor.capture(encoder, target, !capture.composing());
        var collections = MaterialCollectionUniforms.INSTANCE.frame(encoder, Minecraft.getInstance().level);
        int sample = (capture.viewPaused() ? Math.max(frameIndex - 1, 0) : frameIndex++) & 63;
        var drawView = new DrawView(encoder, target, depth, projection, inverseProjection, inverseView, camera,
                cameraForward, sample, tracing);
        var ordered = frame.volumes().stream().sorted(Comparator.comparingDouble((NiagaraVolumeInstance volume) ->
                distanceSquared(volume, camera)).reversed()).toList();
        var draws = new ArrayList<DrawVolume>(ordered.size());
        for (var volume : ordered) {
            var material = volume.material();
            var fog = fogInputs.computeIfAbsent(material.renderSettings().fog(),
                    settings -> VolumeFogInputs.prepare(encoder, settings, fogView, capture, tracing));
            var clock = materialClock(encoder, frame, material, capture.materialViewMipBias());
            var instance = MaterialInstanceUniforms.write(encoder, material);
            var view = material.materialView() == 0 ? null : MaterialViewUniforms.write(encoder, material.materialView(), camera,
                    modelView, orientation, false, atmosphereLighting);
            var collectionBuffers = collections.prepare(material);
            var localToWorld = volume.localToWorld();
            localToWorld.setTranslation(localToWorld.m30() - camera.x * 100, localToWorld.m31() - camera.z * 100,
                    localToWorld.m32() - camera.y * 100);
            var worldToLocal = NiagaraComponentMatrices.inverse(localToWorld, new Matrix4d());
            var grid = VolumeLightingGrid.resolve(volume, localToWorld, projection, target.width, capture.nearClipCm(), tracing);
            var prepared = materials.get(material.fragmentShader());
            var baking = prepared != null && tracing.preshading().enabled()
                    ? VolumePreshadingGrid.resolve(volume, localToWorld, projection, target.width, capture.nearClipCm(), tracing, grid) : null;
            draws.add(new DrawVolume(volume, localToWorld, worldToLocal, grid, baking, prepared, clock, instance, view,
                    collectionBuffers, fog, atmosphere));
        }
        for (var draw : draws) {
            var volume = draw.volume();
            var material = volume.material();
            var lights = frame.pointLights().stream().filter(light -> light.data().volumetricScattering() > 0
                    && light.data().radius() > 0 && volume.intersectsLight(light.data().shader().worldPosition(), light.data().radius())).toList();
            var directionalLights = frame.directionalLights().stream().filter(light -> light.volumetricScattering() > 0).toList();
            var prepared = coefficients(drawView, draw);
            if (prepared != null && !prepared.supportsLocalLightShapes() && lights.stream().anyMatch(light ->
                    light.data().shader().cone() != null || light.data().shader().rect() != null))
                throw new IllegalStateException("Spot and rect lighting need volume program v2 for " + material.fragmentShader());
            boolean inscattering = prepared != null && tracing.lightingCache() == 2 && (!lights.isEmpty() || !directionalLights.isEmpty());
            if (inscattering) {
                for (int index = 0; index < lights.size(); index++) {
                    var light = lights.get(index);
                    var uniforms = volumeView(drawView, draw, light, null, index == 0 ? 1 : 2, MaterialMatrixInverse.DOUBLE_THEN_FLOAT);
                    var shadow = shadows.get(light);
                    compute.cacheLight(encoder, prepared, material, draw.uniforms(uniforms), depth,
                            material.sceneColor() ? sceneColor.view() : null, shadow, null, draw.lighting(),
                            shadow == null ? Cache.INSCATTERING : shadow.spot() ? Cache.INSCATTERING_SPOT_SHADOW : Cache.INSCATTERING_SHADOW);
                }
                for (int index = 0; index < directionalLights.size(); index++) {
                    var light = directionalLights.get(index);
                    var uniforms = directionalVolumeView(drawView, draw, light, lights.isEmpty() && index == 0 ? 1 : 2,
                            MaterialMatrixInverse.DOUBLE_THEN_FLOAT);
                    var directionalShadow = directionalShadows.get(light);
                    compute.cacheLight(encoder, prepared, material, draw.uniforms(uniforms), depth,
                            material.sceneColor() ? sceneColor.view() : null, null, directionalShadow, draw.lighting(),
                            light.castVolumetricShadow() ? Cache.INSCATTERING_SHADOW : Cache.INSCATTERING);
                }
            }
            drawPass(drawView, draw, prepared, volumeView(drawView, draw, (ScenePointLight) null, 0), false, null, null,
                    inscattering ? Ray.INSCATTERING : Ray.DIRECT);
            if (!inscattering) for (var light : lights) {
                var uniform = volumeView(drawView, draw, light, 1);
                var shadow = shadows.get(light);
                Ray mode = shadow == null ? Ray.DIRECT : shadow.spot() ? Ray.SPOT_SHADOW : Ray.SHADOW;
                if (prepared != null && tracing.lightingCache() == 1 && light.data().castVolumetricShadow()) {
                    compute.cacheLight(encoder, prepared, material, draw.uniforms(uniform), depth,
                            material.sceneColor() ? sceneColor.view() : null, null, null, draw.lighting(), Cache.TRANSMITTANCE);
                    mode = shadow == null ? Ray.TRANSMITTANCE : shadow.spot() ? Ray.TRANSMITTANCE_SPOT_SHADOW : Ray.TRANSMITTANCE_SHADOW;
                }
                drawPass(drawView, draw, prepared, uniform, true, shadow, null, mode);
            }
            if (!inscattering) for (var light : directionalLights) {
                var uniform = directionalVolumeView(drawView, draw, light, 1);
                var directionalShadow = directionalShadows.get(light);
                Ray mode = Ray.DIRECT;
                if (prepared != null && tracing.lightingCache() == 1 && light.castVolumetricShadow()) {
                    compute.cacheLight(encoder, prepared, material, draw.uniforms(uniform), depth,
                            material.sceneColor() ? sceneColor.view() : null, null, directionalShadow,
                            draw.lighting(), Cache.TRANSMITTANCE);
                    mode = Ray.TRANSMITTANCE;
                }
                drawPass(drawView, draw, prepared, uniform, true, null, directionalShadow, mode);
            }
        }
    }

    private @Nullable PreparedVolumeMaterial coefficients(DrawView view, DrawVolume draw) {
        var baking = draw.baking();
        if (baking == null) return draw.source();
        compute.bakeCoefficients(view.encoder(), Objects.requireNonNull(draw.source()), draw.volume().material(),
                draw.uniforms(volumeView(view, draw, null, 0)), view.depth(), baking,
                Bake.sourceFormat(view.tracing().preshading().pixelFormat()));
        return Objects.requireNonNull(materials.get(PreparedVolumeMaterial.PRESHADING_PROGRAM),
                "Missing Niagara preshaded volume shader");
    }

    private static double distanceSquared(NiagaraVolumeInstance volume, Vec3 camera) {
        var box = volume.localBounds();
        var center = volume.localToWorld().transformPosition(new Vector3d((box.minX() + box.maxX()) * .5,
                (box.minY() + box.maxY()) * .5, (box.minZ() + box.maxZ()) * .5));
        return new Vec3(center.x * .01, center.z * .01, center.y * .01).distanceToSqr(camera);
    }

    private void drawPass(DrawView frame, DrawVolume draw, @Nullable PreparedVolumeMaterial prepared, GpuBufferSlice volumeView,
                          boolean lightPass, @Nullable ShadowInputs shadows,
                          @Nullable DirectionalShadowInputs directionalShadows, Ray ray) {
        var material = draw.volume().material();
        var fog = draw.fog();
        var target = frame.target();
        var encoder = frame.encoder();
        var depth = frame.depth();
        var instance = draw.instance();
        var view = draw.materialView();
        if (prepared != null) {
            var integrated = compute.integrate(encoder, prepared, material,
                    draw.uniforms(volumeView),
                     depth, material.sceneColor() ? sceneColor.view() : null, shadows, directionalShadows, ray, fog);
            var key = new PipelineKey(LumenFx.id("core/niagara_volume_composite"), target.getColorTexture().getFormat(),
                    lightPass, false, false, false, Set.of("VolumeRadiance"), false, 0, false, List.of(), List.of(), Fog.NONE);
            try (var pass = encoder.createRenderPass(() -> "LumenFX Niagara volume composite", target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(pipelines.computeIfAbsent(key, NiagaraVolumeRenderer::compositePipeline));
                pass.bindTexture("VolumeRadiance", integrated, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            return;
        }
        var key = new PipelineKey(material.fragmentShader(), target.getColorTexture().getFormat(), lightPass,
                shadows != null, shadows != null && shadows.spot(), directionalShadows != null && directionalShadows.frame() != null,
                Set.copyOf(material.textures().keySet()), instance != null, material.materialView(), material.sceneColor(),
                material.materialCollections().stream().map(value -> value.uniform()).toList(), material.virtualTextureReads(), fog.mode());
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (var pass = encoder.createRenderPass(() -> "LumenFX Niagara volume", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipelines.computeIfAbsent(key, NiagaraVolumeRenderer::pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("NiagaraVolumeView", volumeView);
            pass.setUniform("EffectFrame", draw.clock());
            if (instance != null) pass.setUniform("MaterialInstance", instance);
            if (view != null) pass.setUniform("MaterialView", view);
            AtmosphereMaterialTextures.bind(pass, material.materialView(), draw.atmosphere());
            MaterialCollectionUniforms.bind(pass, draw.collections());
            MaterialTextureBindings.bind(pass, material, linear, WorldHdrCapture.INSTANCE.materialViewMipBias());
            pass.bindTexture("VolumeSceneDepth", depth, nearest);
            if (shadows != null) {
                pass.setUniform("VolumePointShadow", shadows.uniform());
                pass.bindTexture(shadows.spot() ? "VolumeSpotShadowDepth" : "VolumeShadowDepth", shadows.depth(), shadows.sampler());
            }
            if (directionalShadows != null && directionalShadows.frame() != null)
                DirectionalShadowRenderer.bind(pass, directionalShadows.uniform(), directionalShadows.frame(), depth);
            if (material.sceneColor()) pass.bindTexture("LfxSceneColor", sceneColor.view(), linear);
            if (fog.height() != null) pass.setUniform("ExponentialFog", fog.height());
            if (fog.froxel() != null) {
                pass.setUniform("FogVolume", fog.froxel().uniforms());
                pass.bindTexture("FogVolumeSampler", fog.froxel().integrated(), linear);
            }
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline pipeline(PipelineKey key) {
        var bindings = BindGroupLayout.builder().withUniform("NiagaraVolumeView", UniformType.UNIFORM_BUFFER)
                .withUniform("EffectFrame", UniformType.UNIFORM_BUFFER).withSampler("VolumeSceneDepth");
        for (String sampler : new TreeSet<>(key.textures())) bindings.withSampler(sampler);
        MaterialVirtualTextureBindings.layout(bindings, key.virtualTextures(), false);
        if (key.sceneColor()) bindings.withSampler("LfxSceneColor");
        if (key.materialInstance()) bindings.withUniform("MaterialInstance", UniformType.UNIFORM_BUFFER);
        if (key.materialView() != 0) bindings.withUniform("MaterialView", UniformType.UNIFORM_BUFFER);
        AtmosphereMaterialTextures.layout(bindings, key.materialView());
        if (key.surfaceShadow()) bindings.withUniform("VolumePointShadow", UniformType.UNIFORM_BUFFER)
                .withSampler(key.spot() ? "VolumeSpotShadowDepth" : "VolumeShadowDepth");
        if (key.directionalShadow()) DirectionalShadowRenderer.layout(bindings);
        if (key.fog() != Fog.NONE) bindings.withUniform("ExponentialFog", UniformType.UNIFORM_BUFFER);
        if (key.fog() == Fog.FROXEL) bindings.withUniform("FogVolume", UniformType.UNIFORM_BUFFER).withSampler("FogVolumeSampler");
        for (String name : key.collections()) bindings.withUniform(name, UniformType.UNIFORM_BUFFER);
        var blend = new BlendFunction(BlendFactor.ONE, key.lightPass() ? BlendFactor.ONE : BlendFactor.ONE_MINUS_SRC_ALPHA,
                BlendFactor.ZERO, BlendFactor.ONE);
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/niagara_volume/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id("core/niagara_volume")).withFragmentShader(key.fragment())
                .withShaderDefine("LFX_VOLUME_LIGHT_PASS", key.lightPass() ? 1 : 0)
                .withShaderDefine("LFX_VOLUME_SURFACE_SHADOW", key.surfaceShadow() ? 1 : 0)
                .withShaderDefine("LFX_VOLUME_SPOT_SHADOW", key.spot() ? 1 : 0)
                .withShaderDefine("LFX_VOLUME_DIRECTIONAL_SHADOW", key.directionalShadow() ? 1 : 0)
                .withShaderDefine("LFX_VOLUME_HEIGHT_FOG", key.fog() != Fog.NONE ? 1 : 0)
                .withShaderDefine("LFX_VOLUME_FROXEL_FOG", key.fog() == Fog.FROXEL ? 1 : 0)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withDepthStencilState(Optional.empty()).withColorTargetState(new ColorTargetState(Optional.of(blend), key.format(), 7))
                .withBindGroupLayout(bindings.build()).build();
    }

    private static RenderPipeline compositePipeline(PipelineKey key) {
        var blend = new BlendFunction(BlendFactor.ONE, key.lightPass() ? BlendFactor.ONE : BlendFactor.ONE_MINUS_SRC_ALPHA,
                BlendFactor.ZERO, BlendFactor.ONE);
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/niagara_volume_composite/" + NEXT.getAndIncrement()))
                .withVertexShader(LumenFx.id("core/niagara_volume")).withFragmentShader(key.fragment())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withDepthStencilState(Optional.empty()).withColorTargetState(new ColorTargetState(Optional.of(blend), key.format(), 7))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("VolumeRadiance").build()).build();
    }

    private static GpuBufferSlice materialClock(CommandEncoder encoder, EffectFrame frame, MaterialDefinition material, float mipBias) {
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48, alignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat((float)frame.time()).putFloat(mipBias).putFloat((float)frame.realTime()).putFloat((float)frame.engineTime());
            material.writeParameters(data);
            data.putFloat(frame.deltaTime()).putFloat(1).putFloat(1).putFloat(0);
            return mapped.slice();
        }
    }

    private static GpuBufferSlice volumeView(DrawView view, DrawVolume draw, @Nullable ScenePointLight light, int pass) {
        return volumeView(view, draw, light, null, pass, MaterialMatrixInverse.FLOAT32);
    }

    private static GpuBufferSlice directionalVolumeView(DrawView view, DrawVolume draw, SceneDirectionalLight light, int pass) {
        return volumeView(view, draw, null, light, pass, MaterialMatrixInverse.FLOAT32);
    }

    private static GpuBufferSlice directionalVolumeView(DrawView view, DrawVolume draw, SceneDirectionalLight light, int pass,
                                                         MaterialMatrixInverse inverseMode) {
        return volumeView(view, draw, null, light, pass, inverseMode);
    }

    private static GpuBufferSlice volumeView(DrawView view, DrawVolume draw, @Nullable ScenePointLight light,
                                              @Nullable SceneDirectionalLight directional, int pass,
                                              MaterialMatrixInverse inverseMode) {
        var volume = draw.volume();
        var camera = view.camera();
        var tracing = view.tracing();
        var grid = draw.lighting();
        float lodFactor = draw.baking() == null ? 1 : draw.baking().lodFactor();
        try (var mapped = view.encoder().transientMemory().allocateGpuMapped(736 + PrimitiveCustomData.BYTES, alignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            matrix(data, view.inverseProjection());
            matrix(data, view.inverseView());
            matrix(data, new Matrix4f(draw.worldToLocal()));
            matrix(data, new Matrix4f(draw.localToWorld()));
            var box = volume.localBounds();
            vector(data, box.minX(), box.minY(), box.minZ(), 0);
            vector(data, box.maxX(), box.maxY(), box.maxZ(), 0);
            vector(data, volume.resolutionX(), volume.resolutionY(), volume.resolutionZ(), view.sample());
            var settings = volume.settings();
            vector(data, settings.stepFactor() * lodFactor, settings.shadowStepFactor() * lodFactor,
                    settings.shadowBiasFactor(), tracing.heightFog() ? 1 : 0);
            vector(data, tracing.distance(), tracing.shadowDistance(), tracing.stepSize(), tracing.maxSteps());
            vector(data, 1.0 / view.target().width, 1.0 / view.target().height, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0, 1);
            vector(data, pass, view.projection().m33() >= 1 ? 1 : 0, tracing.jitter() ? 1 : 0, tracing.shadowStepSize());
            if (light == null && directional == null) {
                for (int i = 0; i < 16; i++) data.putFloat(0);
            } else if (light != null) {
                var source = light.data().shader();
                var p = source.worldPosition();
                vector(data, p.x - camera.x * 100, p.y - camera.z * 100, p.z - camera.y * 100, light.data().radius());
                var color = source.color().scale(light.data().volumetricScattering());
                vector(data, color.x, color.y, color.z, light.data().castVolumetricShadow() ? 1 : 0);
                vector(data, source.falloffExponent() == 0.0f ? 1 : 0,
                        source.sourceRadius(), source.falloffExponent(), source.softSourceRadius());
                var tangent = source.tangent();
                vector(data, tangent.x, tangent.y, tangent.z, source.sourceLength());
            } else {
                var direction = directional.direction();
                vector(data, 0, 0, 0, tracing.shadowDistance());
                var color = directional.color().scale(directional.volumetricScattering());
                vector(data, color.x, color.y, color.z, directional.castVolumetricShadow() ? 1 : 0);
                vector(data, 0, 0, 0, 0);
                var forward = view.cameraForward();
                vector(data, forward.x, forward.y, forward.z, 0);
            }
            vector(data, grid.x(), grid.y(), grid.z(), 1);
            var shader = light == null ? null : light.data().shader();
            var cone = shader == null ? null : shader.cone();
            var direction = directional != null ? directional.direction() : shader == null ? Vec3.ZERO : shader.direction();
            vector(data, direction.x, direction.y, direction.z, directional != null || cone == null ? -1f : cone.cosineOuter());
            vector(data, directional != null || cone == null ? 0f : cone.inverseCosineDifference(), 0, 0, 0);
            var rect = shader == null ? null : shader.rect();
            if (rect == null) vector(data, 0, 0, 0, 0);
            else vector(data, rect.halfWidth(), rect.halfHeight(), rect.barnCosine(), rect.barnLength());
            vector(data, directional == null ? 0 : 1, 0, 0, 0);
            writeComponentObjectData(data, volume, inverseMode);
            return mapped.slice();
        }
    }

    private static void writeComponentObjectData(ByteBuffer data, NiagaraVolumeInstance volume, MaterialMatrixInverse inverseMode) {
        var componentToWorld = volume.primitiveComponentToWorld();
        var box = NiagaraComponentBounds.boxSphere(componentToWorld, volume.localBounds());
        WorldOriginUniforms.writeSourcePosition(data, box.origin().x, box.origin().y, box.origin().z);
        vector(data, box.extent().x, box.extent().y, box.extent().z, box.radius());
        var zAxis = new Vector3d(componentToWorld.m20(), componentToWorld.m21(), componentToWorld.m22());
        if (zAxis.lengthSquared() > 1.0e-8) zAxis.normalize();
        else zAxis.zero();
        vector(data, zAxis.x, zAxis.y, zAxis.z, 0);
        var absoluteLocalToWorld = volume.localToWorld();
        var materialLocalToWorld = new Matrix4f(absoluteLocalToWorld);
        matrix(data, materialLocalToWorld);
        matrix(data, materialWorldToLocal(absoluteLocalToWorld, materialLocalToWorld, inverseMode));
        var actor = volume.actorPosition();
        WorldOriginUniforms.writeSourcePosition(data, actor.x * 100, actor.z * 100, actor.y * 100);
        volume.customPrimitiveData().write(data);
    }

    private static Matrix4f materialWorldToLocal(Matrix4d absoluteLocalToWorld, Matrix4f floatLocalToWorld,
                                                  MaterialMatrixInverse inverseMode) {
        if (inverseMode == MaterialMatrixInverse.DOUBLE_THEN_FLOAT) {
            double determinant = absoluteLocalToWorld.determinant();
            if (!Double.isFinite(determinant)) return new Matrix4f();
            return new Matrix4f(NiagaraComponentMatrices.inverse(absoluteLocalToWorld, new Matrix4d()));
        }
        float determinant = floatLocalToWorld.determinant();
        if (!Float.isFinite(determinant) || determinant == 0 || nilLinearTransform(floatLocalToWorld)) return new Matrix4f();
        return new Matrix4f(floatLocalToWorld).invert();
    }

    private static boolean nilLinearTransform(Matrix4f matrix) {
        for (int column = 0; column < 3; column++) for (int row = 0; row < 3; row++)
            if (Math.abs(matrix.get(column, row)) > 1.0e-8f) return false;
        return true;
    }

    private static @Nullable Frame directionalShadow(SceneDirectionalLight light) {
        var capture = WorldHdrCapture.INSTANCE;
        if (!capture.composing()) return null;
        var prepared = capture.translucencyDirectionalLights().shadow().apply(light);
        if (prepared != null) return prepared;
        var main = SceneDirectionalLight.selectForward(capture.sceneDirectionalLights());
        return main != null && main.identity() == light.identity() ? capture.forwardDirectionalShadows() : null;
    }

    private static DirectionalShadowInputs directionalShadowInputs(CommandEncoder encoder, @Nullable Frame frame,
                                                                    GpuTextureView fallbackDepth) {
        return new DirectionalShadowInputs(DirectionalShadowRenderer.uniforms(encoder, frame,
                DirectionalShadowRenderer.VOLUME_CASCADE_LIMIT), frame, fallbackDepth);
    }

    private static int alignment() { return RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(); }
    private ShadowInputs shadowInputs(CommandEncoder encoder, VolumeShadow shadow) {
        boolean spot = shadow.matrices().spot();
        if (!spot && shadowSampler == null) shadowSampler = PointShadowSampler.create();
        GpuSampler sampler = spot ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) : Objects.requireNonNull(shadowSampler);
        float inverseResolution = 1f / shadow.depth().size();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(PointShadowMatrices.BYTES + 32, alignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            shadow.matrices().writeSampling(data);
            data.putFloat(inverseResolution).putFloat(spot ? shadow.matrices().spotSettings().transitionScale() : 0.03f * 512f * inverseResolution);
            shadow.matrices().writeLinearDepth(data);
            data.putFloat(shadow.strength()).putInt(0).putLong(0);
            return new ShadowInputs(mapped.slice(), shadow.depth().sampled(), sampler, spot);
        }
    }
    private static void matrix(ByteBuffer target, Matrix4fc value) { value.get(target.position(), target); target.position(target.position() + 64); }
    private static void vector(ByteBuffer target, double x, double y, double z, double w) {
        target.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)w);
    }
    public Map<Identifier, PreparedVolumeMaterial> materials() { return materials; }
    public void materials(Map<Identifier, PreparedVolumeMaterial> values) { materials = Map.copyOf(values); }

    @Override public void close() {
        try { compute.close(); }
        finally {
            try { if (shadowSampler != null) { shadowSampler.close(); shadowSampler = null; } }
            finally { sceneColor.close(); pipelines.clear(); frameIndex = 0; }
        }
    }
}
