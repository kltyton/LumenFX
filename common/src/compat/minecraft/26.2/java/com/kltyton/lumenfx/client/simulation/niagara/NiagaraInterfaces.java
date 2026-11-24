package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.ribbon.NiagaraRibbonShape;
import com.kltyton.lumenfx.effect.render.RibbonSettings;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsFieldState;

import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraSimCacheReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraSparseVolumeTexture;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraVolumeCache;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraRigidMeshCollision;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import com.kltyton.lumenfx.effect.niagara.texture.SparseVolumeInterfaceDefinition;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborGrid;
import com.kltyton.lumenfx.effect.niagara.grid.NeighborGridDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraRasterizationGrid;
import com.kltyton.lumenfx.effect.niagara.grid.RasterizationGridDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraVelocityGrid;
import com.kltyton.lumenfx.effect.niagara.grid.VelocityGridDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.Grid3DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.Grid2DCollectionDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollections;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGridCollectionUses.Use;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollections;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.material.NiagaraMaterialCollectionWriter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.material.NiagaraDynamicMaterialWriter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraCpuDebugLines;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialObjects;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.table.NiagaraDataTable;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraIntRenderTarget2D;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterScene;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.NiagaraGeometryCollectionState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.RestCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionAssetJson;
import com.kltyton.lumenfx.effect.niagara.geometry.GeometryCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventDemand;
import java.util.function.Consumer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.NiagaraChaosDestructionState;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.NiagaraHairStrandsState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.HairStrandsFrame;
import com.kltyton.lumenfx.effect.niagara.groom.HairStrandsDefinition;
import java.util.LinkedHashMap;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.water.NiagaraWaterState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.water.NiagaraAuthoredWaterBody;
import com.kltyton.lumenfx.effect.niagara.water.WaterDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.api.client.parameter.NiagaraMovieRenderSample;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraViewQueries;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraSceneCapture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraVirtualTextureInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraLandscape;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraVirtualTextureState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.MinecraftLandscape;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSpline;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborQuery;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.niagara.memory.NiagaraMemoryBufferDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.dynamic.NiagaraDynamicMesh;
import com.kltyton.lumenfx.api.client.NiagaraCacheCapture;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimCacheReaderDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPropertyInterfaceInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.EffectHandle.ArrayParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraRenderTarget2DState;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraDimensionalRenderTargetState;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.CameraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.MutableInterfaceInstance;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalMeshReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalGpuSnapshot;
import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportState;
import com.kltyton.lumenfx.effect.niagara.export.NiagaraParticleExportDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Overrides;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.audio.NiagaraAudioPlayerState;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioPlayerDefinition;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioAnalysisDefinition;
import com.kltyton.lumenfx.client.audio.analysis.NiagaraAudioBusAnalysis;
import com.kltyton.lumenfx.effect.niagara.parameter.UserParameterNames;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshReader;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraStaticMeshDefinition;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import net.minecraft.resources.Identifier;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraActorComponent;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraCollisionState;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Per-system data-interface state shared by its independently scheduled emitter VMs. */
public final class NiagaraInterfaces {
    private final NiagaraTextureBindings textures =
            new NiagaraTextureBindings();
    public NiagaraTextureBindings textures() { return textures; }
    private @Nullable NiagaraParameterStore instanceParameters;
    public void parameterStore(@Nullable NiagaraParameterStore value) { instanceParameters = value; textures.parameterStore(value); }
    private @Nullable Function<String, TextureParameter.Snapshot> gpuTargetExposure;
    private @Nullable Function<String, TextureParameter.Snapshot> gpuDimensionalTargetExposure;
    private @Nullable Function<String, TextureParameter.Snapshot> gpuIntRenderTargetExposure;
    private final MaterialInstanceParameters.TextureValues materialTextures = binding -> {
        if (binding.staticExposure()) return binding.objectDefault() == null
                ? TextureParameter.Snapshot.UNBOUND
                : EffectTextures.snapshot(binding.objectDefault());
        return exposedMaterialTarget(binding.variable(), binding.dataInterface(), binding.child(), binding.interfaceId());
    };
    TextureParameter.Snapshot exposedMaterialTarget(String variable, @Nullable String dataInterface, @Nullable String child, @Nullable String identity) {
        if (dataInterface != null && dataInterface.endsWith(".NiagaraDataInterfaceSceneCapture2D")) {
            if (identity == null || child == null) return TextureParameter.Snapshot.UNBOUND;
            var capture = sceneCaptures.get(SourceNames.canonical(identity));
            var texture = capture == null ? null : capture.exposedTexture(child);
            return texture == null ? TextureParameter.Snapshot.UNBOUND : texture;
        }
        if (dataInterface != null && dataInterface.endsWith(".NiagaraDataInterfaceIntRenderTarget2D")) {
            if (!SourceNames.equal("RenderTarget", child)) return TextureParameter.Snapshot.UNBOUND;
            String id = Objects.requireNonNull(identity, "Material integer render target has no source identity");
            return gpuIntRenderTargetExposure == null ? TextureParameter.Snapshot.UNBOUND : gpuIntRenderTargetExposure.apply(id);
        }
        if (dataInterface != null && (dataInterface.endsWith(".NiagaraDataInterfaceGrid2DCollection")
                || dataInterface.endsWith(".NiagaraDataInterfaceGrid3DCollection"))) {
            if (!SourceNames.equal("RenderTarget", child)) return TextureParameter.Snapshot.UNBOUND;
            String id = Objects.requireNonNull(identity, "Material Grid Collection has no source identity");
            var target = dataInterface.endsWith(".NiagaraDataInterfaceGrid2DCollection")
                    ? grid2DCollections.output(id) : grid3DCollections.output(id);
            return target == null ? TextureParameter.Snapshot.UNBOUND : target.resolve();
        }
        if (dataInterface != null && dataInterface.endsWith(".NiagaraDataInterfaceRenderTarget2D")) {
            if (!SourceNames.equal("RenderTarget", child)) return TextureParameter.Snapshot.UNBOUND;
            String id = Objects.requireNonNull(identity, "Material render target has no source object identity");
            var source = renderTarget2D(id).snapshot();
            if (source.released()) return TextureParameter.Snapshot.UNBOUND;
            if (source.target() != null) return source.target().snapshot();
            if (gpuTargetExposure != null) {
                var value = gpuTargetExposure.apply(id);
                if (value.view() != null) return value;
            }
            return TextureParameter.Snapshot.UNBOUND;
        }
        if (dataInterface != null && (dataInterface.endsWith(".NiagaraDataInterfaceRenderTarget2DArray")
                || dataInterface.endsWith(".NiagaraDataInterfaceRenderTargetVolume")
                || dataInterface.endsWith(".NiagaraDataInterfaceRenderTargetCube"))) {
            if (!SourceNames.equal("RenderTarget", child)) return TextureParameter.Snapshot.UNBOUND;
            String id = Objects.requireNonNull(identity, "Material render target array has no source object identity");
            var source = dimensionalTarget(id).snapshot();
            if (source.userTarget() != null && !source.userTarget().closed()) return source.userTarget().resolve();
            return gpuDimensionalTargetExposure == null ? TextureParameter.Snapshot.UNBOUND : gpuDimensionalTargetExposure.apply(id);
        }
        throw new IllegalStateException("Material texture requires its data interface exposure: " + variable);
    }
    public MaterialInstanceParameters.TextureValues materialTextures() { return materialTextures; }
    public void renderTargetExposure(Function<String, TextureParameter.Snapshot> exposure) {
        gpuTargetExposure = Objects.requireNonNull(exposure);
    }
    public void dimensionalTargetExposure(Function<String, TextureParameter.Snapshot> exposure) {
        gpuDimensionalTargetExposure = Objects.requireNonNull(exposure);
    }
    public void intRenderTargetExposure(Function<String, TextureParameter.Snapshot> exposure) {
        gpuIntRenderTargetExposure = Objects.requireNonNull(exposure);
    }
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final class CounterState {
        private final int initial;
        private final AtomicInteger value;
        private final AtomicLong revision = new AtomicLong(1);
        private long cacheEpoch;
        private final String syncMode;

        CounterState(JsonObject data) {
            initial = data.get("initial").getAsInt();
            value = new AtomicInteger(initial);
            syncMode = data.get("gpu_sync_mode").getAsString();
            if (!Set.of("None", "SyncCpuToGpu", "SyncGpuToCpu", "SyncBoth").contains(syncMode))
                throw new IllegalArgumentException("Invalid Niagara counter synchronization mode " + syncMode);
        }
        public int get() { return value.get(); }
        public int initial() { return initial; }
        int exchange(int next) { return value.getAndSet(next); }
        int add(int amount) { return value.getAndAdd(amount); }
        void modified() { if (syncMode.equals("SyncCpuToGpu") || syncMode.equals("SyncBoth")) revision.incrementAndGet(); }
        public long revision() { return revision.get(); }
        public String syncMode() { return syncMode; }
        public void fromGpu(int next) { value.set(next); }
        public synchronized void readCache(int next) {
            fromGpu(next);
            cacheEpoch++;
            revision.incrementAndGet();
        }
        public synchronized long cacheEpoch() { return cacheEpoch; }
        public synchronized void fromGpu(int next, long expectedCacheEpoch) {
            if (cacheEpoch == expectedCacheEpoch) fromGpu(next);
        }
    }
    public record CounterSnapshot(int value, long revision) { }
    public record MutableGpuInputs(Map<String, ArraySnapshot> arrays, Map<String, CounterSnapshot> counters) {
        public MutableGpuInputs {
            arrays = Map.copyOf(arrays);
            counters = Map.copyOf(counters);
        }
    }
    public record ArraySnapshot(String type, int width, int size, int maximum, int[] defaults, int[] words,
                                long version, String syncMode, boolean usedByCpu) {
        public ArraySnapshot { defaults = defaults.clone(); words = words.clone(); }
        @Override public int[] defaults() { return defaults.clone(); }
        @Override public int[] words() { return words.clone(); }
    }
    public static final class ArrayState {
        private long cacheEpoch;
        final String type;
        final String parameterName;
        final Set<String> parameterNames = new LinkedHashSet<>();
        final int width, maximum;
        final int[] defaults;
        final int[] initialWords;
        final String syncMode;
        private EffectHandle.ArrayParameter lastOverride;
        private int[] ownerWords;
        private int[] words;
        private boolean instanceModified;
        private int size;
        private long version;
        private boolean usedByCpu;
        private @Nullable Boolean gpuWritable;
        private boolean runtimeGpuClassification;
        private @Nullable ArraySnapshot captured;

        ArrayState(JsonObject data, String parameterName) {
            type = data.get("class").getAsString();
            this.parameterName = parameterName;
            if (!parameterName.isEmpty()) parameterNames.add(parameterName);
            width = data.get("components").getAsInt();
            maximum = data.get("maximum_elements").getAsInt();
            syncMode = data.get("gpu_sync_mode").getAsString();
            if (!Set.of("None", "SyncCpuToGpu", "SyncGpuToCpu", "SyncBoth").contains(syncMode))
                throw new IllegalArgumentException("Unknown Niagara GPU array synchronization mode " + syncMode);
            var defaultValues = data.getAsJsonArray("default");
            var initial = data.getAsJsonArray("words");
            if (width != arrayType().components() || defaultValues.size() != width || initial.size() % width != 0)
                throw new IllegalArgumentException("Invalid Niagara array interface layout " + type);
            defaults = new int[width];
            for (int i = 0; i < width; i++) defaults[i] = defaultValues.get(i).getAsInt();
            size = initial.size() / width;
            words = new int[initial.size()];
            for (int i = 0; i < words.length; i++) words[i] = initial.get(i).getAsInt();
            initialWords = words.clone();
            ownerWords = words;
            if (data.has("gpu_writable")) gpuWritable = data.get("gpu_writable").getAsBoolean();
            runtimeGpuClassification = data.has("gpu_writable") && data.has("runtime_gpu_classification")
                    && data.get("runtime_gpu_classification").getAsBoolean();
        }

        void synchronize(EffectHandle.ArrayParameter replacement) {
            if (replacement == lastOverride) return;
            if (replacement == null) ownerWords = initialWords.clone();
            else {
                EffectHandle.ArrayType expected = arrayType();
                if (replacement.type() != expected) throw new IllegalArgumentException("Niagara array parameter type mismatch: " + parameterName);
                ownerWords = replacement.words();
                if (expected == EffectHandle.ArrayType.UINT8)
                    for (int i = 0; i < ownerWords.length; i++) ownerWords[i] &= 255;
                else if (expected == EffectHandle.ArrayType.BOOL)
                    for (int i = 0; i < ownerWords.length; i++) ownerWords[i] = ownerWords[i] == 0 ? 0 : -1;
            }
            words = ownerWords;
            instanceModified = false;
            size = words.length / width;
            lastOverride = replacement;
            version++;
        }

