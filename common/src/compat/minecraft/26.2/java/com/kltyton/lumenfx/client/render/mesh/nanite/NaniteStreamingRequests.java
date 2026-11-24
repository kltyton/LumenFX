package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

/** Authored request streams and resource prefetches enter the same priority table as GPU feedback. */
final class NaniteStreamingRequests {
    private static final long MAX_GPU_PRIORITY = 0xffffffe0L;
    private static final int PREFETCH_PAGES = 16;
    private static final int PAGE_BITS = 16;
    private static final int PAGE_MASK = (1 << PAGE_BITS) - 1;
    private static final int QUANTIZATION_MASK = (1 << (PAGE_BITS + 1)) - 1;

    private record ExplicitPage(int hash, int page, long priority) { }
    private record WaitingPage(NaniteMeshStreaming resource, int page, long priority) { }
    private record Prefetch(NaniteMeshAsset asset, int frames) { }

    private final List<ExplicitPage> explicit = new ArrayList<>();
    private final List<WaitingPage> waiting = new ArrayList<>();
    private final List<Prefetch> prefetch = new ArrayList<>();

    void enqueue(int[] stream) {
        var entries = new ArrayList<ExplicitPage>();
        int offset = 0;
        while (offset < stream.length) {
            int hash = stream[offset++];
            int packed;
            do {
                if (offset == stream.length) throw new IllegalArgumentException("Truncated Nanite page request stream");
                packed = stream[offset++];
                long priority = Math.min(Integer.toUnsignedLong(packed | QUANTIZATION_MASK), MAX_GPU_PRIORITY);
                entries.add(new ExplicitPage(hash, (packed >>> 1) & PAGE_MASK, priority));
            } while ((packed & 1) != 0);
        }
        explicit.addAll(entries);
    }

    void prefetch(NaniteMeshAsset asset, int framesUntilRender) {
        if (framesUntilRender < 0) throw new IllegalArgumentException("Nanite prefetch requires a nonnegative frame count");
        prefetch.add(new Prefetch(asset, Math.min(framesUntilRender, 30)));
    }

    void registerPrefetches(Consumer<NaniteMeshAsset> register) {
        for (var request : prefetch) register.accept(request.asset());
    }

    boolean hasPrefetches() { return !prefetch.isEmpty(); }

    void merge(Collection<NaniteMeshStreaming> resources) {
        if (!explicit.isEmpty()) {
            var byHash = new HashMap<Integer, List<NaniteMeshStreaming>>();
            for (var resource : resources) {
                int hash = resource.asset().persistentHash();
                if (hash != 0) byHash.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(resource);
            }
            for (var page : explicit) {
                for (var resource : byHash.getOrDefault(page.hash(), List.of()))
                    waiting.add(new WaitingPage(resource, page.page(), page.priority()));
            }
            explicit.clear();
        }
        waiting.removeIf(page -> {
            var resource = page.resource();
            if (resource.failedToLoadRoots()) return true;
            if (resource.current() == null) return false;
            resource.requestPage(page.page(), page.priority());
            return true;
        });
        if (!prefetch.isEmpty()) {
            var byAsset = new IdentityHashMap<NaniteMeshAsset, NaniteMeshStreaming>();
            for (var resource : resources) byAsset.put(resource.asset(), resource);
            var active = new ArrayList<Prefetch>();
            for (var request : prefetch) {
                var resource = byAsset.get(request.asset());
                if (resource == null || resource.failedToLoadRoots()) continue;
                if (resource.current() == null) {
                    active.add(request);
                    continue;
                }
                int first = request.asset().header().rootPages();
                int end = Math.min(request.asset().pages().size(), first + PREFETCH_PAGES);
                long priority = MAX_GPU_PRIORITY - request.frames();
                for (int page = first; page < end; page++) resource.requestPage(page, priority);
                if (request.frames() > 1) active.add(new Prefetch(request.asset(), request.frames() - 1));
            }
            prefetch.clear();
            prefetch.addAll(active);
        }
    }

    void clear() {
        explicit.clear();
        waiting.clear();
        prefetch.clear();
    }
}
