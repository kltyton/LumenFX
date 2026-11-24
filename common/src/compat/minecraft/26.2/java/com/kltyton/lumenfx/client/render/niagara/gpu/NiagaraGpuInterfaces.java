package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraConsoleVariable.Initialization;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborGridBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuRasterizationGridBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuRasterizationGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraRasterizationGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuVelocityGridBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuVelocityGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraVelocityGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid3DCollections;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid3DCollectionBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid2DCollections;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid2DCollectionBindings;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollections;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollections.Publication;

import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReaderBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.NiagaraGpuSparseVolumeBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.NiagaraGpuVolumeCacheBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.NiagaraGpuVolumeCaches;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuPhysicsBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuPhysicsBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuRigidMeshBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuPhysicsFieldBindings;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraRigidMeshCollision;
import com.kltyton.lumenfx.client.render.niagara.gpu.geometry.NiagaraGpuGeometryBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.geometry.NiagaraGpuGeometryBuffers;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.chaos.NiagaraGpuChaosBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.chaos.NiagaraGpuChaosBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuHairBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuHairBuffers;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.HairStrandsFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraVolumeCache;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraSparseVolumeTexture;
import com.kltyton.lumenfx.client.render.niagara.gpu.memory.NiagaraGpuMemoryBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.table.NiagaraGpuDataTableBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuGaussianSplatReaderBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuGaussianSplatWriterBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuGaussianSplatWriters;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;
import com.kltyton.lumenfx.client.render.niagara.gpu.distribution.NiagaraGpuIntDistributionBindings;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.table.NiagaraDataTable;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuIntRenderTargetBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuIntRenderTargets;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuIntRenderTargets.IntTargetPublication;
import com.kltyton.lumenfx.client.render.niagara.gpu.socket.NiagaraGpuSocketBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.socket.NiagaraGpuSocketBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.spline.NiagaraGpuSplineBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.spline.NiagaraGpuSplineBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.view.NiagaraGpuViewQueries;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraViewQueries;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraSceneCapture;
import com.kltyton.lumenfx.client.render.niagara.gpu.view.NiagaraGpuSceneCaptureBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuLandscapeBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuVirtualTextureBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuTerrainImages.CollisionGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraLandscape;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraVirtualTextureState;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborQueries;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborQueryBindings;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborQuery;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSpline.GpuFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader;
import com.kltyton.lumenfx.client.render.niagara.gpu.memory.NiagaraGpuMemoryBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraGpuDynamicMeshBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraGpuDynamicMeshes;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer.Snapshot;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraSimCacheReader.Bound;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.AsyncTraceSnapshot;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.NiagaraGpuStaticMeshBindings;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.NiagaraGpuSkeletalMeshBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.export.NiagaraGpuParticleExportBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelReaders;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelReadBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWriteBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWriters;
import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWorlds;
import com.kltyton.lumenfx.client.render.niagara.gpu.audio.NiagaraGpuAudioAnalysisBindings;
import com.kltyton.lumenfx.client.audio.analysis.NiagaraAudioBusAnalysis;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshReader.GpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraPlatformSetProxies;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.kltyton.lumenfx.effect.noise.NiagaraCurlNoise;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraEmitterBounds;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface;
import com.kltyton.lumenfx.client.render.niagara.gpu.property.NiagaraGpuPropertyInterfaces;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraActorComponent;
import com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuRenderTargets;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuDimensionalRenderTargets;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.VectorFieldBinding;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.EmitterBinding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Render-thread bindings for an emitter's Niagara data interfaces. */
public final class NiagaraGpuInterfaces implements NiagaraGpuSimulation.Interfaces, AutoCloseable {
    private static final Set<String> STATIC_INTERFACES = Set.of(
            "NiagaraDataInterfaceCurve", "NiagaraDataInterfaceColorCurve", "NiagaraDataInterfaceVector2DCurve",
            "NiagaraDataInterfaceVectorCurve", "NiagaraDataInterfaceVector4Curve", "NiagaraDataInterfaceSpriteRendererInfo",
            "NiagaraDataInterfaceVectorField", "NiagaraDataInterfaceMeshRendererInfo");
    private final NiagaraGpuPrograms programs;
    private final PreparedNiagaraShaders shaders;
    private final NiagaraGpuVectorFields vectorFields;
    private final @Nullable NiagaraGpuVectorFieldBindings vectorFieldBindings;
    private final @Nullable NiagaraGpuCurveBindings curveBindings;
    private final NiagaraGpuMeshInfo meshInformation;
    private final NiagaraGpuArrayRegistry arrays;
    private final NiagaraGpuCounterRegistry counters;
    private final NiagaraGpuEmitterProperties emitterProperties;
    private final NiagaraGpuObjectPropertyBindings objectProperties;
    private final NiagaraGpuPropertyInterfaces propertyInterfaces;
    private final NiagaraGpuConsoleVariables consoleVariables;
    private final NiagaraGpuAsyncTraceBindings asyncTraces;
    private final NiagaraGpuStaticMeshBindings staticMeshes;
    private @Nullable NiagaraGpuDebugLines debugLines;
    private final NiagaraGpuSkeletalMeshBindings skeletalMeshes;
    private final NiagaraGpuParticleExportBindings particleExports;
    private final NiagaraGpuDataChannelReadBindings dataChannelReads;
    private final NiagaraGpuDataChannelWriteBindings dataChannelWrites;
    private final List<Consumer<NiagaraGpuDataChannelWorlds.StageAccess>> dataChannelStages;
    private final NiagaraGpuAudioAnalysisBindings audioAnalysis;
    private final NiagaraGpuCacheReaderBindings simCacheReaders;
    private final NiagaraGpuSparseVolumeBindings sparseVolumes;
    private final NiagaraGpuVolumeCacheBindings volumeCaches;
    private final NiagaraGpuPhysicsBindings physicsAssets;
    private final NiagaraGpuRigidMeshBindings rigidMeshes;
    private final NiagaraGpuGeometryBindings geometryCollections;
    private final NiagaraGpuChaosBindings chaosDestruction;
    private final NiagaraGpuHairBindings hairStrands;
    private final NiagaraGpuPhysicsFieldBindings physicsField;
    private final NiagaraGpuSceneCaptureBindings sceneCaptures;
    private final NiagaraGpuLandscapeBindings landscapes;
    private final NiagaraGpuVirtualTextureBindings virtualTextures;
    private final NiagaraGpuMemoryBindings memoryBuffers;
    private final NiagaraGpuDataTableBindings dataTables;
    private final NiagaraGpuGaussianSplatReaderBindings gaussianSplatReaders;
    private final NiagaraGpuGaussianSplatWriterBindings gaussianSplatWriters;
    private Map<String, NiagaraDataTable> dataTableSnapshots = Map.of();
    private Map<String, NiagaraGaussianSplatReader> gaussianSplatReaderSnapshots = Map.of();
    private Map<String, NiagaraGaussianSplatWriter.Snapshot> gaussianSplatWriterSnapshots = Map.of();
    private @Nullable NiagaraGpuGaussianSplatWriters gaussianSplatWriterRegistry;
    private final NiagaraGpuIntDistributionBindings intDistributions;
    private Map<String, NiagaraIntDistribution> intDistributionSnapshots = Map.of();
    private final NiagaraGpuIntRenderTargetBindings intRenderTargets;
    private @Nullable NiagaraGpuIntRenderTargets intRenderTargetRegistry;
    private Map<String, IntTargetPublication> intRenderTargetSnapshots = Map.of();
    private final NiagaraGpuSocketBindings socketReaders;
    private @Nullable NiagaraGpuSocketBuffers socketBuffers;
    private Map<String, NiagaraSocketReader.Snapshot> socketSnapshots = Map.of();
    private final NiagaraGpuSplineBindings splines;
    private @Nullable NiagaraGpuSplineBuffers splineBuffers;
    private Map<String, GpuFrame> splineSnapshots = Map.of();
    private final NiagaraGpuViewQueries viewQueries;
    private final NiagaraGpuNeighborQueryBindings neighborQueries;
    private @Nullable NiagaraGpuNeighborQueries neighborQueryRegistry;
    private Map<String, NiagaraNeighborQuery.Snapshot> neighborQuerySnapshots = Map.of();
    private final NiagaraGpuNeighborGridBindings neighborGrids;
    private final NiagaraGpuRasterizationGridBindings rasterizationGrids;
    private final NiagaraGpuVelocityGridBindings velocityGrids;
    private final NiagaraGpuGrid3DCollectionBindings grid3DCollections;
    private final NiagaraGpuGrid2DCollectionBindings grid2DCollections;
    private final NiagaraGpuDynamicMeshBindings dynamicMeshes;
    private @Nullable NiagaraGpuDynamicMeshes dynamicMeshRegistry;
    private Map<String, NiagaraDynamicMeshFrame> dynamicMeshSnapshots = Map.of();
    private @Nullable NiagaraGpuMemoryBuffers memoryRegistry;
    private Map<String, Snapshot> memorySnapshots = Map.of();
    private @Nullable NiagaraGpuNeighborGrids neighborGridRegistry;
    private Map<String, NiagaraNeighborGrid.Snapshot> neighborGridSnapshots = Map.of();
    private @Nullable NiagaraGpuRasterizationGrids rasterizationGridRegistry;
    private Map<String, NiagaraRasterizationGrid.Snapshot> rasterizationGridSnapshots = Map.of();
    private @Nullable NiagaraGpuVelocityGrids velocityGridRegistry;
    private Map<String, NiagaraVelocityGrid.Snapshot> velocityGridSnapshots = Map.of();
    private @Nullable NiagaraGpuGrid3DCollections grid3DCollectionRegistry;
    private Map<String, Publication> grid3DCollectionSnapshots = Map.of();
    private @Nullable NiagaraGpuGrid2DCollections grid2DCollectionRegistry;
    private Map<String, NiagaraGrid2DCollections.Publication> grid2DCollectionSnapshots = Map.of();
    private Map<String, Bound> simCacheReaderSnapshots = Map.of();
    private Map<String, NiagaraSparseVolumeTexture.Snapshot> sparseVolumeSnapshots = Map.of();
    private @Nullable NiagaraGpuVolumeCaches volumeCacheRegistry;
    private Map<String, NiagaraVolumeCache.Snapshot> volumeCacheSnapshots = Map.of();
    private @Nullable NiagaraGpuPhysicsBuffers physicsRegistry;
    private Map<String, NiagaraPhysicsAssetReader.Frame> physicsSnapshots = Map.of();
    private NiagaraGpuRigidMeshBindings.@Nullable Buffers rigidMeshRegistry;
    private Map<String, NiagaraRigidMeshCollision.Frame> rigidMeshSnapshots = Map.of();
    private @Nullable NiagaraGpuGeometryBuffers geometryRegistry;
    private Map<String, GeometryCollectionFrame> geometrySnapshots = Map.of();
    private @Nullable NiagaraGpuChaosBuffers chaosRegistry;
    private Map<String, ChaosDestructionFrame> chaosSnapshots = Map.of();
    private @Nullable NiagaraGpuHairBuffers hairRegistry;
    private Map<String, HairStrandsFrame> hairSnapshots = Map.of();
    private Map<String, NiagaraSceneCapture.Publication> sceneCaptureSnapshots = Map.of();
    private Map<String, NiagaraLandscape.Frame> landscapeSnapshots = Map.of();
    private @Nullable CollisionGrids landscapeGrids;
    private Map<String, NiagaraVirtualTextureState.Frame> virtualTextureSnapshots = Map.of();
    private Map<String, NiagaraAudioBusAnalysis.Frame> audioAnalysisSnapshots = Map.of();
    private @Nullable NiagaraGpuDataChannelReaders dataChannelReaders;
    private @Nullable NiagaraGpuDataChannelWriters dataChannelWriters;
    private Map<String, NiagaraDataChannelGpuSnapshot> dataChannelSnapshots = Map.of();
    private Map<String, NiagaraDataChannelState.GpuWriter> dataChannelWriterSnapshots = Map.of();
    private Map<String, NiagaraParticleExportState.Snapshot> particleExportSnapshots = Map.of();
    private @Nullable ClientLevel particleExportWorld;
    private Map<String, GpuSnapshot> staticMeshSnapshots = Map.of();
    private Map<String, NiagaraSkeletalGpuSnapshot> skeletalMeshSnapshots = Map.of();
    private @Nullable NiagaraPlatformEnvironment consoleTickEnvironment;
    private @Nullable Initialization consoleInitialization;
    private final NiagaraGpuActorComponents actorComponents;
    private Map<String, NiagaraObjectPropertyReader.Snapshot> objectPropertySnapshots = Map.of();
    private Map<String, NiagaraPropertyInterface.Frame> propertyInterfaceSnapshots = Map.of();
    private Map<String, NiagaraActorComponent.Snapshot> actorComponentSnapshots = Map.of();
    private final NiagaraGpuTextureBindings textureBindings;
    private final NiagaraPlatformSetProxies platformSets;
    private final Map<String, String> platformSetSymbols;
    private final Map<String, String> platformOriginals;
    private final com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuRenderTargets renderTargets;
    private final boolean ownsRenderTargets;
    private final List<JsonObject> targetBindings;
    private final Map<String, String> targetSymbols, targetNames;
    private final Map<String, String> targetOriginals;
    private final Set<String> occlusionSymbols;
    private final Set<String> gBufferSymbols;
    private final Set<String> declaredProxyIterations;
    private final boolean hasCollisionInterface;
    private record DimensionalTargetBinding(String key, String original) { }
    private final Map<String, DimensionalTargetBinding> dimensionalTargetSymbols, dimensionalTargetNames;
    private @Nullable NiagaraGpuDimensionalRenderTargets dimensionalTargets;
    private Map<String, String> interfaceSelections = Map.of();
    private Map<String, String> proxySourceNames = Map.of();
    private CommandEncoder tickEncoder;
    private final List<JsonObject> arrayBindings;
    private final Map<String, String> arraySymbols;
    private final Map<String, String> arrayNames;
    private final Map<String, String> arrayOriginals;
    private record CameraBinding(String key, String original) { }
    private final Map<String, CameraBinding> cameraSymbols;
    private @Nullable ToIntFunction<String> cameraObjects;
    private @Nullable Map<String, Integer> cameraModes;
    private @Nullable Map<String, Integer> platformValues;
    private @Nullable Map<String, NiagaraEmitterBounds.Snapshot> emitterBounds;
    private NiagaraInterfaces.@Nullable MutableGpuInputs mutableInputs;
    private @Nullable NiagaraGpuRendererInfoBindings rendererInformation;
    private final Map<String, byte[]> curlParameters;
    private final Map<String, String> curlSymbols;
    private final @Nullable Function<String, byte[]> curlObjects;
    private boolean selfParticleRead;
    private record ParticleReaderBinding(NiagaraGpuParticleRead reader, String key, String original, String target) { }
    private final List<ParticleReaderBinding> particleReaderBindings;
    private @Nullable Function<String, @Nullable EmitterBinding> emitterObjects;
    private final NiagaraGpuReaderSnapshot readerSnapshot;
    private final List<NiagaraGpuParticleRead> particleReaders;
    private final Map<String, ParticleReaderBinding> particleReaderNames;
    private final List<JsonObject> counterBindings;
    private final Map<String, String> counterSymbols, counterNames;
    private final Map<String, String> counterOriginals;
    private @Nullable NiagaraGpuView view;
    private @Nullable GpuTextureView sceneDepth;
    private boolean outsideScene;

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                java.util.function.Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                java.util.function.Function<String, com.kltyton.lumenfx.client.simulation.niagara.NiagaraEmitterBounds.Snapshot> boundsSources) {
        this(emitter, programs, shaders, program, arrays, counters, emitterName, defaultTextures(), particleSources, boundsSources);
    }

    private static com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings defaultTextures() {
        var result = new com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings();
        result.beginFrame(Map.of());
        return result;
    }

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings textures,
                                java.util.function.Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                java.util.function.Function<String, com.kltyton.lumenfx.client.simulation.niagara.NiagaraEmitterBounds.Snapshot> boundsSources) {
        this(emitter, programs, shaders, program, arrays, counters, emitterName, textures, particleSources, boundsSources, null);
    }

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings textures,
                                java.util.function.Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                java.util.function.Function<String, com.kltyton.lumenfx.client.simulation.niagara.NiagaraEmitterBounds.Snapshot> boundsSources,
                                @Nullable NiagaraGpuRenderTargets renderTargets) {
        this(emitter, programs, shaders, program, arrays, counters, emitterName, textures, particleSources, boundsSources,
                renderTargets, new NiagaraPlatformSetProxies(NiagaraPlatforms.publication()));
    }

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings textures,
                                java.util.function.Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                java.util.function.Function<String, com.kltyton.lumenfx.client.simulation.niagara.NiagaraEmitterBounds.Snapshot> boundsSources,
                                @Nullable NiagaraGpuRenderTargets renderTargets,
                                NiagaraPlatformSetProxies platformSets) {
        this(emitter, programs, shaders, program, arrays, counters, emitterName, textures, particleSources, boundsSources,
                renderTargets, platformSets, null);
    }

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                NiagaraTextureBindings textures,
                                Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                Function<String, NiagaraEmitterBounds.Snapshot> boundsSources,
                                @Nullable NiagaraGpuRenderTargets renderTargets, NiagaraPlatformSetProxies platformSets,
                                @Nullable Function<String, byte[]> curlObjects) {
        this(emitter, programs, shaders, program, arrays, counters, emitterName, textures, particleSources, boundsSources,
                renderTargets, platformSets, curlObjects, null);
    }

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                NiagaraTextureBindings textures,
                                Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                Function<String, NiagaraEmitterBounds.Snapshot> boundsSources,
                                @Nullable NiagaraGpuRenderTargets renderTargets, NiagaraPlatformSetProxies platformSets,
                                @Nullable Function<String, byte[]> curlObjects,
                                @Nullable Function<String, @Nullable VectorFieldBinding> vectorFieldObjects) {
        this(emitter, programs, shaders, program, arrays, counters, emitterName, textures, particleSources, boundsSources,
                renderTargets, platformSets, curlObjects, vectorFieldObjects, null);
    }

    public NiagaraGpuInterfaces(Identifier emitter, NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders,
                                JsonObject program, NiagaraGpuArrayRegistry arrays, NiagaraGpuCounterRegistry counters, String emitterName,
                                NiagaraTextureBindings textures,
                                Function<String, NiagaraGpuParticleRead.Source> particleSources,
                                Function<String, NiagaraEmitterBounds.Snapshot> boundsSources,
                                @Nullable NiagaraGpuRenderTargets renderTargets, NiagaraPlatformSetProxies platformSets,
                                @Nullable Function<String, byte[]> curlObjects,
                                @Nullable Function<String, @Nullable VectorFieldBinding> vectorFieldObjects,
                                @Nullable NiagaraGpuCurveRegistry curveObjects) {
        this.programs = programs;
        this.curlObjects = curlObjects;
        this.shaders = shaders;
        this.arrays = arrays;
        this.counters = counters;
        this.platformSets = Objects.requireNonNull(platformSets);
        platformSets.registerGpu(program);
        ownsRenderTargets = renderTargets == null;
        this.renderTargets = ownsRenderTargets
                ? new com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuRenderTargets(programs,
                        new com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces()) : renderTargets;
        emitterProperties = new NiagaraGpuEmitterProperties(program, boundsSources);
        objectProperties = new NiagaraGpuObjectPropertyBindings(programs, program);
        propertyInterfaces = new NiagaraGpuPropertyInterfaces(programs.runtime(), program);
        consoleVariables = new NiagaraGpuConsoleVariables(programs, program);
        asyncTraces = new NiagaraGpuAsyncTraceBindings(program);
        staticMeshes = new NiagaraGpuStaticMeshBindings(programs, shaders, program);
        skeletalMeshes = new NiagaraGpuSkeletalMeshBindings(programs, shaders, program);
        particleExports = new NiagaraGpuParticleExportBindings(programs, program);
        dataChannelReads = new NiagaraGpuDataChannelReadBindings(programs, program);
        dataChannelWrites = new NiagaraGpuDataChannelWriteBindings(programs, program);
        var channelStages = new ArrayList<Consumer<NiagaraGpuDataChannelWorlds.StageAccess>>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var binding = entry.getAsJsonObject();
            if (!binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            switch (binding.get("class").getAsString()) {
                case "NiagaraDataInterfaceDataChannelRead" -> channelStages.add(access -> dataChannelReads.beginStage(symbol, access));
                case "NiagaraDataInterfaceDataChannelWrite" -> channelStages.add(access -> dataChannelWrites.beginStage(symbol, access));
                default -> { }
            }
        }
        dataChannelStages = List.copyOf(channelStages);
        audioAnalysis = new NiagaraGpuAudioAnalysisBindings(programs, program);
        simCacheReaders = new NiagaraGpuCacheReaderBindings(programs, program);
        sparseVolumes = new NiagaraGpuSparseVolumeBindings(programs, program);
        volumeCaches = new NiagaraGpuVolumeCacheBindings(programs, program);
        physicsAssets = new NiagaraGpuPhysicsBindings(program);
        rigidMeshes = new NiagaraGpuRigidMeshBindings(programs, program);
        geometryCollections = new NiagaraGpuGeometryBindings(programs, program);
        chaosDestruction = new NiagaraGpuChaosBindings(program);
        hairStrands = new NiagaraGpuHairBindings(program);
        physicsField = new NiagaraGpuPhysicsFieldBindings(programs, program);
        sceneCaptures = new NiagaraGpuSceneCaptureBindings(program);
        landscapes = new NiagaraGpuLandscapeBindings(program, programs.terrainImages());
        virtualTextures = new NiagaraGpuVirtualTextureBindings(program, programs.terrainImages());
        memoryBuffers = new NiagaraGpuMemoryBindings(program);
        dataTables = new NiagaraGpuDataTableBindings(programs.runtime(), program);
        gaussianSplatReaders = new NiagaraGpuGaussianSplatReaderBindings(programs.runtime(), program);
        gaussianSplatWriters = new NiagaraGpuGaussianSplatWriterBindings(program);
        intDistributions = new NiagaraGpuIntDistributionBindings(programs.runtime(), program);
        intRenderTargets = new NiagaraGpuIntRenderTargetBindings(program);
        socketReaders = new NiagaraGpuSocketBindings(program);
        splines = new NiagaraGpuSplineBindings(program);
        viewQueries = new NiagaraGpuViewQueries(program);
        neighborQueries = new NiagaraGpuNeighborQueryBindings(program);
        neighborGrids = new NiagaraGpuNeighborGridBindings(program);
        rasterizationGrids = new NiagaraGpuRasterizationGridBindings(program);
        velocityGrids = new NiagaraGpuVelocityGridBindings(program);
        grid3DCollections = new NiagaraGpuGrid3DCollectionBindings(program);
        grid2DCollections = new NiagaraGpuGrid2DCollectionBindings(program);
        dynamicMeshes = new NiagaraGpuDynamicMeshBindings(program);
        actorComponents = new NiagaraGpuActorComponents(program);
        textureBindings = new NiagaraGpuTextureBindings(programs, program, textures);
        vectorFields = programs.vectorFields(emitter);
        vectorFieldBindings = vectorFieldObjects == null ? null : new NiagaraGpuVectorFieldBindings(programs, program, vectorFieldObjects);
        curveBindings = curveObjects == null ? null : new NiagaraGpuCurveBindings(program, shaders.curves(), curveObjects);
        meshInformation = programs.meshInformation(emitter);
        var bindings = new java.util.ArrayList<JsonObject>();
        var symbols = new LinkedHashMap<String, String>();
        var arraysByName = new LinkedHashMap<String, String>();
        var originalArrays = new LinkedHashMap<String, String>();
        var cameras = new LinkedHashMap<String, CameraBinding>();
        var platformSymbols = new LinkedHashMap<String, String>();
        var originalPlatforms = new LinkedHashMap<String, String>();
        var curl = new LinkedHashMap<String, byte[]>();
        var curlNames = new LinkedHashMap<String, String>();
        var readers = new java.util.ArrayList<NiagaraGpuParticleRead>();
        var readerBindings = new java.util.ArrayList<ParticleReaderBinding>();
        var readerByName = new LinkedHashMap<String, ParticleReaderBinding>();
        var counterData = new java.util.ArrayList<JsonObject>();
        var counterBySymbol = new LinkedHashMap<String, String>();
        var counterByName = new LinkedHashMap<String, String>();
        var originalCounters = new LinkedHashMap<String, String>();
        var targets = new java.util.ArrayList<JsonObject>();
        var targetsBySymbol = new LinkedHashMap<String, String>();
        var targetsByName = new LinkedHashMap<String, String>();
        var originalTargets = new LinkedHashMap<String, String>();
        var dimensionalTargetsBySymbol = new LinkedHashMap<String, DimensionalTargetBinding>();
        var dimensionalTargetsByName = new LinkedHashMap<String, DimensionalTargetBinding>();
        var occlusion = new java.util.LinkedHashSet<String>();
        var gBuffers = new java.util.LinkedHashSet<String>();
        var proxyIterations = new java.util.LinkedHashSet<String>();
        boolean collisionInterface = false;
        readerSnapshot = new NiagaraGpuReaderSnapshot(emitterName);
        boolean readsSelf = false;
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            if (binding.has("shader_symbol") && (type.equals("NiagaraDataInterfaceNeighborGrid3D")
                    || type.equals("NiagaraDataInterfaceRasterizationGrid3D") || binding.has("neighbor_query")
                    || binding.has("grid2d_collection") || binding.has("grid3d_collection")
                    || binding.has("velocity_grid") || binding.has("int_render_target_2d"))) {
                proxyIterations.add(SourceNames.canonical(binding.get("parameter_name").getAsString()));
            }
            if (type.startsWith("NiagaraDataInterfaceArray") && binding.has("array_data")) {
                if (!binding.has("parameter_name") || !binding.has("interface_id"))
                    throw new IllegalArgumentException("Niagara GPU array has no parameter name or instance identity");
                String id = binding.get("interface_id").getAsString();
                String key = SourceNames.canonical(binding.get("compile_name").getAsString());
                originalArrays.put(key, id);
                String name = SourceNames.canonical(binding.get("parameter_name").getAsString());
                if (arraysByName.putIfAbsent(name, key) != null) throw new IllegalArgumentException("Duplicate Niagara GPU array binding " + name);
                if (binding.has("shader_symbol")) {
                    String symbol = binding.get("shader_symbol").getAsString();
                    if (symbols.putIfAbsent(symbol, key) != null) throw new IllegalArgumentException("Duplicate Niagara GPU array symbol " + symbol);
                }
                arrays.register(binding);
                bindings.add(binding.deepCopy());
            } else if (type.equals("NiagaraDataInterfaceRenderTarget2D")) {
                this.renderTargets.register(binding);
                if (!binding.has("shader_symbol")) continue;
                String id = binding.get("interface_id").getAsString();
                String symbol = binding.get("shader_symbol").getAsString();
                String key = SourceNames.canonical(binding.get("compile_name").getAsString());
                originalTargets.put(key, id);
                if (targetsBySymbol.putIfAbsent(symbol, key) != null)
                    throw new IllegalArgumentException("Duplicate Niagara render target shader symbol " + symbol);
                for (String field : List.of("parameter_name", "resolved_parameter_name", "compile_name")) {
                    if (!binding.has(field)) continue;
                    String name = SourceNames.canonical(binding.get(field).getAsString());
                    String old = targetsByName.putIfAbsent(name, key);
                    if (old != null && !originalTargets.get(old).equals(id))
                        throw new IllegalArgumentException("Ambiguous Niagara render target name " + name);
                }
                targets.add(binding.deepCopy());
            } else if (type.equals("NiagaraDataInterfacePlatformSet")) {
                if (!binding.has("shader_symbol")) continue;
                String symbol = binding.get("shader_symbol").getAsString() + "_bIsActive";
                String key = SourceNames.canonical(binding.get("compile_name").getAsString());
                originalPlatforms.put(key, binding.get("interface_id").getAsString());
                if (platformSymbols.putIfAbsent(symbol, key) != null)
                    throw new IllegalArgumentException("Duplicate Niagara PlatformSet shader symbol " + symbol);
            } else if (type.equals("NiagaraDataInterfaceCamera")) {
                if (!binding.has("shader_symbol")) throw new IllegalArgumentException("Niagara GPU camera has no shader symbol");
                String key = SourceNames.canonical(binding.get("compile_name").getAsString());
                String symbol = binding.get("shader_symbol").getAsString() + "_SplitscreenMode";
                if (cameras.putIfAbsent(symbol, new CameraBinding(key, binding.get("interface_id").getAsString())) != null)
                    throw new IllegalArgumentException("Duplicate Niagara camera shader symbol " + symbol);
            } else if (type.equals("NiagaraDataInterfaceCurlNoise")) {
                if (!binding.has("shader_symbol") || !binding.has("curl_seed"))
                    throw new IllegalArgumentException("Niagara GPU curl noise has no source seed or shader symbol");
                NiagaraCurlNoise noise = new NiagaraCurlNoise(binding.get(binding.has("curl_offset_seed") ? "curl_offset_seed" : "curl_seed").getAsInt());
                String symbol = binding.get("shader_symbol").getAsString() + "_OffsetFromSeed";
                curlNames.put(symbol, SourceNames.canonical(binding.get("compile_name").getAsString()));
                curl.put(symbol,
                        ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                                .putFloat(noise.offsetX()).putFloat(noise.offsetY()).putFloat(noise.offsetZ()).array());
            } else if (type.equals("NiagaraDataInterfaceParticleRead")) {
                if (!binding.has("parameter_name") || !binding.has("particle_read"))
                    throw new IllegalArgumentException("Niagara GPU particle reader has no source binding");
                var read = binding.getAsJsonObject("particle_read");
                readsSelf |= SourceNames.equal(emitterName, read.get("emitter").getAsString());
                String symbol = binding.has("shader_symbol") ? binding.get("shader_symbol").getAsString() : "";
                var reader = new NiagaraGpuParticleRead(symbol, emitterName, read, particleSources,
                        programs.dummyBuffer().slice(), readerSnapshot);
                var readerBinding = new ParticleReaderBinding(reader, SourceNames.canonical(binding.get("compile_name").getAsString()),
                        binding.get("interface_id").getAsString(), read.get("emitter").getAsString());
                readerBindings.add(readerBinding);
                String name = SourceNames.canonical(binding.get("parameter_name").getAsString());
                if (readerByName.putIfAbsent(name, readerBinding) != null)
                    throw new IllegalArgumentException("Duplicate Niagara GPU particle reader binding " + name);
                readers.add(reader);
            } else if (type.equals("NiagaraDataInterfaceSimpleCounter")) {
                if (!binding.has("parameter_name") || !binding.has("interface_id") || !binding.has("simple_counter"))
                    throw new IllegalArgumentException("Niagara GPU counter has no source binding");
                String id = binding.get("interface_id").getAsString();
                String key = SourceNames.canonical(binding.get("compile_name").getAsString());
                originalCounters.put(key, id);
                String name = SourceNames.canonical(binding.get("parameter_name").getAsString());
                if (counterByName.putIfAbsent(name, key) != null)
                    throw new IllegalArgumentException("Duplicate Niagara GPU counter binding " + name);
                if (binding.has("shader_symbol")) {
                    String symbol = binding.get("shader_symbol").getAsString();
                    if (counterBySymbol.putIfAbsent(symbol, key) != null)
                        throw new IllegalArgumentException("Duplicate Niagara GPU counter shader symbol " + symbol);
                }
                counters.register(binding);
                counterData.add(binding.deepCopy());
            } else if (type.equals("NiagaraDataInterfaceRenderTarget2DArray") || type.equals("NiagaraDataInterfaceRenderTargetVolume")
                    || type.equals("NiagaraDataInterfaceRenderTargetCube")) {
                String key = SourceNames.canonical(binding.get("compile_name").getAsString());
                var target = new DimensionalTargetBinding(key, binding.get("interface_id").getAsString());
                for (String field : List.of("compile_name", "parameter_name", "resolved_parameter_name")) {
                    if (!binding.has(field)) continue;
                    String name = SourceNames.canonical(binding.get(field).getAsString());
                    if (name.isEmpty() || name.equals("none")) continue;
                    var previous = dimensionalTargetsByName.putIfAbsent(name, target);
                    if (previous != null && !previous.equals(target))
                        throw new IllegalArgumentException("Duplicate Niagara render target array binding " + name);
                }
                if (binding.has("shader_symbol")) {
                    String symbol = binding.get("shader_symbol").getAsString();
                    if (dimensionalTargetsBySymbol.putIfAbsent(symbol, target) != null)
                        throw new IllegalArgumentException("Duplicate Niagara render target array shader symbol " + symbol);
                }
            } else if (type.equals("NiagaraDataInterfaceOcclusion")) {
                if (binding.has("shader_symbol")) occlusion.add(binding.get("shader_symbol").getAsString());
            } else if (type.equals("NiagaraDataInterfaceGBuffer")) {
                if (binding.has("shader_symbol")) gBuffers.add(binding.get("shader_symbol").getAsString());
            } else if (type.equals("NiagaraDataInterfaceCollisionQuery")) {
                collisionInterface = true;
            } else if (type.equals("NiagaraDataInterfaceEmitterProperties")) {
                if (!binding.has("emitter_properties"))
                    throw new IllegalArgumentException("Niagara GPU emitter properties has no source binding");
            } else if (!supportsRuntimeInterface(type, binding))
                throw new IllegalArgumentException("Niagara GPU interface requires its runtime implementation: " + type);
        }
        arrayBindings = List.copyOf(bindings);
        arraySymbols = Map.copyOf(symbols);
        arrayNames = Map.copyOf(arraysByName);
        arrayOriginals = Map.copyOf(originalArrays);
        cameraSymbols = Map.copyOf(cameras);
        platformSetSymbols = Map.copyOf(platformSymbols);
        platformOriginals = Map.copyOf(originalPlatforms);
        curlParameters = Map.copyOf(curl);
        curlSymbols = Map.copyOf(curlNames);
        selfParticleRead = readsSelf;
        particleReaders = List.copyOf(readers);
        particleReaderBindings = List.copyOf(readerBindings);
        particleReaderNames = Map.copyOf(readerByName);
        counterBindings = List.copyOf(counterData);
        counterSymbols = Map.copyOf(counterBySymbol); counterNames = Map.copyOf(counterByName);
        counterOriginals = Map.copyOf(originalCounters);
        declaredProxyIterations = Set.copyOf(proxyIterations);
        targetBindings = List.copyOf(targets); targetSymbols = Map.copyOf(targetsBySymbol); targetNames = Map.copyOf(targetsByName);
        targetOriginals = Map.copyOf(originalTargets);
        occlusionSymbols = Set.copyOf(occlusion);
        gBufferSymbols = Set.copyOf(gBuffers);
        hasCollisionInterface = collisionInterface;
        dimensionalTargetSymbols = Map.copyOf(dimensionalTargetsBySymbol);
        dimensionalTargetNames = Map.copyOf(dimensionalTargetsByName);
    }

    public static boolean supportsRuntimeInterface(String type, JsonObject binding) {
        return STATIC_INTERFACES.contains(type)
                || type.equals("NiagaraDataInterfaceTexture")
                || type.equals("NiagaraDataInterface2DArrayTexture")
                || type.equals("NiagaraDataInterfaceCubeTexture")
                || type.equals("NiagaraDataInterfaceVolumeTexture")
                || type.equals("NiagaraDataInterfaceVolumeCache")
                || type.equals("NiagaraDataInterfacePhysicsAsset")
                || type.equals("NiagaraDataInterfaceRigidMeshCollisionQuery")
                || binding.has("geometry_collection") || binding.has("chaos_destruction") || binding.has("hair_strands")
                || type.equals("NiagaraDataInterfacePhysicsField")
                || type.equals("NiagaraDataInterfaceSceneCapture2D")
                || type.equals("NiagaraDataInterfaceLandscape")
                || type.equals("NiagaraDataInterfaceVirtualTexture") || type.equals("NiagaraDataInterfaceVirtualTextureSample")
                || type.equals("NiagaraDataInterfaceUObjectPropertyReader")
                || type.equals(NiagaraPropertyInterface.TYPE)
                || type.equals("NiagaraDataInterfaceConsoleVariable")
                || type.equals("NiagaraDataInterfaceAsyncGpuTrace")
                || type.equals("NiagaraDataInterfaceStaticMesh")
                || type.equals("NiagaraDataInterfaceStaticMeshIndirect")
                || type.equals("NiagaraDataInterfaceDebugDraw")
                || type.equals("NiagaraDataInterfaceSkeletalMesh")
                || type.equals("NiagaraDataInterfaceExport")
                || type.equals("NiagaraDataInterfaceDataChannelRead")
                || type.equals("NiagaraDataInterfaceDataChannelWrite")
                || type.equals("NiagaraDataInterfaceAudioSpectrum")
                || type.equals("NiagaraDataInterfaceAudioOscilloscope")
                || type.equals("NiagaraDataInterfaceNeighborGrid3D")
                || type.equals("NiagaraDataInterfaceRasterizationGrid3D")
                || binding.has("velocity_grid")
                || binding.has("grid3d_collection")
                || binding.has("grid2d_collection")
                || binding.has("data_table")
                || binding.has("gaussian_splat_reader") || type.equals(NiagaraGaussianSplatReader.TYPE)
                || binding.has("gaussian_splat_writer") || type.equals(NiagaraGaussianSplatWriter.TYPE)
                || binding.has("int_distribution")
                || binding.has("int_render_target_2d")
                || binding.has("socket_reader")
                || binding.has("spline")
                || binding.has("neighbor_query")
                || binding.has("sim_cache_reader") || type.equals("NiagaraDataInterfaceSimCacheReader")
                || binding.has("memory_buffer") || type.equals("NiagaraDataInterfaceMemoryBuffer")
                || binding.has("dynamic_mesh") || type.equals("NiagaraDataInterfaceDynamicMesh")
                || binding.has("sparse_volume_texture") || type.equals("NiagaraDataInterfaceSparseVolumeTexture")
                || type.equals("NiagaraDataInterfaceMousePosition")
                || type.equals("NiagaraDataInterfaceMRQ")
                || type.equals("NiagaraDataInterfaceActorComponent");
    }

    void debugLines(NiagaraGpuDebugLines lines) { debugLines = lines; }

    @Override public void beginTick(CommandEncoder encoder) {
        tickEncoder = encoder;
        boolean readsSelf = false;
        for (var binding : particleReaderBindings) {
            String identity = selectedParticleReader(binding);
            var object = emitterObjects == null || !interfaceSelections.containsKey(binding.key()) ? null : emitterObjects.apply(identity);
            if (object != null && !object.type().equals("NiagaraDataInterfaceParticleRead"))
                throw new IllegalStateException("Selected particle reader object has a different DI class: " + identity);
            if (object == null && !SourceNames.equal(identity, binding.original()))
                throw new IllegalStateException("Selected particle reader object has no emitter binding: " + identity);
            binding.reader().target(object == null ? binding.target() : object.emitter());
            readsSelf |= binding.reader().readsSelf();
        }
        selfParticleRead = readsSelf;
        if (vectorFieldBindings != null) vectorFieldBindings.prepare(interfaceSelections);
        if (curveBindings != null) curveBindings.prepare(interfaceSelections);
        var information = rendererInformation;
        if (information != null) information.prepare(interfaceSelections);
        if (ownsRenderTargets) prepareRenderTargets();
        for (String key : new java.util.LinkedHashSet<>(targetSymbols.values())) {
            String selected = selectedImage(key);
            if (!selected.equals(targetOriginals.get(key))) renderTargets.prepareSelected(selected);
        }
        emitterProperties.beginTick(interfaceSelections, emitterObjects, emitterBounds);
        objectProperties.beginTick(encoder, interfaceSelections, objectPropertySnapshots);
        propertyInterfaces.beginTick(encoder, interfaceSelections, propertyInterfaceSnapshots);
        var environment = consoleTickEnvironment == null ? NiagaraPlatforms.snapshot() : consoleTickEnvironment;
        var initialization = consoleInitialization;
        if (initialization == null) consoleInitialization = initialization = new Initialization(environment);
        consoleVariables.beginTick(encoder, environment, initialization, interfaceSelections);
        actorComponents.beginTick(interfaceSelections, actorComponentSnapshots);
        staticMeshes.beginTick(interfaceSelections, staticMeshSnapshots);
        skeletalMeshes.beginTick(encoder, interfaceSelections, skeletalMeshSnapshots);
        particleExports.beginTick(interfaceSelections, particleExportSnapshots, particleExportWorld, environment.particleExportReadbackLimit());
        dataChannelReads.beginTick(dataChannelReaders, interfaceSelections, dataChannelSnapshots);
        dataChannelWrites.beginTick(dataChannelWriters, interfaceSelections, dataChannelWriterSnapshots);
        audioAnalysis.beginTick(interfaceSelections, audioAnalysisSnapshots);
        simCacheReaders.beginTick(interfaceSelections, simCacheReaderSnapshots);
        sparseVolumes.beginTick(interfaceSelections, sparseVolumeSnapshots);
        volumeCaches.beginTick(volumeCacheRegistry, interfaceSelections, volumeCacheSnapshots);
        physicsAssets.beginTick(physicsRegistry, interfaceSelections, physicsSnapshots);
        rigidMeshes.beginTick(rigidMeshRegistry, interfaceSelections, rigidMeshSnapshots);
        geometryCollections.beginTick(geometryRegistry, interfaceSelections, geometrySnapshots);
        chaosDestruction.beginTick(chaosRegistry, interfaceSelections, chaosSnapshots);
        hairStrands.beginTick(hairRegistry, interfaceSelections, hairSnapshots,
                consoleTickEnvironment == null ? NiagaraPlatforms.snapshot() : consoleTickEnvironment);
        sceneCaptures.beginTick(interfaceSelections, sceneCaptureSnapshots);
        landscapes.beginTick(landscapeGrids, interfaceSelections, landscapeSnapshots);
        virtualTextures.beginTick(interfaceSelections, virtualTextureSnapshots);
        memoryBuffers.beginTick(encoder, memoryRegistry, interfaceSelections, memorySnapshots);
        dataTables.beginTick(interfaceSelections, dataTableSnapshots);
        gaussianSplatReaders.beginTick(interfaceSelections, gaussianSplatReaderSnapshots);
        gaussianSplatWriters.beginTick(gaussianSplatWriterRegistry, interfaceSelections, proxySourceNames, gaussianSplatWriterSnapshots);
        intDistributions.beginTick(interfaceSelections, intDistributionSnapshots);
        intRenderTargets.beginTick(intRenderTargetRegistry, interfaceSelections, proxySourceNames, intRenderTargetSnapshots);
        socketReaders.beginTick(socketBuffers, interfaceSelections, socketSnapshots);
        splines.beginTick(splineBuffers, interfaceSelections, splineSnapshots);
        neighborGrids.beginTick(neighborGridRegistry, interfaceSelections, proxySourceNames, neighborGridSnapshots,
                NiagaraNeighborGrid.sourceMaximum(environment));
        neighborQueries.beginTick(neighborQueryRegistry, interfaceSelections, proxySourceNames, neighborQuerySnapshots,
                Math.min(environment.neighborQueryCellMaximum(), programs.runtime().maxStorageBufferBytes() / Integer.BYTES));
        var rasterizationLimit = environment.console(NiagaraRasterizationGrid.MAXIMUM_CELLS_VARIABLE);
        rasterizationGrids.beginTick(rasterizationGridRegistry, interfaceSelections, proxySourceNames, rasterizationGridSnapshots,
                rasterizationLimit == null ? NiagaraRasterizationGrid.DEFAULT_MAXIMUM_CELLS : rasterizationLimit.bits());
        velocityGrids.beginTick(velocityGridRegistry, interfaceSelections, proxySourceNames, velocityGridSnapshots);
        grid3DCollections.beginTick(grid3DCollectionRegistry, interfaceSelections, proxySourceNames, grid3DCollectionSnapshots);
        grid2DCollections.beginTick(grid2DCollectionRegistry, interfaceSelections, proxySourceNames, grid2DCollectionSnapshots);
        dynamicMeshes.beginTick(encoder, dynamicMeshRegistry, interfaceSelections, dynamicMeshSnapshots);
        for (JsonObject binding : arrayBindings) {
            String key = SourceNames.canonical(binding.get("compile_name").getAsString());
            String selected = selected(key, arrayOriginals);
            var input = arrayInput(selected);
            if (selected.equals(binding.get("interface_id").getAsString())) {
                arrays.register(binding);
                arrays.prepare(encoder, selected, input);
            } else arrays.prepareSelected(encoder, selected, binding.get("class").getAsString(),
                    binding.get("array_writable").getAsBoolean(), input);
        }
        for (JsonObject binding : counterBindings) {
            String key = SourceNames.canonical(binding.get("compile_name").getAsString());
            String selected = selected(key, counterOriginals);
            var inputs = mutableInputs;
            var input = inputs == null ? null : Objects.requireNonNull(inputs.counters().get(selected),
                    "Counter not published for queued Niagara step: " + selected);
            if (selected.equals(binding.get("interface_id").getAsString())) {
                counters.register(binding);
                counters.prepare(encoder, selected, input);
            } else counters.prepareSelected(encoder, selected, input);
        }
    }

    private NiagaraInterfaces.@Nullable ArraySnapshot arrayInput(String identity) {
        var inputs = mutableInputs;
        return inputs == null ? null : Objects.requireNonNull(inputs.arrays().get(identity),
                "Array not published for queued Niagara step: " + identity);
    }

    private void prepareRenderTargets() {
        for (var binding : targetBindings) {
            renderTargets.register(binding);
            String id = binding.get("interface_id").getAsString();
            renderTargets.prepare(id, renderTargets.sourceDefaultSnapshot(id));
        }
    }

    @Override public void textureSnapshots(Map<String, com.kltyton.lumenfx.api.client.parameter.TextureParameter.Snapshot> textures) {
        textureBindings.snapshots(textures);
    }
    public void dataInterfaces(Map<String, String> selections) {
        interfaceSelections = Map.copyOf(selections);
        textureBindings.selections(interfaceSelections);
    }
    public void cameraSource(ToIntFunction<String> source) { cameraObjects = Objects.requireNonNull(source); }
    public void hostSnapshots(@Nullable Map<String, Integer> cameras,
                              @Nullable Map<String, NiagaraEmitterBounds.Snapshot> bounds) {
        hostSnapshots(cameras, bounds, null);
    }
    public void hostSnapshots(@Nullable Map<String, Integer> cameras,
                              @Nullable Map<String, NiagaraEmitterBounds.Snapshot> bounds,
                              NiagaraInterfaces.@Nullable MutableGpuInputs mutable) {
        hostSnapshots(cameras, bounds, mutable, null);
    }

    public void hostSnapshots(@Nullable Map<String, Integer> cameras,
                              @Nullable Map<String, NiagaraEmitterBounds.Snapshot> bounds,
                              NiagaraInterfaces.@Nullable MutableGpuInputs mutable,
                              @Nullable Map<String, Integer> platforms) {
        cameraModes = cameras;
        emitterBounds = bounds;
        mutableInputs = mutable;
        platformValues = platforms;
    }
    public void releaseTickSnapshots() {
        hostSnapshots(null, null, null);
        textureBindings.snapshots(null);
        objectPropertySnapshots = Map.of();
        actorComponentSnapshots = Map.of();
        consoleTickEnvironment = null;
        asyncTraces.clearStage();
        asyncTraces.snapshots(null);
        staticMeshSnapshots = Map.of();
        propertyInterfaceSnapshots = Map.of();
        staticMeshes.clearSnapshots();
        skeletalMeshSnapshots = Map.of();
        skeletalMeshes.clearSnapshots();
        particleExportSnapshots = Map.of();
        particleExportWorld = null;
        particleExports.clearSnapshots();
        dataChannelSnapshots = Map.of();
        dataChannelReads.clearSnapshots();
        dataChannelWriterSnapshots = Map.of();
        dataChannelWrites.clearSnapshots();
        audioAnalysisSnapshots = Map.of();
        audioAnalysis.clearSnapshots();
        simCacheReaderSnapshots = Map.of();
        simCacheReaders.clearSnapshots();
        sparseVolumeSnapshots = Map.of();
        sparseVolumes.clearSnapshots();
        volumeCacheSnapshots = Map.of();
        volumeCacheRegistry = null;
        volumeCaches.clear();
        physicsSnapshots = Map.of();
        physicsRegistry = null;
        physicsAssets.clear();
        rigidMeshSnapshots = Map.of();
        rigidMeshRegistry = null;
        rigidMeshes.clear();
        geometryRegistry = null;
        geometrySnapshots = Map.of();
        geometryCollections.clear();
        chaosDestruction.clear();
        hairStrands.clear();
        physicsField.snapshot(null);
        sceneCaptureSnapshots = Map.of();
        sceneCaptures.clear();
        landscapes.clear();
        landscapeSnapshots = Map.of();
        landscapeGrids = null;
        virtualTextures.clear();
        virtualTextureSnapshots = Map.of();
        memoryRegistry = null;
        memorySnapshots = Map.of();
        memoryBuffers.clearSnapshots();
        neighborGridRegistry = null;
        neighborGridSnapshots = Map.of();
        neighborGrids.clearSnapshots();
        rasterizationGridRegistry = null;
        rasterizationGridSnapshots = Map.of();
        rasterizationGrids.clearSnapshots();
        velocityGridRegistry = null;
        velocityGridSnapshots = Map.of();
        velocityGrids.clearSnapshots();
        grid3DCollectionRegistry = null;
        grid3DCollectionSnapshots = Map.of();
        grid3DCollections.clearSnapshots();
        grid2DCollectionRegistry = null;
        grid2DCollectionSnapshots = Map.of();
        grid2DCollections.clearSnapshots();
        dataTableSnapshots = Map.of();
        dataTables.clearSnapshots();
        gaussianSplatReaderSnapshots = Map.of();
        gaussianSplatReaders.clearSnapshots();
        gaussianSplatWriterSnapshots = Map.of();
        gaussianSplatWriterRegistry = null;
        gaussianSplatWriters.clearSnapshots();
        intDistributionSnapshots = Map.of();
        intDistributions.clearSnapshots();
        intRenderTargetRegistry = null;
        intRenderTargetSnapshots = Map.of();
        intRenderTargets.clearSnapshots();
        socketBuffers = null;
        socketSnapshots = Map.of();
        socketReaders.clearSnapshots();
        splineBuffers = null;
        splineSnapshots = Map.of();
        splines.clearSnapshots();
        viewQueries.capture(NiagaraViewQueries.EMPTY);
        neighborQueryRegistry = null;
        neighborQuerySnapshots = Map.of();
        neighborQueries.clearSnapshots();
        dynamicMeshRegistry = null;
        dynamicMeshSnapshots = Map.of();
        dynamicMeshes.clearSnapshots();
    }

    public void objectPropertySnapshots(Map<String, NiagaraObjectPropertyReader.Snapshot> snapshots) {
        objectPropertySnapshots = snapshots;
    }

    public void propertyInterfaceSnapshots(Map<String, NiagaraPropertyInterface.Frame> snapshots) {
        propertyInterfaceSnapshots = snapshots;
    }
    public void actorComponentSnapshots(Map<String, NiagaraActorComponent.Snapshot> snapshots) {
        actorComponentSnapshots = snapshots;
    }
    public void staticMeshSnapshots(Map<String, GpuSnapshot> snapshots) { staticMeshSnapshots = snapshots; }
    public void skeletalMeshSnapshots(Map<String, NiagaraSkeletalGpuSnapshot> snapshots) { skeletalMeshSnapshots = snapshots; }
    public void particleExportSnapshots(Map<String, NiagaraParticleExportState.Snapshot> snapshots, @Nullable ClientLevel level) {
        particleExportSnapshots = snapshots;
        particleExportWorld = level;
    }
    public void dataChannelSnapshots(Map<String, NiagaraDataChannelGpuSnapshot> snapshots) { dataChannelSnapshots = snapshots; }
    public void dataChannelReaders(NiagaraGpuDataChannelReaders readers) { dataChannelReaders = readers; }
    public void dataChannelWriterSnapshots(Map<String, NiagaraDataChannelState.GpuWriter> snapshots) { dataChannelWriterSnapshots = snapshots; }
    public void dataChannelWriters(NiagaraGpuDataChannelWriters writers) { dataChannelWriters = writers; }
    public void audioAnalysisSnapshots(Map<String, NiagaraAudioBusAnalysis.Frame> snapshots) { audioAnalysisSnapshots = snapshots; }
    public void simCacheReaderSnapshots(Map<String, Bound> snapshots) { simCacheReaderSnapshots = snapshots; }
    public void sparseVolumeSnapshots(Map<String, NiagaraSparseVolumeTexture.Snapshot> snapshots) { sparseVolumeSnapshots = snapshots; }
    public void volumeCacheSnapshots(@Nullable NiagaraGpuVolumeCaches registry, Map<String, NiagaraVolumeCache.Snapshot> snapshots) {
        volumeCacheRegistry = registry;
        volumeCacheSnapshots = snapshots;
    }
    public void physicsAssetSnapshots(@Nullable NiagaraGpuPhysicsBuffers registry, Map<String, NiagaraPhysicsAssetReader.Frame> snapshots) {
        physicsRegistry = registry;
        physicsSnapshots = snapshots;
    }

    public void hairStrandsSnapshots(@Nullable NiagaraGpuHairBuffers registry, Map<String, HairStrandsFrame> snapshots) {
        hairRegistry = registry;
        hairSnapshots = snapshots;
    }

    public void chaosDestructionSnapshots(@Nullable NiagaraGpuChaosBuffers registry, Map<String, ChaosDestructionFrame> snapshots) {
        chaosRegistry = registry;
        chaosSnapshots = snapshots;
    }

    public void geometryCollectionSnapshots(@Nullable NiagaraGpuGeometryBuffers registry, Map<String, GeometryCollectionFrame> snapshots) {
        geometryRegistry = registry;
        geometrySnapshots = snapshots;
    }

    public void rigidMeshSnapshots(NiagaraGpuRigidMeshBindings.@Nullable Buffers registry,
                                   Map<String, NiagaraRigidMeshCollision.Frame> snapshots) {
        rigidMeshRegistry = registry;
        rigidMeshSnapshots = snapshots;
    }

    public void sceneCaptureSnapshots(Map<String, NiagaraSceneCapture.Publication> snapshots) { sceneCaptureSnapshots = snapshots; }

    public void landscapeSnapshots(@Nullable CollisionGrids grids, Map<String, NiagaraLandscape.Frame> snapshots) {
        landscapeGrids = grids;
        landscapeSnapshots = snapshots;
    }

    public void virtualTextureSnapshots(Map<String, NiagaraVirtualTextureState.Frame> snapshots) { virtualTextureSnapshots = snapshots; }
    public void memoryBufferSnapshots(@Nullable NiagaraGpuMemoryBuffers buffers, Map<String, Snapshot> snapshots) {
        memoryRegistry = buffers;
        memorySnapshots = snapshots;
    }

    public void neighborGridSnapshots(@Nullable NiagaraGpuNeighborGrids grids, Map<String, NiagaraNeighborGrid.Snapshot> snapshots) {
        neighborGridRegistry = grids;
        neighborGridSnapshots = snapshots;
    }

    public void rasterizationGridSnapshots(@Nullable NiagaraGpuRasterizationGrids grids,
                                           Map<String, NiagaraRasterizationGrid.Snapshot> snapshots) {
        rasterizationGridRegistry = grids;
        rasterizationGridSnapshots = snapshots;
    }

    public void velocityGridSnapshots(@Nullable NiagaraGpuVelocityGrids grids, Map<String, NiagaraVelocityGrid.Snapshot> snapshots) {
        velocityGridRegistry = grids;
        velocityGridSnapshots = snapshots;
    }

    public void grid3DCollectionSnapshots(@Nullable NiagaraGpuGrid3DCollections grids, Map<String, Publication> snapshots) {
        grid3DCollectionRegistry = grids;
        grid3DCollectionSnapshots = snapshots;
    }
    public void grid2DCollectionSnapshots(@Nullable NiagaraGpuGrid2DCollections grids, Map<String, NiagaraGrid2DCollections.Publication> snapshots) {
        grid2DCollectionRegistry = grids;
        grid2DCollectionSnapshots = snapshots;
    }
    public void dataTableSnapshots(Map<String, NiagaraDataTable> snapshots) { dataTableSnapshots = snapshots; }
    public void gaussianSplatReaderSnapshots(Map<String, NiagaraGaussianSplatReader> snapshots) { gaussianSplatReaderSnapshots = snapshots; }
    public void gaussianSplatWriterSnapshots(@Nullable NiagaraGpuGaussianSplatWriters writers,
                                             Map<String, NiagaraGaussianSplatWriter.Snapshot> snapshots) {
        gaussianSplatWriterRegistry = writers;
        gaussianSplatWriterSnapshots = snapshots;
    }
    public void intDistributionSnapshots(Map<String, NiagaraIntDistribution> snapshots) { intDistributionSnapshots = snapshots; }
    public void physicsFieldSnapshot(@Nullable NiagaraPhysicsField snapshot) { physicsField.snapshot(snapshot); }
    public void intRenderTargetSnapshots(@Nullable NiagaraGpuIntRenderTargets registry, Map<String, IntTargetPublication> snapshots) {
        intRenderTargetRegistry = registry;
        intRenderTargetSnapshots = snapshots;
    }
    public void socketSnapshots(@Nullable NiagaraGpuSocketBuffers buffers, Map<String, NiagaraSocketReader.Snapshot> snapshots) {
        socketBuffers = buffers;
        socketSnapshots = snapshots;
    }

    public void splineSnapshots(@Nullable NiagaraGpuSplineBuffers buffers, Map<String, GpuFrame> snapshots) {
        splineBuffers = buffers;
        splineSnapshots = snapshots;
    }

    public void viewQueries(NiagaraViewQueries snapshot) { viewQueries.capture(snapshot); }

    public void neighborQuerySnapshots(@Nullable NiagaraGpuNeighborQueries registry, Map<String, NiagaraNeighborQuery.Snapshot> snapshots) {
        neighborQueryRegistry = registry;
        neighborQuerySnapshots = snapshots;
    }
    public void dynamicMeshSnapshots(@Nullable NiagaraGpuDynamicMeshes meshes, Map<String, NiagaraDynamicMeshFrame> snapshots) {
        dynamicMeshRegistry = meshes;
        dynamicMeshSnapshots = snapshots;
    }
    public void consoleVariableSnapshot(NiagaraPlatformEnvironment environment, Initialization initialization) {
        consoleTickEnvironment = environment;
        consoleInitialization = initialization;
    }
    public void asyncTraceSource(Function<String, @Nullable NiagaraAsyncGpuTraceDefinition> source) { asyncTraces.source(source); }
    public void proxySourceNames(Map<String, String> sourceNames) { proxySourceNames = Map.copyOf(sourceNames); }
    public void asyncTraceSnapshots(Map<String, AsyncTraceSnapshot> snapshots) { asyncTraces.snapshots(snapshots); }
    void asyncTraceRegistry(NiagaraGpuAsyncTraces registry) { asyncTraces.registry(registry); }
    public void dimensionalTargets(NiagaraGpuDimensionalRenderTargets arrays) { dimensionalTargets = Objects.requireNonNull(arrays); }
    private NiagaraGpuDimensionalRenderTargets dimensionalTargets() {
        return Objects.requireNonNull(dimensionalTargets, "Writable render target arrays have no system publication");
    }
    private String selectedDimensionalTarget(DimensionalTargetBinding binding) {
        return interfaceSelections.getOrDefault(binding.key(), binding.original());
    }
    public void emitterSource(Function<String, @Nullable EmitterBinding> source) { emitterObjects = Objects.requireNonNull(source); }
    public void rendererInformation(JsonObject program, NiagaraGpuRendererInfoRegistry registry) {
        rendererInformation = new NiagaraGpuRendererInfoBindings(program, registry);
    }
    private String selectedImage(String key) {
        return selected(key, targetOriginals);
    }
    private String selected(String key, Map<String, String> originals) {
        return interfaceSelections.getOrDefault(key, Objects.requireNonNull(originals.get(key)));
    }

    @Override public void beforeParticleStage(CommandEncoder encoder, NiagaraGpuParticleBuffers particles,
                                              NiagaraGpuStageSchedule.Invocation stage) {
        if (selfParticleRead && stage.access() == NiagaraGpuStageSchedule.ParticleAccess.PARTIAL)
            readerSnapshot.capture(encoder, particles);
    }

    @Override public void close() {
        try (sparseVolumes; simCacheReaders; audioAnalysis; dataChannelReads; dataChannelWrites; particleExports; skeletalMeshes; dataTables; gaussianSplatReaders; intDistributions; propertyInterfaces) {
        }
        finally {
            try { staticMeshes.close(); }
            finally {
                try { consoleVariables.close(); }
                finally {
                    try { objectProperties.close(); }
                    finally {
                        try { readerSnapshot.close(); }
                        finally { if (ownsRenderTargets) renderTargets.close(); }
                    }
                }
            }
        }
    }

    public void platformPublication(NiagaraPlatforms.Publication value) { platformSets.synchronize(value); }
    public void view(NiagaraGpuView value) { view = value; }
    public void releaseSceneView() {
        view = null;
        sceneDepth(null);
    }
    public void scene(@Nullable RenderTarget value) { sceneDepth(value == null ? null : value.getDepthTextureView()); }
    public void sceneDepth(@Nullable GpuTextureView value) { sceneInputs(value, false); }
    void sceneInputs(@Nullable GpuTextureView value, boolean outsideScene) {
        sceneDepth = value;
        this.outsideScene = outsideScene;
    }

    static boolean sceneDepthBinding(ShaderBinding binding) {
        return binding instanceof ShaderBinding.SampledTexture sampled
                && (sampled.name().equals("SceneTexturesStruct_SceneDepthTexture")
                || sampled.name().startsWith("SceneTexturesStruct_SceneDepthTexture_"));
    }

    @Override public String variant(int stageIndex) {
        var variants = shaders.stages().get(stageIndex);
        if (variants != null && (grid2DCollections.active() || grid3DCollections.active())) {
            return variants.entrySet().stream().filter(entry -> grid2DCollections.matches(stageIndex, entry.getValue().shader())
                    && grid3DCollections.matches(stageIndex, entry.getValue().shader())).map(Map.Entry::getKey).min(String::compareTo)
                    .orElseThrow(() -> new IllegalStateException("No Niagara Grid Collection variant matches stage " + stageIndex));
        }
        if (variants == null || variants.size() != 1)
            throw new IllegalArgumentException("Niagara GPU stage requires an explicit shader variant: " + stageIndex);
        return variants.keySet().iterator().next();
    }

    @Override public GpuBufferSlice dummyInput() { return programs.dummyBuffer().slice(); }

    @Override public NiagaraGpuSimulation.@Nullable InterfaceIteration iteration(CommandEncoder encoder,
            NiagaraGpuStageSchedule.Invocation stage, int iteration, int currentParticles, ShaderProgram shader) {
        String name = SourceNames.canonical(stage.iterationDataInterface());
        if (neighborGrids.hasIteration(name)) return stage.indirect() ? null : neighborGrids.iteration(name);
        if (neighborQueries.hasIteration(name)) return stage.indirect() ? null : neighborQueries.iteration(name);
        if (rasterizationGrids.hasIteration(name)) return stage.indirect() ? null : rasterizationGrids.iteration(name);
        if (velocityGrids.hasIteration(name)) return stage.indirect() ? null : velocityGrids.iteration(name);
        if (grid3DCollections.hasIteration(name)) return stage.indirect() ? null : grid3DCollections.iteration(name);
        if (grid2DCollections.hasIteration(name)) return stage.indirect() ? null : grid2DCollections.iteration(name);
        if (intRenderTargets.hasIteration(name)) return stage.indirect() ? null : intRenderTargets.iteration(name);
        var splats = gaussianSplatWriters.iteration(name);
        if (splats != null) return stage.indirect() ? null : splats;
        var renderArray = findProxyIteration(name, dimensionalTargetNames, this::selectedDimensionalTarget);
        if (renderArray != null) {
            var control = dimensionalTargets().control(selectedDimensionalTarget(renderArray));
            return new NiagaraGpuSimulation.InterfaceIteration(
                    new NiagaraGpuDispatchGrid.Extent(control.width(), control.height(), control.extentZ()), null, -1);
        }
        String target = findProxyIteration(name, targetNames, this::selectedImage);
        if (target != null) {
            var control = renderTargets.control(selectedImage(target));
            return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(control.width(), control.height(), 1),
                    null, -1);
        }
        var reader = findProxyIteration(name, particleReaderNames, this::selectedParticleReader);
        if (reader != null) return stage.indirect() ? null : reader.reader().iteration();
        String array = findProxyIteration(name, arrayNames, key -> selected(key, arrayOriginals));
        if (array != null) return stage.indirect() ? null : arrays.iteration(selected(array, arrayOriginals));
        String id = findProxyIteration(name, counterNames, key -> selected(key, counterOriginals));
        if (id == null) {
            // The object can still be bound under a different proxy name. UE runs its stage callbacks
            // but skips the dispatch when the alternate iteration proxy cannot be found.
            if (declaredProxyIterations.contains(name) || targetNames.containsKey(name) || dimensionalTargetNames.containsKey(name)
                    || particleReaderNames.containsKey(name) || arrayNames.containsKey(name) || counterNames.containsKey(name)
                    || gaussianSplatWriters.declaresIteration(name)) return null;
            throw new IllegalArgumentException("Unsupported Niagara GPU iteration interface " + stage.iterationDataInterface());
        }
        if (!stage.indirect()) return new NiagaraGpuSimulation.InterfaceIteration(
                new NiagaraGpuDispatchGrid.Extent(1, 1, 1), null, -1);
        return counters.iteration(encoder, selected(id, counterOriginals), shader);
    }

    private String selectedParticleReader(ParticleReaderBinding binding) {
        return interfaceSelections.getOrDefault(binding.key(), binding.original());
    }

    private <T> @Nullable T findProxyIteration(String name, Map<String, T> declared, Function<T, String> selectedIdentity) {
        T matching = declared.get(name);
        if (proxySourceNames.isEmpty()) return matching;
        if (matching != null && name.equals(proxySourceNames.getOrDefault(selectedIdentity.apply(matching), name)))
            return matching;
        for (T candidate : declared.values()) {
            if (name.equals(proxySourceNames.get(selectedIdentity.apply(candidate)))) return candidate;
        }
        return null;
    }

    @Override public Map<String, GpuBufferSlice> curveOverrides() { return curveBindings == null ? Map.of() : curveBindings.overrides(); }
    @Override public @Nullable byte[] value(String name) {
        if (!occlusionSymbols.isEmpty()) {
            var clouds = sceneInputs().clouds();
            for (String symbol : occlusionSymbols) {
                if (name.equals(symbol + "_CloudVolumetricTextureUVScale"))
                    return pair(clouds == null ? 1f : clouds.scaleX(), clouds == null ? 1f : clouds.scaleY());
                if (name.equals(symbol + "_CloudVolumetricTextureUVMax"))
                    return pair(clouds == null ? 1f : clouds.maxX(), clouds == null ? 1f : clouds.maxY());
            }
        }
        var fieldValue = distanceField().value(name);
        if (fieldValue != null) return fieldValue;
        byte[] value = asyncTraces.value(name);
        if (value != null) return value;
        value = objectProperties.value(name);
        if (value != null) return value;
        value = propertyInterfaces.value(name);
        if (value != null) return value;
        value = actorComponents.value(name);
        if (value != null) return value;
        value = staticMeshes.value(name);
        if (value != null) return value;
        value = programs.sharedMeshFields().value(name);
        if (value != null) return value;
        value = debugLines == null ? null : debugLines.value(name);
        if (value != null) return value;
        value = skeletalMeshes.value(name);
        if (value != null) return value;
        value = particleExports.value(name);
        if (value != null) return value;
        value = dataChannelReads.value(name);
        if (value != null) return value;
        value = dataChannelWrites.value(name);
        if (value != null) return value;
        value = audioAnalysis.value(name);
        if (value != null) return value;
        value = simCacheReaders.value(name);
        if (value != null) return value;
        value = sparseVolumes.value(name);
        if (value != null) return value;
        value = volumeCaches.value(name);
        if (value != null) return value;
        value = physicsAssets.value(name);
        if (value != null) return value;
        value = rigidMeshes.value(name);
        if (value != null) return value;
        value = hairStrands.value(name);
        if (value != null) return value;
        value = chaosDestruction.value(name);
        if (value != null) return value;
        value = geometryCollections.value(name);
        if (value != null) return value;
        value = physicsField.value(name);
        if (value != null) return value;
        value = sceneCaptures.value(name);
        if (value != null) return value;
        value = landscapes.value(name);
        if (value != null) return value;
        value = virtualTextures.value(name);
        if (value != null) return value;
        value = memoryBuffers.value(name);
        if (value != null) return value;
        value = neighborGrids.value(name);
        if (value != null) return value;
        value = neighborQueries.value(name);
        if (value != null) return value;
        value = rasterizationGrids.value(name);
        if (value != null) return value;
        value = velocityGrids.value(name);
        if (value != null) return value;
        value = grid3DCollections.value(name);
        if (value != null) return value;
        value = grid2DCollections.value(name);
        if (value != null) return value;
        value = dataTables.value(name);
        if (value != null) return value;
        value = gaussianSplatReaders.value(name);
        if (value != null) return value;
        value = gaussianSplatWriters.value(name);
        if (value != null) return value;
        value = intDistributions.value(name);
        if (value != null) return value;
        value = intRenderTargets.value(name);
        if (value != null) return value;
        value = socketReaders.value(name);
        if (value != null) return value;
        value = splines.value(name);
        if (value != null) return value;
        value = viewQueries.value(name);
        if (value != null) return value;
        value = dynamicMeshes.value(name);
        if (value != null) return value;
        value = vectorFieldBindings == null ? null : vectorFieldBindings.value(name);
        if (value != null) return value;
        value = curveBindings == null ? null : curveBindings.value(name);
        if (value != null) return value;
        var information = rendererInformation;
        value = information == null ? null : information.value(name);
        if (value != null) return value;
        value = vectorFields.value(name);
        if (value != null) return value;
        String platform = platformSetSymbols.get(name);
        if (platform != null) {
            String identity = selected(platform, platformOriginals);
            var values = platformValues;
            if (values == null) return platformSets.parameters(identity);
            int active = Objects.requireNonNull(values.get(identity), "PlatformSet not published for queued Niagara step: " + identity);
            return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(active).array();
        }
        for (var entry : targetSymbols.entrySet()) {
            if (name.equals(entry.getKey() + "_TextureSize")) return renderTargets.size(selectedImage(entry.getValue()));
            if (name.equals(entry.getKey() + "_MipLevels")) return renderTargets.mips(selectedImage(entry.getValue()));
        }
        for (var entry : dimensionalTargetSymbols.entrySet()) {
            if (name.equals(entry.getKey() + "_TextureSize"))
                return dimensionalTargets().size(selectedDimensionalTarget(entry.getValue()));
            if (name.equals(entry.getKey() + "_MipLevels"))
                return dimensionalTargets().mips(selectedDimensionalTarget(entry.getValue()));
        }
        value = meshInformation.value(name);
        if (value != null) return value;
        String curl = curlSymbols.get(name);
        if (curl != null && interfaceSelections.containsKey(curl))
            return Objects.requireNonNull(curlObjects, "GPU curl bindings require their source object directory")
                    .apply(interfaceSelections.get(curl));
        value = curlParameters.get(name);
        if (value != null) return value.clone();
        value = emitterProperties.value(name);
        if (value != null) return value;
        value = textureBindings.value(name);
        if (value != null) return value;
        for (var reader : particleReaders) {
            value = reader.value(name);
            if (value != null) return value;
        }
        if (view != null && (name.startsWith("View.") || name.startsWith("View_"))) {
            value = view.value(name);
            if (value != null) return value;
        }
        for (var entry : arraySymbols.entrySet())
            if (name.equals(entry.getKey() + "_ArrayBufferParams")) return arrays.parameters(selected(entry.getValue(), arrayOriginals));
        for (var entry : counterSymbols.entrySet())
            if (name.equals(entry.getKey() + "_CountOffset"))
                return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(counters.offset(selected(entry.getValue(), counterOriginals))).array();
        var camera = cameraSymbols.get(name);
        if (camera != null) {
            var source = cameraObjects;
            String identity = interfaceSelections.getOrDefault(camera.key(), camera.original());
            var modes = cameraModes;
            int mode = modes != null ? Objects.requireNonNull(modes.get(identity), "Camera not published for queued Niagara step: " + identity)
                    : source == null ? 0 : source.applyAsInt(identity);
            return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(mode).array();
        }
        return null;
    }

    private NiagaraSceneInputs sceneInputs() {
        return outsideScene || view == null ? NiagaraSceneInputs.EMPTY : view.sceneInputs();
    }

    private NiagaraGlobalDistanceField distanceField() {
        return NiagaraGlobalDistanceField.orEmpty(view == null ? null : view.sceneInputs().globalDistanceField());
    }

    private static byte[] pair(float x, float y) {
        return ByteBuffer.allocate(2 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).array();
    }

    private void bindOcclusionScene(ShaderBinding.SampledTexture binding, ComputeBindings.Builder resources) {
        if (occlusionSymbols.isEmpty()) return;
        var scene = sceneInputs();
        GpuTextureView texture = null;
        if (atmosphereTransmittanceBinding(binding)) {
            // A transmittance LUT depends on the atmospheric profile, not a live screen attachment.
            var atmosphere = view == null ? null : view.sceneInputs().atmosphere();
            texture = atmosphere == null ? programs.whiteSceneTexture() : atmosphere.transmittance();
        } else for (String symbol : occlusionSymbols) {
            if (!imageName(binding.name(), symbol + "_CloudVolumetricTexture")) continue;
            var clouds = scene.clouds();
            texture = clouds == null ? programs.blackAlphaOneTexture() : clouds.texture();
            break;
        }
        if (texture == null) return;
        if (binding.array() || binding.cube() || binding.volume())
            throw new IllegalArgumentException("Niagara occlusion scene binding requires a 2D view");
        if (binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.sampledImage(binding.name(), texture);
        else resources.texture(binding.name(), texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    static boolean atmosphereTransmittanceBinding(ShaderBinding.SampledTexture binding) {
        return imageName(binding.name(), "View_TransmittanceLutTexture");
    }

    private static boolean imageName(String actual, String source) { return actual.equals(source) || actual.startsWith(source + "_"); }

    private void bindGBufferScene(ShaderBinding.SampledTexture binding, ComputeBindings.Builder resources) {
        var field = NiagaraSceneInputs.Texture.shaderTexture(binding.name());
        if (field == null) {
            for (String symbol : gBufferSymbols) {
                if (!imageName(binding.name(), symbol + "_VelocityTexture")) continue;
                field = NiagaraSceneInputs.Texture.GBUFFER_VELOCITY;
                break;
            }
        }
        if (field == null) return;
        if (binding.array() || binding.cube() || binding.volume())
            throw new IllegalArgumentException("Niagara GBuffer scene binding requires a 2D view");
        var texture = sceneInputs().textures().get(field);
        if (texture == null) texture = switch (field) {
            case GBUFFER_F -> programs.graySceneTexture();
            case SCREEN_SPACE_AO -> programs.whiteSceneTexture();
            case CUSTOM_DEPTH, PARTIAL_DEPTH -> programs.defaultSceneDepth(binding.format());
            case CUSTOM_STENCIL -> programs.zeroStencilTexture();
            default -> programs.blackTexture();
        };
        if (binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.sampledImage(binding.name(), texture);
        else resources.texture(binding.name(), texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    @Override public void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration) { }
    @Override public void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration, int destinationParticles) {
        volumeCaches.preStage(stage.stageIndex());
        if (stage.first() && iteration == 0) {
            physicsAssets.upload(tickEncoder);
            rigidMeshes.upload(tickEncoder);
            landscapes.preStage();
        }
        neighborGrids.clearOutputs(stage.stageIndex());
        rasterizationGrids.beginStage(stage.stageIndex());
        particleExports.preStage(destinationParticles);
    }
    @Override public void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration, int destinationParticles,
                                   NiagaraGpuDispatchGrid.Extent elements) {
        preStage(stage, iteration, destinationParticles);
        grid3DCollections.beginStage(stage.stageIndex(), elements);
        grid2DCollections.beginStage(stage.stageIndex(), elements);
    }
    @Override public void preStage(NiagaraGpuStageSchedule.Invocation stage, int iteration, int sourceParticles, int destinationParticles,
                                   NiagaraGpuDispatchGrid.Extent elements, NiagaraGpuComputeStage shader) {
        velocityGrids.beginStage(tickEncoder, shader, stage.first() && iteration == 0);
        hairStrands.beginStage(tickEncoder, shader);
        asyncTraces.prepare(shader, interfaceSelections, destinationParticles);
        preStage(stage, iteration, destinationParticles, elements);
        neighborQueries.beginStage(stage.stageIndex(), Math.max(sourceParticles, destinationParticles));
    }
    @Override public void finalizePreStage() { asyncTraces.finalizeRequests(tickEncoder); }
    @Override public void acquireSharedStageInputs(NiagaraGpuDataChannelWorlds.StageAccess access) {
        for (var acquire : dataChannelStages) acquire.accept(access);
    }
    @Override public void reset(Supplier<NiagaraGpuComputeStage> initialStage) {
        velocityGrids.reset(initialStage);
        neighborGrids.reset();
        neighborQueries.reset();
        rasterizationGrids.reset();
        grid3DCollections.reset();
        grid2DCollections.reset();
    }
    @Override public void bind(NiagaraGpuStageSchedule.Invocation stage, int iteration, ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var reader : particleReaders) {
            reader.prepare(stage);
            reader.bind(shader, resources);
        }
        if (vectorFieldBindings == null) vectorFields.bind(shader, resources);
        else {
            vectorFields.bind(shader, resources, vectorFieldBindings.overridden());
            vectorFieldBindings.bind(shader, resources);
        }
        var information = rendererInformation;
        meshInformation.bind(shader, resources, programs.dummyBuffer().slice(), information == null ? Map.of() : information.buffers());
        textureBindings.bind(shader, resources);
        objectProperties.bind(shader, resources);
        propertyInterfaces.bind(shader, resources);
        staticMeshes.bind(shader, resources);
        programs.sharedMeshFields().bind(shader, resources);
        if (debugLines != null) debugLines.bind(programs, shader, resources);
        skeletalMeshes.bind(shader, resources);
        particleExports.bind(tickEncoder, shader, resources);
        dataChannelReads.bind(tickEncoder, shader, resources);
        dataChannelWrites.bind(tickEncoder, shader, resources);
        audioAnalysis.bind(tickEncoder, shader, resources);
        simCacheReaders.bind(shader, resources);
        sparseVolumes.bind(shader, resources);
        volumeCaches.bind(shader, resources);
        physicsAssets.bind(shader, resources, programs.dummyBuffer().slice());
        rigidMeshes.bind(shader, resources);
        geometryCollections.bind(tickEncoder, shader, resources);
        chaosDestruction.bind(tickEncoder, shader, resources);
        hairStrands.bind(tickEncoder, shader, resources);
        physicsField.bind(tickEncoder, shader, resources);
        sceneCaptures.bind(shader, resources, programs::whiteSceneTexture);
        landscapes.bind(shader, resources, programs::blackTexture);
        virtualTextures.bind(shader, resources, programs::blackTexture);
        memoryBuffers.bind(shader, resources);
        neighborGrids.bind(shader, resources);
        neighborQueries.bind(shader, resources);
        rasterizationGrids.bind(shader, resources);
        velocityGrids.bind(shader, resources);
        grid3DCollections.bind(shader, resources);
        grid2DCollections.bind(shader, resources);
        dataTables.bind(shader, resources);
        gaussianSplatReaders.bind(shader, resources);
        gaussianSplatWriters.bind(shader, resources);
        intDistributions.bind(shader, resources);
        intRenderTargets.bind(shader, resources);
        socketReaders.bind(shader, resources);
        splines.bind(shader, resources);
        dynamicMeshes.bind(shader, resources);
        consoleVariables.bind(shader, resources);
        targetSymbols.forEach((symbol, id) -> renderTargets.bind(selectedImage(id), symbol, shader, resources));
        dimensionalTargetSymbols.forEach((symbol, binding) -> dimensionalTargets().bind(selectedDimensionalTarget(binding), symbol, shader, resources));
        for (var binding : shader.bindings()) {
            if (binding instanceof ShaderBinding.SampledTexture sampled) {
                bindOcclusionScene(sampled, resources);
                bindGBufferScene(sampled, resources);
                if (sampled.volume()) {
                    var volume = distanceField().texture(sampled.name(), programs);
                    if (volume != null) resources.volume(sampled.name(), volume, 0);
                }
            }
            if (binding instanceof ShaderBinding.SampledTexture sampled && sceneDepthBinding(sampled)) {
                var depth = outsideScene ? programs.defaultSceneDepth(sampled.format()) : sceneDepth;
                if (depth == null) throw new IllegalStateException("Niagara scene depth is unavailable for the current frame");
                if (sampled.form() == com.kltyton.lumenfx.client.render.compute.ShaderBinding.DescriptorForm.SAMPLED_IMAGE)
                    resources.sampledImage(sampled.name(), depth);
                else resources.texture(sampled.name(), depth,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                continue;
            }
            if (!(binding instanceof com.kltyton.lumenfx.client.render.compute.ShaderBinding.StorageBuffer storage)) continue;
            asyncTraces.bind(storage, resources);
            for (var entry : arraySymbols.entrySet()) {
                if (storage.name().equals(entry.getKey() + "_ArrayReadBuffer") || storage.name().equals(entry.getKey() + "_ArrayRWBuffer")) {
                    resources.storage(storage.name(), arrays.buffer(selected(entry.getValue(), arrayOriginals)), storage.access());
                    break;
                }
            }
        }
    }
    @Override public void postStage(NiagaraGpuStageSchedule.Invocation stage, int iteration) {
        neighborQueries.finishStage(tickEncoder);
        velocityGrids.finishStage(tickEncoder);
        grid3DCollections.finishStage();
        grid2DCollections.finishStage();
        particleExports.postStage(tickEncoder);
        dataChannelReads.endStage();
        dataChannelWrites.endStage();
        for (String id : selectedTargets()) renderTargets.postStage(tickEncoder, id);
    }
    @Override public void postSimulate(CommandEncoder encoder, boolean dataToRender) {
        hairStrands.postSimulate(encoder, dataToRender);
        grid3DCollections.postSimulate();
        grid2DCollections.postSimulate();
        for (String id : selectedTargets()) renderTargets.postSimulate(encoder, id);
        var selectedArrays = new java.util.LinkedHashSet<String>();
        for (String key : arrayNames.values()) selectedArrays.add(selected(key, arrayOriginals));
        for (String id : selectedArrays) arrays.postSimulate(encoder, id, arrayInput(id));
    }
    @Override public void finalizePostStage() { }
    private Set<String> selectedTargets() {
        var result = new java.util.LinkedHashSet<String>();
        for (String id : targetSymbols.values()) result.add(selectedImage(id));
        return result;
    }
}
