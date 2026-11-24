package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.decal.DecalBuffers;
import com.kltyton.lumenfx.client.render.decal.DecalReceiverView;
import com.kltyton.lumenfx.client.render.light.scene.PointLightProjection;
import com.kltyton.lumenfx.client.render.light.shadow.cache.PointShadowStaticCache;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.DynamicPointShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.DynamicShadowScene;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.TerrainPointShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.TerrainShadowScene;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.texture.ProjectedShadowDepthTarget;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.kltyton.lumenfx.client.render.texture.cube.CubeDepthTarget;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import java.util.Collections;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Prepares caster snapshots and reuses depth allocations across consecutive local-light draws. */
public final class PointShadowSceneRenderer implements AutoCloseable {
    @FunctionalInterface
    public interface ParticleCasters {
        void draw(ScenePointLight light, ShadowDepthTarget depth, PointShadowMatrices matrices);
    }

    public record Prepared(PointShadowResolution.Plan plan, @Nullable Consumer<RenderTarget> project,
                           @Nullable VolumeShadow volume) { }
    public record VolumeShadow(ShadowDepthTarget depth, PointShadowMatrices matrices, Runnable renderCasters,
                               @Nullable PointShadowProjectionSettings projectionSettings, float strength) {
        public VolumeShadow(ShadowDepthTarget depth, PointShadowMatrices matrices, Runnable renderCasters,
                            @Nullable PointShadowProjectionSettings projectionSettings) {
            this(depth, matrices, renderCasters, projectionSettings, 1f);
        }
        public VolumeShadow(ShadowDepthTarget depth, PointShadowMatrices matrices, Runnable renderCasters) {
            this(depth, matrices, renderCasters, null);
        }
    }
    private record TargetKey(boolean spot, int size) { }
    private static final class SharedCasterDraw implements Runnable {
        private @Nullable Runnable pending;

        private SharedCasterDraw(Runnable pending) { this.pending = pending; }

        @Override public void run() {
            if (pending == null) return;
            pending.run();
            pending = null;
        }
    }
    private final PointShadowResolution.Settings settings;
    private final PointShadowProjectionSettings.BiasControls bias;
    private final PointShadowStaticCache cache;
    private final Map<TargetKey, ShadowDepthTarget> targets = new HashMap<>();
    private final Map<Long, ShadowDepthTarget> volumeTargets = new HashMap<>();
    private final TerrainPointShadowRenderer terrain = new TerrainPointShadowRenderer();
    private final DynamicPointShadowRenderer dynamic = new DynamicPointShadowRenderer();
    private final PointShadowProjectionRenderer projection = new PointShadowProjectionRenderer();
    private int maximumCubeSize, maximumProjectedSize;

    public PointShadowSceneRenderer() { this(PointShadowResolution.Settings.DEFAULT, PointShadowProjectionSettings.BiasControls.DEFAULT); }
    public PointShadowSceneRenderer(PointShadowResolution.Settings settings, PointShadowProjectionSettings.BiasControls bias) {
        this(settings, bias, PointShadowStaticCache.Controls.DEFAULT);
    }
    public PointShadowSceneRenderer(PointShadowResolution.Settings settings, PointShadowProjectionSettings.BiasControls bias,
                                    PointShadowStaticCache.Controls controls) {
        this.settings = Objects.requireNonNull(settings); this.bias = Objects.requireNonNull(bias);
        cache = new PointShadowStaticCache(Objects.requireNonNull(controls));
    }

    public Map<ScenePointLight, Prepared> prepare(EffectFrame frame, PointLightProjection view, RenderTarget scene,
                                                 Matrix4fc cameraProjection, Vec3 camera,
                                                 @Nullable Surfaces nativeSurface, @Nullable ShaderpackMaterialSurfaces hostSurface) {
        return prepare(frame, view, scene, cameraProjection, camera, nativeSurface, hostSurface, null, null);
    }

    public Map<ScenePointLight, Prepared> prepare(EffectFrame frame, PointLightProjection view, RenderTarget scene,
                                                 Matrix4fc cameraProjection, Vec3 camera,
                                                 @Nullable Surfaces nativeSurface, @Nullable ShaderpackMaterialSurfaces hostSurface,
                                                 @Nullable Matrix4fc modelView, DecalBuffers.@Nullable Frame decalFrame) {
        return prepare(frame, view, scene, cameraProjection, camera, nativeSurface, hostSurface, modelView, decalFrame, null);
    }

    public Map<ScenePointLight, Prepared> prepare(EffectFrame frame, PointLightProjection view, RenderTarget scene,
                                                 Matrix4fc cameraProjection, Vec3 camera,
                                                 @Nullable Surfaces nativeSurface, @Nullable ShaderpackMaterialSurfaces hostSurface,
                                                 @Nullable Matrix4fc modelView, DecalBuffers.@Nullable Frame decalFrame,
                                                 @Nullable ParticleCasters particleCasters) {
        return prepare(frame, view, scene, cameraProjection, camera, nativeSurface, hostSurface, modelView, decalFrame,
                particleCasters, null);
    }

