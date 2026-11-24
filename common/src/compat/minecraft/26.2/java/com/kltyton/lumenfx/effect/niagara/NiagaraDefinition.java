package com.kltyton.lumenfx.effect.niagara;

import com.kltyton.lumenfx.effect.niagara.component.NiagaraComponentPoolSettings;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraDebugParameters;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;

import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraEmitterScalability;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformSet;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability;
import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.niagara.interfaces.NiagaraGpuInterfaceFunctions;
import com.kltyton.lumenfx.effect.niagara.interfaces.debug.NiagaraPersistentDebugShape;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NumericStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public record NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                                List<Variable> systemLayout, List<Variable> spawnInputLayout,
                                List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                                List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                                List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                                NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                                @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                                @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                                @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions,
                                Map<Identifier, StaticMeshAsset> staticMeshInputs,
                                Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs,
                                Map<String, Channel> dataChannels, @Nullable String sourceSystem,
                                Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                                Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs,
                                @Nullable NiagaraDebugParameters debugParameters,
                                List<NiagaraPersistentDebugShape> persistentDebugShapes,
                                Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheInputs, NiagaraLwcSettings lwc,
                                NiagaraComponentDefaults component, @Nullable NiagaraSystemScalability scalability,
                                @Nullable NiagaraBounds initialStreamingBounds, NiagaraComponentPoolSettings componentPool,
                                Map<Identifier, NiagaraGaussianSplatAsset> gaussianSplatInputs) {
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs, @Nullable NiagaraDebugParameters debugParameters,
                             List<NiagaraPersistentDebugShape> persistentDebugShapes, Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheInputs,
                             NiagaraLwcSettings lwc, NiagaraComponentDefaults component, @Nullable NiagaraSystemScalability scalability,
                             @Nullable NiagaraBounds initialStreamingBounds, NiagaraComponentPoolSettings componentPool) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, debugParameters, persistentDebugShapes,
                volumeCacheInputs, lwc, component, scalability, initialStreamingBounds, componentPool, Map.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs, @Nullable NiagaraDebugParameters debugParameters,
                             List<NiagaraPersistentDebugShape> persistentDebugShapes, Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheInputs,
                             NiagaraLwcSettings lwc, NiagaraComponentDefaults component, @Nullable NiagaraSystemScalability scalability,
                             @Nullable NiagaraBounds initialStreamingBounds) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, debugParameters, persistentDebugShapes,
                volumeCacheInputs, lwc, component, scalability, initialStreamingBounds, NiagaraComponentPoolSettings.DEFAULT);
    }

    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs, @Nullable NiagaraDebugParameters debugParameters,
                             List<NiagaraPersistentDebugShape> persistentDebugShapes, Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheInputs,
                             NiagaraLwcSettings lwc, NiagaraComponentDefaults component, @Nullable NiagaraSystemScalability scalability) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, debugParameters, persistentDebugShapes,
                volumeCacheInputs, lwc, component, scalability, null);
    }

    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs, @Nullable NiagaraDebugParameters debugParameters,
                             List<NiagaraPersistentDebugShape> persistentDebugShapes, Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheInputs,
                             NiagaraLwcSettings lwc) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, debugParameters, persistentDebugShapes,
                volumeCacheInputs, lwc, NiagaraComponentDefaults.DEFAULT, null);
    }

    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs, @Nullable NiagaraDebugParameters debugParameters,
                             List<NiagaraPersistentDebugShape> persistentDebugShapes, Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheInputs) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, debugParameters, persistentDebugShapes,
                volumeCacheInputs, NiagaraLwcSettings.DEFAULT);
    }

    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs, @Nullable NiagaraDebugParameters debugParameters) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, debugParameters, List.of(), Map.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels,
                             @Nullable String sourceSystem, Map<Identifier, NiagaraSimulationCache> simCacheInputs,
                             Map<Identifier, SparseVolumeTextureAsset> sparseVolumeInputs) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, sourceSystem, simCacheInputs, sparseVolumeInputs, null);
    }

    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs, Map<String, Channel> dataChannels) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, dataChannels, null, Map.of(), Map.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs,
                             Map<Identifier, SkeletalMeshAsset> skeletalMeshInputs) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs,
                skeletalMeshInputs, Map.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions, Map<Identifier, StaticMeshAsset> staticMeshInputs) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, staticMeshInputs, Map.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces,
                             @Nullable NiagaraGpuInterfaceFunctions gpuInterfaceFunctions) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, gpuInterfaceFunctions, Map.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores, List<MutableInterfaceInstance> mutableInterfaces) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, mutableInterfaces, null);
    }
    public record InstanceStores(ScriptParameterStoreDefinition instance, ScriptParameterStoreDefinition override) { }
    public record MutableInterfaceInstance(String interfaceId, String type, String parameterName,
                                           boolean usedCpu, boolean usedGpu, JsonObject data) {
        public MutableInterfaceInstance {
            if (interfaceId.isEmpty()) throw new IllegalArgumentException("Mutable interface has no identity");
            data = data.deepCopy();
        }
        @Override public JsonObject data() { return data.deepCopy(); }
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric,
                             @Nullable InstanceStores instanceStores) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, instanceStores, List.of());
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets, NumericStoreDefinition instanceNumeric) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, instanceNumeric, null);
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms,
                             @Nullable List<NiagaraPlatformSet> platformRefreshSets) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings, updateInputBindings,
                systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled, systemFixedBounds, timing, renderTargets,
                platforms, platformRefreshSets, NumericStoreDefinition.EMPTY);
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets, NiagaraPlatformSet platforms) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings,
                updateInputBindings, systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled,
                systemFixedBounds, timing, renderTargets, platforms, null);
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing, List<RenderTargetInstance> renderTargets) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings,
                updateInputBindings, systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled,
                systemFixedBounds, timing, renderTargets, NiagaraPlatformSet.ALL);
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds,
                             NiagaraTiming timing) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings,
                updateInputBindings, systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled,
                systemFixedBounds, timing, List.of());
    }
    public record RenderTargetInstance(String interfaceId, boolean usedCpu, boolean usedGpu, JsonObject data) {
        public RenderTargetInstance {
            java.util.Objects.requireNonNull(interfaceId);
            data = data.deepCopy();
        }
        @Override public JsonObject data() { return data.deepCopy(); }
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters, boolean systemFixedBoundsEnabled, @Nullable NiagaraBounds systemFixedBounds) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings,
                updateInputBindings, systemRandomSeed, systemDeterministic, emitters, systemFixedBoundsEnabled,
                systemFixedBounds, NiagaraTiming.DEFAULT);
    }
    public NiagaraDefinition(VectorProgram systemSpawn, VectorProgram systemUpdate,
                             List<Variable> systemLayout, List<Variable> spawnInputLayout,
                             List<Variable> updateInputLayout, List<Binding> spawnInputBindings,
                             List<Binding> updateInputBindings, int systemRandomSeed, boolean systemDeterministic,
                             List<Emitter> emitters) {
        this(systemSpawn, systemUpdate, systemLayout, spawnInputLayout, updateInputLayout, spawnInputBindings,
                updateInputBindings, systemRandomSeed, systemDeterministic, emitters, false, null);
    }
    public static final String SPAWN_INFO_TYPE = "/Script/Niagara.NiagaraSpawnInfo";
    public static final String POSITION_TYPE = "/Script/Niagara.NiagaraPosition";
    public static final String EXECUTION_STATE_TYPE = "/Script/Niagara.ENiagaraExecutionState";
    public record Binding(int emitter, int runtimeOffset, int type, int component) { }
    public record Variable(String name, int floatStart, int floatCount, int intStart, int intCount,
                           int halfStart, int halfCount, String sourceType, @Nullable ComponentOffsets offsets) {
        public Variable(String name, int floatStart, int floatCount, int intStart, int intCount,
                        int halfStart, int halfCount, String sourceType) {
            this(name, floatStart, floatCount, intStart, intCount, halfStart, halfCount, sourceType, null);
        }
        public Variable {
            if (offsets != null && (offsets.floats().size() != floatCount || offsets.integers().size() != intCount
                    || offsets.halves().size() != halfCount)) throw new IllegalArgumentException("Dataset component offsets differ from its layout");
        }
    }
    public record ComponentOffsets(List<Integer> floats, List<Integer> integers, List<Integer> halves) {
        public ComponentOffsets {
            floats = List.copyOf(floats); integers = List.copyOf(integers); halves = List.copyOf(halves);
            if (floats.stream().anyMatch(value -> value < 0) || integers.stream().anyMatch(value -> value < 0)
                    || halves.stream().anyMatch(value -> value < 0)) throw new IllegalArgumentException("Negative dataset component byte offset");
        }
    }
    public record Attribute(String name, int type, int component, int count, String sourceType) { }
    public record EventGenerator(String id, int maximumEventsPerFrame, List<Variable> layout) {
        public EventGenerator { layout = List.copyOf(layout); }
    }
    public record EventHandler(String executionMode, String sourceEmitterId, String sourceEventName,
                               long spawnNumber, long minimumSpawnNumber, long maximumEventsPerFrame,
                               boolean randomSpawnNumber, boolean updateAttributeInitialValues, @Nullable VectorProgram program) { }
    public record EmitterDependency(String mode, String emitterName) { }
    public record RendererAttributeBinding(String name, boolean particle, int channelMask, @Nullable String sourceType) {
        public RendererAttributeBinding(String name, boolean particle, int channelMask) { this(name, particle, channelMask, null); }
        public RendererAttributeBinding(String name, boolean particle) { this(name, particle, 15); }
        public RendererAttributeBinding {
            if ((channelMask & ~15) != 0) throw new IllegalArgumentException("Invalid Niagara renderer channel mask");
        }
    }
    public sealed interface Simulation permits CpuSimulation, GpuSimulation, StatelessSimulation, DisabledSimulation { }
    public record DisabledSimulation(boolean stateless, boolean hasEmitter) implements Simulation {
        public DisabledSimulation {
            if (stateless && hasEmitter) throw new IllegalArgumentException("Disabled analytic instance cannot own a standard emitter pointer");
        }
    }
    public record CpuSimulation(VectorProgram spawn, VectorProgram update, @Nullable Integer fixedParticleCount,
                                @Nullable Integer manualAllocationEstimate, boolean requiresPersistentIds) implements Simulation {
        public CpuSimulation(VectorProgram spawn, VectorProgram update) { this(spawn, update, null, null, false); }
        public CpuSimulation(VectorProgram spawn, VectorProgram update, @Nullable Integer fixedParticleCount,
                             @Nullable Integer manualAllocationEstimate) {
            this(spawn, update, fixedParticleCount, manualAllocationEstimate, false);
        }

        public CpuSimulation {
            if (fixedParticleCount != null && fixedParticleCount < 0
                    || manualAllocationEstimate != null && manualAllocationEstimate < 0)
                throw new IllegalArgumentException("Negative Niagara particle allocation count");
        }

        public boolean automaticAllocation() { return fixedParticleCount == null && manualAllocationEstimate == null; }

        public int allocationEstimate() {
            if (fixedParticleCount != null) return fixedParticleCount;
            return manualAllocationEstimate == null ? 0 : manualAllocationEstimate;
        }

        public int maximumParticles(int storageLimit) {
            return fixedParticleCount == null ? storageLimit : Math.min(storageLimit, fixedParticleCount);
        }
    }
    public record HostExecutionContext(String usage, String usageId, int eventIndex,
                                       ScriptParameterStoreDefinition parameters, List<NiagaraCollectionBinding> collections) {
        public HostExecutionContext { collections = List.copyOf(collections); }
    }
    public record GpuSimulation(Identifier hlslSource, String shaderPipeline, JsonObject metadata,
                                @Nullable ScriptParameterStoreDefinition parameterStore,
                                List<HostExecutionContext> hostContexts) implements Simulation {
        public GpuSimulation(Identifier hlslSource, String shaderPipeline, JsonObject metadata,
                             @Nullable ScriptParameterStoreDefinition parameterStore) {
            this(hlslSource, shaderPipeline, metadata, parameterStore, List.of());
        }
        public GpuSimulation(Identifier hlslSource, String shaderPipeline, JsonObject metadata) {
            this(hlslSource, shaderPipeline, metadata, null);
        }
        public GpuSimulation {
            metadata = metadata.deepCopy();
            hostContexts = List.copyOf(hostContexts);
            if (parameterStore != null) metadata.remove("execution_store");
            if (!hostContexts.isEmpty()) metadata.remove("host_contexts");
        }
        @Override public JsonObject metadata() { return metadata.deepCopy(); }
    }
    public record StatelessSimulation(String target, JsonObject metadata) implements Simulation {
        public StatelessSimulation {
            if (!target.equals("CPUSim") && !target.equals("GPUComputeSim"))
                throw new IllegalArgumentException("Invalid stateless simulation target " + target);
            metadata = metadata.deepCopy();
        }
        @Override public JsonObject metadata() { return metadata.deepCopy(); }
    }
    public record SurfaceMaterialBinding(String parameter, String variant) { }

    public record Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                           boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                           float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                           String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                           String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                           boolean sortOnlyWhenTranslucent, String customSortBinding, boolean customSortParticleBinding, String sortPrecision,
                           boolean distanceCulling, float minCameraDistance, float maxCameraDistance,
                           int rendererVisibility, String visibilityBinding, boolean visibilityParticleBinding,
                           @Nullable String enabledBinding, String sourceMode,
                           Map<String, RendererAttributeBinding> attributeBindings, String alignment, String facingMode,
                           float pivotX, float pivotY, float facingBlendMin, float facingBlendMax, com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                           com.kltyton.lumenfx.effect.render.component.@Nullable ComponentRendererSettings component,
                           @Nullable SurfaceMaterialBinding surfaceMaterialBinding, GpuTranslucentLatency gpuTranslucentLatency,
                           boolean allowInCullProxies) {
        public Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                        boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                        float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                        String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                        String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                        boolean sortOnlyWhenTranslucent, String customSortBinding, boolean customSortParticleBinding, String sortPrecision,
                        boolean distanceCulling, float minCameraDistance, float maxCameraDistance,
                        int rendererVisibility, String visibilityBinding, boolean visibilityParticleBinding,
                        @Nullable String enabledBinding, String sourceMode, Map<String, RendererAttributeBinding> attributeBindings,
                        String alignment, String facingMode, float pivotX, float pivotY, float facingBlendMin, float facingBlendMax,
                        com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                        com.kltyton.lumenfx.effect.render.component.@Nullable ComponentRendererSettings component,
                        @Nullable SurfaceMaterialBinding surfaceMaterialBinding, GpuTranslucentLatency gpuTranslucentLatency) {
            this(name, coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, uvScaleBinding, uvScaleParticleBinding, dynamicParameterBinding, dynamicParameterParticleBinding,
                    dynamicParameterChannelMask, ribbonLinkBinding, ribbonIdBinding, ribbonUniqueFallback, sortOnlyWhenTranslucent,
                    customSortBinding, customSortParticleBinding, sortPrecision, distanceCulling, minCameraDistance, maxCameraDistance,
                    rendererVisibility, visibilityBinding, visibilityParticleBinding, enabledBinding, sourceMode, attributeBindings,
                    alignment, facingMode, pivotX, pivotY, facingBlendMin, facingBlendMax, motion, component, surfaceMaterialBinding,
                    gpuTranslucentLatency, true);
        }

        public Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                        boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                        float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                        String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                        String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                        boolean sortOnlyWhenTranslucent, String customSortBinding, boolean customSortParticleBinding, String sortPrecision,
                        boolean distanceCulling, float minCameraDistance, float maxCameraDistance,
                        int rendererVisibility, String visibilityBinding, boolean visibilityParticleBinding,
                        @Nullable String enabledBinding, String sourceMode, Map<String, RendererAttributeBinding> attributeBindings,
                        String alignment, String facingMode, float pivotX, float pivotY, float facingBlendMin, float facingBlendMax,
                        com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                        com.kltyton.lumenfx.effect.render.component.@Nullable ComponentRendererSettings component,
                        @Nullable SurfaceMaterialBinding surfaceMaterialBinding) {
            this(name, coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, uvScaleBinding, uvScaleParticleBinding, dynamicParameterBinding, dynamicParameterParticleBinding,
                    dynamicParameterChannelMask, ribbonLinkBinding, ribbonIdBinding, ribbonUniqueFallback, sortOnlyWhenTranslucent,
                    customSortBinding, customSortParticleBinding, sortPrecision, distanceCulling, minCameraDistance, maxCameraDistance,
                    rendererVisibility, visibilityBinding, visibilityParticleBinding, enabledBinding, sourceMode, attributeBindings,
                    alignment, facingMode, pivotX, pivotY, facingBlendMin, facingBlendMax, motion, component, surfaceMaterialBinding,
                    GpuTranslucentLatency.ProjectDefault);
        }

        public Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                        boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                        float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                        String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                        String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                        boolean sortOnlyWhenTranslucent, String customSortBinding, boolean customSortParticleBinding, String sortPrecision,
                        boolean distanceCulling, float minCameraDistance, float maxCameraDistance,
                        int rendererVisibility, String visibilityBinding, boolean visibilityParticleBinding,
                        @Nullable String enabledBinding, String sourceMode,
                        Map<String, RendererAttributeBinding> attributeBindings, String alignment, String facingMode,
                        float pivotX, float pivotY, float facingBlendMin, float facingBlendMax,
                        com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                        com.kltyton.lumenfx.effect.render.component.@Nullable ComponentRendererSettings component) {
            this(name, coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, uvScaleBinding, uvScaleParticleBinding, dynamicParameterBinding, dynamicParameterParticleBinding,
                    dynamicParameterChannelMask, ribbonLinkBinding, ribbonIdBinding, ribbonUniqueFallback, sortOnlyWhenTranslucent,
                    customSortBinding, customSortParticleBinding, sortPrecision, distanceCulling, minCameraDistance, maxCameraDistance,
                    rendererVisibility, visibilityBinding, visibilityParticleBinding, enabledBinding, sourceMode, attributeBindings,
                    alignment, facingMode, pivotX, pivotY, facingBlendMin, facingBlendMax, motion, component, null);
        }
        public Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                        boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                        float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                        String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                        String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                        boolean sortOnlyWhenTranslucent, String customSortBinding, boolean customSortParticleBinding, String sortPrecision,
                        boolean distanceCulling, float minCameraDistance, float maxCameraDistance,
                        int rendererVisibility, String visibilityBinding, boolean visibilityParticleBinding,
                        @Nullable String enabledBinding, String sourceMode,
                        Map<String, RendererAttributeBinding> attributeBindings, String alignment, String facingMode,
                        float pivotX, float pivotY, float facingBlendMin, float facingBlendMax,
                        com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion) {
            this(name, coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, uvScaleBinding, uvScaleParticleBinding, dynamicParameterBinding, dynamicParameterParticleBinding,
                    dynamicParameterChannelMask, ribbonLinkBinding, ribbonIdBinding, ribbonUniqueFallback, sortOnlyWhenTranslucent,
                    customSortBinding, customSortParticleBinding, sortPrecision, distanceCulling, minCameraDistance, maxCameraDistance,
                    rendererVisibility, visibilityBinding, visibilityParticleBinding, enabledBinding, sourceMode, attributeBindings,
                    alignment, facingMode, pivotX, pivotY, facingBlendMin, facingBlendMax, motion, null);
        }
        public Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                           boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                           float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                           String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                           String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                           boolean sortOnlyWhenTranslucent, String customSortBinding, boolean customSortParticleBinding, String sortPrecision,
                           boolean distanceCulling, float minCameraDistance, float maxCameraDistance,
                           int rendererVisibility, String visibilityBinding, boolean visibilityParticleBinding,
                           @Nullable String enabledBinding, String sourceMode,
                           Map<String, RendererAttributeBinding> attributeBindings, String alignment, String facingMode,
                           float pivotX, float pivotY, float facingBlendMin, float facingBlendMax) {
            this(name, coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding, subImageDefault, uvScaleBinding, uvScaleParticleBinding, dynamicParameterBinding, dynamicParameterParticleBinding, dynamicParameterChannelMask, ribbonLinkBinding, ribbonIdBinding, ribbonUniqueFallback, sortOnlyWhenTranslucent, customSortBinding, customSortParticleBinding, sortPrecision, distanceCulling, minCameraDistance, maxCameraDistance, rendererVisibility, visibilityBinding, visibilityParticleBinding, enabledBinding, sourceMode, attributeBindings, alignment, facingMode, pivotX, pivotY, facingBlendMin, facingBlendMax, com.kltyton.lumenfx.effect.render.ParticleMotionSettings.DEFAULT);
        }
        public Renderer(String name, SpriteCoverage coverage, float subImageColumns, float subImageRows,
                        boolean subImageBlend, String subImageBinding, boolean subImageParticleBinding,
                        float subImageDefault, String uvScaleBinding, boolean uvScaleParticleBinding,
                        String dynamicParameterBinding, boolean dynamicParameterParticleBinding, int dynamicParameterChannelMask,
                        String ribbonLinkBinding, String ribbonIdBinding, boolean ribbonUniqueFallback,
                        boolean sortOnlyWhenTranslucent, String customSortBinding) {
            this(name, coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, uvScaleBinding, uvScaleParticleBinding, dynamicParameterBinding,
                    dynamicParameterParticleBinding, dynamicParameterChannelMask, ribbonLinkBinding, ribbonIdBinding,
                    ribbonUniqueFallback, sortOnlyWhenTranslucent, customSortBinding, true, "Default", false, 0, 1000,
                    0, "Particles.VisibilityTag", true, null, "Particles", Map.of(), "Unaligned", "FaceCamera", .5f, .5f, 0, 0);
        }
        public Renderer {
            java.util.Objects.requireNonNull(motion);
            java.util.Objects.requireNonNull(gpuTranslucentLatency);
            if (!Float.isFinite(subImageColumns) || !Float.isFinite(subImageRows) || subImageColumns < 1 || subImageRows < 1
                    || !Float.isFinite(subImageDefault) || (dynamicParameterChannelMask & ~15) != 0)
                throw new IllegalArgumentException("Invalid Niagara sprite parameters for " + name);
            if (!List.of("Default", "Low", "High").contains(sortPrecision)
                    || !Float.isFinite(minCameraDistance) || !Float.isFinite(maxCameraDistance))
                throw new IllegalArgumentException("Invalid Niagara renderer visibility for " + name);
            if (!List.of("Particles", "Emitter").contains(sourceMode))
                throw new IllegalArgumentException("Invalid Niagara renderer source mode for " + name);
            if (!List.of("Unaligned", "VelocityAligned", "CustomAlignment", "Automatic").contains(alignment)
                    || !List.of("FaceCamera", "FaceCameraPlane", "CustomFacingVector", "FaceCameraPosition",
                    "FaceCameraDistanceBlend", "Automatic").contains(facingMode)
                    || !Float.isFinite(pivotX) || !Float.isFinite(pivotY)
                    || !Float.isFinite(facingBlendMin) || !Float.isFinite(facingBlendMax))
                throw new IllegalArgumentException("Invalid Niagara sprite orientation for " + name);
            attributeBindings = Map.copyOf(attributeBindings);
        }
    }
    public enum GpuTranslucentLatency { ProjectDefault, Immediate, Latent }
    public record Emitter(String name, String sourceName, String handleId, int randomSeed, boolean deterministic,
                          Simulation simulation, List<Attribute> attributes, List<String> spawnInfo,
                          SpriteCoverage coverage, float subImageColumns, float subImageRows, boolean subImageBlend,
                          String subImageBinding, boolean subImageParticleBinding, float subImageDefault,
                          List<Renderer> renderers, boolean localSpace, int maximumParticles,
                          List<EventGenerator> spawnEvents, List<EventGenerator> updateEvents,
                          List<String> sharedEventIds, List<EventHandler> eventHandlers, List<EmitterDependency> dependencies,
                          String boundsMode, @Nullable NiagaraBounds fixedBounds, NiagaraEmitterScalability scalability,
                          RendererParameterDefaults rendererBindings) {
        public Emitter(String name, String sourceName, String handleId, int randomSeed, boolean deterministic,
                       Simulation simulation, List<Attribute> attributes, List<String> spawnInfo,
                       SpriteCoverage coverage, float subImageColumns, float subImageRows, boolean subImageBlend,
                       String subImageBinding, boolean subImageParticleBinding, float subImageDefault,
                       List<Renderer> renderers, boolean localSpace, int maximumParticles,
                       List<EventGenerator> spawnEvents, List<EventGenerator> updateEvents,
                       List<String> sharedEventIds, List<EventHandler> eventHandlers, List<EmitterDependency> dependencies,
                       String boundsMode, @Nullable NiagaraBounds fixedBounds, NiagaraEmitterScalability scalability) {
            this(name, sourceName, handleId, randomSeed, deterministic, simulation, attributes, spawnInfo, coverage,
                    subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding, subImageDefault,
                    renderers, localSpace, maximumParticles, spawnEvents, updateEvents, sharedEventIds, eventHandlers, dependencies,
                    boundsMode, fixedBounds, scalability, RendererParameterDefaults.EMPTY);
        }
        public Emitter(String name, String sourceName, String handleId, int randomSeed, boolean deterministic,
                       Simulation simulation, List<Attribute> attributes, List<String> spawnInfo,
                       SpriteCoverage coverage, float subImageColumns, float subImageRows, boolean subImageBlend,
                       String subImageBinding, boolean subImageParticleBinding, float subImageDefault,
                       List<Renderer> renderers, boolean localSpace, int maximumParticles,
                       List<EventGenerator> spawnEvents, List<EventGenerator> updateEvents,
                       List<String> sharedEventIds, List<EventHandler> eventHandlers, List<EmitterDependency> dependencies,
                       String boundsMode, @Nullable NiagaraBounds fixedBounds) {
            this(name, sourceName, handleId, randomSeed, deterministic, simulation, attributes, spawnInfo, coverage,
                    subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding, subImageDefault,
                    renderers, localSpace, maximumParticles, spawnEvents, updateEvents, sharedEventIds, eventHandlers, dependencies,
                    boundsMode, fixedBounds, NiagaraEmitterScalability.DEFAULT);
        }
        public Emitter(String name, String sourceName, int randomSeed, boolean deterministic,
                       Simulation simulation, List<Attribute> attributes, List<String> spawnInfo,
                       SpriteCoverage coverage, float subImageColumns, float subImageRows, boolean subImageBlend,
                       String subImageBinding, boolean subImageParticleBinding, float subImageDefault,
                       List<Renderer> renderers, boolean localSpace, int maximumParticles) {
            this(name, sourceName, "", randomSeed, deterministic, simulation, attributes, spawnInfo, coverage,
                    subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, renderers, localSpace, maximumParticles, List.of(), List.of(), List.of(), List.of(), List.of(),
                    "Dynamic", NiagaraBounds.DEFAULT);
        }
        public Emitter {
            java.util.Objects.requireNonNull(scalability);
            java.util.Objects.requireNonNull(rendererBindings);
            attributes = List.copyOf(attributes);
            spawnInfo = List.copyOf(spawnInfo);
            renderers = List.copyOf(renderers);
            spawnEvents = List.copyOf(spawnEvents);
            updateEvents = List.copyOf(updateEvents);
            sharedEventIds = List.copyOf(sharedEventIds);
            eventHandlers = List.copyOf(eventHandlers);
            dependencies = List.copyOf(dependencies);
            if (!List.of("Dynamic", "Fixed", "Programmable").contains(boundsMode))
                throw new IllegalArgumentException("Invalid Niagara emitter bounds for " + name);
            if (!Float.isFinite(subImageColumns) || !Float.isFinite(subImageRows) || subImageColumns < 1 || subImageRows < 1)
                throw new IllegalArgumentException("Invalid Niagara SubUV dimensions for " + name);
            if (!Float.isFinite(subImageDefault)) throw new IllegalArgumentException("Invalid Niagara SubImageIndex default for " + name);
        }
        public VectorProgram spawn() { return ((CpuSimulation) simulation).spawn(); }
        public VectorProgram update() { return ((CpuSimulation) simulation).update(); }

        public boolean requiresPersistentIdStorage() {
            if (simulation instanceof CpuSimulation cpu) {
                if (cpu.requiresPersistentIds()) return true;
            } else if (simulation instanceof GpuSimulation gpu) {
                JsonObject settings = gpu.metadata().getAsJsonObject("emitter_settings");
                if (settings != null && settings.has("bRequiresPersistentIDs")
                        && settings.get("bRequiresPersistentIDs").getAsBoolean()) return true;
            } else return false;
            return attributes.stream().anyMatch(attribute -> attribute.type() == 1 && attribute.count() == 2
                    && SourceNames.equal(attribute.sourceType(), "/Script/Niagara.NiagaraID")
                    && (SourceNames.equal(attribute.name(), "ID") || SourceNames.equal(attribute.name(), "Particles.ID")));
        }

        public Emitter inLocalSpace() {
            if (localSpace) return this;
            return new Emitter(name, sourceName, handleId, randomSeed, deterministic, simulation, attributes, spawnInfo,
                    coverage, subImageColumns, subImageRows, subImageBlend, subImageBinding, subImageParticleBinding,
                    subImageDefault, renderers, true, maximumParticles, spawnEvents, updateEvents, sharedEventIds,
                    eventHandlers, dependencies, boundsMode, fixedBounds, scalability, rendererBindings);
        }
    }
    public NiagaraDefinition {
        java.util.Objects.requireNonNull(component, "component");
        volumeCacheInputs = Map.copyOf(volumeCacheInputs);
        gaussianSplatInputs = Map.copyOf(gaussianSplatInputs);
        persistentDebugShapes = List.copyOf(persistentDebugShapes);
        simCacheInputs = Map.copyOf(simCacheInputs);
        sparseVolumeInputs = Map.copyOf(sparseVolumeInputs);
        staticMeshInputs = Map.copyOf(staticMeshInputs);
        skeletalMeshInputs = Map.copyOf(skeletalMeshInputs);
        dataChannels = Map.copyOf(dataChannels);
        java.util.Objects.requireNonNull(instanceNumeric);
        java.util.Objects.requireNonNull(platforms, "Niagara system platform set");
        java.util.Objects.requireNonNull(timing, "Niagara system timing");
        java.util.Objects.requireNonNull(lwc, "Niagara large world coordinate settings");
        systemLayout = List.copyOf(systemLayout);
        spawnInputLayout = List.copyOf(spawnInputLayout);
        updateInputLayout = List.copyOf(updateInputLayout);
        spawnInputBindings = List.copyOf(spawnInputBindings);
        updateInputBindings = List.copyOf(updateInputBindings);
        emitters = List.copyOf(emitters);
        if (debugParameters != null && debugParameters.emitterNames().size() != emitters.size())
            throw new IllegalArgumentException("Niagara debug names differ from the source emitter list");
        renderTargets = List.copyOf(renderTargets);
        mutableInterfaces = List.copyOf(mutableInterfaces);
        platformRefreshSets = platformRefreshSets == null ? null : List.copyOf(platformRefreshSets);
        var interfaceIds = new HashSet<String>();
        for (var target : renderTargets)
            if (!interfaceIds.add(target.interfaceId())) throw new IllegalArgumentException("Duplicate render target instance " + target.interfaceId());
        var mutableIds = new HashSet<String>();
        for (var value : mutableInterfaces) if (!mutableIds.add(SourceNames.canonical(value.interfaceId())))
            throw new IllegalArgumentException("Duplicate mutable interface instance " + value.interfaceId());
        Map<String, Variable> variables = new HashMap<>();
        for (Variable variable : systemLayout) variables.put(variable.name(), variable);
        if (timing.systemState().runUpdate()) requireVariable(variables, "System.ExecutionState", EXECUTION_STATE_TYPE, 0, 1);
        for (Emitter emitter : emitters) {
            if (!(emitter.simulation() instanceof StatelessSimulation) && !(emitter.simulation() instanceof DisabledSimulation))
                requireVariable(variables, emitter.sourceName() + ".ExecutionState", EXECUTION_STATE_TYPE, 0, 1);
            for (String name : emitter.spawnInfo()) {
                if (!name.startsWith(emitter.sourceName() + "."))
                    throw new IllegalArgumentException("SpawnInfo belongs to another emitter: " + name);
                requireVariable(variables, name, SPAWN_INFO_TYPE, 2, 2);
            }
        }
    }

    private static void requireVariable(Map<String, Variable> variables, String name, String type, int floats, int integers) {
        Variable variable = variables.get(name);
        if (variable == null || !variable.sourceType().equals(type) || variable.floatCount() != floats
                || variable.intCount() != integers || variable.halfCount() != 0)
            throw new IllegalArgumentException("Missing or incompatible Niagara binding " + name + " (" + type + ")");
    }
}