        private EffectHandle.ArrayType arrayType() {
            return switch (type) {
                    case "NiagaraDataInterfaceArrayFloat" -> EffectHandle.ArrayType.FLOAT;
                    case "NiagaraDataInterfaceArrayFloat2" -> EffectHandle.ArrayType.FLOAT2;
                    case "NiagaraDataInterfaceArrayFloat3" -> EffectHandle.ArrayType.FLOAT3;
                    case "NiagaraDataInterfaceArrayPosition" -> EffectHandle.ArrayType.POSITION;
                    case "NiagaraDataInterfaceArrayFloat4" -> EffectHandle.ArrayType.FLOAT4;
                    case "NiagaraDataInterfaceArrayColor" -> EffectHandle.ArrayType.COLOR;
                    case "NiagaraDataInterfaceArrayQuat" -> EffectHandle.ArrayType.QUAT;
                    case "NiagaraDataInterfaceArrayMatrix" -> EffectHandle.ArrayType.MATRIX;
                    case "NiagaraDataInterfaceArrayInt32" -> EffectHandle.ArrayType.INT32;
                    case "NiagaraDataInterfaceArrayUInt8" -> EffectHandle.ArrayType.UINT8;
                    case "NiagaraDataInterfaceArrayBool" -> EffectHandle.ArrayType.BOOL;
                    case "NiagaraDataInterfaceArrayNiagaraID" -> EffectHandle.ArrayType.NIAGARA_ID;
                    default -> throw new IllegalArgumentException("Unknown Niagara array type " + type);
                };
        }

        int size() { return size; }
        private void writable() {
            if (instanceModified) return;
            words = ownerWords.clone();
            instanceModified = true;
        }
        void beginWrite() { writable(); version++; }
        boolean valid(int index) { return index >= 0 && index < size; }
        int get(int index, int component) { return size == 0 ? defaults[component] : words[Math.clamp(index, 0, size - 1) * width + component]; }
        void set(int index, int component, int value) {
            writable();
            if (type.equals("NiagaraDataInterfaceArrayUInt8")) value &= 255;
            else if (type.equals("NiagaraDataInterfaceArrayBool")) value = value == 0 ? 0 : -1;
            words[index * width + component] = value;
            version++;
        }
        void resize(int newSize) {
            if (newSize < 0) throw new IllegalArgumentException("Negative Niagara array size");
            writable();
            int oldSize = size;
            int required = Math.multiplyExact(newSize, width);
            if (required > words.length) {
                int doubled = words.length <= Integer.MAX_VALUE / 2 ? words.length * 2 : Integer.MAX_VALUE;
                words = Arrays.copyOf(words, Math.max(required, Math.max(width * 8, doubled)));
            }
            size = newSize;
            for (int i = oldSize; i < newSize; i++) System.arraycopy(defaults, 0, words, i * width, width);
            version++;
        }
        boolean canAdd() { return maximum <= 0 || size < maximum; }

        public synchronized ArraySnapshot snapshot() {
            var snapshot = captured;
            if (snapshot == null || snapshot.version() != version || snapshot.usedByCpu() != usedByCpu) {
                snapshot = new ArraySnapshot(type, width, size, maximum, defaults,
                        Arrays.copyOf(words, size * width), version, syncMode, usedByCpu);
                captured = snapshot;
            }
            return snapshot;
        }

        public synchronized long version() { return version; }
        public synchronized void readCache(int[] values) {
            if (values.length % width != 0) throw new IllegalArgumentException("Invalid cached Niagara array " + type);
            words = values;
            size = values.length / width;
            instanceModified = true;
            version++;
            cacheEpoch++;
        }
        public synchronized long cacheEpoch() { return cacheEpoch; }
        public synchronized void applyGpu(int[] data, long expectedCacheEpoch) {
            if (cacheEpoch == expectedCacheEpoch) applyGpu(data);
        }
        public synchronized ArraySnapshot cacheGpuSnapshot(int[] values, long revision) {
            if (values.length % width != 0) throw new IllegalArgumentException("Invalid cached GPU Niagara array " + type);
            return new ArraySnapshot(type, width, values.length / width, maximum, defaults, values, revision, syncMode, usedByCpu);
        }
        public synchronized boolean usedByCpu() { return usedByCpu; }
        public synchronized @Nullable Boolean gpuWritable() { return gpuWritable; }
        public synchronized boolean runtimeGpuClassification() { return runtimeGpuClassification; }
        public String type() { return type; }
        public int width() { return width; }
        public String syncMode() { return syncMode; }

        public synchronized void applyGpu(int[] data) {
            if (data.length % width != 0) throw new IllegalArgumentException("Invalid Niagara GPU array readback layout");
            words = data.clone(); size = data.length / width;
            instanceModified = true;
            version++;
        }

        synchronized void destroyInstance() {
            words = ownerWords;
            size = ownerWords.length / width;
            instanceModified = false;
            usedByCpu = false;
            captured = null;
            version++;
        }

        synchronized EffectHandle.ArrayParameter result() {
            return new EffectHandle.ArrayParameter(arrayType(), Arrays.copyOf(words, size * width));
        }
    }

    private record ParticleDistance(int index, int tag, double distanceSquared) { }
    private record CameraValues(float[] properties, float fov, int splitscreen) { }
    private static final class CameraState {
        final ConcurrentLinkedQueue<ParticleDistance> pending = new ConcurrentLinkedQueue<>();
        volatile List<ParticleDistance> sorted = List.of();
        volatile Supplier<CameraDefinition> configuration;
        volatile CameraValues values = new CameraValues(new float[]{0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0}, 0, 0);
        CameraState(Supplier<CameraDefinition> configuration) { this.configuration = configuration; }
        void beginFrame() {
            List<ParticleDistance> next = new ArrayList<>();
            for (ParticleDistance entry; (entry = pending.poll()) != null;) next.add(entry);
            next.sort(Comparator.comparingDouble(ParticleDistance::distanceSquared));
            sorted = List.copyOf(next);
        }
        void capture(@Nullable NiagaraCameraFrame frame, double x, double y, double z) {
            if (configuration.get().controllerIndex() == 0 && frame != null) {
                values = new CameraValues(new float[]{(float)(frame.x() - x), (float)(frame.y() - y), (float)(frame.z() - z),
                        frame.forwardX(), frame.forwardY(), frame.forwardZ(), frame.upX(), frame.upY(), frame.upZ(),
                        frame.rightX(), frame.rightY(), frame.rightZ(), frame.viewportWidth(), frame.viewportHeight(), frame.nearPlane()},
                        frame.fieldOfView(), frame.splitscreenMode());
            } else {
                var previous = values;
                values = new CameraValues(new float[]{0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0,
                        previous.properties()[12], previous.properties()[13], previous.properties()[14]}, 0, previous.splitscreen());
            }
        }
    }

    private final ConcurrentHashMap<String, CameraState> cameras = new ConcurrentHashMap<>();
    private final Map<NiagaraDataInterfaceObject, NiagaraRibbonShape> ribbonShapes = new ConcurrentHashMap<>();
    private record RibbonRenderer(int emitter, RibbonSettings defaults) { }
    private final Map<RibbonRenderer, RibbonSettings> activeRibbonShapes = new ConcurrentHashMap<>();

    void setRibbonShape(NiagaraDataInterfaceObject object, NiagaraRibbonShape shape) { ribbonShapes.put(object, shape); }
    RibbonSettings ribbonSettings(int emitter, RibbonSettings defaults, @Nullable NiagaraDataInterfaceObject object) {
        var renderer = new RibbonRenderer(emitter, defaults);
        var shape = object == null ? null : ribbonShapes.get(object);
        if (shape == null || shape.vertices().size() < 2) return activeRibbonShapes.getOrDefault(renderer, defaults);
        var resolved = shape.apply(defaults.shape());
        return activeRibbonShapes.compute(renderer, (key, current) ->
                current != null && current.shape().equals(resolved) ? current : defaults.withShape(resolved));
    }
    private final ConcurrentHashMap<String, ArrayState> arrays = new ConcurrentHashMap<>();
    private final Map<String, Long> publishedVersions = new HashMap<>();
    private final Map<String, NiagaraParticleReadSource> particleEmitters = new HashMap<>();
    private final Map<String, NiagaraEmitterBounds> emitterBounds = new HashMap<>();
    private final ConcurrentHashMap<String, CounterState> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NiagaraRenderTarget2DState> renderTargets2D = new ConcurrentHashMap<>();
    private final Map<String, NiagaraDimensionalRenderTargetState> dimensionalTargets = new HashMap<>();
    private final Map<String, NiagaraObjectPropertyReader> propertyReaders = new HashMap<>();
    private final Map<String, NiagaraPropertyInterface> propertyInterfaces = new LinkedHashMap<>();
    private final Map<String, NiagaraActorComponent> actorComponents = new HashMap<>();
    private final Map<String, NiagaraCollisionState> collisions = new HashMap<>();
    private final Map<String, NiagaraStaticMeshReader> staticMeshes = new HashMap<>();
    private final Map<String, NiagaraSkeletalMeshReader> skeletalMeshes = new HashMap<>();
    private final Map<String, NiagaraParticleExportState> particleExports = new HashMap<>();
    private final Map<String, NiagaraDataChannelState> dataChannels = new HashMap<>();
    private final Map<String, NiagaraAudioPlayerState> audioPlayers = new HashMap<>();
    private final Map<String, NiagaraAudioAnalysisDefinition> audioAnalysis = new HashMap<>();
    private final Map<String, NiagaraSimCacheReader> simCacheReaders = new HashMap<>();
    private final Map<String, NiagaraSparseVolumeTexture> sparseVolumes = new HashMap<>();
    private final Map<String, NiagaraVolumeCache> volumeCaches = new ConcurrentHashMap<>();
    private final Map<String, NiagaraPhysicsAssetReader> physicsAssets = new HashMap<>();
    private final Map<String, NiagaraRigidMeshCollision> rigidMeshes = new HashMap<>();
    private final Map<String, NiagaraSceneCapture> sceneCaptures = new HashMap<>();
    private final Map<String, NiagaraLandscape> landscapes = new HashMap<>();
    private final Map<String, NiagaraVirtualTextureState> virtualTextures = new HashMap<>();
    private @Nullable MinecraftLandscape hostLandscape;
    private boolean scriptedRigidMeshSearch;
    private Map<Identifier, NiagaraVolumeCacheAsset> volumeCacheAssets = Map.of();
    private final Map<String, NiagaraMemoryBuffer> memoryBuffers = new HashMap<>();
    private final Map<String, NiagaraNeighborGrid> neighborGrids = new HashMap<>();
    private final Map<String, NiagaraRasterizationGrid> rasterizationGrids = new HashMap<>();
    private final Map<String, NiagaraVelocityGrid> velocityGrids = new HashMap<>();
    private final NiagaraGrid3DCollections grid3DCollections = new NiagaraGrid3DCollections();
    private final NiagaraGrid2DCollections grid2DCollections = new NiagaraGrid2DCollections();
    private final Map<String, NiagaraDataTable> dataTables = new HashMap<>();
    private final Map<String, NiagaraGaussianSplatReader> gaussianSplatReaders = new HashMap<>();
    private final Map<String, NiagaraGaussianSplatWriter> gaussianSplatWriters = new HashMap<>();
    private final Map<String, NiagaraIntDistribution> intDistributionDefaults = new HashMap<>();
    private final Map<String, NiagaraWaterState> waters = new HashMap<>();
    private final Map<String, NiagaraGeometryCollectionState> geometryCollections = new LinkedHashMap<>();
    private final Map<String, NiagaraChaosDestructionState> chaosDestructions = new LinkedHashMap<>();
    private final Map<String, NiagaraHairStrandsState> hairStrands = new LinkedHashMap<>();
    private Map<String, NiagaraIntDistribution> intDistributions = Map.of();
    private final Map<String, NiagaraIntRenderTarget2D> intRenderTargets = new HashMap<>();
    private final Map<String, NiagaraSocketReader> socketReaders = new HashMap<>();
    private final Map<String, NiagaraNeighborQuery> neighborQueries = new HashMap<>();
    private final Map<String, NiagaraSpline> splines = new HashMap<>();
    private final Map<String, NiagaraMaterialCollectionWriter> materialCollectionWriters = new HashMap<>();
    private final Map<String, NiagaraDynamicMaterialWriter> dynamicMaterialWriters = new HashMap<>();
    private NiagaraMaterialObjects savedDynamicMaterials = new NiagaraMaterialObjects();
    private Map<String, ?> materialCollectionInputs = Map.of();
    private final Map<String, NiagaraDynamicMesh> dynamicMeshes = new HashMap<>();
    private final Map<String, NiagaraAudioBusAnalysis.Frame> audioFrames = new HashMap<>();
    private enum FrameCallbackKind {
        CAMERA, TEXTURE, RENDER_TARGET_2D, DIMENSIONAL_TARGET, OBJECT_PROPERTY_READER, PROPERTY_INTERFACE, ACTOR_COMPONENT, COLLISION, STATIC_MESH,
        SKELETAL_MESH, SOCKET_READER, SPLINE, PARTICLE_EXPORT, DATA_CHANNEL, AUDIO_PLAYER, AUDIO_ANALYSIS, MATERIAL_COLLECTION_WRITER, DYNAMIC_MATERIAL,
        GEOMETRY_COLLECTION, GRID_2D_COLLECTION, GRID_3D_COLLECTION, RIGID_MESH_COLLISION, SCENE_CAPTURE, LANDSCAPE
    }
    private record FrameCallback(String identity, FrameCallbackKind kind) { }
    record SceneCallbackInputs(NiagaraRigidMeshInputs rigidMeshes, NiagaraGeometryCollectionInputs geometry,
                               Function<Object, @Nullable RestCollection> savedGeometry,
                               NiagaraSceneCaptureInputs captures, NiagaraLandscapeInputs landscapes,
                               @Nullable NiagaraBounds landscapeBounds) { }
    private final Map<String, FrameCallback> frameCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<FrameCallback> frameCallbackOrder = new ConcurrentLinkedQueue<>();
    private volatile boolean[] renderTargetFormatSupport;
    private final Set<String> reportedParticleAttributes = ConcurrentHashMap.newKeySet();
    private record GpuParticleSnapshot(int count, int spawned) { }
    private static final class GpuParticleState {
        final com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter emitter;
        final java.util.function.IntUnaryOperator countEstimate;
        volatile GpuParticleSnapshot snapshot = new GpuParticleSnapshot(0, 0);
        GpuParticleState(com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter emitter,
                         java.util.function.IntUnaryOperator countEstimate) {
            this.emitter = emitter; this.countEstimate = countEstimate;
        }
    }
    private final Map<String, GpuParticleState> gpuParticleEmitters = new HashMap<>();
    private Map<String, EffectHandle.ArrayParameter> publishedArrays = Map.of();
    private @Nullable NiagaraCameraFrame current;
    private double originX, originY, originZ;
    private final NiagaraPhysicsFieldState physicsField = new NiagaraPhysicsFieldState();