    public Map<ScenePointLight, Prepared> prepare(EffectFrame frame, PointLightProjection view, RenderTarget scene,
                                                 Matrix4fc cameraProjection, Vec3 camera,
                                                 @Nullable Surfaces nativeSurface, @Nullable ShaderpackMaterialSurfaces hostSurface,
                                                 @Nullable Matrix4fc modelView, DecalBuffers.@Nullable Frame decalFrame,
                                                 @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials) {
        return prepare(frame, view, scene, cameraProjection, camera, nativeSurface, hostSurface, modelView, decalFrame, particleCasters, materials,
                false, false);
    }

    public Map<ScenePointLight, Prepared> prepare(EffectFrame frame, PointLightProjection view, RenderTarget scene,
                                                 Matrix4fc cameraProjection, Vec3 camera,
                                                 @Nullable Surfaces nativeSurface, @Nullable ShaderpackMaterialSurfaces hostSurface,
                                                 @Nullable Matrix4fc modelView, DecalBuffers.@Nullable Frame decalFrame,
                                                 @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials,
                                                 boolean sceneFog, boolean translucentLightingVolume) {
        return prepare(frame, view, scene, cameraProjection, camera, nativeSurface, hostSurface, modelView, decalFrame,
                particleCasters, materials, sceneFog, translucentLightingVolume, false);
    }

