package com.kltyton.lumenfx.effect.asset.mesh;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.resource.mesh.MeshLodRead;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.ResidentLods;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Streaming;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Owns the separately packaged LODs of one resource generation. Publication occurs before simulation. */
public final class StaticMeshStreaming {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PENDING_READS = 4;
    private static final int NO_REQUEST = Integer.MAX_VALUE;
    // Keep counting cancelled generation reads until their worker actually returns.
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private record Demand(int first, int visibleFirst) {
        private static final Demand NONE = new Demand(NO_REQUEST, NO_REQUEST);
    }
    private record Load(Asset asset, int first, int priority) { }

    private static final class Asset implements Streaming {
        final Identifier id;
        final int lodCount, firstInline, firstAvailable, firstNonOptional;
        final Map<Integer, StaticMeshAssetLoader.StreamedLod> source;
        final AtomicReference<Demand> demand = new AtomicReference<>(Demand.NONE);
        double lastVisible = Double.NEGATIVE_INFINITY;
        volatile ResidentLods resident;
        @Nullable MeshLodRead<StaticMeshGeometry> pending;
        int failedFirst = NO_REQUEST;
        boolean missingTooManyMips;

        Asset(Identifier id, int lodCount, int firstInline, int firstAvailable, int firstNonOptional,
              Map<Integer, StaticMeshAssetLoader.StreamedLod> source) {
            this.id = id;
            this.lodCount = lodCount;
            this.firstInline = firstInline;
            this.firstAvailable = firstAvailable;
            this.firstNonOptional = firstNonOptional;
            this.source = Map.copyOf(source);
            resident = new ResidentLods(firstInline, Map.of());
        }

        @Override public ResidentLods resident() { return resident; }

        @Override public void request(int firstLod) { request(firstLod, true); }

        @Override public void request(int firstLod, boolean visible) {
            int first = Math.min(firstInline, Math.max(firstLod, firstAvailable));
            demand.getAndUpdate(old -> {
                int wanted = Math.min(old.first(), first);
                int visibleWanted = visible ? Math.min(old.visibleFirst(), first) : old.visibleFirst();
                return wanted == old.first() && visibleWanted == old.visibleFirst() ? old : new Demand(wanted, visibleWanted);
            });
        }

        Map<Integer, StaticMeshGeometry> read(CompletableFuture<Map<Integer, StaticMeshGeometry>> destination,
                                               int first, ResidentLods previous) {
            var result = new LinkedHashMap<Integer, StaticMeshGeometry>();
            for (int index = first; index < firstInline && !destination.isCancelled(); index++) {
                var geometry = previous.streamed().get(index);
                var payload = source.get(index);
                if (geometry != null) result.put(index, geometry);
                else if (payload != null) result.put(index, payload.read());
            }
            return result;
        }

        void complete(int wanted, Consumer<Collection<StaticMeshGeometry>> upload) {
            var read = pending;
            if (read == null || !read.finished) return;
            pending = null;
            if (read.future.isCancelled() || wanted >= firstInline) return;
            try {
                int first = Math.max(read.first, wanted);
                var loaded = new LinkedHashMap<Integer, StaticMeshGeometry>();
                read.future.join().forEach((index, geometry) -> {
                    if (index >= first) loaded.put(index, geometry);
                });
                upload.accept(loaded.values());
                resident = new ResidentLods(first, loaded);
            } catch (CompletionException failure) {
                failedFirst = read.first;
                LOGGER.error("Cannot stream static mesh {} from LOD {}", id, read.first, failure.getCause());
            }
        }

        void trim(int first) {
            var previous = resident;
            if (first <= previous.firstLod()) return;
            var retained = new LinkedHashMap<Integer, StaticMeshGeometry>();
            previous.streamed().forEach((index, geometry) -> {
                if (index >= first) retained.put(index, geometry);
            });
            resident = new ResidentLods(first, retained);
        }

    }

    private final List<Asset> assets = new ArrayList<>();

    Streaming register(Identifier id, int lodCount, int firstInline, int firstAvailable, int firstNonOptional,
                       Map<Integer, StaticMeshAssetLoader.StreamedLod> source) {
        var asset = new Asset(id, lodCount, firstInline, firstAvailable, firstNonOptional, source);
        assets.add(asset);
        return asset;
    }

    public void advance(double time, Consumer<Collection<StaticMeshGeometry>> upload) {
        var ready = new ArrayList<Load>();
        int splitThreshold = NiagaraPlatforms.snapshot().streamingSplitThreshold();
        for (Asset asset : assets) {
            var demand = asset.demand.getAndSet(Demand.NONE);
            boolean wasMissingTooManyMips = asset.missingTooManyMips;
            int wanted = Math.min(asset.firstInline, demand.first());
            if (demand.visibleFirst() != NO_REQUEST) asset.lastVisible = time;
            asset.complete(wanted, upload);
            int resident = asset.resident.firstLod();
            int visibleFirst = demand.visibleFirst();
            if (splitThreshold > 0 && asset.lodCount - wanted >= splitThreshold && wanted < visibleFirst && visibleFirst < resident)
                wanted = visibleFirst;
            // The optional payload is requested only after the non-optional range is resident.
            if (resident > asset.firstNonOptional) wanted = Math.max(wanted, asset.firstNonOptional);
            if (asset.failedFirst != NO_REQUEST && wanted != asset.failedFirst) asset.failedFirst = NO_REQUEST;
            int requested = asset.pending == null ? resident : asset.pending.first;
            asset.missingTooManyMips = requested != wanted && wanted < requested && visibleFirst < resident
                    && resident - wanted > 1;
            if (asset.pending != null) {
                asset.pending.cancelSuperseded(wanted, resident, wasMissingTooManyMips, asset.missingTooManyMips);
                continue;
            }
            if (wanted >= resident) {
                asset.trim(wanted);
                continue;
            }
            if (wanted == asset.failedFirst) continue;
            boolean visible = visibleFirst < resident;
            int priority = visible ? 1024 : Math.clamp(255 - (int)Math.min(255, time - asset.lastVisible), 1, 255);
            if (resident - wanted > 1) priority += 256;
            ready.add(new Load(asset, wanted, priority));
        }
        ready.sort(Comparator.comparingInt(Load::priority).reversed());
        for (var load : ready) {
            if (IN_FLIGHT.get() >= MAX_PENDING_READS) break;
            var asset = load.asset();
            int wanted = load.first();
            var previous = asset.resident;
            var read = new MeshLodRead<StaticMeshGeometry>(wanted);
            asset.pending = read;
            read.submit("LumenFX static mesh LOD I/O", IN_FLIGHT, () -> asset.read(read.future, wanted, previous));
        }
    }

    public void clearResidency() {
        for (Asset asset : assets) {
            var pending = asset.pending;
            if (pending != null) pending.future.cancel(false);
            asset.pending = null;
            asset.demand.set(Demand.NONE);
            asset.lastVisible = Double.NEGATIVE_INFINITY;
            asset.failedFirst = NO_REQUEST;
            asset.missingTooManyMips = false;
            asset.resident = new ResidentLods(asset.firstInline, Map.of());
        }
    }
}
