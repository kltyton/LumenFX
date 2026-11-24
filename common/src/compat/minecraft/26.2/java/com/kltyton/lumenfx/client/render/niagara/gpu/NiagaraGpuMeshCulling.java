package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.mesh.MeshLodSelection;
import com.kltyton.lumenfx.client.render.mesh.scene.MeshSceneCompactor;
import com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Source GPUScene update filters followed by native box-frustum culling and stable instance compaction. */
public final class NiagaraGpuMeshCulling implements AutoCloseable {
    private static final int THREADS = 64;
    private final ComputeRuntime runtime;
    private final ComputeKernel kernel;
    private final MeshSceneCompactor compactor;
    private final GpuBuffer dummy;

    public NiagaraGpuMeshCulling(ComputeRuntime runtime, PreparedNiagaraShaders.Stage prepared,
                                 MeshSceneCompactor compactor, GpuBuffer dummy) {
        this.runtime = runtime; this.compactor = compactor; this.dummy = dummy;
        if (prepared.shader().workgroupX() != THREADS || prepared.shader().workgroupY() != 1 || prepared.shader().workgroupZ() != 1)
            throw new IllegalArgumentException("Mesh instance culling requires its 64-thread prefix layout");
        kernel = runtime.createKernel(prepared.shader());
    }

    public NiagaraGpuSortCollector.Sorted prepare(CommandEncoder encoder, NiagaraGpuMeshCollector.Task task,
                                                   NiagaraGpuMeshFrame frame, MeshScenePrograms.Output matrices,
                                                   NiagaraGpuSortCollector.@Nullable Sorted sorted, @Nullable NiagaraGpuView view) {
        RenderSystem.assertOnRenderThread();
        int count = frame.emitterSource() ? 1 : frame.particles().estimate().current();
        int blocks = Math.ceilDiv(count, THREADS);
        long alignment = Math.max(16, runtime.storageBufferAlignment());
        var raw = encoder.transientMemory().allocateGpu((long)count * Integer.BYTES, alignment, ComputeUsage.storageBuffer(0));
        var blockCounts = encoder.transientMemory().allocateGpu((long)blocks * Integer.BYTES, alignment, ComputeUsage.storageBuffer(0));
        var data = frame.particles().current();
        GpuBufferSlice uniforms = uniforms(encoder, task, frame, sorted, view, count, blocks, data.stride());
        var bindings = ComputeBindings.builder();
        for (ShaderBinding binding : kernel.program().bindings()) {
            switch (binding) {
                case ShaderBinding.UniformBuffer uniform -> {
                    if (!uniform.name().equals("LumenMeshSceneCull"))
                        throw new IllegalArgumentException("Unknown mesh instance culling uniform " + uniform.name());
                    bindings.uniform(uniform.name(), uniforms);
                }
                case ShaderBinding.StorageBuffer storage -> bindings.storage(storage.name(), switch (storage.name()) {
                    case "NiagaraParticleDataFloat" -> frame.floatData().slice();
                    case "NiagaraParticleDataHalf" -> frame.halfData().slice();
                    case "NiagaraParticleDataInt" -> data.integers() == null ? dummy.slice() : data.integers().slice();
                    case "SceneSortedIndices" -> sorted == null ? dummy.slice() : sorted.indices().slice();
                    case "SceneParticleCounts" -> frame.particles().countBuffer();
                    case "MeshSceneTransforms" -> matrices.transforms();
                    case "MeshSceneMetrics" -> matrices.metrics();
                    case "OutMeshSceneRawIndices" -> raw;
                    case "OutMeshSceneBlockCounts" -> blockCounts;
                    default -> throw new IllegalArgumentException("Unknown mesh instance culling buffer " + storage.name());
                }, storage.access());
                default -> throw new IllegalArgumentException("Unexpected mesh instance culling descriptor " + binding.name());
            }
        }
        int groupsX = blocks, groupsY = 1;
        if (groupsX > runtime.maxWorkGroups(0)) { groupsX = 128; groupsY = Math.ceilDiv(blocks, groupsX); }
        if (groupsX > runtime.maxWorkGroups(0) || groupsY > runtime.maxWorkGroups(1))
            throw new IllegalArgumentException("Mesh instance culling exceeds the device dispatch limit");
        kernel.dispatch(bindings.build(), Math.multiplyExact(groupsX, THREADS), groupsY, 1);
        return compactor.compact(encoder, raw, blockCounts, count);
    }

