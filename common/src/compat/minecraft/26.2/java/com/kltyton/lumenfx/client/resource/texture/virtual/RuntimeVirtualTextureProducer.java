package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.ProducerLayout;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.PageTableLayout;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.PageView;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureVolume;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTileAtlas;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Render-thread producer for placed RVT volumes, publishing all material layers atomically by page. */
final class RuntimeVirtualTextureProducer implements RuntimeVirtualTextureVolume {
    private static final int MAX_IN_FLIGHT = 8;
    private static final int MAX_REGION_CURSORS = 512;
    private static final int MAX_REQUEST_INSPECTIONS = 512;
    private static final long POOL_BYTES = 16L << 20;
    private record Region(int mip, int minX, int minY, int maxX, int maxY) { }
    private static final class Cursor {
        private final Region region;
        private int x, y;
        Cursor(Region region) { this.region = region; x = region.minX(); y = region.minY(); }
        Page page() { return new Page(region.mip(), x, y); }
        void advance() {
            if (++x > region.maxX()) { x = region.minX(); y++; }
        }
        boolean done() { return y > region.maxY(); }
    }

    private final ClientLevel level;
    private final RuntimeVirtualTextureDefinition definition;
    private final Volume volume;
    private final ProducerLayout layout;
    private final PageTableLayout allocation;
    private final RuntimeVirtualTexturePageRenderer renderer;
    private final @Nullable RuntimeVirtualTextureLowMips streaming;
    private final Consumer<RuntimeVirtualTextureProducer> detach;
    private final List<RuntimeVirtualTexturePageRenderer.Layer> formats;
    private final Set<Page> roots = new LinkedHashSet<>();
    private final Set<Page> refresh = new LinkedHashSet<>();
    private final Set<Page> invalidatedFlights = new HashSet<>();
    private final Map<Page, CompletableFuture<RuntimeVirtualTexturePageRenderer.Page>> inFlight = new LinkedHashMap<>();
    private final ArrayDeque<Cursor> requests = new ArrayDeque<>();
    private final Set<Region> regions = new HashSet<>();
    private final ArrayDeque<Page> continuous = new ArrayDeque<>();
    private final List<NativeTileAtlas> atlases = new ArrayList<>();
    private final VirtualTexturePageTable table;
    private final @Nullable AdaptiveVirtualTexturePageTable adaptive;
    private final VirtualTextureResidency residency;
    private final Snapshot snapshot;
    private int leases;
    private volatile boolean closed;

