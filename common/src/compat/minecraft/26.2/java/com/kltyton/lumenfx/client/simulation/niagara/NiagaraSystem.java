package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuGaussianSplatWriters;
import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatDraw;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraConsoleVariable.Initialization;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;

import java.util.concurrent.CompletableFuture;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSources;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.client.playback.EffectTeardown;
import com.kltyton.lumenfx.api.client.NiagaraComponentTickSettings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraEmitterOverrides;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraWorldSystemSimulation.SharedTickInfo;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPropertyInterfaceInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraVirtualTextureInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshReader.GpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalMeshReader.InvalidSamplingRegion;
import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Overrides;
import com.kltyton.lumenfx.client.audio.MinecraftNiagaraAudio;
import com.kltyton.lumenfx.client.audio.analysis.NiagaraAudioBusAnalysis;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCachePlayer;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCacheRecorder;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCacheInterfaceRecorder;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMesh;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMeshPair;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Target2D;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.VolumeCacheStorage;
import com.kltyton.lumenfx.api.client.NiagaraCacheCapture;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCacheInterfacePlayback;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraGpuCacheFrame;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraSimCacheReader.Bound;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraSparseVolumeTexture;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraVolumeCache;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.NiagaraGpuVolumeCaches;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuPhysicsBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuRigidMeshBindings;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraRigidMeshCollision;
import com.kltyton.lumenfx.effect.niagara.texture.SparseVolumeInterfaceDefinition;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer;
import com.kltyton.lumenfx.client.render.niagara.gpu.memory.NiagaraGpuMemoryBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborGrid;
import com.kltyton.lumenfx.effect.niagara.grid.NeighborGridDefinition;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuRasterizationGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraRasterizationGrid;
import com.kltyton.lumenfx.effect.niagara.grid.RasterizationGridDefinition;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuVelocityGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraVelocityGrid;
import com.kltyton.lumenfx.effect.niagara.grid.VelocityGridDefinition;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterScene;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventDemand;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuHairBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuGroomCache;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.HairStrandsFrame;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.kltyton.lumenfx.client.render.niagara.gpu.chaos.NiagaraGpuChaosBuffers;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.geometry.NiagaraGpuGeometryBuffers;