    private static GpuBufferSlice uniforms(CommandEncoder encoder, NiagaraGpuMeshCollector.Task task, NiagaraGpuMeshFrame frame,
                                            NiagaraGpuSortCollector.@Nullable Sorted sorted, @Nullable NiagaraGpuView view,
                                            int count, int blocks, int stride) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(256, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            boolean cpuEmitter = frame.emitterScene() != null;
            int countOffset = !cpuEmitter && task.emitter().simulation() instanceof NiagaraDefinition.GpuSimulation
                    ? frame.particles().currentCountOffset() : -1;
            bytes.putInt(count).putInt(countOffset).putInt(sorted == null ? -1 : sorted.offset()).putInt(stride);
            var renderer = task.renderer();
            var meshBinding = renderer.attributeBindings().get("MeshIndex");
            int meshIndex = frame.emitterSource() || meshBinding != null && !meshBinding.particle() ? -1
                    : NiagaraGpuSpriteSorting.integerOffset(task.emitter().attributes(), meshBinding == null ? "Particles.MeshIndex" : meshBinding.name());
            int visibility = frame.emitterSource() || !renderer.visibilityParticleBinding() ? -1
                    : NiagaraGpuSpriteSorting.integerOffset(task.emitter().attributes(), renderer.visibilityBinding());
            bytes.putInt(meshIndex).putInt(visibility).putInt(task.meshIndex()).putInt(renderer.rendererVisibility());
            var bounds = task.geometry().sourceBounds();
            var centre = bounds.origin();
            var extent = bounds.extent().multiply(task.geometry().renderer().boundsScale());
            bytes.putFloat((float)centre.x).putFloat((float)centre.y).putFloat((float)centre.z).putFloat(0);
            bytes.putFloat((float)extent.x).putFloat((float)extent.y).putFloat((float)extent.z).putFloat(0);
            float minimum = renderer.minCameraDistance(), maximum = renderer.maxCameraDistance();
            bytes.putFloat(renderer.distanceCulling() ? minimum * minimum : 0)
                    .putFloat(renderer.distanceCulling() ? maximum * maximum : Float.MAX_VALUE).putFloat(0).putFloat(0);
            for (float value : lod(task)) bytes.putFloat(value);
            var shadow = task.view().shadowBounds();
            bytes.putInt(task.view().perspective() == 0 ? 1 : 0).putInt(shadow == null ? 1 : 2).putInt(cpuEmitter ? 1 : 0).putInt(blocks);
            if (shadow == null) {
                var nativeView = Objects.requireNonNull(view, "Mesh culling requires the source projection matrices");
                bytes.put(Objects.requireNonNull(nativeView.value("TranslatedWorldToClip")));
                bytes.put(Objects.requireNonNull(nativeView.value("ViewToClip")));
                bytes.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            } else {
                for (int word = 0; word < 32; word++) bytes.putFloat(0);
                var centreRelative = shadow.centre();
                bytes.putFloat((float)(centreRelative.x * 100)).putFloat((float)(centreRelative.z * 100))
                        .putFloat((float)(centreRelative.y * 100)).putFloat((float)(shadow.radius() * 100));
            }
            return mapped.slice();
        }
    }

    private static float[] lod(NiagaraGpuMeshCollector.Task task) {
        var entry = task.geometry().mesh();
        var view = task.view();
        if (entry.lodMode() != MeshRendererSettings.LodMode.PER_PARTICLE) {
            float projected = view.screenMultiple();
            return new float[]{0, -1, projected * projected, view.perspective() * view.lodDistanceFactor()};
        }
        var asset = Objects.requireNonNull(entry.asset());
        var range = MeshLodSelection.particleRange(asset, entry, view);
        int lod = task.lod();
        float lower = MeshLodSelection.particleLowerScreenSize(asset, range, lod, view);
        float upper = MeshLodSelection.screenSize(asset, lod, view) * .5f;
        float projected = asset.renderBounds().radius() * view.screenMultiple();
        return new float[]{lower * lower, lod > range.first() ? upper * upper : -1, projected * projected,
                view.perspective() * view.lodDistanceFactor() * entry.lodDistanceFactor()};
    }

    @Override public void close() { kernel.close(); }
}
