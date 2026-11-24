package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraWeightedAliasTable;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraExternalRandom;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.AliasTable;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Indices;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Region;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Sampling data for the selected skeletal LOD and ordered authored regions. */
final class NiagaraSkeletalMeshSampling {
    private final @Nullable Lod lod;
    private final List<Region> regions;
    private final int uvSet;
    private final boolean weighted;
    private final @Nullable NiagaraWeightedAliasTable regionSampler;
    private final NiagaraSkeletalMeshWorlds.@Nullable World generated;
    private volatile @Nullable NiagaraMeshUvTree uvTree;
    private volatile @Nullable NiagaraSkeletalConnectivity connectivity;

    NiagaraSkeletalMeshSampling(@Nullable Lod lod, List<Region> regions, int uvSet) {
        this(lod, regions, uvSet, null);
    }

    NiagaraSkeletalMeshSampling(@Nullable Lod lod, List<Region> regions, int uvSet, NiagaraSkeletalMeshWorlds.@Nullable World generated) {
        this.lod = lod;
        this.regions = regions;
        this.uvSet = uvSet;
        this.generated = generated;
        var regionWeights = new float[regions.size()];
        boolean weightedRegions = !regions.isEmpty();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            weightedRegions &= region.areaWeighted() && region.areaSampler().size() != 0;
            regionWeights[i] = region.areaSampler().totalWeight();
        }
        regionSampler = weightedRegions && regions.size() > 1 ? new NiagaraWeightedAliasTable(regionWeights) : null;
        weighted = regions.isEmpty() ? lod != null && lod.supportsUniformlyDistributedSampling() && lod.areaSampler().size() > 0
                : weightedRegions && (regionSampler == null || regionSampler.total() > 0);
    }

    boolean accessible() { return lod != null && lod.cpuAccess(); }
    private StaticMeshGeometry geometry() { return Objects.requireNonNull(Objects.requireNonNull(lod).geometry()); }
    int triangles() { return accessible() && lod != null ? geometry().indexCount() / 3 : 0; }
    int vertices() { return accessible() && lod != null ? geometry().vertexCount() : 0; }

    int count(boolean triangles) {
        if (!accessible() || lod == null) return 0;
        if (regions.isEmpty()) {
            if (!triangles) return vertices();
            if (weighted) return lod.areaSampler().size();
            int count = 0;
            for (var section : lod.sections()) count += section.triangles();
            return count;
        }
        if (triangles && weighted && regions.size() == 1) return regions.getFirst().areaSampler().size();
        int count = 0;
        for (var region : regions) count += elements(region, triangles).size();
        return count;
    }

    int element(int index, boolean triangles) {
        if (!accessible() || lod == null) return -1;
        if (regions.isEmpty()) {
            if (!triangles || weighted) return index;
            for (var section : lod.sections()) {
                if (index >= 0 && index < section.triangles()) return section.firstIndex() + index;
                index -= section.triangles();
            }
            return 0;
        }
        if (regions.size() == 1) {
            var values = elements(regions.getFirst(), triangles);
            return index < 0 || values.size() == 0 ? -1 : decode(values.get(Math.min(index, values.size() - 1)), triangles);
        }
        if (index < 0) return -1;
        for (var region : regions) {
            var values = elements(region, triangles);
            if (index < values.size()) return decode(values.get(index), triangles);
            index -= values.size();
        }
        return 0;
    }

    int randomVertex(NiagaraExternalRandom random) {
        if (!accessible()) return -1;
        if (regions.isEmpty()) return choose(random, vertices());
        var values = regions.get(regions.size() == 1 ? 0 : choose(random, regions.size())).vertices();
        int index = choose(random, values.size());
        return index == -1 ? -1 : values.get(index);
    }

    void randomTriangle(String name, int[] output, NiagaraExternalRandom random) {
        boolean whole = name.equals("RandomTriangle");
        int triangle = -1;
        if (accessible() && lod != null) {
            if (whole) {
                var sampler = lod.areaSampler();
                triangle = lod.supportsUniformlyDistributedSampling() && sampler.size() > 0
                        ? sample(sampler, random) : choose(random, triangles());
            } else if (regions.isEmpty()) {
                if (weighted) triangle = sample(lod.areaSampler(), random);
                else triangle = randomSectionTriangle(random);
            } else {
                int regionIndex = 0;
                if (regions.size() > 1) {
                    regionIndex = weighted && regionSampler != null ? regionSampler.sample(random) : choose(random, regions.size());
                }
                var region = regions.get(regionIndex);
                int index = weighted ? sample(region.areaSampler(), random) : choose(random, region.triangleOffsets().size());
                if (index != -1) triangle = region.triangleOffsets().get(index) / 3;
            }
        }
        output[0] = triangle;
        if (accessible() && (!whole || triangle >= 0)) {
            if (whole) random.barycentric(output, 1);
            else random.streamBarycentric(output, 1);
        } else {
            output[1] = 0;
            output[2] = 0;
            output[3] = 0;
        }
    }

    private int randomSectionTriangle(NiagaraExternalRandom random) {
        if (lod == null || lod.sections().isEmpty()) return -1;
        var section = lod.sections().get(choose(random, lod.sections().size()));
        return section.triangles() > 0 ? section.firstIndex() / 3 + choose(random, section.triangles()) : -1;
    }

    void findUv(boolean point, int[] input, int[] output) {
        var tree = input[0] != 0 && accessible() ? uvTree() : null;
        NiagaraMeshUvTree.Hit hit = null;
        if (tree != null) {
            float u = Float.intBitsToFloat(input[1]), v = Float.intBitsToFloat(input[2]);
            float third = Float.intBitsToFloat(input[3]);
            hit = point ? tree.point(u, v, third) : tree.box(u, v, third, Float.intBitsToFloat(input[4]));
        }
        output[0] = hit == null ? -1 : hit.triangle();
        output[1] = Float.floatToRawIntBits(hit == null ? 0f : (float)hit.a());
        output[2] = Float.floatToRawIntBits(hit == null ? 0f : (float)hit.b());
        output[3] = Float.floatToRawIntBits(hit == null ? 0f : (float)hit.c());
        output[4] = hit == null ? 0 : -1;
    }

    @Nullable NiagaraMeshUvTree uvTree() {
        if (!accessible() || uvSet < 0 || uvSet >= geometry().textureCoordinateCount()) return null;
        var tree = uvTree;
        if (tree != null) return tree;
        synchronized (this) {
            if (uvTree == null) uvTree = generated == null ? new NiagaraMeshUvTree(geometry(), uvSet) : generated.uvTree(geometry(), uvSet);
            return uvTree;
        }
    }

    @Nullable NiagaraSkeletalConnectivity connectivity() {
        if (!accessible()) return null;
        var value = connectivity;
        if (value != null) return value;
        synchronized (this) {
            if (connectivity == null)
                connectivity = generated == null ? new NiagaraSkeletalConnectivity(geometry()) : generated.connectivity(geometry());
            return connectivity;
        }
    }

    private static Indices elements(Region region, boolean triangles) {
        return triangles ? region.triangleOffsets() : region.vertices();
    }
    private static int decode(int value, boolean triangles) { return triangles ? value / 3 : value; }
    private static int choose(NiagaraExternalRandom random, int count) { return count == 0 ? -1 : (int)(random.next() * count); }
    private static int sample(AliasTable sampler, NiagaraExternalRandom random) {
        int index = choose(random, sampler.size());
        if (index < 0) return -1;
        return random.next() < sampler.probability(index) ? index : sampler.alias(index);
    }
}
