package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** GPU reader resources shared by all emitter scripts bound to the same DI object. */
public final class NiagaraGpuDataChannelReaders implements AutoCloseable {
    enum Member {
        FLOATS("DataFloat"), INTEGERS("DataInt32"), SPAWNS("NDCSpawnDataBuffer"),
        COUNT("NDCInstanceCount"), CONSUME("NDCConsumeCount");
        final String shaderName;
        Member(String shaderName) { this.shaderName = shaderName; }
    }

    private record Uploaded(GpuBuffer buffer, Object identity) { }
    private final Map<String, Reader> readers = new HashMap<>();
    private final NiagaraGpuPrograms programs;
    private Object batch = new Object();

    public NiagaraGpuDataChannelReaders(NiagaraGpuPrograms programs) { this.programs = programs; }

    final class Reader implements AutoCloseable {
        private final String identity;
        private final Map<Member, Uploaded> buffers = new EnumMap<>(Member.class);
        Reader(String identity) { this.identity = identity; }

        GpuBuffer bind(CommandEncoder encoder, Member member, NiagaraDataChannelGpuSnapshot snapshot) {
            Object content;
            long bytes;
            Consumer<ByteBuffer> write;
            switch (member) {
                case FLOATS, INTEGERS -> {
                    boolean floating = member == Member.FLOATS;
                    content = snapshot.rowIdentity();
                    bytes = snapshot.columnBytes(floating);
                    write = data -> snapshot.writeColumns(floating, data);
                }
                case SPAWNS -> {
                    content = snapshot;
                    bytes = snapshot.spawnBytes();
                    write = snapshot::writeSpawnData;
                }
                case COUNT -> {
                    content = snapshot.rowIdentity();
                    bytes = Integer.BYTES;
                    write = data -> data.putInt(snapshot.rows());
                }
                case CONSUME -> {
                    content = batch;
                    bytes = Integer.BYTES;
                    write = data -> data.putInt(0);
                }
                default -> throw new IllegalStateException("Unknown Niagara channel GPU reader member");
            }
            var existing = buffers.get(member);
            if (existing != null && existing.identity() == content) return existing.buffer();
            var next = upload(programs, encoder, existing == null ? null : existing.buffer(), identity + "_" + member.shaderName, bytes, write);
            buffers.put(member, new Uploaded(next, content));
            return next;
        }

        @Override public void close() {
            for (var uploaded : buffers.values()) uploaded.buffer().close();
            buffers.clear();
        }
    }

    Reader reader(String identity) { return readers.computeIfAbsent(identity, Reader::new); }
    public void endBatch() { batch = new Object(); }

    static GpuBuffer upload(NiagaraGpuPrograms programs, CommandEncoder encoder, @Nullable GpuBuffer old,
                            String name, long size, Consumer<ByteBuffer> write) {
        if (size > programs.runtime().maxStorageBufferBytes()) throw new IllegalArgumentException("Niagara channel GPU buffer exceeds device capacity: " + name);
        var bytes = MemoryUtil.memAlloc(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            write.accept(bytes);
            bytes.flip();
            if (old != null && old.size() == size) {
                encoder.writeToBuffer(old.slice(), bytes);
                return old;
            }
            var next = RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
            if (old != null) old.close();
            return next;
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Override public void close() {
        for (var reader : readers.values()) reader.close();
        readers.clear();
    }
}
