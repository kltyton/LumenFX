package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.AdaptivePages;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.PageTableLayout;
import com.kltyton.lumenfx.client.resource.texture.virtual.AdaptivePageTableAllocator.Block;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Owns the fine page table and grid indirection for one adaptive producer. */
final class AdaptiveVirtualTexturePageTable implements AutoCloseable {
    private static final int MAX_LEVEL_INCREMENT = 3;
    private static final int MIN_REALLOCATION_AGE = 3;
    private static final int AGE_WHEN_FULL = 15;
    private static final int AGE_TO_FREE = 1000;
    private static final int MAX_GRID_DEMAND = 4096;
    private static final int MAX_UPLOAD_BYTES = 1 << 20;

    private record Cell(int x, int y) { }
    private record Image(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override public void close() {
            RenderSystem.queueFencedTask(() -> { try { view.close(); } finally { texture.close(); } });
        }
    }
    private static final class Allocation {
        final Block block;
        final long created;
        long lastUse;
        boolean mapped;

        Allocation(Block block, long created, long lastUse) {
            this.block = block;
            this.created = created;
            this.lastUse = lastUse;
        }
    }
    record Route(Page page, boolean exact) { }

    private final String name;
    private final PageTableLayout grid;
    private final int bias;
    private final int tableLog;
    private final int persistentRoots;
    private final AdaptivePageTableAllocator allocator;
    private final Map<Cell, Allocation> cells = new LinkedHashMap<>();
    private final Map<Cell, Integer> demands = new LinkedHashMap<>();
    private final Set<Cell> rootsPending = new LinkedHashSet<>();
    private @Nullable Image indirection;
    private @Nullable Image pages;
    private long frame;

    AdaptiveVirtualTexturePageTable(String name, PageTableLayout grid, int tableLog, int persistentRoots) {
        this.name = name;
        this.grid = grid;
        this.bias = grid.producerMipBias();
        this.tableLog = tableLog;
        this.persistentRoots = persistentRoots;
        allocator = new AdaptivePageTableAllocator(tableLog);
    }

    @Nullable AdaptivePages publication() {
        return indirection == null || pages == null ? null : new AdaptivePages(indirection.view(), pages.view());
    }

    Route route(Page original) {
        if (original.mip() >= bias) return new Route(original, true);
        var cell = cell(original);
        var current = cells.get(cell);
        if (current != null) current.lastUse = frame;
        int minimum = current == null ? bias : bias - current.block.level;
        if (original.mip() < minimum && (demands.containsKey(cell) || demands.size() < MAX_GRID_DEMAND))
            demands.merge(cell, original.mip(), Math::min);
        if (current == null || !current.mapped) return new Route(root(cell), false);
        int mip = Math.max(original.mip(), minimum);
        int shift = mip - original.mip();
        return new Route(new Page(mip, original.x() >> shift, original.y() >> shift), shift == 0);
    }

    @Nullable Page pendingRoot(VirtualTextureResidency residency, Set<Page> inFlight, Predicate<Page> available) {
        for (var cell : rootsPending) {
            var page = root(cell);
            if (!residency.contains(page) && !inFlight.contains(page) && available.test(page)) return page;
        }
        return null;
    }

    void advance(VirtualTextureResidency residency) {
        frame++;
        boolean busy = !demands.isEmpty();
        boolean tableFull = (long)allocator.area() * 4 > (long)allocator.capacity() * 3;
        boolean pinnedFull = cells.size() + persistentRoots >= Math.max(1, residency.capacity() - 1);
        boolean physicalBusy = (long)residency.size() * 100 > (long)residency.capacity() * 85;
        boolean shrunk = (busy && (tableFull || pinnedFull) || !busy && physicalBusy)
                && shrinkOldest(residency, busy ? AGE_WHEN_FULL : AGE_TO_FREE, pinnedFull);
        if (!busy) return;
        boolean fragmented = false;
        var iterator = demands.entrySet().iterator();
        while (iterator.hasNext()) {
            var demand = iterator.next();
            var cell = demand.getKey();
            var previous = cells.get(cell);
            if (previous != null && (frame - previous.created < MIN_REALLOCATION_AGE || !previous.mapped)) continue;
            int level = previous == null ? 0 : previous.block.level;
            int target = Math.min(bias, level + MAX_LEVEL_INCREMENT);
            if (previous == null && cells.size() + persistentRoots >= residency.capacity() - 1) continue;
            if (level < target && level < bias - demand.getValue()) {
                initialize();
                var block = allocator.allocate(target);
                if (block == null) {
                    fragmented = true;
                    continue;
                }
                replace(cell, previous, block, frame, residency);
                if (target >= bias - demand.getValue()) iterator.remove();
                else {
                    int requested = demand.getValue();
                    iterator.remove();
                    demands.put(cell, requested);
                }
                return;
            }
            iterator.remove();
        }
        if (fragmented && !shrunk) shrinkOldest(residency, AGE_WHEN_FULL, false);
    }

    private boolean shrinkOldest(VirtualTextureResidency residency, long maxAge, boolean releaseSlot) {
        var candidate = cells.entrySet().stream().filter(entry -> frame - entry.getValue().lastUse >= maxAge)
                .min(Comparator.<Map.Entry<Cell, Allocation>>comparingLong(entry -> entry.getValue().lastUse)
                        .thenComparingInt(entry -> entry.getValue().block.level));
        if (candidate.isEmpty()) return false;
        var oldest = candidate.get();
        shrink(oldest.getKey(), oldest.getValue(), residency, releaseSlot);
        return true;
    }