    RuntimeVirtualTextureProducer(String name, ClientLevel level, RuntimeVirtualTextureDefinition definition,
                                  Volume volume, ProducerLayout sceneLayout, RuntimeVirtualTexturePageRenderer renderer,
                                  @Nullable VirtualTextureTileSource streamed, boolean streamedOnly, Semaphore ioBudget,
                                  Consumer<RuntimeVirtualTextureProducer> detach) {
        RenderSystem.assertOnRenderThread();
        this.level = Objects.requireNonNull(level);
        this.definition = Objects.requireNonNull(definition);
        this.volume = Objects.requireNonNull(volume);
        this.renderer = Objects.requireNonNull(renderer);
        this.detach = Objects.requireNonNull(detach);
        formats = RuntimeVirtualTextureStorage.layers(definition);
        streaming = streamed == null ? null : new RuntimeVirtualTextureLowMips(streamed, definition, sceneLayout, formats, streamedOnly, ioBudget);
        layout = streaming == null ? sceneLayout : streaming.layout();
        allocation = layout.niagaraPageTable(definition.adaptive());
        int physical = layout.physicalSize();
        int rootMip = allocation.maxMip();
        int rootWidth = Math.max(1, allocation.pagesX() >> rootMip);
        int rootHeight = Math.max(1, allocation.pagesY() >> rootMip);
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        int slotsLimit = Math.min(256, limit / physical);
        int maximumSlots = slotsLimit * slotsLimit;
        if ((long)rootWidth * rootHeight >= maximumSlots)
            throw new IllegalArgumentException("Runtime virtual texture roots exceed the physical page pool: " + name);
        for (int y = 0; y < rootHeight; y++) for (int x = 0; x < rootWidth; x++)
            roots.add(new Page(rootMip + allocation.producerMipBias(), x, y));
        int pageBytes = formats.stream().mapToInt(format -> RuntimeVirtualTextureStorage.storage(format).byteSize(physical, physical)).sum();
        int minSlots = roots.size() + 1;
        int desiredSlots = Math.max(minSlots + (minSlots < maximumSlots ? 1 : 0), (int)Math.min(maximumSlots, POOL_BYTES / pageBytes));
        int slots = Math.min(slotsLimit, (int)Math.ceil(Math.sqrt(desiredSlots)));
        int atlasSize = physical * slots;
        residency = new VirtualTextureResidency(slots, roots);
        table = new VirtualTexturePageTable("LumenFX RVT table " + name, allocation.pagesX(), allocation.pagesY(), rootMip);
        adaptive = allocation.producerMipBias() == 0 ? null
                : new AdaptiveVirtualTexturePageTable(name, allocation, layout.adaptiveTableLog2(), roots.size());
        try {
            var layers = new HashMap<Integer, Layer>();
            float inverseSize = 1f / atlasSize;
            for (int index = 0; index < formats.size(); index++) {
                var format = formats.get(index);
                var layerUniform = new Uint4(streaming == null ? 0 : streaming.fallbackColor(index),
                        bits(layout.tileSize() * inverseSize), bits(layout.border() * inverseSize), bits(physical * inverseSize));
                var atlas = new NativeTileAtlas("LumenFX RVT " + name + " layer " + index, atlasSize,
                        new NativeTextureStorage(RuntimeVirtualTextureStorage.storage(format), format.srgb()), true);
                atlases.add(atlas);
                layers.put(index, new Layer(atlas.view(), table.view(), layerUniform, format.srgb(), format.yCoCg()));
            }
            var table0 = new Uint4(bits(1), bits(1), bits(allocation.pagesX()), bits(allocation.pagesY()));
            var table1 = new Uint4(0, Integer.numberOfTrailingZeros(layout.tileSize()) << 24,
                    0x100 | rootMip | (allocation.producerMipBias() << 4), 0);
            snapshot = new Snapshot(definition.material(), volume.origin(), volume.u(), volume.v(),
                    (float)volume.heightScale(), (float)volume.minHeight(), table0, table1, layers,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    ClientLevel level() { return level; }
    RuntimeVirtualTextureDefinition.Priority priority() { return definition.priority(); }

    @Override public @Nullable Lease acquire() {
        RenderSystem.assertOnRenderThread();
        if (closed) return null;
        leases++;
        var fine = adaptive == null ? null : adaptive.publication();
        return new Lease(fine == null ? snapshot : snapshot.withAdaptive(fine), this::release);
    }

    @Override public void request(Feedback feedback) {
        RenderSystem.assertOnRenderThread();
        if (closed || !valid(feedback) || requests.size() >= MAX_REGION_CURSORS) return;
        int mip = Math.clamp(feedback.mip(), 0, allocation.maxMip() + allocation.producerMipBias());
        var region = new Region(mip, coordinate(feedback.minU(), layout.pagesX(), mip), coordinate(feedback.minV(), layout.pagesY(), mip),
                coordinate(feedback.maxU(), layout.pagesX(), mip), coordinate(feedback.maxV(), layout.pagesY(), mip));
        if (regions.add(region)) requests.addLast(new Cursor(region));
    }

    @Override public void invalidate(Feedback region) {
        RenderSystem.assertOnRenderThread();
        if (closed || !valid(region)) return;
        for (Page page : residency.pages()) if (!streamed(page) && intersects(page, region)) refresh.add(page);
        for (Page page : inFlight.keySet()) if (!streamed(page) && intersects(page, region)) {
            invalidatedFlights.add(page);
            refresh.add(page);
        }
    }

    void invalidateWorld(AABB bounds) {
        Vec3 center = bounds.getCenter().subtract(volume.origin());
        Vec3 extent = new Vec3((bounds.maxX - bounds.minX) * .5, (bounds.maxY - bounds.minY) * .5, (bounds.maxZ - bounds.minZ) * .5);
        Vec3 u = volume.u(), v = volume.v(), w = volume.zAxis().scale(1 / volume.zAxis().lengthSqr());
        double centerW = w.dot(center), extentW = projectedExtent(w, extent);
        if (centerW - extentW > 1 || centerW + extentW < 0) return;
        double centerU = u.dot(center), centerV = v.dot(center);
        double extentU = projectedExtent(u, extent), extentV = projectedExtent(v, extent);
        invalidate(new Feedback(centerU - extentU, centerV - extentV, centerU + extentU, centerV + extentV, 0));
    }

    private static double projectedExtent(Vec3 axis, Vec3 extent) {
        return Math.abs(axis.x) * extent.x + Math.abs(axis.y) * extent.y + Math.abs(axis.z) * extent.z;
    }

    private boolean intersects(Page page, Feedback region) {
        var view = pageView(page);
        return region.minU() <= view.maxU() && region.maxU() >= view.minU()
                && region.minV() <= view.maxV() && region.maxV() >= view.minV();
    }

    private PageView pageView(Page page) {
        return layout.pageView(volume, page.mip(), page.x(), page.y());
    }

    private static boolean valid(Feedback feedback) {
        return Double.isFinite(feedback.minU()) && Double.isFinite(feedback.minV())
                && Double.isFinite(feedback.maxU()) && Double.isFinite(feedback.maxV())
                && feedback.minU() <= feedback.maxU() && feedback.minV() <= feedback.maxV()
                && feedback.maxU() >= 0 && feedback.maxV() >= 0 && feedback.minU() <= 1 && feedback.minV() <= 1;
    }

    private static int coordinate(double uv, int pages, int mip) {
        return Math.min(pages - 1, (int)(Math.clamp(uv, 0, 1) * pages)) >> mip;
    }

    private boolean streamed(Page page) { return streaming != null && streaming.owns(page); }
    private boolean available(Page page) { return streaming == null || !streaming.owns(page) || streaming.available(page); }

    int render(int budget) {
        RenderSystem.assertOnRenderThread();
        if (closed) return 0;
        Set<Page> dirty = new LinkedHashSet<>();
        try {
            consumeCompleted(dirty);
            if (adaptive != null) adaptive.advance(residency);
            if (budget <= 0 || closed) return 0;
            boolean canRaster = (streaming == null || streaming.hasLivePages()) && renderer.ready(level);
            if (!canRaster && streaming == null) return 0;
            int submitted = 0;
            for (int available = Math.min(budget, MAX_IN_FLIGHT - inFlight.size()); submitted < available; submitted++) {
                Page page = nextRequest(canRaster);
                if (page == null) break;
                refresh.remove(page);
                try {
                    CompletableFuture<RuntimeVirtualTexturePageRenderer.Page> rendered;
                    if (streamed(page)) {
                        rendered = Objects.requireNonNull(streaming).submit(page, () -> closed);
                        if (rendered == null) {
                            refresh.add(page);
                            break;
                        }
                    } else {
                        var request = new RuntimeVirtualTexturePageRenderer.Request(definition, volume, pageView(page), formats);
                        rendered = renderer.render(level, request).toCompletableFuture();
                    }
                    if (closed) return submitted + 1;
                    inFlight.put(page, rendered);
                } catch (RuntimeException | Error failure) {
                    refresh.add(page);
                    throw failure;
                }
            }
            consumeCompleted(dirty);
            return submitted;
        } finally {
            if (!closed) publish(dirty);
        }
    }

    private void publish(Set<Page> dirty) {
        int bias = allocation.producerMipBias();
        if (bias == 0) table.update(dirty, page -> residency.pageEntry(page, allocation.maxMip()));
        else {
            var coarse = new LinkedHashSet<Page>();
            for (var page : dirty) if (page.mip() >= bias) coarse.add(new Page(page.mip() - bias, page.x(), page.y()));
            table.update(coarse, page -> residency.pageEntry(new Page(page.mip() + bias, page.x(), page.y()),
                    allocation.maxMip() + bias, bias));
            Objects.requireNonNull(adaptive).update(dirty, residency);
        }
    }

    private @Nullable Page nextRequest(boolean canRaster) {
        for (Page page : roots)
            if ((canRaster || streamed(page)) && available(page) && !residency.contains(page) && !inFlight.containsKey(page)) return page;
        if (adaptive != null) {
            var root = adaptive.pendingRoot(residency, inFlight.keySet(), page -> (canRaster || streamed(page)) && available(page));
            if (root != null) return root;
        }
        var dirty = refresh.iterator();
        while (dirty.hasNext()) {
            Page page = dirty.next();
            if (inFlight.containsKey(page) || !canRaster && !streamed(page)) continue;
            dirty.remove();
            return page;
        }
        for (int i = 0; i < MAX_REQUEST_INSPECTIONS && !requests.isEmpty(); i++) {
            Cursor cursor = requests.removeFirst();
            Page page = cursor.page();
            var route = adaptive == null ? null : adaptive.route(page);
            if (route != null) page = route.page();
            boolean pageAvailable = available(page);
            while (!pageAvailable && page.mip() < allocation.maxMip() + allocation.producerMipBias()) {
                page = page.parent();
                pageAvailable = available(page);
            }
            if (!canRaster && !streamed(page)) {
                requests.addLast(cursor);
                continue;
            }
            if (route == null || route.exact() || !available(route.page())) cursor.advance();
            if (cursor.done()) {
                regions.remove(cursor.region);
            } else requests.addLast(cursor);
            if (pageAvailable && !residency.touch(page) && !inFlight.containsKey(page)) return page;
        }
        if (!canRaster || !definition.continuous() || !requests.isEmpty()) return null;
        if (continuous.isEmpty()) continuous.addAll(residency.pages());
        while (!continuous.isEmpty()) {
            Page page = continuous.removeFirst();
            if (!streamed(page) && residency.contains(page) && !inFlight.containsKey(page)) return page;
        }
        return null;
    }

    private void consumeCompleted(Set<Page> dirty) {
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().isDone()) continue;
            Page page = entry.getKey();
            iterator.remove();
            if (invalidatedFlights.remove(page)) continue;
            try {
                upload(page, Objects.requireNonNull(entry.getValue().join()), dirty);
            } catch (RuntimeException | Error failure) {
                refresh.add(page);
                throw failure;
            }
        }
    }

    private void upload(Page page, RuntimeVirtualTexturePageRenderer.Page output, Set<Page> dirty) {
        if (output.layerCount() != formats.size())
            throw new IllegalArgumentException("Runtime virtual texture material returned a different layer count");
        int size = layout.physicalSize();
        int maxBytes = 0;
        for (int layer = 0; layer < formats.size(); layer++) {
            int bytes = RuntimeVirtualTextureStorage.storage(formats.get(layer)).byteSize(size, size);
            if (output.layer(layer).remaining() != bytes)
                throw new IllegalArgumentException("Runtime virtual texture material returned a different layer encoding");
            maxBytes = Math.max(maxBytes, bytes);
        }
        var staging = MemoryUtil.memAlloc(maxBytes);
        try {
            int slot = residency.reserve(dirty);
            if (slot < 0) return;
            try {
                for (int index = 0; index < formats.size(); index++) {
                    staging.clear();
                    staging.put(output.layer(index)).flip();
                    atlases.get(index).write(residency.x(slot) * size, residency.y(slot) * size, size, staging);
                }
                residency.publish(page, slot);
                dirty.add(page);
            } catch (RuntimeException | Error failure) {
                residency.discard(slot);
                throw failure;
            }
        } finally {
            MemoryUtil.memFree(staging);
        }
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        detach.accept(this);
        inFlight.clear();
        refresh.clear();
        invalidatedFlights.clear();
        requests.clear();
        regions.clear();
        continuous.clear();
        if (leases == 0) destroy();
    }

    private void release() {
        if (--leases == 0 && closed) destroy();
    }

    private void destroy() {
        try { atlases.forEach(NativeTileAtlas::close); }
        finally {
            if (adaptive != null) adaptive.close();
            table.close();
        }
    }
}
