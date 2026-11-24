package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NanitePageTranscoder;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NanitePage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** A complete page installation prepared from one requested residency set. */
final class NaniteResidentPages {
    private record Packed(NanitePage source, ByteBuffer unmodified) { }
    private record Decode(int page, int nextDependency) { }
    record Upload(int sourcePage, int slot, ByteBuffer data) { }

    static final class PageLoadFailure extends IOException {
        private final int page;

        PageLoadFailure(int page, Throwable cause) {
            super("Could not load Nanite page " + page, cause);
            this.page = page;
        }

        int page() { return page; }
    }

    static final class Decoded {
        private final NaniteMeshAsset asset;
        private final Map<Integer, Packed> pages;

        Decoded(NaniteMeshAsset asset, Map<Integer, Packed> pages) {
            this.asset = asset;
            this.pages = Map.copyOf(pages);
        }

        java.util.Set<Integer> sourcePages() { return pages.keySet(); }

        NaniteResidentPages install(Map<Integer, Integer> slots) {
            if (!slots.keySet().equals(pages.keySet()))
                throw new IllegalArgumentException("Nanite residency and physical page slots disagree");
            var committed = new HashMap<Integer, NanitePage>();
            pages.forEach((page, packed) -> committed.put(page, packed.source()));
            var hierarchy = NaniteHierarchyState.install(asset, committed, page -> slots.getOrDefault(page, -1));
            return new NaniteResidentPages(asset, pages, slots, hierarchy);
        }
    }

    private final NaniteMeshAsset asset;
    private final Map<Integer, Packed> pages;
    private final Map<Integer, Integer> slots;
    private final NaniteHierarchyState hierarchy;

    private NaniteResidentPages(NaniteMeshAsset asset, Map<Integer, Packed> pages, Map<Integer, Integer> slots,
                                NaniteHierarchyState hierarchy) {
        this.asset = asset;
        this.pages = Map.copyOf(pages);
        this.slots = Map.copyOf(slots);
        this.hierarchy = hierarchy;
    }

    static Decoded prepare(NaniteMeshAsset asset, @Nullable NaniteResidentPages previous,
                           Collection<Integer> requested) throws IOException {
        int streamingSlots = requested.size();
        int maximum = Math.addExact(asset.header().rootPages(), streamingSlots);
        var loader = new Loader(asset, previous == null || previous.asset != asset ? Map.of() : previous.pages, maximum);
        for (int page = 0; page < asset.header().rootPages(); page++) loader.resolve(page);
        for (int page : requested) loader.resolve(page);

        int roots = asset.header().rootPages();
        if (loader.output.size() - roots > streamingSlots)
            throw new IllegalArgumentException("Nanite resident pages and their dependencies exceed the allocated streaming slots");
        return new Decoded(asset, loader.output);
    }

    ByteBuffer hierarchy() { return hierarchy.hierarchy(); }
    ByteBuffer roots() { return asset.rootOffsets(); }
    ByteBuffer assemblyTransforms() { return asset.assemblyTransforms(); }
    Map<Integer, Integer> slots() { return slots; }

    Decoded rootsOnly() {
        var roots = new HashMap<Integer, Packed>();
        for (int page = 0; page < asset.header().rootPages(); page++) roots.put(page, pages.get(page));
        return new Decoded(asset, roots);
    }

    Collection<Upload> uploads(@Nullable NaniteResidentPages previous) {
        var result = new ArrayList<Upload>(pages.size());
        for (var entry : pages.entrySet()) {
            int page = entry.getKey();
            int slot = slots.get(page);
            var packed = entry.getValue();
            if (previous != null && previous.pages.get(page) == packed && Objects.equals(previous.slots.get(page), slot)
                    && hierarchy.sameStreamingLeafs(previous.hierarchy, page)) continue;
            ByteBuffer data = packed.unmodified();
            ByteBuffer patched = null;
            for (int cluster = 0; cluster < packed.source().clusters(); cluster++) {
                if (!hierarchy.clearStreamingLeaf(page, cluster)) continue;
                if (patched == null) {
                    patched = ByteBuffer.allocate(data.limit()).order(ByteOrder.LITTLE_ENDIAN);
                    patched.put(0, data, 0, data.limit());
                }
                NanitePageTranscoder.clearStreamingLeaf(patched, cluster);
            }
            result.add(new Upload(page, slot, (patched == null ? data : patched).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)));
        }
        return result;
    }

    private static final class Loader {
        final NaniteMeshAsset asset;
        final Map<Integer, Packed> reuse;
        final TreeMap<Integer, Packed> output = new TreeMap<>();
        final int maximum;

        Loader(NaniteMeshAsset asset, Map<Integer, Packed> reuse, int maximum) {
            this.asset = asset;
            this.reuse = reuse;
            this.maximum = maximum;
        }

        void resolve(int requested) throws IOException {
            if (output.containsKey(requested)) return;
            var stack = new ArrayDeque<Decode>();
            var active = new BitSet(asset.pages().size());
            push(stack, active, requested);
            while (!stack.isEmpty()) {
                var item = stack.removeLast();
                var state = asset.pages().get(item.page());
                if (item.nextDependency() < state.dependencyCount()) {
                    stack.addLast(new Decode(item.page(), item.nextDependency() + 1));
                    int dependency = asset.dependency(state, item.nextDependency());
                    if (!output.containsKey(dependency)) push(stack, active, dependency);
                    continue;
                }
                var packed = reuse.get(item.page());
                if (packed == null) {
                    try {
                        var page = asset.readPage(item.page());
                        packed = new Packed(page, NanitePageTranscoder.transcode(asset, item.page(), page,
                                dependency -> output.get(dependency).unmodified()));
                    } catch (IOException | RuntimeException failure) {
                        throw new PageLoadFailure(item.page(), failure);
                    }
                }
                output.put(item.page(), packed);
                active.clear(item.page());
            }
        }

        private void push(ArrayDeque<Decode> stack, BitSet active, int page) throws IOException {
            if (!asset.canLoad(page)) throw new IOException("Nanite page is unavailable: " + page);
            if (active.get(page)) throw new IOException("Nanite page dependencies contain a cycle at " + page);
            if (output.size() + stack.size() >= maximum)
                throw new IOException("Nanite page dependencies exceed the resident page pool");
            active.set(page);
            stack.addLast(new Decode(page, 0));
        }
    }
}
