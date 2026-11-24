package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState.GpuWriter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Lease;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Publishes completed GPU writer data to the next client world simulation frame. */
public final class NiagaraGpuDataChannelReadbacks {
    private record Pending(GpuBuffer staging, GpuFence fence, GpuWriter writer, int capacity, long floatBytes,
                           @Nullable Lease lease, @Nullable CompletableFuture<List<int[]>> capture) implements AutoCloseable {
        @Override public void close() {
            if (capture != null && !capture.isDone())
                capture.completeExceptionally(new CancellationException("Niagara channel cache readback ended before publication"));
            try { fence.close(); }
            finally {
                try { staging.close(); }
                finally { if (lease != null) lease.close(); }
            }
        }
    }
    private static final Map<ClientLevel, ArrayDeque<Pending>> WORLDS = new IdentityHashMap<>();
    private NiagaraGpuDataChannelReadbacks() { }

    static void submit(CommandEncoder encoder, GpuWriter writer, NiagaraGpuDataChannelColumns source) {
        ClientLevel level = Objects.requireNonNull(writer.world());
        long floatBytes = source.floats().size(), intBytes = source.integers().size();
        long size = Math.addExact(Integer.BYTES, Math.addExact(floatBytes, intBytes));
        GpuBuffer staging = RenderSystem.getDevice().createBuffer(() -> "Niagara data channel readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
        GpuFence fence = null;
        Lease lease = null;
        try {
            encoder.copyToBuffer(source.count().slice(), staging.slice(0, Integer.BYTES));
            encoder.copyToBuffer(source.floats().slice(), staging.slice(Integer.BYTES, floatBytes));
            encoder.copyToBuffer(source.integers().slice(), staging.slice(Integer.BYTES + floatBytes, intBytes));
            fence = encoder.createFence();
            lease = NiagaraDataChannelWorlds.retain(level, Objects.requireNonNull(writer.address()));
            var capture = writer.recording() == null ? null : writer.recording().gpu(writer.tile());
            WORLDS.computeIfAbsent(level, ignored -> new ArrayDeque<>()).addLast(new Pending(staging, fence, writer, source.capacity(), floatBytes, lease, capture));
        } catch (RuntimeException | Error failure) {
            try { if (fence != null) fence.close(); }
            finally {
                try { staging.close(); }
                finally { if (lease != null) lease.close(); }
            }
            throw failure;
        }
    }

    public static void poll(ClientLevel level) {
        var queue = WORLDS.get(level);
        if (queue == null) return;
        while (!queue.isEmpty() && queue.peekFirst().fence().awaitCompletion(0L)) {
            try (var pending = queue.removeFirst()) {
                int count;
                try (var header = pending.staging().slice(0, Integer.BYTES).map(true, false)) {
                    count = header.data().order(ByteOrder.nativeOrder()).getInt(0);
                }
                if (count < 0 || count > pending.capacity())
                    throw new IllegalStateException("Niagara data channel GPU readback count exceeds its allocation");
                if (count == 0) {
                    if (pending.capture() != null) pending.capture().complete(List.of());
                    continue;
                }
                var writer = pending.writer();
                var schema = Objects.requireNonNull(writer.definition().channel());
                var rows = new ArrayList<int[]>(count);
                int width = schema.width();
                for (int i = 0; i < count; i++) rows.add(new int[width]);
                int column = 0, floatColumn = 0, intColumn = 0;
                for (var variable : schema.variables()) for (var type : variable.registers()) {
                    long offset = switch (type) {
                        case FLOAT -> Integer.BYTES + (long)floatColumn++ * pending.capacity() * Integer.BYTES;
                        case INT, BOOL -> Integer.BYTES + pending.floatBytes() + (long)intColumn++ * pending.capacity() * Integer.BYTES;
                        case HALF -> -1;
                    };
                    if (offset >= 0) {
                        try (var mapped = pending.staging().slice(offset, (long)count * Integer.BYTES).map(true, false)) {
                            var values = mapped.data().order(ByteOrder.nativeOrder());
                            for (var row : rows) row[column] = values.getInt();
                        }
                    }
                    column++;
                }
                if (writer.definition().publishGame() || writer.definition().publishCpu())
                    NiagaraDataChannelWorlds.publishGpuReadback(level, Objects.requireNonNull(writer.address()), writer.definition(), rows, writer.tile());
                if (pending.capture() != null) pending.capture().complete(List.copyOf(rows));
            }
        }
        if (queue.isEmpty()) WORLDS.remove(level);
    }

    public static void clear(@Nullable ClientLevel level) {
        if (level == null) {
            for (var queue : WORLDS.values()) for (var readback : queue) readback.close();
            WORLDS.clear();
        } else {
            var queue = WORLDS.remove(level);
            if (queue != null) for (var readback : queue) readback.close();
        }
    }
}