    NiagaraPhysicsFieldState physicsField() { return physicsField; }
    private Map<String, EffectHandle.ArrayParameter> arrayParameters = Map.of();
    private Set<String> collectionArrayParameters = Set.of();

    void registerEmitterBounds(com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter emitter,
                               com.kltyton.lumenfx.effect.niagara.@Nullable NiagaraBounds systemFixedBounds) {
        String name = SourceNames.canonical(emitter.sourceName());
        if (emitterBounds.putIfAbsent(name, new NiagaraEmitterBounds(emitter, systemFixedBounds)) != null)
            throw new IllegalArgumentException("Duplicate Niagara emitter bounds " + emitter.sourceName());
    }

    @Nullable NiagaraEmitterBounds emitterBounds(String name) {
        return emitterBounds.get(SourceNames.canonical(name));
    }

    void registerParticleEmitter(String name, NiagaraParticleReadSource emitter) {
        String key = SourceNames.canonical(name);
        if (particleEmitters.putIfAbsent(key, emitter) != null || gpuParticleEmitters.containsKey(key))
            throw new IllegalArgumentException("Duplicate Niagara particle emitter " + name);
    }

    void registerGpuParticleEmitter(com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter emitter,
                                     java.util.function.IntUnaryOperator countEstimate) {
        String key = SourceNames.canonical(emitter.sourceName());
        if (gpuParticleEmitters.putIfAbsent(key, new GpuParticleState(emitter, countEstimate)) != null || particleEmitters.containsKey(key))
            throw new IllegalArgumentException("Duplicate Niagara particle emitter " + emitter.sourceName());
    }

    private @Nullable String particleEmitterKey(com.google.gson.JsonObject data) {
        String target = data.get("emitter").getAsString();
        if (target == null || target.isBlank() || SourceNames.equal(target, "None")) return null;
        return SourceNames.canonical(target);
    }

    @Nullable NiagaraParticleReadSource particleEmitter(com.google.gson.JsonObject data) {
        String key = particleEmitterKey(data);
        return key == null ? null : particleEmitters.get(key);
    }

    com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.@Nullable Emitter gpuParticleEmitter(com.google.gson.JsonObject data) {
        String key = particleEmitterKey(data);
        GpuParticleState state = key == null ? null : gpuParticleEmitters.get(key);
        return state == null ? null : state.emitter;
    }

    void publishGpuParticleCounts(String name, int count, int spawned) {
        GpuParticleState state = gpuParticleEmitters.get(SourceNames.canonical(name));
        if (state == null) throw new IllegalArgumentException("Unknown Niagara GPU emitter " + name);
        state.snapshot = new GpuParticleSnapshot(count, spawned);
    }

    int gpuParticleCount(com.google.gson.JsonObject data, boolean spawned) {
        String key = particleEmitterKey(data);
        GpuParticleState state = key == null ? null : gpuParticleEmitters.get(key);
        if (state == null) return 0;
        GpuParticleSnapshot snapshot = state.snapshot;
        return spawned ? snapshot.spawned() : state.countEstimate.applyAsInt(snapshot.count());
    }

    void reportParticleAttribute(String target, String attribute, String type) {
        if (reportedParticleAttributes.add(target + "\0" + attribute + "\0" + type))
            LOGGER.warn("Niagara particle reader cannot read attribute '{}' as '{}' from emitter '{}'", attribute, type, target);
    }

    private NiagaraPlatformEnvironment platformEnvironment = NiagaraPlatforms.snapshot();
    NiagaraPlatformEnvironment platformEnvironment() { return platformEnvironment; }
    private boolean needsMousePosition, needsMovieRenderSample;
    private NiagaraViewQueries viewQueries = NiagaraViewQueries.EMPTY;
    private final NiagaraCpuDebugLines debugLines = new NiagaraCpuDebugLines();

    NiagaraCpuDebugLines debugLines() { return debugLines; }

    void registerViewQuery(@Nullable String sourceClass) {
        if ("NiagaraDataInterfaceMousePosition".equals(sourceClass)) needsMousePosition = true;
        if ("NiagaraDataInterfaceMRQ".equals(sourceClass)) needsMovieRenderSample = true;
    }

    NiagaraViewQueries viewQueries() { return viewQueries; }

    boolean beginFrame(NiagaraFrame frame, @Nullable NiagaraCameraFrame camera, EffectHandle.Input input,
                       ObjectPropertyInputs propertyInputs, Function<Object, ObjectPropertyInputs.@Nullable Source> savedObjects,
                       NiagaraPropertyInterfaceInputs ownerProperties,
                       ActorComponentInputs actorInputs, IntFunction<ActorComponentInputs.@Nullable Component> localPlayers,
                       Function<Object, ActorComponentInputs.@Nullable Binding> savedActorComponents,
                       @Nullable NiagaraCollisionWorld collisionWorld, NiagaraStaticMeshInputs meshInputs,
                       Map<Identifier, StaticMeshAsset> meshAssets, NiagaraSkeletalMeshInputs skeletalInputs,
                       Map<Identifier, SkeletalMeshAsset> skeletalAssets, NiagaraSocketInputs socketInputs, NiagaraSplineInputs splineInputs,
                       Function<Object, NiagaraSplineInputs.@Nullable Actor> savedSplines,
                       Predicate<String> objectParameterOverridden,
                       NiagaraMovieRenderSample movieRenderSample,
                       Map<String, NiagaraParticleExport> exportHandlers,
                       @Nullable ClientLevel channelWorld, boolean lastSubtick, @Nullable EffectHandle channelOwner, IntPredicate activeEmitter,
                       NiagaraAudioBackend audioBackend, Map<String, Overrides> audioOverrides,
                       Function<Object, @Nullable Overrides> savedAudioSettings, SceneCallbackInputs scene) {
        platformEnvironment = frame.platform;
        if (needsMousePosition || needsMovieRenderSample) viewQueries = NiagaraViewQueries.capture(needsMousePosition, movieRenderSample);
        current = camera;
        originX = frame.originX(); originY = frame.originY(); originZ = frame.originZ();
        debugLines.begin(Objects.requireNonNull(frame.platform.console("fx.Niagara.DebugDraw.Enabled")).bits() != 0,
                originX / NiagaraFrame.TILE_SIZE, originY / NiagaraFrame.TILE_SIZE, originZ / NiagaraFrame.TILE_SIZE);
        arrayParameters = input.arrayParameters();
        arrays.values().forEach(array -> { synchronized (array) { array.synchronize(ArrayParameter.latest(arrayParameters, array.parameterNames)); } });
        textures.beginCapture(input.textureParameters());
        grid3DCollections.captureOutputs(input.textureParameters(), instanceParameters);
        grid2DCollections.captureOutputs(input.textureParameters(), instanceParameters);
        for (var target : intRenderTargets.values()) target.captureTarget(input.textureParameters(), instanceParameters);
        var origin = new Vec3(originX, originY, originZ);
        NiagaraLandscapeInputs landscapeInputs = scene.landscapes();
        NiagaraLandscapeInputs.Bounds landscapeRegion = null;
        if (scene.landscapeBounds() != null) {
            if (landscapeInputs == NiagaraLandscapeInputs.EMPTY && channelWorld != null) {
                if (hostLandscape == null || !hostLandscape.belongsTo(channelWorld)) hostLandscape = new MinecraftLandscape(channelWorld);
                landscapeInputs = hostLandscape.inputs();
            }
            var world = NiagaraComponentBounds.world(input.pose(), scene.landscapeBounds());
            landscapeRegion = new NiagaraLandscapeInputs.Bounds(world.minX(), world.minY(), world.maxX(), world.maxY());
        }
        boolean rebind = false;
        for (var callback : frameCallbackOrder) switch (callback.kind()) {
            case CAMERA -> {
                var state = cameras.get(callback.identity());
                state.beginFrame();
                state.capture(camera, originX, originY, originZ);
            }
            case TEXTURE -> textures.capture(callback.identity());
            case RENDER_TARGET_2D -> {
                var target = renderTarget2D(callback.identity());
                target.resolutionMultiplier(frame.platform.renderTargetResolutionMultiplier());
                target.captureTarget(input.textureParameters(), instanceParameters);
            }
            case DIMENSIONAL_TARGET -> {
                var target = dimensionalTarget(callback.identity());
                target.resolutionMultiplier(frame.platform.renderTargetResolutionMultiplier());
                target.captureTarget(input.textureParameters(), instanceParameters);
            }
            case ACTOR_COMPONENT -> actorComponent(callback.identity()).capture(actorInputs, instanceParameters, localPlayers,
                    savedActorComponents, originX, originY, originZ);
            case COLLISION -> collision(callback.identity()).preTick(collisionWorld, frame.platform.collisionCpuEnabled(), originX, originY, originZ);
            case STATIC_MESH -> {
                var reader = staticMesh(callback.identity());
                rebind |= reader.capture(meshInputs, instanceParameters, meshAssets, frame.platform, input.pose(), frame.delta,
                        originX, originY, originZ, channelWorld, objectParameterOverridden.test(reader.definition().meshParameter()));
            }
            case SKELETAL_MESH -> {
                var reader = skeletalMesh(callback.identity());
                rebind |= reader.capture(skeletalInputs, skeletalAssets, instanceParameters,
                        objectParameterOverridden.test(reader.definition().meshParameter()), input.pose(), frame.delta, originX, originY, originZ,
                        frame.engineTime, channelWorld);
            }
            case SOCKET_READER -> {
                var reader = socketReader(callback.identity());
                reader.capture(socketInputs, meshInputs, skeletalInputs, instanceParameters, meshAssets, skeletalAssets,
                        objectParameterOverridden.test(reader.parameter()), savedActorComponents, input.pose(), frame.delta, originX, originY, originZ);
            }
            case SPLINE -> {
                var spline = spline(callback.identity());
                String parameter = spline.parameter();
                var object = instanceParameters == null || parameter.isBlank()
                        ? null : instanceParameters.objectBinding(parameter).value();
                String path = NiagaraParameterStoreSources.sourceObjectPath(object);
                spline.capture(splineInputs, path.isEmpty() ? null : path, object == null ? null : savedSplines.apply(object),
                        objectParameterOverridden.test(parameter), frame.platform.splineCpuLutsDisabled(),
                        frame.platform.splineUsesLegacyCurves(), originX, originY, originZ);
            }
            case PARTICLE_EXPORT -> particleExport(callback.identity()).capture(exportHandlers, originX, originY, originZ);
            case RIGID_MESH_COLLISION -> rigidMesh(callback.identity()).begin(scene.rigidMeshes(), origin);
            case GEOMETRY_COLLECTION -> {
                var state = geometryCollection(callback.identity());
                var parameter = state.parameter();
                var object = instanceParameters == null || parameter.isBlank() ? null : instanceParameters.objectBinding(parameter).value();
                String path = NiagaraParameterStoreSources.sourceObjectPath(object);
                state.begin(scene.geometry(), path.isEmpty() ? null : path,
                        object == null ? null : scene.savedGeometry().apply(object), origin);
            }
            case SCENE_CAPTURE -> sceneCapture(callback.identity()).begin(scene.captures(), input.pose(), origin, frame.delta);
            case LANDSCAPE -> landscape(callback.identity()).begin(landscapeInputs, Objects.requireNonNull(landscapeRegion));
            case DATA_CHANNEL -> dataChannel(callback.identity()).capture(frame.platform.dataChannelsEnabled() ? channelWorld : null,
                    frame.platform.dataChannelsForcePrevious(), lastSubtick, frame.platform.dataChannelsAutoUploadGpuSpawns(),
                    origin, input.pose().position(), channelOwner, activeEmitter);
            case AUDIO_PLAYER -> {
                var audio = audioPlayer(callback.identity());
                String name = audio.definition().configurationParameter();
                var settings = audioOverrides.get(UserParameterNames.display(name));
                if (settings == null && !name.isEmpty() && instanceParameters != null) {
                    var binding = instanceParameters.objectBinding(name).value();
                    if (binding != null) settings = savedAudioSettings.apply(binding);
                }
                audio.capture(input.pose(), origin, audioBackend, settings, channelOwner, channelWorld != null);
            }
            case AUDIO_ANALYSIS -> audioFrames.put(callback.identity(),
                    NiagaraAudioBusAnalysis.capture(channelWorld, Objects.requireNonNull(audioAnalysis.get(callback.identity()))));
            case MATERIAL_COLLECTION_WRITER -> {
                var writer = materialCollectionWriter(callback.identity());
                String binding = SourceNames.canonical(UserParameterNames.display(writer.binding()));
                writer.capture(channelWorld, instanceParameters, materialCollectionInputs.get(binding));
            }
            case DYNAMIC_MATERIAL -> {
                var writer = dynamicMaterialWriter(callback.identity());
                writer.capture(resolveDynamicMaterial(writer.definition(), input.rendererParameters()));
            }
            case PROPERTY_INTERFACE -> propertyInterface(callback.identity()).capture(ownerProperties);
            case OBJECT_PROPERTY_READER -> {
                var reader = objectPropertyReader(callback.identity());
                if (reader.preTick(propertyInputs, instanceParameters, savedObjects, originX, originY, originZ)) {
                    reader.reinitialize();
                    rebind = true;
                }
            }
        }
        textures.publishCapture();
        return rebind;
    }

