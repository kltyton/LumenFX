package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NanitePageTranscoder;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A world-owned physical cluster buffer with optional pages followed by persistent root pages. */
final class NanitePhysicalPagePool implements AutoCloseable {
    static final int LAST_PAGE = (1 << 15) - 1;
    private static final int STREAM_BYTES = NanitePageTranscoder.STREAM_PAGE_BYTES;
    private static final int ROOT_BYTES = NanitePageTranscoder.ROOT_PAGE_BYTES;

    record Placement(Map<Integer, Integer> pages, BitSet usedStreams) { }

    private final long maxBytes;
    private final BitSet usedStreams = new BitSet();
    private @Nullable GpuBuffer buffer;
    private int streamingSlots, allocatedRoots, rootCapacity, initialRoots;
    private boolean growRoots;

    NanitePhysicalPagePool(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    int configure(long streamingBytes, int initialRootPages, boolean grow, boolean resizeStreaming) {
        if (streamingBytes < 0 || initialRootPages < 0)
            throw new IllegalArgumentException("Invalid Nanite physical page pool settings");
        growRoots = grow;
        initialRoots = (int) Math.min(initialRootPages, Math.min(LAST_PAGE + 1L, maxBytes / ROOT_BYTES));
        int minimumRoots = Math.max(allocatedRoots, initialRoots);
        long available = maxBytes - (long) minimumRoots * ROOT_BYTES;
        if (available < 0 || minimumRoots > LAST_PAGE + 1) throw new IllegalArgumentException("Nanite root pool exceeds the device limit");
        int requested = (int) Math.min(Math.min(streamingBytes, available) / STREAM_BYTES, LAST_PAGE + 1L - minimumRoots);
        if (buffer == null) streamingSlots = requested;
        return resizeStreaming ? requested : streamingSlots;
    }

    int registerRoots(int count) {
        int base = allocatedRoots;
        int end = Math.addExact(base, count);
        if ((!growRoots && end > initialRoots) || end > LAST_PAGE + 1 - streamingSlots
                || byteLength(streamingSlots, end) > maxBytes)
            throw new IllegalArgumentException("Nanite root pages exceed the shared physical pool");
        allocatedRoots = end;
        return base;
    }

    void ensureBuffer(CommandEncoder encoder) {
        int minimum = Math.max(initialRoots, allocatedRoots);
        if (buffer == null || minimum > rootCapacity) resize(encoder, streamingSlots, minimum);
    }

    void resetStreaming(CommandEncoder encoder, int slots) {
        if (!usedStreams.isEmpty()) throw new IllegalStateException("Resident optional Nanite pages must be removed before resizing");
        resize(encoder, slots, Math.max(initialRoots, allocatedRoots));
    }

    Placement placement(int rootBase, int roots, Set<Integer> next, Map<Integer, Integer> previous) {
        var occupied = (BitSet) usedStreams.clone();
        var pages = new HashMap<Integer, Integer>();
        previous.forEach((page, slot) -> {
            if (page >= roots && !next.contains(page)) occupied.clear(slot);
        });
        for (int page = 0; page < roots; page++) pages.put(page, LAST_PAGE - rootBase - page);
        for (int page : next) {
            if (page < roots) continue;
            Integer slot = previous.get(page);
            if (slot == null) {
                slot = occupied.nextClearBit(0);
                if (slot >= streamingSlots) throw new IllegalStateException("Nanite physical stream pool exhausted");
                occupied.set(slot);
            }
            pages.put(page, slot);
        }
        return new Placement(Map.copyOf(pages), occupied);
    }

    void commit(Placement placement) {
        usedStreams.clear();
        usedStreams.or(placement.usedStreams());
    }

    void releaseStreams(Map<Integer, Integer> pages, int roots) {
        pages.forEach((page, slot) -> { if (page >= roots) usedStreams.clear(slot); });
    }

    GpuBuffer buffer() { return Objects.requireNonNull(buffer, "Nanite physical pages are not allocated"); }
    int streamingSlots() { return streamingSlots; }

    long byteOffset(int sourcePage, int slot, int roots) {
        return sourcePage < roots
                ? (long) streamingSlots * STREAM_BYTES + (long) (LAST_PAGE - slot) * ROOT_BYTES
                : (long) slot * STREAM_BYTES;
    }

    private void resize(CommandEncoder encoder, int streams, int minimumRoots) {
        int maxRoots = (int) Math.min(LAST_PAGE + 1L - streams, (maxBytes - (long) streams * STREAM_BYTES) / ROOT_BYTES);
        if (minimumRoots > maxRoots) throw new IllegalArgumentException("Nanite physical buffer cannot hold its registered roots");
        int granularity = Math.max(1, Integer.highestOneBit(minimumRoots) >> 1);
        int rounded = minimumRoots <= initialRoots ? initialRoots : Math.ceilDiv(minimumRoots, granularity) * granularity;
        int roots = Math.min(maxRoots, Math.max(minimumRoots, rounded));
        long bytes = byteLength(streams, roots);
        var old = buffer;
        int usage = ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC);
        var replacement = RenderSystem.getDevice().createBuffer(() -> "LumenFX shared Nanite cluster pages", usage, bytes);
        try {
            if (old != null) {
                long streamBytes = (long) streamingSlots * STREAM_BYTES;
                if (streamBytes > 0 && streams == streamingSlots)
                    encoder.copyToBuffer(old.slice(0, streamBytes), replacement.slice(0, streamBytes));
                long rootBytes = (long) Math.min(rootCapacity, allocatedRoots) * ROOT_BYTES;
                if (rootBytes > 0) encoder.copyToBuffer(old.slice(streamBytes, rootBytes),
                        replacement.slice((long) streams * STREAM_BYTES, rootBytes));
            }
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        buffer = replacement;
        streamingSlots = streams;
        rootCapacity = roots;
        if (old != null) old.close();
    }

    private static long byteLength(int streams, int roots) {
        return (long) streams * STREAM_BYTES + (long) roots * ROOT_BYTES;
    }

    @Override public void close() {
        if (buffer != null) buffer.close();
        buffer = null;
        usedStreams.clear();
        streamingSlots = allocatedRoots = rootCapacity = 0;
    }
}
