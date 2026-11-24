package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.resource.shader.NiagaraEmitterShaderAbi;

import com.kltyton.lumenfx.client.render.depth.SceneDepthDefaults;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraDrawShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraSortShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraIdShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraStatelessShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraGaussianSplatShaders;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraAllocationHistory;
import com.kltyton.lumenfx.client.render.niagara.stateless.NiagaraStatelessDataCache;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.NiagaraGpuSharedMeshFields;
import com.kltyton.lumenfx.client.render.niagara.gpu.physics.NiagaraGpuPhysicsFields;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuTerrainImages;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.kltyton.lumenfx.client.render.texture.NativeColorTarget;
import org.joml.Vector4f;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.ClientLevel;

/** Render-thread compute programs shared by instances in one resource generation. */
public final class NiagaraGpuPrograms implements AutoCloseable {
    private static int tickCounter;
    private record Stage(ComputeKernel kernel, NiagaraGpuComputeStage dispatch) { }
    private record GridStageKey(Identifier emitter, int stage, String variant) { }
    private final Map<GridStageKey, PreparedNiagaraShaders.Stage> gridStageSources = new LinkedHashMap<>();
    private final Map<GridStageKey, Stage> gridStages = new LinkedHashMap<>();
    private final ComputeRuntime runtime;
    private final NiagaraGpuSharedMeshFields sharedMeshFields = new NiagaraGpuSharedMeshFields(this);
    private final Map<ClientLevel, NiagaraGpuAsyncTraces> worldAsyncTraces = new IdentityHashMap<>();
    private final NiagaraGpuPhysicsFields physicsFields = new NiagaraGpuPhysicsFields();
    private final NiagaraGpuTerrainImages terrainImages = new NiagaraGpuTerrainImages();
    private final boolean usesSharedMeshFields;
    private final NiagaraGpuDispatchGrid grid;
    private final Map<Identifier, Map<Integer, Map<String, Stage>>> programs;
    private final Map<Identifier, NiagaraGpuCurves> curves;
    private final Map<Identifier, NiagaraGpuVectorFields> vectorFields;
    private final Map<Identifier, NiagaraGpuMeshInfo> meshInformation;
    private final Map<VectorFieldVolume, ComputeVolume> volumes;
    private final Stage drawIndirect;
    private final @Nullable Stage asyncTrace;
    private final @Nullable Stage physicsFieldBuilder;
    private final Map<String, Stage> groomCacheInterpolation;
    private final @Nullable PreparedNiagaraStatelessShaders statelessShaders;
    private @Nullable Stage stateless;
    private @Nullable GpuBuffer statelessNoise;
    private @Nullable NiagaraStatelessDataCache statelessData;
    private final Map<NiagaraDefinition.Emitter, GpuBuffer> statelessStatic = new IdentityHashMap<>();
    private final GpuBuffer dummy;
    private com.kltyton.lumenfx.client.render.texture.NativeColorTarget blackTexture;
    private @Nullable NativeColorTarget blackAlphaOneTexture;
    private com.kltyton.lumenfx.client.render.texture.NativeColorTarget whiteSceneTexture;
    private com.mojang.blaze3d.pipeline.TextureTarget graySceneTexture;
    private final SceneDepthDefaults sceneDepthDefaults = new SceneDepthDefaults();
    private com.kltyton.lumenfx.client.render.compute.ComputeTexture blackArrayTexture;
    private com.kltyton.lumenfx.client.render.texture.cube.CubeColorTarget blackCubeTexture;
    private com.kltyton.lumenfx.client.render.compute.ComputeVolume blackVolumeTexture;
    private @Nullable ComputeVolume blackIntegerVolumeTexture;
    private final PreparedNiagaraSortShaders sortShaders;
    private final PreparedNiagaraIdShaders idShaders;
    private final @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders;
    private final Map<String, Stage> sortStages = new LinkedHashMap<>();
    private final Map<String, Stage> gaussianSplatStages = new LinkedHashMap<>();
    private final Map<String, Stage> targetMipStages = new LinkedHashMap<>();
    private com.mojang.blaze3d.textures.GpuSampler targetPointSampler, targetLinearSampler;
    private final Map<Identifier, NiagaraAllocationHistory> allocationEstimates = new LinkedHashMap<>();
    private boolean closed;