    void reset() {
        destroyInstanceResources();
        arrays.clear();
        arrayParameters = Map.of();
        debugLines.clear();
        publishedVersions.clear();
        publishedArrays = Map.of();
        reportedParticleAttributes.clear();
    }

    public CounterState counter(String id, JsonObject data) {
        if (id == null || id.isEmpty() || data == null) throw new IllegalArgumentException("Invalid Niagara counter source");
        return counters.computeIfAbsent(id, ignored -> new CounterState(data));
    }
    public CounterState counter(String id) {
        return Objects.requireNonNull(counters.get(id), "Counter was not registered before simulation: " + id);
    }
    public Set<String> mutableInterfaceIds() {
        var ids = new HashSet<>(arrays.keySet());
        ids.addAll(counters.keySet());
        return Set.copyOf(ids);
    }
    boolean hasCameras() { return !cameras.isEmpty(); }

    void clearCollectionArrayBindings(Set<String> names) {
        collectionArrayParameters = names;
        if (names.isEmpty()) return;
        for (var array : arrays.values()) synchronized (array) {
            if (array.parameterNames.removeIf(name -> names.contains(SourceNames.canonical(name))))
                array.lastOverride = null;
        }
        publishedVersions.keySet().removeIf(name -> names.contains(SourceNames.canonical(name)));
        var results = new HashMap<>(publishedArrays);
        results.keySet().removeIf(name -> names.contains(SourceNames.canonical(name)));
        publishedArrays = Map.copyOf(results);
    }

    void retainArrays(Set<String> identities) { arrays.keySet().retainAll(identities); }

    void registerMutable(MutableInterfaceInstance definition) {
        if (definition.type().equals("NiagaraDataInterfaceSimpleCounter")) counter(definition.interfaceId(), definition.data());
        else if (definition.type().startsWith("NiagaraDataInterfaceArray")) {
            JsonObject data = definition.data();
            var state = array(definition.interfaceId(), definition.parameterName(), data);
            synchronized (state) {
                if (definition.usedCpu()) state.usedByCpu = true;
                if (data.has("gpu_writable")) state.gpuWritable = data.get("gpu_writable").getAsBoolean();
                state.runtimeGpuClassification = data.has("gpu_writable") && data.has("runtime_gpu_classification")
                        && data.get("runtime_gpu_classification").getAsBoolean();
            }
        } else throw new IllegalArgumentException("Unknown mutable DI initialization class " + definition.type());
    }

    void mutableUsage(String identity, boolean cpu) {
        ArrayState array = arrays.get(identity);
        if (array != null) synchronized (array) { array.usedByCpu = cpu; }
    }
    void bindArrayParameters(String identity, Collection<String> names) {
        ArrayState array = arrays.get(identity);
        if (array != null) synchronized (array) {
            for (String name : names) if (!name.isEmpty()) array.parameterNames.add(name);
            array.synchronize(ArrayParameter.latest(arrayParameters, array.parameterNames));
        }
    }

    private void registerFrameCallback(String identity, FrameCallbackKind kind) {
        var callback = new FrameCallback(identity, kind);
        var existing = frameCallbacks.putIfAbsent(identity, callback);
        if (existing == null) frameCallbackOrder.add(callback);
        else if (existing.kind() != kind)
            throw new IllegalArgumentException("Niagara data interface identity has conflicting frame callbacks: " + identity);
    }

    void orderFrameCallbacks(List<String> discovered, Set<String> standalone) {
        var ordered = new LinkedHashSet<FrameCallback>();
        for (String identity : discovered) {
            var callback = frameCallbacks.get(identity);
            if (callback != null) ordered.add(callback);
        }
        // Legacy script bindings and material-owned targets have no execution store.
        for (var callback : frameCallbackOrder) {
            if (standalone.contains(callback.identity())) ordered.add(callback);
        }
        frameCallbackOrder.clear();
        frameCallbackOrder.addAll(ordered);
    }

    void postSimulate(@Nullable ClientLevel level, boolean firstRigidMeshStep, float delta) {
        for (var callback : frameCallbackOrder) {
            String identity = callback.identity();
            switch (callback.kind()) {
                case COLLISION -> collision(identity).postSimulate();
                case GEOMETRY_COLLECTION -> geometryCollection(identity).postSimulate();
                case PARTICLE_EXPORT -> particleExport(identity).postSimulate(level);
                case DATA_CHANNEL -> dataChannel(identity).postSimulate();
                case AUDIO_PLAYER -> audioPlayer(identity).postSimulate();
                case GRID_2D_COLLECTION -> grid2DCollections.postSimulate(identity);
                case GRID_3D_COLLECTION -> grid3DCollections.postSimulate(identity);
                case RIGID_MESH_COLLISION -> {
                    if (firstRigidMeshStep) rigidMesh(identity).postSimulate();
                }
                case SCENE_CAPTURE -> sceneCapture(identity).postSimulate(delta);
                case LANDSCAPE -> landscape(identity).flushFeedback();
            }
        }
    }

    void registerTexture(String id, JsonObject data) {
        textures.register(id, data);
        registerFrameCallback(id, FrameCallbackKind.TEXTURE);
    }

    void registerObjectPropertyReader(String identity, NiagaraObjectPropertyReader.Definition definition) {
        var reader = propertyReaders.computeIfAbsent(identity, ignored -> new NiagaraObjectPropertyReader(definition));
        if (!reader.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara object reader definition: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.OBJECT_PROPERTY_READER);
    }

    NiagaraObjectPropertyReader objectPropertyReader(String identity) {
        return Objects.requireNonNull(propertyReaders.get(identity), "Uninitialized Niagara object reader: " + identity);
    }

    void registerPropertyInterface(String identity, NiagaraPropertyInterface.Definition definition) {
        propertyInterfaces.computeIfAbsent(identity, ignored -> new NiagaraPropertyInterface(definition));
        registerFrameCallback(identity, FrameCallbackKind.PROPERTY_INTERFACE);
    }

    /** Capture the owner at per-instance initialization before building system VM bindings. */
    void initializePropertyInterfaces(NiagaraPropertyInterfaceInputs owner) {
        for (var callback : frameCallbackOrder) {
            if (callback.kind() != FrameCallbackKind.PROPERTY_INTERFACE) continue;
            var state = propertyInterface(callback.identity());
            if (state.initialFrame() == null) state.capture(owner);
        }
    }

    NiagaraPropertyInterface propertyInterface(String identity) {
        return Objects.requireNonNull(propertyInterfaces.get(identity), "Missing Niagara Property Interface " + identity);
    }

    Map<String, NiagaraPropertyInterface.Frame> capturePropertyInterfaces(Set<String> identities) {
        if (identities.isEmpty()) return Map.of();
        var frames = new HashMap<String, NiagaraPropertyInterface.Frame>();
        for (String identity : identities) frames.put(identity, propertyInterface(identity).frame());
        return Map.copyOf(frames);
    }

    void registerObjectPropertyRequest(String identity, NiagaraObjectPropertyReader.Request request) {
        objectPropertyReader(identity).register(request);
    }

    void registerActorComponent(String identity, NiagaraActorComponent.Definition definition) {
        var state = actorComponents.computeIfAbsent(identity, ignored -> new NiagaraActorComponent(definition));
        if (!state.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara Actor Component definition: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.ACTOR_COMPONENT);
    }

    NiagaraActorComponent actorComponent(String identity) {
        return Objects.requireNonNull(actorComponents.get(identity), "Uninitialized Niagara Actor Component: " + identity);
    }

    void registerCollision(String identity) {
        collisions.computeIfAbsent(identity, ignored -> new NiagaraCollisionState());
        registerFrameCallback(identity, FrameCallbackKind.COLLISION);
    }

