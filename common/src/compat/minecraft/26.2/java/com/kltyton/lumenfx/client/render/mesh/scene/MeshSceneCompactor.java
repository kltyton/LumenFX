package com.kltyton.lumenfx.client.render.mesh.scene;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSortCollector;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Original instance compaction passes preserve the completed particle sort, including equal-key order. */
public final class MeshSceneCompactor implements AutoCloseable {
    private static final int BLOCK_SIZE = 64;
    private record Stage(ComputeKernel kernel, NiagaraGpuComputeStage dispatch) { }
    private final ComputeRuntime runtime;
    private final Stage offsets, scatter;
    private boolean closed;

    public MeshSceneCompactor(ComputeRuntime runtime, Map<String, PreparedNiagaraShaders.Stage> prepared) {
        this.runtime = runtime;
        Stage first = stage(prepared.get("compaction_offsets"));
        try {
            scatter = stage(prepared.get("compaction_scatter"));
            offsets = first;
        } catch (RuntimeException | Error error) {
            first.kernel().close();
            throw error;
        }
    }

    private Stage stage(PreparedNiagaraShaders.Stage prepared) {
        var shader = prepared.shader();
        if (shader.workgroupX() != BLOCK_SIZE || shader.workgroupY() != 1 || shader.workgroupZ() != 1)
            throw new IllegalArgumentException("Instance compaction requires its source 64-thread block layout");
        ComputeKernel kernel = runtime.createKernel(shader);
        try { return new Stage(kernel, new NiagaraGpuComputeStage(prepared, kernel)); }
        catch (RuntimeException | Error error) { kernel.close(); throw error; }
    }

    public NiagaraGpuSortCollector.Sorted compact(CommandEncoder encoder, GpuBufferSlice rawIndices,
                                                   GpuBufferSlice blockCounts, int instances) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Mesh scene compaction has been released");
        if (instances <= 0 || instances > 0x00ffffff)
            throw new IllegalArgumentException("Instance compaction count exceeds its packed source field");
        int blocks = Math.ceilDiv(instances, BLOCK_SIZE);
        if (rawIndices.length() < (long)instances * Integer.BYTES || blockCounts.length() < (long)blocks * Integer.BYTES)
            throw new IllegalArgumentException("Instance compaction inputs do not cover the declared draw");
        long indexBytes = Math.multiplyExact(Math.ceilDiv((long)instances, 4), 16);
        if (indexBytes > runtime.maxStorageBufferBytes() || indexBytes > runtime.maxTexelBufferElements() * 16)
            throw new IllegalArgumentException("Compacted instance IDs exceed the device binding limit");
        long alignment = Math.max(16, runtime.storageBufferAlignment());
        GpuBufferSlice data;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(20, alignment, ComputeUsage.storageBuffer(0))) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(instances << 8 | 1).putInt(0).putInt(0).putInt(0).putInt(0);
            data = mapped.slice();
        }
        GpuBufferSlice drawIndices = encoder.transientMemory().allocateGpu((long)blocks * Integer.BYTES, alignment,
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
        runtime.clearBuffer(drawIndices, 0);
        GpuBufferSlice blockOffsets = encoder.transientMemory().allocateGpu((long)blocks * Integer.BYTES, alignment, ComputeUsage.storageBuffer(0));
        GpuBufferSlice counts = encoder.transientMemory().allocateGpu(20, alignment, ComputeUsage.storageBuffer(0));
        GpuBufferSlice indices = encoder.transientMemory().allocateGpu(indexBytes, alignment,
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
        dispatch(encoder, offsets, data, rawIndices, blockCounts, drawIndices, blockOffsets, counts, indices, blocks, 1, 1);
        int groupsX = blocks, groupsY = 1;
        if (groupsX > runtime.maxWorkGroups(0)) {
            groupsX = 128;
            groupsY = Math.ceilDiv(blocks, groupsX);
        }
        if (groupsX > runtime.maxWorkGroups(0) || groupsY > runtime.maxWorkGroups(1))
            throw new IllegalArgumentException("Instance compaction exceeds the device dispatch limits");
        dispatch(encoder, scatter, data, rawIndices, blockCounts, drawIndices, blockOffsets, counts, indices, blocks, groupsX, groupsY);
        return new NiagaraGpuSortCollector.Sorted(indices.buffer(), Math.toIntExact(indices.offset() / Integer.BYTES), counts, 1);
    }

    private void dispatch(CommandEncoder encoder, Stage stage, GpuBufferSlice data, GpuBufferSlice raw,
                          GpuBufferSlice blockCounts, GpuBufferSlice drawIndices, GpuBufferSlice blockOffsets,
                          GpuBufferSlice counts, GpuBufferSlice output, int blocks, int groupsX, int groupsY) {
        var resources = ComputeBindings.builder();
        for (ShaderBinding binding : stage.dispatch().shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice slice = switch (storage.name()) {
                case "DrawCommandCompactionData" -> data;
                case "BlockInstanceCounts" -> blockCounts;
                case "BlockDestInstanceOffsetsOut", "BlockDestInstanceOffsets" -> blockOffsets;
                case "DrawIndirectArgsBufferOut" -> counts;
                case "BlockDrawCommandIndices" -> drawIndices;
                case "InstanceIdsBufferIn" -> raw;
                case "InstanceIdsBufferOut" -> output;
                default -> throw new IllegalArgumentException("Unknown instance compaction buffer " + storage.name());
            };
            resources.storage(storage.name(), slice, storage.access());
        }
        byte[] drawCommands = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array();
        byte[] compactionBlocks = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blocks).array();
        stage.dispatch().dispatch(encoder, resources,
                name -> switch (name.substring(name.lastIndexOf('.') + 1)) {
                    case "NumDrawCommands" -> drawCommands;
                    case "NumCompactionBlocks" -> compactionBlocks;
                    case "MeshSceneCompactionDispatch" -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(blocks).array();
                    default -> null;
                },
                Math.multiplyExact(groupsX, BLOCK_SIZE), groupsY, 1);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        scatter.kernel().close(); offsets.kernel().close();
    }
}
