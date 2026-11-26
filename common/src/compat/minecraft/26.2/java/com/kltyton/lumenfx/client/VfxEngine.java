package com.kltyton.lumenfx.client;

import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatRenderer;

import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.Pooling;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.api.client.physics.AuthoredPhysicsFieldComponent;
import com.kltyton.lumenfx.client.scene.physics.NiagaraPhysicsFieldWorlds;
import com.kltyton.lumenfx.client.scene.water.NiagaraWaterWorlds;
import com.kltyton.lumenfx.client.scene.physics.NiagaraChaosWorlds;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventDemand;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomAsset;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterScene;

import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureVolume;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.StreamingMips;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement.SceneProfile;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Bounds;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Registration;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.client.render.texture.virtual.MaterialVirtualTextureBindings;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureResources;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.TranslucentSortPolicy;
import com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraWorldDistanceField;
import com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraSceneGBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import com.kltyton.lumenfx.api.client.parameter.NiagaraParameterCollections;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSources;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionWorlds;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCollectionUniforms;
import com.kltyton.lumenfx.client.render.scene.material.OpaqueSceneColor;
import com.kltyton.lumenfx.effect.EmitterDefinition;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent;
import com.kltyton.lumenfx.api.client.NiagaraCacheCapture;
import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.EffectGpuRenderer;
import com.kltyton.lumenfx.client.render.EffectMeshBuilder;
import com.kltyton.lumenfx.client.render.EffectMesh;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.ribbon.NiagaraCpuRibbonCompute;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteInstanceCollector;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteWorldRenderer;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.client.render.depth.ParticleCustomDepthRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.ParticleCasters;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticlePointShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.CpuSource;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.GpuSource;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.client.render.scene.view.SourceViewLod;
import com.kltyton.lumenfx.client.concurrent.VfxFrameExecutor;
import com.kltyton.lumenfx.client.component.lifecycle.ComponentWorlds;
import com.kltyton.lumenfx.client.playback.EffectTeardown;
import com.kltyton.lumenfx.client.playback.PhotonEffectPlayback;
import com.kltyton.lumenfx.client.playback.WorldEffectPlayback;
import com.kltyton.lumenfx.client.playback.WorldEffectPlayback.FrameEffect;
import com.kltyton.lumenfx.client.playback.WorldEffectPlayback.Scope;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.kltyton.lumenfx.client.simulation.EffectInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NativeVectorMath;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.effect.RenderSettings;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.shader.PreparedShadingEnergyShaders;
import com.kltyton.lumenfx.client.resource.texture.subsurface.SubsurfaceProfiles;
import com.kltyton.lumenfx.client.resource.texture.specular.SpecularProfiles;
import com.kltyton.lumenfx.client.resource.texture.toon.ToonProfiles;
import com.kltyton.lumenfx.client.render.light.subsurface.LegacySubsurfaceScattering;
import com.kltyton.lumenfx.client.render.fog.FogIntegration;
import com.kltyton.lumenfx.client.resource.shader.PreparedFogIntegration;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraDrawShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraStatelessShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraGaussianSplatShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNaniteVertexMaterial;
import com.kltyton.lumenfx.client.resource.shader.PreparedNaniteMaterials;
import com.kltyton.lumenfx.client.render.niagara.stateless.NiagaraStatelessDataCache;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraSortShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraIdShaders;
import com.kltyton.lumenfx.client.render.texture.target.TextureMipPrograms;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuBatchDispatcher;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraDebugLineRenderer;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteSorting;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshSorting;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraCpuSpriteCompute;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraCpuSplatCollector;
import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatDraw;
import com.kltyton.lumenfx.client.render.scene.capture.NiagaraManagedSceneCaptures;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCameraFrame;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraTickClock;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraWorldSystemSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.component.NiagaraComponentPool;
import com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialObjects;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraScalabilityManager;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraScalabilityManager.CullingMode;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraScalabilityView;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraFxBudget;
import com.kltyton.lumenfx.client.render.niagara.visibility.NiagaraRenderVisibility;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportCallbacks;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalMeshWorlds;
import com.kltyton.lumenfx.effect.asset.mesh.StaticMeshStreaming;
import com.kltyton.lumenfx.client.resource.mesh.SkeletalMeshStreaming;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraCpuDebugLines;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWorlds;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelReadbacks;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.OfflineFont;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.audio.MinecraftNiagaraAudio;
import com.kltyton.lumenfx.client.audio.analysis.NiagaraAudioBusAnalysis;
import com.kltyton.lumenfx.api.client.NiagaraAudioBuses;
import com.kltyton.lumenfx.client.render.niagara.gpu.export.NiagaraGpuParticleExportReadbacks;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeInstance;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeRenderer;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

public final class VfxEngine {
    private static final int GPU_VIEWLESS_FRAME_LIMIT = 3;
    private static final int UNBOUNDED_CACHE_STEPS_PER_FRAME = 64;
    private static final int SCENE_VELOCITY_READER = 32;
    private static final int SUBSURFACE_HISTORY = 1024;
    private static final int OPAQUE_MATERIAL_LIGHTING = 2048;
    private static final int CAP_NANITE_PREVIOUS_VIEW = 1 << 16;
    private static final int CAP_UNITY_CAMERA_VELOCITY = 1 << 17;
    private static final int CAP_HAS_RIBBON = 1 << 18;
    private static final int CAP_NEEDS_MESH_VIEW = 1 << 19;
    private static final int CAP_READS_OPAQUE_SCENE_COLOR = 1 << 20;
    private static final int CAP_TRANSLUCENCY_VOLUME = 1 << 21;
    private static final int CAP_FORWARD_TRANSLUCENCY = 1 << 22;
    private static final int CAP_LIT_VOLUME_FOG = 1 << 23;
    private static final int CAP_GAMMA_BLENDING = 1 << 24;
    private static final int NATIVE_OPAQUE_SCENE = 7 | NiagaraGpuBatchDispatcher.READ_CAPTURED_GBUFFER
            | NiagaraGpuBatchDispatcher.READ_PARTIAL_DEPTH | NiagaraGpuBatchDispatcher.READ_CUSTOM_DEPTH
            | NiagaraGpuBatchDispatcher.READ_SCENE_DEPTH | NiagaraGpuBatchDispatcher.READ_SCREEN_SPACE_AO
            | NiagaraGpuBatchDispatcher.POST_OPAQUE_SCENE | SCENE_VELOCITY_READER
            | SUBSURFACE_HISTORY | OPAQUE_MATERIAL_LIGHTING;
    public static final VfxEngine INSTANCE = new VfxEngine();
    private record Pending(Scope scope, EffectHandle handle, Identifier effect, EffectDefinition definition,
                           long seed, long sequence) { }
    private record Resources(long generation, Map<Identifier, EffectDefinition> definitions,
                             Map<EffectDefinition, RenderSettings> particleViews,
                             Map<Identifier, EffectTexture> textures, PreparedFogIntegration fogIntegration,
                             Map<Identifier, PreparedNiagaraShaders> niagaraShaders, PreparedNiagaraDrawShaders niagaraDrawShaders,
                             PreparedNiagaraSortShaders niagaraSortShaders, PreparedNiagaraIdShaders niagaraIdShaders,
                             com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshSceneShaders meshSceneShaders,
                             PreparedNiagaraStatelessShaders statelessShaders,
                             PreparedNiagaraNaniteShaders naniteShaders,
                             @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders,
                             PreparedNaniteMaterials naniteMaterials,
                             Map<Identifier, RenderTargetTexture> textureTargets, @Nullable TextureMipPrograms textureMipPrograms,
                             VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                             SubsurfaceProfiles subsurfaceProfiles, SpecularProfiles specularProfiles, ToonProfiles toonProfiles) { }
    public record ReplacedTextures(Map<Identifier, EffectTexture> images, Map<Identifier, RenderTargetTexture> targets,
                                   @Nullable TextureMipPrograms mipPrograms) {
        public ReplacedTextures(Map<Identifier, EffectTexture> images, Map<Identifier, RenderTargetTexture> targets) {
            this(images, targets, null);
        }
    }
    public static final class PreparedReload implements AutoCloseable {
        private final Map<Identifier, EffectDefinition> definitions;
        private final NiagaraCollectionWorlds.Catalog collectionCatalog;
        private final Map<EffectDefinition, RenderSettings> particleViews;
        private final PreparedFogIntegration fogIntegration;
        private final Map<Identifier, PreparedNiagaraShaders> niagaraShaders;
        private final PreparedNiagaraDrawShaders niagaraDrawShaders;
        private final PreparedNiagaraSortShaders niagaraSortShaders;
        private final PreparedNiagaraIdShaders niagaraIdShaders;
        private final com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshSceneShaders meshSceneShaders;
        private final PreparedNiagaraStatelessShaders statelessShaders;
        private final PreparedNiagaraNaniteShaders naniteShaders;
        private final @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders;
        private final PreparedNaniteMaterials naniteMaterials;
        private com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms meshScenePrograms;
        private NaniteInstanceCollector naniteInstances;
        private NaniteWorldRenderer naniteRenderer;
        private FogIntegration volumeIntegrator;
        private NiagaraGpuPrograms niagaraPrograms;
        private boolean published;
        private boolean closed;
        private PreparedReload(Map<Identifier, EffectDefinition> definitions, Map<EffectDefinition, RenderSettings> particleViews,
                                PreparedFogIntegration fogIntegration, Map<Identifier, PreparedNiagaraShaders> niagaraShaders,
                                PreparedNiagaraDrawShaders niagaraDrawShaders, PreparedNiagaraSortShaders niagaraSortShaders,
                                PreparedNiagaraIdShaders niagaraIdShaders,
                                com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshSceneShaders meshSceneShaders,
                                PreparedNiagaraStatelessShaders statelessShaders, PreparedNiagaraNaniteShaders naniteShaders,
                                @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders,
                                PreparedNaniteMaterials naniteMaterials) {
            this.definitions = definitions;
            List<NiagaraCollectionBinding> collectionBindings = new ArrayList<>();
            for (EffectDefinition effect : definitions.values()) {
                var niagara = effect.niagara();
                if (niagara != null) collectionBindings.addAll(NiagaraCollectionSources.bindings(niagara));
            }
            collectionCatalog = NiagaraCollectionWorlds.prepare(collectionBindings);
            this.particleViews = particleViews;
            this.fogIntegration = fogIntegration;
            this.niagaraShaders = Map.copyOf(niagaraShaders);
            this.niagaraDrawShaders = niagaraDrawShaders;
            this.niagaraSortShaders = niagaraSortShaders;
            this.niagaraIdShaders = niagaraIdShaders;
            this.meshSceneShaders = meshSceneShaders;
            this.statelessShaders = statelessShaders;
            this.naniteShaders = naniteShaders;
            this.gaussianSplatShaders = gaussianSplatShaders;
            this.naniteMaterials = naniteMaterials;
            try {
                if (fogIntegration != null)
                    volumeIntegrator = new FogIntegration(fogIntegration);
                boolean needsCompute = !niagaraShaders.isEmpty() || meshSceneShaders != null || naniteShaders != null
                        || gaussianSplatShaders != null || definitions.values().stream().filter(effect -> effect.niagara() != null)
                                .flatMap(effect -> effect.niagara().emitters().stream())
                                .anyMatch(emitter -> emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation stateless
                                        && stateless.target().equals("GPUComputeSim"));
                boolean cpuSortCompute = niagaraDrawShaders != null && niagaraSortShaders != null && niagaraIdShaders != null
                        && ComputeRuntime.supportsStorageBufferKernels(RenderSystem.getDevice())
                        && (NiagaraGpuSpriteSorting.hasCpuSpriteWork(definitions.values())
                                || NiagaraGpuMeshSorting.hasCpuMeshWork(definitions.values()));
                if (niagaraDrawShaders != null && (needsCompute || cpuSortCompute))
                    niagaraPrograms = new NiagaraGpuPrograms(niagaraShaders, niagaraDrawShaders, niagaraSortShaders, niagaraIdShaders,
                            statelessShaders, gaussianSplatShaders);
                if (meshSceneShaders != null) meshScenePrograms = new com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms(meshSceneShaders);
                if (naniteShaders != null) {
                    var runtime = Objects.requireNonNull(niagaraPrograms).runtime();
                    naniteInstances = new NaniteInstanceCollector(runtime, naniteShaders);
                    naniteRenderer = new NaniteWorldRenderer(runtime, naniteShaders, this.naniteMaterials);
                }
            } catch (RuntimeException | Error error) {
                close();
                throw error;
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (!published && volumeIntegrator != null) volumeIntegrator.close();
            if (!published && meshScenePrograms != null) meshScenePrograms.close();
            if (!published && naniteRenderer != null) naniteRenderer.close();
            if (!published && naniteInstances != null) naniteInstances.close();
            if (!published && niagaraPrograms != null) niagaraPrograms.close();
            volumeIntegrator = null;
            niagaraPrograms = null;
            meshScenePrograms = null;
            naniteInstances = null;
            naniteRenderer = null;
        }
    }
    public record CameraProjection(boolean initialized, float horizontalScale, float nearClipCm, int width, int height,
                                   @Nullable Matrix4fc matrix) {
        public CameraProjection(boolean initialized, float horizontalScale, float nearClipCm, int width, int height) {
            this(initialized, horizontalScale, nearClipCm, width, height, null);
        }
    }
    private record FrameClock(long generation, ClientLevel level, double sampledTime, double time,
                              double engineTime, double realTime, float delta, float realDelta) {
        FrameInput input(Vec3 position, Quaternionf rotation, @Nullable CameraProjection cameraProjection) {
            return new FrameInput(generation, level, position, rotation.x(), rotation.y(), rotation.z(), rotation.w(),
                    time, engineTime, realTime, delta, realDelta, cameraProjection);
        }
    }
    private record HostSimulationFrame(FrameClock clock, Set<EffectInstance> simulated, long activationBoundary) { }
    private record EarlyGpuFrame(FrameInput input, long renderFrame, List<EffectInstance> submitted) { }

    public record FrameInput(long generation, ClientLevel level, Vec3 camera, float qx, float qy, float qz, float qw,
                             double time, double engineTime, double realTime, float delta, float realDelta,
                             @Nullable CameraProjection cameraProjection) {
        public FrameInput(long generation, ClientLevel level, Vec3 camera, float qx, float qy, float qz, float qw,
                          double time, double engineTime, double realTime, float delta, float realDelta) {
            this(generation, level, camera, qx, qy, qz, qw, time, engineTime, realTime, delta, realDelta, null);
        }

        public FrameInput(long generation, ClientLevel level, Vec3 camera, float qx, float qy, float qz, float qw,
                          double time, float delta) {
            this(generation, level, camera, qx, qy, qz, qw, time, time, time, delta, delta);
        }
    }

    private record PostOpaqueWork(EffectInstance instance, boolean finishHost) { }
    private record PostOpaqueFrame(List<PostOpaqueWork> work, NiagaraGpuView.Frame view, NiagaraGpuView.@Nullable Frame previousView,
                                   com.kltyton.lumenfx.client.render.mesh.@Nullable MeshView meshView, float ribbonLodDistanceFactor,
                                   boolean lowLatency) { }

    /** Owns the old and current simulation results until the native rendering phases have consumed them. */
    public static final class PreparedFrame implements AutoCloseable {
        private final VfxEngine owner;
        private final FrameInput input;
        private final EffectFrame frame;
        private final List<NaniteInstanceCollector.Prepared> naniteInstances;
        private List<NaniteInstanceCollector.Prepared> lateNaniteInstances = List.of();
        private final NiagaraSceneInputs sceneInputs;
        private @Nullable NiagaraSceneInputs preparedSceneInputs;
        private final @Nullable PostOpaqueFrame postOpaque;
        private boolean niagaraVisibilitySubmitted;
        private @Nullable EffectFrame lateTranslucency;
        private final NiagaraGpuView.@Nullable Frame gpuView;
        private final long gpuViewCut;
        private final long asyncTraceFrame;
        private final long naniteFrameSequence;
        private final List<EffectInstance> instances;
        private final List<CpuSource> cpuShadowCasters;
        private final List<StaticMeshComponentDraw> componentShadowCasters;
        private final List<GpuSource> gpuShadowCasters;
        private final List<NiagaraGaussianSplatDraw> cpuSplatDraws;
        private final boolean paused;
        private final @Nullable NiagaraStatelessDataCache statelessData;
        private final long statelessFrame;
        private final int materialTextureFrame;
        private boolean closed;

        private PreparedFrame(VfxEngine owner, FrameInput input, EffectFrame frame, List<EffectInstance> instances,
                              @Nullable NiagaraStatelessDataCache statelessData, long statelessFrame,
                              List<CpuSource> cpuShadowCasters, List<StaticMeshComponentDraw> componentShadowCasters,
                              @Nullable List<GpuSource> gpuShadowCasters,
                              List<NiagaraGaussianSplatDraw> cpuSplatDraws,
                              NiagaraSceneInputs sceneInputs, @Nullable PostOpaqueFrame postOpaque,
                              NiagaraGpuView.@Nullable Frame gpuView, List<NaniteInstanceCollector.Prepared> naniteInstances,
                              long naniteFrameSequence) {
            this.owner = owner; this.input = input; this.frame = frame;
            this.sceneInputs = sceneInputs;
            this.postOpaque = postOpaque;
            this.gpuView = gpuView;
            this.naniteInstances = new ArrayList<>(naniteInstances);
            this.naniteFrameSequence = naniteFrameSequence;
            gpuViewCut = WorldHdrCapture.INSTANCE.cameraCutVersion();
            asyncTraceFrame = WorldHdrCapture.INSTANCE.renderFrameSequence();
            this.instances = List.copyOf(instances);
            this.cpuShadowCasters = List.copyOf(cpuShadowCasters);
            this.componentShadowCasters = List.copyOf(componentShadowCasters);
            this.gpuShadowCasters = gpuShadowCasters == null ? List.of() : List.copyOf(gpuShadowCasters);
            this.cpuSplatDraws = List.copyOf(cpuSplatDraws);
            paused = Minecraft.getInstance().isPaused();
            materialTextureFrame = paused ? owner.materialTextureFrame : ++owner.materialTextureFrame;
            this.statelessData = statelessData;
            this.statelessFrame = statelessFrame;
        }

        public boolean needsSceneColor() { return frame.needsSceneColor() || lateTranslucency != null && lateTranslucency.needsSceneColor(); }

        public List<NaniteInstanceCollector.Prepared> naniteInstances() {
            if (closed) throw new IllegalStateException("Nanite instances belong to a finished view");
            var result = new ArrayList<>(naniteInstances);
            result.addAll(lateNaniteInstances);
            return List.copyOf(result);
        }