    void registerStaticMesh(String identity, NiagaraStaticMeshDefinition definition, boolean requiresCpuAccess, boolean gpuOnly) {
        var state = staticMeshes.computeIfAbsent(identity, ignored -> new NiagaraStaticMeshReader(identity, definition));
        if (!state.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara static mesh reader definition: " + identity);
        state.setUsage(requiresCpuAccess, gpuOnly);
        registerFrameCallback(identity, FrameCallbackKind.STATIC_MESH);
    }

    NiagaraStaticMeshReader staticMesh(String identity) {
        return Objects.requireNonNull(staticMeshes.get(identity), "Uninitialized Niagara Static Mesh Reader: " + identity);
    }

    void registerSkeletalMesh(String identity, @Nullable String sourcePath, NiagaraSkeletalMeshDefinition definition) {
        var state = skeletalMeshes.computeIfAbsent(identity, ignored -> new NiagaraSkeletalMeshReader(identity, sourcePath, definition));
        if (!state.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara skeletal mesh reader definition: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.SKELETAL_MESH);
    }

    NiagaraSkeletalMeshReader skeletalMesh(String identity) {
        return Objects.requireNonNull(skeletalMeshes.get(identity), "Uninitialized Niagara Skeletal Mesh Reader: " + identity);
    }

    void registerParticleExport(String identity, NiagaraParticleExportDefinition definition) {
        var state = particleExports.computeIfAbsent(identity, ignored -> new NiagaraParticleExportState(definition));
        if (!state.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara particle export definition: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.PARTICLE_EXPORT);
    }

    NiagaraParticleExportState particleExport(String identity) {
        return Objects.requireNonNull(particleExports.get(identity), "Uninitialized Niagara Particle Export: " + identity);
    }

    void registerDataChannel(String identity, NiagaraDataChannelDefinition definition) {
        var state = dataChannels.computeIfAbsent(identity, ignored -> new NiagaraDataChannelState(definition));
        if (!state.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara data channel definition: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.DATA_CHANNEL);
    }

    NiagaraDataChannelState dataChannel(String identity) {
        return Objects.requireNonNull(dataChannels.get(identity), "Uninitialized Niagara Data Channel: " + identity);
    }

    boolean hasDataChannels() { return !dataChannels.isEmpty(); }
    void recordChannelCache(@Nullable NiagaraCacheCapture capture) { dataChannels.values().forEach(channel -> channel.recordCache(capture)); }
    void discardChannelCacheFrame() { dataChannels.values().forEach(NiagaraDataChannelState::discardCacheFrame); }

    void registerSimCacheReader(String id, NiagaraSimCacheReaderDefinition definition) {
        var reader = simCacheReaders.computeIfAbsent(id, ignored -> new NiagaraSimCacheReader(definition));
        if (!reader.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara SimCache Reader definition: " + id);
    }

    void captureSimCacheReaders(Map<String, NiagaraSimulationCache> overrides, Map<Identifier, NiagaraSimulationCache> assets) {
        for (var reader : simCacheReaders.values()) reader.capture(overrides, assets, instanceParameters);
    }

    Map<String, NiagaraSimCacheReader.Bound> captureSimCacheReaders(Set<String> gpu) {
        if (gpu.isEmpty()) return Map.of();
        var values = new HashMap<String, NiagaraSimCacheReader.Bound>();
        for (var id : gpu) values.put(id, simCacheReader(id).bound());
        return Map.copyOf(values);
    }

    NiagaraSimCacheReader simCacheReader(String id) {
        return Objects.requireNonNull(simCacheReaders.get(id), "Uninitialized Niagara SimCache Reader: " + id);
    }

    void volumeCacheAssets(Map<Identifier, NiagaraVolumeCacheAsset> assets) { volumeCacheAssets = assets; }

    void registerPhysicsAsset(String id, JsonObject data, Map<Identifier, SkeletalMeshAsset> meshes) {
        physicsAssets.computeIfAbsent(SourceNames.canonical(id),
                ignored -> new NiagaraPhysicsAssetReader(id, NiagaraPhysicsAssetReader.Definition.read(data, meshes)));
    }

    void registerRigidMesh(String id, JsonObject definition) {
        var state = rigidMeshes.computeIfAbsent(SourceNames.canonical(id),
                ignored -> new NiagaraRigidMeshCollision(id, NiagaraRigidMeshCollision.Definition.read(definition)));
        if (scriptedRigidMeshSearch) state.scripted();
        registerFrameCallback(id, FrameCallbackKind.RIGID_MESH_COLLISION);
    }

    void scriptedRigidMeshSearch() {
        scriptedRigidMeshSearch = true;
        rigidMeshes.values().forEach(NiagaraRigidMeshCollision::scripted);
    }

    NiagaraRigidMeshCollision rigidMesh(String id) {
        return Objects.requireNonNull(rigidMeshes.get(SourceNames.canonical(id)), "Unregistered Niagara Rigid Mesh Collision " + id);
    }

    void registerSceneCapture(String id, JsonObject definition) {
        sceneCaptures.computeIfAbsent(SourceNames.canonical(id), ignored -> new NiagaraSceneCapture(id, definition));
        registerFrameCallback(id, FrameCallbackKind.SCENE_CAPTURE);
    }

    NiagaraSceneCapture sceneCapture(String id) {
        return Objects.requireNonNull(sceneCaptures.get(SourceNames.canonical(id)), "Unregistered Niagara Scene Capture 2D " + id);
    }

    Map<String, NiagaraSceneCapture.Publication> captureSceneCaptures(Set<String> ids, boolean deferred) {
        if (ids.isEmpty()) return Map.of();
        var frames = new HashMap<String, NiagaraSceneCapture.Publication>();
        try {
            for (String id : ids) frames.put(id, sceneCapture(id).publish(deferred));
            return Map.copyOf(frames);
        } catch (RuntimeException | Error failure) {
            frames.values().forEach(NiagaraSceneCapture.Publication::close);
            throw failure;
        }
    }

    private void clearSceneCaptures() {
        sceneCaptures.values().forEach(NiagaraSceneCapture::close);
        sceneCaptures.clear();
    }

    void registerLandscape(String id, JsonObject data, NiagaraLandscape.Usage usage) {
        landscapes.computeIfAbsent(SourceNames.canonical(id), ignored -> new NiagaraLandscape(data)).usage(usage);
        registerFrameCallback(id, FrameCallbackKind.LANDSCAPE);
    }

    void registerLandscapeObject(String id, JsonObject data, boolean gpu) {
        var landscape = landscapes.computeIfAbsent(SourceNames.canonical(id), ignored -> new NiagaraLandscape(data));
        if (gpu) landscape.gpuUse();
        registerFrameCallback(id, FrameCallbackKind.LANDSCAPE);
    }

    NiagaraLandscape landscape(String id) {
        return Objects.requireNonNull(landscapes.get(SourceNames.canonical(id)), "Unregistered Niagara Landscape " + id);
    }

    boolean hasLandscapes() { return !landscapes.isEmpty(); }

    Map<String, NiagaraLandscape.Frame> captureLandscapes(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var frames = new HashMap<String, NiagaraLandscape.Frame>();
        try {
            for (String id : ids) frames.put(id, landscape(id).publish());
            return Map.copyOf(frames);
        } catch (RuntimeException | Error failure) {
            frames.values().forEach(NiagaraLandscape.Frame::close);
            throw failure;
        }
    }

    void registerVirtualTexture(String id, JsonObject data) {
        virtualTextures.computeIfAbsent(SourceNames.canonical(id), ignored -> new NiagaraVirtualTextureState(data));
    }

    NiagaraVirtualTextureState virtualTexture(String id) {
        return Objects.requireNonNull(virtualTextures.get(SourceNames.canonical(id)), "Unregistered Niagara virtual texture " + id);
    }

    void beginVirtualTextures(NiagaraVirtualTextureInputs inputs, Map<String, NiagaraStreamingVirtualTexture> packaged,
                              Map<String, NiagaraRuntimeVirtualTexture> placed, Vec3 origin) {
        for (var texture : virtualTextures.values()) texture.begin(inputs, packaged, placed, instanceParameters, origin);
    }

    Map<String, NiagaraVirtualTextureState.Frame> captureVirtualTextures(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var frames = new HashMap<String, NiagaraVirtualTextureState.Frame>();
        try {
            for (String id : ids) frames.put(id, virtualTexture(id).publish());
            return Map.copyOf(frames);
        } catch (RuntimeException | Error failure) {
            frames.values().forEach(NiagaraVirtualTextureState.Frame::close);
            throw failure;
        }
    }

    private void clearVirtualTextures() {
        virtualTextures.values().forEach(NiagaraVirtualTextureState::close);
        virtualTextures.clear();
    }

    Map<String, NiagaraRigidMeshCollision.Frame> rigidMeshSnapshots(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<String, NiagaraRigidMeshCollision.Frame>();
        for (String id : ids) {
            var captured = rigidMesh(id).frame();
            if (captured != null) result.put(id, captured);
        }
        return Map.copyOf(result);
    }

    void capturePhysicsAssets(NiagaraSkeletalMeshInputs inputs, EffectHandle.Pose pose) {
        for (var asset : physicsAssets.values()) asset.capture(inputs, pose);
    }

    Map<String, NiagaraPhysicsAssetReader.Frame> physicsAssetSnapshots(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<String, NiagaraPhysicsAssetReader.Frame>();
        for (String id : ids) {
            var reader = physicsAssets.get(SourceNames.canonical(id));
            var captured = reader == null ? null : reader.frame();
            if (captured != null) result.put(id, captured);
        }
        return Map.copyOf(result);
    }

    NiagaraVolumeCache volumeCache(String id, JsonObject definition) {
        return volumeCaches.computeIfAbsent(SourceNames.canonical(id), ignored -> new NiagaraVolumeCache(definition, volumeCacheAssets));
    }

    Map<String, NiagaraVolumeCache.Snapshot> captureVolumeCaches(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<String, NiagaraVolumeCache.Snapshot>();
        for (String id : ids) {
            var value = volumeCaches.get(SourceNames.canonical(id));
            if (value != null) result.put(id, value.snapshot());
        }
        return Map.copyOf(result);
    }

    void registerSparseVolume(String id, SparseVolumeInterfaceDefinition definition) {
        var texture = sparseVolumes.computeIfAbsent(id, ignored -> new NiagaraSparseVolumeTexture(definition));
        if (!texture.definition().equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara sparse volume definition: " + id);
    }

    void captureSparseVolumes(Map<String, SparseVolumeTextureAsset> overrides, Map<Identifier, SparseVolumeTextureAsset> assets) {
        for (var texture : sparseVolumes.values()) texture.capture(overrides, assets, instanceParameters);
    }

    Map<String, NiagaraSparseVolumeTexture.Snapshot> captureSparseVolumes(Set<String> gpuIds) {
        if (gpuIds.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraSparseVolumeTexture.Snapshot>();
        for (String id : gpuIds) snapshots.put(id, sparseVolume(id).snapshot());
        return Map.copyOf(snapshots);
    }

    NiagaraSparseVolumeTexture sparseVolume(String id) {
        return Objects.requireNonNull(sparseVolumes.get(id), "Uninitialized Niagara sparse volume: " + id);
    }

    void registerMemoryBuffer(String id, NiagaraMemoryBufferDefinition definition, int maximum, boolean usesCpuWords) {
        var buffer = memoryBuffers.computeIfAbsent(id, ignored -> new NiagaraMemoryBuffer(definition, maximum));
        if (!buffer.definition().equals(definition)) throw new IllegalArgumentException("Conflicting Niagara memory buffer definition: " + id);
        if (usesCpuWords) buffer.requireCpu();
    }

    public NiagaraMemoryBuffer memoryBuffer(String id) {
        return Objects.requireNonNull(memoryBuffers.get(id), "Uninitialized Niagara memory buffer: " + id);
    }

    void registerNeighborGrid(String id, NeighborGridDefinition definition, int deviceMaximum, NiagaraPlatformEnvironment environment) {
        neighborGrids.computeIfAbsent(id, ignored -> new NiagaraNeighborGrid(definition, deviceMaximum, environment));
    }

    public NiagaraNeighborGrid neighborGrid(String id) {
        return Objects.requireNonNull(neighborGrids.get(id), "Uninitialized Niagara Neighbor Grid3D: " + id);
    }

    Map<String, NiagaraNeighborGrid.Snapshot> captureNeighborGrids(Set<String> gpu) {
        if (gpu.isEmpty()) return Map.of();
        var states = new HashMap<String, NiagaraNeighborGrid.Snapshot>();
        for (String id : gpu) states.put(id, neighborGrid(id).snapshot());
        return Map.copyOf(states);
    }

    void registerNeighborQuery(String id, NiagaraNeighborQuery.Definition definition, long deviceCells) {
        neighborQueries.computeIfAbsent(id, ignored -> new NiagaraNeighborQuery(definition, deviceCells));
    }

    NiagaraNeighborQuery neighborQuery(String id) {
        return Objects.requireNonNull(neighborQueries.get(id), "Uninitialized Niagara Neighbor Query: " + id);
    }

    Map<String, NiagaraNeighborQuery.Snapshot> captureNeighborQueries(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var states = new HashMap<String, NiagaraNeighborQuery.Snapshot>();
        for (String id : ids) states.put(id, neighborQuery(id).snapshot());
        return Map.copyOf(states);
    }

    void registerRasterizationGrid(String id, RasterizationGridDefinition definition, int maximum) {
        rasterizationGrids.computeIfAbsent(id, ignored -> new NiagaraRasterizationGrid(definition, maximum));
    }

    public NiagaraRasterizationGrid rasterizationGrid(String id) {
        return Objects.requireNonNull(rasterizationGrids.get(id), "Uninitialized Niagara Rasterization Grid3D: " + id);
    }

    void registerVelocityGrid(String id, VelocityGridDefinition definition) {
        velocityGrids.computeIfAbsent(id, ignored -> new NiagaraVelocityGrid(definition));
    }

    public NiagaraVelocityGrid velocityGrid(String id) {
        return Objects.requireNonNull(velocityGrids.get(id), "Uninitialized Niagara Velocity/Pressure Grid: " + id);
    }

    void beginVelocityGrids(EffectHandle.Pose pose) {
        for (var grid : velocityGrids.values()) grid.begin(pose);
    }

    void registerGrid3DCollection(String id, Grid3DCollectionDefinition definition, NiagaraPlatformEnvironment environment, boolean cpu) {
        grid3DCollections.register(id, definition, environment, cpu);
        registerFrameCallback(id, FrameCallbackKind.GRID_3D_COLLECTION);
    }

    NiagaraGrid3DCollection grid3DCollection(String id) {
        return grid3DCollections.grid(id);
    }

    void registerGrid2DCollection(String id, Grid2DCollectionDefinition definition, NiagaraPlatformEnvironment environment, boolean cpu) {
        grid2DCollections.register(id, definition, environment, cpu);
        registerFrameCallback(id, FrameCallbackKind.GRID_2D_COLLECTION);
    }

    NiagaraGrid2DCollection grid2DCollection(String id) { return grid2DCollections.grid(id); }
    void beginGridCollectionBindings() { grid2DCollections.beginBindings(); grid3DCollections.beginBindings(); }
    void configureGridCollections(List<Use> uses) { grid2DCollections.configure(uses); grid3DCollections.configure(uses); }
    Map<String, NiagaraGrid3DCollections.Publication> captureGrid3DCollections() { return grid3DCollections.capture(); }
    Map<String, NiagaraGrid2DCollections.Publication> captureGrid2DCollections() { return grid2DCollections.capture(); }

    void registerDataTable(String id, NiagaraDataTable.Definition definition, NiagaraParameterStore parameters) {
        dataTables.computeIfAbsent(id, ignored -> {
            var binding = parameters.objectBinding(definition.parameter());
            return new NiagaraDataTable(definition, binding != null && binding.valid()
                    ? NiagaraParameterStoreSources.dataTableAsset(binding.value()) : null);
        });
    }

    NiagaraDataTable dataTable(String id) {
        return Objects.requireNonNull(dataTables.get(id), "Uninitialized Niagara Data Table: " + id);
    }

    Map<String, NiagaraDataTable> captureDataTables() { return Map.copyOf(dataTables); }
    Map<String, NiagaraGaussianSplatReader> captureGaussianSplatReaders() { return Map.copyOf(gaussianSplatReaders); }

    void registerGaussianSplatReader(String id, NiagaraGaussianSplatReader.Definition definition,
                                    Map<Identifier, NiagaraGaussianSplatAsset> assets) {
        gaussianSplatReaders.computeIfAbsent(id, ignored -> new NiagaraGaussianSplatReader(definition.asset() == null
                ? NiagaraGaussianSplatAsset.EMPTY : Objects.requireNonNull(assets.get(definition.asset()),
                        "Missing prepared Niagara Gaussian Splat " + definition.asset()), definition.maxShDegree()));
    }

    NiagaraGaussianSplatReader gaussianSplatReader(String id) {
        return Objects.requireNonNull(gaussianSplatReaders.get(id), "Uninitialized Niagara Gaussian Splat Reader: " + id);
    }

    void registerGaussianSplatWriter(String id, NiagaraGaussianSplatWriter.Definition definition,
                                    Map<Identifier, NiagaraGaussianSplatAsset> assets, long maxStorageBytes) {
        gaussianSplatWriters.computeIfAbsent(id, ignored -> new NiagaraGaussianSplatWriter(definition, assets,
                platformEnvironment(), maxStorageBytes));
    }

    NiagaraGaussianSplatWriter gaussianSplatWriter(String id) {
        return Objects.requireNonNull(gaussianSplatWriters.get(id), "Uninitialized Niagara Gaussian Splat Writer: " + id);
    }

    Map<String, NiagaraGaussianSplatWriter.Snapshot> publishGaussianSplatWriters() {
        if (gaussianSplatWriters.isEmpty()) return Map.of();
        var result = new HashMap<String, NiagaraGaussianSplatWriter.Snapshot>();
        gaussianSplatWriters.forEach((id, writer) -> result.put(id, writer.publish()));
        return Map.copyOf(result);
    }

    void registerHairStrands(String id, HairStrandsDefinition definition) {
        hairStrands.computeIfAbsent(id, ignored -> new NiagaraHairStrandsState(definition));
    }

    void registerHairStrands(String id, JsonObject source) {
        if (!hairStrands.containsKey(id)) registerHairStrands(id, HairStrandsDefinition.read(source));
    }

    NiagaraHairStrandsState hairStrands(String id) {
        return Objects.requireNonNull(hairStrands.get(id), "Uninitialized Niagara HairStrands: " + id);
    }

    void beginHairStrands(NiagaraGroomInputs inputs, float delta) {
        for (var state : hairStrands.values()) state.step(inputs, delta);
    }

    Map<String, HairStrandsFrame> captureHairStrands(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var frames = new HashMap<String, HairStrandsFrame>();
        for (String id : ids) frames.put(id, hairStrands(id).frame());
        return Map.copyOf(frames);
    }

    void registerChaosDestruction(String id, ChaosDestructionDefinition definition) {
        chaosDestructions.computeIfAbsent(id, ignored -> new NiagaraChaosDestructionState(definition));
    }

    void registerChaosDestruction(String id, JsonObject source) {
        if (!chaosDestructions.containsKey(id)) registerChaosDestruction(id, ChaosDestructionDefinition.read(source));
    }

    NiagaraChaosDestructionState chaosDestruction(String id) {
        return Objects.requireNonNull(chaosDestructions.get(id), "Uninitialized Niagara Chaos Destruction: " + id);
    }

    void requestChaosEvents(Consumer<EventDemand> enable) {
        for (var state : chaosDestructions.values()) state.eventDemand().forEach(enable);
    }

    void beginChaosDestruction(NiagaraChaosScene scene) {
        for (var state : chaosDestructions.values()) state.step(scene);
    }

    Map<String, ChaosDestructionFrame> captureChaosDestruction(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var frames = new HashMap<String, ChaosDestructionFrame>();
        for (String id : ids) frames.put(id, chaosDestruction(id).frame());
        return Map.copyOf(frames);
    }

    void registerGeometryCollection(String id, GeometryCollectionDefinition definition, @Nullable RestCollection saved) {
        geometryCollections.computeIfAbsent(id, ignored -> new NiagaraGeometryCollectionState(definition, saved));
        registerFrameCallback(id, FrameCallbackKind.GEOMETRY_COLLECTION);
    }

    void registerGeometryCollection(String id, JsonObject source) {
        if (!geometryCollections.containsKey(id)) registerGeometryCollection(id, GeometryCollectionDefinition.read(source),
                GeometryCollectionAssetJson.read(source.get("saved_collection")));
    }

    NiagaraGeometryCollectionState geometryCollection(String id) {
        return Objects.requireNonNull(geometryCollections.get(id), "Uninitialized Niagara Geometry Collection: " + id);
    }

    Map<String, GeometryCollectionFrame> captureGeometryCollections(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<String, GeometryCollectionFrame>();
        for (String id : ids) result.put(id, geometryCollection(id).capture());
        return Map.copyOf(result);
    }

    void registerWater(String id, WaterDefinition definition, @Nullable NiagaraAuthoredWaterBody authored) {
        waters.computeIfAbsent(id, ignored -> new NiagaraWaterState(definition, authored));
    }

    NiagaraWaterState water(String id) {
        return Objects.requireNonNull(waters.get(id), "Uninitialized Niagara Water: " + id);
    }

    void beginWaters(NiagaraWaterScene scene, Map<String, String> sourceOverrides, EffectHandle.Pose pose, Vec3 origin,
                     float seconds) {
        if (waters.isEmpty()) return;
        Map<String, String> byIdentity = new HashMap<>();
        if (!sourceOverrides.isEmpty() && instanceParameters != null) {
            for (var slot : instanceParameters.layout().variables()) {
                var key = slot.key();
                if (key.storage() != Storage.INTERFACE || !SourceNames.equal(key.sourceType(), WaterDefinition.TYPE)) continue;
                var replacement = sourceOverrides.get(SourceNames.canonical(key.name()));
                if (replacement != null && instanceParameters.dataInterface(key) instanceof NiagaraDataInterfaceObject object)
                    byIdentity.put(object.identity(), replacement);
            }
        }
        var owner = pose.position();
        var sourceOwner = new Vec3(owner.x * 100, owner.z * 100, owner.y * 100);
        var cvar = platformEnvironment.console(WaterDefinition.DEPTH_QUERY_SUPPORTED);
        boolean queryDepth = cvar != null && cvar.bits() != 0;
        var depth = platformEnvironment.console(WaterDefinition.OCEAN_FALLBACK_DEPTH);
        float oceanFallback = depth == null ? 3000f : Float.intBitsToFloat(depth.bits());
        waters.forEach((id, water) -> water.begin(scene, byIdentity.get(id), sourceOwner, origin, queryDepth, seconds, oceanFallback));
    }

    void registerIntDistribution(String id, NiagaraIntDistribution distribution) {
        intDistributionDefaults.putIfAbsent(id, distribution);
    }

    void beginIntDistributions(Map<String, NiagaraIntDistribution> overrides) {
        if (intDistributionDefaults.isEmpty() || overrides.isEmpty() || instanceParameters == null) {
            intDistributions = Map.copyOf(intDistributionDefaults);
            return;
        }
        var values = new HashMap<>(intDistributionDefaults);
        for (var slot : instanceParameters.layout().variables()) {
            var key = slot.key();
            if (key.storage() != Storage.INTERFACE || !SourceNames.equal(key.sourceType(), NiagaraIntDistribution.TYPE)) continue;
            var replacement = overrides.get(SourceNames.canonical(key.name()));
            if (replacement != null && instanceParameters.dataInterface(key) instanceof NiagaraDataInterfaceObject object
                    && intDistributionDefaults.containsKey(object.identity()))
                values.put(object.identity(), replacement);
        }
        intDistributions = Map.copyOf(values);
    }

    NiagaraIntDistribution intDistribution(String id) {
        return Objects.requireNonNull(intDistributions.get(id), "Missing Niagara integer distribution " + id);
    }

    Map<String, NiagaraIntDistribution> captureIntDistributions() { return intDistributions; }

    void registerSocketReader(String id, NiagaraSocketReader.Definition definition) {
        socketReaders.computeIfAbsent(id, ignored -> new NiagaraSocketReader(definition));
        registerFrameCallback(id, FrameCallbackKind.SOCKET_READER);
    }

    NiagaraSocketReader socketReader(String id) {
        return Objects.requireNonNull(socketReaders.get(id), "Uninitialized Niagara Socket Reader: " + id);
    }

    ComponentTickGroup socketReaderTickGroup(NiagaraSocketInputs socketInputs, NiagaraStaticMeshInputs staticInputs,
                                              NiagaraSkeletalMeshInputs skeletalInputs, Map<Identifier, StaticMeshAsset> staticAssets,
                                              Map<Identifier, SkeletalMeshAsset> skeletalAssets, Predicate<String> objectParameterOverridden,
                                              Function<Object, ActorComponentInputs.@Nullable Binding> savedComponents) {
        var group = ComponentTickGroup.TG_PrePhysics;
        for (var callback : frameCallbackOrder) {
            if (callback.kind() != FrameCallbackKind.SOCKET_READER) continue;
            var reader = socketReader(callback.identity());
            var required = reader.requiredTickGroup(socketInputs, staticInputs, skeletalInputs,
                    instanceParameters, staticAssets, skeletalAssets, objectParameterOverridden.test(reader.parameter()), savedComponents);
            group = ComponentTickGroup.latest(group, required);
        }
        return group;
    }

    Map<String, NiagaraSocketReader.Snapshot> captureSocketReaders() {
        if (socketReaders.isEmpty()) return Map.of();
        var states = new HashMap<String, NiagaraSocketReader.Snapshot>();
        socketReaders.forEach((id, reader) -> states.put(id, reader.snapshot()));
        return Map.copyOf(states);
    }

    void registerSpline(String id, NiagaraSpline.Definition definition, boolean gpu) {
        var spline = splines.computeIfAbsent(id, ignored -> new NiagaraSpline(definition));
        if (gpu) spline.enableGpu();
        registerFrameCallback(id, FrameCallbackKind.SPLINE);
    }

    NiagaraSpline spline(String id) {
        return Objects.requireNonNull(splines.get(id), "Uninitialized Niagara Spline: " + id);
    }

    Map<String, NiagaraSpline.GpuFrame> captureSplines(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var states = new HashMap<String, NiagaraSpline.GpuFrame>();
        for (String id : ids) states.put(id, spline(id).gpuFrame());
        return Map.copyOf(states);
    }

    void registerIntRenderTarget2D(String id, NiagaraIntRenderTarget2D.Definition definition, int maximum,
                                  NiagaraPlatformEnvironment environment, boolean gpu) {
        intRenderTargets.computeIfAbsent(id, ignored -> new NiagaraIntRenderTarget2D(definition, maximum, environment, gpu))
                .initialize(environment, gpu);
    }

    NiagaraIntRenderTarget2D intRenderTarget2D(String id) {
        return Objects.requireNonNull(intRenderTargets.get(id), "Uninitialized Niagara integer render target: " + id);
    }

    Map<String, NiagaraIntRenderTarget2D.Snapshot> captureIntRenderTargets() {
        if (intRenderTargets.isEmpty()) return Map.of();
        var values = new HashMap<String, NiagaraIntRenderTarget2D.Snapshot>();
        intRenderTargets.forEach((id, target) -> values.put(id, target.snapshot()));
        return Map.copyOf(values);
    }

    Map<String, NiagaraVelocityGrid.Snapshot> captureVelocityGrids(Set<String> gpu) {
        if (gpu.isEmpty()) return Map.of();
        var states = new HashMap<String, NiagaraVelocityGrid.Snapshot>();
        for (String id : gpu) states.put(id, velocityGrid(id).capture());
        return Map.copyOf(states);
    }

    Map<String, NiagaraRasterizationGrid.Snapshot> captureRasterizationGrids(Set<String> gpu) {
        if (gpu.isEmpty()) return Map.of();
        var states = new HashMap<String, NiagaraRasterizationGrid.Snapshot>();
        for (String id : gpu) states.put(id, rasterizationGrid(id).capture());
        return Map.copyOf(states);
    }

    void materialCollectionInputs(Map<String, ?> values) { materialCollectionInputs = values; }

    void registerMaterialCollectionWriter(String identity, NiagaraMaterialCollectionWriter.Definition definition) {
        materialCollectionWriters.computeIfAbsent(identity, ignored -> new NiagaraMaterialCollectionWriter(definition));
        registerFrameCallback(identity, FrameCallbackKind.MATERIAL_COLLECTION_WRITER);
    }

    NiagaraMaterialCollectionWriter materialCollectionWriter(String identity) {
        return Objects.requireNonNull(materialCollectionWriters.get(identity), "Uninitialized Niagara material collection writer: " + identity);
    }

    void registerDynamicMaterialWriter(String identity, NiagaraDynamicMaterialWriter.Definition definition) {
        dynamicMaterialWriters.computeIfAbsent(identity, ignored -> new NiagaraDynamicMaterialWriter(definition));
        registerFrameCallback(identity, FrameCallbackKind.DYNAMIC_MATERIAL);
    }

    NiagaraDynamicMaterialWriter dynamicMaterialWriter(String identity) {
        return Objects.requireNonNull(dynamicMaterialWriters.get(identity), "Uninitialized Niagara dynamic material: " + identity);
    }

    @Nullable DynamicMaterialValues dynamicMaterial(String sourceObject) {
        return savedDynamicMaterials.find(sourceObject);
    }

    void dynamicMaterialObjects(NiagaraMaterialObjects objects) { savedDynamicMaterials = Objects.requireNonNull(objects); }

    private @Nullable DynamicMaterialValues resolveDynamicMaterial(NiagaraDynamicMaterialWriter.Definition definition,
                                                                   Map<String, RendererParameter> rendererParameters) {
        String path = definition.defaultMaterial();
        if (!definition.binding().isBlank()) {
            var value = RendererParameter.named(rendererParameters, definition.binding());
            var host = NiagaraDynamicMaterialWriter.host(value);
            if (host != null) return host;
            if (instanceParameters != null && instanceParameters.objectBinding(definition.binding()).value() instanceof ObjectValue object
                    && object.source().has("class") && object.source().get("class").getAsString().equals("MaterialInstanceDynamic")) {
                String saved = object.source().get("path").getAsString();
                if (value == null || usesMaterialSource(value, saved)) path = saved;
            }
        }
        return path.isBlank() || SourceNames.equal(path, "None") ? null : savedDynamicMaterials.load(path);
    }

    private static boolean usesMaterialSource(RendererParameter value, String source) {
        if (value instanceof RendererParameter.Material material)
            return material.value() != null && SourceNames.equal(material.value().sourceObject(), source);
        return value instanceof RendererParameter.MaterialVariants variants
                && variants.values().values().stream().anyMatch(variant -> usesMaterialSource(variant, source));
    }

    void registerDynamicMesh(String id, NiagaraDynamicMeshDefinition definition) {
        var mesh = dynamicMeshes.computeIfAbsent(id, ignored -> new NiagaraDynamicMesh(definition));
        if (!mesh.definition().equals(definition)) throw new IllegalArgumentException("Conflicting Niagara dynamic mesh definition: " + id);
    }

    public NiagaraDynamicMesh dynamicMesh(String id) {
        return Objects.requireNonNull(dynamicMeshes.get(id), "Uninitialized Niagara dynamic mesh: " + id);
    }

    void beginDynamicMeshes() { dynamicMeshes.values().forEach(NiagaraDynamicMesh::beginFrame); }
    Map<String, NiagaraDynamicMeshFrame> captureDynamicMeshes(Set<String> gpu) {
        if (gpu.isEmpty()) return Map.of();
        var values = new HashMap<String, NiagaraDynamicMeshFrame>();
        for (String id : gpu) values.put(id, dynamicMesh(id).publish());
        return Map.copyOf(values);
    }

    Map<String, NiagaraMemoryBuffer.Snapshot> captureMemoryBuffers(Set<String> gpu) {
        if (gpu.isEmpty()) return Map.of();
        var values = new HashMap<String, NiagaraMemoryBuffer.Snapshot>();
        for (String id : gpu) values.put(id, memoryBuffer(id).snapshot());
        return Map.copyOf(values);
    }
    void systemFixedBounds(@Nullable NiagaraBounds bounds) { emitterBounds.values().forEach(emitter -> emitter.systemFixedBounds(bounds)); }
    record ChannelTickRange(ComponentTickGroup earliest, ComponentTickGroup latest) { }

    ChannelTickRange dataChannelTickRange(int forcedReadGroup) {
        var earliest = ComponentTickGroup.TG_PrePhysics;
        var latest = ComponentTickGroup.TG_LastDemotable;
        for (var callback : frameCallbackOrder) {
            if (callback.kind() != FrameCallbackKind.DATA_CHANNEL) continue;
            var channel = dataChannel(callback.identity());
            earliest = ComponentTickGroup.latest(earliest, channel.earliestTickGroup(forcedReadGroup));
            var deadline = channel.latestTickGroup();
            if (deadline.ordinal() < latest.ordinal()) latest = deadline;
        }
        return new ChannelTickRange(earliest, latest);
    }
    void publishDataChannelSpawns() {
        for (var callback : frameCallbackOrder)
            if (callback.kind() == FrameCallbackKind.DATA_CHANNEL) dataChannel(callback.identity()).publishSpawns();
    }
    List<NiagaraGpuSpawnInfo.Entry> dataChannelSpawns(int emitter) {
        if (dataChannels.isEmpty()) return List.of();
        var entries = new ArrayList<NiagaraGpuSpawnInfo.Entry>();
        for (var callback : frameCallbackOrder)
            if (callback.kind() == FrameCallbackKind.DATA_CHANNEL) entries.addAll(dataChannel(callback.identity()).spawns(emitter));
        return entries;
    }
    void registerAudioPlayer(String identity, NiagaraAudioPlayerDefinition definition) {
        var state = audioPlayers.computeIfAbsent(identity, ignored -> new NiagaraAudioPlayerState(definition));
        if (!state.definition().equals(definition)) throw new IllegalArgumentException("Conflicting Niagara Audio Player definition: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.AUDIO_PLAYER);
    }
    NiagaraAudioPlayerState audioPlayer(String identity) {
        return Objects.requireNonNull(audioPlayers.get(identity), "Uninitialized Niagara Audio Player: " + identity);
    }
    void registerAudioAnalysis(String identity, NiagaraAudioAnalysisDefinition definition) {
        var prior = audioAnalysis.putIfAbsent(identity, definition);
        if (prior != null && !prior.equals(definition)) throw new IllegalArgumentException("Conflicting Niagara audio analyzer: " + identity);
        registerFrameCallback(identity, FrameCallbackKind.AUDIO_ANALYSIS);
    }
    NiagaraAudioBusAnalysis.Frame audioFrame(String identity) {
        return audioFrames.getOrDefault(identity, NiagaraAudioBusAnalysis.Frame.EMPTY);
    }
    Map<String, NiagaraAudioBusAnalysis.Frame> captureAudioFrames(Set<String> active) {
        if (active.isEmpty()) return Map.of();
        var frames = new HashMap<String, NiagaraAudioBusAnalysis.Frame>();
        for (String identity : active) frames.put(identity, audioFrame(identity));
        return Map.copyOf(frames);
    }

    ComponentTickGroup skeletalMeshTickGroup(NiagaraSkeletalMeshInputs inputs, Predicate<String> objectParameterOverridden) {
        var group = ComponentTickGroup.TG_PrePhysics;
        for (var callback : frameCallbackOrder) {
            if (callback.kind() != FrameCallbackKind.SKELETAL_MESH) continue;
            var reader = skeletalMesh(callback.identity());
            group = ComponentTickGroup.latest(group, reader.requiredTickGroup(inputs, instanceParameters,
                    objectParameterOverridden.test(reader.definition().meshParameter())));
        }
        return group;
    }

    Map<String, NiagaraStaticMeshReader.GpuSnapshot> captureStaticMeshes(Set<String> active) {
        if (active.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraStaticMeshReader.GpuSnapshot>();
        for (String identity : active) snapshots.put(identity, staticMesh(identity).captureGpu());
        return Map.copyOf(snapshots);
    }

    Map<String, NiagaraSkeletalGpuSnapshot> captureSkeletalMeshes(Set<String> active) {
        if (active.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraSkeletalGpuSnapshot>();
        for (String identity : active) snapshots.put(identity, skeletalMesh(identity).gpuSnapshot());
        return Map.copyOf(snapshots);
    }

    Map<String, NiagaraParticleExportState.Snapshot> captureParticleExports(Set<String> active) {
        if (active.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraParticleExportState.Snapshot>();
        for (String identity : active) {
            var snapshot = particleExport(identity).snapshot();
            if (snapshot != null) snapshots.put(identity, snapshot);
        }
        return Map.copyOf(snapshots);
    }

    Map<String, NiagaraDataChannelGpuSnapshot> captureDataChannelReaders(Set<String> active, int emitters) {
        if (active.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraDataChannelGpuSnapshot>();
        try {
            for (String identity : active) snapshots.put(identity, dataChannel(identity).gpuSnapshot(emitters));
            return Map.copyOf(snapshots);
        } catch (RuntimeException | Error failure) {
            snapshots.values().forEach(NiagaraDataChannelGpuSnapshot::close);
            throw failure;
        }
    }

    Map<String, NiagaraDataChannelState.GpuWriter> captureDataChannelWriters(Set<String> active) {
        if (active.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraDataChannelState.GpuWriter>();
        try {
            for (String identity : active) snapshots.put(identity, dataChannel(identity).gpuWriter());
            return Map.copyOf(snapshots);
        } catch (RuntimeException | Error failure) {
            snapshots.values().forEach(NiagaraDataChannelState.GpuWriter::close);
            throw failure;
        }
    }

    NiagaraCollisionState collision(String identity) {
        return Objects.requireNonNull(collisions.get(identity), "Uninitialized Niagara Collision Query: " + identity);
    }

    ComponentTickGroup actorComponentTickGroup(ActorComponentInputs inputs,
            IntFunction<ActorComponentInputs.@Nullable Component> localPlayers,
            Function<Object, ActorComponentInputs.@Nullable Binding> savedComponents) {
        var group = ComponentTickGroup.TG_PrePhysics;
        for (var callback : frameCallbackOrder) {
            if (callback.kind() != FrameCallbackKind.ACTOR_COMPONENT) continue;
            var required = actorComponent(callback.identity()).requiredTickGroup(inputs, instanceParameters, localPlayers, savedComponents);
            group = ComponentTickGroup.latest(group, required);
        }
        return group;
    }

    Map<String, NiagaraActorComponent.Snapshot> captureActorComponents() {
        if (actorComponents.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraActorComponent.Snapshot>();
        actorComponents.forEach((identity, component) -> snapshots.put(identity, component.snapshot()));
        return Map.copyOf(snapshots);
    }

    Map<String, NiagaraObjectPropertyReader.Snapshot> captureObjectProperties() {
        if (propertyReaders.isEmpty()) return Map.of();
        var snapshots = new HashMap<String, NiagaraObjectPropertyReader.Snapshot>();
        propertyReaders.forEach((identity, reader) -> snapshots.put(identity, reader.snapshot()));
        return Map.copyOf(snapshots);
    }

    void applyObjectReaderRemaps(Map<String, Map<String, ObjectPropertyInputs.Remap>> remaps, boolean initialize) {
        propertyReaders.forEach((identity, reader) -> {
            reader.remaps(remaps.getOrDefault(identity, Map.of()));
            if (initialize) reader.reinitialize();
        });
    }

    public NiagaraRenderTarget2DState registerRenderTarget2D(String id, JsonObject data,
                                                                                                                         int maximum, float resolutionMultiplier, int projectFormat) {
        return registerRenderTarget2D(id, data, maximum, resolutionMultiplier, projectFormat, -1);
    }

    public NiagaraRenderTarget2DState registerRenderTarget2D(String id, JsonObject data, int maximum) {
        return registerRenderTarget2D(id, data, maximum, platformEnvironment);
    }

    public NiagaraRenderTarget2DState registerRenderTarget2D(String id, JsonObject data, int maximum,
                                                             NiagaraPlatformEnvironment environment) {
        var settings = environment.renderTargetSettings();
        return registerRenderTarget2D(id, data, maximum, environment.renderTargetResolutionMultiplier(),
                settings.defaultFormat(), settings.overrideFormat());
    }

    private NiagaraRenderTarget2DState registerRenderTarget2D(String id, JsonObject data, int maximum,
                                                              float resolutionMultiplier, int projectFormat, int overrideFormat) {
        if (id == null || id.isEmpty() || data == null) throw new IllegalArgumentException("Invalid RenderTarget2D source");
        var value = renderTargets2D.computeIfAbsent(id, ignored -> {
            var created = new NiagaraRenderTarget2DState(data, maximum, resolutionMultiplier, projectFormat, overrideFormat);
            created.initializeFormat(renderTargetFormats());
            return created;
        });
        if (!value.matches(data)) throw new IllegalArgumentException("RenderTarget2D identity has conflicting definitions: " + id);
        registerFrameCallback(id, FrameCallbackKind.RENDER_TARGET_2D);
        return value;
    }

    private boolean[] renderTargetFormats() {
        if (renderTargetFormatSupport == null) {
            RenderSystem.assertOnRenderThread();
            var support = new boolean[11];
            try (var runtime = ComputeRuntime.create(RenderSystem.getDevice())) {
                for (int i = 0; i < support.length; i++) support[i] = runtime.supportsColorImageStores(
                        RenderTargetTextureDefinition.gpuFormat(i));
            }
            renderTargetFormatSupport = support;
        }
        return renderTargetFormatSupport;
    }

    public void registerDimensionalTarget(String id, JsonObject data, int maximum, NiagaraPlatformEnvironment environment) {
        var settings = environment.renderTargetSettings();
        var state = dimensionalTargets.computeIfAbsent(id, ignored ->
                new NiagaraDimensionalRenderTargetState(data, dimensionalTargetMaximum(data, maximum),
                        environment.renderTargetResolutionMultiplier(), settings.defaultFormat(), settings.overrideFormat(), renderTargetFormats()));
        if (!state.matches(data)) throw new IllegalArgumentException("Render target array identity has conflicting definitions: " + id);
        registerFrameCallback(id, FrameCallbackKind.DIMENSIONAL_TARGET);
    }

    private static int dimensionalTargetMaximum(JsonObject data, int arrayMaximum) {
        if (data.has("cube") && data.get("cube").getAsBoolean())
            return com.kltyton.lumenfx.client.render.texture.cube.CubeColorTarget.maximumSize();
        if (data.has("volume") && data.get("volume").getAsBoolean())
            return com.kltyton.lumenfx.client.render.compute.ComputeVolume.maximumSize();
        return arrayMaximum;
    }

    public NiagaraDimensionalRenderTargetState dimensionalTarget(String id) {
        return Objects.requireNonNull(dimensionalTargets.get(id), "Render target array was not prepared: " + id);
    }

    public List<String> dimensionalTargetOrder() {
        return frameCallbackOrder.stream().filter(callback -> callback.kind() == FrameCallbackKind.DIMENSIONAL_TARGET)
                .map(FrameCallback::identity).toList();
    }
    public NiagaraRenderTarget2DState renderTarget2D(String id) {
        return Objects.requireNonNull(renderTargets2D.get(id), "RenderTarget2D was not prepared before simulation: " + id);
    }

    public List<String> renderTarget2DOrder() {
        return frameCallbackOrder.stream().filter(callback -> callback.kind() == FrameCallbackKind.RENDER_TARGET_2D)
                .map(FrameCallback::identity).toList();
    }

    /** Array object values survive; completed per-instance mutations and resources are released. */
    public void destroyInstanceResources() {
        ribbonShapes.clear();
        activeRibbonShapes.clear();
        physicsField.clear();
        arrays.values().forEach(ArrayState::destroyInstance);
        cameras.clear(); counters.clear();
        propertyReaders.clear();
        propertyInterfaces.clear();
        actorComponents.clear();
        collisions.clear();
        staticMeshes.clear();
        skeletalMeshes.clear();
        particleExports.clear();
        dataChannels.values().forEach(NiagaraDataChannelState::close);
        dataChannels.clear();
        audioPlayers.values().forEach(NiagaraAudioPlayerState::close);
        audioPlayers.clear();
        audioAnalysis.clear();
        simCacheReaders.clear();
        sparseVolumes.clear();
        volumeCaches.clear();
        physicsAssets.clear();
        rigidMeshes.clear();
        clearSceneCaptures();
        landscapes.clear();
        clearVirtualTextures();
        hostLandscape = null;
        memoryBuffers.clear();
        neighborGrids.clear();
        neighborQueries.clear();
        rasterizationGrids.clear();
        velocityGrids.clear();
        waters.clear();
        geometryCollections.clear();
        chaosDestructions.clear();
        hairStrands.clear();
        grid3DCollections.clear();
        grid2DCollections.clear();
        dataTables.clear();
        gaussianSplatReaders.clear();
        gaussianSplatWriters.clear();
        intDistributionDefaults.clear();
        intDistributions = Map.of();
        intRenderTargets.clear();
        socketReaders.clear();
        splines.clear();
        needsMousePosition = needsMovieRenderSample = false;
        viewQueries = NiagaraViewQueries.EMPTY;
        materialCollectionWriters.clear();
        dynamicMaterialWriters.clear();
        dynamicMeshes.clear();
        audioFrames.clear();
        dimensionalTargets.clear();
        renderTargets2D.values().forEach(target -> target.release(true));
        renderTargets2D.clear(); frameCallbacks.clear(); frameCallbackOrder.clear();
        textures.clear(); gpuTargetExposure = null; gpuDimensionalTargetExposure = null;
        current = null;
    }

    Map<String, EffectHandle.ArrayParameter> arrayResults() {
        Map<String, EffectHandle.ArrayParameter> next = new HashMap<>(publishedArrays);
        boolean changed = false;
        for (ArrayState array : arrays.values()) synchronized (array) {
            boolean dirty = false;
            for (String name : array.parameterNames) {
                Long old = publishedVersions.get(name);
                if (old == null || old.longValue() != array.version) { dirty = true; break; }
            }
            if (dirty) {
                ArrayParameter value = array.result();
                for (String name : array.parameterNames) {
                    next.put(name, value);
                    publishedVersions.put(name, array.version);
                }
                changed = true;
            }
        }
        if (changed) publishedArrays = Map.copyOf(next);
        return publishedArrays;
    }

    ArrayState array(VectorProgram.External binding) {
        if (binding.interfaceId().isEmpty()) throw new IllegalArgumentException("Niagara array interface has no instance identity");
        ArrayState state = array(binding.interfaceId(), binding.interfaceParameter(), binding.interfaceData());
        synchronized (state) { state.usedByCpu = true; }
        return state;
    }

    public ArrayState array(String id, String parameterName, JsonObject data) {
        if (id == null || id.isEmpty() || data == null) throw new IllegalArgumentException("Invalid Niagara array interface source");
        // The executable store supplies aliases for collection slots after rebinding.
        String alias = collectionArrayParameters.contains(SourceNames.canonical(parameterName)) ? "" : parameterName;
        ArrayState state = arrays.computeIfAbsent(id, ignored -> {
            ArrayState created = new ArrayState(data, alias);
            created.synchronize(ArrayParameter.latest(arrayParameters, created.parameterNames));
            return created;
        });
        if (!alias.isEmpty()) synchronized (state) {
            if (state.parameterNames.add(alias))
                state.synchronize(ArrayParameter.latest(arrayParameters, state.parameterNames));
        }
        return state;
    }
    public ArrayState array(String id) {
        return Objects.requireNonNull(arrays.get(id), "Array was not registered before simulation: " + id);
    }

    private CameraState camera(VectorProgram.External binding) {
        if (binding.interfaceId().isEmpty()) throw new IllegalArgumentException("Niagara camera interface has no instance identity");
        var result = cameras.computeIfAbsent(binding.interfaceId(), ignored -> {
            var definition = new CameraDefinition(binding.controllerIndex(), binding.requireCurrent());
            var state = new CameraState(() -> definition);
            state.capture(current, originX, originY, originZ);
            return state;
        });
        if (!frameCallbacks.containsKey(binding.interfaceId()))
            registerFrameCallback(binding.interfaceId(), FrameCallbackKind.CAMERA);
        return result;
    }
    void registerCamera(String identity, CameraDefinition definition) {
        cameras.computeIfAbsent(identity, ignored -> new CameraState(() -> definition));
        registerFrameCallback(identity, FrameCallbackKind.CAMERA);
    }
    void registerCamera(NiagaraDataInterfaceObject object) {
        Supplier<CameraDefinition> configuration = () -> Objects.requireNonNull(object.camera(), "Camera object lost its configuration: " + object.identity());
        cameras.computeIfAbsent(object.identity(), ignored -> new CameraState(configuration)).configuration = configuration;
        registerFrameCallback(object.identity(), FrameCallbackKind.CAMERA);
    }
    public int cameraSplitscreen(String identity) {
        return Objects.requireNonNull(cameras.get(identity), "Camera instance was not initialized: " + identity).values.splitscreen();
    }

    Map<String, Integer> captureCameraModes() {
        if (cameras.isEmpty()) return Map.of();
        var modes = new HashMap<String, Integer>();
        cameras.forEach((id, state) -> modes.put(id, state.values.splitscreen()));
        return Map.copyOf(modes);
    }

    MutableGpuInputs captureMutableGpuInputs(Set<String> identities) {
        var arrayInputs = new HashMap<String, ArraySnapshot>();
        var counterInputs = new HashMap<String, CounterSnapshot>();
        for (String identity : identities) {
            var array = arrays.get(identity);
            if (array != null) arrayInputs.put(identity, array.snapshot());
            else {
                var counter = Objects.requireNonNull(counters.get(identity), "Unregistered GPU mutable interface: " + identity);
                counterInputs.put(identity, new CounterSnapshot(counter.get(), counter.revision()));
            }
        }
        return new MutableGpuInputs(arrayInputs, counterInputs);
    }

    Map<String, NiagaraEmitterBounds.Snapshot> captureEmitterBounds() {
        var snapshots = new HashMap<String, NiagaraEmitterBounds.Snapshot>();
        emitterBounds.forEach((name, state) -> snapshots.put(name, state.renderSnapshot()));
        return Map.copyOf(snapshots);
    }

    float[] cameraProperties(VectorProgram.External binding) {
        return camera(binding).values.properties().clone();
    }

    float fieldOfView(VectorProgram.External binding) {
        return camera(binding).values.fov();
    }
    int splitscreen(VectorProgram.External binding) { return camera(binding).values.splitscreen(); }

    void queueDistance(VectorProgram.External binding, int index, int tag, float x, float y, float z) {
        var state = camera(binding);
        var location = state.values.properties();
        float dx = x - location[0], dy = y - location[1], dz = z - location[2];
        state.pending.add(new ParticleDistance(index, tag, dx * dx + dy * dy + dz * dz));
    }

    Set<Long> closest(VectorProgram.External binding, int count) {
        List<ParticleDistance> sorted = camera(binding).sorted;
        if (count <= 0 || sorted.isEmpty()) return Set.of();
        Set<Long> result = new HashSet<>();
        for (int i = 0, limit = Math.min(count, sorted.size()); i < limit; i++) {
            ParticleDistance value = sorted.get(i);
            result.add(id(value.index(), value.tag()));
        }
        return result;
    }

    static long id(int index, int tag) {
        return ((long) index << 32) | Integer.toUnsignedLong(tag);
    }
}
