package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Layer;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Uint4;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTileAtlas;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureTileSource.Request;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureTileSource.Tile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Owns a sparse, bounded resident cache of native tiles for one packaged streaming VT. */
public final class StreamedVirtualTexture implements NiagaraStreamingVirtualTexture, AutoCloseable {
    private static final int MAX_IO_BATCH = 8;
    private static final int MAX_PENDING = 1024;
    private static final long PHYSICAL_POOL_BYTES = 16L << 20;

    private final VirtualTextureTileSource source;
    private final Semaphore ioBudget;
    private final int maxMip;
    private final Set<Page> roots = new LinkedHashSet<>();
    private final Set<Page> pending = new LinkedHashSet<>();
    private final Set<Page> reading = new HashSet<>();
    private @Nullable VirtualTextureResidency residency;
    private @Nullable CompletableFuture<List<Tile>> batch;
    private @Nullable VirtualTexturePageTable pageTable;
    private final List<NativeTileAtlas> atlases = new ArrayList<>();
    private @Nullable GpuSampler sampler;
    private @Nullable Snapshot publication;
    private volatile boolean closed;
    private int leases;

    public StreamedVirtualTexture(VirtualTextureTileSource source, Semaphore ioBudget) {
        this.source = source;
        this.ioBudget = ioBudget;
        var layout = source.directory().layout();
        int minPages = Math.min(layout.pagesX(), layout.pagesY());
        int tableMaxMip = Integer.SIZE - Integer.numberOfLeadingZeros(minPages - 1);
        maxMip = Math.min(tableMaxMip, source.directory().mips() - 1);
    }

    @Override public @Nullable Lease acquire() {
        RenderSystem.assertOnRenderThread();
        if (closed) return null;
        if (publication == null) initialize();
        pump();
        Snapshot snapshot = Objects.requireNonNull(publication);
        leases++;
        return new Lease(snapshot, this::release);
    }

    @Override public void request(Feedback region) {
        RenderSystem.assertOnRenderThread();
        if (closed || !Double.isFinite(region.minU()) || !Double.isFinite(region.minV())
                || !Double.isFinite(region.maxU()) || !Double.isFinite(region.maxV())
                || region.minU() > region.maxU() || region.minV() > region.maxV()) return;
        var layout = source.directory().layout();
        int mip = Math.clamp(region.mip(), 0, maxMip);
        int firstX = coordinate(region.minU(), layout.blocksX(), layout.pagesX(), mip);
        int firstY = coordinate(region.minV(), layout.blocksY(), layout.pagesY(), mip);
        int lastX = coordinate(region.maxU(), layout.blocksX(), layout.pagesX(), mip);
        int lastY = coordinate(region.maxV(), layout.blocksY(), layout.pagesY(), mip);
        for (int y = firstY; y <= lastY; y++) for (int x = firstX; x <= lastX; x++) {
            if (pending.size() >= MAX_PENDING) return;
            Page page = availableAncestor(new Page(mip, x, y));
            if (page == null) continue;
            if ((residency == null || !residency.touch(page)) && !reading.contains(page)) pending.add(page);
        }
    }

    private static int coordinate(double value, int blocks, int pages, int mip) {
        return Math.min(pages - 1, (int)(Math.clamp(value / blocks, 0, 1) * pages)) >> mip;
    }

    private @Nullable Page availableAncestor(Page start) {
        for (Page page = start; page.mip() < source.directory().mips(); page = page.parent())
            if (source.directory().range(page, 0) != null) return page;
        return null;
    }

