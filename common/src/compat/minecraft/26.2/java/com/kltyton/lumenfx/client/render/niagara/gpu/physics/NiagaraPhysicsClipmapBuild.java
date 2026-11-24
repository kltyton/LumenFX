package com.kltyton.lumenfx.client.render.niagara.gpu.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Clipmap;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGpuData;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Supplies Chaos build ranges to the pinned Unreal clipmap compute shader. */
final class NiagaraPhysicsClipmapBuild {
    private static final int THREADS = 64;

    private NiagaraPhysicsClipmapBuild() { }

    static void run(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraPhysicsField field, PhysicsFieldGpuData data,
                    GpuBuffer clipmap, GpuBuffer parameters, GpuBuffer nodes, GpuBuffer targets) {
        Clipmap geometry = Objects.requireNonNull(field.clipmap());
        int resolution = geometry.resolution(), levels = data.levels();
        int[] attributes = data.physicsAttributes();
        int[] indices = Arrays.copyOf(attributes, PhysicsFieldGpuData.TARGET_ROWS);
        int[] selected = new int[PhysicsFieldGpuData.TARGET_ROWS];
        int count = 0;
        for (int target = 1; target < attributes.length; target++) if (attributes[target] >= 0)
            selected[count++] = target;
        int sections = Math.multiplyExact(count, levels);
        int cells = Math.multiplyExact(Math.multiplyExact(resolution, resolution), resolution);
        int threads = Math.multiplyExact(sections, cells);
        int maxThreads = (int)Math.min(Integer.MAX_VALUE, (long)programs.runtime().maxWorkGroups(0) * THREADS);
        Map<String, GpuBuffer> shared = Map.of("FieldClipmap", clipmap, "NodesParams", parameters, "NodesOffsets", nodes, "TargetsOffsets", targets);
        programs.runtime().clearBuffer(clipmap.slice(), 0);
        if (threads <= maxThreads) {
            int[] starts = new int[sections + 1], minima = new int[sections * 4], maxima = new int[sections * 4];
            for (int section = 0; section < sections; section++) {
                starts[section + 1] = (section + 1) * cells;
                setBox(maxima, section, resolution, resolution, resolution);
            }
            dispatch(encoder, programs, field, data, geometry, count, selected, indices, starts, minima, maxima, threads, shared);
            return;
        }
        int tileX = Math.min(resolution, maxThreads);
        int tileY = Math.min(resolution, maxThreads / tileX);
        int tileZ = Math.min(resolution, maxThreads / (tileX * tileY));
        int[] oneTarget = new int[PhysicsFieldGpuData.TARGET_ROWS], starts = new int[levels + 1];
        int[] minima = new int[levels * 4], maxima = new int[levels * 4];
        for (int target = 0; target < count; target++) {
            oneTarget[0] = selected[target];
            for (int level = 0; level < levels; level++) {
                for (int z = 0; z < resolution; z += tileZ) for (int y = 0; y < resolution; y += tileY)
                    for (int x = 0; x < resolution; x += tileX) {
                        int endX = Math.min(resolution, x + tileX), endY = Math.min(resolution, y + tileY);
                        int endZ = Math.min(resolution, z + tileZ);
                        int work = (endX - x) * (endY - y) * (endZ - z);
                        Arrays.fill(starts, 0, level + 1, 0);
                        Arrays.fill(starts, level + 1, starts.length, work);
                        setBox(minima, level, x, y, z);
                        setBox(maxima, level, endX, endY, endZ);
                        dispatch(encoder, programs, field, data, geometry, 1, oneTarget, indices, starts, minima, maxima, work, shared);
                    }
            }
        }
    }

    private static void setBox(int[] storage, int level, int x, int y, int z) {
        int index = level * 4;
        storage[index] = x;
        storage[index + 1] = y;
        storage[index + 2] = z;
    }

    private static void dispatch(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraPhysicsField field,
                                 PhysicsFieldGpuData data, Clipmap clipmap, int validCount, int[] valid, int[] indices,
                                 int[] starts, int[] minima, int[] maxima, int cells, Map<String, GpuBuffer> shared) {
        var stage = programs.physicsFieldBuilder();
        GpuBuffer startBuffer = null, minBuffer = null, maxBuffer = null;
        try {
            startBuffer = storage("CellsOffsets", starts);
            minBuffer = storage("CellsMin", minima);
            maxBuffer = storage("CellsMax", maxima);
            var bindings = ComputeBindings.builder();
            for (var binding : stage.shader().bindings()) if (binding instanceof ShaderBinding.StorageBuffer slot) {
                GpuBuffer buffer = switch (slot.name()) {
                    case "CellsOffsets" -> startBuffer;
                    case "CellsMin" -> minBuffer;
                    case "CellsMax" -> maxBuffer;
                    default -> Objects.requireNonNull(shared.get(slot.name()), slot.name());
                };
                bindings.storage(slot.name(), buffer.slice(), slot.access());
            }
            Map<String, byte[]> constants = Map.of(
                    "ClipmapResolution", words(clipmap.resolution()), "ClipmapDistance", words(Float.floatToRawIntBits(clipmap.distance())),
                    "ClipmapCenter", words(Float.floatToRawIntBits((float)clipmap.center().x), Float.floatToRawIntBits((float)clipmap.center().y),
                            Float.floatToRawIntBits((float)clipmap.center().z)),
                    "ClipmapCount", words(data.levels()), "ClipmapExponent", words(data.exponent()), "ValidCount", words(validCount),
                    "ValidTargets", words(valid), "TargetsIndex", words(indices), "NumCells", words(cells),
                    "TimeSeconds", words(Float.floatToRawIntBits(field.worldSeconds())));
            stage.dispatch(encoder, bindings, name -> constants.get(name.substring(name.lastIndexOf('.') + 1)), cells, 1, 1);
        } finally {
            retire(startBuffer);
            retire(minBuffer);
            retire(maxBuffer);
        }
    }

    private static GpuBuffer storage(String name, int[] source) {
        var words = MemoryUtil.memAlloc(Math.multiplyExact(source.length, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (int value : source) words.putInt(value);
            words.flip();
            return RenderSystem.getDevice().createBuffer(() -> "Niagara.PhysicsField." + name,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), words);
        } finally {
            MemoryUtil.memFree(words);
        }
    }

    private static byte[] words(int... words) {
        var buffer = ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) buffer.putInt(word);
        return buffer.array();
    }

    private static void retire(@Nullable GpuBuffer buffer) {
        if (buffer != null) RenderSystem.queueFencedTask(buffer::close);
    }
}