    public Map<ScenePointLight, Prepared> prepare(EffectFrame frame, PointLightProjection view, RenderTarget scene,
                                                 Matrix4fc cameraProjection, Vec3 camera,
                                                 @Nullable Surfaces nativeSurface, @Nullable ShaderpackMaterialSurfaces hostSurface,
                                                 @Nullable Matrix4fc modelView, DecalBuffers.@Nullable Frame decalFrame,
                                                 @Nullable ParticleCasters particleCasters, OpaqueParticleSurfaces.@Nullable Frame materials,
                                                 boolean sceneFog, boolean translucentLightingVolume, boolean forwardLocalShadows) {
        RenderSystem.assertOnRenderThread();
        cache.beginFrame(frame.realTime());
        var result = new IdentityHashMap<ScenePointLight, Prepared>();
        if (frame.pointLights().isEmpty()) {
            releaseUnusedVolumeTargets(Set.of());
            return result;
        }
        if (nativeSurface == null && hostSurface == null) throw new IllegalArgumentException("Point shadows require the actual scene receiver surfaces");
        var minecraft = Minecraft.getInstance();
        var renderer = minecraft.levelRenderer;
        Set<GpuTexture> worldDepths = Collections.newSetFromMap(new IdentityHashMap<>());
        addDepth(worldDepths, scene); addDepth(worldDepths, minecraft.gameRenderer.mainRenderTarget());
        addDepth(worldDepths, renderer.itemEntityTarget()); addDepth(worldDepths, renderer.weatherTarget());
        var depth = Objects.requireNonNull(scene.getDepthTextureView(), "Point shadow receivers need scene depth");
        PointShadowProjectionRenderer.DecalInputs decalInputs = null;
        if (decalFrame != null && hostSurface == null) {
            var device = RenderSystem.getDevice(); var encoder = device.createCommandEncoder();
            try (var mapped = encoder.transientMemory().allocateGpuMapped(DecalReceiverView.UNIFORM_BYTES,
                    device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
                new DecalReceiverView().write(Objects.requireNonNull(modelView, "Decal shadow normals require the actual model-view matrix"),
                        mapped.data().order(ByteOrder.nativeOrder()), 0);
                decalInputs = new PointShadowProjectionRenderer.DecalInputs(decalFrame, mapped.slice());
            }
        }
        var receivedDecals = decalInputs;
        var retainedVolumes = new HashSet<Long>();
        for (var light : frame.pointLights()) {
            var cone = light.data().shader().cone();
            boolean spot = cone != null;
            if (spot && maximumProjectedSize == 0) maximumProjectedSize = ProjectedShadowDepthTarget.maximumSize();
            if (!spot && maximumCubeSize == 0) maximumCubeSize = CubeDepthTarget.maximumSize();
            int maximumSize = spot ? maximumProjectedSize : maximumCubeSize;
            var requested = PointShadowResolution.prepare(light, view, settings, maximumSize);
            if (!requested.projected()) { result.put(light, new Prepared(requested, null, null)); continue; }
            var terrainScene = light.data().castStaticShadow() ? TerrainShadowScene.collect(renderer, light) : new TerrainShadowScene.Frame(List.of(), 0);
            var cached = cache.prepare(light, requested, terrainScene, Math.min(settings.maximumResolution(), maximumSize));
            var plan = cached.plan();
            Vec3 source = light.data().shader().worldPosition();
            boolean volumeReceiver = light.data().volumetricScattering() > 0 && (sceneFog && light.data().castVolumetricShadow()
                    || frame.volumes().stream().anyMatch(volume -> volume.intersectsLight(source, light.data().radius())))
                    || translucentLightingVolume && light.data().shader().affectsTranslucency()
                    || forwardLocalShadows && light.data().shader().affectsTranslucency();
            if (volumeReceiver) retainedVolumes.add(light.identity());
            var targetKey = new TargetKey(spot, plan.resolution());
            var shadowDepth = volumeReceiver ? volumeTarget(light.identity(), targetKey)
                    : targets.computeIfAbsent(targetKey, key -> createTarget("LumenFX local-light shadow " + key.size(), key));
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            var matrices = cone == null ? new PointShadowMatrices(light.data().radius(), zeroToOne, light.identity(),
                    light.data().shadowSettings().nearClipCm())
                    : new PointShadowMatrices(light.data().radius(), cone, light.data().worldToLight(new Matrix4d()),
                        plan.resolution(), zeroToOne, light.data().shadowSettings(), light.data().lightingChannels(), light.identity());
            boolean movingCasters = light.data().castDynamicShadow() && cached.movingSubjects();
            var dynamicScene = movingCasters ? DynamicShadowScene.collect(minecraft, renderer, light)
                    : new DynamicShadowScene.Frame(new Vec3(source.x * .01, source.z * .01, source.y * .01), List.of(), List.of());
            var sampler = renderer.chunkLayerSampler;
            var atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            var shadowBias = light.data().shader().rect() == null ? bias : PointShadowProjectionSettings.BiasControls.RECT;
            var parameters = PointShadowProjectionSettings.prepare(light.data().shadowSettings(), plan.resolution(), shadowBias, plan.fade(), plan.quality());
            Runnable drawCasters = () -> {
                if (cached.mode() == PointShadowStaticCache.Mode.UNCACHED) terrain.draw(terrainScene, shadowDepth, matrices, atlas, sampler);
                else {
                    var staticDepth = cached.depth();
                    if (cached.mode() == PointShadowStaticCache.Mode.STATIC_AND_MOVING) {
                        if (staticDepth != null) terrain.draw(cached.staticCasters(), staticDepth, matrices, atlas, sampler);
                        cached.completeStaticPass();
                    }
                    if (staticDepth != null) shadowDepth.copyFrom(staticDepth);
                    terrain.draw(cached.changingCoverage(), shadowDepth, matrices, atlas, sampler, staticDepth == null);
                }
                dynamic.draw(dynamicScene, shadowDepth, matrices, worldDepths);
                if (movingCasters && particleCasters != null) particleCasters.draw(light, shadowDepth, matrices);
            };
            Runnable render = volumeReceiver ? new SharedCasterDraw(drawCasters) : drawCasters;
            var volume = volumeReceiver ? new VolumeShadow(shadowDepth, matrices, render, parameters,
                    light.data().shadowSettings().strength()) : null;
            result.put(light, new Prepared(plan, attenuation -> {
                render.run();
                if (hostSurface != null) projection.draw(shadowDepth, matrices, light, parameters, view.viewToSourceWorld(), camera,
                        hostSurface, depth, attenuation, true);
                else projection.draw(shadowDepth, matrices, light, parameters, view.viewToSourceWorld(), camera,
                        Objects.requireNonNull(nativeSurface), cameraProjection, depth, attenuation, true,
                        receivedDecals, materials);
            }, volume));
        }
        releaseUnusedVolumeTargets(retainedVolumes);
        return Collections.unmodifiableMap(result);
    }

    private static ShadowDepthTarget createTarget(String label, TargetKey key) {
        return key.spot() ? new ProjectedShadowDepthTarget(label, key.size()) : new CubeDepthTarget(label, key.size());
    }

    private ShadowDepthTarget volumeTarget(long identity, TargetKey key) {
        var previous = volumeTargets.get(identity);
        if (previous != null && previous.size() == key.size() && (previous.viewCount() == 1) == key.spot()) return previous;
        var next = createTarget("LumenFX volume local-light shadow " + identity, key);
        volumeTargets.put(identity, next);
        if (previous != null) previous.close();
        return next;
    }

    private void releaseUnusedVolumeTargets(Set<Long> active) {
        var entries = volumeTargets.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!active.contains(entry.getKey())) {
                entry.getValue().close();
                entries.remove();
            }
        }
    }

    private static void addDepth(Set<GpuTexture> depths, @Nullable RenderTarget target) {
        if (target != null && target.getDepthTexture() != null) depths.add(target.getDepthTexture());
    }
    public void endFrame() { dynamic.endFrame(); }
    public void clearVolumeTargets() { releaseUnusedVolumeTargets(Set.of()); }
    public void expire(double time) { cache.expire(time); }
    public void remove(long identity) {
        cache.remove(identity);
        var volume = volumeTargets.remove(identity);
        if (volume != null) volume.close();
    }
    public void resetCache() { cache.close(); releaseUnusedVolumeTargets(Set.of()); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        targets.values().forEach(ShadowDepthTarget::close); targets.clear();
        maximumCubeSize = maximumProjectedSize = 0;
        releaseUnusedVolumeTargets(Set.of());
        cache.close();
        terrain.close(); dynamic.close(); projection.close();
    }
}