    private void shrink(Cell cell, Allocation allocation, VirtualTextureResidency residency, boolean releaseSlot) {
        if (!releaseSlot) for (int level = allocation.block.level - 1; level > 0; level--) {
            var smaller = allocator.allocate(level);
            if (smaller != null) {
                replace(cell, allocation, smaller, allocation.lastUse, residency);
                return;
            }
        }
        if (allocation.mapped) publish(cell, 0);
        allocator.free(allocation.block);
        cells.remove(cell);
        rootsPending.remove(cell);
        residency.unpin(root(cell));
    }

    private void replace(Cell cell, @Nullable Allocation previous, Block block, long lastUse, VirtualTextureResidency residency) {
        var next = new Allocation(block, frame, lastUse);
        if (previous == null) residency.pin(root(cell));
        try {
            if (residency.contains(root(cell))) map(cell, next, residency);
            else rootsPending.add(cell);
        } catch (RuntimeException | Error failure) {
            allocator.free(block);
            if (previous == null) residency.unpin(root(cell));
            throw failure;
        }
        cells.put(cell, next);
        if (previous != null) allocator.free(previous.block);
    }

    void update(Set<Page> changed, VirtualTextureResidency residency) {
        var iterator = rootsPending.iterator();
        while (iterator.hasNext()) {
            var cell = iterator.next();
            if (!residency.contains(root(cell))) continue;
            map(cell, java.util.Objects.requireNonNull(cells.get(cell)), residency);
            iterator.remove();
        }
        for (var page : changed) {
            if (page.mip() > bias) continue;
            var cell = cell(page);
            var allocation = cells.get(cell);
            if (allocation != null && allocation.mapped) update(cell, allocation.block, page, residency);
        }
    }

    private void map(Cell cell, Allocation allocation, VirtualTextureResidency residency) {
        var table = java.util.Objects.requireNonNull(pages).texture();
        var block = allocation.block;
        int mipBias = bias - block.level;
        int root = residency.pageEntry(root(cell), bias, mipBias);
        for (int mip = 0; mip <= block.level; mip++)
            write(table, mip, block.x >> mip, block.y >> mip, block.side() >> mip, block.side() >> mip, (x, y) -> root);
        for (var page : residency.pages())
            if (page.mip() >= mipBias && page.mip() < bias && cell.equals(cell(page))) update(cell, block, page, residency);
        publish(cell, (1 << 28) | (block.level << 24) | (block.y << 12) | block.x);
        allocation.mapped = true;
    }

    private void update(Cell cell, Block block, Page changed, VirtualTextureResidency residency) {
        var table = java.util.Objects.requireNonNull(pages).texture();
        int mipBias = bias - block.level;
        int top = changed.mip() - mipBias;
        if (top < 0) return;
        for (int mip = 0; mip <= top; mip++) {
            int size = 1 << (top - mip);
            int producerMip = mip + mipBias;
            int x = changed.x() << (top - mip), y = changed.y() << (top - mip);
            int localX = x - (cell.x() << (bias - producerMip)), localY = y - (cell.y() << (bias - producerMip));
            write(table, mip, (block.x >> mip) + localX, (block.y >> mip) + localY, size, size,
                    (dx, dy) -> residency.pageEntry(new Page(producerMip, x + dx, y + dy), bias, mipBias));
        }
    }

    private Cell cell(Page page) { return new Cell(page.x() >> (bias - page.mip()), page.y() >> (bias - page.mip())); }
    private Page root(Cell cell) { return new Page(bias, cell.x(), cell.y()); }

    private void publish(Cell cell, int value) {
        write(java.util.Objects.requireNonNull(indirection).texture(), 0, cell.x(), cell.y(), 1, 1, (x, y) -> value);
    }

    private void initialize() {
        if (pages != null) return;
        int side = 1 << tableLog;
        var limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        if (side > limit || grid.pagesX() > limit || grid.pagesY() > limit)
            throw new IllegalStateException("Adaptive runtime virtual texture " + name + " exceeds the device page-table limit");
        var table = image(name + " adaptive pages", side, side, bias + 1);
        Image map = null;
        try {
            map = image(name + " adaptive grid", grid.pagesX(), grid.pagesY(), 1);
            write(map.texture(), 0, 0, 0, grid.pagesX(), grid.pagesY(), (x, y) -> 0);
            pages = table;
            indirection = map;
        } catch (RuntimeException | Error failure) {
            table.close();
            if (map != null) map.close();
            throw failure;
        }
    }

    private static Image image(String label, int width, int height, int mips) {
        var device = RenderSystem.getDevice();
        var texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R32_UINT, width, height, 1, mips);
        try { return new Image(texture, device.createTextureView(texture)); }
        catch (RuntimeException | Error failure) { texture.close(); throw failure; }
    }

    private static void write(GpuTexture image, int mip, int x, int y, int width, int height, IntBinaryOperator value) {
        int rows = Math.min(height, Math.max(1, MAX_UPLOAD_BYTES / (width * Integer.BYTES)));
        var data = MemoryUtil.memAlloc(width * rows * Integer.BYTES).order(ByteOrder.nativeOrder());
        try {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int row = 0; row < height; row += rows) {
                int count = Math.min(rows, height - row);
                data.clear();
                for (int dy = 0; dy < count; dy++) for (int dx = 0; dx < width; dx++) data.putInt(value.applyAsInt(dx, row + dy));
                encoder.writeToTexture(image, data.flip(), mip, 0, x, y + row, width, count);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    @Override public void close() {
        if (indirection != null) indirection.close();
        if (pages != null) pages.close();
        indirection = null;
        pages = null;
        cells.clear();
        demands.clear();
        rootsPending.clear();
    }
}