    private void initialize() {
        var layout = source.directory().layout();
        var layers = source.directory().layers();
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        int maxSlotsWide = Math.min(256, limit / layout.physicalSize());
        if (maxSlotsWide == 0) throw new IllegalArgumentException("Physical VT tile exceeds the device limit: " + source.id());
        int rootX = VirtualTexturePageTable.ceilDivide(layout.pagesX(), 1 << maxMip);
        int rootY = VirtualTexturePageTable.ceilDivide(layout.pagesY(), 1 << maxMip);
        for (int y = 0; y < rootY; y++) for (int x = 0; x < rootX; x++) {
            Page page = availableAncestor(new Page(maxMip, x, y));
            if (page != null) roots.add(page);
        }
        if (roots.size() >= maxSlotsWide * maxSlotsWide)
            throw new IllegalArgumentException("Pinned VT root tiles exceed the physical cache: " + source.id());
        long pageBytes = layers.stream().mapToLong(VirtualTexturePages.Layer::bytes).sum();
        long capacity = Math.min((long)maxSlotsWide * maxSlotsWide, PHYSICAL_POOL_BYTES / pageBytes);
        int minSlots = roots.size() + 1;
        int desiredSlots = Math.max(minSlots + (minSlots < maxSlotsWide * maxSlotsWide ? 1 : 0), (int)capacity);
        int slotsWide = Math.min(maxSlotsWide, (int)Math.ceil(Math.sqrt(desiredSlots)));
        int size = slotsWide * layout.physicalSize();
        residency = new VirtualTextureResidency(slotsWide, roots);
        try {
            pageTable = new VirtualTexturePageTable("LumenFX VT table " + source.id(), layout.pagesX(), layout.pagesY(), maxMip);
            sampler = EffectSamplerFactory.create(new EffectSamplerFactory.Settings(address(layout.addressU()), address(layout.addressV()),
                    layout.filter() == 0 ? FilterMode.POINT : layout.filter() >= 3 ? FilterMode.ANISO : FilterMode.LINEAR,
                    layout.filter() == 2 || layout.filter() == 4 ? FilterMode.LINEAR : FilterMode.POINT,
                    Math.max(1, layout.physicalAnisotropy())), 1);
            float inverseSize = 1f / size;
            var physical = new ArrayList<Layer>(layers.size());
            for (int index = 0; index < layers.size(); index++) {
                var layer = layers.get(index);
                var atlas = new NativeTileAtlas("LumenFX VT pages " + source.id() + " layer " + index, size,
                        new NativeTextureStorage(layer.format(), layer.srgb()), layout.filter() != 0);
                atlases.add(atlas);
                var layerUniform = new Uint4(layer.fallbackColor(), bits(layout.tileSize() * inverseSize),
                        bits(layout.border() * inverseSize), bits(layout.physicalSize() * inverseSize));
                physical.add(new Layer(atlas.view(), pageTable.view(), layerUniform, layer.srgb(), false));
            }
            var table0 = new Uint4(bits(1f / layout.blocksX()), bits(1f / layout.blocksY()), bits(layout.pagesX()), bits(layout.pagesY()));
            int maxAnisoLog = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(Math.max(1, layout.maxAnisotropy()));
            var table1 = new Uint4(bits(maxAnisoLog), Integer.numberOfTrailingZeros(layout.tileSize()) << 24, maxMip | 0x100, 0);
            publication = new Snapshot(layout.width(), layout.height(), source.directory().mips(),
                    physical.getFirst(), null, table0, table1, sampler, physical, addressCode(layout.addressU()), addressCode(layout.addressV()));
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    private void pump() {
        if (batch != null && batch.isDone()) {
            var ready = batch;
            batch = null;
            try { upload(ready.join()); }
            catch (CompletionException failure) {
                pending.addAll(reading);
                throw failure;
            }
            finally { reading.clear(); }
        }
        if (batch != null || !ioBudget.tryAcquire()) return;
        var resident = Objects.requireNonNull(residency);
        List<Request> requests = new ArrayList<>(MAX_IO_BATCH);
        for (Page root : roots) {
            if (!resident.contains(root)) append(requests, root);
            if (reading.size() == MAX_IO_BATCH) break;
        }
        var iterator = pending.iterator();
        while (reading.size() < MAX_IO_BATCH && iterator.hasNext()) {
            Page page = iterator.next();
            iterator.remove();
            if (!resident.contains(page)) append(requests, page);
        }
        if (requests.isEmpty()) {
            ioBudget.release();
            return;
        }
        try {
            batch = CompletableFuture.supplyAsync(() -> {
                try { return source.readTiles(requests, () -> closed); }
                catch (IOException failure) { throw new UncheckedIOException("Could not stream " + source.id(), failure); }
                finally { ioBudget.release(); }
            }, Util.backgroundExecutor().forName("LumenFX virtual texture tile I/O"));
        } catch (RuntimeException | Error failure) {
            ioBudget.release();
            pending.addAll(reading);
            reading.clear();
            throw failure;
        }
    }

    private void append(List<Request> output, Page page) {
        if (source.directory().range(page, 0) == null || !reading.add(page)) return;
        for (int layer = 0; layer < source.directory().layers().size(); layer++)
            output.add(new Request(page, layer, Objects.requireNonNull(source.directory().range(page, layer))));
    }

    private void upload(List<Tile> tiles) {
        var table = Objects.requireNonNull(pageTable);
        var resident = Objects.requireNonNull(residency);
        Set<Page> dirty = new LinkedHashSet<>();
        int tileSize = source.directory().layout().physicalSize();
        var pages = new LinkedHashMap<Page, List<Tile>>();
        for (var tile : tiles) pages.computeIfAbsent(tile.page(), ignored -> new ArrayList<>()).add(tile);
        try {
            for (var page : pages.entrySet()) {
                if (page.getValue().size() != atlases.size()) throw new IllegalStateException("Incomplete physical VT page " + page.getKey());
                int slot = resident.reserve(dirty);
                if (slot < 0) continue;
                try {
                    for (var tile : page.getValue()) {
                        var bytes = MemoryUtil.memAlloc(tile.data().length);
                        try {
                            bytes.put(tile.data()).flip();
                            atlases.get(tile.layer()).write(resident.x(slot) * tileSize, resident.y(slot) * tileSize, tileSize, bytes);
                        } finally {
                            MemoryUtil.memFree(bytes);
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    resident.discard(slot);
                    throw failure;
                }
                resident.publish(page.getKey(), slot);
                dirty.add(page.getKey());
            }
        } finally {
            table.update(dirty, page -> resident.pageEntry(page, source.directory().mips() - 1));
        }
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }

    // Source platform Address values are Wrap, Clamp, Mirror. The shader uses Clamp, Wrap, Mirror.
    private static int addressCode(int value) { return value == 2 ? 2 : 1 - value; }
    private static Address address(int value) { return value == 0 ? Address.WRAP : value == 1 ? Address.CLAMP : Address.MIRROR; }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        batch = null;
        pending.clear();
        reading.clear();
        roots.clear();
        residency = null;
        if (leases == 0) destroy();
    }

    private void release() {
        if (--leases == 0 && closed) destroy();
    }

    private void destroy() {
        publication = null;
        atlases.forEach(NativeTileAtlas::close);
        atlases.clear();
        if (pageTable != null) { pageTable.close(); pageTable = null; }
        if (sampler != null) {
            RenderSystem.queueFencedTask(sampler::close);
            sampler = null;
        }
    }
}