import com.kltyton.lumenfx.effect.niagara.grid.Grid3DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.Grid2DCollectionDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGridCollectionUses.Use;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollections;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid3DCollections;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid2DCollections;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollections;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.table.NiagaraDataTable;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraIntRenderTarget2D;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraMovieRenderSample;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraViewQueries;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraSceneCapture;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraLandscape;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraVirtualTextureState;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuTerrainImages.CollisionGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSpline;
import com.kltyton.lumenfx.client.render.niagara.gpu.spline.NiagaraGpuSplineBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborQueries;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborQuery;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuIntRenderTargets;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuIntRenderTargets.IntTargetPublication;
import com.kltyton.lumenfx.client.render.niagara.gpu.socket.NiagaraGpuSocketBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraGpuDynamicMeshes;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraDynamicMeshRenderSource;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.effect.niagara.memory.NiagaraMemoryBufferDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimCacheReaderDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Playback;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Transform;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelReaders;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWriters;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.decal.DecalMaterialSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.simulation.niagara.render.NaniteInstanceFloatBindings;
import com.kltyton.lumenfx.client.simulation.niagara.render.NaniteInstanceFloatBindings.FloatComponent;
import com.kltyton.lumenfx.client.simulation.niagara.render.NiagaraEmitterRenderState;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuArrayRegistry;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuBatchDispatcher;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraCpuDebugLines;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraPersistentDebugShapes;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuCountArena;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuCounterRegistry;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuCurveRegistry;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRendererInfoRegistry;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDataBuffer;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuInterfaces;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleRead;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRebase;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuRenderTargets;
import com.kltyton.lumenfx.effect.render.scene.ScenePrimitiveIds;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraDataInterfaceInstances;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraAsyncGpuTraceInstances;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraActorComponent;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraCollisionState;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuDimensionalRenderTargets;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraGpuArrayAccess;
import com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialInstances;
import com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialObjects;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraEngineParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraRendererParameterStores;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraInstanceNumericStores;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataSetStoreBinding;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources.ObjectRegistry;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.CameraDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraScriptNumericStore;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraPlatformSetProxies;
import com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.MutableInterfaceInstance;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.Reference;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.StreamedBinding;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.StreamedValue;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.AsyncTraceSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** The system VM publishes emitter inputs before independent emitter jobs execute. */
public final class NiagaraSystem implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final long renderIdentity = ScenePrimitiveIds.next();
    private final NiagaraDefinition definition;
    private final NiagaraPersistentDebugShapes persistentDebugShapes;
    private final @Nullable NiagaraTickClock fixedClock;
    private NiagaraComponentTickSettings componentTickSettings = NiagaraComponentTickSettings.DEFAULT;
    private NiagaraEmitterOverrides emitterOverrides = NiagaraEmitterOverrides.EMPTY;
    private final boolean limitsVariableDelta;
    private final NiagaraEmitterSchedule schedule;
    private final NiagaraEventData events;
    private final NiagaraInterfaces interfaces = new NiagaraInterfaces();
    private ObjectPropertyInputs objectPropertyInputs = ObjectPropertyInputs.EMPTY;
    private NiagaraPropertyInterfaceInputs propertyInterfaceInputs = NiagaraPropertyInterfaceInputs.EMPTY;
    private ActorComponentInputs actorComponentInputs = ActorComponentInputs.EMPTY;
    private IntFunction<ActorComponentInputs.@Nullable Component> localActorComponents = index -> null;
    private @Nullable NiagaraCollisionWorld collisionWorld;
    private NiagaraStaticMeshInputs staticMeshInputs = NiagaraStaticMeshInputs.EMPTY;
    private NiagaraSkeletalMeshInputs skeletalMeshInputs = NiagaraSkeletalMeshInputs.EMPTY;
    private NiagaraRigidMeshInputs rigidMeshInputs = NiagaraRigidMeshInputs.EMPTY;
    private NiagaraGeometryCollectionInputs geometryCollectionInputs = NiagaraGeometryCollectionInputs.EMPTY;
    private NiagaraChaosScene chaosScene = NiagaraChaosScene.EMPTY;
    private NiagaraGroomInputs groomInputs = NiagaraGroomInputs.EMPTY;
    private NiagaraSceneCaptureInputs sceneCaptureInputs = NiagaraSceneCaptureInputs.EMPTY;
    private NiagaraLandscapeInputs landscapeInputs = NiagaraLandscapeInputs.EMPTY;
    private NiagaraVirtualTextureInputs virtualTextureInputs = NiagaraVirtualTextureInputs.EMPTY;
    private Map<String, NiagaraIntDistribution> intDistributionInputs = Map.of();
    private @Nullable NiagaraPhysicsField worldPhysicsField;
    private NiagaraWaterScene waterScene = NiagaraWaterScene.EMPTY;
    private Map<String, String> waterSourceOverrides = Map.of();
    private Map<String, NiagaraStreamingVirtualTexture> virtualTextureAssets = Map.of();
    private Map<String, NiagaraRuntimeVirtualTexture> runtimeVirtualTextures = Map.of();
    private boolean firstRigidMeshStep;
    private NiagaraSocketInputs socketInputs = NiagaraSocketInputs.EMPTY;
    private NiagaraSplineInputs splineInputs = NiagaraSplineInputs.EMPTY;
    private NiagaraMovieRenderSample movieRenderSample = NiagaraMovieRenderSample.INACTIVE;
    private Map<String, NiagaraParticleExport> exportHandlers = Map.of();
    private @Nullable ClientLevel channelWorld;
    private @Nullable EffectHandle channelOwner;
    private @Nullable NiagaraBounds externalFixedBounds;
    private NiagaraAudioBackend audioBackend = MinecraftNiagaraAudio.INSTANCE;
    private Map<String, Overrides> audioOverrides = Map.of();
    private List<ObjectPropertyInputs.Remap> objectReaderRemaps = List.of();
    private @Nullable List<ObjectPropertyInputs.Remap> appliedObjectReaderRemaps;
    private final NiagaraPlatformSetProxies platformSets;
    private final Set<String> platformObjects = new HashSet<>();
    private final Set<String> basePlatformObjects;
    private final Set<String> baseMutableInterfaces;
    private long dataInterfaceRevision;
    private final boolean needsCamera;
    private boolean currentCameraRequired;
    private boolean channelTickConflictReported;
    private final boolean alwaysVelocity;
    private final VectorMachine spawn, update;
    private final NiagaraCpuEmitter[] emitters;
    private final boolean[] emitterSynchronousCollision;
    private final NiagaraGpuEmitterState[] gpuEmitters;
    private final NiagaraStatelessGpuEmitterState[] statelessGpu;
    private final int[] gpuParticleCounts;
    private final NiagaraGpuSimulation[] gpuSimulations;
    private final Vec3[] gpuRenderOrigins;
    private @Nullable NiagaraCachePlayer cachePlayer;
    private @Nullable NiagaraCacheInterfacePlayback cacheInterfaces;
    private NiagaraGpuCacheFrame @Nullable [] cacheGpuFrames;
    private boolean @Nullable [] cacheGpuVisible;
    private NiagaraGpuCacheFrame @Nullable [] resumeGpuFrames;
    private boolean @Nullable [] resumeGpuVisible;
    private @Nullable NiagaraCacheInterfacePlayback resumeCacheInterfaces;
    private @Nullable NiagaraCachePlayer lastSampledCache;
    private @Nullable NiagaraDataInterfaceInstances observedInterfaceInstances;
    private @Nullable Float cacheAgeOverride;
    private Map<String, NiagaraSimulationCache> simCacheReaderInputs = Map.of();
    private Map<String, SparseVolumeTextureAsset> sparseVolumeInputs = Map.of();
    private @Nullable NiagaraBounds cacheBounds;
    private @Nullable NiagaraCacheRecorder cacheRecorder;
    private @Nullable NiagaraGpuMemoryBuffers gpuMemoryBuffers;
    private @Nullable NiagaraGpuNeighborGrids gpuNeighborGrids;
    private @Nullable NiagaraGpuNeighborQueries gpuNeighborQueries;
    private @Nullable NiagaraGpuRasterizationGrids gpuRasterizationGrids;
    private @Nullable NiagaraGpuVelocityGrids gpuVelocityGrids;
    private @Nullable NiagaraGpuGrid3DCollections gpuGrid3DCollections;
    private @Nullable NiagaraGpuGrid2DCollections gpuGrid2DCollections;
    private @Nullable NiagaraGpuIntRenderTargets gpuIntRenderTargets;
    private @Nullable NiagaraGpuSocketBuffers gpuSocketBuffers;
    private @Nullable NiagaraGpuVolumeCaches gpuVolumeCaches;
    private @Nullable NiagaraGpuPhysicsBuffers gpuPhysicsAssets;
    private NiagaraGpuRigidMeshBindings.@Nullable Buffers gpuRigidMeshes;
    private @Nullable NiagaraGpuGeometryBuffers gpuGeometryCollections;
    private @Nullable NiagaraGpuChaosBuffers gpuChaosDestruction;
    private @Nullable NiagaraGpuHairBuffers gpuHairStrands;
    private @Nullable CollisionGrids gpuLandscapes;
    private @Nullable NiagaraGpuSplineBuffers gpuSplineBuffers;
    private @Nullable NiagaraGpuDynamicMeshes gpuDynamicMeshes;
    private @Nullable NiagaraGpuGaussianSplatWriters gpuGaussianSplatWriters;
    private Map<String, NiagaraGaussianSplatWriter.Snapshot> latestGaussianSplatWriters = Map.of();
    private enum CaptureInterfaceKind { ARRAY, COUNTER, TARGET_2D, TARGET_VOLUME, CHANNEL, MEMORY, DYNAMIC_MESH }
    private @Nullable NiagaraCacheCapture channelCacheRequest;
    private record CaptureInterface(String id, CaptureInterfaceKind kind, boolean gpu) { }
    private final Map<NiagaraCacheInterfaceData.Key, CaptureInterface> captureInterfaceBindings = new LinkedHashMap<>();
    private final List<ArrayList<NiagaraGpuEmitterState.@Nullable Step>> gpuTicks;
    private Initialization consoleInitialization;
    private record GpuInterfaceStep(NiagaraGpuRenderTargets.@Nullable Step targets,
                                    NiagaraGpuDimensionalRenderTargets.@Nullable Step dimensionalTargets,
                                    NiagaraTextureBindings.@Nullable Step textures, Map<String, Integer> cameraModes,
                                    Map<String, NiagaraEmitterBounds.Snapshot> emitterBounds,
                                    NiagaraInterfaces.MutableGpuInputs mutable, Map<String, Integer> platformValues,
                                    Map<String, NiagaraObjectPropertyReader.Snapshot> objectProperties,
                                    Map<String, NiagaraPropertyInterface.Frame> propertyInterfaces,
                                    Map<String, NiagaraActorComponent.Snapshot> actorComponents,
                                    Map<String, GpuSnapshot> staticMeshes,
                                    Map<String, NiagaraSkeletalGpuSnapshot> skeletalMeshes,
                                    Map<String, NiagaraParticleExportState.Snapshot> particleExports,
                                    Map<String, NiagaraDataChannelGpuSnapshot> channelReaders,
                                    Map<String, NiagaraDataChannelState.GpuWriter> channelWriters,
                                    Map<String, NiagaraAudioBusAnalysis.Frame> audioFrames,
                                    Map<String, AsyncTraceSnapshot> asyncTraces,
                                    Map<String, Bound> simCacheReaders,
                                    Map<String, NiagaraSparseVolumeTexture.Snapshot> sparseVolumes,
                                    Map<String, NiagaraVolumeCache.Snapshot> volumeCaches,
                                    Map<String, NiagaraPhysicsAssetReader.Frame> physicsAssets,
                                    Map<String, NiagaraRigidMeshCollision.Frame> rigidMeshes,
                                    Map<String, GeometryCollectionFrame> geometryCollections,
                                    Map<String, ChaosDestructionFrame> chaosDestruction,
                                    Map<String, HairStrandsFrame> hairStrands,
                                    @Nullable NiagaraPhysicsField physicsField,
                                    Map<String, NiagaraSceneCapture.Publication> sceneCaptures,
                                    Map<String, NiagaraLandscape.Frame> landscapes,
                                    Map<String, NiagaraVirtualTextureState.Frame> virtualTextures,
                                    Map<String, NiagaraMemoryBuffer.Snapshot> memoryBuffers,
                                    Map<String, NiagaraNeighborGrid.Snapshot> neighborGrids,
                                    Map<String, NiagaraRasterizationGrid.Snapshot> rasterizationGrids,
                                    Map<String, NiagaraVelocityGrid.Snapshot> velocityGrids,
                                    Map<String, NiagaraGrid3DCollections.Publication> grid3DCollections,
                                    Map<String, NiagaraGrid2DCollections.Publication> grid2DCollections,
                                    Map<String, NiagaraDataTable> dataTables,
                                    Map<String, NiagaraGaussianSplatReader> gaussianSplatReaders,
                                    Map<String, NiagaraGaussianSplatWriter.Snapshot> gaussianSplatWriters,
                                    Map<String, NiagaraIntDistribution> intDistributions,
                                    Map<String, IntTargetPublication> intRenderTargets,
                                    Map<String, NiagaraSocketReader.Snapshot> sockets,
                                    Map<String, NiagaraSpline.GpuFrame> splines,
                                    NiagaraViewQueries viewQueries,
                                    Map<String, NiagaraNeighborQuery.Snapshot> neighborQueries,
                                    Map<String, NiagaraDynamicMeshFrame> dynamicMeshes,
                                    @Nullable ClientLevel exportWorld,
                                    NiagaraPlatformEnvironment consoleEnvironment,
                                    Initialization consoleInitialization) { }
    private final List<@Nullable GpuInterfaceStep> gpuInterfaceSteps = new ArrayList<>();
    private final Set<String> gpuMutableIds = new HashSet<>();
    private final Set<String> gpuTextureIds = new HashSet<>();
    private final Set<String> gpuDimensionalTargetIds = new HashSet<>();
    private final Set<String> gpuStaticMeshIds = new HashSet<>();
    private final Set<String> gpuSkeletalMeshIds = new HashSet<>();
    private final Set<String> gpuParticleExportIds = new HashSet<>();
    private final Set<String> gpuDataChannelReaderIds = new HashSet<>();
    private final Set<String> gpuDataChannelWriterIds = new HashSet<>();
    private final Set<String> gpuAudioAnalysisIds = new HashSet<>();
    private final Set<String> gpuSimCacheReaderIds = new HashSet<>();
    private final Set<String> gpuSparseVolumeIds = new HashSet<>();
    private final Set<String> gpuVolumeCacheIds = new HashSet<>();
    private final Set<String> gpuPhysicsAssetIds = new HashSet<>();
    private final Set<String> gpuRigidMeshIds = new HashSet<>();
    private final Set<String> gpuGeometryCollectionIds = new HashSet<>();
    private final Set<String> gpuPropertyInterfaceIds = new HashSet<>();
    private final Set<String> gpuChaosDestructionIds = new HashSet<>();
    private final Set<String> gpuHairStrandsIds = new HashSet<>();
    private final Set<String> gpuSceneCaptureIds = new HashSet<>();
    private final Set<String> gpuLandscapeIds = new HashSet<>();
    private final Set<String> gpuVirtualTextureIds = new HashSet<>();
    private final Set<String> gpuMemoryBufferIds = new HashSet<>();
    private final Set<String> gpuNeighborGridIds = new HashSet<>();
    private final Set<String> gpuNeighborQueryIds = new HashSet<>();
    private final Set<String> gpuRasterizationGridIds = new HashSet<>();
    private final Set<String> gpuVelocityGridIds = new HashSet<>();
    private final Set<String> gpuGrid3DCollectionIds = new HashSet<>();
    private final Set<String> gpuGrid2DCollectionIds = new HashSet<>();
    private final Set<String> gpuIntRenderTargetIds = new HashSet<>();
    private final Set<String> gpuSocketIds = new HashSet<>();
    private final Set<String> gpuSplineIds = new HashSet<>();
    private final Set<String> gpuDynamicMeshIds = new HashSet<>();
    private final Map<String, String> dynamicMeshParameters = new LinkedHashMap<>();
    private @Nullable NiagaraGpuDataChannelReaders gpuDataChannelReaders;
    private @Nullable NiagaraGpuDataChannelWriters gpuDataChannelWriters;
    private @Nullable NiagaraGpuDimensionalRenderTargets gpuDimensionalTargets;
    private final boolean gpuEmitterPropertiesUsed;
    private final List<ArrayList<List<NiagaraGpuStageSchedule.Invocation>>> gpuPlans;
    private final int[] gpuTickCursor;
    private boolean gpuPrefixMutated;
    private int gpuTickCapacity;
    private boolean deferredGpuBatch;
    private boolean hostGpuBatch;
    private final int[] gpuOrder;
    private @Nullable NiagaraGpuBatchDispatcher gpuDispatcher;
    private NiagaraGpuCountArena gpuCounters;
    private NiagaraGpuArrayRegistry gpuArrays;
    private NiagaraGpuCounterRegistry gpuInterfaceCounters;
    private NiagaraGpuCurveRegistry gpuCurves;
    private NiagaraGpuRendererInfoRegistry gpuRendererInformation;
    private NiagaraGpuRenderTargets gpuRenderTargets;
    private final java.util.Map<String, JsonObject> gpuRenderTargetBindings = new java.util.LinkedHashMap<>();
    private final Set<String> standaloneInterfaceCallbacks = new HashSet<>();
    private Map<String, String> gpuProxySourceNames = Map.of();
    private ComputeRuntime cpuTargetRuntime;
    private final java.util.Map<String, JsonObject> materialRenderTargets = new java.util.LinkedHashMap<>();
    private final Map<String, JsonObject> materialDimensionalTargets = new java.util.LinkedHashMap<>();
    private final Map<String, JsonObject> materialSceneCaptures = new LinkedHashMap<>();
    private final Map<String, NiagaraDefinition.Variable> variables;
    private final NiagaraDefinition.@Nullable Variable executionStateBinding;
    private final @Nullable NiagaraSystemStateClock nativeSystemState;
    private VectorDataSet current, destination;
    private final VectorDataSet spawnInputs, updateInputs;
    private final byte[] spawnConstants, updateConstants, defaultSpawnConstants, defaultUpdateConstants;
    private final NiagaraScriptNumericStore spawnParameterStore, updateParameterStore;
    private final NiagaraInstanceNumericStores instanceParameterStores;
    private final NiagaraDataSetStoreBinding spawnInstanceBinding, updateInstanceBinding;
    private final NiagaraDataSetStoreBinding[] rendererDataSetBindings;
    private final byte[][] runtimes;
    private final NiagaraMaterialInstances.EngineValues[] materialEngineValues;
    private final NiagaraRendererParameterStores[] rendererParameterStores;
    private final Map<String, ObjectValue> parameterObjects = new LinkedHashMap<>();
    private final NiagaraParameterStoreSources parameterSources;
    private final NiagaraAsyncGpuTraceInstances asyncTraceInstances = new NiagaraAsyncGpuTraceInstances();
    private final NiagaraFrame frame;
    private int seed;
    private @Nullable Integer randomSeedOffset;
    private boolean seedOffsetsApplied;
    private boolean initialized, execute, complete, boundsPublished, dynamicBindingsPublished, componentCompletionNotified;
    private boolean statelessRestartRenderPending;
    private @Nullable NiagaraBounds committedComponentBounds;
    private double forceBoundsUpdateTime;
    private @Nullable NiagaraWorldSystemSimulation worldSimulation;
    private double lastTime = Double.NaN, now, realTime;
    private EffectHandle.Input input;
    private final List<MaterialCollectionDefinition> materialCollections;
    private final List<NiagaraCollectionBinding> collectionBindings;
    private boolean anyEmitterShouldTick;

    public NiagaraSystem(EffectDefinition effect, long seed) {
        this(effect, seed, NiagaraPlatforms.publication());
    }
    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatformEnvironment platform) {
        this(effect, seed, new NiagaraPlatforms.Publication(platform, NiagaraPlatforms.publication().sequence()));
    }
    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatforms.Publication publication) {
        this(effect, seed, publication, new NiagaraPlatformSetProxies(publication));
    }
    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatforms.Publication publication,
                         NiagaraPlatformSetProxies platformSets) {
        this(effect, seed, publication, platformSets, null);
    }
    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatforms.Publication publication,
                         NiagaraPlatformSetProxies platformSets, @Nullable ObjectRegistry objects) {
        this(effect, seed, publication, platformSets, objects, Map.of());
    }
    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatforms.Publication publication,
                         NiagaraPlatformSetProxies platformSets, @Nullable ObjectRegistry objects,
                         Map<Key, Reference> dataInterfaces) {
        this(effect, seed, publication, platformSets, objects, dataInterfaces, null);
    }

    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatforms.Publication publication,
                         NiagaraPlatformSetProxies platformSets, @Nullable ObjectRegistry objects,
                         Map<Key, Reference> dataInterfaces, @Nullable Playback playback) {
        this(effect, seed, publication, platformSets, objects, dataInterfaces, playback, Map.of());
    }

    public NiagaraSystem(EffectDefinition effect, long seed, NiagaraPlatforms.Publication publication,
                         NiagaraPlatformSetProxies platformSets, @Nullable ObjectRegistry objects,
                         Map<Key, Reference> dataInterfaces, @Nullable Playback playback, Map<Key, Reference> objectParameters) {
        definition = Objects.requireNonNull(effect.niagara());
        frame = new NiagaraFrame(definition.timing().initialOwnerVelocityFromActor(), definition.lwc());
        cachePlayer = playback == null ? null : new NiagaraCachePlayer(playback.cache(), definition);
        cacheInterfaces = playback == null || playback.cache().interfaces().isEmpty() ? null
                : new NiagaraCacheInterfacePlayback(playback.cache().interfaces());
        cacheAgeOverride = playback == null ? null : playback.age();
        cacheGpuFrames = playback == null ? null : new NiagaraGpuCacheFrame[definition.emitters().size()];
        persistentDebugShapes = new NiagaraPersistentDebugShapes(definition.persistentDebugShapes());
        interfaces.volumeCacheAssets(definition.volumeCacheInputs());
        cacheGpuVisible = playback == null ? null : new boolean[definition.emitters().size()];
        parameterSources = new NiagaraParameterStoreSources(parameterObjects, objects, definition.simCacheInputs(),
                definition.sparseVolumeInputs());
        this.platformSets = Objects.requireNonNull(platformSets);
        platformSets.synchronize(publication);
        NiagaraPlatformEnvironment platform = publication.environment();
        frame.platform = Objects.requireNonNull(platform);
        frame.platformSequence = publication.sequence();
        fixedClock = definition.timing().fixedTick() ? new NiagaraTickClock(definition.timing()) : null;
        limitsVariableDelta = fixedClock == null && Float.isFinite(definition.timing().maximumDelta())
                && definition.emitters().stream().anyMatch(emitter -> emitter.simulation() instanceof NiagaraDefinition.CpuSimulation
                    || emitter.simulation() instanceof NiagaraDefinition.GpuSimulation);
        parameterSources.registerMutable(definition.mutableInterfaces());
        parameterSources.register(definition.systemSpawn()); parameterSources.register(definition.systemUpdate());
        for (var source : definition.emitters()) {
            if (source.simulation() instanceof NiagaraDefinition.CpuSimulation cpu) {
                parameterSources.register(cpu.spawn()); parameterSources.register(cpu.update());
            }
            for (var handler : source.eventHandlers()) if (handler.program() != null) parameterSources.register(handler.program());
        }
        registerPlatformInterfaces(definition.systemSpawn()); registerPlatformInterfaces(definition.systemUpdate());
        for (var source : definition.emitters()) {
            if (source.simulation() instanceof NiagaraDefinition.CpuSimulation cpu) {
                registerPlatformInterfaces(cpu.spawn()); registerPlatformInterfaces(cpu.update());
            } else if (source.simulation() instanceof NiagaraDefinition.GpuSimulation gpu)
                registerPlatformInterfaces(gpu.metadata());
            for (var handler : source.eventHandlers()) if (handler.program() != null) registerPlatformInterfaces(handler.program());
        }
        var collectionSources = NiagaraCollectionSources.collect(definition);
        materialCollections = collectionSources.materials(); collectionBindings = collectionSources.bindings();
        for (var material : effect.materials().values()) for (var texture : material.instanceParameters().textureLayout()) {
            String type = texture.dataInterface();
            if (type == null || texture.interfaceDefinition() == null) continue;
            Map<String, JsonObject> definitions;
            if (type.endsWith(".NiagaraDataInterfaceRenderTarget2D")) definitions = materialRenderTargets;
            else if (type.endsWith(".NiagaraDataInterfaceRenderTarget2DArray")
                    || type.endsWith(".NiagaraDataInterfaceRenderTargetCube")
                    || type.endsWith(".NiagaraDataInterfaceRenderTargetVolume")) definitions = materialDimensionalTargets;
            else if (type.endsWith(".NiagaraDataInterfaceSceneCapture2D")) definitions = materialSceneCaptures;
            else continue;
            String id = Objects.requireNonNull(texture.interfaceId());
            var data = texture.interfaceDefinition();
            var previous = definitions.putIfAbsent(id, data);
            if (previous != null && !previous.equals(data))
                throw new IllegalArgumentException("Conflicting material texture interface " + id);
        }
        registerTargetInstances();
        registerMaterialInterfaces();
        alwaysVelocity = definition.emitters().stream().flatMap(emitter -> emitter.renderers().stream())
                .anyMatch(renderer -> renderer.motion().enabled());
        for (var emitter : definition.emitters()) {
            if (emitter.simulation() instanceof NiagaraDefinition.DisabledSimulation disabled && !disabled.hasEmitter()) continue;
            interfaces.registerEmitterBounds(emitter, definition.systemFixedBoundsEnabled() ? definition.systemFixedBounds() : null);
        }
        schedule = new NiagaraEmitterSchedule(definition.emitters());
        events = new NiagaraEventData(definition.emitters());
        this.seed = definition.systemDeterministic() ? definition.systemRandomSeed() : NiagaraSeed.next();
        nativeSystemState = definition.timing().systemState().runUpdate() ? null
                : new NiagaraSystemStateClock(definition.timing().systemState(), this.seed);
        spawn = new VectorMachine(definition.systemSpawn(), interfaces); update = new VectorMachine(definition.systemUpdate(), interfaces);
        registerCpuInterfaces(definition.systemSpawn()); registerCpuInterfaces(definition.systemUpdate());
        current = dataset(definition.systemLayout()); destination = dataset(definition.systemLayout());
        spawnInputs = dataset(definition.spawnInputLayout()); updateInputs = dataset(definition.updateInputLayout());
        spawnInputs.count(1); updateInputs.count(1);
        spawnConstants = definition.systemSpawn().constantData();
        updateConstants = definition.systemUpdate().constantData();
        spawnParameterStore = new NiagaraScriptNumericStore(definition.systemSpawn(), parameterSources);
        updateParameterStore = new NiagaraScriptNumericStore(definition.systemUpdate(), parameterSources);
        spawnParameterStore.restoreConstants(spawnConstants); updateParameterStore.restoreConstants(updateConstants);
        defaultSpawnConstants = spawnConstants.clone(); defaultUpdateConstants = updateConstants.clone();
        instanceParameterStores = new NiagaraInstanceNumericStores(definition.instanceNumeric(), definition.instanceStores(), parameterSources);
        instanceParameterStores.applyDataInterfaces(dataInterfaces);
        instanceParameterStores.applyObjectParameters(objectParameters);
        if (definition.instanceStores() != null) interfaces.parameterStore(instanceParameterStores.instance());
        spawnInstanceBinding = new NiagaraDataSetStoreBinding(instanceParameterStores.instance(), definition.spawnInputLayout());
        updateInstanceBinding = new NiagaraDataSetStoreBinding(instanceParameterStores.instance(), definition.updateInputLayout());
        Map<String, NiagaraDefinition.Variable> variables = new LinkedHashMap<>();
        for (var variable : definition.systemLayout()) variables.put(variable.name(), variable);
        this.variables = Collections.unmodifiableMap(variables);
        executionStateBinding = variables.get("System.ExecutionState");
        emitters = new NiagaraCpuEmitter[definition.emitters().size()];
        emitterSynchronousCollision = new boolean[emitters.length];
        gpuEmitters = new NiagaraGpuEmitterState[emitters.length];
        statelessGpu = new NiagaraStatelessGpuEmitterState[emitters.length];
        gpuParticleCounts = new int[emitters.length];
        gpuSimulations = new NiagaraGpuSimulation[emitters.length];
        gpuRenderOrigins = new Vec3[emitters.length];
        gpuTicks = IntStream.range(0, emitters.length)
                .mapToObj(index -> new ArrayList<NiagaraGpuEmitterState.@Nullable Step>()).toList();
        gpuPlans = IntStream.range(0, emitters.length)
                .mapToObj(index -> new ArrayList<List<NiagaraGpuStageSchedule.Invocation>>()).toList();
        gpuTickCursor = new int[emitters.length];
        gpuTickCapacity = Math.addExact(definition.timing().activeWarmupTicks(), 100);
        for (int index = 0; index < emitters.length; index++) {
            var source = definition.emitters().get(index);
            List<EmitterDefinition> renderers = source.renderers().stream().map(binding ->
                    effect.niagaraRenderer(binding.name())).toList();
            if (source.simulation() instanceof NiagaraDefinition.DisabledSimulation) {
                emitters[index] = new NiagaraDisabledEmitter(source, interfaces);
            } else if (source.simulation() instanceof NiagaraDefinition.GpuSimulation gpu) {
                registerGpuInterfaces(gpu);
                gpuEmitters[index] = new NiagaraGpuEmitterState(source, this.variables, events, platform, definition.systemLayout(), parameterSources);
                interfaces.registerGpuParticleEmitter(source, gpuEmitters[index]::estimatedCount);
            } else if (source.simulation() instanceof NiagaraDefinition.StatelessSimulation stateless) {
                if (stateless.target().equals("CPUSim"))
                    emitters[index] = new NiagaraStatelessEmitter(definition, source, renderers, interfaces,
                            spawnConstants, updateConstants, (int)seed, platform);
                else {
                    statelessGpu[index] = new NiagaraStatelessGpuEmitterState(definition, source, (int)seed, platform);
                    interfaces.registerGpuParticleEmitter(source, statelessGpu[index]::estimatedCount);
                }
            } else {
                registerCpuInterfaces(source.spawn()); registerCpuInterfaces(source.update());
                emitterSynchronousCollision[index] = usesSynchronousCollision(source.spawn()) || usesSynchronousCollision(source.update())
                        || source.eventHandlers().stream().anyMatch(handler -> handler.program() != null && usesSynchronousCollision(handler.program()));
                var emitter = new NiagaraEmitter(source, renderers, this.variables, events, interfaces, platform, parameterSources);
                emitter.bindParameters(instanceParameterStores.instance(), definition.systemLayout());
                emitters[index] = emitter;
            }
            for (var handler : source.eventHandlers()) if (handler.program() != null) registerCpuInterfaces(handler.program());
        }
        definition.mutableInterfaces().forEach(interfaces::registerMutable);
        var cameraObjects = parameterSources.cameras();
        cameraObjects.forEach(interfaces::registerCamera);
        needsCamera = !cameraObjects.isEmpty() || cameraUsed(definition.systemSpawn()) || cameraUsed(definition.systemUpdate())
                || definition.emitters().stream().anyMatch(source ->
                    source.simulation() instanceof NiagaraDefinition.CpuSimulation cpu
                            && (cameraUsed(cpu.spawn()) || cameraUsed(cpu.update()))
                    || source.simulation() instanceof NiagaraDefinition.GpuSimulation gpu && cameraUsed(gpu.metadata())
                    || source.eventHandlers().stream().anyMatch(handler -> handler.program() != null && cameraUsed(handler.program())));
        gpuEmitterPropertiesUsed = definition.emitters().stream().anyMatch(emitter ->
                emitter.simulation() instanceof NiagaraDefinition.GpuSimulation gpu
                        && gpu.metadata().getAsJsonArray("interfaces").asList().stream().anyMatch(item ->
                        item.getAsJsonObject().get("class").getAsString().equals("NiagaraDataInterfaceEmitterProperties")
                                && item.getAsJsonObject().has("shader_symbol")));
        runtimes = new byte[emitters.length][];
        rendererParameterStores = new NiagaraRendererParameterStores[emitters.length];
        rendererDataSetBindings = new NiagaraDataSetStoreBinding[emitters.length];
        materialEngineValues = new NiagaraMaterialInstances.EngineValues[emitters.length];
        for (int i = 0; i < materialEngineValues.length; i++) {
            int index = i;
            rendererParameterStores[i] = new NiagaraRendererParameterStores(definition.emitters().get(i).rendererBindings(), false);
            rendererParameterStores[i].bindParents(spawnParameterStore.store(), updateParameterStore.store(),
                    instanceParameterStores.instance(), instanceParameterStores.override(),
                    gpuEmitters[i] == null ? null : gpuEmitters[i].combinedParameters(), false);
            if (gpuEmitters[i] != null) gpuEmitters[i].bindParameters(instanceParameterStores.instance(), rendererParameterStores[i]);
            rendererParameterStores[i].bindCompiled();
            if (emitters[i] instanceof NiagaraEmitter stateful)
                stateful.bindRendererObjects(rendererParameterStores[i], instanceParameterStores.override());
            else if (emitters[i] instanceof NiagaraStatelessEmitter stateless)
                stateless.bindRendererParameters(rendererParameterStores[i]);
            rendererDataSetBindings[i] = new NiagaraDataSetStoreBinding(rendererParameterStores[i].renderer(), definition.systemLayout());
            materialEngineValues[i] = new NiagaraMaterialInstances.EngineValues() {
                @Override public boolean read(String name, float[] destination) {
                    return NiagaraEngineParameters.readFloats(
                            runtimes[index], name, definition.emitters().get(index).sourceName(), destination);
                }
                @Override public boolean read(String name, MaterialInstanceParameters.Source source, float[] destination) {
                    return source.sourceType() != null && NiagaraEngineParameters.readFloats(runtimes[index], name,
                            definition.emitters().get(index).sourceName(), source.sourceType(), destination);
                }
                @Override public boolean readDefault(String name, MaterialInstanceParameters.Source source, float[] destination) {
                    return rendererParameterStores[index].read(name, source, destination);
                }
                @Override public @Nullable TextureParameter readTexture(MaterialInstanceParameters.TextureSlot binding) {
                    return rendererParameterStores[index].texture(binding);
                }
                @Override public StreamedValue readStreamedTexture(StreamedBinding binding) {
                    return rendererStreamedTexture(index, binding);
                }
                @Override public NiagaraCollectionSnapshot collections() { return frame.collections; }
                @Override public @Nullable DynamicMaterialValues dynamicMaterial(MaterialDefinition source) {
                    return interfaces.dynamicMaterial(source.sourceObject());
                }
                @Override public boolean dynamicBindingsPublished() {
                    return dynamicBindingsPublished;
                }
            };
        }
        interfaces.clearCollectionArrayBindings(parameterSources.collectionInterfaceNames());
        flushDataInterfaceSources();
        initializeDataInterfaceStates(null);
        parameterSources.forEachPlatformObject((identity, source) -> {
            platformSets.register(identity, source);
            platformObjects.add(identity);
        });
        basePlatformObjects = Set.copyOf(platformObjects);
        baseMutableInterfaces = interfaces.mutableInterfaceIds();
        parameterSources.finishObjectInitialization();
        gpuOrder = IntStream.range(0, emitters.length).filter(this::isGpuEmitter)
                .boxed().sorted(java.util.Comparator.comparingInt((Integer index) -> schedule.group(index)).thenComparingInt(index -> index))
                .mapToInt(Integer::intValue).toArray();
        if (!definition.platforms().active(platform)) {
            complete = true;
            for (int index = 0; index < emitters.length; index++) {
                if (emitters[index] != null) emitters[index].complete(4);
                else if (statelessGpu[index] != null) statelessGpu[index].complete(4);
                else gpuEmitters[index].complete(4);
            }
        }
    }

    private static boolean cameraUsed(VectorProgram program) {
        return program.external().stream().anyMatch(value -> value.interfaceType().equals("camera"));
    }

    private static boolean legacyCameraRequiresCurrent(VectorProgram program) {
        return program.external().stream().anyMatch(value -> value.interfaceKey() == null
                && value.interfaceType().equals("camera") && value.requireCurrent());
    }

    public Set<String> platformObjectIdentities() { return Set.copyOf(platformObjects); }
    private void registerPlatformInterfaces(VectorProgram program) {
        platformSets.register(program);
        for (var function : program.external()) if (function.interfaceType().equals("platform_set"))
            platformObjects.add(function.interfaceId());
    }
    private void registerPlatformInterfaces(JsonObject program) {
        platformSets.registerGpu(program);
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (binding.get("class").getAsString().equals("NiagaraDataInterfacePlatformSet"))
                platformObjects.add(binding.get("interface_id").getAsString());
        }
    }

    private void flushDataInterfaceSources() {
        instanceParameterStores.override().tick();
        instanceParameterStores.instance().tick();
    }

    private void initializeDataInterfaceStates(@Nullable Set<String> activeMutableInterfaces) {
        consoleInitialization = new Initialization(frame.platform);
        asyncTraceInstances.reset();
        var instance = instanceParameterStores.instance();
        boolean rebind = activeMutableInterfaces != null;
        currentCameraRequired = legacyCameraRequiresCurrent(definition.systemSpawn())
                || legacyCameraRequiresCurrent(definition.systemUpdate());
        if (rebind) {
            spawnParameterStore.store().markInterfacesDirty();
            updateParameterStore.store().markInterfacesDirty();
        }
        var discovered = new NiagaraDataInterfaceInstances();
        discovered.visit(instance, null, false, false);
        discovered.visit(spawnParameterStore.store(), instance, true, false);
        discovered.visit(updateParameterStore.store(), instance, true, false);
        for (int index = 0; index < emitters.length; index++) {
            if (emitters[index] instanceof NiagaraEmitter stateful && !stateful.disabled()) {
                stateful.forEachExecutionStore(rebind, store -> discovered.visit(store, null, true, false));
                var emitter = definition.emitters().get(index);
                var cpu = (NiagaraDefinition.CpuSimulation) emitter.simulation();
                currentCameraRequired |= legacyCameraRequiresCurrent(cpu.spawn()) || legacyCameraRequiresCurrent(cpu.update())
                        || emitter.eventHandlers().stream().anyMatch(event -> event.program() != null
                        && legacyCameraRequiresCurrent(event.program()));
            }
            var gpuEmitter = gpuEmitters[index];
            if (gpuEmitter != null && !gpuEmitter.disabled()) {
                gpuEmitter.forEachHostExecutionStore(rebind, store -> discovered.visit(store, null, false, true));
                gpuEmitter.refreshUserInterfaces(rebind);
                var combined = gpuEmitter.combinedParameters();
                if (combined != null) discovered.visit(combined, null, false, true);
            }
        }
        var staticMeshesRequiringCpu = new HashSet<String>();
        BiConsumer<String, String> meshFunction = (identity, function) -> {
            if (NiagaraStaticMeshReader.functionNeedsCpuAccess(function)) staticMeshesRequiringCpu.add(identity);
        };
        spawnParameterStore.forEachStaticMeshFunction(instance, meshFunction);
        updateParameterStore.forEachStaticMeshFunction(instance, meshFunction);
        for (var emitter : emitters) if (emitter instanceof NiagaraEmitter stateful && !stateful.disabled())
            stateful.forEachStaticMeshFunction(meshFunction);
        var frameCallbacks = new ArrayList<String>();
        var proxyNames = new HashMap<String, String>();
        observedInterfaceInstances = discovered;
        if (cacheInterfaces != null) cacheInterfaces.clearBindings();
        captureInterfaceBindings.clear();
        dynamicMeshParameters.clear();
        interfaces.beginGridCollectionBindings();
        discovered.forEach((object, usage) -> {
            frameCallbacks.add(object.identity());
            String proxyName = usage.gpuSourceName();
            if (proxyName != null) proxyNames.put(object.identity(), SourceNames.canonical(proxyName));
            interfaces.registerViewQuery(object.sourceClassName());
            asyncTraceInstances.initialize(object, usage.gpu(), instanceParameterStores.override(),
                    definition.gpuInterfaceFunctions(), frame.platform);
            if (cacheInterfaces != null) cacheInterfaces.bind(object, usage);
            var mutable = object.mutableDefinition();
            if (mutable != null) {
                if (activeMutableInterfaces != null) activeMutableInterfaces.add(object.identity());
                if (usage.gpu()) gpuMutableIds.add(object.identity());
                var data = mutable.data();
                if (definition.gpuInterfaceFunctions() != null && mutable.type().startsWith("NiagaraDataInterfaceArray")) {
                    data.addProperty("gpu_writable", NiagaraGpuArrayAccess.writable(object, instanceParameterStores.override(), definition.gpuInterfaceFunctions()));
                    data.addProperty("runtime_gpu_classification", true);
                }
                interfaces.registerMutable(new MutableInterfaceInstance(object.identity(), mutable.type(),
                        mutable.parameterName().isEmpty() ? usage.parameterName() : mutable.parameterName(), usage.cpu(), usage.gpu(), data));
            }
            interfaces.mutableUsage(object.identity(), usage.cpu());
            interfaces.bindArrayParameters(object.identity(), usage.parameterNames());
            var camera = object.camera();
            if (camera != null) {
                interfaces.registerCamera(object);
                currentCameraRequired |= usage.cpu() && camera.requireCurrent();
            }
            var texture = object.textureDefinition();
            if (texture != null) {
                interfaces.registerTexture(object.identity(), texture);
                if (usage.gpu()) gpuTextureIds.add(object.identity());
            }
            var target = object.renderTargetDefinition();
            if (mutable != null || target != null) {
                var kind = target != null ? CaptureInterfaceKind.TARGET_2D
                        : mutable.type().equals("NiagaraDataInterfaceSimpleCounter") ? CaptureInterfaceKind.COUNTER : CaptureInterfaceKind.ARRAY;
                String type = "/Script/Niagara." + (target != null ? "NiagaraDataInterfaceRenderTarget2D" : mutable.type());
                var capture = new CaptureInterface(object.identity(), kind, usage.gpu());
                for (String name : usage.parameterNames())
                    if (!name.isEmpty()) captureInterfaceBindings.putIfAbsent(new NiagaraCacheInterfaceData.Key(name, type), capture);
            }
            var targetArray = object.dimensionalTargetDefinition();
            if (targetArray != null && "NiagaraDataInterfaceRenderTargetVolume".equals(object.sourceClassName())) {
                var capture = new CaptureInterface(object.identity(), CaptureInterfaceKind.TARGET_VOLUME, usage.gpu());
                for (String name : usage.parameterNames()) if (!name.isEmpty())
                    captureInterfaceBindings.putIfAbsent(new NiagaraCacheInterfaceData.Key(name,
                            "/Script/Niagara.NiagaraDataInterfaceRenderTargetVolume"), capture);
            }
            var reader = object.propertyReader();
            if (reader != null) interfaces.registerObjectPropertyReader(object.identity(), reader);
            var propertyInterface = object.propertyInterface();
            if (propertyInterface != null) {
                interfaces.registerPropertyInterface(object.identity(), propertyInterface);
                if (usage.gpu()) gpuPropertyInterfaceIds.add(object.identity());
            }
            var actor = object.actorComponent();
            if (actor != null) interfaces.registerActorComponent(object.identity(), actor);
            var materialCollection = object.materialCollectionWriter();
            if (materialCollection != null) interfaces.registerMaterialCollectionWriter(object.identity(), materialCollection);
            var dynamicMaterial = object.dynamicMaterial();
            if (dynamicMaterial != null) interfaces.registerDynamicMaterialWriter(object.identity(), dynamicMaterial);
            boolean collision = usage.cpu() && "NiagaraDataInterfaceCollisionQuery".equals(object.sourceClassName());
            if (collision) interfaces.registerCollision(object.identity());
            var staticMesh = object.staticMeshReader();
            if (staticMesh != null) {
                interfaces.registerStaticMesh(object.identity(), staticMesh, staticMeshesRequiringCpu.contains(object.identity()),
                        usage.gpu() && !usage.cpu());
                if (usage.gpu()) gpuStaticMeshIds.add(object.identity());
            }
            var skeletalMesh = object.skeletalMeshReader();
            if (skeletalMesh != null) {
                interfaces.registerSkeletalMesh(object.identity(), object.authoredSourcePath(), skeletalMesh);
                if (usage.gpu()) gpuSkeletalMeshIds.add(object.identity());
            }
            var export = object.particleExport();
            if (export != null) {
                interfaces.registerParticleExport(object.identity(), export);
                if (usage.gpu()) gpuParticleExportIds.add(object.identity());
            }
            var dataChannel = object.dataChannel();
            if (dataChannel != null) {
                interfaces.registerDataChannel(object.identity(), dataChannel);
                if (usage.gpu()) (dataChannel.writer() ? gpuDataChannelWriterIds : gpuDataChannelReaderIds).add(object.identity());
                if (dataChannel.writer() && dataChannel.channel() != null) {
                    var capture = new CaptureInterface(object.identity(), CaptureInterfaceKind.CHANNEL, usage.gpu());
                    for (String name : usage.parameterNames()) if (!name.isEmpty())
                        captureInterfaceBindings.putIfAbsent(new NiagaraCacheInterfaceData.Key(name,
                                "/Script/Niagara.NiagaraDataInterfaceDataChannelWrite"), capture);
                }
            }
            var audioPlayer = object.audioPlayer();
            if (audioPlayer != null) interfaces.registerAudioPlayer(object.identity(), audioPlayer);
            var audioAnalysis = object.audioAnalysis();
            if (audioAnalysis != null) {
                interfaces.registerAudioAnalysis(object.identity(), audioAnalysis);
                if (usage.gpu()) gpuAudioAnalysisIds.add(object.identity());
            }
            var simCacheReader = object.simCacheReader();
            if (simCacheReader != null) {
                interfaces.registerSimCacheReader(object.identity(), simCacheReader);
                if (usage.gpu()) gpuSimCacheReaderIds.add(object.identity());
            }
            var sparseVolume = object.sparseVolume();
            if (sparseVolume != null) {
                interfaces.registerSparseVolume(object.identity(), sparseVolume);
                if (usage.gpu()) gpuSparseVolumeIds.add(object.identity());
            }
            var volumeCache = object.volumeCache();
            if (volumeCache != null) {
                interfaces.volumeCache(object.identity(), volumeCache);
                if (usage.gpu()) gpuVolumeCacheIds.add(object.identity());
            }
            var physics = object.physicsAsset();
            if (physics != null && usage.gpu()) {
                interfaces.registerPhysicsAsset(object.identity(), physics, definition.skeletalMeshInputs());
                gpuPhysicsAssetIds.add(object.identity());
            }
            var hair = object.hairStrands();
            if (hair != null) {
                interfaces.registerHairStrands(object.identity(), hair);
                if (usage.gpu()) gpuHairStrandsIds.add(object.identity());
            }
            var chaos = object.chaosDestruction();
            if (chaos != null) {
                interfaces.registerChaosDestruction(object.identity(), chaos);
                if (usage.gpu()) gpuChaosDestructionIds.add(object.identity());
            }
            var geometryCollection = object.geometryCollection();
            if (geometryCollection != null) {
                interfaces.registerGeometryCollection(object.identity(), geometryCollection, object.geometryCollectionAsset());
                if (usage.gpu()) gpuGeometryCollectionIds.add(object.identity());
            }
            var rigidMesh = object.rigidMeshCollision();
            if (rigidMesh != null) {
                interfaces.registerRigidMesh(object.identity(), rigidMesh);
                if (usage.gpu()) gpuRigidMeshIds.add(object.identity());
            }
            var sceneCapture = object.sceneCapture();
            if (sceneCapture != null) {
                interfaces.registerSceneCapture(object.identity(), sceneCapture);
                if (usage.gpu()) gpuSceneCaptureIds.add(object.identity());
            }
            var landscape = object.landscape();
            if (landscape != null) {
                interfaces.registerLandscapeObject(object.identity(), landscape, usage.gpu());
                if (usage.gpu()) gpuLandscapeIds.add(object.identity());
            }
            var virtualTexture = object.virtualTexture();
            if (virtualTexture != null) {
                interfaces.registerVirtualTexture(object.identity(), virtualTexture);
                if (usage.gpu()) gpuVirtualTextureIds.add(object.identity());
            }
            var memoryBuffer = object.memoryBuffer();
            if (memoryBuffer != null) {
                interfaces.registerMemoryBuffer(object.identity(), memoryBuffer, memoryBufferMaximum(), usage.cpu() && object.usesCpuMemoryBuffer());
                if (usage.gpu()) gpuMemoryBufferIds.add(object.identity());
                var capture = new CaptureInterface(object.identity(), CaptureInterfaceKind.MEMORY, usage.gpu());
                for (String name : usage.parameterNames()) if (!name.isEmpty())
                    captureInterfaceBindings.putIfAbsent(new NiagaraCacheInterfaceData.Key(name,
                            "/Script/Niagara.NiagaraDataInterfaceMemoryBuffer"), capture);
            }
            var dynamicMesh = object.dynamicMesh();
            var neighborGrid = object.neighborGrid();
            if (neighborGrid != null) {
                interfaces.registerNeighborGrid(object.identity(), neighborGrid, neighborGridDeviceMaximum(), frame.platform);
                if (usage.gpu()) gpuNeighborGridIds.add(object.identity());
            }
            var neighborQuery = object.neighborQuery();
            if (neighborQuery != null) {
                interfaces.registerNeighborQuery(object.identity(), neighborQuery, neighborQueryDeviceMaximum());
                if (usage.gpu()) gpuNeighborQueryIds.add(object.identity());
            }
            var rasterizationGrid = object.rasterizationGrid();
            if (rasterizationGrid != null) {
                interfaces.registerRasterizationGrid(object.identity(), rasterizationGrid, rasterizationGridMaximum());
                if (usage.gpu()) gpuRasterizationGridIds.add(object.identity());
            }
            var velocityGrid = object.velocityGrid();
            if (velocityGrid != null) {
                interfaces.registerVelocityGrid(object.identity(), velocityGrid);
                if (usage.gpu()) gpuVelocityGridIds.add(object.identity());
            }
            var grid3DCollection = object.grid3DCollection();
            if (grid3DCollection != null) {
                interfaces.registerGrid3DCollection(object.identity(), grid3DCollection, frame.platform, usage.cpu());
                if (usage.gpu()) gpuGrid3DCollectionIds.add(object.identity());
            }
            var grid2DCollection = object.grid2DCollection();
            if (grid2DCollection != null) {
                interfaces.registerGrid2DCollection(object.identity(), grid2DCollection, frame.platform, usage.cpu());
                if (usage.gpu()) gpuGrid2DCollectionIds.add(object.identity());
            }
            var table = object.dataTable();
            if (table != null) interfaces.registerDataTable(object.identity(), table, instance);
            registerGaussianSplatInterfaces(object);
            var distribution = object.intDistribution();
            if (distribution != null) interfaces.registerIntDistribution(object.identity(), distribution);
            var water = object.water();
            if (water != null) interfaces.registerWater(object.identity(), water, object.authoredWater());
            var intTarget = object.intRenderTarget2D();
            if (intTarget != null) {
                interfaces.registerIntRenderTarget2D(object.identity(), intTarget,
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform, usage.gpu());
                if (usage.gpu()) gpuIntRenderTargetIds.add(object.identity());
            }
            var socketReader = object.socketReader();
            if (socketReader != null) {
                interfaces.registerSocketReader(object.identity(), socketReader);
                if (usage.gpu()) gpuSocketIds.add(object.identity());
            }
            var spline = object.spline();
            if (spline != null) {
                interfaces.registerSpline(object.identity(), spline, usage.gpu());
                if (usage.gpu()) gpuSplineIds.add(object.identity());
            }
            if (dynamicMesh != null) {
                interfaces.registerDynamicMesh(object.identity(), dynamicMesh);
                if (usage.gpu()) gpuDynamicMeshIds.add(object.identity());
                for (String name : usage.parameterNames())
                    if (!name.isEmpty()) dynamicMeshParameters.putIfAbsent(SourceNames.canonical(name), object.identity());
                var capture = new CaptureInterface(object.identity(), CaptureInterfaceKind.DYNAMIC_MESH, usage.gpu());
                for (String name : usage.parameterNames()) if (!name.isEmpty())
                    captureInterfaceBindings.putIfAbsent(new NiagaraCacheInterfaceData.Key(name,
                            "/Script/Niagara.NiagaraDataInterfaceDynamicMesh"), capture);
            }
            if (targetArray != null) {
                interfaces.registerDimensionalTarget(object.identity(), targetArray,
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
                if (usage.gpu()) gpuDimensionalTargetIds.add(object.identity());
            }
            if (target != null) {
                interfaces.registerRenderTarget2D(object.identity(), target,
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
                if (usage.gpu()) {
                    var binding = new JsonObject();
                    binding.addProperty("interface_id", object.identity());
                    binding.add("render_target_2d", target);
                    gpuRenderTargetBindings.putIfAbsent(object.identity(), binding);
                }
            }
            var platforms = object.platformDefinition();
            if (platforms != null) {
                platformSets.register(object.identity(), platforms);
                platformObjects.add(object.identity());
            }
        });
        for (var gpuEmitter : gpuEmitters) if (gpuEmitter != null && !gpuEmitter.disabled())
            gpuEmitter.registerObjectPropertyRequests(interfaces);
        spawnParameterStore.forEachObjectPropertyRequest(instance, interfaces::registerObjectPropertyRequest);
        updateParameterStore.forEachObjectPropertyRequest(instance, interfaces::registerObjectPropertyRequest);
        for (var emitter : emitters) if (emitter instanceof NiagaraEmitter stateful && !stateful.disabled())
            stateful.registerObjectPropertyRequests(interfaces);
        gpuProxySourceNames = Map.copyOf(proxyNames);
        interfaces.orderFrameCallbacks(frameCallbacks, standaloneInterfaceCallbacks);
        gpuRenderTargetBindings.keySet().retainAll(interfaces.renderTarget2DOrder());
        {
            var gridUses = new ArrayList<Use>();
            for (int index = 0; index < gpuEmitters.length; index++) {
                var emitter = gpuEmitters[index];
                if (emitter == null || emitter.disabled()) continue;
                var source = definition.emitters().get(index);
                var program = ((NiagaraDefinition.GpuSimulation)source.simulation()).metadata();
                var names = new HashSet<String>();
                for (var item : program.getAsJsonArray("interfaces")) {
                    var binding = item.getAsJsonObject();
                    if (binding.has("grid2d_collection") || binding.has("grid3d_collection"))
                        names.add(SourceNames.canonical(binding.get("compile_name").getAsString()));
                }
                if (!names.isEmpty()) gridUses.add(new Use(source.sourceName(), program,
                        emitter.dataInterfaceSelections(names)));
            }
            interfaces.configureGridCollections(gridUses);
        }
        appliedObjectReaderRemaps = null;
    }
    private static boolean cameraUsed(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces"))
            if (item.getAsJsonObject().get("class").getAsString().equals("NiagaraDataInterfaceCamera")) return true;
        return false;
    }

    private void registerCpuInterfaces(VectorProgram program) {
        for (var binding : program.external()) {
            if (binding.interfaceType().equals("fast_path")) continue;
            if (program.parameterStore() == null || binding.interfaceKey() == null)
                standaloneInterfaceCallbacks.add(binding.interfaceId());
            if (binding.interfaceType().equals("array")) interfaces.array(binding);
            else if (binding.interfaceType().equals("simple_counter")) interfaces.counter(binding.interfaceId(), binding.interfaceData());
            else if (binding.interfaceType().equals("collision_query")) interfaces.registerCollision(binding.interfaceId());
            else if (binding.interfaceType().equals("mouse_position")) interfaces.registerViewQuery("NiagaraDataInterfaceMousePosition");
            else if (binding.interfaceType().equals("movie_render_queue")) interfaces.registerViewQuery("NiagaraDataInterfaceMRQ");
            else if (binding.interfaceType().equals("volume_cache"))
                interfaces.volumeCache(binding.interfaceId(), binding.interfaceData().getAsJsonObject("volume_cache"));
            else if (binding.interfaceType().equals("hair_strands")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerHairStrands(binding.interfaceId(), binding.interfaceData());
            }
            else if (binding.interfaceType().equals("chaos_destruction")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerChaosDestruction(binding.interfaceId(), binding.interfaceData());
            }
            else if (binding.interfaceType().equals("geometry_collection")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerGeometryCollection(binding.interfaceId(), binding.interfaceData());
            }
            else if (binding.interfaceType().equals("gaussian_splat_reader")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerGaussianSplatReader(binding.interfaceId(), NiagaraGaussianSplatReader.Definition.read(
                            binding.interfaceData().getAsJsonObject("gaussian_splat_reader")), definition.gaussianSplatInputs());
            }
            else if (binding.interfaceType().equals("gaussian_splat_writer")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerGaussianSplatWriter(binding.interfaceId(), NiagaraGaussianSplatWriter.Definition.read(
                            binding.interfaceData().getAsJsonObject("gaussian_splat_writer")), definition.gaussianSplatInputs(),
                            cpuTargetRuntime().maxStorageBufferBytes());
            }
            else if (binding.interfaceType().equals("property_interface")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerPropertyInterface(binding.interfaceId(), NiagaraPropertyInterface.Definition.read(binding.interfaceData()));
            }
            else if (binding.interfaceType().equals("rigid_mesh_collision")) {
                if (binding.name().equals("FindActors")) interfaces.scriptedRigidMeshSearch();
                interfaces.registerRigidMesh(binding.interfaceId(), binding.interfaceData());
            }
            else if (binding.interfaceType().equals("scene_capture_2d")) {
                if (binding.interfaceKey() == null) interfaces.registerSceneCapture(binding.interfaceId(), binding.interfaceData());
            }
            else if (binding.interfaceType().equals("landscape")) {
                if (binding.interfaceKey() == null)
                    interfaces.registerLandscape(binding.interfaceId(), binding.interfaceData(), NiagaraLandscape.Usage.NONE);
            }
            else if (binding.interfaceType().equals("virtual_texture_sample")) {
                if (binding.interfaceKey() == null) interfaces.registerVirtualTexture(binding.interfaceId(), binding.interfaceData());
            }
            else if (binding.interfaceType().equals("texture_2d") || binding.interfaceType().equals("texture_array")
                    || binding.interfaceType().equals("texture_cube") || binding.interfaceType().equals("texture_volume"))
                interfaces.registerTexture(binding.interfaceId(), binding.interfaceData());
            else if (binding.interfaceType().equals("render_target_2d")) interfaces.registerRenderTarget2D(binding.interfaceId(), binding.interfaceData(),
                    RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
            else if (binding.interfaceType().equals("render_target_array") || binding.interfaceType().equals("render_target_volume")
                    || binding.interfaceType().equals("render_target_cube"))
                interfaces.registerDimensionalTarget(binding.interfaceId(), binding.interfaceData(),
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
            else if (binding.interfaceType().equals("camera"))
                interfaces.registerCamera(binding.interfaceId(), new CameraDefinition(binding.controllerIndex(), binding.requireCurrent()));
        }
    }

    private static boolean usesSynchronousCollision(VectorProgram program) {
        return program.external().stream().anyMatch(binding -> binding.interfaceType().equals("collision_query")
                && binding.name().equals(NiagaraCollisionState.SYNC));
    }

    private void registerGpuInterfaces(NiagaraDefinition.GpuSimulation simulation) {
        var metadata = simulation.metadata();
        boolean typedStore = simulation.parameterStore() != null || metadata.has("execution_store");
        for (var item : metadata.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            String type = binding.get("class").getAsString();
            if ((!typedStore || !binding.has("interface_key")) && binding.has("interface_id"))
                standaloneInterfaceCallbacks.add(binding.get("interface_id").getAsString());
            interfaces.registerViewQuery(type);
            if (type.startsWith("NiagaraDataInterfaceArray") && binding.has("array_data")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.array(id, binding.get("resolved_parameter_name").getAsString(), binding.getAsJsonObject("array_data"));
                gpuMutableIds.add(id);
            } else if (type.equals("NiagaraDataInterfaceSimpleCounter")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.counter(id, binding.getAsJsonObject("simple_counter"));
                gpuMutableIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceCamera")) {
                JsonObject data = binding.has("camera") ? binding.getAsJsonObject("camera")
                        : binding.has("properties") && binding.get("properties").isJsonObject() ? binding.getAsJsonObject("properties") : new JsonObject();
                boolean compiled = binding.has("camera");
                String index = compiled ? "controller_index" : "PlayerControllerIndex";
                String current = compiled ? "require_current" : "bRequireCurrentFrameData";
                interfaces.registerCamera(binding.get("interface_id").getAsString(), new CameraDefinition(
                        data.has(index) ? data.get(index).getAsInt() : 0, !data.has(current) || data.get(current).getAsBoolean()));
            }
            else if (type.equals("NiagaraDataInterfaceTexture") || type.equals("NiagaraDataInterface2DArrayTexture")
                    || type.equals("NiagaraDataInterfaceCubeTexture") || type.equals("NiagaraDataInterfaceVolumeTexture")) {
                String id = binding.get("interface_id").getAsString();
                String field = switch (type) {
                    case "NiagaraDataInterface2DArrayTexture" -> "texture_array";
                    case "NiagaraDataInterfaceCubeTexture" -> "texture_cube";
                    case "NiagaraDataInterfaceVolumeTexture" -> "texture_volume";
                    default -> "texture_2d";
                };
                interfaces.registerTexture(id, binding.getAsJsonObject(field));
                if (binding.has("shader_symbol")) gpuTextureIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceDynamicMesh")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerDynamicMesh(id, NiagaraDynamicMeshDefinition.read(binding.getAsJsonObject("dynamic_mesh")));
                if (binding.has("shader_symbol")) gpuDynamicMeshIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceMemoryBuffer")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerMemoryBuffer(id, NiagaraMemoryBufferDefinition.read(binding.getAsJsonObject("memory_buffer")),
                        memoryBufferMaximum(), false);
                if (binding.has("shader_symbol")) gpuMemoryBufferIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceNeighborGrid3D")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerNeighborGrid(id, NeighborGridDefinition.read(binding.getAsJsonObject("neighbor_grid")),
                        neighborGridDeviceMaximum(), frame.platform);
                if (binding.has("shader_symbol")) gpuNeighborGridIds.add(id);
            }
            else if (binding.has("neighbor_query")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerNeighborQuery(id, NiagaraNeighborQuery.Definition.read(binding.getAsJsonObject("neighbor_query")),
                        neighborQueryDeviceMaximum());
                if (binding.has("shader_symbol")) gpuNeighborQueryIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceRasterizationGrid3D")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerRasterizationGrid(id, RasterizationGridDefinition.read(binding.getAsJsonObject("rasterization_grid")),
                        rasterizationGridMaximum());
                if (binding.has("shader_symbol")) gpuRasterizationGridIds.add(id);
            }
            else if (binding.has("velocity_grid")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerVelocityGrid(id, VelocityGridDefinition.read(binding.getAsJsonObject("velocity_grid")));
                if (binding.has("shader_symbol")) gpuVelocityGridIds.add(id);
            }
            else if (binding.has("socket_reader")) {
                interfaces.registerSocketReader(binding.get("interface_id").getAsString(),
                        NiagaraSocketReader.Definition.read(binding.getAsJsonObject("socket_reader")));
                if (binding.has("shader_symbol")) gpuSocketIds.add(binding.get("interface_id").getAsString());
            }
            else if (binding.has("spline")) {
                String id = binding.get("interface_id").getAsString();
                boolean gpu = binding.has("shader_symbol");
                interfaces.registerSpline(id, NiagaraSpline.Definition.read(binding.getAsJsonObject("spline")), gpu);
                if (gpu) gpuSplineIds.add(id);
            }
            else if (binding.has("int_render_target_2d")) {
                interfaces.registerIntRenderTarget2D(binding.get("interface_id").getAsString(),
                        NiagaraIntRenderTarget2D.Definition.read(binding.getAsJsonObject("int_render_target_2d")),
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform, binding.has("shader_symbol"));
                if (binding.has("shader_symbol")) gpuIntRenderTargetIds.add(binding.get("interface_id").getAsString());
            }
            else if (binding.has("data_table")) {
                interfaces.registerDataTable(binding.get("interface_id").getAsString(),
                        NiagaraDataTable.Definition.read(binding.getAsJsonObject("data_table")), instanceParameterStores.instance());
            }
            else if (binding.has("gaussian_splat_reader")) {
                interfaces.registerGaussianSplatReader(binding.get("interface_id").getAsString(),
                        NiagaraGaussianSplatReader.Definition.read(binding.getAsJsonObject("gaussian_splat_reader")),
                        definition.gaussianSplatInputs());
            }
            else if (binding.has("gaussian_splat_writer")) {
                interfaces.registerGaussianSplatWriter(binding.get("interface_id").getAsString(),
                        NiagaraGaussianSplatWriter.Definition.read(binding.getAsJsonObject("gaussian_splat_writer")),
                        definition.gaussianSplatInputs(), cpuTargetRuntime().maxStorageBufferBytes());
            }
            else if (binding.has("property_interface")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerPropertyInterface(id, NiagaraPropertyInterface.Definition.read(binding.getAsJsonObject("property_interface")));
                if (binding.has("shader_symbol")) gpuPropertyInterfaceIds.add(id);
            }
            else if (binding.has("int_distribution")) {
                interfaces.registerIntDistribution(binding.get("interface_id").getAsString(),
                        NiagaraIntDistribution.read(binding.getAsJsonObject("int_distribution")));
            }
            else if (binding.has("grid2d_collection")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerGrid2DCollection(id, Grid2DCollectionDefinition.read(binding.getAsJsonObject("grid2d_collection")),
                        frame.platform, false);
                if (binding.has("shader_symbol")) gpuGrid2DCollectionIds.add(id);
            }
            else if (binding.has("grid3d_collection")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerGrid3DCollection(id, Grid3DCollectionDefinition.read(binding.getAsJsonObject("grid3d_collection")),
                        frame.platform, false);
                if (binding.has("shader_symbol")) gpuGrid3DCollectionIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceSimCacheReader")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerSimCacheReader(id, NiagaraSimCacheReaderDefinition.read(binding.getAsJsonObject("sim_cache_reader")));
                if (binding.has("shader_symbol")) gpuSimCacheReaderIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceSparseVolumeTexture")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerSparseVolume(id, SparseVolumeInterfaceDefinition.read(binding.getAsJsonObject("sparse_volume_texture")));
                if (binding.has("shader_symbol")) gpuSparseVolumeIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceVolumeCache")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.volumeCache(id, binding.getAsJsonObject("volume_cache"));
                if (binding.has("shader_symbol")) gpuVolumeCacheIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfacePhysicsAsset") && binding.has("shader_symbol")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerPhysicsAsset(id, binding.getAsJsonObject("physics_asset"), definition.skeletalMeshInputs());
                gpuPhysicsAssetIds.add(id);
            }
            else if (binding.has("hair_strands")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerHairStrands(id, binding.getAsJsonObject("hair_strands"));
                if (binding.has("shader_symbol")) gpuHairStrandsIds.add(id);
            }
            else if (binding.has("chaos_destruction")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerChaosDestruction(id, binding.getAsJsonObject("chaos_destruction"));
                if (binding.has("shader_symbol")) gpuChaosDestructionIds.add(id);
            }
            else if (binding.has("geometry_collection")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerGeometryCollection(id, binding.getAsJsonObject("geometry_collection"));
                if (binding.has("shader_symbol")) gpuGeometryCollectionIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceRigidMeshCollisionQuery")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerRigidMesh(id, binding.getAsJsonObject("rigid_mesh_collision"));
                if (binding.has("shader_symbol")) gpuRigidMeshIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceSceneCapture2D")) {
                if (binding.has("interface_key")) continue;
                String id = binding.get("interface_id").getAsString();
                interfaces.registerSceneCapture(id, binding.getAsJsonObject("scene_capture_2d"));
                if (binding.has("shader_symbol")) gpuSceneCaptureIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceLandscape") && binding.has("shader_symbol")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerLandscape(id, binding.getAsJsonObject("landscape"),
                        NiagaraLandscape.Usage.read(binding.getAsJsonObject("landscape_usage")));
                gpuLandscapeIds.add(id);
            }
            else if ((type.equals("NiagaraDataInterfaceVirtualTexture") || type.equals("NiagaraDataInterfaceVirtualTextureSample"))
                    && binding.has("shader_symbol") && !binding.has("interface_key")) {
                String id = binding.get("interface_id").getAsString();
                interfaces.registerVirtualTexture(id, binding.getAsJsonObject("virtual_texture"));
                gpuVirtualTextureIds.add(id);
            }
            else if (type.equals("NiagaraDataInterfaceRenderTarget2D")) {
                interfaces.registerRenderTarget2D(binding.get("interface_id").getAsString(), binding.getAsJsonObject("render_target_2d"),
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
                if (binding.has("shader_symbol")) gpuRenderTargetBindings.putIfAbsent(binding.get("interface_id").getAsString(), binding.deepCopy());
            } else if (type.equals("NiagaraDataInterfaceRenderTarget2DArray") || type.equals("NiagaraDataInterfaceRenderTargetVolume")
                    || type.equals("NiagaraDataInterfaceRenderTargetCube")) {
                String id = binding.get("interface_id").getAsString();
                String field = switch (type) {
                    case "NiagaraDataInterfaceRenderTargetVolume" -> "render_target_volume";
                    case "NiagaraDataInterfaceRenderTargetCube" -> "render_target_cube";
                    default -> "render_target_array";
                };
                interfaces.registerDimensionalTarget(id, binding.getAsJsonObject(field),
                        RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
                if (binding.has("shader_symbol") && !binding.has("interface_key")) gpuDimensionalTargetIds.add(id);
            }
        }
    }

    private void registerMaterialInterfaces() {
        standaloneInterfaceCallbacks.addAll(materialRenderTargets.keySet());
        standaloneInterfaceCallbacks.addAll(materialDimensionalTargets.keySet());
        standaloneInterfaceCallbacks.addAll(materialSceneCaptures.keySet());
        materialRenderTargets.forEach((id, data) -> interfaces.registerRenderTarget2D(id, data,
                RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform));
        materialDimensionalTargets.forEach((id, data) -> interfaces.registerDimensionalTarget(id, data,
                RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform));
        materialSceneCaptures.forEach(interfaces::registerSceneCapture);
    }

    private void registerTargetInstances() {
        for (var target : definition.renderTargets()) {
            if (definition.instanceStores() == null) standaloneInterfaceCallbacks.add(target.interfaceId());
            var data = target.data();
            interfaces.registerRenderTarget2D(target.interfaceId(), data,
                    RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize(), frame.platform);
            if (target.usedGpu()) {
                var binding = new JsonObject();
                binding.addProperty("interface_id", target.interfaceId());
                binding.add("render_target_2d", data);
                gpuRenderTargetBindings.putIfAbsent(target.interfaceId(), binding);
            }
        }
    }

    public void prepareRenderTargetResources() {
        prepareRenderTargetResources(null);
    }

    public void prepareRenderTargetResources(@Nullable NiagaraGpuPrograms programs) {
        RenderSystem.assertOnRenderThread();
        if (!execute || !initialized || complete) return;
        if (cachePlayer != null) {
            prepareCachedRenderTargets(programs);
            return;
        }
        boolean includeCookedOut = !frame.platform.renderTargetIgnoreCookedOut();
        var targetOrder = interfaces.renderTarget2DOrder();
        var dimensionalOrder = interfaces.dimensionalTargetOrder();
        if (gpuRenderTargets == null && (programs != null && !gpuRenderTargetBindings.isEmpty()
                || includeCookedOut && !targetOrder.isEmpty())) {
            gpuRenderTargets = programs == null ? new NiagaraGpuRenderTargets(cpuTargetRuntime(), interfaces)
                    : new NiagaraGpuRenderTargets(programs, interfaces);
        }
        if (programs != null && gpuRenderTargets != null) gpuRenderTargets.gpuPrograms(programs);
        for (String id : targetOrder) {
            var source = interfaces.renderTarget2D(id);
            var control = source.snapshot();
            var binding = gpuRenderTargetBindings.get(id);
            if (gpuRenderTargets != null && (programs != null && binding != null || includeCookedOut || gpuRenderTargets.hasImage(id))) {
                if (binding == null) gpuRenderTargets.register(id);
                else gpuRenderTargets.register(binding);
                gpuRenderTargets.prepare(id, control);
            } else if (control.target() != null) {
                source.applyUserControls(programs == null ? cpuTargetRuntime() : programs.runtime(), control);
            }
        }
        if (gpuDimensionalTargets == null && (programs != null && !gpuDimensionalTargetIds.isEmpty()
                || includeCookedOut && !dimensionalOrder.isEmpty())) {
            gpuDimensionalTargets = programs == null ? new NiagaraGpuDimensionalRenderTargets(cpuTargetRuntime())
                    : new NiagaraGpuDimensionalRenderTargets(programs);
            interfaces.dimensionalTargetExposure(gpuDimensionalTargets::exposed);
        }
        if (programs != null && gpuDimensionalTargets != null) gpuDimensionalTargets.gpuPrograms(programs);
        for (String id : dimensionalOrder) {
            var control = interfaces.dimensionalTarget(id).snapshot();
            if (gpuDimensionalTargets != null && (programs != null && gpuDimensionalTargetIds.contains(id)
                    || includeCookedOut || gpuDimensionalTargets.hasImage(id))) {
                gpuDimensionalTargets.prepare(id, control);
            } else if (control.userTarget() != null && !control.userTarget().closed()) {
                control.userTarget().configure(programs == null ? cpuTargetRuntime() : programs.runtime(), control.surface(), control.extentZ());
            }
        }
    }

    private void prepareCachedRenderTargets(@Nullable NiagaraGpuPrograms programs) {
        var storage = publishedCacheInterfaces();
        if (storage == null || storage.targets().isEmpty() && storage.volumes().isEmpty()) return;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (!storage.targets().isEmpty()) {
            if (gpuRenderTargets == null)
                gpuRenderTargets = programs == null ? new NiagaraGpuRenderTargets(cpuTargetRuntime(), interfaces)
                        : new NiagaraGpuRenderTargets(programs, interfaces);
            for (var entry : storage.targets().entrySet()) {
                var saved = entry.getValue();
                var source = interfaces.renderTarget2D(entry.getKey());
                source.readCache(saved.width(), saved.height(), saved.format());
                source.captureTarget(input.textureParameters(), instanceParameterStores.instance());
                gpuRenderTargets.restoreCache(encoder, entry.getKey(), saved);
            }
        }
        if (!storage.volumes().isEmpty()) {
            if (gpuDimensionalTargets == null) {
                gpuDimensionalTargets = programs == null ? new NiagaraGpuDimensionalRenderTargets(cpuTargetRuntime())
                        : new NiagaraGpuDimensionalRenderTargets(programs);
                interfaces.dimensionalTargetExposure(gpuDimensionalTargets::exposed);
            }
            for (var entry : storage.volumes().entrySet()) {
                var saved = entry.getValue();
                var source = interfaces.dimensionalTarget(entry.getKey());
                source.readCache(saved);
                source.captureTarget(input.textureParameters(), instanceParameterStores.instance());
                gpuDimensionalTargets.restoreCache(encoder, entry.getKey(), source.snapshot(), saved);
            }
        }
    }

    private ComputeRuntime cpuTargetRuntime() {
        if (cpuTargetRuntime == null) cpuTargetRuntime = ComputeRuntime.create(RenderSystem.getDevice());
        return cpuTargetRuntime;
    }

    private int memoryBufferMaximum() {
        var runtime = cpuTargetRuntime();
        return (int)Math.max(0, Math.min(Integer.MAX_VALUE - 4L,
                Math.min(runtime.maxTexelBufferElements(), runtime.maxStorageBufferBytes() / Integer.BYTES - 4)));
    }

    private int neighborGridDeviceMaximum() {
        var runtime = cpuTargetRuntime();
        return (int)Math.min(Integer.MAX_VALUE, Math.min(runtime.maxTexelBufferElements(), runtime.maxStorageBufferBytes() / Integer.BYTES));
    }

    private long neighborQueryDeviceMaximum() { return cpuTargetRuntime().maxStorageBufferBytes() / Integer.BYTES; }

    private int rasterizationGridMaximum() {
        var configured = frame.platform.console(NiagaraRasterizationGrid.MAXIMUM_CELLS_VARIABLE);
        return configured == null ? NiagaraRasterizationGrid.DEFAULT_MAXIMUM_CELLS : configured.bits();
    }

    public boolean needsCamera() { return needsCamera || interfaces.hasCameras(); }
    public boolean requiresUpdatedCamera() {
        return definition.timing().requireCurrentFrameData() && currentCameraRequired;
    }
    public boolean requiresUpdatedHostData() {
        return hostPrerequisiteTickGroup().ordinal() >= ComponentTickGroup.TG_EndPhysics.ordinal();
    }
    public ComponentTickGroup hostPrerequisiteTickGroup() {
        if (cachePlayer != null || !definition.timing().requireCurrentFrameData()) return ComponentTickGroup.TG_PrePhysics;
        var group = interfaces.actorComponentTickGroup(actorComponentInputs, localActorComponents, parameterSources::savedActorComponent);
        var attached = actorComponentInputs.attached();
        if (attached != null) {
            var end = attached.blendsSkeletalPhysics()
                    ? ComponentTickGroup.latest(attached.tickEnd(), ComponentTickGroup.TG_EndPhysics) : attached.tickEnd();
            group = ComponentTickGroup.latest(group, ComponentTickGroup.afterPrerequisite(attached.tickStart(), end));
        }
        group = ComponentTickGroup.latest(group, interfaces.skeletalMeshTickGroup(skeletalMeshInputs,
                instanceParameterStores::hasObjectParameterOverride));
        group = ComponentTickGroup.latest(group, interfaces.socketReaderTickGroup(socketInputs, staticMeshInputs, skeletalMeshInputs,
                definition.staticMeshInputs(), definition.skeletalMeshInputs(), instanceParameterStores::hasObjectParameterOverride,
                parameterSources::savedActorComponent));
        if (currentCameraRequired) group = ComponentTickGroup.latest(group, ComponentTickGroup.TG_PostUpdateWork);
        var channelGroups = interfaces.dataChannelTickRange(frame.platform.dataChannelsForcedReadTickGroup());
        group = ComponentTickGroup.latest(group, channelGroups.earliest());
        boolean conflict = group.ordinal() > channelGroups.latest().ordinal();
        if (conflict && !channelTickConflictReported) {
            LOGGER.warn("Niagara system {} requires tick group {} after data channel write deadline {}",
                    definition.sourceSystem(), group, channelGroups.latest());
        }
        channelTickConflictReported = conflict;
        return group;
    }
    public boolean usesNonOverlappingTickPass() { return cachePlayer == null && frame.platform.worldUsesTickPasses() && interfaces.hasDataChannels(); }
    public boolean requiresCurrentGpuDataChannel() {
        for (String identity : gpuDataChannelReaderIds)
            if (interfaces.dataChannel(identity).definition().readCurrent()) return true;
        return false;
    }
    public MaterialInstanceParameters.TextureValues materialTextures() { return interfaces.materialTextures(); }
    public Map<String, EffectHandle.ArrayParameter> arrayResults() { return interfaces.arrayResults(); }

    public void prepare(double now, float delta, EffectHandle.Input input, Vec3 camera, boolean emitting) {
        prepare(now, delta, input, camera, emitting, null);
    }

    public void prepare(double now, float delta, EffectHandle.Input input, Vec3 camera, boolean emitting,
                        NiagaraCameraFrame cameraFrame) {
        prepare(now, now, now, delta, input, camera, emitting, cameraFrame);
    }

    public void prepare(double now, double engineTime, double realTime, float delta, EffectHandle.Input input, Vec3 camera, boolean emitting,
                        NiagaraCameraFrame cameraFrame) {
        prepare(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame, false);
    }

    public void prepareCacheStep(double now, double engineTime, double realTime, float delta, EffectHandle.Input input,
                                 Vec3 camera, boolean emitting, @Nullable NiagaraCameraFrame cameraFrame) {
        if (worldSimulation != null) worldSimulation.detach(this);
        prepare(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame, true);
    }

    public NiagaraTickClock.Plan planManualTick(float delta) {
        return fixedClock == null ? new NiagaraTickClock.Plan(1, delta, delta, false)
                : fixedClock.planDelta(delta, maximumFrameSteps());
    }

    public float componentAge() { return frame.age(); }
    public double lastUpdateEngineTime() { return frame.engineTime; }

    public NiagaraTickClock.Plan planAgeTick(NiagaraAgeClock.Step step) {
        if (step.count() == 0) return new NiagaraTickClock.Plan(0, step.delta(), step.delta(), false);
        if (step.multiTick()) return new NiagaraTickClock.Plan(step.count(), step.count() * step.delta(), step.delta(), true);
        return planManualTick(step.delta());
    }

    public void advanceAgeMultiTick(float delta) {
        if (fixedClock != null) fixedClock.advanceMultiTick(delta);
    }

    public NiagaraTickClock.Plan planFrame(double now, NiagaraTickClock.Plan normal) {
        if (cachePlayer != null) return new NiagaraTickClock.Plan(1, normal.engineDelta(), normal.engineDelta(), false);
        if (normal.fixed()) return normal;
        float delta = componentDelta(normal.engineDelta());
        if (fixedClock != null) return fixedClock.plan(now, delta, maximumFrameSteps());
        return delta == normal.engineDelta() ? normal : new NiagaraTickClock.Plan(normal.steps(), delta, delta, false);
    }

    public void componentTickSettings(NiagaraComponentTickSettings settings) { componentTickSettings = settings; }

    public void ownerVelocity(@Nullable Velocity velocity) { frame.actorVelocity(velocity); }
    public void attachmentBirthsAllowed(boolean allowed) { frame.attachmentBirthsAllowed = allowed; }

    public float componentDelta(float hostDelta) {
        return cachePlayer == null ? hostDelta * componentTickSettings.customTimeDilation() : hostDelta;
    }

    public boolean requiresWorldTileReset(double now, EffectHandle.Pose pose, NiagaraTickClock.Plan normal,
                                          NiagaraAgeClock.@Nullable Step manual) {
        if (cachePlayer != null || complete || !initialized || frame.systemExecutionState != 0
                || !frame.requiresTileReset(pose.position())) return false;
        if (manual != null) {
            if (manual.count() == 0) return false;
            if (fixedClock == null || manual.multiTick()) return !frame.platform.skipsSystemTick(manual.delta());
            return !frame.platform.skipsSystemTick(definition.timing().fixedDelta())
                    && fixedClock.hasManualStep(manual.delta(), maximumFrameSteps());
        }
        if (normal.steps() == 0) return false;
        if (normal.fixed()) return !frame.platform.skipsSystemTick(normal.systemDelta());
        float delta = componentDelta(normal.engineDelta());
        if (fixedClock == null) return now != lastTime && !frame.platform.skipsSystemTick(delta);
        return !frame.platform.skipsSystemTick(definition.timing().fixedDelta())
                && fixedClock.hasStep(now, delta, maximumFrameSteps());
    }

    public void resetComponentSimulation() {
        resetSimulationData();
        lastTime = Double.NaN;
        boundsPublished = false;
        dynamicBindingsPublished = false;
        execute = false;
    }

    public void completeForPool() {
        completeEmitters(3);
        releaseCompletedState();
    }

    public void reactivateKeepingParticles() {
        if (complete) {
            resetComponentSimulation();
            return;
        }
        boolean keepStatelessRenderState = cachePlayer == null && (initialized || statelessRestartRenderPending);
        statelessRestartRenderPending = false;
        destination.clear();
        frame.resetAge();
        frame.systemExecutionState = 0;
        seed = definition.systemDeterministic() ? definition.systemRandomSeed() : NiagaraSeed.next();
        if (nativeSystemState != null) nativeSystemState.reset(systemSeed());
        for (int index = 0; index < emitters.length; index++) {
            if (emitters[index] instanceof NiagaraEmitter emitter) {
                emitter.sourceSeedOffset(configuredSeedOffset());
                emitter.resetSimulation(false);
            } else if (emitters[index] instanceof NiagaraStatelessEmitter emitter) {
                emitter.restartKeepingSpawns();
                statelessRestartRenderPending |= keepStatelessRenderState;
            } else if (statelessGpu[index] != null) {
                statelessGpu[index].restartKeepingSpawns();
                statelessRestartRenderPending |= keepStatelessRenderState;
            } else if (gpuEmitters[index] != null) {
                gpuEmitters[index].sourceSeedOffset(configuredSeedOffset());
                gpuEmitters[index].resetSimulation(false);
            }
        }
        // Stateless emitters can draw retained particles until the next System Spawn replaces their renderer inputs.
        if (!statelessRestartRenderPending) current.clear();
        lastTime = Double.NaN;
        execute = false;
        initialized = false;
        componentCompletionNotified = false;
    }

    public float age() { return frame.age(); }
    public void scalabilityLod(float distance, float maximum) { frame.scalabilityLod(distance, maximum); }
    public void cullProxyLod(float maximum) { frame.cullProxyLod(maximum); }
    public void significanceIndex(int index) { frame.significanceIndex(index); }
    public void renderedAt(double time) { frame.renderedAt(time); }
    public boolean renderedSince(double time) { return frame.renderedSince(time); }
    public void resetRenderTime() { frame.resetRenderTime(); }
    public float timeSinceRendered(double time) { return frame.timeSinceRendered(time); }

    public int maximumFrameSteps() { return fixedClock == null ? 1 : frame.platform.maxSystemTickSubsteps(); }

    public void resetWarmupAge() { frame.resetAge(); lastTime = Double.NaN; }

    public boolean initialized() { return initialized; }
    public boolean hasRendererState() { return initialized || statelessRestartRenderPending; }
    public boolean hasEmitterRendererState(int index) {
        return initialized || statelessRestartRenderPending
                && (emitters[index] instanceof NiagaraStatelessEmitter || statelessGpu[index] != null);
    }
    public NiagaraPlatformEnvironment platformEnvironment() { return frame.platform; }
    public NiagaraPlatforms.Publication platformPublication() { return new NiagaraPlatforms.Publication(frame.platform, frame.platformSequence); }
    public boolean applyPlatformPublication(NiagaraPlatforms.Publication publication) {
        platformSets.synchronize(publication);
        if (!applyPlatformEnvironment(publication.environment())) return false;
        frame.platformSequence = publication.sequence();
        return true;
    }
    public boolean applyPlatformEnvironment(NiagaraPlatformEnvironment next) {
        NiagaraPlatformEnvironment previous = frame.platform;
        if (previous == next) return true;
        if (previous.quality() != next.quality() || previous.availableQualityMask() != next.availableQualityMask()
                || !previous.profileHierarchy().equals(next.profileHierarchy())
                || previous.statelessFeatures() != next.statelessFeatures()) return false;
        var refreshSets = definition.platformRefreshSets();
        if (refreshSets != null) {
            for (var set : refreshSets) if (set.consoleChanged(previous, next)) return false;
        } else {
            if (definition.platforms().consoleChanged(previous, next) || platformSets.consoleChanged(previous, next)) return false;
            for (var emitter : definition.emitters()) if (emitter.scalability().consoleChanged(previous, next)) return false;
        }
        frame.platform = next;
        return true;
    }
    public void collectionSnapshot(NiagaraCollectionSnapshot snapshot) {
        boolean changedInterfaces = parameterSources.collectionSnapshot(Objects.requireNonNull(snapshot));
        frame.collections = snapshot;
        if (changedInterfaces && !complete) rebindCollectionInterfaces();
    }
    public void objectPropertyInputs(ObjectPropertyInputs inputs, List<ObjectPropertyInputs.Remap> remaps) {
        objectPropertyInputs = Objects.requireNonNull(inputs);
        objectReaderRemaps = Objects.requireNonNull(remaps);
    }

    public void socketInputs(NiagaraSocketInputs inputs) { socketInputs = inputs; }
    public void splineInputs(NiagaraSplineInputs inputs) { splineInputs = inputs; }
    public void movieRenderSample(NiagaraMovieRenderSample sample) { movieRenderSample = sample; }
    public void randomSeedOffset(@Nullable Integer value) { randomSeedOffset = value; }
    public void actorComponentInputs(ActorComponentInputs inputs, IntFunction<ActorComponentInputs.@Nullable Component> localPlayers) {
        actorComponentInputs = inputs;
        localActorComponents = localPlayers;
    }

    public void collisionWorld(@Nullable NiagaraCollisionWorld world) { collisionWorld = world; }
    public void staticMeshInputs(NiagaraStaticMeshInputs inputs) { staticMeshInputs = inputs; }
    public void skeletalMeshInputs(NiagaraSkeletalMeshInputs inputs) { skeletalMeshInputs = inputs; }
    public void rigidMeshInputs(NiagaraRigidMeshInputs inputs) { rigidMeshInputs = inputs; }
    public void geometryCollectionInputs(NiagaraGeometryCollectionInputs inputs) { geometryCollectionInputs = inputs; }
    public void propertyInterfaceInputs(NiagaraPropertyInterfaceInputs inputs) {
        propertyInterfaceInputs = inputs;
        interfaces.initializePropertyInterfaces(inputs);
    }
    public void objectParameterInputs(Map<Key, Reference> inputs) { instanceParameterStores.applyObjectParameters(inputs); }
    public void dataInterfaceInputs(Map<Key, Reference> inputs) {
        instanceParameterStores.applyDataInterfaces(inputs);
        if (!inputs.isEmpty()) registerDynamicGaussianSplatInterfaces(inputs);
    }

    private void registerDynamicGaussianSplatInterfaces(Map<Key, Reference> inputs) {
        var override = instanceParameterStores.override();
        for (Key key : inputs.keySet()) {
            if (override.contains(key) && override.dataInterface(key) instanceof NiagaraDataInterfaceObject object) {
                registerGaussianSplatInterfaces(object);
            }
        }
    }

    private void registerGaussianSplatInterfaces(NiagaraDataInterfaceObject object) {
        var gaussianSplat = object.gaussianSplatReader();
        if (gaussianSplat != null)
            interfaces.registerGaussianSplatReader(object.identity(), gaussianSplat, definition.gaussianSplatInputs());
        var splatWriter = object.gaussianSplatWriter();
        if (splatWriter != null)
            interfaces.registerGaussianSplatWriter(object.identity(), splatWriter,
                    definition.gaussianSplatInputs(), cpuTargetRuntime().maxStorageBufferBytes());
    }
    public void chaosScene(NiagaraChaosScene scene) { chaosScene = scene; }
    public void groomInputs(NiagaraGroomInputs inputs) { groomInputs = inputs; }
    public void requestChaosEvents(Consumer<EventDemand> enable) { interfaces.requestChaosEvents(enable); }
    public void sceneCaptureInputs(NiagaraSceneCaptureInputs inputs) { sceneCaptureInputs = inputs; }
    public void landscapeInputs(NiagaraLandscapeInputs inputs) { landscapeInputs = inputs; }
    public void virtualTextureInputs(NiagaraVirtualTextureInputs inputs) { virtualTextureInputs = inputs; }
    public void intDistributions(Map<String, NiagaraIntDistribution> inputs) { intDistributionInputs = inputs; }
    public void physicsField(@Nullable NiagaraPhysicsField value) { worldPhysicsField = value; }

    public void waterSources(NiagaraWaterScene scene, Map<String, String> sourceOverrides) {
        waterScene = scene;
        waterSourceOverrides = sourceOverrides;
    }

    private StreamedValue rendererStreamedTexture(int emitter, StreamedBinding binding) {
        var inputs = virtualTextureInputs.streaming();
        var assigned = inputs.get(binding.variable());
        if (binding.dataInterface() != null) {
            if (assigned != null) return new StreamedValue.Virtual(assigned);
            if (binding.staticExposure()) return savedStreamedMaterialTexture(binding.objectDefault());
            return new StreamedValue.Ordinary(() -> interfaces.exposedMaterialTarget(binding.variable(), binding.dataInterface(),
                    binding.child(), binding.interfaceId()));
        }
        var collection = binding.collection();
        if (collection != null) {
            if (assigned != null) return new StreamedValue.Virtual(assigned);
            var collections = frame.collections;
            boolean component = collections.component().contains(collection.path());
            var texture = component ? collections.component().getTexture(collection.path(), collection.name(), collection.sourceType())
                    : collection.systemOverride() ? null : collections.world().getTexture(collection.path(), collection.name(), collection.sourceType());
            return texture == null ? savedStreamedMaterialTexture(component ? collection.baseDefault() : binding.objectDefault())
                    : new StreamedValue.Ordinary(texture);
        }
        for (var objectInput : binding.objectInputs()) {
            var external = inputs.get(objectInput.variable());
            if (external != null) assigned = external;
        }
        if (assigned != null) return new StreamedValue.Virtual(assigned);
        var store = rendererParameterStores[emitter].renderer();
        var key = new Key(binding.variable(), binding.sourceType(), 1, binding.typeFlags(), Storage.OBJECT);
        if (store.contains(key)) {
            Object object = store.object(key);
            if (object instanceof NiagaraStreamingVirtualTexture texture) return new StreamedValue.Virtual(texture);
            if (object instanceof TextureParameter texture) return new StreamedValue.Ordinary(texture);
            var texture = virtualTextureAssets.get(NiagaraParameterStoreSources.sourceObjectPath(object));
            return texture == null ? StreamedValue.Default.BASE : new StreamedValue.Virtual(texture);
        }
        String fallback = binding.objectDefault();
        for (var objectInput : binding.objectInputs()) if (objectInput.defaultPresent()) fallback = objectInput.defaultTexture();
        return savedStreamedMaterialTexture(fallback);
    }

    private StreamedValue savedStreamedMaterialTexture(@Nullable String path) {
        if (path == null) return StreamedValue.Default.BASE;
        var texture = virtualTextureAssets.get(path);
        return texture == null ? StreamedValue.Default.UNPAGED : new StreamedValue.Virtual(texture);
    }

    public void virtualTextureAssets(Map<String, NiagaraStreamingVirtualTexture> assets, Map<String, NiagaraRuntimeVirtualTexture> placed) {
        virtualTextureAssets = assets;
        runtimeVirtualTextures = placed;
    }
    public void particleExportHandlers(Map<String, NiagaraParticleExport> handlers) { exportHandlers = handlers; }
    public void dataChannelWorld(ClientLevel level, EffectHandle owner) { channelWorld = level; channelOwner = owner; }

    public void componentOwnerVisibility(EffectHandle host) {
        var override = host.niagaraOwnerVisibility();
        frame.ownerVisibility = override == null ? definition.component().ownerVisibility() : override;
        frame.componentHost = host;
    }
    public @Nullable ClientLevel gpuDispatchWorld() { return channelWorld; }
    public void audioInputs(@Nullable NiagaraAudioBackend backend, Map<String, Overrides> overrides) {
        audioBackend = backend == null ? MinecraftNiagaraAudio.INSTANCE : backend;
        audioOverrides = overrides;
    }
    public void systemFixedBounds(@Nullable NiagaraBounds bounds) {
        if (Objects.equals(externalFixedBounds, bounds)) return;
        externalFixedBounds = bounds;
        interfaces.systemFixedBounds(bounds != null ? bounds : definition.systemFixedBoundsEnabled() ? definition.systemFixedBounds() : null);
    }

    public void emitterOverrides(NiagaraEmitterOverrides overrides) {
        if (overrides == emitterOverrides) return;
        for (int index = 0; index < emitters.length; index++) {
            var source = definition.emitters().get(index);
            var settings = overrides.get(source.name());
            if (overrides.fixedBoundsChangedSince(source.name(), emitterOverrides))
                Objects.requireNonNull(interfaces.emitterBounds(source.sourceName())).fixedBounds(settings.fixedBounds());
            boolean allowed = settings.spawningAllowed();
            if (allowed == emitterOverrides.get(source.name()).spawningAllowed()) continue;
            if (emitters[index] instanceof NiagaraEmitter emitter) emitter.spawningAllowed(allowed);
            else if (emitters[index] instanceof NiagaraStatelessEmitter emitter) emitter.spawningAllowed(allowed);
            else if (gpuEmitters[index] != null) gpuEmitters[index].spawningAllowed(allowed);
            else if (statelessGpu[index] != null) statelessGpu[index].spawningAllowed(allowed);
        }
        emitterOverrides = overrides;
    }

    private void applyObjectReaderRemaps() {
        if (appliedObjectReaderRemaps == objectReaderRemaps) return;
        var store = instanceParameterStores.instance();
        var remaps = new HashMap<String, Map<String, ObjectPropertyInputs.Remap>>();
        for (var remap : objectReaderRemaps) {
            var key = remap.reader();
            if (store.contains(key) && store.dataInterface(key) instanceof NiagaraDataInterfaceObject object
                    && object.propertyReader() != null)
                remaps.computeIfAbsent(object.identity(), ignored -> new HashMap<>())
                        .put(SourceNames.canonical(remap.graphName()), remap);
        }
        interfaces.applyObjectReaderRemaps(remaps, appliedObjectReaderRemaps == null);
        appliedObjectReaderRemaps = objectReaderRemaps;
    }

    public long dataInterfaceRevision() { return dataInterfaceRevision; }

    private void discardGpuInterfaceTicks() {
        RenderSystem.assertOnRenderThread();
        for (int index = 0; index < gpuTicks.size(); index++) {
            var ticks = gpuTicks.get(index);
            for (int tick = gpuTickCursor[index] - 1; tick >= 0; tick--) {
                var step = ticks.get(tick);
                if (updatesGpuCountFence(step)) {
                    gpuEmitters[index].releaseCountFence(step.particleCountFence());
                    break;
                }
            }
            if (gpuSimulations[index] != null && gpuTickCursor[index] != 0) gpuSimulations[index].finishBatch();
            ticks.clear();
            gpuPlans.get(index).clear();
            gpuTickCursor[index] = 0;
        }
        releaseGpuInterfaceResources();
    }

    private void rebindCollectionInterfaces() {
        flushDataInterfaceSources();
        discardGpuInterfaceTicks();
        interfaces.destroyInstanceResources();
        platformObjects.clear();
        platformObjects.addAll(basePlatformObjects);
        interfaces.clearCollectionArrayBindings(parameterSources.collectionInterfaceNames());
        registerDefaultInterfaceStates();
        Set<String> activeMutableInterfaces = new HashSet<>(baseMutableInterfaces);
        initializeDataInterfaceStates(activeMutableInterfaces);
        interfaces.initializePropertyInterfaces(propertyInterfaceInputs);
        interfaces.recordChannelCache(channelCacheRequest);
        interfaces.retainArrays(activeMutableInterfaces);
        platformSets.retainObjects(platformObjects);
        dataInterfaceRevision++;
        if (!bindSystemInterfaces()) releaseCompletedState();
    }

    private void registerDefaultInterfaceStates() {
        gpuRenderTargetBindings.clear();
        gpuMutableIds.clear();
        gpuTextureIds.clear();
        gpuStaticMeshIds.clear();
        gpuSkeletalMeshIds.clear();
        gpuParticleExportIds.clear();
        gpuDataChannelReaderIds.clear();
        gpuDataChannelWriterIds.clear();
        gpuAudioAnalysisIds.clear();
        gpuSimCacheReaderIds.clear();
        gpuSparseVolumeIds.clear();
        gpuVolumeCacheIds.clear();
        gpuPhysicsAssetIds.clear();
        gpuRigidMeshIds.clear();
        gpuGeometryCollectionIds.clear();
        gpuPropertyInterfaceIds.clear();
        gpuChaosDestructionIds.clear();
        gpuHairStrandsIds.clear();
        gpuSceneCaptureIds.clear();
        gpuLandscapeIds.clear();
        gpuVirtualTextureIds.clear();
        gpuMemoryBufferIds.clear();
        gpuNeighborGridIds.clear();
        gpuNeighborQueryIds.clear();
        gpuRasterizationGridIds.clear();
        gpuVelocityGridIds.clear();
        gpuGrid3DCollectionIds.clear();
        gpuGrid2DCollectionIds.clear();
        gpuIntRenderTargetIds.clear();
        gpuSocketIds.clear();
        gpuSplineIds.clear();
        gpuDynamicMeshIds.clear();
        gpuDimensionalTargetIds.clear();
        registerTargetInstances();
        registerMaterialInterfaces();
        registerCpuInterfaces(definition.systemSpawn());
        registerCpuInterfaces(definition.systemUpdate());
        for (var source : definition.emitters()) {
            if (source.simulation() instanceof NiagaraDefinition.CpuSimulation cpu) {
                registerCpuInterfaces(cpu.spawn());
                registerCpuInterfaces(cpu.update());
            } else if (source.simulation() instanceof NiagaraDefinition.GpuSimulation gpu) registerGpuInterfaces(gpu);
            for (var handler : source.eventHandlers()) if (handler.program() != null) registerCpuInterfaces(handler.program());
        }
        definition.mutableInterfaces().forEach(interfaces::registerMutable);
        parameterSources.cameras().forEach(interfaces::registerCamera);
    }
    public List<MaterialCollectionDefinition> materialCollections() { return materialCollections; }
    public List<NiagaraCollectionBinding> collectionBindings() { return collectionBindings; }
    public boolean completed() { return complete; }

    public void prepareManualStep(double now, double engineTime, double realTime, float delta, float engineDelta,
                                  int step, int count, EffectHandle.Input input, Vec3 camera, boolean emitting,
                                  @Nullable NiagaraCameraFrame cameraFrame) {
        prepare(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame, true, engineDelta, step, count);
    }

    public void prepareRenderOnly(double now, EffectHandle.Input input) {
        this.input = input; this.now = now; execute = false;
        updateComponentAttachments(input.pose());
    }

    private void prepare(double now, double engineTime, double realTime, float delta, EffectHandle.Input input,
                         Vec3 camera, boolean emitting, NiagaraCameraFrame cameraFrame, boolean manualTick) {
        prepare(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame, manualTick, delta, 0, 1);
    }

    private void prepare(double now, double engineTime, double realTime, float delta, EffectHandle.Input input,
                         Vec3 camera, boolean emitting, NiagaraCameraFrame cameraFrame, boolean manualTick,
                         float engineDelta, int step, int count) {
        if (cachePlayer != null) {
            prepareCache(now, engineTime, realTime, delta, input, camera, emitting);
            return;
        }
        if (!prepareSystemStep(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame,
                manualTick, engineDelta, step, count, null)) return;
        boolean newborn = needsSystemSpawn();
        int running = newborn ? 0 : 1;
        prepareSystemSpawnParameters(running);
        if (newborn && runsSystemSpawn()) {
            prepareSpawnExecution();
            spawn.execute(1, new VectorDataSet[] {current, spawnInputs}, new VectorDataSet[] {destination}, 0, spawnConstants);
            swap();
        }
        if (runsSystemUpdate() && (!newborn || updatesOnSpawn())) {
            prepareUpdateExecution(running);
            update.execute(1, new VectorDataSet[] {current, updateInputs}, new VectorDataSet[] {destination}, 0, updateConstants);
            swap();
        }
        finishSystemStep();
    }

    public boolean requiresSoloSystemSimulation() {
        return cachePlayer != null || componentTickSettings.requiresSolo() || definition.timing().fixedTick()
                || !frame.collections.component().isEmpty();
    }

    public boolean playingCache() { return cachePlayer != null; }
    public @Nullable NiagaraSimulationCache cacheSource() { return cachePlayer == null ? null : cachePlayer.cache(); }
    public void cacheAge(@Nullable Float value) { cacheAgeOverride = value; }

    /** Applies a cache change at an instance boundary. False requests source-style reinitialization. */
    public boolean changeCache(@Nullable Playback playback) {
        var next = playback == null ? null : playback.cache();
        if (next == cacheSource()) {
            cacheAge(playback == null ? null : playback.age());
            return true;
        }
        if (pendingGpuSteps() != 0) return true;
        var previous = cachePlayer;
        if (previous != null && initialized && !previous.canResumeLive()) return false;
        var player = next == null ? null : new NiagaraCachePlayer(next, definition);
        var storage = next == null || next.interfaces().isEmpty() ? null : new NiagaraCacheInterfacePlayback(next.interfaces());
        if (storage != null && observedInterfaceInstances != null) observedInterfaceInstances.forEach(storage::bind);
        if (worldSimulation != null) worldSimulation.detach(this);
        if (previous != null && previous.hasReadFrame()) {
            discardPendingCacheResume();
            resumeGpuFrames = cacheGpuFrames;
            resumeGpuVisible = cacheGpuVisible;
            resumeCacheInterfaces = cacheInterfaces;
            lastSampledCache = previous;
        } else {
            closeCacheFrames(cacheGpuFrames);
        }
        if (player == null) {
            if (resumeGpuFrames != null) for (int index = 0; index < resumeGpuFrames.length; index++) {
                var cached = resumeGpuFrames[index];
                var emitter = gpuEmitters[index];
                if (cached == null || emitter == null) continue;
                var saved = cached.saved();
                emitter.restoreCache(current, cached.count(), saved.totalSpawned());
            }
            for (var emitter : emitters) if (emitter instanceof NiagaraStatelessEmitter stateless) stateless.leaveCache();
        }
        cachePlayer = player;
        cacheInterfaces = storage;
        cacheGpuFrames = player == null ? null : new NiagaraGpuCacheFrame[emitters.length];
        cacheGpuVisible = player == null ? null : new boolean[emitters.length];
        cacheAgeOverride = playback == null ? null : playback.age();
        return true;
    }

    public void restoreLiveGpuCache(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                    Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        if (cachePlayer != null) return;
        if (resumeCacheInterfaces != null) {
            uploadCachedGpuInterfaces(encoder, programs, resumeCacheInterfaces, true);
            resumeCacheInterfaces = null;
        }
        if (resumeGpuFrames == null) return;
        for (int index = 0; index < resumeGpuFrames.length; index++) {
            var cached = resumeGpuFrames[index];
            if (cached == null) continue;
            if (gpuEmitters[index] != null && gpuEmitters[index].hasGpuContext()) {
                var emitter = definition.emitters().get(index);
                var gpu = (NiagaraDefinition.GpuSimulation)emitter.simulation();
                var shader = Objects.requireNonNull(shaderLookup.apply(NiagaraGpuBatchDispatcher.shaderId(gpu)),
                        "Missing Niagara GPU shader while restoring " + emitter.name());
                int count = cached.count();
                var target = gpuSimulation(index, programs, shader, Math.max(count, gpuAllocationEstimate(index, programs)));
                cached.restoreSimulation(encoder, programs, target.particles());
            }
            cached.close();
            resumeGpuFrames[index] = null;
        }
        resumeGpuFrames = null;
        resumeGpuVisible = null;
        lastSampledCache = null;
        cacheBounds = null;
    }
    public void simCacheReaderInputs(Map<String, NiagaraSimulationCache> values) { simCacheReaderInputs = values; }
    public void sparseVolumeInputs(Map<String, SparseVolumeTextureAsset> values) { sparseVolumeInputs = values; }
    public void materialCollectionInputs(Map<String, ?> values) { interfaces.materialCollectionInputs(values); }

    public void recordChannelCache(@Nullable NiagaraCacheCapture capture) {
        updateChannelCache(capture != null && capture.advanceDelta() > 0 ? null : capture);
    }

    private void updateChannelCache(@Nullable NiagaraCacheCapture capture) {
        channelCacheRequest = capture == null || !capture.capturesInterfaces(definition.sourceSystem())
                || capture.currentFrame() || capture.finishing() ? null : capture;
        interfaces.recordChannelCache(channelCacheRequest);
    }

    public void captureLiveCache(@Nullable NiagaraCacheCapture capture, @Nullable ClientLevel level) {
        captureLiveCache(capture, level, null, null);
    }

    public boolean hasPendingCurrentCapture(@Nullable NiagaraCacheCapture capture) {
        return capture != null && capture.currentFrame() && initialized && !capture.finishing() && !capture.result().isDone()
                && (cacheRecorder == null || cacheRecorder.request() != capture || !cacheRecorder.finished());
    }

    public boolean hasPendingCacheAdvance(@Nullable NiagaraCacheCapture capture) {
        return capture != null && capture.advanceDelta() > 0 && !capture.finishing() && !capture.result().isDone()
                && (cacheRecorder == null || cacheRecorder.request() != capture || !cacheRecorder.finished());
    }

    public void finishCacheRenderOpportunity() {
        if (cacheRecorder != null && cacheRecorder.request().advanceDelta() == 0) cacheRecorder.finishRenderOpportunity();
    }

    public boolean capturesAnalyticGpu(NiagaraCacheCapture capture, int index) {
        if (statelessGpu[index] == null || capture.finishing() || capture.result().isDone()) return false;
        var source = capture.appendSource();
        if (source != null && definition.sourceSystem() != null && SourceNames.equal(source.sourceSystem(), definition.sourceSystem()))
            return index < source.emitterLayouts().size() && !source.emitterLayouts().get(index).name().equals("none");
        return capture.options().includeDebugData();
    }

    public void captureLiveCache(@Nullable NiagaraCacheCapture capture, @Nullable ClientLevel level, @Nullable CommandEncoder cacheCommands,
                                 @Nullable Map<Integer, NiagaraGpuParticleView> analyticData) {
        if (capture != null && capture.advanceDelta() > 0 && !capture.finishing() && !complete) return;
        captureCache(capture, level, cacheCommands, analyticData);
    }

    public boolean beginManualCache(NiagaraCacheCapture capture) {
        if (cacheRecorder(capture) == null) return false;
        updateChannelCache(capture);
        return true;
    }

    public void endManualCacheFrame() {
        updateChannelCache(null);
    }

    public void finishManualCache(NiagaraCacheCapture capture) {
        if (cacheRecorder != null && cacheRecorder.request() == capture) cacheRecorder.finish();
    }

    public void captureManualCache(NiagaraCacheCapture capture, @Nullable ClientLevel level,
                                   @Nullable CommandEncoder commands, @Nullable NiagaraGpuPrograms programs) {
        int analyticCount = 0;
        if (initialized && !complete && !capture.finishing()) {
            for (int index = 0; index < statelessGpu.length; index++)
                if (capturesAnalyticGpu(capture, index)) analyticCount++;
        }
        if (analyticCount == 0) {
            captureCache(capture, level, commands, Map.of());
        } else {
            var gpu = Objects.requireNonNull(programs, "Manual Niagara capture requires the analytic GPU programs");
            var encoder = Objects.requireNonNull(commands);
            var batch = Objects.requireNonNull(gpu.beginStatelessFrame(analyticCount));
            long generation = batch.frame();
            boolean submitted = false;
            try {
                var views = new HashMap<Integer, NiagaraGpuParticleView>();
                for (int index = 0; index < statelessGpu.length; index++) if (capturesAnalyticGpu(capture, index)) {
                    var particles = prepareStatelessGpuParticles(index, encoder, gpu);
                    if (particles != null) views.put(index, particles);
                }
                batch.flush(generation, gpu);
                captureCache(capture, level, encoder, views);
                submitted = true;
            } finally {
                if (submitted) batch.finishFrame(generation);
                else batch.abortFrame(generation);
            }
        }
        if (cacheRecorder != null && cacheRecorder.request() == capture) cacheRecorder.finishRenderOpportunity();
    }

    private @Nullable NiagaraCacheRecorder cacheRecorder(NiagaraCacheCapture capture) {
        if (cachePlayer != null || definition.sourceSystem() == null) {
            capture.result().completeExceptionally(new IllegalStateException("Cache capture requires a live Niagara source system"));
            return null;
        }
        if (cacheRecorder == null || cacheRecorder.request() != capture) {
            try { cacheRecorder = new NiagaraCacheRecorder(definition, capture, frame.platform.volumeCacheStorage()); }
            catch (IllegalArgumentException failure) {
                capture.result().completeExceptionally(failure);
                return null;
            }
        }
        return cacheRecorder;
    }

    private void captureCache(@Nullable NiagaraCacheCapture capture, @Nullable ClientLevel level,
                              @Nullable CommandEncoder cacheCommands, @Nullable Map<Integer, NiagaraGpuParticleView> analyticData) {
        if (capture == null || capture.result().isDone()) { cacheRecorder = null; return; }
        var recorder = cacheRecorder(capture);
        if (recorder == null || recorder.finished()) return;
        if (complete || capture.finishing()) { recorder.finish(); return; }
        if (!capture.currentFrame() && capture.capturesInterfaces(definition.sourceSystem()) && capture != channelCacheRequest
                && captureInterfaceBindings.entrySet().stream().anyMatch(entry -> entry.getValue().kind() == CaptureInterfaceKind.CHANNEL
                        && recorder.capturesInterface(entry.getKey()))) return;
        if (!initialized || frame.tick() == 0 && !capture.currentFrame() || pendingGpuSteps() != 0) return;
        if (analyticData == null) {
            for (int index = 0; index < statelessGpu.length; index++)
                if (capturesAnalyticGpu(capture, index)) return;
        }
        if (!recorder.observe(frame.tick())) {
            interfaces.discardChannelCacheFrame();
            return;
        }
        try {
            var particles = new ArrayList<CompletableFuture<EmitterFrame>>(emitters.length);
            CommandEncoder encoder = cacheCommands;
            for (int index = 0; index < emitters.length; index++) {
                var layout = recorder.emitterLayout(index);
                if (emitters[index] instanceof NiagaraEmitter emitter) {
                    particles.add(CompletableFuture.completedFuture(emitter.captureCache(layout)));
                    continue;
                }
                if (emitters[index] instanceof NiagaraStatelessEmitter analytic && !layout.name().equals("none")) {
                    var bounds = Objects.requireNonNull(interfaces.emitterBounds(definition.emitters().get(index).sourceName())).snapshot().bounds();
                    var data = NiagaraCacheRecorder.snapshot(analytic.particleReadData(false), layout, 0, new int[0]);
                    particles.add(CompletableFuture.completedFuture(new EmitterFrame(bounds, 0, data)));
                    continue;
                }
                if (statelessGpu[index] != null && !layout.name().equals("none") && analyticData != null) {
                    var analytic = analyticData.get(index);
                    if (analytic != null) {
                        if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
                        var bounds = Objects.requireNonNull(interfaces.emitterBounds(definition.emitters().get(index).sourceName())).snapshot().bounds();
                        var count = analytic.countBuffer().slice((long)analytic.currentCountOffset() * Integer.BYTES, Integer.BYTES);
                        particles.add(NiagaraGpuCacheReadbacks.capture(encoder, level, layout, analytic.current(), count, 0, null)
                                .thenApply(data -> new EmitterFrame(bounds, 0, data)));
                        continue;
                    }
                }
                var simulation = gpuSimulations[index];
                if (simulation == null || gpuEmitters[index].complete()) {
                    particles.add(CompletableFuture.completedFuture(new EmitterFrame(null, 0, NiagaraCacheRecorder.empty())));
                    continue;
                }
                if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
                int total = gpuEmitters[index].totalSpawned();
                var bounds = Objects.requireNonNull(interfaces.emitterBounds(definition.emitters().get(index).sourceName())).snapshot().bounds();
                particles.add(simulation.particles().captureCache(encoder, level, layout).thenApply(data -> new EmitterFrame(bounds, total, data)));
            }
            var pose = input.pose();
            var position = pose.position();
            var transform = new Transform(new Vec3(position.x * 100 - frame.originX(), position.z * 100 - frame.originY(),
                    position.y * 100 - frame.originZ()), -pose.x(), -pose.z(), -pose.y(), pose.w(),
                    new Vec3(pose.scaleX(), pose.scaleZ(), pose.scaleY()),
                    new Vec3(frame.originX() / NiagaraFrame.TILE_SIZE, frame.originY() / NiagaraFrame.TILE_SIZE,
                            frame.originZ() / NiagaraFrame.TILE_SIZE));
            var values = capture.capturesInterfaces(definition.sourceSystem()) ? captureLiveInterfaces(encoder, level, recorder, capture.options().rebase()) : Map.<NiagaraCacheInterfaceData.Key,
                    CompletableFuture<NiagaraCacheInterfaceRecorder.Value>>of();
            interfaces.discardChannelCacheFrame();
            var debug = recorder.capturesDebug() ? recorder.snapshotDebug(debugParameterStores(), parameterSources) : null;
            recorder.append(frame.age(), frame.tick(), transform, gpuBounds(), current, particles, values, debug);
        } catch (RuntimeException failure) {
            capture.result().completeExceptionally(failure);
        }
    }

    private Map<String, NiagaraParameterStore> debugParameterStores() {
        var stores = new LinkedHashMap<String, NiagaraParameterStore>();
        stores.put("OverrideParameters", instanceParameterStores.override());
        stores.put("InstanceParameters", instanceParameterStores.instance());
        stores.put("System Spawn", spawnParameterStore.store());
        stores.put("System Update", updateParameterStore.store());
        var debug = definition.debugParameters();
        for (int index = 0; index < emitters.length; index++) {
            String name = debug == null ? definition.emitters().get(index).name() : debug.emitterNames().get(index);
            stores.put(name + " RendererBindings", rendererParameterStores[index].renderer());
            if (emitters[index] instanceof NiagaraEmitter emitter) emitter.collectDebugParameters(name, stores);
            else if (gpuEmitters[index] != null) gpuEmitters[index].collectDebugParameters(name, stores);
        }
        return stores;
    }

    private Map<NiagaraCacheInterfaceData.Key, CompletableFuture<NiagaraCacheInterfaceRecorder.Value>> captureLiveInterfaces(
            @Nullable CommandEncoder encoder, @Nullable ClientLevel level, NiagaraCacheRecorder recorder, boolean rebase) {
        if (captureInterfaceBindings.isEmpty()) return Map.of();
        var commands = encoder == null ? RenderSystem.getDevice().createCommandEncoder() : encoder;
        var instances = new HashMap<CaptureInterface, CompletableFuture<NiagaraCacheInterfaceRecorder.Value>>();
        var values = new LinkedHashMap<NiagaraCacheInterfaceData.Key, CompletableFuture<NiagaraCacheInterfaceRecorder.Value>>();
        var volumeStorage = recorder.volumeCacheStorage();
        captureInterfaceBindings.forEach((key, binding) -> {
            if (!recorder.capturesInterface(key)) return;
            if (binding.kind() == CaptureInterfaceKind.TARGET_VOLUME && volumeStorage == VolumeCacheStorage.DISABLED) return;
            values.put(key, instances.computeIfAbsent(binding, source -> {
            String id = source.id();
            return switch (source.kind()) {
                case ARRAY -> {
                    var cpu = new ArrayFrame(true, interfaces.array(id).snapshot().words());
                    var gpu = source.gpu() && gpuArrays != null ? gpuArrays.captureCache(commands, level, id)
                            : CompletableFuture.completedFuture(new ArrayFrame(false, new int[0]));
                    yield gpu.thenApply(result -> new NiagaraCacheInterfaceRecorder.ArrayValue(cpu, result));
                }
                case COUNTER -> {
                    int cpu = interfaces.counter(id).get();
                    var gpu = source.gpu() && gpuInterfaceCounters != null
                            ? gpuInterfaceCounters.captureCache(commands, level, id, cpu) : CompletableFuture.completedFuture(cpu);
                    yield gpu.thenApply(result -> new NiagaraCacheInterfaceRecorder.CounterValue(cpu, result));
                }
                case TARGET_2D -> {
                    if (gpuRenderTargets != null && gpuRenderTargets.hasImage(id))
                        yield gpuRenderTargets.captureCache(commands, level, id).thenApply(NiagaraCacheInterfaceRecorder.TargetValue::new);
                    var control = interfaces.renderTarget2D(id).snapshot();
                    var target = control.target();
                    var image = target == null || target.isClosed() ? null : target.storageImage();
                    var pixels = image == null ? CompletableFuture.completedFuture(new Target2D(2, 0, 0, new byte[0]))
                            : NiagaraGpuCacheReadbacks.target2D(commands, level, id, image.texture(), control.format());
                    yield pixels.thenApply(NiagaraCacheInterfaceRecorder.TargetValue::new);
                }
                case TARGET_VOLUME -> {
                    boolean sparse = volumeStorage == VolumeCacheStorage.SPARSE;
                    if (gpuDimensionalTargets != null && gpuDimensionalTargets.hasImage(id))
                        yield gpuDimensionalTargets.captureVolumeCache(commands, level, id, sparse)
                                .thenApply(NiagaraCacheInterfaceRecorder.VolumeValue::new);
                    var control = interfaces.dimensionalTarget(id).snapshot();
                    var target = control.userTarget();
                    var image = target == null || target.closed() ? null : target.resolve().volume();
                    var pixels = image == null ? CompletableFuture.completedFuture(NiagaraCacheVolumeFrame.ABSENT)
                            : NiagaraGpuCacheReadbacks.volume(commands, level, id, image, control.format(), sparse);
                    yield pixels.thenApply(NiagaraCacheInterfaceRecorder.VolumeValue::new);
                }
                case CHANNEL -> {
                    var writer = interfaces.dataChannel(id);
                    if (recorder.request().currentFrame())
                        yield CompletableFuture.completedFuture(new NiagaraCacheInterfaceRecorder.ChannelValue(writer.captureCurrentCache(rebase)));
                    writer.recordCache(channelCacheRequest);
                    yield writer.captureCache(rebase).thenApply(NiagaraCacheInterfaceRecorder.ChannelValue::new);
                }
                case MEMORY -> {
                    int[] words = interfaces.memoryBuffer(id).cachedCpuWords();
                    var cpu = new ArrayFrame(words.length != 0, words);
                    var gpu = source.gpu() && gpuMemoryBuffers != null ? gpuMemoryBuffers.captureCache(commands, level, id)
                            : CompletableFuture.completedFuture(new ArrayFrame(false, new int[0]));
                    yield gpu.thenApply(result -> new NiagaraCacheInterfaceRecorder.ArrayValue(cpu, result));
                }
                case DYNAMIC_MESH -> {
                    var mesh = interfaces.dynamicMesh(id);
                    var frame = mesh.captureFrame();
                    var materials = captureDynamicMaterialPaths(mesh.materialSources(frame), mesh.materialParameters(frame));
                    DynamicMesh cpu = frame.indices().length == 0 || frame.attributes().length == 0 ? null : new DynamicMesh(frame, materials);
                    CompletableFuture<@Nullable NiagaraDynamicMeshFrame> gpu = source.gpu() && gpuDynamicMeshes != null
                            ? gpuDynamicMeshes.captureCache(commands, level, id, frame) : CompletableFuture.completedFuture(null);
                    yield gpu.thenApply(result -> new NiagaraCacheInterfaceRecorder.DynamicValue(
                            new DynamicMeshPair(cpu, result == null ? null : new DynamicMesh(result, materials))));
                }
            };
            }));
        });
        return Map.copyOf(values);
    }

    private List<String> captureDynamicMaterialPaths(List<String> defaults, List<String> parameters) {
        var values = new ArrayList<String>(defaults);
        for (int index = 0; index < parameters.size(); index++) {
            var parameter = RendererParameter.named(input.rendererParameters(), parameters.get(index));
            if (parameter instanceof RendererParameter.Material material && material.value() != null) {
                values.set(index, material.value().sourceObject());
            } else if (parameter instanceof RendererParameter.MaterialVariants variants) {
                var paths = variants.values().values().stream().filter(material -> material.value() != null)
                        .map(material -> material.value().sourceObject()).distinct().toList();
                if (paths.size() > 1) throw new IllegalArgumentException("Niagara capture material variants have different source objects");
                if (!paths.isEmpty()) values.set(index, paths.getFirst());
            }
        }
        return List.copyOf(values);
    }

    private void prepareCache(double now, double engineTime, double realTime, float delta, EffectHandle.Input input,
                              Vec3 camera, boolean emitting) {
        var player = Objects.requireNonNull(cachePlayer);
        var sample = player.advance(delta, cacheAgeOverride);
        this.input = input; this.now = now; this.realTime = realTime;
        updateComponentAttachments(input.pose());
        execute = !complete;
        if (!execute) return;
        if (sample == null) {
            completeEmitters(3);
            return;
        }
        if (initialized && !player.shouldRead(frame.age(), input.pose())) {
            execute = false;
            lastTime = now;
            return;
        }
        boundsPublished = false;
        dynamicBindingsPublished = false;
        int particles = 0, alive = 0;
        for (int index = 0; index < emitters.length; index++) {
            if (!player.hasEmitter(index)) continue;
            alive++;
            particles += emitters[index] == null ? gpuParticleCounts[index] : emitters[index].count();
        }
        frame.advanceCacheClock(player.age());
        frame.prepare(engineTime, realTime, delta, input, camera, emitting, emitters.length, alive, particles, systemSeed(), false);
        var tile = new Vec3(frame.originX(), frame.originY(), frame.originZ());
        instanceParameterStores.prepare(input.parameters(), input.textureParameters(), tile.x, tile.y, tile.z);
        player.readSystem(sample, delta, input.pose(), tile, current);
        if (!gpuHairStrandsIds.isEmpty()) {
            for (var emitter : gpuEmitters) if (emitter != null) emitter.prepareParameters(frame, input, current);
            interfaces.beginHairStrands(groomInputs, 0);
        }
        if (cacheInterfaces != null) {
            cacheInterfaces.read(interfaces, sample);
            if (frame.platform.dataChannelsEnabled())
                cacheInterfaces.publishChannels(interfaces::dataChannel, channelWorld, sample, input.pose(), tile);
        }
        cacheBounds = sample.first().bounds();
        for (int index = 0; index < emitters.length; index++) {
            if (!player.hasEmitter(index)) {
                if (emitters[index] instanceof NiagaraStatelessEmitter stateless) stateless.leaveCache();
                continue;
            }
            var source = definition.emitters().get(index);
            rendererDataSetBindings[index].toStore(current, 0);
            rendererParameterStores[index].updateExternalObjects(instanceParameterStores.override());
            if (emitters[index] instanceof NiagaraEmitter emitter)
                emitter.readCache(player, index, sample, frame, input, tile, current);
            else if (emitters[index] instanceof NiagaraStatelessEmitter emitter)
                emitter.readCache(player, index, sample, frame, input, tile);
            else if (emitters[index] == null) {
                var state = variables.get(source.sourceName() + ".ExecutionState");
                boolean visible = gpuEmitters[index] != null ? gpuEmitters[index].hasGpuContext() : !statelessGpu[index].complete();
                if (state != null) {
                    int recorded = current.integer(state.intStart(), 0);
                    visible &= recorded != 3 && recorded != 4;
                }
                Objects.requireNonNull(cacheGpuVisible)[index] = visible;
                if (!visible && (gpuEmitters[index] == null || !gpuEmitters[index].hasGpuContext())) continue;
                var frames = Objects.requireNonNull(cacheGpuFrames);
                if (frames[index] == null) frames[index] = new NiagaraGpuCacheFrame(source,
                        NiagaraGpuDataBuffer.maximumParticleCapacity(cpuTargetRuntime(), source.attributes()));
                var saved = frames[index].read(player, index, sample, delta, input.pose(), tile);
                gpuRenderOrigins[index] = tile;
                gpuParticleCounts[index] = frames[index].count();
                interfaces.publishGpuParticleCounts(source.sourceName(), gpuParticleCounts[index], saved.totalSpawned());
                Objects.requireNonNull(interfaces.emitterBounds(source.sourceName())).readCache(saved.bounds());
            }
        }
        // The last sampled cache owns the restore state until its replacement supplies a frame.
        discardPendingCacheResume();
        player.markRead(input.pose());
        statelessRestartRenderPending = false;
        initialized = true;
        lastTime = now;
    }

    boolean prepareSharedStep(double now, double engineTime, double realTime, float delta, EffectHandle.Input input,
                              Vec3 camera, boolean emitting, @Nullable NiagaraCameraFrame cameraFrame,
                              @Nullable SharedTickInfo spawnTiming) {
        return prepareSystemStep(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame,
                false, delta, 0, 1, spawnTiming);
    }

    SharedTickInfo sharedTickInfo(float delta) { return new SharedTickInfo(delta, 1, frame.timeStepFraction()); }

    private boolean prepareSystemStep(double now, double engineTime, double realTime, float delta, EffectHandle.Input input,
                                      Vec3 camera, boolean emitting, @Nullable NiagaraCameraFrame cameraFrame,
                                      boolean manualTick, float engineDelta, int step, int count,
                                      @Nullable SharedTickInfo spawnTiming) {
        this.input = input; this.now = now; this.realTime = realTime;
        updateComponentAttachments(input.pose());
        execute = spawnTiming == null
                ? (manualTick || !initialized || now != lastTime) && !frame.platform.skipsSystemTick(delta) : !initialized;
        if (!execute) return false;
        boundsPublished = false;
        dynamicBindingsPublished = false;
        if (now < lastTime) {
            resetSimulationData();
        } else {
            if (gpuArrays != null) gpuArrays.poll();
            if (gpuInterfaceCounters != null) gpuInterfaceCounters.poll();
        }
        if (complete || !validateTraceInitialization()) { execute = false; return false; }
        if (statelessRestartRenderPending) {
            current.clear();
            statelessRestartRenderPending = false;
        }
        if (!seedOffsetsApplied) {
            applyInitialSeedOffset();
            seedOffsetsApplied = true;
        }
        spawnParameterStore.tickRapidIteration(); updateParameterStore.tickRapidIteration();
        int particles = 0, aliveEmitters = 0;
        for (int index = 0; index < emitters.length; index++) {
            if (gpuEmitters[index] != null && !gpuEmitters[index].disabled()) {
                particles += gpuEmitters[index].estimatedCount(gpuParticleCounts[index]);
                aliveEmitters++;
            } else if (emitters[index] instanceof NiagaraEmitter stateful && !stateful.disabled()) {
                particles += stateful.count();
                aliveEmitters++;
            }
        }
        float simulationDelta = limitsVariableDelta ? Math.clamp(delta, 0f, definition.timing().maximumDelta()) : delta;
        frame.prepare(engineTime, realTime, simulationDelta, input, camera, emitting, emitters.length, aliveEmitters, particles,
                systemSeed(), initialized && emitting && frame.systemExecutionState == 0);
        prepareTileRebase();
        if (spawnTiming == null) {
            float fraction = limitsVariableDelta ? simulationDelta / engineDelta : (step + 1) / (float)count;
            frame.timeStep(engineDelta, step, count, fraction, !initialized);
        } else frame.timeStep(spawnTiming.engineDelta(), 0, spawnTiming.steps(), spawnTiming.fraction(), !initialized);
        for (var emitter : gpuEmitters) if (emitter != null) emitter.prepareParameters(frame, input, current);
        for (var emitter : emitters) if (emitter instanceof NiagaraEmitter stateful) stateful.prepareParameters(frame, input, current);
        instanceParameterStores.prepare(input.parameters(), input.textureParameters(), frame.originX(), frame.originY(), frame.originZ());
        interfaces.captureSimCacheReaders(simCacheReaderInputs, definition.simCacheInputs());
        interfaces.captureSparseVolumes(sparseVolumeInputs, definition.sparseVolumeInputs());
        interfaces.recordChannelCache(channelCacheRequest);
        interfaces.beginDynamicMeshes();
        applyObjectReaderRemaps();
        var sceneCallbacks = new NiagaraInterfaces.SceneCallbackInputs(rigidMeshInputs, geometryCollectionInputs,
                parameterSources::savedGeometryCollection, sceneCaptureInputs, landscapeInputs,
                interfaces.hasLandscapes() ? gpuBounds() : null);
        try {
            if (interfaces.beginFrame(frame, cameraFrame, input, objectPropertyInputs, parameterSources::savedPropertyObject,
                    propertyInterfaceInputs,
                    actorComponentInputs, localActorComponents, parameterSources::savedActorComponent, collisionWorld,
                    staticMeshInputs, definition.staticMeshInputs(), skeletalMeshInputs, definition.skeletalMeshInputs(), socketInputs, splineInputs,
                    parameterSources::savedSpline, instanceParameterStores::hasObjectParameterOverride,
                    movieRenderSample, exportHandlers,
                    channelWorld, step == count - 1, channelOwner, this::activeDataChannelEmitter, audioBackend, audioOverrides,
                    parameterSources::savedAudioSettings, sceneCallbacks))
                dirtyDataInterfaceFunctions();
        } catch (InvalidSamplingRegion failure) {
            LOGGER.warn("Could not initialize Niagara skeletal mesh interface (instance {}): {}", renderIdentity, failure.getMessage());
            completeEmitters(3);
            execute = false;
            return false;
        }
        if (!bindSystemInterfaces()) return false;
        interfaces.capturePhysicsAssets(skeletalMeshInputs, input.pose());
        interfaces.physicsField().begin(worldPhysicsField, frame.originX(), frame.originY(), frame.originZ());
        interfaces.beginVelocityGrids(input.pose());
        interfaces.beginChaosDestruction(chaosScene);
        interfaces.beginHairStrands(groomInputs, frame.delta);
        interfaces.beginVirtualTextures(virtualTextureInputs, virtualTextureAssets, runtimeVirtualTextures,
                new Vec3(frame.originX(), frame.originY(), frame.originZ()));
        interfaces.beginIntDistributions(intDistributionInputs);
        interfaces.beginWaters(waterScene, waterSourceOverrides, input.pose(),
                new Vec3(frame.originX(), frame.originY(), frame.originZ()), (float)engineTime);
        firstRigidMeshStep = step == 0;
        for (int i = 0; i < emitters.length; i++) runtimes[i] = emitters[i] != null ? emitters[i].prepareRuntime(frame)
                : statelessGpu[i] != null ? statelessGpu[i].prepareRuntime(frame)
                : gpuEmitters[i].prepareRuntime(frame, gpuEmitters[i].estimatedCount(gpuParticleCounts[i]));
        spawnInputs.clear(); updateInputs.clear();
        spawnInputs.count(1); updateInputs.count(1);
        applyParameters(input.parameters(), spawnInputs, definition.spawnInputLayout());
        applyParameters(input.parameters(), updateInputs, definition.updateInputLayout());
        if (instanceParameterStores.instance().numericDirty()) {
            spawnInstanceBinding.toDataSet(spawnInputs, 0);
            updateInstanceBinding.toDataSet(updateInputs, 0);
        }
        bind(definition.spawnInputBindings(), spawnInputs);
        bind(definition.updateInputBindings(), updateInputs);
        System.arraycopy(frame.current, 0, spawnConstants, 0, 32);
        if (!spawnParameterStore.restoreConstants(spawnConstants)) NiagaraParameterValues.restore(definition.systemSpawn(), spawnConstants, defaultSpawnConstants);
        NiagaraParameterValues.write(definition.systemSpawn(), spawnConstants, input.parameters(), frame.originX(), frame.originY(), frame.originZ());
        NiagaraParameterValues.writeCollections(definition.systemSpawn(), spawnConstants, frame.collections, frame.originX(), frame.originY(), frame.originZ());
        return true;
    }

    private void resetSimulationData() {
        discardPendingCacheResume();
        cacheBounds = null;
        current.clear(); destination.clear(); frame.reset();
        spawnParameterStore.reset(); updateParameterStore.reset();
        instanceParameterStores.reset();
        events.reset();
        discardGpuInterfaceTicks();
        interfaces.reset();
        persistentDebugShapes.reset();
        registerDefaultInterfaceStates();
        for (int i = 0; i < emitters.length; i++) {
            if (emitters[i] instanceof NiagaraStatelessEmitter stateless && randomSeedOffset != null)
                stateless.resetWithSeedOffset(randomSeedOffset);
            else if (emitters[i] != null) {
                if (emitters[i] instanceof NiagaraEmitter stateful) stateful.sourceSeedOffset(configuredSeedOffset());
                emitters[i].reset();
            }
            else if (statelessGpu[i] != null) {
                if (randomSeedOffset == null) statelessGpu[i].reset();
                else statelessGpu[i].resetWithSeedOffset(randomSeedOffset);
                gpuParticleCounts[i] = 0;
                interfaces.publishGpuParticleCounts(definition.emitters().get(i).sourceName(), 0, 0);
            }
            else {
                gpuEmitters[i].sourceSeedOffset(configuredSeedOffset());
                gpuEmitters[i].reset(); gpuParticleCounts[i] = 0;
                interfaces.publishGpuParticleCounts(definition.emitters().get(i).sourceName(), 0, 0);
            }
        }
        for (var store : rendererParameterStores) store.reset();
        interfaces.clearCollectionArrayBindings(parameterSources.collectionInterfaceNames());
        flushDataInterfaceSources();
        initializeDataInterfaceStates(null);
        interfaces.initializePropertyInterfaces(propertyInterfaceInputs);
        interfaces.recordChannelCache(channelCacheRequest);
        dataInterfaceRevision++;
        seed = definition.systemDeterministic() ? definition.systemRandomSeed() : NiagaraSeed.next();
        if (nativeSystemState != null) nativeSystemState.reset(systemSeed());
        seedOffsetsApplied = true;
        complete = false;
        componentCompletionNotified = false;
        statelessRestartRenderPending = false;
        initialized = false;
    }

    private int configuredSeedOffset() { return randomSeedOffset == null ? 0 : randomSeedOffset; }

    private int systemSeed() { return NiagaraSeed.offset(seed, configuredSeedOffset()); }

    private void applyInitialSeedOffset() {
        if (randomSeedOffset == null) return;
        int offset = randomSeedOffset;
        if (nativeSystemState != null) nativeSystemState.reset(systemSeed());
        for (int index = 0; index < emitters.length; index++) {
            if (emitters[index] instanceof NiagaraEmitter emitter) emitter.sourceSeedOffset(offset);
            else if (emitters[index] instanceof NiagaraStatelessEmitter emitter) emitter.resetWithSeedOffset(offset);
            else if (gpuEmitters[index] != null) gpuEmitters[index].sourceSeedOffset(offset);
            else if (statelessGpu[index] != null) statelessGpu[index].resetWithSeedOffset(offset);
        }
    }

    boolean needsSystemSpawn() { return !initialized; }
    boolean runsSystemUpdate() { return nativeSystemState == null; }
    boolean runsSystemSpawn() { return runsSystemUpdate() || definition.timing().systemState().runSpawn(); }
    boolean updatesOnSpawn() { return frame.platform.systemUpdateOnSpawn() != 0; }

    void prepareSystemSpawnParameters(int running) {
        NiagaraParameterValues.writeSystemSimulation(definition.systemSpawn(), spawnConstants, frame.platform, running);
        spawnParameterStore.capture(spawnConstants);
    }

    void prepareSpawnExecution() { spawnParameterStore.tick(); }

    void prepareUpdateExecution(int running) {
        System.arraycopy(frame.current, 0, updateConstants, 0, 32);
        if (!updateParameterStore.restoreConstants(updateConstants)) NiagaraParameterValues.restore(definition.systemUpdate(), updateConstants, defaultUpdateConstants);
        NiagaraParameterValues.write(definition.systemUpdate(), updateConstants, input.parameters(), frame.originX(), frame.originY(), frame.originZ());
        NiagaraParameterValues.writeCollections(definition.systemUpdate(), updateConstants, frame.collections, frame.originX(), frame.originY(), frame.originZ());
        NiagaraParameterValues.writeSystemSimulation(definition.systemUpdate(), updateConstants, frame.platform, running);
        if (!initialized && frame.platform.systemUpdateOnSpawn() == 1) {
            ByteBuffer globals = ByteBuffer.wrap(updateConstants).order(ByteOrder.LITTLE_ENDIAN);
            globals.putFloat(4, .0001f); globals.putFloat(8, 10000f);
        }
        updateParameterStore.capture(updateConstants);
        updateParameterStore.tick();
    }

    public ComponentTickGroup scheduleHostTickGroup(ComponentTickGroup requested) {
        return worldSimulation == null ? requested : worldSimulation.scheduleGroup(this, requested);
    }

    NiagaraDefinition systemDefinition() { return definition; }
    void joinWorldSimulation(NiagaraWorldSystemSimulation simulation) {
        if (worldSimulation != null && worldSimulation != simulation)
            throw new IllegalStateException("Niagara system already belongs to another world simulation");
        worldSimulation = simulation;
    }
    void leaveWorldSimulation(NiagaraWorldSystemSimulation simulation) {
        if (worldSimulation == simulation) worldSimulation = null;
    }
    VectorMachine systemMachine(boolean spawning) { return spawning ? spawn : update; }
    VectorDataSet systemRow() { return current; }
    VectorDataSet systemDestination() { return destination; }
    VectorDataSet systemInputs(boolean spawning) { return spawning ? spawnInputs : updateInputs; }
    byte[] systemConstants(boolean spawning) { return spawning ? spawnConstants : updateConstants; }

    void acceptSharedSystemRows(VectorDataSet currentRow, VectorDataSet destinationRow) {
        current.copyRowFrom(currentRow, 0, 0);
        current.count(currentRow.count());
        destination.copyRowFrom(destinationRow, 0, 0);
        destination.count(destinationRow.count());
    }

    void acceptSystemOutput(VectorDataSet source, int row, VectorProgram script) {
        if (row >= 0) for (var column : script.outputs()) {
            if (column.component() != 0xffff)
                destination.component(column.type(), column.component())[0] = source.component(column.type(), column.component())[row];
        }
        destination.count(row < 0 ? 0 : 1);
        swap();
    }

    void finishSystemStep() {
        int state;
        if (nativeSystemState == null) {
            state = current.integer(Objects.requireNonNull(executionStateBinding).intStart(), 0);
        } else {
            if (!runsSystemSpawn()) current.count(1);
            state = nativeSystemState.tick(frame.age() + frame.delta, frame.requestedExecutionState(), frame.systemExecutionState);
        }
        if (state == 0 && frame.systemExecutionState != 0) {
            for (int index = 0; index < emitters.length; index++) {
                if (emitters[index] instanceof NiagaraEmitter stateful) stateful.activateExecution();
                else if (gpuEmitters[index] != null) gpuEmitters[index].activateExecution();
            }
        }
        frame.systemExecutionState = state;
        if (state == 3 || state == 4) completeEmitters(state);
        interfaces.publishDataChannelSpawns();
        anyEmitterShouldTick = false;
        for (int index = 0; index < emitters.length; index++) {
            boolean terminal = emitters[index] != null ? emitters[index].complete()
                    : statelessGpu[index] != null ? statelessGpu[index].complete() : gpuEmitters[index].complete();
            if (!terminal) {
                if (nativeSystemState == null || !initialized && runsSystemSpawn())
                    rendererDataSetBindings[index].toStore(current, 0);
                if (nativeSystemState == null && emitters[index] instanceof NiagaraEmitter stateful) {
                    stateful.transferExecutionState(current);
                    stateful.transferParameters(current);
                    stateful.dataChannelSpawns(interfaces.dataChannelSpawns(index));
                }
                else if (nativeSystemState == null && gpuEmitters[index] != null) {
                    var emitter = gpuEmitters[index];
                    emitter.transferExecutionState(current);
                    emitter.transferHostParameters(current, definition.systemLayout());
                }
            }
        }
        for (int index = 0; index < emitters.length; index++)
            anyEmitterShouldTick |= emitters[index] != null ? emitters[index].shouldTick()
                    : statelessGpu[index] != null ? statelessGpu[index].shouldTick() : gpuEmitters[index].shouldTick();
        initialized = true;
        lastTime = now;
    }

    private boolean activeDataChannelEmitter(int index) {
        if (index < 0 || index >= emitters.length) return false;
        if (emitters[index] instanceof NiagaraEmitter emitter) return emitter.isActiveForDataChannel();
        var gpu = gpuEmitters[index];
        return gpu != null && gpu.isActiveForDataChannel();
    }

    private boolean bindSystemInterfaces() {
        if (!validateTraceInitialization()) return false;
        var instance = instanceParameterStores.instance();
        boolean bound = bindSystemInterfaces("spawn", spawnParameterStore, spawn, instance)
                & bindSystemInterfaces("update", updateParameterStore, update, instance);
        if (!bound) completeEmitters(3);
        return bound;
    }

    private boolean validateTraceInitialization() {
        String failure = asyncTraceInstances.failure();
        if (failure == null) return true;
        LOGGER.warn("Could not initialize Niagara async GPU trace interface (instance {}): {}", renderIdentity, failure);
        completeEmitters(3);
        return false;
    }

    private void dirtyDataInterfaceFunctions() {
        spawnParameterStore.store().markInterfacesDirty();
        updateParameterStore.store().markInterfacesDirty();
        for (var emitter : emitters)
            if (emitter instanceof NiagaraEmitter stateful && !stateful.disabled())
                stateful.forEachExecutionStore(false, NiagaraParameterStore::markInterfacesDirty);
        for (var emitter : gpuEmitters) if (emitter != null && !emitter.disabled()) {
            emitter.forEachHostExecutionStore(false, NiagaraParameterStore::markInterfacesDirty);
            var combined = emitter.combinedParameters();
            if (combined != null) combined.markInterfacesDirty();
        }
    }

    private boolean bindSystemInterfaces(String usage, NiagaraScriptNumericStore context, VectorMachine machine,
                                         NiagaraParameterStore instance) {
        try {
            context.bindSystemInterfaces(machine, instance);
            return true;
        } catch (VectorMachine.InterfaceBindingException failure) {
            LOGGER.error("Could not bind Niagara system {} data interfaces (instance {}): {}",
                    usage, renderIdentity, failure.getMessage());
            return false;
        }
    }

    private void completeEmitters(int state) {
        complete = true;
        for (int index = 0; index < emitters.length; index++) {
            if (emitters[index] != null) emitters[index].complete(state);
            else if (statelessGpu[index] != null) statelessGpu[index].complete(state);
            else gpuEmitters[index].complete(state);
        }
    }

    private void prepareTileRebase() {
        frame.gpuRebaseEmitters = List.of();
        if (frame.shiftX == 0 && frame.shiftY == 0 && frame.shiftZ == 0) return;
        // UE 5.8.4's single-row helper returns for every valid system row. Owner constants
        // have already been rebased, and the emitter helper walks the asset's declaration order.
        var gpu = new ArrayList<Integer>();
        for (int index = 0; index < emitters.length; index++) {
            var source = definition.emitters().get(index);
            if (source.localSpace() || source.simulation() instanceof NiagaraDefinition.StatelessSimulation
                    || source.simulation() instanceof NiagaraDefinition.DisabledSimulation disabled
                    && (disabled.stateless() || !disabled.hasEmitter())) return;
            if (emitters[index] instanceof NiagaraEmitter emitter) emitter.rebaseParticles(frame);
            else if (gpuEmitters[index] != null && gpuEmitters[index].estimatedCount(gpuParticleCounts[index]) > 0) gpu.add(index);
        }
        frame.gpuRebaseEmitters = List.copyOf(gpu);
    }

    public void applyGpuTileRebase(CommandEncoder encoder, NiagaraGpuPrograms programs, int step) {
        NiagaraGpuEmitterState.Tick tile = null;
        for (var ticks : gpuTicks) if (step < ticks.size() && ticks.get(step) instanceof NiagaraGpuEmitterState.Tick tick
                && !tick.rebaseEmitters().isEmpty()) {
            tile = tick;
            break;
        }
        if (tile == null) return;
        for (int index : tile.rebaseEmitters()) {
            var simulation = gpuSimulations[index];
            if (simulation == null || simulation.particles().estimate().current() == 0
                    || simulation.particles().currentCountOffset() < 0) continue;
            gpuPrefixMutated = true;
            NiagaraGpuRebase.apply(encoder, programs, simulation.particles(), definition.emitters().get(index).attributes(),
                    tile.shiftX(), tile.shiftY(), tile.shiftZ());
        }
    }

    public List<EmitterInstance.Snapshot> updateEmitter(int index) {
        return updateEmitter(index, true);
    }

    public List<EmitterInstance.Snapshot> updateEmitter(int index, boolean pack) {
        if (emitters[index] == null) throw new IllegalArgumentException("GPU Niagara emitter must use its render-thread path");
        if (cachePlayer != null && !cachePlayer.hasEmitter(index)) return List.of();
        var snapshots = emitters[index].simulate(frame, current, variables, input, now, execute && cachePlayer == null, pack);
        if (snapshots.isEmpty()) return snapshots;
        return prepareRendererSnapshots(index, snapshots, renderIdentity, previousRendererPose(), componentVelocity());
    }

    public @Nullable NiagaraEmitterRenderState createCullProxyCpuRenderer(int index, List<EmitterDefinition> renderers) {
        if (emitters[index] == null || emitters[index] instanceof NiagaraDisabledEmitter) return null;
        var emitter = definition.emitters().get(index).inLocalSpace();
        return new NiagaraEmitterRenderState(emitter, renderers.stream().map(EmitterDefinition::inLocalSpace).toList(),
                true, materialTextures(), true);
    }

    public List<EmitterInstance.Snapshot> packCullProxyEmitter(int index, NiagaraEmitterRenderState renderer,
            EffectHandle.Input drawInput, long primitive, EffectHandle.Pose previous) {
        if (!hasEmitterRendererState(index) || complete) return List.of();
        var snapshots = emitters[index].packCullProxy(renderer, current, variables, drawInput, now);
        return prepareRendererSnapshots(index, snapshots, primitive, previous, rendererVelocity(drawInput.pose(), previous));
    }

    private List<EmitterInstance.Snapshot> prepareRendererSnapshots(int index, List<EmitterInstance.Snapshot> snapshots,
            long primitive, EffectHandle.Pose previous, boolean velocity) {
        List<EmitterInstance.Snapshot> meshSnapshots = null;
        for (int draw = 0; draw < snapshots.size(); draw++) {
            var snapshot = snapshots.get(draw);
            snapshot.nativeParticles().componentBounds(this::rendererBounds);
            snapshot.nativeParticles().sourcePrimitive(primitive);
            if (!dynamicMeshParameters.isEmpty()) snapshot.nativeParticles().dynamicMeshes(this::dynamicMeshRenderSource);
            snapshot.nativeParticles().prepareMaterialFrame(frame.originX(), frame.originY(), frame.originZ(), materialEngineValues[index]);
            snapshot.nativeParticles().previousOwnerPose(previous);
            snapshot.nativeParticles().componentVelocity(velocity);
            if (snapshot.definition().ribbonSettings() != null)
                snapshot.nativeParticles().setRibbonSettings(ribbonSettings(index, snapshot.definition().ribbonSettings()));
            var mesh = snapshot.definition().meshSettings();
            if (mesh != null && mesh.nanite() != null) {
                snapshot.nativeParticles().naniteInstanceFloats(naniteInstanceFloats(index, snapshot.definition(), true));
                snapshot.nativeParticles().naniteRendererValues(naniteRendererValues(index));
            }
            var drawInput = meshDrawInput(index, snapshot.definition(), snapshot.input());
            if (drawInput != snapshot.input()) {
                if (meshSnapshots == null) meshSnapshots = new ArrayList<>(snapshots);
                meshSnapshots.set(draw, new EmitterInstance.Snapshot(snapshot.definition(), snapshot.particles(), drawInput,
                        snapshot.noise(), snapshot.time(), snapshot.emitterAge(), snapshot.emitting(),
                        snapshot.tessellation(), snapshot.nativeParticles()));
            }
        }
        return meshSnapshots == null ? snapshots : meshSnapshots;
    }

    public RibbonSettings ribbonSettings(int emitter, RibbonSettings defaults) {
        var key = defaults.shapeInterface();
        if (key == null) return defaults;
        var renderer = rendererParameterStores[emitter].renderer();
        var value = renderer.contains(key) ? renderer.dataInterface(key) : null;
        return interfaces.ribbonSettings(emitter, defaults, value instanceof NiagaraDataInterfaceObject object ? object : null);
    }

    public List<FloatComponent> naniteInstanceFloats(int emitter, EmitterDefinition draw, boolean readParticles) {
        var settings = Objects.requireNonNull(Objects.requireNonNull(draw.meshSettings()).nanite());
        return NaniteInstanceFloatBindings.resolve(settings, definition.emitters().get(emitter),
                rendererParameterStores[emitter].renderer(), readParticles);
    }

    public NiagaraParameterStore rendererStore(int emitter) { return rendererParameterStores[emitter].renderer(); }

    public Map<Key, byte[]> naniteRendererValues(int emitter) {
        var values = new LinkedHashMap<Key, byte[]>();
        rendererParameterStores[emitter].renderer().snapshotValues(values::put, (key, value) -> { });
        return Map.copyOf(values);
    }

    /** Freeze the mesh objects selected by this emitter's renderer store for the current draw. */
    public EffectHandle.Input meshDrawInput(int emitter, EmitterDefinition draw, EffectHandle.Input input) {
        var settings = draw.meshSettings();
        if (settings == null) return input;
        if (settings.nanite() != null && !settings.nanite().meshParameters().isEmpty()) {
            var store = rendererParameterStores[emitter].renderer();
            var resolved = new LinkedHashMap<String, RendererParameter>();
            for (var binding : settings.nanite().meshParameters().entrySet()) {
                String name = Objects.requireNonNull(settings.entries().get(binding.getKey()).assetBinding());
                if (RendererParameter.named(input.rendererParameters(), name) != null || !store.contains(binding.getValue())) continue;
                if (!(store.object(binding.getValue()) instanceof ObjectValue value)) {
                    resolved.put(name, new RendererParameter.Mesh(null));
                    continue;
                }
                var asset = value.source().getAsJsonObject("static_mesh_parameter");
                if (asset == null || !asset.has("asset")) resolved.put(name, new RendererParameter.Mesh(null));
                else {
                    var id = Identifier.parse(asset.get("asset").getAsString());
                    var mesh = Objects.requireNonNull(definition.staticMeshInputs().get(id), "Missing prepared Niagara mesh " + id);
                    resolved.put(name, new RendererParameter.Mesh(mesh));
                }
            }
            return input.withRendererDefaults(resolved);
        }
        if (settings.meshArray() == null || settings.meshesBinding() == null) return input;
        String name = settings.meshesBinding();
        if (RendererParameter.named(input.rendererParameters(), name) != null) return input;
        var array = settings.meshArray();
        var store = rendererParameterStores[emitter].renderer();
        if (!store.contains(array.slot()) || !(store.dataInterface(array.slot()) instanceof NiagaraDataInterfaceObject object))
            return input;
        var entries = array.values().get(SourceNames.canonical(object.identity()));
        if (entries == null) return input;
        return input.withRendererDefaults(Map.of(name, new RendererParameter.Meshes(entries)));
    }

    public boolean isGpuEmitter(int index) { return gpuEmitters[index] != null || statelessGpu[index] != null; }
    public @Nullable NiagaraDynamicMeshRenderSource dynamicMeshRenderSource(CommandEncoder encoder, NiagaraGpuPrograms programs, String parameter) {
        String id = dynamicMeshParameters.get(SourceNames.canonical(parameter));
        if (id == null) return null;
        var state = interfaces.dynamicMesh(id);
        var frame = state.publish();
        if (gpuDynamicMeshes == null) gpuDynamicMeshes = new NiagaraGpuDynamicMeshes(programs);
        var geometry = gpuDynamicMeshes.drawGeometry(encoder, id, frame, gpuDynamicMeshIds.contains(id));
        return new NiagaraDynamicMeshRenderSource(geometry, state.materialSources(frame), state.materialParameters(frame));
    }
    public boolean isStatelessGpuEmitter(int index) { return statelessGpu[index] != null; }
    public int gpuEmitterCount() { return gpuOrder.length; }
    public NiagaraGpuDebugLines.@Nullable Frame debugLines() {
        return gpuDispatcher == null || playingCache() ? null : gpuDispatcher.debugLines();
    }

    public List<NiagaraGaussianSplatDraw> gaussianSplatDraws() { return gaussianSplatDraws(null); }

    public List<NiagaraGaussianSplatDraw> gaussianSplatDraws(@Nullable ComputeRuntime runtime) {
        if (complete || playingCache()) return List.of();
        var writers = gpuGaussianSplatWriters;
        if (writers == null) {
            if (runtime == null || latestGaussianSplatWriters.isEmpty()) return List.of();
            writers = gpuGaussianSplatWriters = new NiagaraGpuGaussianSplatWriters(runtime);
        }
        if (pendingGpuSteps() == 0) writers.applyClears(latestGaussianSplatWriters);
        return writers.draw(input.pose(), new Vec3(frame.originX() * .01, frame.originZ() * .01, frame.originY() * .01));
    }
    public NiagaraCpuDebugLines.@Nullable Snapshot cpuDebugLines() {
        return playingCache() ? null : interfaces.debugLines().snapshot();
    }

    public NiagaraGpuBatchDispatcher gpuDispatcher() {
        if (gpuDispatcher == null) gpuDispatcher = new NiagaraGpuBatchDispatcher(this);
        return gpuDispatcher;
    }
    public int gpuEmitterIndex(int order) { return gpuOrder[order]; }
    public boolean gpuEmitterComplete(int index) {
        if (cachePlayer != null) {
            var visible = usingRetainedCacheFrame() ? resumeGpuVisible : cacheGpuVisible;
            return visible == null || !visible[index];
        }
        if (statelessGpu[index] != null) return statelessGpu[index].complete();
        if (gpuEmitters[index] == null) throw new IllegalArgumentException("Not a GPU Niagara emitter");
        return gpuEmitters[index].complete();
    }
    public int emitterGroup(int index) { return schedule.group(index); }
    public int emitterEstimatedWorkload(int index) {
        if (isGpuEmitter(index)) return 8;
        NiagaraCpuEmitter emitter = emitters[index];
        return emitter == null ? 1 : Math.max(1, emitter.count());
    }
    public boolean emitterUsesSynchronousCollision(int index) { return execute && cachePlayer == null && emitterSynchronousCollision[index]; }
    public void prepareGpuEmitter(int index) {
        if (cachePlayer != null) return;
        if (statelessGpu[index] != null) {
            if (!execute) return;
            if (statelessGpu[index].tick(frame, frame.systemExecutionState, spawnConstants, updateConstants, input, gpuTickCapacity))
                Objects.requireNonNull(interfaces.emitterBounds(definition.emitters().get(index).sourceName())).postTick(null);
            return;
        }
        NiagaraGpuEmitterState emitter = gpuEmitters[index];
        if (emitter == null) throw new IllegalArgumentException("Not a GPU Niagara emitter");
        if (!execute) return;
        var ticks = gpuTicks.get(index);
        if (ticks.size() >= gpuTickCapacity) throw new IllegalStateException("Niagara GPU batch exceeds its declared tick capacity");
        NiagaraGpuEmitterState.Step tick;
        try {
            tick = emitter.prepareTick(frame, current, variables, input, true, anyEmitterShouldTick, interfaces.textures().snapshots(),
                    interfaces.dataChannelSpawns(index));
        } catch (NiagaraGpuParameterValues.InterfaceBindingException failure) {
            tick = emitter.disableForInterfaceBinding(failure);
        }
        if (emitter.ticked()) rendererParameterStores[index].updateExternalObjects(instanceParameterStores.override());
        // Emitters share the system's host-step indices even across complete or disabled slots.
        ticks.add(tick);
        if (emitter.ticked())
            Objects.requireNonNull(interfaces.emitterBounds(definition.emitters().get(index).sourceName())).postTick(null);
    }

    public void beginGpuBatch(int capacity) {
        beginGpuBatch(capacity, false);
    }

    public void beginGpuBatch(int capacity, boolean hostDeferred) {
        if (capacity < 0) throw new IllegalArgumentException("Negative Niagara GPU tick capacity");
        int pending = pendingGpuSteps();
        if (pending != 0 && !deferredGpuBatch)
            throw new IllegalStateException("Previous Niagara GPU tick batch has not been released");
        for (int index = 0; index < gpuTickCursor.length; index++)
            if (gpuTickCursor[index] != 0 || !gpuPlans.get(index).isEmpty())
                throw new IllegalStateException("Niagara GPU dispatch is in progress");
        gpuTickCapacity = Math.addExact(pending, capacity);
        deferredGpuBatch = hostDeferred || pending != 0;
        hostGpuBatch = hostDeferred;
        for (var emitter : statelessGpu) if (emitter != null) emitter.beginBatch();
    }

    public void prepareStatelessRenderState(int index) {
        if (statelessGpu[index] == null) throw new IllegalArgumentException("Not an analytic GPU emitter");
        statelessGpu[index].renderFrame();
    }

    public @Nullable NiagaraGpuParticleView statelessGpuParticles(int index, CommandEncoder encoder,
                                                                 NiagaraGpuPrograms programs, boolean allowGpuGeneration) {
        NiagaraStatelessGpuEmitterState emitter = statelessGpu[index];
        if (emitter == null) throw new IllegalArgumentException("Not an analytic GPU emitter");
        var state = emitter.renderFrame();
        if (state == null || !state.eligible() || state.executionState() == 3 || state.executionState() == 4) return null;
        var plan = state.plan();
        if (plan.active() == 0) return null;
        var cache = programs.statelessData();
        var result = cache.request(cache.frame(), emitter.renderKey(), definition.emitters().get(index).attributes(),
                Math.toIntExact(plan.active()), emitter.reuseAllocations());
        if (result == null) return null;
        var generation = emitter.simulation().prepare(encoder, programs, result, state, plan,
                allowGpuGeneration && cache.allowsGpuGeneration(cache.frame()), emitter.cpuThreshold());
        var dispatch = generation.dispatch();
        if (dispatch != null) cache.enqueue(cache.frame(), dispatch);
        return generation.available() ? result : null;
    }
    public @Nullable NiagaraGpuParticleView prepareStatelessGpuParticles(int index, CommandEncoder encoder,
                                                                        NiagaraGpuPrograms programs) {
        return statelessGpuParticles(index, encoder, programs, true);
    }

    public int pendingGpuTicks(int index) { return gpuTicks.get(index).size(); }

    public @Nullable NiagaraAsyncGpuTraceDefinition gpuAsyncTraceDefinition(int emitter, int step, String binding,
            String original, NiagaraAsyncGpuTraceDefinition fallback) {
        var tick = (NiagaraGpuEmitterState.Tick) Objects.requireNonNull(gpuTicks.get(emitter).get(step));
        String identity = tick.external().dataInterfaces().getOrDefault(binding, original);
        var interfaceStep = step < gpuInterfaceSteps.size() ? gpuInterfaceSteps.get(step) : null;
        var snapshot = interfaceStep == null ? null : interfaceStep.asyncTraces().get(SourceNames.canonical(identity));
        if (snapshot != null) return snapshot.definition();
        return SourceNames.equal(identity, original) ? fallback : null;
    }

    public boolean hasStatefulGpuEmitters() {
        if (cachePlayer != null) return false;
        for (var emitter : gpuEmitters) if (emitter != null && !emitter.disabled()) return true;
        return false;
    }

    public boolean canDeferGpu(EffectHandle.Input input, NiagaraGpuPrograms programs,
                               Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        if (!interfaces.textures().retainsAcrossFrames(gpuTextureIds, input.textureParameters())) return false;
        for (int index = 0; index < gpuEmitters.length; index++) {
            var emitter = gpuEmitters[index];
            if (emitter != null && !emitter.disabled()
                    && !gpuDispatcher().supportsViewlessDispatch(index, programs, shaderLookup)) return false;
        }
        return true;
    }
    public int pendingGpuSteps() {
        int pending = 0;
        for (var ticks : gpuTicks) pending = Math.max(pending, ticks.size());
        return pending;
    }

    public boolean gpuTickExecutes(int index, int step) {
        return gpuTicks.get(index).get(step) instanceof NiagaraGpuEmitterState.Tick tick && tick.execute();
    }

    private static boolean updatesGpuCountFence(NiagaraGpuEmitterState.@Nullable Step step) {
        return step instanceof NiagaraGpuEmitterState.Clear || step instanceof NiagaraGpuEmitterState.Tick tick && tick.execute();
    }

    /** Consumes a host step that does not dispatch particle stages. A clear still affects later steps. */
    public void consumeUnsubmittedGpuTick(int index) {
        var ticks = gpuTicks.get(index);
        int cursor = gpuTickCursor[index];
        if (cursor >= ticks.size()) throw new IllegalStateException("Niagara GPU emitter has no prepared tick");
        var tick = ticks.get(cursor);
        if (tick instanceof NiagaraGpuEmitterState.Tick dispatch && dispatch.execute())
            throw new IllegalStateException("Niagara GPU emitter tick requires dispatch");
        if (tick instanceof NiagaraGpuEmitterState.Clear) {
            gpuPrefixMutated = true;
            clearGpuEmitterData(index);
        }
        gpuTickCursor[index] = cursor + 1;
    }

    public int lastGpuSimulationTick(int index) {
        return lastGpuSimulationTick(index, gpuTicks.get(index).size());
    }

    public int lastGpuSimulationTick(int index, int steps) {
        var ticks = gpuTicks.get(index);
        for (int i = Math.min(ticks.size(), steps) - 1; i >= 0; i--) if (gpuTickExecutes(index, i)) return i;
        return -1;
    }

    public int planGpuEmitter(int index, PreparedNiagaraShaders shaders) {
        return planGpuEmitter(index, shaders, gpuTicks.get(index).size());
    }

    public int planGpuEmitter(int index, PreparedNiagaraShaders shaders, int steps) {
        var plans = gpuPlans.get(index);
        if (!plans.isEmpty()) throw new IllegalStateException("Niagara GPU tick batch is already planned");
        int lastOutput = -1;
        var ticks = gpuTicks.get(index);
        for (int step = 0, count = Math.min(steps, ticks.size()); step < count; step++) {
            var tick = ticks.get(step);
            List<NiagaraGpuStageSchedule.Invocation> stages = new ArrayList<>();
            if (tick instanceof NiagaraGpuEmitterState.Tick dispatch && dispatch.execute())
                shaders.schedule().forEach(dispatch.stageParameters(), dispatch.reset(), stages::add);
            plans.add(List.copyOf(stages));
            if (!stages.isEmpty()) lastOutput = plans.size() - 1;
        }
        return lastOutput;
    }

    public void releaseGpuTicks() {
        releaseGpuPrefix(pendingGpuSteps());
    }

    /** Keeps unsubmitted host ticks and their snapshots available to the next owning view. */
    public void deferUnsubmittedGpuPrefix() {
        if (gpuPrefixMutated) throw new IllegalStateException("Cannot defer a partially submitted Niagara GPU prefix");
        for (int i = 0; i < gpuTickCursor.length; i++) {
            gpuPlans.get(i).clear();
            gpuTickCursor[i] = 0;
        }
        deferredGpuBatch = pendingGpuSteps() != 0;
    }

    public void gpuSubmissionFailed(Throwable failure) {
        if (!gpuPrefixMutated) {
            deferUnsubmittedGpuPrefix();
            return;
        }
        failGpuInstance(failure);
    }

    private void failGpuInstance(Throwable failure) {
        // A submitted stage can already have changed shared interfaces and external render targets.
        // Replaying its host tick would apply those writes twice.
        complete = true;
        execute = false;
        var owner = channelOwner;
        if (owner != null) {
            owner.invalidate();
            var capture = owner.niagaraCacheCapture();
            if (capture != null) capture.result().completeExceptionally(failure);
        }
    }

    private void releaseGpuPrefix(int steps) {
        if (steps < 0 || steps > pendingGpuSteps()) throw new IllegalArgumentException("Invalid Niagara GPU tick prefix");
        for (int i = 0; i < gpuEmitters.length; i++)
            if (gpuTickCursor[i] != Math.min(steps, pendingGpuTicks(i)))
                throw new IllegalStateException("Niagara GPU tick prefix was not fully consumed");
        for (int i = 0; i < gpuEmitters.length; i++) {
            var ticks = gpuTicks.get(i);
            int consumed = gpuTickCursor[i];
            if (consumed != 0) {
                for (int step = consumed - 1; step >= 0; step--) {
                    var tick = ticks.get(step);
                    if (updatesGpuCountFence(tick)) {
                        gpuEmitters[i].releaseCountFence(tick.particleCountFence());
                        break;
                    }
                }
                ticks.subList(0, consumed).clear();
            }
            if (gpuSimulations[i] != null) gpuSimulations[i].finishBatch();
            gpuPlans.get(i).clear(); gpuTickCursor[i] = 0;
        }
        releaseGpuInterfacePrefix(Math.min(steps, gpuInterfaceSteps.size()));
        if (pendingGpuSteps() == 0) releaseGpuInterfaceSteps();
        gpuPrefixMutated = false;
    }

    private void captureGpuInterfaceStep(@Nullable ClientLevel level, @Nullable NiagaraGpuPrograms programs) {
        int pendingSteps = pendingGpuSteps();
        if (pendingSteps <= gpuInterfaceSteps.size()) return;
        if (pendingSteps != gpuInterfaceSteps.size() + 1)
            throw new IllegalStateException("Niagara GPU host steps must be finalized in submission order");
        if (hostGpuBatch) deferredGpuBatch = true;
        boolean dispatch = false;
        var textureIds = new HashSet<String>();
        for (int index = 0; index < gpuTicks.size(); index++) {
            var ticks = gpuTicks.get(index);
            if (ticks.size() != pendingSteps || !(ticks.getLast() instanceof NiagaraGpuEmitterState.Tick tick) || !tick.execute()) continue;
            dispatch = true;
            gpuEmitters[index].collectTextureInputs(tick, textureIds);
        }
        if (!dispatch && latestGaussianSplatWriters.isEmpty()) {
            gpuInterfaceSteps.add(null);
            return;
        }
        var cameras = interfaces.captureCameraModes();
        var bounds = gpuEmitterPropertiesUsed ? interfaces.captureEmitterBounds() : Map.<String, NiagaraEmitterBounds.Snapshot>of();
        var mutable = interfaces.captureMutableGpuInputs(gpuMutableIds);
        var platformValues = platformSets.captureValues();
        var objectProperties = interfaces.captureObjectProperties();
        var propertyInterfaces = interfaces.capturePropertyInterfaces(gpuPropertyInterfaceIds);
        var actorComponents = interfaces.captureActorComponents();
        var staticMeshes = interfaces.captureStaticMeshes(gpuStaticMeshIds);
        var skeletalMeshes = interfaces.captureSkeletalMeshes(gpuSkeletalMeshIds);
        var particleExports = interfaces.captureParticleExports(gpuParticleExportIds);
        var audioFrames = interfaces.captureAudioFrames(gpuAudioAnalysisIds);
        var simCacheReaders = interfaces.captureSimCacheReaders(gpuSimCacheReaderIds);
        var sparseVolumes = interfaces.captureSparseVolumes(gpuSparseVolumeIds);
        var volumeCaches = interfaces.captureVolumeCaches(gpuVolumeCacheIds);
        var physicsAssets = interfaces.physicsAssetSnapshots(gpuPhysicsAssetIds);
        var rigidMeshes = interfaces.rigidMeshSnapshots(gpuRigidMeshIds);
        var geometryCollections = interfaces.captureGeometryCollections(gpuGeometryCollectionIds);
        var chaosDestruction = interfaces.captureChaosDestruction(gpuChaosDestructionIds);
        var hairStrands = interfaces.captureHairStrands(gpuHairStrandsIds);
        var memoryBuffers = interfaces.captureMemoryBuffers(gpuMemoryBufferIds);
        var neighborGrids = interfaces.captureNeighborGrids(gpuNeighborGridIds);
        var rasterizationGrids = interfaces.captureRasterizationGrids(gpuRasterizationGridIds);
        var velocityGrids = interfaces.captureVelocityGrids(gpuVelocityGridIds);
        var grid3DCollections = gpuGrid3DCollectionIds.isEmpty() ? Map.<String, NiagaraGrid3DCollections.Publication>of()
                : interfaces.captureGrid3DCollections();
        var grid2DCollections = gpuGrid2DCollectionIds.isEmpty() ? Map.<String, NiagaraGrid2DCollections.Publication>of()
                : interfaces.captureGrid2DCollections();
        var dynamicMeshes = interfaces.captureDynamicMeshes(gpuDynamicMeshIds);
        var textures = textureIds.isEmpty() ? null : interfaces.textures().retainStep(textureIds);
        NiagaraGpuRenderTargets.Step targets = null;
        NiagaraGpuDimensionalRenderTargets.Step dimensionalTargets = null;
        Map<String, NiagaraDataChannelGpuSnapshot> channelReaders = Map.of();
        Map<String, NiagaraDataChannelState.GpuWriter> channelWriters = Map.of();
        Map<String, NiagaraSceneCapture.Publication> sceneCaptures = Map.of();
        Map<String, NiagaraLandscape.Frame> landscapes = Map.of();
        Map<String, NiagaraVirtualTextureState.Frame> virtualTextures = Map.of();
        var intTargets = new LinkedHashMap<String, IntTargetPublication>();
        try {
            if (hostGpuBatch && textures != null && !textures.retainedAcrossFrames())
                throw new IllegalStateException("Niagara texture provider did not retain its deferred GPU publication");
            targets = gpuRenderTargets == null ? null : gpuRenderTargets.captureStep();
            dimensionalTargets = gpuDimensionalTargets == null ? null : gpuDimensionalTargets.captureStep();
            channelReaders = interfaces.captureDataChannelReaders(gpuDataChannelReaderIds, emitters.length);
            channelWriters = interfaces.captureDataChannelWriters(gpuDataChannelWriterIds);
            sceneCaptures = interfaces.captureSceneCaptures(gpuSceneCaptureIds, hostGpuBatch);
            landscapes = interfaces.captureLandscapes(gpuLandscapeIds);
            virtualTextures = interfaces.captureVirtualTextures(gpuVirtualTextureIds);
            if (!gpuIntRenderTargetIds.isEmpty()) {
                if (gpuIntRenderTargets == null) {
                    gpuIntRenderTargets = new NiagaraGpuIntRenderTargets(programs == null ? cpuTargetRuntime() : programs.runtime());
                    interfaces.intRenderTargetExposure(gpuIntRenderTargets::exposed);
                }
                var integerTargets = gpuIntRenderTargets;
                interfaces.captureIntRenderTargets().forEach((id, control) -> {
                    if (gpuIntRenderTargetIds.contains(id)) intTargets.put(id, integerTargets.capture(id, control));
                });
            }
            gpuInterfaceSteps.add(new GpuInterfaceStep(targets, dimensionalTargets, textures, cameras, bounds, mutable, platformValues,
                    objectProperties, propertyInterfaces, actorComponents, staticMeshes, skeletalMeshes, particleExports, channelReaders, channelWriters,
                    audioFrames, parameterSources.captureAsyncGpuTraces(asyncTraceInstances::definition), simCacheReaders, sparseVolumes,
                    volumeCaches, physicsAssets, rigidMeshes, geometryCollections,
                    chaosDestruction, hairStrands,
                    interfaces.physicsField().renderField(worldPhysicsField), sceneCaptures,
                    landscapes, virtualTextures,
                    memoryBuffers, neighborGrids, rasterizationGrids, velocityGrids, grid3DCollections,
                    grid2DCollections, interfaces.captureDataTables(), interfaces.captureGaussianSplatReaders(),
                    latestGaussianSplatWriters,
                    interfaces.captureIntDistributions(), Map.copyOf(intTargets),
                    gpuSocketIds.isEmpty() ? Map.of() : interfaces.captureSocketReaders(), interfaces.captureSplines(gpuSplineIds), interfaces.viewQueries(),
                    interfaces.captureNeighborQueries(gpuNeighborQueryIds),
                    dynamicMeshes, level, frame.platform, consoleInitialization));
        } catch (RuntimeException | Error failure) {
            channelReaders.values().forEach(NiagaraDataChannelGpuSnapshot::close);
            channelWriters.values().forEach(NiagaraDataChannelState.GpuWriter::close);
            sceneCaptures.values().forEach(NiagaraSceneCapture.Publication::close);
            landscapes.values().forEach(NiagaraLandscape.Frame::close);
            virtualTextures.values().forEach(NiagaraVirtualTextureState.Frame::close);
            intTargets.values().forEach(IntTargetPublication::close);
            try { if (dimensionalTargets != null) dimensionalTargets.close(); }
            finally {
                try { if (targets != null) targets.close(); }
                finally { if (textures != null) textures.close(); }
            }
            throw failure;
        }
    }

    private void releaseGpuInterfaceSteps() {
        releaseGpuInterfacePrefix(gpuInterfaceSteps.size());
        deferredGpuBatch = false;
    }

    private void releaseGpuInterfacePrefix(int count) {
        var consumed = gpuInterfaceSteps.subList(0, count);
        Throwable failure = null;
        for (var step : consumed) if (step != null) {
            for (var lease : step.channelReaders().values()) failure = retireGpuStepLease(lease, failure);
            for (var lease : step.channelWriters().values()) failure = retireGpuStepLease(lease, failure);
            for (var lease : step.sceneCaptures().values()) failure = retireGpuStepLease(lease, failure);
            for (var lease : step.landscapes().values()) failure = retireGpuStepLease(lease, failure);
            for (var lease : step.virtualTextures().values()) failure = retireGpuStepLease(lease, failure);
            for (var lease : step.intRenderTargets().values()) failure = retireGpuStepLease(lease, failure);
            failure = retireGpuStepLease(step.dimensionalTargets(), failure);
            failure = retireGpuStepLease(step.targets(), failure);
            failure = retireGpuStepLease(step.textures(), failure);
        }
        consumed.clear();
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Failed to retire a Niagara GPU interface snapshot", failure);
    }

    private static @Nullable Throwable retireGpuStepLease(@Nullable AutoCloseable lease, @Nullable Throwable failure) {
        if (lease != null) try { lease.close(); }
        catch (Exception | Error error) {
            if (failure == null) return error;
            if (failure != error) failure.addSuppressed(error);
        }
        return failure;
    }
    public NiagaraDefinition.Emitter gpuEmitter(int index) {
        if (!isGpuEmitter(index)) throw new IllegalArgumentException("Not a GPU Niagara emitter");
        return definition.emitters().get(index);
    }
    public VectorDataSet gpuSystemData() { return current; }
    public Map<String, NiagaraDefinition.Variable> gpuVariables() { return variables; }
    public EffectHandle.Input gpuInput() { return input; }
    public void dynamicMaterialObjects(NiagaraMaterialObjects objects) { interfaces.dynamicMaterialObjects(objects); }

    public NiagaraMaterialInstances.EngineValues materialEngineValues(int index) {
        return materialEngineValues[index];
    }
    private EffectHandle.Pose previousRendererPose;
    public void previousRendererPose(EffectHandle.Pose pose) { previousRendererPose = pose; }
    public EffectHandle.Pose previousRendererPose() { return previousRendererPose == null ? input.pose() : previousRendererPose; }
    public boolean componentVelocity() { return rendererVelocity(input.pose(), previousRendererPose()); }
    public boolean rendererVelocity(EffectHandle.Pose pose, EffectHandle.Pose previous) {
        return alwaysVelocity || NiagaraVelocityPolicy.moved(pose, previous);
    }
    public double gpuOriginX() { return frame.originX(); }
    public double gpuOriginY() { return frame.originY(); }
    public double gpuOriginZ() { return frame.originZ(); }
    public Vec3 gpuRenderOrigin(int emitter) {
        var origin = gpuRenderOrigins[emitter];
        return origin == null ? new Vec3(frame.originX(), frame.originY(), frame.originZ()) : origin;
    }
    public NiagaraBounds rendererBounds() {
        if (committedComponentBounds != null) return committedComponentBounds.scaleExtent(definition.component().boundsScale());
        return componentFallbackBounds();
    }

    private NiagaraBounds componentFallbackBounds() {
        if (externalFixedBounds != null) return externalFixedBounds;
        if (definition.systemFixedBoundsEnabled()) {
            NiagaraBounds fixed = definition.systemFixedBounds();
            if (fixed == null) throw new IllegalStateException("Niagara system has invalid fixed bounds");
            return fixed;
        }
        return new NiagaraBounds(-1, -1, -1, 1, 1, 1);
    }

    /** Commits the raw component-local box after the owning world tick, as NiagaraComponent::PostTick does. */
    public void updateComponentBounds(float worldDelta) {
        forceBoundsUpdateTime += worldDelta;
        NiagaraBounds next = gpuBounds();
        if (committedComponentBounds == null || !contains(committedComponentBounds, next)
                || forceBoundsUpdateTime > 5.0) {
            committedComponentBounds = next;
            forceBoundsUpdateTime = 0.0;
        }
    }

    public void resetComponentBounds() {
        committedComponentBounds = null;
        forceBoundsUpdateTime = 0.0;
    }

    private static boolean contains(NiagaraBounds outer, NiagaraBounds inner) {
        return outer.minX() <= inner.minX() && outer.minY() <= inner.minY() && outer.minZ() <= inner.minZ()
                && outer.maxX() >= inner.maxX() && outer.maxY() >= inner.maxY() && outer.maxZ() >= inner.maxZ();
    }
    public long renderIdentity() { return renderIdentity; }

    public double gpuSortDepth(Vec3 camera) {
        return gpuSortDepth(camera, null);
    }

    public double gpuSortDepth(Vec3 camera, @Nullable MeshView view) {
        return MeshDrawSelection.depth(input.pose(), rendererBounds(), camera, view);
    }

    private NiagaraBounds gpuBounds() {
        if (cachePlayer != null && cacheBounds != null) return cacheBounds;
        if (externalFixedBounds != null) return externalFixedBounds;
        NiagaraBounds bounds;
        if (definition.systemFixedBoundsEnabled()) {
            bounds = definition.systemFixedBounds();
            if (bounds == null) throw new IllegalStateException("Niagara system has invalid fixed bounds");
        } else {
            NiagaraBounds dynamic = null, fixed = null;
            for (int i = 0; i < emitters.length; i++) {
                var source = definition.emitters().get(i);
                if (source.simulation() instanceof NiagaraDefinition.DisabledSimulation disabled && !disabled.hasEmitter()) continue;
                var state = Objects.requireNonNull(interfaces.emitterBounds(source.sourceName())).snapshot();
                NiagaraBounds emitterBounds = state.bounds();
                if (emitterBounds == null) continue;
                if (state.dynamic())
                    dynamic = dynamic == null ? emitterBounds : dynamic.union(emitterBounds);
                else fixed = fixed == null ? emitterBounds : fixed.union(emitterBounds);
            }
            bounds = dynamic == null ? fixed : dynamic.scaleExtent(1.1);
            if (fixed != null && dynamic != null) bounds = bounds.union(fixed);
            if (bounds == null) bounds = new NiagaraBounds(-1, -1, -1, 1, 1, 1);
        }
        return bounds;
    }

    public int gpuAllocationEstimate(int index, NiagaraGpuPrograms programs) {
        var source = definition.emitters().get(index);
        if (!(source.simulation() instanceof NiagaraDefinition.GpuSimulation gpu))
            throw new IllegalArgumentException("Not a GPU Niagara emitter");
        var settings = gpu.metadata().getAsJsonObject("emitter_settings");
        if (GpuAllocationMode.fromSource(settings) != GpuAllocationMode.AUTOMATIC_ESTIMATE)
            return gpuPreAllocationCount(settings);
        Identifier id = Identifier.fromNamespaceAndPath(gpu.hlslSource().getNamespace(), gpu.shaderPipeline());
        return programs.allocationEstimate(id);
    }

    private enum GpuAllocationMode {
        AUTOMATIC_ESTIMATE, MANUAL_ESTIMATE, FIXED_COUNT;

        static GpuAllocationMode fromSource(JsonObject settings) {
            String source = settings.has("AllocationMode") ? settings.get("AllocationMode").getAsString() : "AutomaticEstimate";
            String mode = source.substring(source.lastIndexOf(':') + 1);
            return switch (mode) {
                case "0", "AutomaticEstimate" -> AUTOMATIC_ESTIMATE;
                case "1", "ManualEstimate" -> MANUAL_ESTIMATE;
                case "2", "FixedCount" -> FIXED_COUNT;
                default -> throw new IllegalArgumentException("Unknown Niagara allocation mode " + mode);
            };
        }
    }

    private static int gpuPreAllocationCount(JsonObject settings) {
        return settings.has("PreAllocationCount") ? Math.max(0, settings.get("PreAllocationCount").getAsInt()) : 0;
    }

    private void prepareGpuMutableResources(NiagaraGpuPrograms programs) {
        if (gpuArrays == null) {
            var mutableInterfaces = interfaces.mutableInterfaceIds();
            if (gpuCounters == null) gpuCounters = new NiagaraGpuCountArena(definition.emitters(), programs, mutableInterfaces);
            else gpuCounters.rebindInterfaces(programs, mutableInterfaces);
            gpuArrays = new NiagaraGpuArrayRegistry(programs, gpuCounters, interfaces, definition.emitters());
        }
        if (gpuInterfaceCounters == null) gpuInterfaceCounters = new NiagaraGpuCounterRegistry(programs, gpuCounters, interfaces);
    }

    public void uploadCachedGpuInterfaces(CommandEncoder encoder, NiagaraGpuPrograms programs) {
        uploadCachedGrooms(encoder, programs);
        var storage = publishedCacheInterfaces();
        if (storage != null) uploadCachedGpuInterfaces(encoder, programs, storage, false);
    }

    private boolean usingRetainedCacheFrame() {
        return cachePlayer != null && !cachePlayer.hasReadFrame() && lastSampledCache != null;
    }

    private @Nullable NiagaraCacheInterfacePlayback publishedCacheInterfaces() {
        return usingRetainedCacheFrame() ? resumeCacheInterfaces : cacheInterfaces;
    }

    private void uploadCachedGpuInterfaces(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                           NiagaraCacheInterfacePlayback storage, boolean resuming) {
        var state = storage.gpu();
        if (!state.arrays().isEmpty() || !state.counters().isEmpty()) {
            prepareGpuMutableResources(programs);
            state.arrays().forEach((id, data) -> {
                if (resuming) gpuArrays.resumeCached(encoder, id, data, storage.arrayLiveRevision(id));
                else gpuArrays.prepareCached(encoder, id, data);
            });
            state.counters().forEach((id, data) -> {
                if (resuming) gpuInterfaceCounters.resumeCached(encoder, id, data, storage.counterLiveRevision(id));
                else gpuInterfaceCounters.prepareCached(encoder, id, data);
            });
        }
        if (!storage.memory().isEmpty()) {
            if (gpuMemoryBuffers == null) gpuMemoryBuffers = new NiagaraGpuMemoryBuffers(programs, interfaces::memoryBuffer);
            storage.memory().forEach((id, data) -> {
                if (resuming) gpuMemoryBuffers.resumeCache(encoder, id, data.words().words(), data.revision(), data.liveRevision(), data.sync());
                else gpuMemoryBuffers.restoreCache(encoder, id, data.words().words(), data.revision());
            });
        }
        if (!storage.meshes().isEmpty()) {
            if (gpuDynamicMeshes == null) gpuDynamicMeshes = new NiagaraGpuDynamicMeshes(programs);
            storage.meshes().forEach((id, data) -> gpuDynamicMeshes.prepare(encoder, id, data));
        }
    }

    private void uploadCachedGrooms(CommandEncoder encoder, NiagaraGpuPrograms programs) {
        boolean retained = usingRetainedCacheFrame();
        var player = retained ? lastSampledCache : cachePlayer;
        var cachedFrames = retained ? resumeGpuFrames : cacheGpuFrames;
        if (gpuHairStrandsIds.isEmpty() || player == null || cachedFrames == null) return;

        var active = new LinkedHashSet<String>();
        for (int i = 0; i < gpuEmitters.length; i++) {
            var emitter = gpuEmitters[i];
            if (emitter == null || emitter.disabled()) continue;
            var simulation = (NiagaraDefinition.GpuSimulation) definition.emitters().get(i).simulation();
            var authored = new LinkedHashMap<String, String>();
            for (var item : simulation.metadata().getAsJsonArray("interfaces")) {
                var binding = item.getAsJsonObject();
                if (binding.has("hair_strands") && binding.has("shader_symbol"))
                    authored.put(SourceNames.canonical(binding.get("compile_name").getAsString()),
                            binding.get("interface_id").getAsString());
            }
            if (authored.isEmpty()) continue;
            var selected = emitter.dataInterfaceSelections(authored.keySet());
            authored.forEach((name, id) -> active.add(selected.getOrDefault(name, id)));
        }
        if (active.isEmpty()) return;
        var frames = interfaces.captureHairStrands(active);
        if (gpuHairStrands == null) gpuHairStrands = new NiagaraGpuHairBuffers(programs.runtime());
        for (int index = 0; index < cachedFrames.length; index++) {
            var frame = cachedFrames[index];
            if (frame == null || gpuEmitterComplete(index) || gpuParticleCounts[index] == 0) continue;
            int node = cacheGroomColumn(player, index, "NodePosition");
            int rest = cacheGroomColumn(player, index, "RestPosition");
            if (node < 0 || rest < 0) continue;
            var particles = frame.particles(encoder, programs);
            for (var selected : frames.entrySet()) NiagaraGpuGroomCache.apply(encoder, programs, gpuHairStrands,
                    selected.getKey(), selected.getValue(), particles, node, rest, gpuParticleCounts[index]);
        }
    }

    private int cacheGroomColumn(NiagaraCachePlayer player, int index, String name) {
        String vectorType = "/Script/CoreUObject.Vector3f";
        if (!player.hasEmitterFloat3(index, name, vectorType)) return -1;
        for (var attribute : definition.emitters().get(index).attributes()) {
            if (attribute.type() == 0 && attribute.count() == 3 && SourceNames.equal(attribute.name(), name)
                    && SourceNames.equal(attribute.sourceType(), vectorType)) return attribute.component();
        }
        return -1;
    }

    public NiagaraGpuInterfaces gpuInterfaces(Identifier id, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                              JsonObject metadata, String emitterName) {
        prepareGpuMutableResources(programs);
        if (gpuMemoryBuffers == null && !gpuMemoryBufferIds.isEmpty())
            gpuMemoryBuffers = new NiagaraGpuMemoryBuffers(programs, interfaces::memoryBuffer);
        if (gpuDynamicMeshes == null && !gpuDynamicMeshIds.isEmpty())
            gpuDynamicMeshes = new NiagaraGpuDynamicMeshes(programs);
        if (gpuNeighborGrids == null && !gpuNeighborGridIds.isEmpty())
            gpuNeighborGrids = new NiagaraGpuNeighborGrids(programs);
        if (gpuNeighborQueries == null && !gpuNeighborQueryIds.isEmpty()) gpuNeighborQueries = new NiagaraGpuNeighborQueries(programs);
        if (gpuRasterizationGrids == null && !gpuRasterizationGridIds.isEmpty())
            gpuRasterizationGrids = new NiagaraGpuRasterizationGrids(programs);
        if (gpuVelocityGrids == null && !gpuVelocityGridIds.isEmpty())
            gpuVelocityGrids = new NiagaraGpuVelocityGrids(programs.runtime());
        if (gpuGrid3DCollections == null && !gpuGrid3DCollectionIds.isEmpty())
            gpuGrid3DCollections = new NiagaraGpuGrid3DCollections(programs);
        if (gpuGrid2DCollections == null && !gpuGrid2DCollectionIds.isEmpty())
            gpuGrid2DCollections = new NiagaraGpuGrid2DCollections(programs.runtime());
        if (gpuSocketBuffers == null && !gpuSocketIds.isEmpty()) gpuSocketBuffers = new NiagaraGpuSocketBuffers(programs.runtime());
        if (gpuVolumeCaches == null && !gpuVolumeCacheIds.isEmpty()) gpuVolumeCaches = new NiagaraGpuVolumeCaches();
        if (gpuPhysicsAssets == null && !gpuPhysicsAssetIds.isEmpty()) gpuPhysicsAssets = new NiagaraGpuPhysicsBuffers();
        if (gpuRigidMeshes == null && !gpuRigidMeshIds.isEmpty()) gpuRigidMeshes = new NiagaraGpuRigidMeshBindings.Buffers();
        if (gpuGeometryCollections == null && !gpuGeometryCollectionIds.isEmpty())
            gpuGeometryCollections = new NiagaraGpuGeometryBuffers(programs.runtime());
        if (gpuChaosDestruction == null && !gpuChaosDestructionIds.isEmpty())
            gpuChaosDestruction = new NiagaraGpuChaosBuffers(programs.runtime());
        if (gpuHairStrands == null && !gpuHairStrandsIds.isEmpty())
            gpuHairStrands = new NiagaraGpuHairBuffers(programs.runtime());
        if (gpuLandscapes == null && !gpuLandscapeIds.isEmpty()) gpuLandscapes = new CollisionGrids();
        if (gpuSplineBuffers == null && !gpuSplineIds.isEmpty()) gpuSplineBuffers = new NiagaraGpuSplineBuffers(programs.runtime());
        if (gpuCurves == null) gpuCurves = new NiagaraGpuCurveRegistry(programs, parameterSources::gpuCurve);
        if (gpuRendererInformation == null) gpuRendererInformation = new NiagaraGpuRendererInfoRegistry(programs,
                parameterSources::spriteInformation, parameterSources::meshInformation);
        if (gpuRenderTargets == null) {
            gpuRenderTargets = new NiagaraGpuRenderTargets(programs, interfaces);
            for (var binding : gpuRenderTargetBindings.values()) {
                gpuRenderTargets.register(binding);
                String targetId = binding.get("interface_id").getAsString();
                gpuRenderTargets.prepare(targetId, interfaces.renderTarget2D(targetId).snapshot());
            }
        }
        gpuRenderTargets.gpuPrograms(programs);
        var result = new NiagaraGpuInterfaces(id, programs, shaders, metadata, gpuArrays, gpuInterfaceCounters, emitterName,
                interfaces.textures(),
                this::gpuParticleSource, name -> {
                    var state = interfaces.emitterBounds(name);
                    return state == null ? null : state.renderSnapshot();
                }, gpuRenderTargets, platformSets, parameterSources::curlParameters, parameterSources::vectorField, gpuCurves);
        result.cameraSource(interfaces::cameraSplitscreen);
        result.proxySourceNames(gpuProxySourceNames);
        result.emitterSource(parameterSources::emitterBinding);
        result.asyncTraceSource(parameterSources::asyncGpuTrace);
        if (!gpuDataChannelReaderIds.isEmpty()) {
            if (gpuDataChannelReaders == null) gpuDataChannelReaders = new NiagaraGpuDataChannelReaders(programs);
            result.dataChannelReaders(gpuDataChannelReaders);
        }
        if (!gpuDataChannelWriterIds.isEmpty()) {
            if (gpuDataChannelWriters == null) gpuDataChannelWriters = new NiagaraGpuDataChannelWriters(programs);
            result.dataChannelWriters(gpuDataChannelWriters);
        }
        result.rendererInformation(metadata, gpuRendererInformation);
        if (gpuDimensionalTargets != null) {
            gpuDimensionalTargets.gpuPrograms(programs);
            result.dimensionalTargets(gpuDimensionalTargets);
        }
        return result;
    }

    public void finishGpuFrame(CommandEncoder encoder) {
        finishGpuPrefix(encoder, pendingGpuSteps());
    }

    public void finishGpuPrefix(CommandEncoder encoder, int steps) {
        try {
            releaseGpuPrefix(steps);
            if (gpuInterfaceCounters != null) gpuInterfaceCounters.postFrame(encoder);
            if (gpuMemoryBuffers != null) gpuMemoryBuffers.postFrame(encoder, channelWorld);
            if (gpuDataChannelReaders != null) gpuDataChannelReaders.endBatch();
            if (gpuDataChannelWriters != null) gpuDataChannelWriters.endBatch(encoder);
        } catch (RuntimeException | Error failure) {
            failGpuInstance(failure);
            throw failure;
        }
    }

    private NiagaraGpuParticleRead.Source gpuParticleSource(String name) {
        for (int index = 0; index < emitters.length; index++) {
            var emitter = definition.emitters().get(index);
            if (!SourceNames.equal(emitter.sourceName(), name)) continue;
            var simulation = gpuSimulations[index];
            return new NiagaraGpuParticleRead.Source(emitter, simulation == null ? null : simulation.particles(),
                    gpuEmitters[index] != null && gpuEmitters[index].hasGpuContext());
        }
        return null;
    }

    public List<NiagaraGpuStageSchedule.Invocation> plannedGpuStages(int index, int step) {
        return gpuPlans.get(index).get(step);
    }

    public int gpuEmitterGroup(int index) { return schedule.group(index); }

    public GpuTargetStep activateGpuTargetStep(int step) {
        var snapshot = step < gpuInterfaceSteps.size() ? gpuInterfaceSteps.get(step) : null;
        if (snapshot == null) return new GpuTargetStep(null, null);
        if (gpuGaussianSplatWriters != null && !snapshot.gaussianSplatWriters().isEmpty()) {
            gpuPrefixMutated = true;
            gpuGaussianSplatWriters.applyClears(snapshot.gaussianSplatWriters());
        }
        var targets = snapshot.targets() == null ? null : Objects.requireNonNull(gpuRenderTargets);
        var dimensional = snapshot.dimensionalTargets() == null ? null : Objects.requireNonNull(gpuDimensionalTargets);
        if (targets != null) targets.beginStep(Objects.requireNonNull(snapshot.targets()));
        try {
            if (dimensional != null) dimensional.beginStep(Objects.requireNonNull(snapshot.dimensionalTargets()));
        } catch (RuntimeException | Error failure) {
            if (targets != null) targets.endStep();
            throw failure;
        }
        return new GpuTargetStep(targets, dimensional);
    }

    public record GpuTargetStep(@Nullable NiagaraGpuRenderTargets targets, @Nullable NiagaraGpuDimensionalRenderTargets dimensional)
            implements AutoCloseable {
        @Override public void close() {
            try { if (dimensional != null) dimensional.endStep(); }
            finally { if (targets != null) targets.endStep(); }
        }
    }

    public record GpuEmitterTick(NiagaraGpuSimulation.Tick simulation, Runnable publish,
                                 @Nullable NiagaraGpuInterfaces interfaces) implements AutoCloseable {
        public void finish() {
            simulation.finish();
            publish.run();
        }

        @Override public void close() {
            if (interfaces != null) interfaces.releaseTickSnapshots();
        }
    }

    public GpuEmitterTick beginGpuEmitterTick(int index, CommandEncoder encoder, NiagaraGpuPrograms programs,
            PreparedNiagaraShaders shaders, NiagaraGpuInterfaces interfaces, int eventSpawns,
            int allocationEstimate, boolean freeBufferEarly, boolean finalTick, boolean dataToRender) {
        NiagaraGpuEmitterState emitter = gpuEmitters[index];
        if (emitter == null) throw new IllegalArgumentException("Not a GPU Niagara emitter");
        var source = definition.emitters().get(index);
        var gpu = (NiagaraDefinition.GpuSimulation) source.simulation();
        NiagaraGpuSimulation simulation = gpuSimulation(index, programs, shaders, allocationEstimate);
        var ticks = gpuTicks.get(index);
        int cursor = gpuTickCursor[index]++;
        if (cursor >= ticks.size()) throw new IllegalStateException("Niagara GPU emitter has no prepared tick");
        if (!(ticks.get(cursor) instanceof NiagaraGpuEmitterState.Tick tick) || !tick.execute())
            throw new IllegalStateException("Niagara GPU emitter tick is not executable");
        var interfaceStep = cursor < gpuInterfaceSteps.size() ? gpuInterfaceSteps.get(cursor) : null;
        NiagaraGpuParticleBuffers particles = simulation.particles();
        gpuPrefixMutated = true;
        int limit = NiagaraGpuDataBuffer.maximumParticleCapacity(programs.runtime(), source.attributes());
        var settings = gpu.metadata().getAsJsonObject("emitter_settings");
        if (GpuAllocationMode.fromSource(settings) == GpuAllocationMode.FIXED_COUNT) {
            limit = Math.min(limit, gpuPreAllocationCount(settings));
        }
        int maximum = limit;
        try {
            interfaces.dataInterfaces(tick.external().dataInterfaces());
            var textureStep = interfaceStep == null ? null : interfaceStep.textures();
            interfaces.textureSnapshots(textureStep == null ? tick.textureSnapshots() : textureStep.snapshots());
            interfaces.hostSnapshots(interfaceStep == null ? null : interfaceStep.cameraModes(),
                    interfaceStep == null ? null : interfaceStep.emitterBounds(),
                    interfaceStep == null ? null : interfaceStep.mutable(),
                    interfaceStep == null ? null : interfaceStep.platformValues());
            interfaces.objectPropertySnapshots(interfaceStep == null ? Map.of() : interfaceStep.objectProperties());
            interfaces.propertyInterfaceSnapshots(interfaceStep == null ? Map.of() : interfaceStep.propertyInterfaces());
            interfaces.actorComponentSnapshots(interfaceStep == null ? Map.of() : interfaceStep.actorComponents());
            interfaces.staticMeshSnapshots(interfaceStep == null ? Map.of() : interfaceStep.staticMeshes());
            interfaces.skeletalMeshSnapshots(interfaceStep == null ? Map.of() : interfaceStep.skeletalMeshes());
            interfaces.particleExportSnapshots(interfaceStep == null ? Map.of() : interfaceStep.particleExports(),
                    interfaceStep == null ? null : interfaceStep.exportWorld());
            interfaces.dataChannelSnapshots(interfaceStep == null ? Map.of() : interfaceStep.channelReaders());
            interfaces.dataChannelWriterSnapshots(interfaceStep == null ? Map.of() : interfaceStep.channelWriters());
            interfaces.audioAnalysisSnapshots(interfaceStep == null ? Map.of() : interfaceStep.audioFrames());
            interfaces.asyncTraceSnapshots(interfaceStep == null ? Map.of() : interfaceStep.asyncTraces());
            interfaces.simCacheReaderSnapshots(interfaceStep == null ? Map.of() : interfaceStep.simCacheReaders());
            interfaces.sparseVolumeSnapshots(interfaceStep == null ? Map.of() : interfaceStep.sparseVolumes());
            interfaces.volumeCacheSnapshots(gpuVolumeCaches, interfaceStep == null ? Map.of() : interfaceStep.volumeCaches());
            interfaces.physicsAssetSnapshots(gpuPhysicsAssets, interfaceStep == null ? Map.of() : interfaceStep.physicsAssets());
            interfaces.rigidMeshSnapshots(gpuRigidMeshes, interfaceStep == null ? Map.of() : interfaceStep.rigidMeshes());
            interfaces.geometryCollectionSnapshots(gpuGeometryCollections,
                    interfaceStep == null ? Map.of() : interfaceStep.geometryCollections());
            interfaces.chaosDestructionSnapshots(gpuChaosDestruction,
                    interfaceStep == null ? Map.of() : interfaceStep.chaosDestruction());
            interfaces.hairStrandsSnapshots(gpuHairStrands,
                    interfaceStep == null ? Map.of() : interfaceStep.hairStrands());
            interfaces.sceneCaptureSnapshots(interfaceStep == null ? Map.of() : interfaceStep.sceneCaptures());
            interfaces.landscapeSnapshots(gpuLandscapes, interfaceStep == null ? Map.of() : interfaceStep.landscapes());
            interfaces.virtualTextureSnapshots(interfaceStep == null ? Map.of() : interfaceStep.virtualTextures());
            interfaces.memoryBufferSnapshots(gpuMemoryBuffers, interfaceStep == null ? Map.of() : interfaceStep.memoryBuffers());
            interfaces.neighborGridSnapshots(gpuNeighborGrids, interfaceStep == null ? Map.of() : interfaceStep.neighborGrids());
            interfaces.neighborQuerySnapshots(gpuNeighborQueries, interfaceStep == null ? Map.of() : interfaceStep.neighborQueries());
            interfaces.rasterizationGridSnapshots(gpuRasterizationGrids,
                    interfaceStep == null ? Map.of() : interfaceStep.rasterizationGrids());
            interfaces.velocityGridSnapshots(gpuVelocityGrids,
                    interfaceStep == null ? Map.of() : interfaceStep.velocityGrids());
            interfaces.grid3DCollectionSnapshots(gpuGrid3DCollections,
                    interfaceStep == null ? Map.of() : interfaceStep.grid3DCollections());
            interfaces.grid2DCollectionSnapshots(gpuGrid2DCollections,
                    interfaceStep == null ? Map.of() : interfaceStep.grid2DCollections());
            interfaces.dataTableSnapshots(interfaceStep == null ? Map.of() : interfaceStep.dataTables());
            interfaces.gaussianSplatReaderSnapshots(interfaceStep == null ? Map.of() : interfaceStep.gaussianSplatReaders());
            if (interfaceStep != null && !interfaceStep.gaussianSplatWriters().isEmpty() && gpuGaussianSplatWriters == null)
                gpuGaussianSplatWriters = new NiagaraGpuGaussianSplatWriters(programs.runtime());
            interfaces.gaussianSplatWriterSnapshots(gpuGaussianSplatWriters,
                    interfaceStep == null ? Map.of() : interfaceStep.gaussianSplatWriters());
            interfaces.intDistributionSnapshots(interfaceStep == null ? Map.of() : interfaceStep.intDistributions());
            interfaces.physicsFieldSnapshot(interfaceStep == null ? null : interfaceStep.physicsField());
            interfaces.intRenderTargetSnapshots(gpuIntRenderTargets, interfaceStep == null ? Map.of() : interfaceStep.intRenderTargets());
            interfaces.socketSnapshots(gpuSocketBuffers, interfaceStep == null ? Map.of() : interfaceStep.sockets());
            interfaces.splineSnapshots(gpuSplineBuffers, interfaceStep == null ? Map.of() : interfaceStep.splines());
            interfaces.viewQueries(interfaceStep == null ? NiagaraViewQueries.EMPTY : interfaceStep.viewQueries());
            interfaces.dynamicMeshSnapshots(gpuDynamicMeshes, interfaceStep == null ? Map.of() : interfaceStep.dynamicMeshes());
            interfaces.consoleVariableSnapshot(interfaceStep == null ? frame.platform : interfaceStep.consoleEnvironment(),
                    interfaceStep == null ? consoleInitialization : interfaceStep.consoleInitialization());
            if (eventSpawns < 0) throw new IllegalArgumentException("Negative Niagara GPU event spawn count");
            int eventCount = (int)Math.min(maximum, (long)eventSpawns + tick.eventSpawns());
            int requestedAllocation = (int)Math.min(maximum, Math.max(allocationEstimate, tick.allocationRequest() + eventSpawns));
            var submission = simulation.beginTick(encoder, plannedGpuStages(index, cursor), interfaces, tick.engine(),
                    tick.external()::value, tick.spawn(), eventCount, maximum, requestedAllocation,
                    freeBufferEarly, tick.reset(), finalTick, dataToRender);
            return new GpuEmitterTick(submission, () -> {
                int allocation = Math.min(maximum, particles.estimate().allocationHighWater());
                if (emitter.recordAllocation(allocation)) {
                    Identifier id = Identifier.fromNamespaceAndPath(gpu.hlslSource().getNamespace(), gpu.shaderPipeline());
                    programs.recordAllocation(id, emitter.allocationId(), allocation);
                }
                gpuRenderOrigins[index] = new Vec3(tick.originX(), tick.originY(), tick.originZ());
                gpuParticleCounts[index] = particles.estimate().current();
                this.interfaces.publishGpuParticleCounts(source.sourceName(), gpuParticleCounts[index], particles.currentSpawnedInstances());
            }, interfaces);
        } catch (RuntimeException | Error failure) {
            try { interfaces.releaseTickSnapshots(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public NiagaraGpuParticleBuffers gpuParticles(int index, NiagaraGpuPrograms programs,
                                                 PreparedNiagaraShaders shaders, int allocationEstimate) {
        return gpuSimulation(index, programs, shaders, allocationEstimate).particles();
    }

    public NiagaraGpuParticleBuffers cachedGpuParticles(int index, CommandEncoder encoder, NiagaraGpuPrograms programs) {
        var cachedFrames = usingRetainedCacheFrame() ? resumeGpuFrames : cacheGpuFrames;
        var gpuFrame = Objects.requireNonNull(cachedFrames)[index];
        return Objects.requireNonNull(gpuFrame, "Missing Niagara cache GPU emitter frame").particles(encoder, programs);
    }

    private NiagaraGpuSimulation gpuSimulation(int index, NiagaraGpuPrograms programs,
                                               PreparedNiagaraShaders shaders, int allocationEstimate) {
        if (gpuEmitters[index] == null) throw new IllegalArgumentException("Not a GPU Niagara emitter");
        NiagaraGpuSimulation simulation = gpuSimulations[index];
        if (simulation != null) return simulation;
        var source = definition.emitters().get(index);
        var gpu = (NiagaraDefinition.GpuSimulation)source.simulation();
        if (gpuCounters == null) gpuCounters = new NiagaraGpuCountArena(definition.emitters(), programs);
        Identifier id = Identifier.fromNamespaceAndPath(gpu.hlslSource().getNamespace(), gpu.shaderPipeline());
        simulation = new NiagaraGpuSimulation(id, programs, shaders, source.attributes(),
                source.requiresPersistentIdStorage(), gpuCounters, index);
        gpuSimulations[index] = simulation;
        gpuEmitters[index].initializeAllocation(allocationEstimate);
        return simulation;
    }

    public void publishEmitterBounds() {
        if (!execute || boundsPublished) return;
        for (var source : definition.emitters()) {
            if (source.simulation() instanceof NiagaraDefinition.DisabledSimulation disabled && !disabled.hasEmitter()) continue;
            Objects.requireNonNull(interfaces.emitterBounds(source.sourceName())).publishRenderSnapshot();
        }
        boundsPublished = true;
    }

    /** Matches UE 5.9 FNiagaraSystemRenderData::GenerateSetDynamicDataCommands emitter-then-renderer MID write order. */
    public void publishDynamicMaterialBindings(EffectDefinition effect, EffectHandle.Input frameInput) {
        if (!initialized || complete || dynamicBindingsPublished) return;
        var sourceEmitters = definition.emitters();
        for (int emitterIndex = 0; emitterIndex < sourceEmitters.size(); emitterIndex++) {
            var emitter = sourceEmitters.get(emitterIndex);
            if (emitter.simulation() instanceof NiagaraDefinition.DisabledSimulation) continue;
            if (isGpuEmitter(emitterIndex)) {
                if (gpuEmitterComplete(emitterIndex)) continue;
            } else if (emitters[emitterIndex] == null || emitters[emitterIndex].complete()) {
                continue;
            }
            var store = rendererParameterStores[emitterIndex].renderer();
            var engine = materialEngineValues[emitterIndex];
            for (var renderer : emitter.renderers()) {
                var renderDef = effect.niagaraRenderer(renderer.name());
                if (!renderDef.renderer().usesMaterial()) continue;
                Integer enabled = NiagaraRendererParameters.integer(renderer.enabledBinding(), true, current,
                        variables, frameInput, store, emitter.rendererBindings());
                if (enabled != null && enabled == 0) continue;
                var authored = Objects.requireNonNull(effect.materials().get(renderDef.material()),
                        "Missing Niagara renderer material " + renderDef.material());
                if (renderDef.renderer() != EmitterDefinition.Renderer.MESH && !authored.instanceParameters().hasRendererBindings())
                    continue;
                publishRendererDynamicMaterialBindings(effect, renderDef, renderer, authored, frameInput, engine);
            }
        }
        dynamicBindingsPublished = true;
    }

    private void publishRendererDynamicMaterialBindings(EffectDefinition effect, EmitterDefinition renderDef,
                                                        NiagaraDefinition.Renderer renderer, MaterialDefinition authored,
                                                        EffectHandle.Input frameInput,
                                                        NiagaraMaterialInstances.EngineValues engine) {
        var applied = new IdentityHashMap<DynamicMaterialValues, Boolean>();
        switch (renderDef.renderer()) {
            case SPRITE, RIBBON -> {
                MaterialDefinition target = authored;
                var binding = renderer.surfaceMaterialBinding();
                if (binding != null) {
                    var selected = RendererParameter.material(
                            RendererParameter.named(frameInput.rendererParameters(), binding.parameter()),
                            binding.variant());
                    if (selected != null) target = selected;
                }
                applyExternalDynamicMaterial(authored, target, frameInput, engine, applied);
            }
            case DECAL -> {
                var settings = Objects.requireNonNull(renderDef.decalSettings());
                var target = DecalMaterialSelection.resolve(settings, effect, authored, frameInput);
                applyExternalDynamicMaterial(authored, target, frameInput, engine, applied);
            }
            case VOLUME -> {
                var settings = Objects.requireNonNull(renderDef.volumeSettings());
                MaterialDefinition target = authored;
                if (settings.materialBinding() != null) {
                    var selected = RendererParameter.material(
                            RendererParameter.named(frameInput.rendererParameters(), settings.materialBinding()),
                            settings.materialVariant());
                    if (selected != null) target = selected;
                }
                applyExternalDynamicMaterial(authored, target, frameInput, engine, applied);
            }
            case MESH -> {
                var settings = Objects.requireNonNull(renderDef.meshSettings());
                for (var configured : MeshDrawSelection.entries(settings, frameInput)) {
                    var mesh = MeshDrawSelection.entry(configured, frameInput, settings);
                    var asset = mesh.asset();
                    if (asset == null) continue;
                    for (var lod : asset.lods()) {
                        for (var section : lod.sections()) {
                            var target = MeshDrawSelection.material(effect, renderDef, asset, mesh, section, frameInput);
                            var bindingSource = target.instanceParameters().hasRendererBindings() ? target : authored;
                            if (bindingSource.instanceParameters().hasRendererBindings())
                                applyExternalDynamicMaterial(bindingSource, target, frameInput, engine, applied);
                        }
                    }
                }
            }
            default -> { }
        }
    }

    private void applyExternalDynamicMaterial(MaterialDefinition authored, MaterialDefinition target,
                                              EffectHandle.Input frameInput,
                                              NiagaraMaterialInstances.EngineValues engine,
                                              IdentityHashMap<DynamicMaterialValues, Boolean> applied) {
        var dynamic = target.instanceParameters().dynamicValues();
        if (dynamic == null) dynamic = engine.dynamicMaterial(target);
        if (dynamic == null || applied.putIfAbsent(dynamic, Boolean.TRUE) != null) return;
        NiagaraMaterialInstances.applyDynamicBindings(authored, dynamic, current, variables, frameInput,
                frame.originX(), frame.originY(), frame.originZ(), engine, null);
    }

    public void finish() {
        finish(Minecraft.getInstance().level);
    }
    public void finish(@Nullable ClientLevel level) {
        finish(level, null);
    }
    public void finish(@Nullable ClientLevel level,
                       @Nullable NiagaraGpuPrograms programs) {
        finish(level, programs, false);
    }

    public void finishForRender(@Nullable ClientLevel level) {
        finish(level, null, true);
    }

    private void finish(@Nullable ClientLevel level, @Nullable NiagaraGpuPrograms programs, boolean hasRenderReaders) {
        if (!execute) return;
        if (cachePlayer != null) {
            if (complete) {
                if (!hasRenderReaders) releaseCompletedState();
            }
            else {
                prepareCachedRenderTargets(programs);
                publishEmitterBounds();
                if (level != null) for (int index = 0; index < emitters.length; index++) {
                    if (emitters[index] != null && cachePlayer.hasEmitter(index))
                        emitters[index].postSystemTick(level, frame, input, current, variables, realTime);
                }
            }
            execute = false;
            return;
        }
        boolean completed = finished(false);
        if (!completed) {
            interfaces.physicsField().postSimulate(worldPhysicsField);
            interfaces.postSimulate(level, firstRigidMeshStep, frame.delta);
            interfaces.debugLines().drawPersistent(persistentDebugShapes, instanceParameterStores.override(), input.pose());
            interfaces.debugLines().publish();
        }
        publishEmitterBounds();
        for (var emitter : statelessGpu) if (emitter != null) emitter.renderFrame();
        if (completed) {
            complete = true;
            if (!hasRenderReaders) releaseCompletedState();
        } else {
            prepareRenderTargetResources(programs);
            latestGaussianSplatWriters = interfaces.publishGaussianSplatWriters();
            captureGpuInterfaceStep(level, programs);
            if (level != null) for (var emitter : emitters)
                if (emitter != null) emitter.postSystemTick(level, frame, input, current, variables, realTime);
        }
        frame.finish();
        execute = false;
    }

    private void clearGpuEmitterData(int index) {
        if (gpuSimulations[index] != null) gpuSimulations[index].clearForExecutionState();
        gpuParticleCounts[index] = 0;
        interfaces.publishGpuParticleCounts(definition.emitters().get(index).sourceName(), 0, 0);
    }

    public void retireRenderedState() {
        if (complete) releaseCompletedState();
    }

    private void releaseCompletedState() {
        if (componentCompletionNotified) return;
        if (worldSimulation != null) worldSimulation.detach(this);
        if (frame.platform.worldUsesTickPasses()) instanceParameterStores.unbindOverride();
        for (var emitter : emitters) if (emitter != null) emitter.destroyComponentRenderState();
        releaseGpuResources();
        interfaces.destroyInstanceResources();
        componentCompletionNotified = true;
    }
    public int emitterCount() { return emitters.length; }
    public void finishRenderFrame(boolean paused) {
        for (var emitter : emitters) if (emitter != null) emitter.finishComponentFrame(paused);
    }
    public void collectPointLights(EffectDefinition effect, List<ScenePointLight> output) {
        for (var emitter : emitters) if (emitter != null) emitter.collectPointLights(effect, output);
    }
    public void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output) {
        for (var emitter : emitters) if (emitter != null) emitter.collectDirectionalLights(effect, output);
    }
    public void collectDecals(EffectDefinition effect, List<ParticleDecal> output) {
        for (var emitter : emitters) if (emitter != null) emitter.collectDecals(effect, output);
    }
    public void collectComponentMeshes(EffectDefinition effect, @Nullable UUID viewActor,
                                       List<StaticMeshComponentDraw> output) {
        for (var emitter : emitters) if (emitter != null) emitter.collectComponentMeshes(effect, viewActor, output);
    }
    private void updateComponentAttachments(EffectHandle.Pose pose) {
        for (var emitter : emitters) if (emitter != null) emitter.updateComponentAttachments(pose);
    }
    public boolean finished(boolean stopping) {
        if (complete) return true;
        if (cachePlayer != null) return stopping;
        boolean allComplete = true, allDone = true;
        for (int i = 0; i < emitters.length; i++) {
            allComplete &= emitters[i] != null ? emitters[i].complete()
                    : statelessGpu[i] != null ? statelessGpu[i].complete() : gpuEmitters[i].complete();
            allDone &= emitters[i] != null ? emitters[i].done()
                    : statelessGpu[i] != null ? statelessGpu[i].complete() : gpuEmitters[i].done(gpuParticleCounts[i]);
        }
        return allComplete || stopping && allDone;
    }

    private void bind(List<NiagaraDefinition.Binding> bindings, VectorDataSet target) {
        for (var binding : bindings) {
            byte[] bytes = binding.emitter() < 0 ? frame.current : runtimes[binding.emitter()];
            int offset = binding.runtimeOffset();
            int word = (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8
                    | (bytes[offset + 2] & 255) << 16 | bytes[offset + 3] << 24;
            target.component(binding.type(), binding.component())[0] = word;
        }
    }

    private void applyParameters(Map<String, EffectHandle.ParameterValue> parameters, VectorDataSet target,
                                 List<NiagaraDefinition.Variable> layout) {
        for (var entry : parameters.entrySet()) {
            NiagaraDefinition.Variable variable = layout.stream().filter(item -> item.name().equals(entry.getKey()))
                    .findFirst().orElse(null);
            if (variable == null) continue;
            EffectHandle.ParameterValue value = entry.getValue();
            int components = value.type().components();
            long[] words = value.words();
            if (!NiagaraParameterValues.matches(variable.sourceType(), value.type().storage(), components))
                throw new IllegalArgumentException("Niagara parameter type does not match " + variable.name());
            if (variable.floatCount() == components && variable.intCount() == 0 && variable.halfCount() == 0
                    && (value.type().storage() == 0 || value.type().storage() == 4)) {
                for (int i = 0; i < components; i++) target.floating(variable.floatStart() + i, 0,
                        value.type().storage() == 4
                                ? NiagaraPositionParameters.component(words[i],
                                        i == 0 ? frame.originX() : i == 1 ? frame.originY() : frame.originZ())
                                : Float.intBitsToFloat((int)words[i]));
            } else if (variable.intCount() == components && variable.floatCount() == 0 && variable.halfCount() == 0
                    && (value.type().storage() == 1 || value.type().storage() == 2 || value.type().storage() == 5)) {
                for (int i = 0; i < components; i++) target.integer(variable.intStart() + i, 0,
                        value.type().storage() == 2 ? words[i] == 0 ? 0 : -1 : (int)words[i]);
            } else if (variable.halfCount() == components && variable.floatCount() == 0 && variable.intCount() == 0
                    && value.type().storage() == 3) {
                for (int i = 0; i < components; i++) target.half(variable.halfStart() + i, 0,
                        Float.float16ToFloat((short)words[i]));
            } else {
                throw new IllegalArgumentException("Niagara parameter type does not match " + variable.name());
            }
        }
    }

    private void swap() { VectorDataSet swap = current; current = destination; destination = swap; }

    static VectorDataSet dataset(List<NiagaraDefinition.Variable> layout) {
        int floats = 0, integers = 0, halves = 0;
        for (var variable : layout) {
            floats = Math.max(floats, variable.floatStart() + variable.floatCount());
            integers = Math.max(integers, variable.intStart() + variable.intCount());
            halves = Math.max(halves, variable.halfStart() + variable.halfCount());
        }
        return new VectorDataSet(floats, integers, halves);
    }

    private static void closeCacheFrames(NiagaraGpuCacheFrame @Nullable [] frames) {
        if (frames == null) return;
        for (var frame : frames) if (frame != null) frame.close();
    }

    private void discardPendingCacheResume() {
        closeCacheFrames(resumeGpuFrames);
        resumeGpuFrames = null;
        resumeGpuVisible = null;
        resumeCacheInterfaces = null;
        lastSampledCache = null;
    }

    private void releaseGpuResources() {
        gpuPrefixMutated = false;
        closeCacheFrames(cacheGpuFrames);
        discardPendingCacheResume();
        cacheBounds = null;
        for (var emitter : statelessGpu) if (emitter != null) emitter.releaseResources();
        for (int i = 0; i < gpuSimulations.length; i++) {
            if (gpuSimulations[i] != null) {
                gpuSimulations[i].close();
                gpuSimulations[i] = null;
            }
            gpuParticleCounts[i] = 0;
            gpuRenderOrigins[i] = null;
            gpuTicks.get(i).clear(); gpuPlans.get(i).clear(); gpuTickCursor[i] = 0;
        }
        releaseGpuInterfaceResources();
        if (gpuCounters != null) { gpuCounters.close(); gpuCounters = null; }
    }

    private void releaseGpuInterfaceResources() {
        releaseGpuInterfaceSteps();
        gpuPrefixMutated = false;
        if (gpuDispatcher != null) {
            gpuDispatcher.reset();
            gpuDispatcher = null;
        }
        if (gpuArrays != null) { gpuArrays.close(); gpuArrays = null; }
        if (gpuMemoryBuffers != null) { gpuMemoryBuffers.close(); gpuMemoryBuffers = null; }
        if (gpuDynamicMeshes != null) { gpuDynamicMeshes.close(); gpuDynamicMeshes = null; }
        if (gpuGaussianSplatWriters != null) { gpuGaussianSplatWriters.close(); gpuGaussianSplatWriters = null; }
        latestGaussianSplatWriters = Map.of();
        if (gpuNeighborGrids != null) { gpuNeighborGrids.close(); gpuNeighborGrids = null; }
        if (gpuNeighborQueries != null) { gpuNeighborQueries.close(); gpuNeighborQueries = null; }
        if (gpuRasterizationGrids != null) { gpuRasterizationGrids.close(); gpuRasterizationGrids = null; }
        if (gpuVelocityGrids != null) { gpuVelocityGrids.close(); gpuVelocityGrids = null; }
        if (gpuGrid3DCollections != null) { gpuGrid3DCollections.close(); gpuGrid3DCollections = null; }
        if (gpuGrid2DCollections != null) { gpuGrid2DCollections.close(); gpuGrid2DCollections = null; }
        if (gpuIntRenderTargets != null) { gpuIntRenderTargets.close(); gpuIntRenderTargets = null; }
        if (gpuSocketBuffers != null) { gpuSocketBuffers.close(); gpuSocketBuffers = null; }
        if (gpuVolumeCaches != null) { gpuVolumeCaches.close(); gpuVolumeCaches = null; }
        if (gpuPhysicsAssets != null) { gpuPhysicsAssets.close(); gpuPhysicsAssets = null; }
        if (gpuRigidMeshes != null) { gpuRigidMeshes.close(); gpuRigidMeshes = null; }
        if (gpuGeometryCollections != null) { gpuGeometryCollections.close(); gpuGeometryCollections = null; }
        if (gpuChaosDestruction != null) { gpuChaosDestruction.close(); gpuChaosDestruction = null; }
        if (gpuHairStrands != null) { gpuHairStrands.close(); gpuHairStrands = null; }
        if (gpuLandscapes != null) { gpuLandscapes.close(); gpuLandscapes = null; }
        if (gpuSplineBuffers != null) { gpuSplineBuffers.close(); gpuSplineBuffers = null; }
        if (gpuInterfaceCounters != null) { gpuInterfaceCounters.close(); gpuInterfaceCounters = null; }
        if (gpuDataChannelReaders != null) { gpuDataChannelReaders.close(); gpuDataChannelReaders = null; }
        if (gpuDataChannelWriters != null) { gpuDataChannelWriters.close(); gpuDataChannelWriters = null; }
        if (gpuCurves != null) { gpuCurves.close(); gpuCurves = null; }
        if (gpuRendererInformation != null) { gpuRendererInformation.close(); gpuRendererInformation = null; }
        if (gpuRenderTargets != null) { gpuRenderTargets.close(); gpuRenderTargets = null; }
        if (gpuDimensionalTargets != null) { gpuDimensionalTargets.close(); gpuDimensionalTargets = null; }
        if (cpuTargetRuntime != null) { cpuTargetRuntime.close(); cpuTargetRuntime = null; }
    }

    @Override public void close() {
        try (var teardown = new EffectTeardown()) {
            if (cacheRecorder != null) teardown.run(cacheRecorder::finish);
            if (worldSimulation != null) teardown.run(() -> worldSimulation.detach(this));
            for (var emitter : emitters) if (emitter != null) teardown.run(emitter::closeComponents);
            for (var emitter : emitters) if (emitter instanceof NiagaraEmitter stateful) teardown.run(stateful::closeParameters);
            for (var store : rendererParameterStores) teardown.run(store::close);
            teardown.run(spawnParameterStore::close);
            teardown.run(updateParameterStore::close);
            teardown.run(() -> interfaces.parameterStore(null));
            teardown.run(instanceParameterStores::close);
            for (var emitter : gpuEmitters) if (emitter != null) teardown.run(emitter::closeParameters);
            teardown.run(parameterSources::close);
            teardown.run(parameterObjects::clear);
            teardown.run(this::releaseGpuResources);
            teardown.run(interfaces::destroyInstanceResources);
        }
    }
}