    public NiagaraGpuPrograms(Map<Identifier, PreparedNiagaraShaders> prepared, PreparedNiagaraDrawShaders drawShaders,
                              PreparedNiagaraSortShaders sortShaders, PreparedNiagaraIdShaders idShaders) {
        this(prepared, drawShaders, sortShaders, idShaders, null, null);
    }
    public NiagaraGpuPrograms(Map<Identifier, PreparedNiagaraShaders> prepared, PreparedNiagaraDrawShaders drawShaders,
                              PreparedNiagaraSortShaders sortShaders, PreparedNiagaraIdShaders idShaders,
                              @Nullable PreparedNiagaraStatelessShaders statelessShaders) {
        this(prepared, drawShaders, sortShaders, idShaders, statelessShaders, null);
    }
    public NiagaraGpuPrograms(Map<Identifier, PreparedNiagaraShaders> prepared, PreparedNiagaraDrawShaders drawShaders,
                              PreparedNiagaraSortShaders sortShaders, PreparedNiagaraIdShaders idShaders,
                              @Nullable PreparedNiagaraStatelessShaders statelessShaders,
                              @Nullable PreparedNiagaraGaussianSplatShaders gaussianSplatShaders) {
        RenderSystem.assertOnRenderThread();
        this.statelessShaders = statelessShaders;
        this.gaussianSplatShaders = gaussianSplatShaders;
        usesSharedMeshFields = prepared.values().stream().flatMap(emitter -> emitter.stages().values().stream())
                .flatMap(stages -> stages.values().stream()).flatMap(stage -> stage.shader().bindings().stream())
                .anyMatch(binding -> binding.name().equals("LfxSharedMeshObjects"));
        runtime = ComputeRuntime.create(RenderSystem.getDevice());
        Map<Identifier, Map<Integer, Map<String, Stage>>> loaded = new LinkedHashMap<>();
        Map<Identifier, NiagaraGpuCurves> curveResources = new LinkedHashMap<>();
        Map<Identifier, NiagaraGpuVectorFields> fieldResources = new LinkedHashMap<>();
        Map<Identifier, NiagaraGpuMeshInfo> meshInfoResources = new LinkedHashMap<>();
        Map<VectorFieldVolume, ComputeVolume> uploadedVolumes = new IdentityHashMap<>();
        List<ComputeKernel> created = new ArrayList<>();
        GpuBuffer dummyBuffer = null;
        try {
            grid = new NiagaraGpuDispatchGrid(runtime);
            dummyBuffer = RenderSystem.getDevice().createBuffer(() -> "Niagara.EmptyParticleData",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), Integer.BYTES);
            runtime.clearBuffer(dummyBuffer.slice(), 0);
            dummy = dummyBuffer;
            if (drawShaders == null) throw new IllegalArgumentException("Missing Niagara GPU indirect-draw shaders");
            if (statelessShaders != null && !NiagaraEmitterShaderAbi.matchesUtility(statelessShaders.engineHead(), drawShaders.engineHead()))
                throw new IllegalArgumentException("Niagara analytic and utility shader engine versions differ");
            if (gaussianSplatShaders != null && !drawShaders.engineHead().equals(gaussianSplatShaders.engineHead()))
                throw new IllegalArgumentException("Niagara Gaussian Splat and simulation shader engine versions differ");
            if (sortShaders == null || !drawShaders.engineHead().equals(sortShaders.engineHead()))
                throw new IllegalArgumentException("Missing or incompatible Niagara GPU sort shaders");
            this.sortShaders = sortShaders;
            if (idShaders == null || !drawShaders.engineHead().equals(idShaders.engineHead()))
                throw new IllegalArgumentException("Missing or incompatible Niagara GPU ID shaders");
            this.idShaders = idShaders;
            var draw = drawShaders.variants().get("separate_reset");
            if (draw == null) throw new IllegalArgumentException("Missing Niagara GPU indirect-draw variant");
            ComputeKernel drawKernel = runtime.createKernel(draw.shader());
            created.add(drawKernel);
            drawIndirect = new Stage(drawKernel, new NiagaraGpuComputeStage(draw, drawKernel));
            Stage traceStage = null;
            Stage fieldBuilder = null;
            var groomInterpolation = new LinkedHashMap<String, Stage>();
            for (var emitter : prepared.entrySet()) {
                if (!NiagaraEmitterShaderAbi.matchesUtility(emitter.getValue().engineHead(), drawShaders.engineHead()))
                    throw new IllegalArgumentException("Niagara GPU shader engine version mismatch: " + emitter.getKey());
                var traceShaders = emitter.getValue().asyncTraceShaders();
                if (traceShaders != null) {
                    if (!drawShaders.engineHead().equals(traceShaders.engineHead()))
                        throw new IllegalArgumentException("Niagara async trace shader engine version mismatch: " + emitter.getKey());
                    if (traceStage == null) {
                        var source = traceShaders.globalDistanceField();
                        var kernel = runtime.createKernel(source.shader());
                        created.add(kernel);
                        traceStage = new Stage(kernel, new NiagaraGpuComputeStage(source, kernel));
                    }
                }
                var fieldShaders = emitter.getValue().physicsFieldShaders();
                if (fieldShaders != null) {
                    if (!drawShaders.engineHead().equals(fieldShaders.engineHead()))
                        throw new IllegalArgumentException("Niagara Chaos field shader engine version mismatch: " + emitter.getKey());
                    if (fieldBuilder == null) {
                        var source = fieldShaders.buildClipmap();
                        var kernel = runtime.createKernel(source.shader());
                        created.add(kernel);
                        fieldBuilder = new Stage(kernel, new NiagaraGpuComputeStage(source, kernel));
                    }
                }
                var groomShaders = emitter.getValue().groomCacheShaders();
                if (groomShaders != null) {
                    if (!drawShaders.engineHead().equals(groomShaders.engineHead()))
                        throw new IllegalArgumentException("Niagara groom cache shader engine version mismatch: " + emitter.getKey());
                    for (var variant : groomShaders.interpolation().entrySet()) {
                        if (groomInterpolation.containsKey(variant.getKey())) continue;
                        var source = variant.getValue();
                        var kernel = runtime.createKernel(source.shader());
                        created.add(kernel);
                        groomInterpolation.put(variant.getKey(), new Stage(kernel, new NiagaraGpuComputeStage(source, kernel)));
                    }
                }
                var targets = emitter.getValue().targetShaders();
                if (targets != null) {
                    if (!drawShaders.engineHead().equals(targets.engineHead()))
                        throw new IllegalArgumentException("Niagara render target shader engine version mismatch");
                    for (var entry : targets.mipPrograms().entrySet()) {
                        if (targetMipStages.containsKey(entry.getKey())) continue;
                        ComputeKernel kernel = runtime.createKernel(entry.getValue().shader());
                        created.add(kernel);
                        targetMipStages.put(entry.getKey(), new Stage(kernel, new NiagaraGpuComputeStage(entry.getValue(), kernel)));
                    }
                }
                curveResources.put(emitter.getKey(), new NiagaraGpuCurves(emitter.getKey().toString(), emitter.getValue().curves(), runtime));
                fieldResources.put(emitter.getKey(), new NiagaraGpuVectorFields(emitter.getValue().vectorFields(),
                        field -> uploadedVolumes.computeIfAbsent(field, value -> ComputeVolume.create(emitter.getKey().toString(), value))));
                meshInfoResources.put(emitter.getKey(), new NiagaraGpuMeshInfo(emitter.getKey().toString(), emitter.getValue().meshes(), runtime));
                Map<Integer, Map<String, Stage>> stages = new LinkedHashMap<>();
                for (var stage : emitter.getValue().stages().entrySet()) {
                    Map<String, Stage> variants = new LinkedHashMap<>();
                    for (var variant : stage.getValue().entrySet()) {
                        if (variant.getValue().metadata().has("niagaraGridCollection")) {
                            gridStageSources.put(new GridStageKey(emitter.getKey(), stage.getKey(), variant.getKey()), variant.getValue());
                            continue;
                        }
                        ComputeKernel kernel = runtime.createKernel(variant.getValue().shader());
                        created.add(kernel);
                        variants.put(variant.getKey(), new Stage(kernel, new NiagaraGpuComputeStage(variant.getValue(), kernel)));
                    }
                    stages.put(stage.getKey(), Map.copyOf(variants));
                }
                loaded.put(emitter.getKey(), Map.copyOf(stages));
            }
            programs = Map.copyOf(loaded);
            asyncTrace = traceStage;
            physicsFieldBuilder = fieldBuilder;
            groomCacheInterpolation = Map.copyOf(groomInterpolation);
            curves = Map.copyOf(curveResources);
            vectorFields = Map.copyOf(fieldResources);
            meshInformation = Map.copyOf(meshInfoResources);
            volumes = uploadedVolumes;
        } catch (RuntimeException | Error error) {
            for (ComputeKernel kernel : created) kernel.close();
            curveResources.values().forEach(NiagaraGpuCurves::close);
            meshInfoResources.values().forEach(NiagaraGpuMeshInfo::close);
            uploadedVolumes.values().forEach(ComputeVolume::close);
            if (dummyBuffer != null) dummyBuffer.close();
            runtime.close();
            throw error;
        }
    }

    public NiagaraGpuCurves curves(Identifier emitter) {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        NiagaraGpuCurves result = curves.get(emitter);
        if (result == null) throw new IllegalArgumentException("Missing Niagara GPU curves " + emitter);
        return result;
    }

    public NiagaraGpuVectorFields vectorFields(Identifier emitter) {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        NiagaraGpuVectorFields result = vectorFields.get(emitter);
        if (result == null) throw new IllegalArgumentException("Missing Niagara GPU vector fields " + emitter);
        return result;
    }
    public ComputeVolume vectorFieldVolume(VectorFieldVolume source) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        return volumes.computeIfAbsent(source, value -> ComputeVolume.create("Niagara.VectorField", value));
    }

    public NiagaraGpuMeshInfo meshInformation(Identifier emitter) {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        NiagaraGpuMeshInfo result = meshInformation.get(emitter);
        if (result == null) throw new IllegalArgumentException("Missing Niagara GPU mesh information " + emitter);
        return result;
    }

    public NiagaraGpuComputeStage drawIndirect() {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        return drawIndirect.dispatch();
    }

    public NiagaraGpuComputeStage stateless() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        if (statelessShaders == null) throw new IllegalStateException("Missing Niagara analytic shader bundle");
        if (stateless == null) {
            ComputeKernel kernel = runtime.createKernel(statelessShaders.simulation().shader());
            try { stateless = new Stage(kernel, new NiagaraGpuComputeStage(statelessShaders.simulation(), kernel)); }
            catch (RuntimeException | Error error) { kernel.close(); throw error; }
        }
        return stateless.dispatch();
    }

    public GpuBuffer statelessNoise() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        if (statelessNoise == null) {
            float[] values = NativeStatelessMath.noiseLookupData();
            long bytes = Math.multiplyExact((long)values.length, Float.BYTES);
            if (bytes > runtime.maxStorageBufferBytes()) throw new IllegalStateException("Niagara analytic noise LUT exceeds the device buffer limit");
            ByteBuffer data = MemoryUtil.memAlloc(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
            try {
                for (float value : values) data.putFloat(value);
                data.flip();
                statelessNoise = RenderSystem.getDevice().createBuffer(() -> "Niagara.Stateless.NoiseLUT",
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
            } finally { MemoryUtil.memFree(data); }
        }
        return statelessNoise;
    }

    public NiagaraStatelessDataCache statelessData() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        if (statelessData == null) statelessData = new NiagaraStatelessDataCache(runtime);
        return statelessData;
    }

    public @Nullable NiagaraStatelessDataCache beginStatelessFrame(int requests) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        if (requests == 0 && statelessData == null) return null;
        NiagaraStatelessDataCache cache = statelessData();
        cache.beginFrame(requests);
        return cache;
    }

    public GpuBuffer statelessStaticData(NiagaraDefinition.Emitter emitter, int[] words) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        GpuBuffer buffer = statelessStatic.get(emitter);
        if (buffer != null) return buffer;
        long bytes = (long)words.length * Integer.BYTES;
        if (bytes == 0 || bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara analytic static data exceeds the device buffer limit: " + emitter.sourceName());
        ByteBuffer data = MemoryUtil.memAlloc(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (int word : words) data.putInt(word);
            data.flip();
            buffer = RenderSystem.getDevice().createBuffer(() -> "Niagara.Stateless.StaticData." + emitter.sourceName(),
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
            try { statelessStatic.put(emitter, buffer); }
            catch (RuntimeException | Error error) { buffer.close(); throw error; }
            return buffer;
        } finally { MemoryUtil.memFree(data); }
    }

    public GpuBuffer dummyBuffer() {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        return dummy;
    }

    public com.mojang.blaze3d.textures.GpuTextureView blackTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (blackTexture == null) {
            var candidate = new com.kltyton.lumenfx.client.render.texture.NativeColorTarget("Niagara.UnboundTexture", 1, 1, false);
            try { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(candidate.texture(), new org.joml.Vector4f()); }
            catch (RuntimeException | Error error) { candidate.close(); throw error; }
            blackTexture = candidate;
        }
        return blackTexture.view();
    }

    GpuTextureView whiteSceneTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (whiteSceneTexture == null) {
            var candidate = new com.kltyton.lumenfx.client.render.texture.NativeColorTarget("Niagara.WhiteSceneTexture", 1, 1, false);
            try { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(candidate.texture(), new org.joml.Vector4f(1, 1, 1, 1)); }
            catch (RuntimeException | Error failure) { candidate.close(); throw failure; }
            whiteSceneTexture = candidate;
        }
        return whiteSceneTexture.view();
    }

    GpuTextureView blackAlphaOneTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (blackAlphaOneTexture == null) {
            var candidate = new NativeColorTarget("Niagara.BlackAlphaOne", 1, 1, false);
            try { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(candidate.texture(), new Vector4f(0, 0, 0, 1)); }
            catch (RuntimeException | Error failure) { candidate.close(); throw failure; }
            blackAlphaOneTexture = candidate;
        }
        return blackAlphaOneTexture.view();
    }

    GpuTextureView graySceneTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (graySceneTexture == null) {
            var candidate = new com.mojang.blaze3d.pipeline.TextureTarget("Niagara.MidGraySceneTexture", 1, 1, false, GpuFormat.RGBA16_FLOAT);
            try { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(candidate.getColorTexture(), new org.joml.Vector4f(.5f)); }
            catch (RuntimeException | Error failure) { candidate.destroyBuffers(); throw failure; }
            graySceneTexture = candidate;
        }
        return graySceneTexture.getColorTextureView();
    }

    GpuTextureView zeroStencilTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        return sceneDepthDefaults.stencil();
    }

    public com.kltyton.lumenfx.client.render.compute.ComputeTexture blackArrayTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (blackArrayTexture == null)
            blackArrayTexture = runtime().createZeroTexture("Niagara.UnboundArrayTexture", GpuFormat.RGBA8_UNORM, true);
        return blackArrayTexture;
    }

    public GpuTextureView blackCubeTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (blackCubeTexture == null) blackCubeTexture = new com.kltyton.lumenfx.client.render.texture.cube.CubeColorTarget(
                "Niagara.UnboundCubeTexture", GpuFormat.RGBA8_UNORM, 1, new org.joml.Vector4f(0, 0, 0, 1));
        return blackCubeTexture.view();
    }

    public com.kltyton.lumenfx.client.render.compute.ComputeVolume blackVolumeTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (blackVolumeTexture == null) {
            var address = com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address.CLAMP;
            var filter = com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode.POINT;
            var sampler = new com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings(address, address, filter, filter, 1);
            var storage = new com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage(
                    com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat.BGRA8, false);
            blackVolumeTexture = com.kltyton.lumenfx.client.render.compute.ComputeVolume.createSampled("Niagara.UnboundVolumeTexture",
                    1, 1, 1, java.util.List.of(java.nio.ByteBuffer.allocateDirect(4)), storage,
                    new com.kltyton.lumenfx.client.render.compute.ComputeVolume.Sampling(sampler, address));
        }
        return blackVolumeTexture;
    }

    GpuTextureView defaultSceneDepth(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        return sceneDepthDefaults.depth(format);
    }

    public ComputeVolume blackIntegerVolumeTexture() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs have been released");
        if (blackIntegerVolumeTexture == null)
            blackIntegerVolumeTexture = ComputeVolume.createSampled("Niagara.UnboundIntegerVolume", 1, 1, 1,
                    List.of(ByteBuffer.allocateDirect(Integer.BYTES)), new NativeTextureStorage(TextureStorageFormat.R32_UINT, false),
                    NiagaraGlobalDistanceField.PAGE_TABLE_SAMPLING);
        return blackIntegerVolumeTexture;
    }

    public NiagaraGpuComputeStage sortKey(String variant) {
        return sortStage("key/" + variant, sortShaders.keyVariants().get(variant));
    }

    public NiagaraGpuComputeStage bitonic(String pass) {
        var variants = sortShaders.bitonicStages().get(pass);
        return sortStage("bitonic/" + pass, variants == null ? null : variants.get("default"));
    }

    public NiagaraGpuComputeStage radix(String pass) {
        var variants = sortShaders.radixStages().get(pass);
        return sortStage("radix/" + pass, variants == null ? null : variants.get("default"));
    }

    public NiagaraGpuComputeStage neighborQuery(String pass) {
        var variants = sortShaders.neighborQueryStages().get(pass);
        return sortStage("neighbor_query/" + pass, variants == null ? null : variants.get("default"));
    }

    public boolean supportsRibbonGeneration() { return !sortShaders.ribbonStages().isEmpty(); }

    public NiagaraGpuComputeStage ribbonSort(String pass, String variant) {
        var variants = sortShaders.ribbonStages().get(pass);
        return sortStage("ribbon/" + pass + "/" + variant, variants == null ? null : variants.get(variant));
    }

    public NiagaraGpuComputeStage ribbonAggregation(String pass, String variant) {
        var variants = sortShaders.ribbonAggregationStages().get(pass);
        return sortStage("ribbon_aggregation/" + pass + "/" + variant, variants == null ? null : variants.get(variant));
    }

    public NiagaraGpuComputeStage ribbonIndices(String pass, String variant) {
        var variants = sortShaders.ribbonIndexStages().get(pass);
        return sortStage("ribbon_indices/" + pass + "/" + variant, variants == null ? null : variants.get(variant));
    }

    public NiagaraGpuComputeStage persistentIds(String pass) {
        var variants = idShaders.stages().get(pass);
        return sortStage("ids/" + pass, variants == null ? null : variants.get("default"));
    }

    /** Niagara's automatic allocation estimate is the upper median of recorded emitter high-water marks. */
    public int allocationEstimate(Identifier emitter) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        NiagaraAllocationHistory allocations = allocationEstimates.get(emitter);
        return allocations == null ? 0 : allocations.estimate();
    }

    public void recordAllocation(Identifier emitter, long instance, int count) {
        RenderSystem.assertOnRenderThread();
        if (closed || count < 0) throw new IllegalArgumentException("Invalid Niagara GPU allocation");
        allocationEstimates.computeIfAbsent(emitter, unused -> new NiagaraAllocationHistory()).record(instance, count);
    }

    public void removeAllocation(Identifier emitter, long instance) {
        RenderSystem.assertOnRenderThread();
        NiagaraAllocationHistory allocations = allocationEstimates.get(emitter);
        if (allocations == null) return;
        allocations.remove(instance);
    }

    private NiagaraGpuComputeStage sortStage(String name, PreparedNiagaraShaders.Stage prepared) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        if (prepared == null) throw new IllegalArgumentException("Missing Niagara GPU sorting stage " + name);
        Stage existing = sortStages.get(name);
        if (existing != null) return existing.dispatch();
        ComputeKernel kernel = runtime.createKernel(prepared.shader());
        try {
            Stage stage = new Stage(kernel, new NiagaraGpuComputeStage(prepared, kernel));
            sortStages.put(name, stage);
            return stage.dispatch();
        } catch (RuntimeException | Error error) {
            kernel.close();
            throw error;
        }
    }

    public NiagaraGpuComputeStage gaussianSplat(String program, String variant) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        if (gaussianSplatShaders == null) throw new IllegalStateException("Missing Niagara Gaussian Splat shaders");
        String key = program + '/' + variant;
        Stage existing = gaussianSplatStages.get(key);
        if (existing != null) return existing.dispatch();
        PreparedNiagaraShaders.Stage prepared = gaussianSplatShaders.stage(program, variant);
        ComputeKernel kernel = runtime.createKernel(prepared.shader());
        try {
            Stage stage = new Stage(kernel, new NiagaraGpuComputeStage(prepared, kernel));
            gaussianSplatStages.put(key, stage);
            return stage.dispatch();
        } catch (RuntimeException | Error error) {
            kernel.close();
            throw error;
        }
    }

    public ComputeRuntime runtime() {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        return runtime;
    }

    public NiagaraGpuComputeStage targetMipStage(boolean gaussian, boolean srgb) {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        String name = (gaussian ? "blur" : "linear") + (srgb ? "_srgb" : "");
        Stage stage = targetMipStages.get(name);
        if (stage == null) throw new IllegalStateException("Missing Niagara render target mip shader " + name);
        return stage.dispatch();
    }

    public com.mojang.blaze3d.textures.GpuSampler targetMipSampler(boolean point) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        var existing = point ? targetPointSampler : targetLinearSampler;
        if (existing != null) return existing;
        var settings = new com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings(
                com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address.CLAMP,
                com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address.CLAMP,
                point ? com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode.POINT
                        : com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode.LINEAR,
                com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode.POINT, 1);
        var created = com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.create(settings, 1);
        if (point) targetPointSampler = created; else targetLinearSampler = created;
        return created;
    }

    public NiagaraGpuDispatchGrid grid() {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        return grid;
    }

    public int nextTick() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        return ++tickCounter;
    }

    public void observeCachedParticleTag(int tag) {
        RenderSystem.assertOnRenderThread();
        if (Integer.compareUnsigned(tag, tickCounter) > 0) tickCounter = tag;
    }

    public NiagaraGpuComputeStage stage(Identifier emitter, int index, String variant) {
        if (closed) throw new IllegalStateException("Niagara GPU programs are closed");
        Map<Integer, Map<String, Stage>> stages = programs.get(emitter);
        Map<String, Stage> variants = stages == null ? null : stages.get(index);
        Stage stage = variants == null ? null : variants.get(variant);
        if (stage == null) {
            var key = new GridStageKey(emitter, index, variant);
            var source = gridStageSources.get(key);
            if (source != null) stage = gridStages.computeIfAbsent(key, ignored -> {
                var kernel = runtime.createKernel(source.shader());
                try { return new Stage(kernel, new NiagaraGpuComputeStage(source, kernel)); }
                catch (RuntimeException | Error failure) { kernel.close(); throw failure; }
            });
        }
        if (stage == null) throw new IllegalArgumentException("Missing Niagara GPU stage " + emitter + "/" + index + "/" + variant);
        return stage.dispatch();
    }

    NiagaraGpuComputeStage globalDistanceFieldTrace() {
        if (closed || asyncTrace == null) throw new IllegalStateException("Niagara distance field trace shader is unavailable");
        return asyncTrace.dispatch();
    }

    public NiagaraGpuSharedMeshFields sharedMeshFields() { return sharedMeshFields; }
    NiagaraGpuAsyncTraces asyncTraces(ClientLevel level) {
        return worldAsyncTraces.computeIfAbsent(level, ignored -> new NiagaraGpuAsyncTraces(this));
    }
    public void clearAsyncTraces(ClientLevel level) {
        var traces = worldAsyncTraces.remove(level);
        if (traces != null) traces.close();
    }
    public void beginAsyncTraceRenderFrame(ClientLevel level, long renderFrame) {
        var traces = worldAsyncTraces.get(level);
        if (traces != null) traces.frameBoundary(renderFrame);
    }

    public boolean hasAsyncTraceRenderFrame(ClientLevel level, long renderFrame) {
        var traces = worldAsyncTraces.get(level);
        return traces != null && traces.hasDeferredFrame(renderFrame);
    }

    public boolean asyncTraceNeedsDistanceField(ClientLevel level, long renderFrame) {
        var traces = worldAsyncTraces.get(level);
        return traces != null && traces.hasDeferredFrame(renderFrame) && traces.deferredFrameNeedsDistanceField();
    }

    public void finishAsyncTraceRenderFrame(ClientLevel level, CommandEncoder encoder, NiagaraGpuView view) {
        var traces = worldAsyncTraces.get(level);
        if (traces != null) traces.finishDeferredFrame(encoder, view);
    }

    public void abortAsyncTraceRenderFrame(ClientLevel level, long renderFrame) {
        var traces = worldAsyncTraces.get(level);
        if (traces != null && traces.hasDeferredFrame(renderFrame)) traces.abortFrame();
    }
    public NiagaraGpuPhysicsFields physicsFields() { return physicsFields; }
    public NiagaraGpuComputeStage groomCacheInterpolation(boolean skinned) {
        var stage = groomCacheInterpolation.get(skinned ? "local" : "world");
        if (closed || stage == null) throw new IllegalStateException("Niagara groom cache interpolation shader is unavailable");
        return stage.dispatch();
    }

    public NiagaraGpuComputeStage physicsFieldBuilder() {
        if (closed || physicsFieldBuilder == null) throw new IllegalStateException("Niagara Chaos field clipmap shader is unavailable");
        return physicsFieldBuilder.dispatch();
    }
    public NiagaraGpuTerrainImages terrainImages() { return terrainImages; }
    public void captureSharedMeshFields(ClientLevel level) {
        if (usesSharedMeshFields) sharedMeshFields.capture(level);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        programs.values().forEach(stages -> stages.values().forEach(variants -> variants.values().forEach(stage -> stage.kernel().close())));
        gridStages.values().forEach(stage -> stage.kernel().close());
        gridStages.clear();
        gridStageSources.clear();
        drawIndirect.kernel().close();
        if (asyncTrace != null) asyncTrace.kernel().close();
        if (physicsFieldBuilder != null) physicsFieldBuilder.kernel().close();
        groomCacheInterpolation.values().forEach(stage -> stage.kernel().close());
        if (stateless != null) { stateless.kernel().close(); stateless = null; }
        if (statelessNoise != null) { statelessNoise.close(); statelessNoise = null; }
        if (statelessData != null) { statelessData.close(); statelessData = null; }
        statelessStatic.values().forEach(GpuBuffer::close);
        statelessStatic.clear();
        targetMipStages.values().forEach(stage -> stage.kernel().close());
        targetMipStages.clear();
        if (targetPointSampler != null) targetPointSampler.close();
        if (targetLinearSampler != null) targetLinearSampler.close();
        sortStages.values().forEach(stage -> stage.kernel().close());
        sortStages.clear();
        gaussianSplatStages.values().forEach(stage -> stage.kernel().close());
        gaussianSplatStages.clear();
        allocationEstimates.clear();
        sharedMeshFields.close();
        worldAsyncTraces.values().forEach(NiagaraGpuAsyncTraces::close);
        worldAsyncTraces.clear();
        physicsFields.close();
        terrainImages.close();
        curves.values().forEach(NiagaraGpuCurves::close);
        meshInformation.values().forEach(NiagaraGpuMeshInfo::close);
        volumes.values().forEach(ComputeVolume::close);
        volumes.clear();
        dummy.close();
        if (blackTexture != null) { blackTexture.close(); blackTexture = null; }
        if (blackAlphaOneTexture != null) { blackAlphaOneTexture.close(); blackAlphaOneTexture = null; }
        if (whiteSceneTexture != null) { whiteSceneTexture.close(); whiteSceneTexture = null; }
        if (graySceneTexture != null) { graySceneTexture.destroyBuffers(); graySceneTexture = null; }
        sceneDepthDefaults.close();
        if (blackArrayTexture != null) { blackArrayTexture.close(); blackArrayTexture = null; }
        if (blackCubeTexture != null) { blackCubeTexture.close(); blackCubeTexture = null; }
        if (blackVolumeTexture != null) { blackVolumeTexture.close(); blackVolumeTexture = null; }
        if (blackIntegerVolumeTexture != null) { blackIntegerVolumeTexture.close(); blackIntegerVolumeTexture = null; }
        runtime.close();
    }
}
