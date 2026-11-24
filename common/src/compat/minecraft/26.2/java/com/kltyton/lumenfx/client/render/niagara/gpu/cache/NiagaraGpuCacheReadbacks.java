package com.kltyton.lumenfx.client.render.niagara.gpu.cache;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDataBuffer;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Target2D;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/** Copies live GPU datasets into ordered, fenced readbacks owned by their client world. */
public final class NiagaraGpuCacheReadbacks {
    private record Dataset(Layout layout, int stride, int tag, long floatBytes, long intBytes, long halfBytes, long idBytes) { }
    private record Pending<T>(@Nullable ClientLevel world, GpuBuffer staging, GpuFence fence, Function<GpuBuffer, T> decode,
                              CompletableFuture<T> result)
            implements AutoCloseable {
        @Override public void close() {
            try { fence.close(); }
            finally { staging.close(); }
        }
        void publish() {
            if (result.isCancelled()) return;
            try { result.complete(decode.apply(staging)); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        }
    }

    private static final List<Pending<?>> PENDING = new ArrayList<>();
    private NiagaraGpuCacheReadbacks() { }

    public static CompletableFuture<Buffer> capture(CommandEncoder encoder, @Nullable ClientLevel world, Layout layout,
                                                     NiagaraGpuDataBuffer data, GpuBufferSlice count, int acquireTag,
                                                     @Nullable GpuBuffer ids) {
        long floatBytes = size(data.floats()), intBytes = size(data.integers()), halfBytes = size(data.halves()), idBytes = size(ids);
        long bytes = Math.addExact(Integer.BYTES, Math.addExact(floatBytes, Math.addExact(intBytes, Math.addExact(halfBytes, idBytes))));
        var dataset = new Dataset(layout, data.stride(), acquireTag, floatBytes, intBytes, halfBytes, idBytes);
        return submit(encoder, world, layout.name(), bytes, staging -> {
            encoder.copyToBuffer(count, staging.slice(0, Integer.BYTES));
            long offset = copy(encoder, data.floats(), staging, Integer.BYTES);
            offset = copy(encoder, data.integers(), staging, offset);
            offset = copy(encoder, data.halves(), staging, offset);
            copy(encoder, ids, staging, offset);
        }, staging -> read(dataset, staging));
    }

    public static CompletableFuture<Integer> counter(CommandEncoder encoder, @Nullable ClientLevel world, String name, GpuBufferSlice count) {
        return submit(encoder, world, name, Integer.BYTES, staging -> encoder.copyToBuffer(count, staging.slice()), NiagaraGpuCacheReadbacks::word);
    }

    public static CompletableFuture<ArrayFrame> array(CommandEncoder encoder, @Nullable ClientLevel world, String name,
            @Nullable GpuBufferSlice liveCount, GpuBufferSlice data, int width, int capacity) {
        if (capacity == 0) return CompletableFuture.completedFuture(new ArrayFrame(true, new int[0]));
        long dataBytes = Math.multiplyExact((long)width * capacity, Integer.BYTES);
        long base = liveCount == null ? 0 : Integer.BYTES;
        return submit(encoder, world, name, Math.addExact(base, dataBytes), staging -> {
            if (liveCount != null) encoder.copyToBuffer(liveCount, staging.slice(0, Integer.BYTES));
            encoder.copyToBuffer(data.slice(0, dataBytes), staging.slice(base, dataBytes));
        }, staging -> {
            int count = liveCount == null ? capacity : word(staging);
            if (count < 0 || count > capacity) throw new IllegalStateException("Invalid Niagara cache GPU array count: " + name);
            var words = new int[Math.multiplyExact(count, width)];
            if (words.length != 0) try (var map = staging.slice(base, (long)words.length * Integer.BYTES).map(true, false)) {
                map.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
            }
            return new ArrayFrame(true, words);
        });
    }

    public static CompletableFuture<Target2D> target2D(CommandEncoder encoder, @Nullable ClientLevel world, String name,
                                                      GpuTexture source, int format) {
        int width = source.getWidth(0), height = source.getHeight(0);
        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), source.getFormat().blockSize());
        return submit(encoder, world, name, bytes, staging -> encoder.copyTextureToBuffer(source, staging, 0, () -> { }, 0),
                staging -> {
                    byte[] pixels = new byte[bytes];
                    try (var mapped = staging.slice().map(true, false)) { mapped.data().get(pixels); }
                    return new Target2D(format == 3 ? 2 : format, width, height, pixels);
                });
    }

    public static CompletableFuture<NiagaraCacheVolumeFrame> volume(CommandEncoder encoder, @Nullable ClientLevel world,
                                                                     String name, ComputeVolume source, int format,
                                                                     boolean omitZeroTiles) {
        int width = source.width(), height = source.height(), depth = source.depth();
        int bytes = Math.toIntExact((long)width * height * depth * source.format().blockSize());
        return submit(encoder, world, name, bytes, staging -> source.copyCachePixels(encoder, staging.slice()), staging -> {
            var result = new byte[bytes];
            try (var mapped = staging.slice().map(true, false)) { mapped.data().get(result); }
            return result;
        }).thenApplyAsync(pixels -> NiagaraCacheVolumeFrame.packPixels(format, width, height, depth, pixels, omitZeroTiles),
                Util.backgroundExecutor());
    }

    private static <T> CompletableFuture<T> submit(CommandEncoder encoder, @Nullable ClientLevel world, String name, long bytes,
                                                  Consumer<GpuBuffer> write, Function<GpuBuffer, T> decode) {
        RenderSystem.assertOnRenderThread();
        var staging = RenderSystem.getDevice().createBuffer(() -> name + ".SimCacheReadback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
        GpuFence fence = null;
        try {
            write.accept(staging);
            fence = encoder.createFence();
            var result = new CompletableFuture<T>();
            PENDING.add(new Pending<>(world, staging, fence, decode, result));
            return result;
        } catch (RuntimeException | Error failure) {
            if (fence != null) fence.close();
            staging.close();
            throw failure;
        }
    }

    private static long size(@Nullable GpuBuffer value) { return value == null ? 0 : value.size(); }
    private static long copy(CommandEncoder encoder, @Nullable GpuBuffer source, GpuBuffer staging, long offset) {
        if (source == null) return offset;
        encoder.copyToBuffer(source.slice(), staging.slice(offset, source.size()));
        return offset + source.size();
    }

    public static void poll() {
        RenderSystem.assertOnRenderThread();
        if (PENDING.isEmpty()) return;
        var ready = new ArrayList<Pending<?>>();
        PENDING.removeIf(sample -> {
            if (!sample.fence().awaitCompletion(0)) return false;
            ready.add(sample);
            return true;
        });
        for (var sample : ready) try (sample) {
            sample.publish();
        }
    }

    public static void clear(@Nullable ClientLevel world) {
        RenderSystem.assertOnRenderThread();
        var cancelled = new ArrayList<Pending<?>>();
        PENDING.removeIf(sample -> {
            if (world != null && sample.world() != world) return false;
            cancelled.add(sample);
            return true;
        });
        for (var sample : cancelled) try (sample) {
            sample.result().completeExceptionally(new CancellationException("Niagara GPU cache readback lost its client resources"));
        }
    }

    private static int word(GpuBuffer staging) {
        try (var mapped = staging.slice(0, Integer.BYTES).map(true, false)) {
            return mapped.data().order(ByteOrder.nativeOrder()).getInt(0);
        }
    }

    private static Buffer read(Dataset sample, GpuBuffer staging) {
        int count = word(staging);
        if (count < 0 || count >= sample.stride()) throw new IllegalStateException("Invalid Niagara cache GPU instance count");
        var layout = sample.layout();
        long offset = Integer.BYTES;
        int[] floats = words(staging, sample.stride(), offset, layout.floatCount(), count);
        offset += sample.floatBytes();
        int[] integers = words(staging, sample.stride(), offset, layout.integerCount(), count);
        offset += sample.intBytes();
        short[] halves = new short[Math.multiplyExact(layout.halfCount(), count)];
        if (halves.length != 0) for (int column = 0; column < layout.halfCount(); column++) {
            long start = offset + (long)column * sample.stride() * Short.BYTES;
            try (var mapped = staging.slice(start, (long)count * Short.BYTES).map(true, false)) {
                mapped.data().order(ByteOrder.nativeOrder()).asShortBuffer().get(halves, column * count, count);
            }
        }
        offset += sample.halfBytes();
        int[] ids = new int[Math.toIntExact(sample.idBytes() / Integer.BYTES)];
        if (ids.length != 0) try (var mapped = staging.slice(offset, sample.idBytes()).map(true, false)) {
            mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(ids);
        }
        return new Buffer(count, sample.tag(), floats, integers, halves, ids, new int[0], layout.captureIds(integers, count));
    }

    private static int[] words(GpuBuffer staging, int stride, long offset, int columns, int count) {
        var result = new int[Math.multiplyExact(columns, count)];
        if (result.length != 0) for (int column = 0; column < columns; column++) {
            long start = offset + (long)column * stride * Integer.BYTES;
            try (var mapped = staging.slice(start, (long)count * Integer.BYTES).map(true, false)) {
                mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(result, column * count, count);
            }
        }
        return result;
    }
}
