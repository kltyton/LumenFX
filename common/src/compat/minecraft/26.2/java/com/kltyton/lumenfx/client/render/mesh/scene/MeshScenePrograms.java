package com.kltyton.lumenfx.client.render.mesh.scene;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshFrame;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraMeshSceneShaders;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Resource-generation kernels; instance storage belongs to the host transient allocator. */
public final class MeshScenePrograms implements AutoCloseable {
    public record Output(GpuBufferSlice transforms, GpuBufferSlice info, GpuBufferSlice metrics) { }
    private record Key(Object particles, Object renderer, MeshRendererSettings.Entry entry, boolean localSpace, boolean previous) { }
    private final ComputeRuntime runtime;
    private final ComputeKernel cpu, gpu;
    private final GpuBuffer dummy, emptyParticleUniforms;
    private final MeshSceneCompactor compactor;
    private final com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCulling culling;
    private final Map<Key, Output> preparedGpu = new HashMap<>();
    private boolean closed;

    public MeshScenePrograms(PreparedNiagaraMeshSceneShaders prepared) {
        RenderSystem.assertOnRenderThread();
        runtime = ComputeRuntime.create(RenderSystem.getDevice());
        ComputeKernel cpuCandidate = null, gpuCandidate = null;
        GpuBuffer dummyCandidate = null, uniformCandidate = null;
        MeshSceneCompactor compactorCandidate = null;
        com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCulling cullingCandidate = null;
        try {
            cpuCandidate = runtime.createKernel(prepared.variants().get("cpu").shader());
            gpuCandidate = runtime.createKernel(prepared.variants().get("gpu").shader());
            dummyCandidate = RenderSystem.getDevice().createBuffer(() -> "Niagara.MeshScene.Empty",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), 4);
            uniformCandidate = RenderSystem.getDevice().createBuffer(() -> "Niagara.MeshScene.UnusedParticleUniforms",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                    com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.MESH_BYTES);
            runtime.clearBuffer(dummyCandidate.slice(), 0);
            runtime.clearBuffer(uniformCandidate.slice(), 0);
            compactorCandidate = new MeshSceneCompactor(runtime, prepared.compaction());
            cullingCandidate = new com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCulling(runtime, prepared.culling(), compactorCandidate, dummyCandidate);
            cpu = cpuCandidate; gpu = gpuCandidate; dummy = dummyCandidate; emptyParticleUniforms = uniformCandidate;
            compactor = compactorCandidate;
            culling = cullingCandidate;
        } catch (RuntimeException | Error error) {
            if (cullingCandidate != null) cullingCandidate.close();
            if (compactorCandidate != null) compactorCandidate.close();
            if (uniformCandidate != null) uniformCandidate.close();
            if (dummyCandidate != null) dummyCandidate.close();
            if (gpuCandidate != null) gpuCandidate.close();
            if (cpuCandidate != null) cpuCandidate.close();
            runtime.close();
            throw error;
        }
    }

    public void beginDraw() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Mesh scene programs have been released");
        preparedGpu.clear();
    }

    public long storageAlignment() { return runtime.storageBufferAlignment(); }
    public MeshSceneCompactor compactor() {
        if (closed) throw new IllegalStateException("Mesh scene programs have been released");
        return compactor;
    }

    public com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCulling culling() {
        if (closed) throw new IllegalStateException("Mesh scene programs have been released");
        return culling;
    }

    public Output withCompaction(CommandEncoder encoder, Output prepared,
                                 com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSortCollector.Sorted compacted) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(Math.toIntExact(prepared.transforms().offset() / 16))
                    .putInt(compacted.offset()).putInt(0).putInt(0);
            return new Output(prepared.transforms(), mapped.slice(), prepared.metrics());
        }
    }

    public Output prepareCpu(CommandEncoder encoder, MeshBatchGeometry geometry, GpuBufferSlice meshUniforms,
                             MeshSceneView view, GpuBufferSlice vertices, int vertexBase, GpuBuffer extra,
                             int first, int count, int extraStride, boolean previous,
                             @Nullable MeshEmitterSceneData emitterScene) {
        long start = vertices.offset() + ((long)vertexBase + (long)first * 35) * Float.BYTES;
        long aligned = start / runtime.storageBufferAlignment() * runtime.storageBufferAlignment();
        GpuBufferSlice input = new GpuBufferSlice(vertices.buffer(), aligned, start - aligned + (long)count * 35 * Float.BYTES);
        return prepare(encoder, cpu, geometry, meshUniforms, emptyParticleUniforms.slice(), view,
                dummy.slice(), dummy.slice(), input, extra.slice(), dummy.slice(), first, count, 0,
                Math.toIntExact((start - aligned) / Float.BYTES), 0, extraStride, previous ? 4 : 0, emitterScene);
    }

    public Output prepareGpu(CommandEncoder encoder, NiagaraGpuMeshFrame frame, GpuBufferSlice meshUniforms,
                             MeshSceneView view, boolean previous) {
        var geometry = frame.geometry();
        var key = new Key(frame.particles(), frame.sceneRenderer() == null ? frame.uniforms() : frame.sceneRenderer(),
                geometry.mesh(), geometry.localSpace(), previous);
        Output existing = preparedGpu.get(key);
        if (existing != null) return existing;
        int count = frame.emitterSource() ? 1 : frame.particles().current().stride();
        Output output = prepare(encoder, gpu, geometry, meshUniforms, frame.uniforms(), view,
                frame.floatData().slice(), frame.halfData().slice(), dummy.slice(), dummy.slice(), frame.particles().countBuffer(),
                0, count, frame.particles().currentCountOffset(), 0, 0, 0, (frame.emitterSource() ? 1 : 0) | (previous ? 4 : 0), frame.emitterScene());
        preparedGpu.put(key, output);
        return output;
    }

    private Output prepare(CommandEncoder encoder, ComputeKernel kernel, MeshBatchGeometry geometry,
                           GpuBufferSlice meshUniforms, GpuBufferSlice particleUniforms, MeshSceneView view,
                           GpuBufferSlice floats, GpuBufferSlice halves, GpuBufferSlice vertices, GpuBufferSlice extras,
                           GpuBufferSlice counts, int first, int count, int countOffset,
                           int vertexBase, int extraBase, int extraStride, int flags,
                           @Nullable MeshEmitterSceneData emitterScene) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Mesh scene programs have been released");
        var mode = geometry.renderer().sceneTransforms();
        if (mode == MeshRendererSettings.SceneTransforms.LEGACY)
            throw new IllegalArgumentException("Legacy mesh transforms do not use GPUScene preparation");
        long bytes = Math.multiplyExact((long)count, 144);
        if (bytes > runtime.maxStorageBufferBytes() || (long)count * 9 > runtime.maxTexelBufferElements())
            throw new IllegalArgumentException("Mesh scene instance buffer exceeds the device binding limit");
        GpuBufferSlice output = encoder.transientMemory().allocateGpu(bytes, Math.max(16, runtime.storageBufferAlignment()),
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
        GpuBufferSlice metrics = encoder.transientMemory().allocateGpu(Math.multiplyExact((long)count, 16),
                Math.max(16, runtime.storageBufferAlignment()), ComputeUsage.storageBuffer(0));
        if (mode == MeshRendererSettings.SceneTransforms.GPU_SCENE_COMPRESSED) flags |= 2;
        GpuBufferSlice emitterData = dummy.slice();
        if (emitterScene != null) {
            if (count != 1) throw new IllegalArgumentException("Emitter scene upload requires exactly one instance");
            flags |= 8;
            try (var mapped = encoder.transientMemory().allocateGpuMapped(MeshEmitterSceneData.BYTES, runtime.storageBufferAlignment(),
                    ComputeUsage.storageBuffer(0))) {
                emitterScene.write(mapped.data().order(ByteOrder.nativeOrder()));
                emitterData = mapped.slice();
            }
        }
        int groups = Math.ceilDiv(count, kernel.program().workgroupX());
        int height = Math.ceilDiv(groups, runtime.maxWorkGroups(0));
        int width = height == 1 ? count : Math.multiplyExact(Math.ceilDiv(groups, height), kernel.program().workgroupX());
        if (height > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Mesh scene dispatch exceeds the device workgroup limit");
        var distanceCenter = geometry.sourceBounds().origin().multiply(geometry.mesh().scale());
        GpuBufferSlice camera = view.write(encoder, first, count, countOffset, flags, vertexBase, extraBase, extraStride, width, distanceCenter);
        var resources = ComputeBindings.builder();
        for (ShaderBinding binding : kernel.program().bindings()) {
            switch (binding) {
                case ShaderBinding.UniformBuffer uniform -> resources.uniform(uniform.name(), switch (uniform.name()) {
                    case "LumenMeshSceneView" -> camera;
                    case "NiagaraMeshData" -> meshUniforms;
                    case "NiagaraParticleData" -> particleUniforms;
                    default -> throw new IllegalArgumentException("Unknown mesh scene uniform " + uniform.name());
                });
                case ShaderBinding.StorageBuffer storage -> resources.storage(storage.name(), switch (storage.name()) {
                    case "NiagaraParticleDataFloat" -> floats;
                    case "NiagaraParticleDataHalf" -> halves;
                    case "CpuMeshInstances" -> vertices;
                    case "CpuMeshExtra" -> extras;
                    case "SceneParticleCounts" -> counts;
                    case "CpuEmitterSceneData" -> emitterData;
                    case "OutMeshSceneTransforms" -> output;
                    case "OutMeshSceneMetrics" -> metrics;
                    default -> throw new IllegalArgumentException("Unknown mesh scene buffer " + storage.name());
                }, storage.access());
                default -> throw new IllegalArgumentException("Unexpected mesh scene descriptor " + binding.name());
            }
        }
        kernel.dispatch(resources.build(), width, height, 1);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(Math.toIntExact(output.offset() / 16)).putInt(0).putInt(0).putInt(0);
            return new Output(output, mapped.slice(), metrics);
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        preparedGpu.clear();
        culling.close(); compactor.close(); emptyParticleUniforms.close(); dummy.close(); gpu.close(); cpu.close(); runtime.close();
    }
}