        public void finish() {
            if (closed) return;
            if (postOpaque != null && lateTranslucency == null)
                throw new IllegalStateException("Cannot finish a Niagara view before its PostOpaque simulation");
            closed = true;
            try {
                instances.forEach(instance -> instance.finishRenderFrame(paused));
                NiagaraManagedSceneCaptures.INSTANCE.renderPending(input.level());
                if (frame.motionView() != null) owner.particleMotionView.commit(frame.motionView(), paused);
                if (gpuView != null && !paused) {
                    owner.previousGpuView = gpuView;
                    owner.gpuViewLevel = input.level();
                    owner.gpuViewCutVersion = gpuViewCut;
                }
            } finally {
                naniteInstances.clear();
                lateNaniteInstances = List.of();
                try { retirePreparedInstances(instances, false); }
                finally { if (statelessData != null) statelessData.finishFrame(statelessFrame); }
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                if (owner.niagaraPrograms != null)
                    owner.niagaraPrograms.abortAsyncTraceRenderFrame(input.level(), asyncTraceFrame);
                if (frame.motionView() != null) owner.particleMotionView.reset();
                if (gpuView != null) owner.previousGpuView = null;
            } finally {
                naniteInstances.clear();
                lateNaniteInstances = List.of();
                try { retirePreparedInstances(instances, true); }
                finally { if (statelessData != null) statelessData.abortFrame(statelessFrame); }
            }
        }

    }

