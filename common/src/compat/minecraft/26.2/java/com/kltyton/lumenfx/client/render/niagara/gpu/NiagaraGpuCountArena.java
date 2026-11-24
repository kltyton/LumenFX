package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** System-owned GPU counters shared by emitters and mutable data interfaces. */
public final class NiagaraGpuCountArena implements AutoCloseable {
    private GpuBuffer buffer;
    private final Map<String, Integer> baseInterfaceOffsets;
    private Map<String, Integer> interfaceOffsets;
    private final Map<Integer, Integer> readerSnapshotOffsets;
    private final int emitters;
    private final int firstDynamicSlot;
    private boolean closed;

    public NiagaraGpuCountArena(List<NiagaraDefinition.Emitter> emitters, NiagaraGpuPrograms programs) {
        this(emitters, programs, Set.of());
    }
    public NiagaraGpuCountArena(List<NiagaraDefinition.Emitter> emitters, NiagaraGpuPrograms programs,
                                Set<String> instanceInterfaces) {
        RenderSystem.assertOnRenderThread();
        this.emitters = emitters.size();
        int next = Math.multiplyExact(this.emitters, NiagaraGpuParticleBuffers.DATA_BUFFERS);
        Map<String, Integer> offsets = new LinkedHashMap<>();
        Map<Integer, Integer> readerOffsets = new LinkedHashMap<>();
        boolean mutableReaders = false;
        for (int emitterIndex = 0; emitterIndex < emitters.size(); emitterIndex++) {
            var emitter = emitters.get(emitterIndex);
            if (!(emitter.simulation() instanceof NiagaraDefinition.GpuSimulation gpu)) continue;
            var interfaces = gpu.metadata().getAsJsonArray("interfaces");
            if (interfaces == null) continue;
            for (var element : interfaces) {
                JsonObject binding = element.getAsJsonObject();
                if (binding.get("class").getAsString().equals("NiagaraDataInterfaceParticleRead")
                        && binding.has("particle_read") && binding.getAsJsonObject("particle_read").has("emitter")) {
                    mutableReaders |= binding.has("interface_key");
                    String target = binding.getAsJsonObject("particle_read").get("emitter").getAsString();
                    for (int targetIndex = 0; targetIndex < emitters.size(); targetIndex++) {
                        var source = emitters.get(targetIndex);
                        if (source.simulation() instanceof NiagaraDefinition.GpuSimulation
                                && SourceNames.equal(source.sourceName(), target) && !readerOffsets.containsKey(targetIndex))
                            readerOffsets.put(targetIndex, next++);
                    }
                    continue;
                }
                String type = binding.get("class").getAsString();
                if (!type.startsWith("NiagaraDataInterfaceArray") && !type.equals("NiagaraDataInterfaceSimpleCounter")) continue;
                if (!binding.has("interface_id")) throw new IllegalArgumentException("Niagara GPU array interface has no instance identity");
                String id = binding.get("interface_id").getAsString();
                if (!offsets.containsKey(id)) offsets.put(id, next++);
            }
        }
        if (mutableReaders) for (int index = 0; index < emitters.size(); index++)
            if (emitters.get(index).simulation() instanceof NiagaraDefinition.GpuSimulation && !readerOffsets.containsKey(index))
                readerOffsets.put(index, next++);
        baseInterfaceOffsets = Map.copyOf(offsets);
        firstDynamicSlot = next;
        readerSnapshotOffsets = Map.copyOf(readerOffsets);
        var activeOffsets = interfaceOffsets(instanceInterfaces);
        buffer = allocate(programs, size(activeOffsets));
        interfaceOffsets = activeOffsets;
    }

    /** Called after the old interface registries have released their cached offsets. */
    public void rebindInterfaces(NiagaraGpuPrograms programs, Set<String> instanceInterfaces) {
        RenderSystem.assertOnRenderThread();
        GpuBuffer previous = buffer();
        var activeOffsets = interfaceOffsets(instanceInterfaces);
        long bytes = size(activeOffsets);
        if (bytes > previous.size()) {
            GpuBuffer replacement = allocate(programs, bytes);
            try {
                long stableBytes = (long) firstDynamicSlot * Integer.BYTES;
                if (stableBytes != 0) RenderSystem.getDevice().createCommandEncoder()
                        .copyToBuffer(previous.slice(0, stableBytes), replacement.slice(0, stableBytes));
            } catch (RuntimeException | Error error) {
                replacement.close();
                throw error;
            }
            buffer = replacement;
            previous.close();
        }
        interfaceOffsets = activeOffsets;
    }

    private Map<String, Integer> interfaceOffsets(Set<String> instanceInterfaces) {
        var offsets = new LinkedHashMap<>(baseInterfaceOffsets);
        int next = firstDynamicSlot;
        for (String id : instanceInterfaces) if (!offsets.containsKey(id)) offsets.put(id, next++);
        return Map.copyOf(offsets);
    }

    private long size(Map<String, Integer> activeOffsets) {
        return (long) Math.max(1, Math.addExact(firstDynamicSlot, activeOffsets.size() - baseInterfaceOffsets.size()))
                * Integer.BYTES;
    }

    private static GpuBuffer allocate(NiagaraGpuPrograms programs, long bytes) {
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara GPU counter arena exceeds the device limit");
        GpuBuffer created = RenderSystem.getDevice().createBuffer(() -> "Niagara.SystemCounters",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), bytes);
        try {
            programs.runtime().clearBuffer(created.slice(), 0);
            return created;
        } catch (RuntimeException | Error error) {
            created.close();
            throw error;
        }
    }

    public GpuBuffer buffer() {
        if (closed) throw new IllegalStateException("Niagara GPU counter arena is closed");
        return buffer;
    }

    public int emitterOffset(int index) {
        if (closed || index < 0 || index >= emitters) throw new IllegalArgumentException("Invalid Niagara emitter counter index");
        return index * NiagaraGpuParticleBuffers.DATA_BUFFERS;
    }

    public int interfaceOffset(String id) {
        if (closed) throw new IllegalStateException("Niagara GPU counter arena is closed");
        Integer offset = interfaceOffsets.get(id);
        if (offset == null) throw new IllegalArgumentException("Unknown Niagara GPU array counter " + id);
        return offset;
    }

    public int readerSnapshotOffset(int emitterIndex) {
        if (closed || emitterIndex < 0 || emitterIndex >= emitters) throw new IllegalArgumentException("Invalid Niagara emitter index");
        return readerSnapshotOffsets.getOrDefault(emitterIndex, -1);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        buffer.close();
    }
}
