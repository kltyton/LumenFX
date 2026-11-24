package com.kltyton.lumenfx.client.resource.mesh;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.ResidentLods;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Streaming;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Publishes complete skeletal LOD payloads before reader capture; GPU snapshots retain each published LOD. */
public final class SkeletalMeshStreaming {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PENDING_READS = 4;
    private static final int NO_REQUEST = Integer.MAX_VALUE;
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    record Payload(Identifier id, Resource resource, int vertexCount, int flags, int version) {
        Lod read(int index) {
            try (var input = resource.open()) {
                var bytes = input.readAllBytes();
                var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                var lod = SkeletalMeshAssetLoader.lod(data, version).withIndex(index);
                if (data.hasRemaining() || lod.vertexCount() != vertexCount || lod.cpuAccess() != ((flags & 1) != 0)
                        || lod.hasColors() != ((flags & 2) != 0) || lod.cpuColors() != ((flags & 4) != 0)
                        || lod.unlimitedInfluences() != ((flags & 8) != 0)
                        || version >= 6 && lod.supportsUniformlyDistributedSampling() != ((flags & 16) != 0))
                    throw new IllegalArgumentException("Skeletal LOD payload disagrees with its residency descriptor " + id);
                return lod;
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot stream skeletal LOD " + id, failure);
            }
        }
    }


    private static final class Asset implements Streaming {
        final Identifier id;
        final int lodCount, firstInline;
        final Map<Integer, Payload> source;
        final Map<Integer, Lod> inline;
        final AtomicReference<Integer> demand = new AtomicReference<>(NO_REQUEST);
        volatile ResidentLods resident;
        @Nullable MeshLodRead<Lod> pending;
        int failedFirst = NO_REQUEST;

        Asset(Identifier id, int lodCount, int firstInline, Map<Integer, Lod> inline, Map<Integer, Payload> source) {
            this.id = id;
            this.lodCount = lodCount;
            this.firstInline = firstInline;
            this.inline = Map.copyOf(inline);
            this.source = Map.copyOf(source);
            resident = new ResidentLods(firstInline, inline);
        }

        @Override public ResidentLods resident() { return resident; }

        @Override public void request(int firstLod) {
            int first = Math.clamp(firstLod, 0, firstInline);
            demand.getAndUpdate(old -> Math.min(old, first));
        }

        Map<Integer, Lod> read(MeshLodRead<Lod> read, ResidentLods previous) {
            var result = new LinkedHashMap<Integer, Lod>();
            for (int index = read.first; index < firstInline && !read.future.isCancelled(); index++) {
                var existing = previous.lods().get(index);
                var payload = source.get(index);
                if (existing != null) result.put(index, existing);
                else if (payload != null) result.put(index, payload.read(index));
                else throw new IllegalStateException("Missing streamed skeletal LOD " + index + " for " + id);
            }
            return result;
        }

        void complete(int wanted) {
            var read = pending;
            if (read == null || !read.finished) return;
            pending = null;
            if (read.future.isCancelled() || wanted >= firstInline) return;
            try {
                int first = Math.max(read.first, wanted);
                var loaded = new LinkedHashMap<Integer, Lod>();
                read.future.join().forEach((index, lod) -> { if (index >= first) loaded.put(index, lod); });
                inline.forEach(loaded::putIfAbsent);
                resident = new ResidentLods(first, loaded);
            } catch (CompletionException failure) {
                failedFirst = read.first;
                LOGGER.error("Cannot stream skeletal mesh {} from LOD {}", id, read.first, failure.getCause());
            }
        }

        void trim(int first) {
            var previous = resident;
            if (first <= previous.firstLod()) return;
            var retained = new LinkedHashMap<Integer, Lod>();
            previous.lods().forEach((index, lod) -> { if (index >= first) retained.put(index, lod); });
            inline.forEach(retained::putIfAbsent);
            resident = new ResidentLods(first, retained);
        }
    }

    private final List<Asset> assets = new ArrayList<>();
    private int nextAsset;

    Streaming register(Identifier id, int lodCount, int firstInline, Map<Integer, Lod> inline,
                       Map<Integer, Payload> source) {
        var asset = new Asset(id, lodCount, firstInline, inline, source);
        assets.add(asset);
        return asset;
    }

    /** Called at the existing resource-generation simulation boundary. */
    public void advance() {
        int count = assets.size();
        for (int offset = 0; offset < count; offset++) {
            Asset asset = assets.get((nextAsset + offset) % count);
            int wanted = Math.min(asset.firstInline, asset.demand.getAndSet(NO_REQUEST));
            if (wanted != asset.failedFirst) asset.failedFirst = NO_REQUEST;
            asset.complete(wanted);
            int resident = asset.resident.firstLod();
            if (asset.pending != null) {
                // Simulation readers request residency without camera visibility.
                asset.pending.cancelSuperseded(wanted, resident, false, false);
                continue;
            }
            if (wanted >= resident) {
                asset.trim(wanted);
                continue;
            }
            if (wanted == asset.failedFirst) continue;
            if (IN_FLIGHT.get() >= MAX_PENDING_READS) continue;
            var previous = asset.resident;
            var read = new MeshLodRead<Lod>(wanted);
            asset.pending = read;
            read.submit("LumenFX skeletal mesh LOD I/O", IN_FLIGHT, () -> asset.read(read, previous));
        }
        if (count != 0) nextAsset = (nextAsset + 1) % count;
    }

    public void clearResidency() {
        for (Asset asset : assets) {
            var pending = asset.pending;
            if (pending != null) pending.future.cancel(false);
            asset.pending = null;
            asset.demand.set(NO_REQUEST);
            asset.failedFirst = NO_REQUEST;
            asset.resident = new ResidentLods(asset.firstInline, asset.inline);
        }
        nextAsset = 0;
    }
}
