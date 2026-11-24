package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NanitePage;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NanitePageRange;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Rebuilds the published hierarchy after a committed page set changes. */
final class NaniteHierarchyState {
    private static final int CLUSTER_BITS = 8;
    private static final int CLUSTER_MASK = (1 << CLUSTER_BITS) - 1;
    private static final int NON_LEAF_ERROR_MASK = 0x7fffffff;

    private record PartKey(int page, int part) { }
    private record ParentPart(NanitePage page, NanitePage.Part part, int leafCounter) { }

    private final ByteBuffer hierarchy;
    private final Map<Integer, BitSet> parentClusters;

    private NaniteHierarchyState(ByteBuffer hierarchy, Map<Integer, BitSet> parentClusters) {
        this.hierarchy = hierarchy.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        this.parentClusters = parentClusters;
    }

    static NaniteHierarchyState install(NaniteMeshAsset asset, Map<Integer, NanitePage> committed, IntUnaryOperator slot) {
        for (int page = 0; page < asset.header().rootPages(); page++) requiredPage(committed, page);
        var source = asset.hierarchy();
        var hierarchy = ByteBuffer.allocate(source.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        hierarchy.put(source).flip();
        var parents = new HashMap<PartKey, ParentPart>();
        var parentClusters = new HashMap<Integer, BitSet>();
        for (var page : committed.values()) {
            for (int index = 0; index < page.groupCount(); index++) {
                var group = page.group(index);
                if (!new NanitePageRange(group.pageRange()).allStreamingPages(asset, committed::containsKey)) continue;
                installParts(hierarchy, committed, slot, page, group);
                collectParentUpdates(committed, parents, parentClusters, page, group);
            }
        }
        for (var entry : parents.values()) {
            if (entry.leafCounter() != 0) continue;
            for (int index = 0; index < entry.part().locations(); index++) {
                int offset = entry.page().location(entry.part().firstLocation() + index).lodErrorOffset();
                hierarchy.putInt(offset, hierarchy.getInt(offset) & NON_LEAF_ERROR_MASK);
            }
        }
        return new NaniteHierarchyState(hierarchy, Map.copyOf(parentClusters));
    }

    private static void installParts(ByteBuffer hierarchy, Map<Integer, NanitePage> committed, IntUnaryOperator slot,
                                     NanitePage page, NanitePage.Group group) {
        for (int index = 0; index < group.parts(); index++) {
            var part = page.part(group.firstPart() + index);
            var target = requiredPage(committed, part.page());
            int physicalPage = slot.applyAsInt(part.page());
            if (physicalPage < 0 || physicalPage > NanitePhysicalPagePool.LAST_PAGE || part.firstCluster() >= target.clusters()
                    || (part.firstCluster() & ~CLUSTER_MASK) != 0)
                throw new IllegalArgumentException("Invalid Nanite child page or cluster reference");
            int reference = (physicalPage << CLUSTER_BITS) | part.firstCluster();
            for (int location = 0; location < part.locations(); location++) {
                int offset = page.location(part.firstLocation() + location).childReferenceOffset();
                hierarchy.putInt(offset, reference);
            }
        }
    }

    private static void collectParentUpdates(Map<Integer, NanitePage> committed, Map<PartKey, ParentPart> parents,
                                             Map<Integer, BitSet> parentClusters, NanitePage page, NanitePage.Group group) {
        for (int index = 0; index < group.parents(); index++) {
            var update = page.parent(group.firstParent() + index);
            var partKey = new PartKey(update.partPage(), update.part());
            var old = parents.get(partKey);
            var partPage = old == null ? requiredPage(committed, update.partPage()) : old.page();
            var part = old == null ? partPage.part(update.part()) : old.part();
            int counter = old == null ? part.leafCounter() : old.leafCounter();
            if (counter <= 0) throw new IllegalArgumentException("Invalid Nanite parent part leaf counter");
            parents.put(partKey, new ParentPart(partPage, part, counter - 1));
            var target = requiredPage(committed, update.page());
            var clusters = parentClusters.computeIfAbsent(update.page(), ignored -> new BitSet(target.clusters()));
            for (int entry = 0; entry < update.clusters(); entry++) {
                int cluster = page.clusterIndex(update.firstCluster() + entry);
                if (cluster >= target.clusters()) throw new IllegalArgumentException("Invalid Nanite parent cluster index");
                clusters.set(cluster);
            }
        }
    }

    private static NanitePage requiredPage(Map<Integer, NanitePage> committed, int page) {
        var target = committed.get(page);
        if (target == null) throw new IllegalArgumentException("Missing committed Nanite page " + page);
        return target;
    }

    ByteBuffer hierarchy() { return hierarchy.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
    boolean sameStreamingLeafs(NaniteHierarchyState previous, int page) {
        return Objects.equals(parentClusters.get(page), previous.parentClusters.get(page));
    }
    boolean clearStreamingLeaf(int page, int cluster) {
        var affected = parentClusters.get(page);
        return affected != null && affected.get(cluster);
    }
}