    private static void retirePreparedInstances(List<EffectInstance> instances, boolean abandoned) {
        Throwable failure = null;
        for (var instance : instances) {
            if (abandoned) try { instance.abandonPreparedGpuFrame(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            try { instance.releasePreviousGpuRenderData(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            try { instance.retirePreparedFrame(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
    private NiagaraMaterialObjects dynamicMaterialObjects = new NiagaraMaterialObjects();
    private volatile Resources resources = new Resources(0, Map.of(), Map.of(), Map.of(), null, Map.of(),
            null, null, null, null, null, null, null, PreparedNaniteMaterials.EMPTY, Map.of(), null, new VirtualTextureResources(Map.of()), null,
            SubsurfaceProfiles.EMPTY, SpecularProfiles.EMPTY, ToonProfiles.EMPTY);
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private StaticMeshStreaming meshStreaming = new StaticMeshStreaming();
    private SkeletalMeshStreaming skeletalMeshStreaming = new SkeletalMeshStreaming();
    private final ConcurrentLinkedQueue<Long> retiredPointLights = new ConcurrentLinkedQueue<>();
    private final WorldEffectPlayback playback = new WorldEffectPlayback();
    private final EffectGpuRenderer renderer = new EffectGpuRenderer();
    private final ParticlePointShadowRenderer particleShadows = new ParticlePointShadowRenderer();
    private final ParticleCustomDepthRenderer particleCustomDepth = new ParticleCustomDepthRenderer();
    private int materialTextureFrame;
    private long naniteFrameSequence;
    private final NiagaraDebugLineRenderer debugLineRenderer = new NiagaraDebugLineRenderer();
    private @Nullable NiagaraGaussianSplatRenderer gaussianSplatRenderer;
    private final NiagaraVolumeRenderer volumeRenderer = new NiagaraVolumeRenderer();
    private final VfxFrameExecutor workers = VfxFrameExecutor.INSTANCE;
    private final NiagaraWorldDistanceField worldDistanceField = new NiagaraWorldDistanceField();
    private @Nullable ComputeVolume neutralAerialPerspective;
    private final NiagaraSceneGBuffer niagaraSceneGBuffer = new NiagaraSceneGBuffer();
    private final OpaqueSceneColor opaqueSceneColor = new OpaqueSceneColor();
    private final EffectMeshBuilder[] builders;
    private final com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView particleMotionView =
            new com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView();
    private final List<EffectInstance> frameInstances = new ArrayList<>();
    private final NiagaraWorldSystemSimulation worldSystemSimulation = new NiagaraWorldSystemSimulation();
    private final NiagaraScalabilityManager niagaraScalability = new NiagaraScalabilityManager();
    private final NiagaraComponentPool componentPool = new NiagaraComponentPool();
    private final NiagaraRenderVisibility niagaraRenderVisibility = new NiagaraRenderVisibility();
    private final NiagaraFxBudget niagaraFxBudget = new NiagaraFxBudget();
    private final List<EffectInstance.EmitterFrame> frameEmitters = new ArrayList<>();
    private final List<EffectMesh> frameMeshes = new ArrayList<>();
    private final List<com.kltyton.lumenfx.effect.render.light.ParticleLight> frameLights = new ArrayList<>();
    private final List<com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight> framePointLights = new ArrayList<>();
    private final List<SceneDirectionalLight> frameDirectionalLights = new ArrayList<>();
    private final List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> frameDecals = new ArrayList<>();
    private final List<NiagaraVolumeInstance> frameVolumes = new ArrayList<>();
    private final List<NiagaraGpuSpriteCollector.Task> frameGpuTasks = new ArrayList<>();
    private final List<com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector.Task> frameGpuMeshTasks = new ArrayList<>();
    private final List<com.kltyton.lumenfx.client.render.mesh.CpuMeshSceneInput> frameCpuMeshSources = new ArrayList<>();
    private final List<NiagaraCpuRibbonCompute.Source> frameCpuRibbonSources = new ArrayList<>();
    private final List<NiagaraCpuSpriteCompute.Source> frameCpuSpriteSources = new ArrayList<>();
    private final List<NiagaraCpuSplatCollector.Source> frameCpuSplatSources = new ArrayList<>();
    private final List<EffectInstance> frameLate = new ArrayList<>();
    private final List<EffectInstance> frameOrdinary = new ArrayList<>();
    private final List<EffectInstance> frameSeeking = new ArrayList<>();
    private final List<EffectInstance> frameStepping = new ArrayList<>();
    private final List<EffectInstance> frameDispatchBatch = new ArrayList<>();
    private final List<StaticMeshComponentDraw> frameComponentMeshes = new ArrayList<>();
    private final List<StaticMeshComponentDraw> frameComponentShadowCasters = new ArrayList<>();
    private final List<CpuSource> frameCpuShadowCasters = new ArrayList<>();
    private final List<GpuSource> frameGpuShadowCasters = new ArrayList<>();
    private final List<NiagaraGpuRibbonFrame> frameGpuRibbons = new ArrayList<>();
    private final List<NaniteInstanceCollector.Prepared> frameNanitePrepared = new ArrayList<>();
    private final Set<EffectInstance> frameDeferredGpu = new java.util.LinkedHashSet<>();
    private final Map<EffectInstance, NiagaraCacheCapture> frameManualCaptures = new LinkedHashMap<>();
    private final List<NiagaraGpuSpriteCollector.Task> framePostOpaqueSpriteTasks = new ArrayList<>();
    private final List<NiagaraGpuMeshCollector.Task> framePostOpaqueMeshTasks = new ArrayList<>();
    private final List<NaniteInstanceCollector.GpuInput> frameNaniteTasks = new ArrayList<>();
    private final List<NiagaraGaussianSplatDraw> frameSplatDraws = new ArrayList<>();
    private final List<NiagaraGpuDebugLines.Frame> frameDebugLines = new ArrayList<>();
    private final List<NiagaraCpuDebugLines.Snapshot> frameCpuDebugLines = new ArrayList<>();
    private final List<Object> frameSplatKeys = new ArrayList<>();
    private final Matrix4f scratchProjection = new Matrix4f();
    private final Quaternionf scratchOrientation = new Quaternionf();
    private long capabilityGeneration = Long.MIN_VALUE;
    private @Nullable NiagaraPlatformEnvironment capabilityPlatform;
    private final IdentityHashMap<EffectDefinition, Integer> definitionCapabilities = new IdentityHashMap<>();
    private @Nullable Scope activeScope;
    private final AtomicLong pendingSequence = new AtomicLong();
    private long sceneRegistrationBoundary = Long.MAX_VALUE;
    private ClientLevel clockLevel;
    private double simulationTime;
    private double gameClockStart;
    private long realClockStart;
    private long realClockLast;
    private @Nullable HostSimulationFrame hostSimulationFrame;
    private @Nullable FrameInput extractedHostInput;
    private @Nullable EarlyGpuFrame earlyGpuFrame;
    private boolean renderResourcesReleased;
    private NiagaraGpuPrograms niagaraPrograms;
    private NaniteInstanceCollector naniteInstances;
    private NaniteWorldRenderer naniteRenderer;
    private com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms meshScenePrograms;
    private NiagaraGpuSpriteCollector gpuSpriteCollector;
    private NiagaraGpuMeshCollector gpuMeshCollector;
    private NiagaraGpuSpriteCollector postOpaqueSprites;
    private NiagaraGpuMeshCollector postOpaqueMeshes;
    private com.kltyton.lumenfx.client.render.mesh.CpuMeshSceneCollector cpuMeshCollector;
    private @Nullable NiagaraCpuRibbonCompute cpuRibbonCompute;
    private @Nullable NiagaraCpuSpriteCompute cpuSpriteCompute;
    private @Nullable NiagaraCpuSplatCollector cpuSplatCollector;
    private NiagaraGpuView.Frame previousGpuView;
    private ClientLevel gpuViewLevel;
    private long gpuViewCutVersion = Long.MIN_VALUE;
    private int queuedGpuFrames;
    private volatile @Nullable BiConsumer<ClientLevel, UnityParticleCollisionEvent.ForceImpact> unityColliderForceConsumer;

    private VfxEngine() {
        builders = new EffectMeshBuilder[workers.maximumWorkers()];
        for (int i = 0; i < builders.length; i++) builders[i] = new EffectMeshBuilder(i);
    }

    public boolean hasEffect(Identifier id) {
        if (id == null) return false;
        return resources.definitions().containsKey(id) || FXHelper.contains(id);
    }

    public EffectHandle spawn(ClientLevel level, Identifier id, Vec3 position, long seed) {
        return createEffect(level, id, position, seed, false, Pooling.None);
    }

    public EffectHandle createNiagaraComponent(ClientLevel level, Identifier id, Vec3 position, long seed) {
        return createNiagaraComponent(level, id, position, seed, Pooling.None);
    }

    public EffectHandle createNiagaraComponent(ClientLevel level, Identifier id, Vec3 position, long seed, Pooling pooling) {
        return createEffect(level, id, position, seed, true, pooling);
    }

    private EffectHandle createEffect(ClientLevel level, Identifier id, Vec3 position, long seed, boolean component, Pooling pooling) {
        Objects.requireNonNull(level, "A VFX instance needs a client world");
        Resources snapshot = resources;
        EffectDefinition definition = snapshot.definitions().get(id);
        if (definition == null) {
            if (!component && FXHelper.contains(id)) return PhotonEffectPlayback.spawn(playback, level, id, position, seed);
            throw new IllegalArgumentException("Unknown VFX effect " + id);
        }
        if (component && definition.niagara() == null) throw new IllegalArgumentException("VFX effect is not a Niagara system: " + id);
        var niagara = definition.niagara();
        var control = component ? new NiagaraComponentControl(Objects.requireNonNull(niagara).component().autoActivate(), pooling) : null;
        EffectHandle handle = new EffectHandle(position, control,
                niagara == null ? PrimitiveCustomData.EMPTY : niagara.component().customPrimitiveData());
        handle.setEffectId(id);
        if (!definition.receivesDecals()) handle.setReceivesDecals(false);
        var request = new Pending(Scope.capture(level, snapshot.generation()), handle, id, definition, seed,
                pendingSequence.incrementAndGet());
        pending.add(request);
        // A concurrent caller may have read the old resource snapshot before the reload retired its queue entries.
        if (snapshot != resources && pending.remove(request)) handle.invalidate();
        return handle;
    }

    public void setNiagaraScalabilityMode(CullingMode mode) {
        requireClientThread();
        if (niagaraScalability.setMode(mode)) niagaraFxBudget.clear();
    }

    public Channel dataChannel(String sourceAssetPath) {
        String path = SourceNames.canonical(sourceAssetPath);
        Channel result = null;
        for (var definition : resources.definitions().values()) {
            var niagara = definition.niagara();
            if (niagara == null) continue;
            var candidate = niagara.dataChannels().get(path);
            if (candidate == null) continue;
            if (result != null && !result.equals(candidate))
                throw new IllegalArgumentException("Conflicting Niagara data channel schemas for " + sourceAssetPath);
            result = candidate;
        }
        if (result == null) throw new IllegalArgumentException("Unknown Niagara data channel " + sourceAssetPath);
        return result;
    }

    public void reload(Map<Identifier, EffectDefinition> values) {
        try (PreparedReload prepared = prepareReload(values)) {
            EffectTextures.releaseReplaced(commitReload(prepared, resources.textures()));
        }
    }

    public PreparedReload prepareReload(Map<Identifier, EffectDefinition> values) {
        Resources snapshot = resources;
        return prepareReload(values, snapshot.fogIntegration(), snapshot.niagaraShaders(),
                snapshot.niagaraDrawShaders(), snapshot.niagaraSortShaders(), snapshot.niagaraIdShaders(),
                snapshot.meshSceneShaders(), snapshot.statelessShaders(), snapshot.naniteShaders(),
                snapshot.gaussianSplatShaders(), snapshot.naniteMaterials());
    }

    public PreparedReload prepareReload(Map<Identifier, EffectDefinition> values, PreparedFogIntegration fogIntegration,
                                         Map<Identifier, PreparedNiagaraShaders> niagaraShaders,
                                         PreparedNiagaraDrawShaders niagaraDrawShaders, PreparedNiagaraSortShaders niagaraSortShaders,
                                         PreparedNiagaraIdShaders niagaraIdShaders,
                                         com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshSceneShaders meshSceneShaders,
                                         PreparedNiagaraStatelessShaders statelessShaders, PreparedNiagaraNaniteShaders naniteShaders,
                                         @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders,
                                         PreparedNaniteMaterials naniteMaterials) {
        requireClientThread();
        Map<Identifier, EffectDefinition> definitions = Map.copyOf(values);
        for (var effect : definitions.values()) if (effect.niagara() != null) {
            for (var channel : effect.niagara().dataChannels().values()) {
                var islands = channel.islands();
                if (islands != null) {
                    for (Identifier handler : islands.systems()) {
                        var definition = definitions.get(handler);
                        if (definition == null || definition.niagara() == null)
                            throw new IllegalArgumentException("Niagara data channel " + channel.path() + " requires handler system " + handler);
                    }
                }
                var burst = channel.gameplayBurst();
                if (burst != null) {
                    for (Identifier handler : burst.systems()) {
                        var definition = definitions.get(handler);
                        if (definition == null || definition.niagara() == null)
                            throw new IllegalArgumentException("Niagara data channel " + channel.path() + " requires handler system " + handler);
                    }
                }
            }
        }
        Map<Identifier, PreparedNiagaraShaders> requiredNiagara = new LinkedHashMap<>();
        boolean needsStateless = false, needsGpuStateless = false;
        for (EffectDefinition effect : definitions.values()) {
            if (effect.niagara() == null) continue;
            for (var emitter : effect.niagara().emitters()) {
                if (emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation stateless && stateless.target().equals("GPUComputeSim")) {
                    needsGpuStateless = true;
                    needsStateless |= (stateless.metadata().get("allowed_features").getAsInt() & 1) != 0;
                }
                if (emitter.simulation() instanceof NiagaraDefinition.GpuSimulation gpu) {
                    Identifier key = Identifier.fromNamespaceAndPath(gpu.hlslSource().getNamespace(), gpu.shaderPipeline());
                    PreparedNiagaraShaders shaders = niagaraShaders.get(key);
                    if (shaders == null) throw new IllegalArgumentException("Missing Niagara GPU shader bundle " + key);
                    shaders.requireSource(gpu.metadata());
                    requiredNiagara.put(key, shaders);
                }
            }
        }
        boolean needsMeshScene = definitions.values().stream().flatMap(effect -> effect.emitters().stream())
                .anyMatch(emitter -> emitter.meshSettings() != null && emitter.meshSettings().sceneTransforms()
                        != com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.SceneTransforms.LEGACY);
        if (needsMeshScene && meshSceneShaders == null)
            throw new IllegalArgumentException("Missing Niagara mesh GPUScene shader bundle");
        boolean needsNanite = definitions.values().stream().flatMap(effect -> effect.emitters().stream())
                .anyMatch(emitter -> emitter.meshSettings() != null && emitter.meshSettings().nanite() != null);
        if (needsNanite && naniteShaders == null)
            throw new IllegalArgumentException("Missing Niagara Nanite shader bundle");
        boolean needsSplat = PreparedNiagaraGaussianSplatShaders.isRequired(definitions.values());
        if (needsSplat && gaussianSplatShaders == null)
            throw new IllegalArgumentException("Missing Niagara Gaussian Splat shader bundle");
        boolean needsGpuUtilities = !requiredNiagara.isEmpty() || needsMeshScene || needsGpuStateless || needsNanite || needsSplat;
        if (needsStateless && statelessShaders == null)
            throw new IllegalArgumentException("Missing Niagara analytic shader bundle");
        if (needsGpuUtilities && niagaraDrawShaders == null)
            throw new IllegalArgumentException("Missing Niagara GPU indirect-draw shader bundle");
        if (needsGpuUtilities && niagaraSortShaders == null)
            throw new IllegalArgumentException("Missing Niagara GPU sorting shader bundle");
        if (needsGpuUtilities && niagaraIdShaders == null)
            throw new IllegalArgumentException("Missing Niagara GPU ID shader bundle");
        if (needsMeshScene && !meshSceneShaders.engineHead().equals(niagaraDrawShaders.engineHead()))
            throw new IllegalArgumentException("Niagara mesh and simulation shader engine versions differ");
        if (needsNanite && !naniteShaders.engineHead().equals(niagaraDrawShaders.engineHead()))
            throw new IllegalArgumentException("Niagara Nanite and simulation shader engine versions differ");
        if (needsSplat && !gaussianSplatShaders.engineHead().equals(niagaraDrawShaders.engineHead()))
            throw new IllegalArgumentException("Niagara Gaussian Splat and simulation shader engine versions differ");
        if (definitions.values().stream().anyMatch(value -> value.niagara() != null)) NativeVectorMath.initialize();
        var viewPlan = resolveParticleViews(definitions);
        if (viewPlan.needsVolume() && fogIntegration == null)
            throw new IllegalArgumentException("Native volume integration requires its offline shader bundle");
        // CPU-simulated ribbons may request compute later, after this resource generation is installed.
        return new PreparedReload(definitions, viewPlan.particleViews(), fogIntegration,
                requiredNiagara, niagaraDrawShaders, niagaraSortShaders, niagaraIdShaders,
                needsMeshScene ? meshSceneShaders : null, needsStateless ? statelessShaders : null,
                needsNanite ? naniteShaders : null, needsSplat ? gaussianSplatShaders : null, naniteMaterials);
    }

    public record ParticleViewPlan(Map<EffectDefinition, RenderSettings> particleViews, boolean needsVolume) { }

    public static ParticleViewPlan resolveParticleViews(Map<Identifier, EffectDefinition> definitions) {
        var particleViews = new IdentityHashMap<EffectDefinition, RenderSettings>();
        boolean needsVolume = false;
        for (var entry : definitions.entrySet()) {
            var effect = entry.getValue();
            var effectView = RenderSettings.STANDARD;
            var requests = Stream.concat(Stream.of(effect.renderSettings()),
                    effect.materials().values().stream().map(MaterialDefinition::renderSettings)).iterator();
            while (requests.hasNext()) {
                var requested = requests.next();
                if (!requested.hdr() && requested.indirectLighting().equals(RenderSettings.STANDARD.indirectLighting()))
                    continue;
                needsVolume |= requested.fog() instanceof VolumetricFog;
                effectView = mergeViewInputs(effectView, requested);
            }
            if (effectView.hdr()) particleViews.put(effect, effectView);
        }
        return new ParticleViewPlan(Collections.unmodifiableMap(particleViews), needsVolume);
    }

    /** Publishes definitions, render profile and GPU textures through one immutable snapshot. */
    public Map<Identifier, EffectTexture> commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures) {
        return commitReload(prepared, textures, resources.textureTargets()).images();
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets) {
        return commitReload(prepared, textures, targets, resources.textureMipPrograms());
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeRenderer.materials());
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeMaterials, resources.virtualTextures());
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                                         VirtualTextureResources virtualTextures) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeMaterials, virtualTextures,
                resources.shadingEnergyShaders(), resources.subsurfaceProfiles());
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                                         VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                                         SubsurfaceProfiles subsurfaceProfiles) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeMaterials, virtualTextures,
                shadingEnergyShaders, subsurfaceProfiles, resources.specularProfiles());
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                                         VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                                         SubsurfaceProfiles subsurfaceProfiles, SpecularProfiles specularProfiles) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeMaterials, virtualTextures,
                shadingEnergyShaders, subsurfaceProfiles, specularProfiles, meshStreaming);
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                                         VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                                         SubsurfaceProfiles subsurfaceProfiles, SpecularProfiles specularProfiles,
                                         StaticMeshStreaming streaming) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeMaterials, virtualTextures,
                shadingEnergyShaders, subsurfaceProfiles, specularProfiles, streaming, skeletalMeshStreaming);
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                                         VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                                         SubsurfaceProfiles subsurfaceProfiles, SpecularProfiles specularProfiles,
                                         StaticMeshStreaming streaming, SkeletalMeshStreaming skeletalStreaming) {
        return commitReload(prepared, textures, targets, mipPrograms, volumeMaterials, virtualTextures,
                shadingEnergyShaders, subsurfaceProfiles, specularProfiles, resources.toonProfiles(), streaming, skeletalStreaming);
    }

    public ReplacedTextures commitReload(PreparedReload prepared, Map<Identifier, EffectTexture> textures,
                                         Map<Identifier, RenderTargetTexture> targets, @Nullable TextureMipPrograms mipPrograms,
                                         Map<Identifier, PreparedVolumeMaterial> volumeMaterials,
                                         VirtualTextureResources virtualTextures, @Nullable PreparedShadingEnergyShaders shadingEnergyShaders,
                                         SubsurfaceProfiles subsurfaceProfiles, SpecularProfiles specularProfiles, ToonProfiles toonProfiles,
                                         StaticMeshStreaming streaming, SkeletalMeshStreaming skeletalStreaming) {
        requireClientThread();
        if (!java.util.Collections.disjoint(textures.keySet(), targets.keySet()))
            throw new IllegalArgumentException("An effect texture identifier cannot name both an image and a render target");
        if (prepared.published || prepared.closed)
            throw new IllegalStateException("The resource candidate has already been consumed");
        Resources previous = resources;
        SkeletalMeshStreaming previousSkeletalMeshStreaming = skeletalMeshStreaming;
        Resources next = new Resources(previous.generation() + 1, prepared.definitions, prepared.particleViews,
                Map.copyOf(textures), prepared.fogIntegration, prepared.niagaraShaders,
                prepared.niagaraDrawShaders, prepared.niagaraSortShaders, prepared.niagaraIdShaders, prepared.meshSceneShaders,
                prepared.statelessShaders, prepared.naniteShaders, prepared.gaussianSplatShaders, prepared.naniteMaterials,
                Map.copyOf(targets), mipPrograms, virtualTextures, shadingEnergyShaders,
                subsurfaceProfiles, specularProfiles, toonProfiles);
        try (var teardown = new EffectTeardown()) {
            teardown.run(playback::discardPhotonEffects);
            teardown.run(this::releaseRenderResources);
        }
        volumeRenderer.materials(volumeMaterials);
        installRenderResources(prepared);
        NiagaraCollectionWorlds.publish(prepared.collectionCatalog);
        NiagaraSkeletalMeshWorlds.clear();
        dynamicMaterialObjects = new NiagaraMaterialObjects();
        meshStreaming = streaming;
        skeletalMeshStreaming = skeletalStreaming;
        previousSkeletalMeshStreaming.clearResidency();
        renderResourcesReleased = false;
        resources = next;
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onResourceReload();
        pending.forEach(request -> {
            if (request.scope().resourceGeneration() != next.generation() && pending.remove(request)) {
                request.handle().invalidate();
            }
        });
        return new ReplacedTextures(previous.textures(), previous.textureTargets(), previous.textureMipPrograms() == mipPrograms ? null : previous.textureMipPrograms());
    }

    private void installRenderResources(PreparedReload prepared) {
        WorldHdrCapture.INSTANCE.configureFogIntegration(prepared.volumeIntegrator);
        niagaraPrograms = prepared.niagaraPrograms;
        naniteInstances = prepared.naniteInstances;
        naniteRenderer = prepared.naniteRenderer;
        meshScenePrograms = prepared.meshScenePrograms;
        prepared.published = true;
        prepared.volumeIntegrator = null;
        prepared.niagaraPrograms = null;
        prepared.naniteInstances = null;
        prepared.meshScenePrograms = null;
    }

    public void ensureRenderResources() {
        requireClientThread();
        if (!renderResourcesReleased) return;
        Resources snapshot = resources;
        try (PreparedReload prepared = new PreparedReload(snapshot.definitions(), snapshot.particleViews(),
                snapshot.fogIntegration(), snapshot.niagaraShaders(),
                snapshot.niagaraDrawShaders(), snapshot.niagaraSortShaders(), snapshot.niagaraIdShaders(),
                snapshot.meshSceneShaders(), snapshot.statelessShaders(), snapshot.naniteShaders(),
                snapshot.gaussianSplatShaders(), snapshot.naniteMaterials())) {
            installRenderResources(prepared);
            renderResourcesReleased = false;
        }
    }

    public void releaseRenderResources() {
        requireClientThread();
        try (var teardown = new EffectTeardown()) {
            releaseRenderResources(teardown);
        }
    }

    private void releaseRenderResources(EffectTeardown teardown) {
        clearHostSimulationFrame();
        teardown.run(componentPool::clear);
        NiagaraGpuParticleExportReadbacks.clear();
        NiagaraGpuCacheReadbacks.clear(null);
        volumeRenderer.close();
        debugLineRenderer.clear();
        if (gaussianSplatRenderer != null) {
            gaussianSplatRenderer.close();
            gaussianSplatRenderer = null;
        }
        NiagaraParticleExportCallbacks.clear();
        NiagaraDataChannelWorlds.clear();
        NiagaraGpuDataChannelWorlds.clear();
        NiagaraGpuDataChannelReadbacks.clear(null);
        MinecraftNiagaraAudio.INSTANCE.clearWaves();
        NiagaraAudioBusAnalysis.clear();
        NiagaraAudioBuses.clear();
        worldSystemSimulation.clear();
        niagaraScalability.clear();
        niagaraRenderVisibility.close();
        niagaraFxBudget.clear();
        worldDistanceField.close();
        if (neutralAerialPerspective != null) {
            neutralAerialPerspective.close();
            neutralAerialPerspective = null;
        }
        niagaraSceneGBuffer.close();
        opaqueSceneColor.close();
        particleMotionView.reset();
        renderResourcesReleased = true;
        previousGpuView = null; gpuViewLevel = null; gpuViewCutVersion = Long.MIN_VALUE;
        queuedGpuFrames = 0;
        teardown.run(playback::discardFrameEffects);
        meshStreaming.clearResidency();
        skeletalMeshStreaming.clearResidency();
        resources.virtualTextures().clearResidency();
        NiagaraManagedSceneCaptures.INSTANCE.close();
        if (gpuSpriteCollector != null) { gpuSpriteCollector.close(); gpuSpriteCollector = null; }
        if (gpuMeshCollector != null) { gpuMeshCollector.close(); gpuMeshCollector = null; }
        if (postOpaqueSprites != null) { postOpaqueSprites.close(); postOpaqueSprites = null; }
        if (postOpaqueMeshes != null) { postOpaqueMeshes.close(); postOpaqueMeshes = null; }
        if (cpuMeshCollector != null) { cpuMeshCollector.close(); cpuMeshCollector = null; }
        if (cpuRibbonCompute != null) { cpuRibbonCompute.close(); cpuRibbonCompute = null; }
        if (cpuSpriteCompute != null) { cpuSpriteCompute.close(); cpuSpriteCompute = null; }
        if (cpuSplatCollector != null) { cpuSplatCollector.close(); cpuSplatCollector = null; }
        frameCpuMeshSources.clear();
        frameCpuRibbonSources.clear();
        frameCpuSpriteSources.clear();
        frameCpuSplatSources.clear();
        renderer.releaseBuffers();
        particleShadows.close();
        particleCustomDepth.close();
        MaterialCollectionUniforms.INSTANCE.close();
        retiredPointLights.clear();
        WorldHdrCapture.INSTANCE.release();
        if (naniteRenderer != null) { naniteRenderer.close(); naniteRenderer = null; }
        if (naniteInstances != null) { naniteInstances.close(); naniteInstances = null; }
        if (niagaraPrograms != null) { niagaraPrograms.close(); niagaraPrograms = null; }
        if (meshScenePrograms != null) { meshScenePrograms.close(); meshScenePrograms = null; }
        MaterialTextureBindings.clearSharedSamplers();
    }

    public com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms meshScenePrograms() {
        return java.util.Objects.requireNonNull(meshScenePrograms, "Mesh scene programs were not prepared for this resource generation");
    }

    public boolean hasActiveLightRenderers() {
        return hasActiveSceneRenderers(1);
    }

    public boolean hasActiveSceneRenderers() { return hasActiveSceneRenderers(3); }
    public boolean hasActiveDecalRenderers() { return hasActiveSceneRenderers(2); }
    public boolean hasActiveSceneStages() { return hasActiveSceneRenderers(NATIVE_OPAQUE_SCENE); }

    public boolean hasActiveGBufferReaders() { return hasActiveSceneRenderers(NiagaraGpuBatchDispatcher.READ_CAPTURED_GBUFFER); }
    public boolean hasActiveLitOpaqueMaterials() { return hasActiveSceneRenderers(OPAQUE_MATERIAL_LIGHTING); }
    public boolean hasActiveNiagaraVelocityReaders() { return hasActiveSceneRenderers(SCENE_VELOCITY_READER); }
    public boolean hasActiveSubsurfaceHistory() {
        return LegacySubsurfaceScattering.usesBurleyHistory(NiagaraPlatforms.snapshot())
                && hasActiveSceneRenderers(SUBSURFACE_HISTORY);
    }

    private boolean hasActiveSceneRenderers(int mask) {
        var level = Minecraft.getInstance().level;
        long generation = resources.generation();
        int particleGeneration = VanillaParticleHost.INSTANCE.generation();
        if (playback.hasFrameEffects() && activeScope != null && activeScope.current(level, generation, particleGeneration)) {
            for (var value : playback.activeEffects()) {
                if (value instanceof FrameEffect effect && effect.handle().isAlive()
                        && (hasSceneRenderers(effect.instance().definition(), effect.handle(), mask)
                            || (mask & NiagaraGpuBatchDispatcher.POST_OPAQUE_SCENE) != 0
                                && effect.instance().requiresCurrentGpuDataChannel())) return true;
            }
        }
        if (!pending.isEmpty()) {
            for (var value : pending) {
                if (value.sequence() <= sceneRegistrationBoundary
                        && value.scope().current(level, generation, particleGeneration) && value.handle().isAlive()
                        && hasSceneRenderers(value.definition(), value.handle(), mask)) return true;
            }
        }
        return false;
    }

    private int definitionCapabilities(EffectDefinition definition) {
        var platform = NiagaraPlatforms.snapshot();
        long generation = resources.generation();
        if (generation != capabilityGeneration || platform != capabilityPlatform) {
            definitionCapabilities.clear();
            capabilityGeneration = generation;
            capabilityPlatform = platform;
        }
        Integer cached = definitionCapabilities.get(definition);
        if (cached != null) return cached;
        int caps = computeDefinitionCapabilities(definition, platform);
        definitionCapabilities.put(definition, caps);
        return caps;
    }

    private int computeDefinitionCapabilities(EffectDefinition definition, NiagaraPlatformEnvironment platform) {
        int caps = 0;
        for (var binding : definition.unityRenderers().values()) {
            if (binding.lights() != null) caps |= 1;
            var billboard = binding.billboard();
            if (billboard != null) {
                if (billboard.usesCameraVelocity()) caps |= CAP_UNITY_CAMERA_VELOCITY;
                if (billboard.order().usesCameraDistance()) caps |= CAP_NEEDS_MESH_VIEW;
            }
        }
        for (var material : definition.materials().values()) {
            if (material.sceneColor() || material.customSceneTextures() != 0
                    || (material.deferredSceneTextures() & MaterialDefinition.RAW_SCENE_COLOR) != 0
                    || material.blend() == MaterialDefinition.Blend.OPAQUE) caps |= 4;
            if ((material.deferredSceneTextures() & MaterialDefinition.GBUFFER_SCENE_TEXTURES) != 0)
                caps |= NiagaraGpuBatchDispatcher.READ_CAPTURED_GBUFFER;
            if ((material.deferredSceneTextures() & MaterialDefinition.SCENE_AMBIENT_OCCLUSION) != 0)
                caps |= NiagaraGpuBatchDispatcher.READ_SCREEN_SPACE_AO;
            if ((material.particleLightingFeatures() & MaterialDefinition.SUBSURFACE_DIFFUSION) != 0)
                caps |= SUBSURFACE_HISTORY;
            if (material.defaultLitSurface() && !material.blend().translucent()) caps |= OPAQUE_MATERIAL_LIGHTING;
            var vertex = material.vertexMaterial();
            if (vertex != null && vertex.naniteShader() != null && (vertex.previousView() || vertex.previousMatrices()
                    || vertex.previousClock() || vertex.previousFirstPerson() || (vertex.firstPersonInputs() & 0xf00) != 0))
                caps |= CAP_NANITE_PREVIOUS_VIEW;
            if (EffectFrame.readsOpaqueSceneColor(material)) caps |= CAP_READS_OPAQUE_SCENE_COLOR;
            if (material.usesTranslucencyVolume()) caps |= CAP_TRANSLUCENCY_VOLUME;
            if (material.usesForwardTranslucency()) caps |= CAP_FORWARD_TRANSLUCENCY;
            if (EffectFrame.usesLitVolumeFog(material)) caps |= CAP_LIT_VOLUME_FOG;
            if (material.blend().translucent()
                    && material.renderSettings().blendColorSpace() == RenderSettings.BlendColorSpace.UNITY_GAMMA)
                caps |= CAP_GAMMA_BLENDING;
        }
        for (var emitter : definition.emitters()) {
            if (definition.niagara() != null && !platform.renders(emitter.renderer())) continue;
            switch (emitter.renderer()) {
                case LIGHT -> caps |= 1;
                case DECAL -> caps |= 2;
                case RIBBON -> caps |= CAP_HAS_RIBBON;
                case MESH -> caps |= CAP_NEEDS_MESH_VIEW;
                default -> {}
            }
        }
        if (definition.niagara() != null) {
            if (platform.translucentSortPolicy() != TranslucentSortPolicy.DISTANCE) caps |= CAP_NEEDS_MESH_VIEW;
            for (var emitter : definition.niagara().emitters()) {
                for (var renderer : emitter.renderers()) {
                    var component = renderer.component();
                    if (component != null) {
                        int outputs = com.kltyton.lumenfx.api.client.VfxComponents.factory(component.sourceClass()).sceneOutputs();
                        caps |= outputs & 7;
                        if ((outputs & com.kltyton.lumenfx.api.client.component.ComponentFactory.GEOMETRY) != 0)
                            caps |= CAP_NEEDS_MESH_VIEW;
                    }
                }
                if (emitter.simulation() instanceof NiagaraDefinition.GpuSimulation gpu) {
                    var shaders = resources.niagaraShaders().get(NiagaraGpuBatchDispatcher.shaderId(gpu));
                    if (shaders != null) {
                        int inputs = NiagaraGpuBatchDispatcher.opaqueInputs(shaders, gpu.metadata(), platform);
                        caps |= inputs & (NiagaraGpuBatchDispatcher.READ_CAPTURED_GBUFFER
                                | NiagaraGpuBatchDispatcher.READ_PARTIAL_DEPTH | NiagaraGpuBatchDispatcher.READ_CUSTOM_DEPTH
                                | NiagaraGpuBatchDispatcher.READ_SCENE_DEPTH | NiagaraGpuBatchDispatcher.READ_SCREEN_SPACE_AO
                                | NiagaraGpuBatchDispatcher.POST_OPAQUE_SCENE);
                        if ((inputs & NiagaraGpuBatchDispatcher.READ_SCENE_VELOCITY) != 0) caps |= SCENE_VELOCITY_READER;
                    }
                }
            }
        }
        return caps;
    }

    private boolean hasSceneRenderers(EffectDefinition definition, EffectHandle handle, int mask) {
        if ((definitionCapabilities(definition) & mask) != 0) return true;
        if ((mask & 4) != 0) {
            var override = handle.customDepthStencilOverride();
            return (override == null ? definition.customDepthStencil() : override).enabled();
        }
        return false;
    }

    /** New scene-dependent systems join before the material capture starts. */
    public long beginSceneFrame() {
        requireClientThread();
        long previous = sceneRegistrationBoundary;
        sceneRegistrationBoundary = pendingSequence.get();
        return previous;
    }

    public void endSceneFrame(long previous) { sceneRegistrationBoundary = previous; }

    public EffectTexture texture(Identifier id) { return resources.textures().get(id); }
    public RenderTargetTexture textureTarget(Identifier id) { return resources.textureTargets().get(id); }
    public @Nullable TextureMipPrograms textureMipPrograms() { return resources.textureMipPrograms(); }
    public @Nullable PreparedShadingEnergyShaders shadingEnergyShaders() { return resources.shadingEnergyShaders(); }
    public @Nullable PreparedNaniteVertexMaterial naniteVertexMaterial(Identifier shader) { return resources.naniteMaterials().vertex().get(shader); }
    public int subsurfaceProfileIndex(String source) { return resources.subsurfaceProfiles().index(source); }
    public int toonProfileIndex(String source) { return resources.toonProfiles().index(source); }
    public int specularProfileIndex(String source) { return resources.specularProfiles().index(source); }

    public @Nullable TextureMipPrograms replaceTextureMipPrograms(@Nullable TextureMipPrograms mipPrograms) {
        requireClientThread();
        Resources previous = resources;
        resources = new Resources(previous.generation(), previous.definitions(), previous.particleViews(), previous.textures(),
                previous.fogIntegration(), previous.niagaraShaders(),
                previous.niagaraDrawShaders(), previous.niagaraSortShaders(), previous.niagaraIdShaders(), previous.meshSceneShaders(),
                previous.statelessShaders(), previous.naniteShaders(), previous.gaussianSplatShaders(), previous.naniteMaterials(), previous.textureTargets(), mipPrograms, previous.virtualTextures(), previous.shadingEnergyShaders(),
                previous.subsurfaceProfiles(), previous.specularProfiles(), previous.toonProfiles());
        return previous.textureMipPrograms();
    }

    public Map<Identifier, EffectTexture> replaceTextures(Map<Identifier, EffectTexture> textures) {
        requireClientThread();
        Resources previous = resources;
        resources = new Resources(previous.generation(), previous.definitions(), previous.particleViews(),
                Map.copyOf(textures), previous.fogIntegration(), previous.niagaraShaders(),
                previous.niagaraDrawShaders(), previous.niagaraSortShaders(), previous.niagaraIdShaders(), previous.meshSceneShaders(),
                previous.statelessShaders(), previous.naniteShaders(), previous.gaussianSplatShaders(), previous.naniteMaterials(), previous.textureTargets(), previous.textureMipPrograms(), previous.virtualTextures(),
                previous.shadingEnergyShaders(), previous.subsurfaceProfiles(), previous.specularProfiles(), previous.toonProfiles());
        return previous.textures();
    }

    public Map<Identifier, RenderTargetTexture> replaceTextureTargets(Map<Identifier, RenderTargetTexture> targets) {
        requireClientThread();
        Resources previous = resources;
        resources = new Resources(previous.generation(), previous.definitions(), previous.particleViews(), previous.textures(),
                previous.fogIntegration(), previous.niagaraShaders(),
                previous.niagaraDrawShaders(), previous.niagaraSortShaders(), previous.niagaraIdShaders(), previous.meshSceneShaders(),
                previous.statelessShaders(), previous.naniteShaders(), previous.gaussianSplatShaders(), previous.naniteMaterials(), Map.copyOf(targets), previous.textureMipPrograms(), previous.virtualTextures(),
                previous.shadingEnergyShaders(), previous.subsurfaceProfiles(), previous.specularProfiles(), previous.toonProfiles());
        return previous.textureTargets();
    }

    public @Nullable RenderSettings viewSettings() {
        var snapshot = resources;
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        RenderSettings settings = RenderSettings.STANDARD;
        var views = snapshot.particleViews();
        if (!views.isEmpty()) {
            long generation = snapshot.generation();
            int particleGeneration = VanillaParticleHost.INSTANCE.generation();
            if (activeScope != null && activeScope.current(level, generation, particleGeneration)) {
                for (var active : playback.activeEffects()) {
                    if (active instanceof FrameEffect effect && effect.handle().isAlive()) {
                        settings = mergeViewInputs(settings, views.get(effect.instance().definition()));
                    }
                }
            }
            for (var queued : pending) {
                if (queued.sequence() <= sceneRegistrationBoundary && queued.scope().current(level, generation, particleGeneration)
                        && queued.handle().isAlive()) {
                    settings = mergeViewInputs(settings, views.get(queued.definition()));
                }
            }
            if (settings.hdr()) return settings;
        }
        return hasActiveSceneRenderers(NATIVE_OPAQUE_SCENE | CAP_GAMMA_BLENDING) ? RenderSettings.STANDARD : null;
    }

    public boolean hasActiveDeferredSkyLighting() {
        return anyActiveIndirectLighting(com.kltyton.lumenfx.client.render.light.scene.DeferredSkyLighting::active);
    }

    public boolean hasActiveOpaqueIndirectLighting(NiagaraPlatformEnvironment platform) {
        return anyActiveIndirectLighting(indirect ->
                com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse.hasOpaqueBasePassLighting(indirect, platform));
    }

    private boolean anyActiveIndirectLighting(
            java.util.function.Predicate<com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting> predicate) {
        var snapshot = resources;
        var level = Minecraft.getInstance().level;
        if (level == null || snapshot.particleViews().isEmpty()) return false;
        long generation = snapshot.generation();
        int particleGeneration = VanillaParticleHost.INSTANCE.generation();
        if (activeScope != null && activeScope.current(level, generation, particleGeneration)) {
            for (var active : playback.activeEffects()) {
                if (active instanceof FrameEffect effect && effect.handle().isAlive()
                        && definitionMatchesIndirect(effect.instance().definition(), predicate)) {
                    return true;
                }
            }
        }
        for (var queued : pending) {
            if (queued.sequence() <= sceneRegistrationBoundary && queued.scope().current(level, generation, particleGeneration)
                    && queued.handle().isAlive() && definitionMatchesIndirect(queued.definition(), predicate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean definitionMatchesIndirect(
            EffectDefinition definition,
            java.util.function.Predicate<com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting> predicate) {
        if (predicate.test(definition.renderSettings().indirectLighting())) return true;
        for (var material : definition.materials().values()) {
            if (predicate.test(material.renderSettings().indirectLighting())) return true;
        }
        return false;
    }

    private static RenderSettings mergeViewInputs(RenderSettings current, @Nullable RenderSettings requested) {
        if (requested == null) return current;
        var defaults = RenderSettings.STANDARD;
        var indirect = requested.indirectLighting().equals(defaults.indirectLighting()) ? current.indirectLighting() : requested.indirectLighting();
        if (current.hdr() && current.indirectLighting().equals(indirect)) return current;
        return new RenderSettings(true, defaults.fog(), defaults.meshLod(), indirect);
    }

    public PreparedNiagaraShaders niagaraShaders(Identifier id) { return resources.niagaraShaders().get(id); }
    public NiagaraGpuPrograms niagaraPrograms() { return niagaraPrograms; }
    public void distanceFieldBlockChanged(ClientLevel level, BlockPos position) { worldDistanceField.blockChanged(level, position); }
    public void publishPhysicsField(ClientLevel level, @Nullable NiagaraPhysicsField field) {
        requireClientThread();
        if (Minecraft.getInstance().level != level) throw new IllegalArgumentException("Physics field belongs to a different level");
        NiagaraPhysicsFieldWorlds.publish(level, field);
    }
    public void publishWaterScene(ClientLevel level, @Nullable NiagaraWaterScene scene) {
        requireClientThread();
        if (Minecraft.getInstance().level != level) throw new IllegalArgumentException("Water scene belongs to a different level");
        NiagaraWaterWorlds.publish(level, scene);
    }

    public void publishChaosScene(ClientLevel level, @Nullable NiagaraChaosScene scene) {
        requireClientThread();
        if (Minecraft.getInstance().level != level) throw new IllegalArgumentException("Chaos scene belongs to a different level");
        NiagaraChaosWorlds.publish(level, scene);
    }

    public void setChaosEventProducer(ClientLevel level, @Nullable Consumer<EventDemand> enable) {
        requireClientThread();
        if (Minecraft.getInstance().level != level) throw new IllegalArgumentException("Chaos event producer belongs to a different level");
        NiagaraChaosWorlds.producer(level, enable);
    }

    public void setUnityColliderForceConsumer(
            @Nullable BiConsumer<ClientLevel, UnityParticleCollisionEvent.ForceImpact> consumer) {
        unityColliderForceConsumer = consumer;
    }

    public void dispatchUnityColliderForces(@Nullable ClientLevel level, List<UnityParticleCollisionEvent.ForceImpact> forces) {
        if (forces.isEmpty()) return;
        var consumer = unityColliderForceConsumer;
        if (consumer != null) for (var force : forces) consumer.accept(level, force);
    }

    public NiagaraGroomAsset niagaraGroomAsset(Identifier effect, String source) {
        requireClientThread();
        var definition = resources.definitions().get(effect);
        if (definition == null) throw new IllegalArgumentException("Unknown effect " + effect);
        var asset = definition.groomAssets().get(SourceNames.canonical(source));
        if (asset == null) throw new IllegalArgumentException("Unknown Niagara Groom " + source + " in " + effect);
        return asset;
    }

    public AuthoredPhysicsFieldComponent authoredPhysicsFieldComponent(Identifier effect, String component) {
        requireClientThread();
        var definition = resources.definitions().get(effect);
        if (definition == null) throw new IllegalArgumentException("Unknown effect " + effect);
        var field = definition.physicsFieldComponents().get(SourceNames.canonical(component));
        if (field == null) throw new IllegalArgumentException("Unknown Chaos FieldSystemComponent " + component + " in " + effect);
        return field;
    }

    public OfflineFont offlineMaterialFont(Identifier effect, String source) {
        requireClientThread();
        var definition = resources.definitions().get(effect);
        if (definition == null) throw new IllegalArgumentException("Unknown effect " + effect);
        var textures = definition.materialFonts().get(SourceNames.canonical(source));
        if (textures == null) throw new IllegalArgumentException("Unknown offline material font " + source + " in " + effect);
        var pages = new HashMap<Integer, TextureParameter>();
        textures.forEach((page, texture) -> pages.put(page, TextureParameter.resource(texture)));
        return new OfflineFont(pages);
    }
    public void distanceFieldChunkChanged(ClientLevel level, ChunkPos position) {
        worldDistanceField.chunkChanged(level, position);
    }

    public void requestNanitePages(ClientLevel level, int[] packedStream) {
        naniteStreaming(level).requestPages(level, Objects.requireNonNull(packedStream));
    }

    public void prefetchNaniteMesh(ClientLevel level, StaticMeshAsset mesh, int framesUntilRender) {
        var asset = Objects.requireNonNull(mesh.nanite(), "Static mesh has no native Nanite resource");
        naniteStreaming(level).prefetch(level, asset, framesUntilRender);
    }

    private NaniteWorldRenderer naniteStreaming(ClientLevel level) {
        requireClientThread();
        if (level != Minecraft.getInstance().level) throw new IllegalArgumentException("Nanite streaming requires the current client world");
        return Objects.requireNonNull(naniteRenderer, "The current renderer has no prepared Nanite resources");
    }

    public FrameInput capture(ClientLevel level, CameraRenderState camera, double time) {
        requireClientThread();
        var host = hostSimulationFrame;
        if (host != null && (host.clock().level() != level || host.clock().generation() != resources.generation()
                || host.clock().sampledTime() != time)) {
            clearHostSimulationFrame();
            host = null;
        }
        FrameClock clock = host != null ? host.clock() : advanceFrameClock(level, time);
        var window = Minecraft.getInstance().getWindow();
        Matrix4fc projection = camera.projectionMatrix;
        float nearClip = camera.initialized ? Math.abs(projection.m32() / (projection.m23() - projection.m22())) * 100.0f : 0.0f;
        var cameraProjection = new CameraProjection(camera.initialized, projection.m00(), nearClip,
                window.getWidth(), window.getHeight(), camera.initialized ? new Matrix4f(projection) : null);
        var input = clock.input(camera.pos, camera.orientation, cameraProjection);
        if (!renderResourcesReleased) {
            if (extractedHostInput == null) {
                var scalabilityView = camera.initialized ? new NiagaraScalabilityView(camera.pos, camera.viewRotationMatrix,
                        projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) : null;
                long boundary = host == null ? playback.frameActivation() : host.activationBoundary();
                Set<EffectInstance> simulated = host == null ? Set.of() : host.simulated();
                Supplier<@Nullable NiagaraCameraFrame> snapshot = () -> captureInterfaceCamera(input, cameraProjection);
                NiagaraDataChannelWorlds.flush(level);
                if (activeScope != null && activeScope.current(level, clock.generation(), VanillaParticleHost.INSTANCE.generation())) {
                    niagaraScalability.view(scalabilityView);
                    simulated = simulateHostInstances(input, NiagaraWorldSystemSimulation.Phase.AfterCamera, simulated,
                            snapshot, boundary, false, null);
                }
                boolean earlySpawn = host != null && clock.generation() == resources.generation() && !pending.isEmpty()
                        && NiagaraPlatforms.snapshot().worldSpawnsPerTickGroup();
                if (earlySpawn) {
                    prepareInstances(level);
                    niagaraScalability.view(scalabilityView);
                    simulated = simulateHostInstances(input, NiagaraWorldSystemSimulation.Phase.AfterCamera, simulated,
                            snapshot, boundary, true, NiagaraWorldSystemSimulation.Phase.AfterCamera);
                }
                NiagaraGpuParticleExportReadbacks.poll(level);
                NiagaraGpuCacheReadbacks.poll();
                NiagaraParticleExportCallbacks.drain(level);
                if (clock.generation() == resources.generation() && (earlySpawn || !pending.isEmpty())) {
                    if (!pending.isEmpty()) prepareInstances(level);
                    niagaraScalability.view(scalabilityView);
                    simulated = simulateHostInstances(input, NiagaraWorldSystemSimulation.Phase.AfterCamera, simulated,
                            snapshot, boundary, true, null);
                }
                hostSimulationFrame = new HostSimulationFrame(clock, simulated, boundary);
            }
            extractedHostInput = input;
        }
        return input;
    }

    private FrameClock advanceFrameClock(ClientLevel level, double time) {
        worldSystemSimulation.advanceFrame();
        long monotonicTime = net.minecraft.util.Util.getNanos();
        niagaraFxBudget.beginFrame(NiagaraPlatforms.snapshot(), monotonicTime * 1.0e-9);
        float delta = clockLevel == level ? (float)Math.max(0, time - simulationTime) : 0;
        float realDelta = clockLevel == level ? (float)((monotonicTime - realClockLast) * 1.0e-9) : 0;
        if (clockLevel != level) {
            if (clockLevel != null) clearDataChannelWorld(clockLevel);
            if (clockLevel != null) {
                meshStreaming.clearResidency();
                skeletalMeshStreaming.clearResidency();
                NiagaraStaticMeshWorlds.clear(clockLevel);
                NiagaraSkeletalMeshWorlds.clear(clockLevel);
                NiagaraGpuCacheReadbacks.clear(clockLevel);
                NiagaraAudioBusAnalysis.clear(clockLevel);
                NiagaraAudioBuses.clear(clockLevel);
            }
            renderer.resetPointShadowCache();
            ComponentWorlds.retainWorld(level);
            clockLevel = level; simulationTime = time; gameClockStart = time; realClockStart = monotonicTime;
        }
        else simulationTime = Math.max(simulationTime, time);
        if (!renderResourcesReleased)
            meshStreaming.advance(simulationTime, lods -> renderer.prepareStaticMeshResidency(resources.generation(), lods));
        if (!renderResourcesReleased) skeletalMeshStreaming.advance();
        NiagaraCollectionWorlds.advance(level, simulationTime);
        var channelEnvironment = NiagaraPlatforms.snapshot();
        if (channelEnvironment.dataChannelsEnabled()) {
            NiagaraGpuDataChannelReadbacks.poll(level);
            NiagaraDataChannelWorlds.advance(level, simulationTime, channelEnvironment.dataChannelsAutoUploadGpuSpawns());
        } else clearDataChannelWorld(level);
        realClockLast = monotonicTime;
        double realSeconds = (monotonicTime - realClockStart) * 1.0e-9;
        ComponentWorlds.advance(level, simulationTime - gameClockStart);
        drainPointLightRetirements();
        renderer.expirePointShadows(realSeconds);
        return new FrameClock(resources.generation(), level, time, simulationTime, simulationTime - gameClockStart,
                realSeconds, delta, realDelta);
    }

    public void simulateBeforeCamera(ClientLevel level, Camera camera, double time) {
        requireClientThread();
        clearHostSimulationFrame();
        if (renderResourcesReleased) return;
        prepareInstances(level);
        if (!playback.hasFrameEffects() && pending.isEmpty()) {
            if (ComponentWorlds.hasOwners(level)) advanceFrameClock(level, time);
            return;
        }
        flushMissedGpuFrames(level);
        FrameClock clock = advanceFrameClock(level, time);
        FrameInput input = clock.input(camera.position(), camera.rotation(), null);
        long boundary = playback.frameActivation();
        hostSimulationFrame = new HostSimulationFrame(clock, simulateHostInstances(input,
                NiagaraWorldSystemSimulation.Phase.BeforeCamera, Set.of(), () -> captureBeforeCamera(camera),
                boundary, false, null), boundary);
    }

    private Set<EffectInstance> simulateHostInstances(FrameInput input, NiagaraWorldSystemSimulation.Phase phase,
                                                     Set<EffectInstance> alreadySimulated,
                                                     Supplier<@Nullable NiagaraCameraFrame> cameraSnapshot,
                                                     long activationBoundary, boolean postActorSpawn,
                                                     NiagaraWorldSystemSimulation.@Nullable Phase spawnBeforeGroup) {
        var normalPlan = new NiagaraTickClock.Plan(1, input.delta(), input.delta(), false);
        var ready = new ArrayList<EffectInstance>();
        var deferredPrograms = previousGpuView != null && gpuViewLevel == input.level() ? niagaraPrograms : null;
        boolean cameraUpdated = phase == NiagaraWorldSystemSimulation.Phase.AfterCamera;
        updateNiagaraScalability(input, postActorSpawn || cameraUpdated && hostSimulationFrame != null);
        for (var effect : playback.activeEffects()) {
            if (!(effect instanceof FrameEffect entry) || (entry.activation() > activationBoundary) != postActorSpawn) continue;
            var instance = entry.instance();
            if (!alreadySimulated.contains(instance) && instance.handle().isAlive()
                    && instance.beginHostSimulationFrame(input.time(), cameraUpdated, deferredPrograms, this::niagaraShaders,
                    () -> normalPlan, postActorSpawn, spawnBeforeGroup)) {
                instance.trackWorldSimulation(worldSystemSimulation, phase, postActorSpawn);
                ready.add(instance);
            }
        }
        if (!postActorSpawn) worldSystemSimulation.beginTick(phase, input.delta());
        if (ready.isEmpty()) return alreadySimulated;
        var cameraFrame = ready.stream().anyMatch(EffectInstance::needsCameraInterface) ? cameraSnapshot.get() : null;
        simulateFrameSteps(ready, input, cameraFrame, false, postActorSpawn, true);
        ready.forEach(EffectInstance::finishHostSimulationFrame);
        var simulated = new HashSet<>(alreadySimulated);
        simulated.addAll(ready);
        return Set.copyOf(simulated);
    }

    private void updateNiagaraScalability(FrameInput view, boolean newOnly) {
        niagaraRenderVisibility.poll();
        try (var timing = niagaraFxBudget.game()) {
            var frame = hostSimulationFrame;
            var simulated = frame == null ? Set.<EffectInstance>of() : frame.simulated();
            for (var effect : playback.activeEffects()) {
                if (!(effect instanceof FrameEffect entry)) continue;
                var instance = entry.instance();
                if (!simulated.contains(instance)) instance.applyComponentRequests();
            }
            niagaraScalability.update(playback.activeEffects(), effect -> effect instanceof FrameEffect entry ? entry.instance() : null,
                    view.camera(), view.engineTime(), newOnly,
                    niagaraRenderVisibility.completedTime() - view.delta(), Minecraft.getInstance().isWindowActive(),
                    niagaraFxBudget.worstUsage(), view.delta(),
                    playback::addFrameEffect);
        }
    }

    private void flushMissedGpuFrames(ClientLevel level) {
        var cached = previousGpuView;
        var programs = niagaraPrograms;
        if (cached == null || gpuViewLevel != level || programs == null) {
            queuedGpuFrames = 0;
            return;
        }
        int pendingSteps = 0;
        var pendingInstances = new ArrayList<EffectInstance>();
        for (var effect : playback.activeEffects()) {
            if (!(effect instanceof FrameEffect entry)) continue;
            var instance = entry.instance();
            if (instance.handle().isAlive() && instance.pendingHostFinish()) {
                pendingSteps = Math.max(pendingSteps, instance.pendingGpuSteps());
                pendingInstances.add(instance);
            }
        }
        if (pendingSteps == 0) {
            queuedGpuFrames = 0;
            return;
        }
        if (++queuedGpuFrames < GPU_VIEWLESS_FRAME_LIMIT && pendingSteps < NiagaraGpuBatchDispatcher.VIEWLESS_STEP_LIMIT)
            return;
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var projection = cached.baseProjection();
        var temporary = new NiagaraGpuView.Frame(cached.camera(), cached.modelView(), projection, projection, projection,
                cached.width(), cached.height(), cached.gameTime(), cached.realTime(), cached.deltaTime(),
                cached.realDeltaTime(), cached.resolutionFraction());
        var channelEnvironment = NiagaraPlatforms.snapshot();
        int reads = 0;
        for (var instance : pendingInstances) reads |= instance.sceneReads(programs, this::niagaraShaders);
        var sceneInputs = NiagaraSceneInputs.EMPTY;
        if ((reads & NiagaraGpuBatchDispatcher.READ_GLOBAL_DISTANCE_FIELD) != 0
                && channelEnvironment.distanceFieldClipmapCount() > 0
                && gpuViewCutVersion == WorldHdrCapture.INSTANCE.cameraCutVersion()) {
            sceneInputs = sceneInputs.withGlobalDistanceField(worldDistanceField.cached(level, cached.camera(), gpuViewCutVersion));
        }
        var view = new NiagaraGpuView(temporary, null, device.getDeviceInfo().isZZeroToOne(), sceneInputs);
        programs.captureSharedMeshFields(level);
        EffectInstance.flushGpuWithoutScene(pendingInstances, encoder, programs, this::niagaraShaders, view, () ->
                NiagaraGpuDataChannelWorlds.beginFrame(level, programs, channelEnvironment.dataChannelsRetainGpu(),
                        channelEnvironment.dataChannelsAutoUploadGpuSpawns()));
        queuedGpuFrames = 0;
    }

    private static @Nullable NiagaraCameraFrame captureBeforeCamera(Camera camera) {
        if (!camera.isInitialized()) return null;
        var window = Minecraft.getInstance().getWindow();
        double horizontal = Math.atan(Math.tan(Math.toRadians(camera.getFov()) / 2)
                * window.getWidth() / window.getHeight());
        return NiagaraCameraFrame.capture(camera.position(), camera.rotation(), (float)Math.toDegrees(2 * horizontal),
                window.getWidth(), window.getHeight(), Camera.PROJECTION_Z_NEAR * 100.0f);
    }

    private void clearHostSimulationFrame() {
        hostSimulationFrame = null;
        extractedHostInput = null;
    }

    private static void clearDataChannelWorld(ClientLevel level) {
        NiagaraGpuDataChannelReadbacks.clear(level);
        NiagaraGpuDataChannelWorlds.clear(level);
        NiagaraDataChannelWorlds.clear(level);
    }

    private void warmUp(List<EffectInstance> instances, FrameInput view, @Nullable NiagaraCameraFrame cameraFrame) {
        try (var timing = niagaraFxBudget.game()) { runWarmup(instances, view, cameraFrame); }
    }

    private void runWarmup(List<EffectInstance> instances, FrameInput view, @Nullable NiagaraCameraFrame cameraFrame) {
        List<EffectInstance> warming = new ArrayList<>();
        while (true) {
            warming.clear();
            frameEmitters.clear();
            for (var instance : instances) if (instance.beginWarmupStep()) {
                warming.add(instance);
                frameEmitters.addAll(instance.prepareWarmupFrame(view.time(), view.engineTime(), view.realTime(), view.camera(), cameraFrame));
            }
            if (warming.isEmpty()) return;
            executeEmitters(false);
            for (var instance : warming) instance.finishWarmupStep(niagaraPrograms);
        }
    }

    private int simulateFrameSteps(List<EffectInstance> instances, FrameInput view,
                                   @Nullable NiagaraCameraFrame cameraFrame, boolean pack,
                                   boolean postActorSpawn, boolean warmup) {
        var groups = new EnumMap<ComponentTickGroup, List<EffectInstance>>(ComponentTickGroup.class);
        for (var instance : instances) {
            groups.computeIfAbsent(instance.selectedTickGroup(), ignored -> new ArrayList<>()).add(instance);
        }
        int partitions = 0;
        try (var timing = niagaraFxBudget.game()) {
            NiagaraDataChannelWorlds.flush(view.level());
            for (var group : groups.values()) {
                if (warmup) runWarmup(group, view, cameraFrame);
                partitions = Math.max(partitions, runSimulationSteps(group, view, cameraFrame, pack, postActorSpawn));
                NiagaraDataChannelWorlds.flush(view.level());
            }
        }
        return partitions;
    }

    private int runSimulationSteps(List<EffectInstance> instances, FrameInput view,
                                   @Nullable NiagaraCameraFrame cameraFrame, boolean pack, boolean postActorSpawn) {
        for (var instance : instances) instance.resolveFrameTickPlan(postActorSpawn);
        frameSeeking.clear();
        frameOrdinary.clear();
        for (var instance : instances) {
            if (instance.needsAgeRenderPack()) frameSeeking.add(instance);
            else frameOrdinary.add(instance);
        }
        if (frameSeeking.isEmpty()) {
            frameOrdinary.clear();
            return simulateTickSteps(instances, view, cameraFrame, pack, postActorSpawn);
        }
        int partitions = simulateTickSteps(frameOrdinary, view, cameraFrame, pack, postActorSpawn);
        frameOrdinary.clear();
        // The native solo MultiTick budget measures only that component's simulation.
        for (var instance : frameSeeking)
            partitions = Math.max(partitions, simulateTickSteps(Collections.singletonList(instance), view, cameraFrame, pack, postActorSpawn));
        frameSeeking.clear();
        return partitions;
    }

    private int simulateTickSteps(List<EffectInstance> instances, FrameInput view,
                                  @Nullable NiagaraCameraFrame cameraFrame, boolean pack, boolean postActorSpawn) {
        if (instances.isEmpty()) return 0;
        int rounds = 1, partitions = 0;
        int passes = 1;
        for (var instance : instances) {
            rounds = Math.max(rounds, instance.simulationSteps());
            if (instance.usesNonOverlappingNiagaraPass()) passes = 2;
        }
        var stepping = frameStepping;
        for (int step = 0; step < rounds; step++) {
            boolean stepped = false;
            for (int pass = 0; pass < passes; pass++) {
                frameEmitters.clear();
                stepping.clear();
                try {
                    for (var instance : instances) {
                        if (passes == 2 && instance.usesNonOverlappingNiagaraPass() != (pass == 1)) continue;
                        if (step < instance.simulationSteps()) {
                            stepping.add(instance);
                            frameEmitters.addAll(instance.prepareSimulationStep(view.time(), view.engineTime(), view.realTime(),
                                    view.camera(), cameraFrame, step, worldSystemSimulation, postActorSpawn));
                        } else if (step == 0 && instance.simulationSteps() == 0) {
                            var emitters = instance.prepareRenderFrame(view.time());
                            if (pack) frameEmitters.addAll(emitters);
                        }
                    }
                    executeSharedSystemSteps();
                } catch (RuntimeException | Error failure) {
                    stepping.clear();
                    worldSystemSimulation.discardPending();
                    throw failure;
                }
                partitions = Math.max(partitions, executeEmitters(pack));
                for (var instance : stepping) instance.finishFrameSimulationStep(view.level(), niagaraPrograms, step);
                stepped |= !stepping.isEmpty();
            }
            if (!stepped) break;
        }
        stepping.clear();
        return partitions;
    }

    private void executeSharedSystemSteps() {
        var steps = worldSystemSimulation.stagePendingSystemSteps();
        try {
            int first = 0;
            while (first < steps.size()) {
                var step = steps.get(first);
                if (!step.canRunOnTaskExecutor()) {
                    step.execute();
                    step.finish();
                    first++;
                    continue;
                }
                int end = first + 1;
                while (end < steps.size() && steps.get(end).canRunOnTaskExecutor()) end++;
                int start = first;
                int count = end - start;
                int partitions = Math.min(workers.maximumWorkers(), count);
                workers.executeRanges(start, end, partitions, (partition, from, to) -> {
                    for (int index = from; index < to; index++) {
                        steps.get(index).execute();
                    }
                });
                for (int index = start; index < end; index++) steps.get(index).finish();
                first = end;
            }
        } catch (RuntimeException | Error failure) {
            steps.forEach(NiagaraWorldSystemSimulation.PreparedStep::discard);
            throw failure;
        }
    }

    public void invalidatePointLight(long identity) { retiredPointLights.add(identity); }
    private void drainPointLightRetirements() {
        for (Long identity; (identity = retiredPointLights.poll()) != null;) renderer.removePointShadow(identity);
    }

    public void draw(FrameInput view, Matrix4fc modelView, RenderTarget target) {
        try (var prepared = prepareFrame(view, modelView, target)) {
            if (prepared != null) {
                drawPrepared(prepared, modelView, target, null);
                prepared.finish();
            }
        }
    }

    public void drawPrepared(PreparedFrame prepared, Matrix4fc modelView, RenderTarget target, Boolean opaquePhase) {
        requireClientThread();
        if (prepared.owner != this || prepared.closed) throw new IllegalStateException("Effect frame has no live preparation owner");
        var view = prepared.input;
        if (view.level() != Minecraft.getInstance().level || view.generation() != resources.generation())
            throw new IllegalStateException("Effect frame belongs to an expired world or resource generation");
        var virtualTextures = resources.virtualTextures();
        virtualTextures.beginMaterialReads(view.level(), prepared.materialTextureFrame, target.width, target.height);
        try (var timing = niagaraFxBudget.render()) {
            drawScene(prepared, modelView, target, opaquePhase);
            finishAsyncTraceRenderFrame(prepared, target);
        }
        finally { virtualTextures.materialReads().end(); }
    }

    private void finishAsyncTraceRenderFrame(PreparedFrame prepared, RenderTarget target) {
        var programs = niagaraPrograms;
        var level = prepared.input.level();
        long renderFrame = prepared.asyncTraceFrame;
        if (programs == null || !programs.hasAsyncTraceRenderFrame(level, renderFrame)) return;
        if (prepared.postOpaque != null && prepared.lateTranslucency == null) return;
        int reads = programs.asyncTraceNeedsDistanceField(level, renderFrame)
                ? NiagaraGpuBatchDispatcher.READ_GLOBAL_DISTANCE_FIELD : 0;
        var inputs = prepared.preparedSceneInputs == null ? prepared.sceneInputs : prepared.preparedSceneInputs;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var view = prepareGpuView(encoder, level, Objects.requireNonNull(prepared.gpuView), target, inputs, reads);
        programs.finishAsyncTraceRenderFrame(level, encoder, view);
    }

    private void drawScene(PreparedFrame prepared, Matrix4fc modelView, RenderTarget target, Boolean opaquePhase) {
        var view = prepared.input;
        EffectFrame frame = opaquePhase == null ? prepared.frame : Boolean.FALSE.equals(opaquePhase) && prepared.postOpaque != null
                ? Objects.requireNonNull(prepared.lateTranslucency, "The opaque stage has not submitted Niagara")
                : prepared.frame.geometryPhase(opaquePhase);
        var orientation = new Quaternionf(view.qx(), view.qy(), view.qz(), view.qw());
        var worldCapture = WorldHdrCapture.INSTANCE;
        var platform = NiagaraPlatforms.snapshot();
        var baseInputs = prepared.preparedSceneInputs == null ? prepared.sceneInputs : prepared.preparedSceneInputs;
        var atmosphereLighting = AtmosphereMaterialLighting.resolve(baseInputs.atmosphere(), frame.directionalLights(), platform);
        var nanite = prepareNaniteWorld(prepared, target, Boolean.FALSE.equals(opaquePhase), atmosphereLighting);
        Supplier<NiagaraSceneInputs> afterBaseDepth = null;
        if (!Boolean.FALSE.equals(opaquePhase) && prepared.preparedSceneInputs == null
                && worldCapture.composing() && target == worldCapture.linearTarget()) {
            var settings = ParticleCustomDepthRenderer.Settings.capture();
            Supplier<NiagaraSceneInputs> produceDepth = () ->
                    prepared.preparedSceneInputs = particleCustomDepth.prepare(prepared.frame, modelView, orientation,
                            view.camera(), target, baseInputs, settings, nanite);
            if (settings.mode() == 0 || settings.beforeBasePass()) produceDepth.get();
            else afterBaseDepth = produceDepth;
        }
        var sceneInputs = prepared.preparedSceneInputs == null ? baseInputs : prepared.preparedSceneInputs;
        var splatInputs = sceneInputs;
        ParticleCasters particleCasters = null;
        DirectionalShadowRenderer.ParticleCasters directionalCasters = null;
        if (!Boolean.FALSE.equals(opaquePhase) && (!frame.pointLights().isEmpty() || !frame.directionalLights().isEmpty())
                && (!prepared.cpuShadowCasters.isEmpty() || !prepared.componentShadowCasters.isEmpty()
                    || !prepared.gpuShadowCasters.isEmpty()
                    || nanite != null && nanite.hasShadows())) {
            var projection = new Matrix4f();
            WorldHdrCapture.INSTANCE.copyUnjitteredProjection(projection);
            float lodDistanceFactor = SourceViewLod.factor(projection);
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            String hostPlatform = meshPlatform();
            if (!frame.pointLights().isEmpty())
                particleCasters = (light, depth, matrices) -> particleShadows.draw(frame, prepared.cpuShadowCasters,
                        prepared.componentShadowCasters, prepared.gpuShadowCasters, PointShadowParticleView.create(light, view.camera(),
                                matrices.spot() ? matrices.hostProjection(0) : projection,
                                lodDistanceFactor, zeroToOne, hostPlatform), target, depth, matrices, 0, depth.viewCount(),
                        niagaraPrograms, meshScenePrograms, nanite, baseInputs.atmosphere(), view.camera());
            if (!frame.directionalLights().isEmpty())
                directionalCasters = (depth, matrices, cascade) -> particleShadows.draw(frame, prepared.cpuShadowCasters,
                        prepared.componentShadowCasters, prepared.gpuShadowCasters, PointShadowParticleView.directional(matrices, cascade,
                                lodDistanceFactor, zeroToOne, hostPlatform), target, depth, matrices, cascade, cascade + 1,
                        niagaraPrograms, meshScenePrograms, nanite, baseInputs.atmosphere(), view.camera());
        }
        boolean captureGBuffer = worldCapture.capturesNiagaraGBuffer() && worldCapture.composing()
                && target == worldCapture.linearTarget();
        boolean publishDecalAo = worldCapture.composing() && target == worldCapture.linearTarget() && !frame.decals().isEmpty();
        int preparedCaps = 0;
        if (prepared.postOpaque != null) {
            for (var instance : prepared.instances) preparedCaps |= definitionCapabilities(instance.definition());
        }
        // Split host stages still need the final opaque color before the later transparent pass.
        boolean captureRawColor = false;
        if (!Boolean.FALSE.equals(opaquePhase)) {
            captureRawColor = prepared.frame.readsOpaqueSceneColor();
            if (!captureRawColor && nanite != null) {
                for (var surface : nanite.surfaces()) {
                    if (EffectFrame.readsOpaqueSceneColor(surface.material())) {
                        captureRawColor = true;
                        break;
                    }
                }
            }
            if (!captureRawColor && prepared.postOpaque != null) {
                for (var work : prepared.postOpaque.work()) {
                    if ((definitionCapabilities(work.instance().definition()) & CAP_READS_OPAQUE_SCENE_COLOR) != 0) {
                        captureRawColor = true;
                        break;
                    }
                }
            }
        }
        boolean captureDeferredTextures = captureGBuffer || captureRawColor || publishDecalAo;
        boolean splitOpaqueDepth = false;
        if (prepared.postOpaque != null) {
            for (var work : prepared.postOpaque.work()) {
                if ((work.instance().sceneReads(Objects.requireNonNull(niagaraPrograms), this::niagaraShaders)
                        & NiagaraGpuBatchDispatcher.READ_PARTIAL_DEPTH) != 0) {
                    splitOpaqueDepth = true;
                    break;
                }
            }
        }
        boolean captureVisibility = false;
        if (!prepared.niagaraVisibilitySubmitted && !Boolean.FALSE.equals(opaquePhase)) {
            for (var instance : prepared.instances) {
                if (instance.hasNiagaraVisibilityProxy()) {
                    captureVisibility = true;
                    break;
                }
            }
        }
        boolean usesTranslucencyVolume = prepared.frame.usesTranslucencyVolume()
                || (preparedCaps & CAP_TRANSLUCENCY_VOLUME) != 0;
        boolean usesForwardTranslucency = prepared.frame.usesForwardTranslucency()
                || (preparedCaps & CAP_FORWARD_TRANSLUCENCY) != 0;
        if (!Boolean.FALSE.equals(opaquePhase)) {
            boolean volumeLighting = prepared.frame.usesVolumeSceneLighting()
                    || (preparedCaps & CAP_LIT_VOLUME_FOG) != 0;
            worldCapture.volumeSceneLighting(volumeLighting);
        }
        if (opaquePhase == null && (!frame.volumes().isEmpty() || prepared.postOpaque != null || captureDeferredTextures || captureVisibility)) {
            var opaque = frame.geometryPhase(true);
            renderer.draw(opaque, modelView, orientation, target, view.camera(), true, particleCasters, sceneInputs, afterBaseDepth,
                    usesTranslucencyVolume, usesForwardTranslucency, directionalCasters, nanite, splitOpaqueDepth);
            volumeRenderer.draw(opaque, modelView, orientation, target, view.camera(), renderer.volumeShadows(), sceneInputs.atmosphere());
            if (captureVisibility) submitNiagaraVisibility(prepared, modelView, target);
            if (captureDeferredTextures) prepareDeferredSceneTextures(prepared, modelView, target, captureGBuffer, captureRawColor, publishDecalAo);
            var transparent = preparePostOpaqueGpu(prepared, target, orientation);
            var transparentInputs = prepared.preparedSceneInputs == null ? sceneInputs : prepared.preparedSceneInputs;
            splatInputs = transparentInputs;
            var transparentLighting = AtmosphereMaterialLighting.resolve(transparentInputs.atmosphere(),
                    transparent.directionalLights(), platform);
            renderer.draw(transparent, modelView, orientation, target, view.camera(), false, null,
                    transparentInputs, null,
                    usesTranslucencyVolume, usesForwardTranslucency, null, prepareNaniteWorld(prepared, target, true, transparentLighting));
        } else {
            renderer.draw(frame, modelView, orientation, target, view.camera(), opaquePhase == null || opaquePhase, particleCasters,
                    sceneInputs, afterBaseDepth, usesTranslucencyVolume, usesForwardTranslucency, directionalCasters, nanite,
                    !Boolean.FALSE.equals(opaquePhase) && splitOpaqueDepth);
            if (Boolean.TRUE.equals(opaquePhase)) {
                volumeRenderer.draw(frame, modelView, orientation, target, view.camera(), renderer.volumeShadows(), sceneInputs.atmosphere());
                if (captureVisibility) submitNiagaraVisibility(prepared, modelView, target);
                if (captureDeferredTextures)
                    prepareDeferredSceneTextures(prepared, modelView, target, captureGBuffer, captureRawColor, publishDecalAo);
                if (prepared.postOpaque != null) preparePostOpaqueGpu(prepared, target, orientation);
            }
        }
        if (!Boolean.TRUE.equals(opaquePhase)) {
            var environment = NiagaraPlatforms.snapshot();
            if (environment.gaussianSplatEnabled()) drawGaussianSplats(prepared, modelView, target, splatInputs);
            if (Objects.requireNonNull(environment.console(NiagaraGpuDebugLines.DRAW_ENABLED)).bits() != 0) {
                frameDebugLines.clear();
                frameCpuDebugLines.clear();
                for (var instance : prepared.instances) {
                    var debug = instance.debugLines();
                    if (debug != null) frameDebugLines.add(debug);
                    var cpu = instance.cpuDebugLines();
                    if (cpu != null) frameCpuDebugLines.add(cpu);
                }
                float occluded = Float.intBitsToFloat(Objects.requireNonNull(
                        environment.console(NiagaraDebugLineRenderer.OCCLUDED_SCALE)).bits());
                debugLineRenderer.draw(frameDebugLines, frameCpuDebugLines, modelView, target, view.camera(), occluded);
                frameDebugLines.clear();
                frameCpuDebugLines.clear();
            }
        }
    }

    private void drawGaussianSplats(PreparedFrame prepared, Matrix4fc modelView, RenderTarget target, NiagaraSceneInputs inputs) {
        var programs = niagaraPrograms;
        if (programs == null) return;
        frameSplatDraws.clear();
        frameSplatDraws.addAll(prepared.cpuSplatDraws);
        for (var instance : prepared.instances) frameSplatDraws.addAll(instance.gaussianSplatDraws(programs.runtime()));
        if (frameSplatDraws.isEmpty()) return;
        if (gaussianSplatRenderer == null) gaussianSplatRenderer = new NiagaraGaussianSplatRenderer(programs);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var viewFrame = prepared.gpuView == null ? captureGpuView(prepared.input, modelView, target) : prepared.gpuView;
        var gpuView = prepareGpuView(encoder, prepared.input.level(), viewFrame, target, inputs, 0);
        gaussianSplatRenderer.draw(encoder, frameSplatDraws, gpuView, target, inputs, prepared.materialTextureFrame);
        frameSplatDraws.clear();
    }

    private NaniteWorldRenderer.@Nullable Frame prepareNaniteWorld(PreparedFrame prepared, RenderTarget target, boolean late,
                                                                    AtmosphereMaterialLighting atmosphere) {
        var view = prepared.gpuView;
        if (naniteRenderer == null || view == null || target.getDepthTextureView() == null) return null;
        var sources = late ? prepared.lateNaniteInstances : prepared.naniteInstances;
        if (sources.isEmpty()) return null;
        var capture = WorldHdrCapture.INSTANCE;
        boolean mainView = !late && capture.composing() && target == capture.linearTarget();
        var previous = gpuViewLevel == prepared.input.level() && gpuViewCutVersion == prepared.gpuViewCut ? previousGpuView : null;
        return naniteRenderer.prepare(prepared.input, view, previous, prepared.frame.motionView(), sources,
                prepared.naniteFrameSequence, mainView, atmosphere);
    }

    private void submitNiagaraVisibility(PreparedFrame prepared, Matrix4fc modelView, RenderTarget target) {
        var sceneDepth = target.getDepthTextureView();
        if (sceneDepth == null) return;
        var view = prepared.input;
        var capture = WorldHdrCapture.INSTANCE;
        var projection = new Matrix4f();
        if (capture.composing() && target == capture.linearTarget()) {
            capture.copyProjection(projection);
        } else {
            var camera = view.cameraProjection();
            if (camera == null || camera.matrix() == null) return;
            projection.set(camera.matrix());
        }
        niagaraRenderVisibility.submit(sceneDepth, projection.mul(modelView), view.camera(), view.engineTime(), prepared.instances);
        prepared.niagaraVisibilitySubmitted = true;
    }

    private void prepareDeferredSceneTextures(PreparedFrame prepared, Matrix4fc modelView, RenderTarget target,
                                              boolean captureGBuffer, boolean captureRawColor, boolean publishDecalAo) {
        var capture = WorldHdrCapture.INSTANCE;
        var scene = prepared.preparedSceneInputs == null ? prepared.sceneInputs : prepared.preparedSceneInputs;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (captureGBuffer) {
            var projection = new Matrix4f();
            capture.copyProjection(projection);
            scene = resolveSceneGBuffer(encoder, modelView, projection, target, scene);
        }
        if (publishDecalAo)
            scene = scene.withTextureIfAbsent(NiagaraSceneInputs.Texture.SCREEN_SPACE_AO, capture.sceneDecalAo());
        if (captureRawColor && !scene.textures().containsKey(NiagaraSceneInputs.Texture.SCENE_COLOR))
            scene = scene.withTextureIfAbsent(NiagaraSceneInputs.Texture.SCENE_COLOR, opaqueSceneColor.capture(encoder, target));
        prepared.preparedSceneInputs = scene;
    }

    private EffectFrame preparePostOpaqueGpu(PreparedFrame prepared, RenderTarget target, Quaternionf orientation) {
        var post = prepared.postOpaque;
        var transparent = prepared.frame.geometryPhase(false);
        if (post == null) return transparent;
        if (prepared.lateTranslucency != null) return prepared.lateTranslucency;
        var view = prepared.input;
        var programs = Objects.requireNonNull(niagaraPrograms, "GPU resources expired during the current Niagara view");
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        int sceneReads = 0;
        frameDispatchBatch.clear();
        for (var work : post.work()) {
            var instance = work.instance();
            frameDispatchBatch.add(instance);
            sceneReads |= instance.sceneReads(programs, this::niagaraShaders);
        }
        var inputs = prepared.preparedSceneInputs == null ? prepared.sceneInputs : prepared.preparedSceneInputs;
        var gpuView = prepareGpuView(encoder, view.level(), post.view(), post.previousView(), target, inputs, sceneReads);
        var depth = target.getDepthTextureView();
        EffectInstance.dispatchPreparedGpu(frameDispatchBatch, encoder, programs, this::niagaraShaders, gpuView, depth);
        frameDispatchBatch.clear();
        framePostOpaqueSpriteTasks.clear();
        framePostOpaqueMeshTasks.clear();
        if (naniteInstances != null) frameNaniteTasks.clear();
        var naniteTasks = naniteInstances == null ? null : frameNaniteTasks;
        for (var work : post.work()) {
            var instance = work.instance();
            instance.appendGpuRenderers(encoder, programs, this::niagaraShaders, view.camera(), orientation,
                    instance.gpuSortDepth(view.camera(), post.meshView()), false, false, true, post.ribbonLodDistanceFactor(), gpuView, target,
                    framePostOpaqueSpriteTasks, List.of(), post.meshView(), framePostOpaqueMeshTasks, null, null, null, null,
                    EffectInstance.GpuRenderPhase.AFTER_POST_OPAQUE, post.lowLatency(), naniteTasks);
        }
        if (naniteInstances != null && naniteRenderer != null && depth != null) {
            var sources = naniteInstances.prepareGpu(encoder, naniteTasks, previousCamera(post.previousView(), view.camera()));
            prepared.lateNaniteInstances = naniteRenderer.select(encoder, view.level(), prepared.naniteFrameSequence, sources);
            NaniteWorldRenderer.selectFallbacks(framePostOpaqueMeshTasks, prepared.lateNaniteInstances);
        }
        for (var work : post.work()) {
            work.instance().finishGpuFrame(encoder);
            if (work.finishHost()) work.instance().finishPreparedFrame();
        }
        queuedGpuFrames = 0;
        boolean hasExtraSprites = !framePostOpaqueSpriteTasks.isEmpty();
        boolean hasExtraMeshes = !framePostOpaqueMeshTasks.isEmpty();
        List<NiagaraGpuSpriteFrame> sprites = hasExtraSprites ? new ArrayList<>(transparent.gpuSprites()) : transparent.gpuSprites();
        List<NiagaraGpuMeshFrame> meshes = hasExtraMeshes ? new ArrayList<>(transparent.gpuMeshes()) : transparent.gpuMeshes();
        if (hasExtraSprites) {
            if (postOpaqueSprites == null) postOpaqueSprites = new NiagaraGpuSpriteCollector(programs);
            sprites.addAll(postOpaqueSprites.prepare(encoder, framePostOpaqueSpriteTasks));
        }
        if (hasExtraMeshes) {
            if (postOpaqueMeshes == null) postOpaqueMeshes = new NiagaraGpuMeshCollector(programs);
            meshes.addAll(postOpaqueMeshes.prepare(encoder, framePostOpaqueMeshTasks, meshScenePrograms,
                    new com.kltyton.lumenfx.client.render.mesh.scene.MeshSceneView(orientation, view.camera(), transparent.motionView(),
                            view.delta()), gpuView));
        }
        framePostOpaqueSpriteTasks.clear();
        framePostOpaqueMeshTasks.clear();
        frameNaniteTasks.clear();
        prepared.lateTranslucency = new EffectFrame(transparent.generation(), transparent.time(), transparent.engineTime(),
                transparent.realTime(), transparent.meshes(), sprites, transparent.gpuRibbons(), transparent.deltaTime(),
                meshes, transparent.motionView(), transparent.lights(), transparent.decals(), transparent.pointLights(),
                transparent.volumes(), transparent.directionalLights());
        return prepared.lateTranslucency;
    }

    public PreparedFrame prepareFrame(FrameInput view, Matrix4fc modelView, RenderTarget target) {
        return prepareFrame(view, modelView, target, NiagaraSceneInputs.EMPTY);
    }

    /** Dispatches GPU work already prepared by the host's simulation phases. */
    public void dispatchNativeGpuStage(FrameInput input, Matrix4fc modelView, RenderTarget target,
                                       NiagaraGpuBatchDispatcher.TickStage stage) {
        try (var timing = niagaraFxBudget.render()) { dispatchPreparedNativeGpuStage(input, modelView, target, stage); }
    }

    private void dispatchPreparedNativeGpuStage(FrameInput input, Matrix4fc modelView, RenderTarget target,
                                               NiagaraGpuBatchDispatcher.TickStage stage) {
        requireClientThread();
        var host = hostSimulationFrame;
        var programs = niagaraPrograms;
        if (input != extractedHostInput || host == null || programs == null
                || input.level() != Minecraft.getInstance().level || input.generation() != resources.generation()) return;
        var batch = frameDispatchBatch;
        batch.clear();
        for (var effect : playback.activeEffects()) {
            if (!(effect instanceof FrameEffect entry)) continue;
            var instance = entry.instance();
            if (!instance.pendingHostFinish() || !instance.handle().isAlive() || !host.simulated().contains(instance)
                    || instance.gpuTickStage(programs, this::niagaraShaders) != stage) continue;
            batch.add(instance);
        }
        if (batch.isEmpty()) return;
        if (earlyGpuFrame == null) {
            beginGpuChannelFrame(input, programs);
            earlyGpuFrame = new EarlyGpuFrame(input, WorldHdrCapture.INSTANCE.renderFrameSequence(), new ArrayList<>());
        }
        if (earlyGpuFrame.input() != input) {
            batch.clear();
            throw new IllegalStateException("Overlapping native Niagara GPU frames");
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var view = prepareGpuView(encoder, input.level(), captureGpuView(input, modelView, target), target,
                NiagaraSceneInputs.EMPTY, 0);
        earlyGpuFrame.submitted().addAll(batch);
        EffectInstance.dispatchPreparedGpu(batch, encoder, programs, this::niagaraShaders, view, null);
        batch.clear();
    }

    public void endNativeGpuFrame(FrameInput input) {
        var frame = earlyGpuFrame;
        if (frame == null || frame.input() != input) return;
        earlyGpuFrame = null;
        if (niagaraPrograms != null) niagaraPrograms.abortAsyncTraceRenderFrame(input.level(), frame.renderFrame());
        frameDispatchBatch.clear();
        for (var instance : frame.submitted()) {
            if (instance.pendingHostFinish()) frameDispatchBatch.add(instance);
        }
        if (!frameDispatchBatch.isEmpty()) {
            retirePreparedInstances(frameDispatchBatch, true);
            frameDispatchBatch.clear();
        }
    }

    public PreparedFrame prepareFrame(FrameInput view, Matrix4fc modelView, RenderTarget target, NiagaraSceneInputs sceneInputs) {
        try (var timing = niagaraFxBudget.render()) { return prepareRenderFrame(view, modelView, target, sceneInputs); }
    }

    static boolean supportsPostOpaqueDepth(boolean composing, boolean linearTarget) {
        return composing && linearTarget;
    }

    private PreparedFrame prepareRenderFrame(FrameInput view, Matrix4fc modelView, RenderTarget target, NiagaraSceneInputs sceneInputs) {
        requireClientThread();
        if (view.level() != Minecraft.getInstance().level || view.generation() != resources.generation()) return null;
        prepareInstances(view.level());
        updateNiagaraScalability(view, view == extractedHostInput && hostSimulationFrame != null);
        frameInstances.clear();
        frameEmitters.clear();
        playback.collectFrameEffects(frameInstances, instance -> componentPool.reclaim(instance, view.engineTime()));
        var cameraEntity = Minecraft.getInstance().gameRenderer.mainCamera().entity();
        var viewActor = cameraEntity == null ? null : cameraEntity.getUUID();
        for (var instance : frameInstances) instance.captureViewActor(viewActor);
        var host = hostSimulationFrame;
        boolean preparedAtHost = view == extractedHostInput && host != null;
        Set<EffectInstance> prepared = preparedAtHost ? host.simulated() : Set.of();
        clearHostSimulationFrame();
        var normalPlan = new NiagaraTickClock.Plan(1, view.delta(), view.delta(), false);
        var late = frameLate;
        late.clear();
        boolean needCameraInterface = false;
        boolean anyNeedsAgeRenderPack = false;
        for (var instance : frameInstances) {
            if (prepared.contains(instance)) continue;
            late.add(instance);
            instance.tickPlan(normalPlan);
            instance.beginSimulationFrame(view.time());
            instance.trackWorldSimulation(worldSystemSimulation, NiagaraWorldSystemSimulation.Phase.Render, false);
            if (instance.needsCameraInterface()) needCameraInterface = true;
            if (instance.needsAgeRenderPack()) anyNeedsAgeRenderPack = true;
        }
        worldSystemSimulation.beginTick(NiagaraWorldSystemSimulation.Phase.Render, view.delta());
        NiagaraCameraFrame cameraFrame = needCameraInterface ? captureInterfaceCamera(view, target) : null;
        warmUp(late, view, cameraFrame);
        var manualCaptures = frameManualCaptures;
        manualCaptures.clear();
        late.removeIf(instance -> {
            var capture = instance.manualCacheRequest();
            if (capture == null || !instance.beginManualCache(capture)) return false;
            manualCaptures.put(instance, capture);
            return true;
        });
        Vec3 cameraPosition = view.camera();
        float qx = view.qx(), qy = view.qy(), qz = view.qz(), qw = view.qw();
        scratchOrientation.set(qx, qy, qz, qw);
        com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView.Snapshot motionView = null;
        int frameCaps = 0;
        for (var instance : frameInstances) frameCaps |= definitionCapabilities(instance.definition());
        boolean nanitePreviousView = (frameCaps & CAP_NANITE_PREVIOUS_VIEW) != 0;
        boolean unityCameraVelocity = (frameCaps & CAP_UNITY_CAMERA_VELOCITY) != 0;
        if (WorldHdrCapture.INSTANCE.particleMotionActive() || nanitePreviousView || unityCameraVelocity) {
            var minecraft = Minecraft.getInstance();
            var hdr = WorldHdrCapture.INSTANCE;
            hdr.copyProjection(scratchProjection);
            motionView = particleMotionView.capture(view.level(), cameraEntity,
                    hdr.cameraCutVersion(), cameraPosition, scratchOrientation, scratchProjection, modelView,
                    view.realTime(), view.engineTime(), hdr.firstPersonMaterialView(), hdr.renderFrameSequence(),
                    minecraft.options.getCameraType());
        }
        Vec3 cameraVelocity = unityCameraVelocity && motionView != null ? motionView.cameraVelocity() : Vec3.ZERO;
        com.kltyton.lumenfx.client.render.mesh.MeshView meshView = null;
        float ribbonLodDistanceFactor = 1;
        if ((frameCaps & CAP_HAS_RIBBON) != 0) {
            WorldHdrCapture.INSTANCE.copyUnjitteredProjection(scratchProjection);
            ribbonLodDistanceFactor = com.kltyton.lumenfx.client.render.scene.view.SourceViewLod.factor(scratchProjection);
        }
        if ((frameCaps & CAP_NEEDS_MESH_VIEW) != 0) {
            var meshProjection = new org.joml.Matrix4f();
            WorldHdrCapture.INSTANCE.copyCameraProjection(meshProjection);
            float meshLodDistance = com.kltyton.lumenfx.client.render.scene.view.SourceViewLod.factor(meshProjection);
            meshView = new com.kltyton.lumenfx.client.render.mesh.MeshView(meshProjection, modelView,
                    com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), meshLodDistance, meshPlatform());
        }
        boolean computeRibbonsAvailable = niagaraPrograms != null ? niagaraPrograms.supportsRibbonGeneration()
                : resources.niagaraDrawShaders() != null && resources.niagaraIdShaders() != null
                        && resources.niagaraSortShaders() != null && !resources.niagaraSortShaders().ribbonStages().isEmpty()
                        && ComputeRuntime.supportsStorageBufferKernels(RenderSystem.getDevice());
        boolean computeSortingAvailable = niagaraPrograms != null
                && ComputeRuntime.supportsStorageBufferKernels(RenderSystem.getDevice());
        for (var builder : builders) {
            builder.begin(cameraPosition, scratchOrientation, meshView, ribbonLodDistanceFactor, modelView,
                    view.delta(), cameraVelocity);
            builder.computeRibbonsAvailable(computeRibbonsAvailable);
            builder.computeSortingAvailable(computeSortingAvailable);
        }
        int partitions = simulateFrameSteps(late, view, cameraFrame, true, false, false);
        boolean gpuChannelsPrepared;
        try {
            gpuChannelsPrepared = !manualCaptures.isEmpty()
                    && runManualCaptures(manualCaptures, view, cameraFrame, modelView, target, sceneInputs);
        } catch (RuntimeException | Error failure) {
            late.clear();
            manualCaptures.clear();
            try { retirePreparedInstances(frameInstances, true); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (!prepared.isEmpty() || !manualCaptures.isEmpty() || anyNeedsAgeRenderPack) {
            frameEmitters.clear();
            for (var instance : frameInstances) {
                if (prepared.contains(instance)) frameEmitters.addAll(instance.packPreparedFrame());
                else if (manualCaptures.containsKey(instance)) frameEmitters.addAll(instance.prepareRenderFrame(view.time()));
                else if (instance.needsAgeRenderPack()) frameEmitters.addAll(instance.packPreparedFrame());
            }
            partitions = Math.max(partitions, executeEmitters(true));
        }
        late.clear();
        manualCaptures.clear();
        frameEmitters.clear();
        for (var instance : frameInstances) instance.prepareCullProxyViews(frameEmitters);
        partitions = Math.max(partitions, executeEmitters(true));
        for (var instance : frameInstances) instance.publishSimulationInputs();
        if (meshView != null) for (var instance : frameInstances)
            instance.requestStaticMeshLods(meshView, cameraPosition, view.engineTime());
        frameMeshes.clear();
        frameCpuMeshSources.clear();
        frameCpuRibbonSources.clear();
        frameCpuSpriteSources.clear();
        frameCpuSplatSources.clear();
        frameLights.clear();
        frameDecals.clear();
        frameVolumes.clear();
        framePointLights.clear();
        frameDirectionalLights.clear();
        var componentMeshes = frameComponentMeshes;
        componentMeshes.clear();
        for (var instance : frameInstances) {
            instance.collectPointLights(framePointLights);
            instance.collectDirectionalLights(frameDirectionalLights);
            instance.collectDecals(frameDecals);
            instance.collectComponentMeshes(viewActor, componentMeshes);
        }
        if (!componentMeshes.isEmpty()) {
            for (var component : componentMeshes) if (component.visible()) builders[0].add(component);
            partitions = Math.max(partitions, 1);
        }
        boolean movingShadowLights = false;
        for (var light : framePointLights) {
            if (light.data().castDynamicShadow()) {
                movingShadowLights = true;
                break;
            }
        }
        if (!movingShadowLights) {
            for (var light : frameDirectionalLights) {
                if ((light.lightingChannels() != 0 || light.affectsTranslucency()
                        || light.castVolumetricShadow() && light.volumetricScattering() > 0) && light.cascades() != null
                        && (light.cascades().count() > 0 && light.cascades().distance() > 0 || light.cascades().farCount() > 0)) {
                    movingShadowLights = true;
                    break;
                }
            }
        }
        var cpuShadowCasters = frameCpuShadowCasters;
        cpuShadowCasters.clear();
        var gpuShadowCasters = movingShadowLights ? frameGpuShadowCasters : null;
        if (gpuShadowCasters != null) gpuShadowCasters.clear();
        drainPointLightRetirements();
        for (int i = 0; i < partitions; i++) {
            frameMeshes.addAll(builders[i].finish());
            frameCpuMeshSources.addAll(builders[i].sceneMeshes());
            frameCpuRibbonSources.addAll(builders[i].computeRibbons());
            frameCpuSpriteSources.addAll(builders[i].computeSprites());
            frameLights.addAll(builders[i].lights());
            frameDecals.addAll(builders[i].decals());
            frameVolumes.addAll(builders[i].volumes());
            frameCpuSplatSources.addAll(builders[i].splats());
            if (movingShadowLights) cpuShadowCasters.addAll(builders[i].shadowSources());
        }
        for (int i = partitions; i < builders.length; i++) builders[i].clear();
        frameGpuTasks.clear();
        frameGpuMeshTasks.clear();
        List<NiagaraGpuSpriteFrame> gpuSprites = List.of();
        List<com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshFrame> gpuMeshes = List.of();
        List<NiagaraGaussianSplatDraw> cpuSplatDraws = List.of();
        var gpuRibbons = frameGpuRibbons;
        gpuRibbons.clear();
        var naniteFrame = frameNanitePrepared;
        naniteFrame.clear();
        NiagaraStatelessDataCache statelessData = null;
        long statelessFrame = 0;
        if (niagaraPrograms != null) {
            int requests = 0;
            for (EffectInstance instance : frameInstances) {
                NiagaraDefinition definition = instance.definition().niagara();
                if (definition == null) continue;
                for (var emitter : definition.emitters())
                    if (emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation stateless
                            && stateless.target().equals("GPUComputeSim")) requests = Math.incrementExact(requests);
            }
            statelessData = niagaraPrograms.beginStatelessFrame(requests);
            if (statelessData != null) statelessFrame = statelessData.frame();
        }
        var deferredGpu = frameDeferredGpu;
        deferredGpu.clear();
        PostOpaqueFrame postOpaque = null;
        NiagaraGpuView.Frame currentGpuView = null;
        long naniteSequence = naniteRenderer == null ? 0 : ++naniteFrameSequence;
        try {
            CommandEncoder encoder = null;
            if (niagaraPrograms != null) {
                encoder = RenderSystem.getDevice().createCommandEncoder();
                if (!gpuChannelsPrepared) beginGpuChannelFrame(view, niagaraPrograms);
                currentGpuView = captureGpuView(view, modelView, target);
                var hdr = WorldHdrCapture.INSTANCE;
                var previousView = gpuViewLevel == view.level() && gpuViewCutVersion == hdr.cameraCutVersion() ? previousGpuView : null;
                List<NaniteInstanceCollector.GpuInput> naniteTasks = null;
                if (naniteInstances != null) {
                    frameNaniteTasks.clear();
                    naniteTasks = frameNaniteTasks;
                }
                boolean postOpaqueScene = supportsPostOpaqueDepth(hdr.composing(), target == hdr.linearTarget());
                if (postOpaqueScene) {
                    for (var instance : frameInstances)
                        if (instance.requiresPostOpaque(niagaraPrograms, this::niagaraShaders)) deferredGpu.add(instance);
                    for (var instance : deferredGpu) instance.retainPreviousGpuRenderData(encoder, niagaraPrograms, this::niagaraShaders);
                }
                boolean lowLatency = Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera()
                        != net.minecraft.world.level.material.FogType.WATER;
                if (!deferredGpu.isEmpty()) {
                    var work = new ArrayList<PostOpaqueWork>(deferredGpu.size());
                    for (var instance : deferredGpu) {
                        work.add(new PostOpaqueWork(instance, !prepared.contains(instance) || instance.pendingHostFinish()));
                    }
                    postOpaque = new PostOpaqueFrame(work, currentGpuView, previousView, meshView, ribbonLodDistanceFactor, lowLatency);
                }
                int sceneReads = 0;
                for (var instance : frameInstances)
                    if (!deferredGpu.contains(instance)) sceneReads |= instance.sceneReads(niagaraPrograms, this::niagaraShaders);
                var gpuView = prepareGpuView(encoder, view.level(), currentGpuView, target, sceneInputs, sceneReads);
                for (var instance : frameInstances) if (instance.hasGpuEmitters())
                    instance.prepareAnalyticRenderers(encoder, niagaraPrograms);
                if (statelessData != null) statelessData.flush(statelessFrame, niagaraPrograms);
                for (var stage : NiagaraGpuBatchDispatcher.TickStage.values()) {
                    frameDispatchBatch.clear();
                    for (var instance : frameInstances) {
                        if (!deferredGpu.contains(instance) && instance.gpuTickStage(niagaraPrograms, this::niagaraShaders) == stage)
                            frameDispatchBatch.add(instance);
                    }
                    EffectInstance.dispatchPreparedGpu(frameDispatchBatch, encoder, niagaraPrograms, this::niagaraShaders, gpuView,
                            target.getDepthTextureView());
                }
                frameDispatchBatch.clear();
                for (var instance : frameInstances) {
                    if (!instance.hasGpuEmitters()) continue;
                    instance.appendGpuRenderers(encoder, niagaraPrograms, this::niagaraShaders, cameraPosition, scratchOrientation,
                            instance.gpuSortDepth(cameraPosition, meshView), false, false, false, ribbonLodDistanceFactor, gpuView, target,
                            frameGpuTasks, gpuRibbons, meshView, frameGpuMeshTasks, frameLights, frameDecals, frameVolumes, gpuShadowCasters,
                            deferredGpu.contains(instance) ? EffectInstance.GpuRenderPhase.BEFORE_POST_OPAQUE : EffectInstance.GpuRenderPhase.ALL,
                            lowLatency, naniteTasks);
                }
                if (naniteInstances != null && target.getDepthTextureView() != null) {
                    Vec3 previousCamera = previousCamera(previousView, cameraPosition);
                    naniteFrame.addAll(naniteInstances.prepareCpu(encoder, frameCpuMeshSources, cameraPosition, previousCamera));
                    naniteFrame.addAll(naniteInstances.prepareGpu(encoder, naniteTasks, previousCamera));
                }
                for (var instance : frameInstances)
                    if (instance.hasGpuEmitters() && !deferredGpu.contains(instance)) instance.finishGpuFrame(encoder);
                if (deferredGpu.isEmpty()) queuedGpuFrames = 0;
                if (!frameCpuMeshSources.isEmpty() && cpuMeshCollector == null)
                    cpuMeshCollector = new com.kltyton.lumenfx.client.render.mesh.CpuMeshSceneCollector(niagaraPrograms);
                if (cpuMeshCollector != null)
                    cpuMeshCollector.append(encoder, frameCpuMeshSources, cameraPosition, scratchOrientation,
                            meshView, frameGpuMeshTasks);
                if (!frameCpuSpriteSources.isEmpty() && cpuSpriteCompute == null)
                    cpuSpriteCompute = new NiagaraCpuSpriteCompute(niagaraPrograms);
                if (cpuSpriteCompute != null)
                    cpuSpriteCompute.append(encoder, frameCpuSpriteSources, cameraPosition, scratchOrientation,
                            meshView, frameGpuTasks, gpuShadowCasters);
                if (naniteRenderer != null) {
                    var selected = naniteRenderer.select(encoder, view.level(), naniteSequence, naniteFrame);
                    naniteFrame.clear();
                    naniteFrame.addAll(selected);
                    NaniteWorldRenderer.selectFallbacks(frameGpuMeshTasks, selected);
                }
                if (!frameGpuTasks.isEmpty()) {
                    if (gpuSpriteCollector == null) gpuSpriteCollector = new NiagaraGpuSpriteCollector(niagaraPrograms);
                    gpuSprites = gpuSpriteCollector.prepare(encoder, frameGpuTasks);
                }
                if (!frameGpuMeshTasks.isEmpty()) {
                    if (gpuMeshCollector == null) gpuMeshCollector = new com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector(niagaraPrograms);
                    gpuMeshes = gpuMeshCollector.prepare(encoder, frameGpuMeshTasks, meshScenePrograms,
                            new com.kltyton.lumenfx.client.render.mesh.scene.MeshSceneView(scratchOrientation, cameraPosition, motionView, view.delta()), gpuView);
                }
                if (!frameCpuSplatSources.isEmpty() || cpuSplatCollector != null) {
                    if (cpuSplatCollector == null) cpuSplatCollector = new NiagaraCpuSplatCollector(niagaraPrograms);
                    cpuSplatDraws = cpuSplatCollector.prepare(encoder, frameCpuSplatSources);
                    frameSplatKeys.clear();
                    for (var source : frameCpuSplatSources) frameSplatKeys.add(source.key());
                    cpuSplatCollector.retainActive(frameSplatKeys);
                    frameSplatKeys.clear();
                }
                frameNaniteTasks.clear();
            }
            if (!frameCpuRibbonSources.isEmpty() || cpuRibbonCompute != null) {
                if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
                if (cpuRibbonCompute == null) {
                    var programs = niagaraPrograms;
                    boolean ownsPrograms = programs == null;
                    if (ownsPrograms) programs = new NiagaraGpuPrograms(Map.of(), resources.niagaraDrawShaders(),
                            resources.niagaraSortShaders(), resources.niagaraIdShaders());
                    cpuRibbonCompute = new NiagaraCpuRibbonCompute(programs, ownsPrograms);
                }
                cpuRibbonCompute.append(encoder, frameCpuRibbonSources, cameraPosition, meshView, ribbonLodDistanceFactor,
                        gpuRibbons, gpuShadowCasters);
            }
            for (var instance : frameInstances)
                if (!deferredGpu.contains(instance) && (!prepared.contains(instance) || instance.pendingHostFinish())) instance.finishPreparedFrame();
            deferredGpu.clear();
            var componentShadowCasters = frameComponentShadowCasters;
            componentShadowCasters.clear();
            if (movingShadowLights && !componentMeshes.isEmpty()) {
                for (var component : componentMeshes) {
                    if (component.lighting().castsDynamicShadow()) componentShadowCasters.add(component);
                }
            }
            var result = new PreparedFrame(this, view,
                    new EffectFrame(view.generation(), view.time(), view.engineTime(), view.realTime(), frameMeshes, gpuSprites,
                            gpuRibbons, view.delta(), gpuMeshes, motionView, frameLights, frameDecals,
                            framePointLights, frameVolumes, frameDirectionalLights), frameInstances, statelessData, statelessFrame, cpuShadowCasters,
                    componentShadowCasters,
                    gpuShadowCasters, cpuSplatDraws, sceneInputs, postOpaque, currentGpuView, naniteFrame, naniteSequence);
            componentMeshes.clear();
            componentShadowCasters.clear();
            cpuShadowCasters.clear();
            if (gpuShadowCasters != null) gpuShadowCasters.clear();
            gpuRibbons.clear();
            naniteFrame.clear();
            return result;
        } catch (RuntimeException | Error failure) {
            if (niagaraPrograms != null)
                niagaraPrograms.abortAsyncTraceRenderFrame(view.level(), WorldHdrCapture.INSTANCE.renderFrameSequence());
            deferredGpu.clear();
            componentMeshes.clear();
            cpuShadowCasters.clear();
            if (gpuShadowCasters != null) gpuShadowCasters.clear();
            gpuRibbons.clear();
            naniteFrame.clear();
            frameNaniteTasks.clear();
            frameSplatKeys.clear();
            frameDispatchBatch.clear();
            try { retirePreparedInstances(frameInstances, true); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            if (statelessData != null) try { statelessData.abortFrame(statelessFrame); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static String meshPlatform() {
        String os = System.getProperty("os.name");
        return os.startsWith("Windows") ? "Windows" : os.startsWith("Mac") ? "Mac" : os;
    }

    private static Vec3 previousCamera(NiagaraGpuView.@Nullable Frame frame, Vec3 current) {
        if (frame == null) return current;
        var camera = frame.camera();
        return new Vec3(camera.x() * .01, camera.z() * .01, camera.y() * .01);
    }

    private boolean runManualCaptures(Map<EffectInstance, NiagaraCacheCapture> requests, FrameInput view,
                                      @Nullable NiagaraCameraFrame cameraFrame, Matrix4fc modelView,
                                      RenderTarget target, NiagaraSceneInputs sceneInputs) {
        var programs = niagaraPrograms;
        boolean hasGpu = programs != null && requests.keySet().stream().anyMatch(EffectInstance::hasGpuEmitters);
        var encoder = hasGpu ? RenderSystem.getDevice().createCommandEncoder() : null;
        var gpuFrame = hasGpu ? captureGpuView(view, modelView, target) : null;
        var gpuViews = new HashMap<Integer, NiagaraGpuView>();
        IntFunction<NiagaraGpuView> scene = reads -> gpuViews.computeIfAbsent(reads, mask ->
                prepareGpuView(Objects.requireNonNull(encoder), view.level(), Objects.requireNonNull(gpuFrame), target, sceneInputs, mask));
        if (hasGpu) {
            var gpu = Objects.requireNonNull(programs);
            beginGpuChannelFrame(view, gpu);
            if (requests.keySet().stream().anyMatch(EffectInstance::requiresCurrentGpuDataChannel)) {
                for (var instance : frameInstances) {
                    if (requests.containsKey(instance) || instance.requiresCurrentGpuDataChannel() || instance.pendingGpuSteps() == 0) continue;
                    int reads = instance.sceneReads(gpu, this::niagaraShaders);
                    EffectInstance.dispatchPreparedGpu(List.of(instance), Objects.requireNonNull(encoder), gpu, this::niagaraShaders, scene.apply(reads),
                            target.getDepthTextureView());
                }
            }
        }

        var ordered = requests.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().requiresCurrentGpuDataChannel())).toList();
        for (var entry : ordered) {
            var instance = entry.getKey();
            var capture = entry.getValue();
            try {
                if (instance.hasGpuEmitters() && programs == null) {
                    capture.result().completeExceptionally(new IllegalStateException("Manual Niagara capture requires the GPU simulation programs"));
                    continue;
                }
                flushManualGpu(instance, encoder, programs, scene, target);
                if (!instance.canAdvanceManualCache()) {
                    instance.captureManualCache(capture, encoder, programs);
                    instance.finishManualCache(capture);
                    continue;
                }
                long stepBudget = !capture.currentFrame() && capture.options().frameLimit() == 0
                        ? UNBOUNDED_CACHE_STEPS_PER_FRAME : Long.MAX_VALUE;
                while (stepBudget-- > 0 && instance.manualCacheRequest() == capture && instance.handle().isAlive()) {
                    frameEmitters.clear();
                    frameEmitters.addAll(instance.prepareManualCacheStep(capture, view.time(), view.engineTime(),
                            view.realTime(), view.camera(), cameraFrame));
                    executeEmitters(false);
                    instance.finishSimulationStep(view.level(), programs);
                    flushManualGpu(instance, encoder, programs, scene, target);
                    instance.captureManualCache(capture, encoder, programs);
                    if (!instance.canAdvanceManualCache()) break;
                }
                if (capture.finishing()) instance.finishManualCache(capture);
            } catch (RuntimeException | Error failure) {
                capture.result().completeExceptionally(failure);
                throw failure;
            } finally {
                instance.endManualCacheFrame();
            }
        }
        return hasGpu;
    }

    private void flushManualGpu(EffectInstance instance, @Nullable CommandEncoder encoder,
                                @Nullable NiagaraGpuPrograms programs, IntFunction<NiagaraGpuView> scene,
                                RenderTarget target) {
        if (instance.pendingGpuSteps() == 0) return;
        var gpu = Objects.requireNonNull(programs);
        int reads = instance.sceneReads(gpu, this::niagaraShaders);
        instance.flushManualCacheGpu(Objects.requireNonNull(encoder), gpu, this::niagaraShaders, scene.apply(reads),
                target.getDepthTextureView());
    }

    private void beginGpuChannelFrame(FrameInput view, NiagaraGpuPrograms programs) {
        if (earlyGpuFrame != null && earlyGpuFrame.input() == view) return;
        var environment = NiagaraPlatforms.snapshot();
        programs.captureSharedMeshFields(view.level());
        programs.beginAsyncTraceRenderFrame(view.level(), WorldHdrCapture.INSTANCE.renderFrameSequence());
        NiagaraGpuDataChannelWorlds.beginFrame(view.level(), programs,
                environment.dataChannelsRetainGpu(), environment.dataChannelsAutoUploadGpuSpawns());
    }

    private static NiagaraGpuView.Frame captureGpuView(FrameInput view, Matrix4fc modelView, RenderTarget target) {
        var hdr = WorldHdrCapture.INSTANCE;
        var projection = new Matrix4f();
        var unjittered = new Matrix4f();
        var baseProjection = new Matrix4f();
        hdr.copyProjection(projection);
        hdr.copyUnjitteredProjection(unjittered);
        hdr.copyCameraProjection(baseProjection);
        var orientation = new Quaternionf(view.qx(), view.qy(), view.qz(), view.qw());
        var camera = NiagaraCameraFrame.capture(view.camera(), orientation, horizontalFov(baseProjection.m00()),
                target.width, target.height, hdr.nearClipCm());
        return new NiagaraGpuView.Frame(camera, modelView, projection, unjittered, baseProjection,
                target.width, target.height, view.engineTime(), view.realTime(), view.delta(), view.realDelta(),
                hdr.materialResolutionFraction(), hdr.temporalAAParameters());
    }

    private NiagaraGpuView prepareGpuView(CommandEncoder encoder, ClientLevel level, NiagaraGpuView.Frame frame,
                                          RenderTarget target, NiagaraSceneInputs inputs, int reads) {
        var hdr = WorldHdrCapture.INSTANCE;
        var previous = gpuViewLevel == level && gpuViewCutVersion == hdr.cameraCutVersion() ? previousGpuView : null;
        return prepareGpuView(encoder, level, frame, previous, target, inputs, reads);
    }

    private NiagaraGpuView prepareGpuView(CommandEncoder encoder, ClientLevel level, NiagaraGpuView.Frame frame,
                                          NiagaraGpuView.@Nullable Frame previous, RenderTarget target, NiagaraSceneInputs inputs, int reads) {
        var hdr = WorldHdrCapture.INSTANCE;
        if ((reads & NiagaraGpuBatchDispatcher.READ_GLOBAL_DISTANCE_FIELD) != 0 && inputs.globalDistanceField() == null)
            inputs = inputs.withGlobalDistanceField(worldDistanceField.prepare(level, frame.camera(),
                    NiagaraPlatforms.snapshot(), Minecraft.getInstance().options.getEffectiveRenderDistance(), hdr.cameraCutVersion()));
        if ((reads & NiagaraGpuBatchDispatcher.READ_CAPTURED_GBUFFER) != 0) {
            inputs = resolveSceneGBuffer(encoder, frame.modelView(), frame.projection(), target, inputs);
        }
        if ((reads & NiagaraGpuBatchDispatcher.READ_PARTIAL_DEPTH) != 0)
            inputs = inputs.withTextureIfAbsent(NiagaraSceneInputs.Texture.PARTIAL_DEPTH, hdr.partialDepth(target));
        if ((reads & NiagaraGpuBatchDispatcher.READ_SCENE_COLOR) != 0)
            inputs = inputs.withTextureIfAbsent(NiagaraSceneInputs.Texture.SCENE_COLOR, target.getColorTextureView());
        if ((reads & NiagaraGpuBatchDispatcher.READ_SCENE_VELOCITY) != 0
                && !inputs.textures().containsKey(NiagaraSceneInputs.Texture.GBUFFER_VELOCITY))
            inputs = inputs.withTextureIfAbsent(NiagaraSceneInputs.Texture.GBUFFER_VELOCITY,
                    hdr.prepareNiagaraSceneVelocity(target.getDepthTextureView()));
        return new NiagaraGpuView(frame, previous, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), inputs,
                hdr.renderFrameSequence());
    }

    private NiagaraSceneInputs resolveSceneGBuffer(CommandEncoder encoder, Matrix4fc modelView, Matrix4fc projection,
                                                   RenderTarget target, NiagaraSceneInputs inputs) {
        var hdr = WorldHdrCapture.INSTANCE;
        var surfaces = hdr.sceneSurfaces();
        if (surfaces != null && surfaces.width() == target.width && surfaces.height() == target.height) {
            return niagaraSceneGBuffer.resolve(encoder, surfaces, modelView, inputs, hdr.particleGBuffer());
        }
        var shaderpack = hdr.shaderpackFrame();
        if (shaderpack != null) {
            var hostSurfaces = hdr.resolveShaderpackSurfaces(shaderpack, projection);
            if (hostSurfaces.width() == target.width && hostSurfaces.height() == target.height) {
                var encoding = hostSurfaces.surfaceEncoding() != null
                        ? hostSurfaces.surfaceEncoding() : shaderpack.colourEncoding();
                return niagaraSceneGBuffer.resolveShaderpack(encoder, hostSurfaces, modelView, encoding,
                        inputs, hdr.particleGBuffer(), hdr.externalPhotonOpaqueMask());
            }
        }
        return inputs;
    }

    private static @Nullable NiagaraCameraFrame captureInterfaceCamera(FrameInput view, RenderTarget target) {
        var source = view.cameraProjection();
        if (source == null) {
            // Explicit FrameInput callers predating the extraction snapshot use the supplied render view.
            var projection = new org.joml.Matrix4f();
            WorldHdrCapture.INSTANCE.copyCameraProjection(projection);
            source = new CameraProjection(true, projection.m00(), WorldHdrCapture.INSTANCE.nearClipCm(), target.width, target.height);
        }
        return captureInterfaceCamera(view, source);
    }

    private static @Nullable NiagaraCameraFrame captureInterfaceCamera(FrameInput view, CameraProjection source) {
        if (!source.initialized()) return null;
        return NiagaraCameraFrame.capture(view.camera(), new Quaternionf(view.qx(), view.qy(), view.qz(), view.qw()),
                horizontalFov(source.horizontalScale()), source.width(), source.height(), source.nearClipCm());
    }

    private static float horizontalFov(float projectionScale) {
        return (float) Math.toDegrees(2 * Math.atan(1.0 / Math.abs(projectionScale)));
    }

    private int executeEmitters(boolean pack) {
        int count = frameEmitters.size();
        if (count == 0) return 0;
        int partitions = workers.partitions(count), maxGroup = 0;
        boolean hasOwnerWork = false;
        for (var emitter : frameEmitters) {
            maxGroup = Math.max(maxGroup, emitter.group());
            hasOwnerWork |= emitter.requiresOwnerThread();
        }
        boolean separateOwnerWork = partitions > 1 && hasOwnerWork;
        for (int group = 0; group <= maxGroup; group++) {
            int currentGroup = group;
            var concurrentTime = niagaraFxBudget.trackingWorkers() ? new LongAdder() : null;
            long start = concurrentTime == null ? 0 : System.nanoTime();
            try {
                workers.executeRanges(0, count, partitions, (partition, from, to) -> {
                    long partitionStart = concurrentTime == null ? 0 : System.nanoTime();
                    try {
                        for (int i = from; i < to; i++) {
                            var emitter = frameEmitters.get(i);
                            if (emitter.group() != currentGroup) continue;
                            if (separateOwnerWork && emitter.requiresOwnerThread()) continue;
                            if (pack) emitter.updateForStep(builders[partition]);
                            else emitter.updateSimulation();
                        }
                    } finally {
                        if (concurrentTime != null) concurrentTime.add(System.nanoTime() - partitionStart);
                    }
                });
            } finally {
                if (concurrentTime != null) niagaraFxBudget.workers(System.nanoTime() - start, concurrentTime.sum());
            }
            if (separateOwnerWork) for (int partition = 0; partition < partitions; partition++) {
                for (int i = count * partition / partitions; i < count * (partition + 1) / partitions; i++) {
                    var emitter = frameEmitters.get(i);
                    if (emitter.group() != currentGroup || !emitter.requiresOwnerThread()) continue;
                    if (pack) emitter.updateForStep(builders[partition]);
                    else emitter.updateSimulation();
                }
            }
        }
        return partitions;
    }

    private void prepareInstances(ClientLevel level) {
        long currentGeneration = resources.generation();
        int particleGeneration = VanillaParticleHost.INSTANCE.generation();
        double worldTime = clockLevel == level ? simulationTime - gameClockStart : 0;
        boolean prime = componentPool.update(NiagaraPlatforms.snapshot());
        if (activeScope == null || !activeScope.current(level, currentGeneration, particleGeneration)) {
            ClientLevel previousLevel = activeScope == null ? null : activeScope.level();
            if (previousLevel != null && previousLevel != level) {
                NiagaraPhysicsFieldWorlds.publish(previousLevel, null);
                NiagaraWaterWorlds.publish(previousLevel, null);
                NiagaraChaosWorlds.remove(previousLevel);
                NiagaraParameterCollections.clearWorld(previousLevel); MaterialCollectionWorlds.clearWorld(previousLevel);
                NiagaraGpuParticleExportReadbacks.clear(previousLevel);
                NiagaraGpuCacheReadbacks.clear(previousLevel);
                NiagaraParticleExportCallbacks.clear(previousLevel);
                clearDataChannelWorld(previousLevel);
                if (niagaraPrograms != null) {
                    niagaraPrograms.sharedMeshFields().clear(previousLevel);
                    niagaraPrograms.clearAsyncTraces(previousLevel);
                    niagaraPrograms.physicsFields().close();
                }
                MaterialCollectionUniforms.INSTANCE.close();
                worldDistanceField.close();
                resources.virtualTextures().clearWorld(previousLevel);
            }
            worldSystemSimulation.clear();
            niagaraScalability.clear();
            if (previousLevel != level) niagaraScalability.setMode(CullingMode.Enabled);
            niagaraRenderVisibility.close();
            niagaraFxBudget.clear();
            componentPool.clear();
            prime = true;
            playback.discardFrameEffects();
            if (cpuRibbonCompute != null) {
                RenderSystem.queueFencedTask(cpuRibbonCompute::close);
                cpuRibbonCompute = null;
                frameCpuRibbonSources.clear();
            }
            if (cpuSpriteCompute != null) {
                RenderSystem.queueFencedTask(cpuSpriteCompute::close);
                cpuSpriteCompute = null;
                frameCpuSpriteSources.clear();
            }
            NiagaraManagedSceneCaptures.INSTANCE.close();
            for (var builder : builders) builder.clear();
            activeScope = new Scope(level, currentGeneration, particleGeneration);
        }
        if (prime) primeNiagaraComponents(level, worldTime);
        List<Pending> deferred = null;
        try {
            for (Pending item; (item = pending.poll()) != null;) {
                if (!item.scope().current(level, currentGeneration, particleGeneration)
                        || !item.handle().isAlive() || item.handle().stopping()) {
                    item.handle().invalidate(); continue;
                }
                if (item.sequence() > sceneRegistrationBoundary && (resources.particleViews().containsKey(item.definition())
                        || hasSceneRenderers(item.definition(), item.handle(), NATIVE_OPAQUE_SCENE | CAP_GAMMA_BLENDING))) {
                    if (deferred == null) deferred = new ArrayList<>();
                    deferred.add(item); continue;
                }
                try {
                    var control = item.handle().niagaraComponent();
                    boolean pooled = control != null && control.pooling() != Pooling.None
                            && componentPool.canPool(Objects.requireNonNull(item.definition().niagara()).componentPool());
                    var instance = pooled ? componentPool.acquire(item.definition(), item.handle(), item.seed(), worldTime) : null;
                    if (instance == null) {
                        instance = new EffectInstance(item.definition(), item.handle(), item.seed(), level,
                                resources.virtualTextures().bindings(item.effect()), resources.virtualTextures().runtimeBindings(level),
                                dynamicMaterialObjects);
                        instance.pooledLease(pooled);
                    }
                    playback.addFrameEffect(instance);
                } catch (RuntimeException | Error failure) {
                    item.handle().invalidate();
                    throw failure;
                }
            }
        } finally { if (deferred != null) pending.addAll(deferred); }
    }

    private void primeNiagaraComponents(ClientLevel level, double time) {
        for (var entry : resources.definitions().entrySet()) {
            var definition = entry.getValue();
            if (definition.niagara() == null || definition.niagara().componentPool().primedMinimum() == 0) continue;
            componentPool.prime(definition, () -> {
                var control = new NiagaraComponentControl(false, Pooling.ManualRelease);
                var handle = new EffectHandle(Vec3.ZERO, control, definition.niagara().component().customPrimitiveData());
                return new EffectInstance(definition, handle, 0, level, resources.virtualTextures().bindings(entry.getKey()),
                        resources.virtualTextures().runtimeBindings(level), dynamicMaterialObjects);
            }, time);
        }
    }

    public void finishClientTick(ClientLevel level) {
        requireClientThread();
        playback.afterParticleTick(level);
    }

    public void disconnected() {
        requireClientThread();
        try {
            playback.discardAll();
        } finally {
            try {
                disconnectWorld();
            } finally {
                workers.close();
            }
        }
    }

    private void disconnectWorld() {
        NiagaraPhysicsFieldWorlds.clear();
        NiagaraWaterWorlds.clear();
        NiagaraChaosWorlds.clear();
        clearHostSimulationFrame();
        NiagaraParameterCollections.clearWorlds();
        MaterialCollectionWorlds.clearWorlds();
        NiagaraSkeletalMeshWorlds.clear();
        if (clockLevel != null) NiagaraStaticMeshWorlds.clear(clockLevel);
        if (activeScope != null && activeScope.level() != clockLevel) NiagaraStaticMeshWorlds.clear(activeScope.level());
        MaterialCollectionUniforms.INSTANCE.close();
        if (activeScope == null && pending.isEmpty() && !resources.virtualTextures().hasResidency()) return;
        try (var teardown = new EffectTeardown()) {
            teardown.run(ComponentWorlds::clear);
            for (Pending value; (value = pending.poll()) != null;) value.handle().invalidate();
            teardown.run(this::releaseRenderResources);
            for (var builder : builders) builder.clear();
            frameInstances.clear();
            frameEmitters.clear();
            frameMeshes.clear();
            frameGpuTasks.clear();
            frameGpuMeshTasks.clear();
            activeScope = null;
            frameLights.clear();
            frameDecals.clear();
            frameVolumes.clear();
            framePointLights.clear();
            frameDirectionalLights.clear();
            particleMotionView.reset();
            clockLevel = null;
        }
    }

    public boolean requestVirtualTexture(Identifier effect, String source, Feedback region) {
        requireClientThread();
        return resources.virtualTextures().request(effect, source, region);
    }

    public @Nullable NiagaraStreamingVirtualTexture streamedVirtualTexture(Identifier effect, String source) {
        requireClientThread();
        return resources.virtualTextures().bindings(effect).get(source);
    }

    public RuntimeVirtualTexturePlacement authoredRuntimeVirtualTexture(Identifier effect, String component) {
        requireClientThread();
        return resources.virtualTextures().placement(effect, component);
    }

    public @Nullable RuntimeVirtualTextureVolume placeAuthoredRuntimeVirtualTexture(ClientLevel level, Identifier effect,
            String component, SceneProfile profile, int tileSizeBias, int tileCountBias, RuntimeVirtualTexturePageRenderer renderer) {
        requireClientThread();
        if (level != Minecraft.getInstance().level) throw new IllegalArgumentException("Runtime virtual texture requires the current client scene");
        return resources.virtualTextures().placeAuthored(level, effect, component, profile, tileSizeBias, tileCountBias, renderer);
    }

    public @Nullable RuntimeVirtualTextureVolume placeAuthoredRuntimeVirtualTexture(ClientLevel level, Identifier effect,
            String component, SceneProfile profile, int tileSizeBias, int tileCountBias) {
        requireClientThread();
        if (level != Minecraft.getInstance().level) throw new IllegalArgumentException("Runtime virtual texture requires the current client scene");
        return resources.virtualTextures().placeAuthored(level, effect, component, profile, tileSizeBias, tileCountBias);
    }

    public Registration registerRuntimeVirtualTexturePrimitive(ClientLevel level, Identifier effect, String component,
            RuntimeVirtualTexturePrimitive geometry, Bounds bounds) {
        requireClientThread();
        if (level != Minecraft.getInstance().level) throw new IllegalArgumentException("RVT primitive requires the current client scene");
        return resources.virtualTextures().registerPrimitive(level, effect, component, geometry, bounds);
    }

    public RuntimeVirtualTextureVolume placeRuntimeVirtualTexture(ClientLevel level, Identifier effect, String source, Volume volume,
                                                                 int tileSizeBias, int tileCountBias, int componentBias,
                                                                 RuntimeVirtualTexturePageRenderer renderer) {
        return placeRuntimeVirtualTexture(level, effect, source, volume, tileSizeBias, tileCountBias, componentBias, renderer, null);
    }

    public RuntimeVirtualTextureVolume placeRuntimeVirtualTexture(ClientLevel level, Identifier effect, String source, Volume volume,
                                                                 int tileSizeBias, int tileCountBias, int componentBias,
                                                                 RuntimeVirtualTexturePageRenderer renderer, @Nullable StreamingMips streaming) {
        requireClientThread();
        if (level != Minecraft.getInstance().level) throw new IllegalArgumentException("Runtime virtual texture requires the current client scene");
        return resources.virtualTextures().place(level, effect, source, volume, tileSizeBias, tileCountBias, componentBias, renderer, streaming);
    }

    public MaterialDefinition runtimeVirtualTextureWriter(Identifier effect, String sourceObject) {
        var definition = resources.definitions().get(effect);
        String source = SourceNames.canonical(sourceObject.replace(':', '.'));
        if (definition != null) for (var material : definition.materials().values())
            if (material.runtimeVirtualTextureOutputs() != 0 && SourceNames.canonical(material.sourceObject()).equals(source))
                return material;
        throw new IllegalArgumentException("Unknown runtime virtual texture writer " + sourceObject + " in " + effect);
    }

    public RuntimeVirtualTextureVolume placeRuntimeVirtualTexture(ClientLevel level, Identifier effect, String source, Volume volume,
                                                                 int tileSizeBias, int tileCountBias, int componentBias,
                                                                 RuntimeVirtualTextureMaterialPass material) {
        requireClientThread();
        if (level != Minecraft.getInstance().level) throw new IllegalArgumentException("Runtime virtual texture requires the current client scene");
        return resources.virtualTextures().place(level, effect, source, volume, tileSizeBias, tileCountBias, componentBias, material);
    }

    public void renderVirtualTexturePages(FrameInput input) {
        if (input.level() == Minecraft.getInstance().level && input.generation() == resources.generation()) {
            var virtualTextures = resources.virtualTextures();
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            virtualTextures.beginMaterialReads(input.level(), materialTextureFrame, target.width, target.height);
            try { virtualTextures.renderScenePages(input.level()); }
            finally { virtualTextures.materialReads().end(); }
        }
    }

    public MaterialVirtualTextureBindings materialVirtualTextures() { return resources.virtualTextures().materialReads(); }

    public ComputeVolume neutralAerialPerspective() {
        if (neutralAerialPerspective == null) {
            var sampling = new ComputeVolume.Sampling(
                    new EffectSamplerFactory.Settings(TextureMetadata.Address.CLAMP, TextureMetadata.Address.CLAMP,
                            TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT, 1), TextureMetadata.Address.CLAMP);
            neutralAerialPerspective = ComputeVolume.createTarget("LumenFX inactive aerial perspective", 1, 1, 1,
                    GpuFormat.RGBA16_FLOAT, false, false, new float[] {0, 0, 0, 1}, sampling);
        }
        return neutralAerialPerspective;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("VFX simulation requires the client thread");
    }
}
