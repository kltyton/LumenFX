package com.kltyton.lumenfx.effect.render.mesh;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Reference skeleton, native render LODs and authored sampling data for a skinned mesh. */
public record SkeletalMeshAsset(Bounds bounds, List<Bone> bones, List<Socket> sockets, List<Lod> lods,
                                List<Region> regions, int inlineLods, @Nullable Streaming streaming) {
    public SkeletalMeshAsset(Bounds bounds, List<Bone> bones, List<Socket> sockets, List<Lod> lods,
                             List<Region> regions, int inlineLods) {
        this(bounds, bones, sockets, lods, regions, inlineLods, null);
    }

    public SkeletalMeshAsset {
        Objects.requireNonNull(bounds);
        bones = List.copyOf(bones);
        sockets = List.copyOf(sockets);
        var indexedLods = new java.util.ArrayList<Lod>(lods.size());
        for (int index = 0; index < lods.size(); index++) indexedLods.add(lods.get(index).withIndex(index));
        lods = List.copyOf(indexedLods);
        regions = List.copyOf(regions);
        if (bones.isEmpty() || lods.isEmpty() || inlineLods < 0 || inlineLods > lods.size())
            throw new IllegalArgumentException("Invalid native skeletal mesh asset");
        for (int index = 0; index < bones.size(); index++) {
            int parent = bones.get(index).parent();
            if (parent < -1 || parent >= index) throw new IllegalArgumentException("Skeletal parent index is not in reference order");
        }
        for (var lod : lods) for (var section : lod.sections()) for (int i = 0; i < section.boneMap().size(); i++) {
            int bone = section.boneMap().get(i);
            if (bone < 0 || bone >= bones.size()) throw new IllegalArgumentException("Skeletal section references an unknown bone");
        }
    }

    public int bone(String name) {
        for (int index = 0; index < bones.size(); index++) if (SourceNames.equal(bones.get(index).name(), name)) return index;
        return -1;
    }

    public record ResidentLods(int firstLod, Map<Integer, Lod> lods, boolean allLods) {
        public ResidentLods(int firstLod, Map<Integer, Lod> lods) { this(firstLod, lods, false); }
        public ResidentLods { lods = Map.copyOf(lods); }
        public boolean contains(int lod) { return lod >= firstLod && (allLods || lods.containsKey(lod)); }
        public @Nullable Lod get(SkeletalMeshAsset asset, int lod) { return allLods ? asset.lods().get(lod) : lods.get(lod); }
    }

    private static final ResidentLods EAGER_RESIDENCY = new ResidentLods(0, Map.of(), true);

    public interface Streaming {
        ResidentLods resident();
        void request(int firstLod);
    }

    public ResidentLods resident() {
        if (streaming != null) return streaming.resident();
        return EAGER_RESIDENCY;
    }

    public record LocalTransform(Vec3 location, float qx, float qy, float qz, float qw, Vec3 scale) {
        public LocalTransform {
            if (!finite(location) || !finite(scale) || !Float.isFinite(qx) || !Float.isFinite(qy)
                    || !Float.isFinite(qz) || !Float.isFinite(qw))
                throw new IllegalArgumentException("Non-finite skeletal reference transform");
        }
    }
    public record Bone(String name, int parent, LocalTransform reference, LocalTransform componentReference,
                       float @Nullable [] inverseBind) {
        public Bone {
            if (inverseBind != null) {
                if (inverseBind.length != 16) throw new IllegalArgumentException("Invalid skeletal inverse bind matrix");
                inverseBind = inverseBind.clone();
                for (float value : inverseBind) if (!Float.isFinite(value))
                    throw new IllegalArgumentException("Non-finite skeletal inverse bind matrix");
            }
        }
        @Override public float @Nullable [] inverseBind() { return inverseBind == null ? null : inverseBind.clone(); }
    }
    public record Socket(String name, String bone, LocalTransform relative) { }

    public static final class Indices {
        private final int[] values;
        public Indices(int[] values) { this.values = values.clone(); }
        public int size() { return values.length; }
        public int get(int index) { return values[index]; }
    }

    public record Section(int material, int firstIndex, int triangles, int firstVertex, int vertices, Indices boneMap) {
        public Section {
            if (firstIndex < 0 || triangles < 0 || firstVertex < 0 || vertices < 0)
                throw new IllegalArgumentException("Invalid skeletal section range");
        }
    }
    public record Region(String name, int lod, boolean areaWeighted, Indices triangleOffsets, Indices vertices,
                         Indices bones, AliasTable areaSampler) { }

    public static final class AliasTable {
        private final float[] probability;
        private final int[] alias;
        private final float totalWeight;
        public AliasTable(float[] probability, int[] alias, float totalWeight) {
            if (probability.length != alias.length || !Float.isFinite(totalWeight) || totalWeight < 0)
                throw new IllegalArgumentException("Invalid skeletal area sampler");
            this.probability = probability.clone();
            this.alias = alias.clone();
            this.totalWeight = totalWeight;
            for (int i = 0; i < alias.length; i++)
                if (!(probability[i] >= 0 && probability[i] <= 1) || alias[i] < 0 || alias[i] >= alias.length)
                    throw new IllegalArgumentException("Invalid skeletal area alias entry");
        }
        public int size() { return alias.length; }
        public float probability(int index) { return probability[index]; }
        public int alias(int index) { return alias[index]; }
        public float totalWeight() { return totalWeight; }
    }

    public static final class SkinWeights {
        private final int[] starts, bones;
        private final char[] weights;
        public SkinWeights(int[] starts, int[] bones, char[] weights) {
            if (starts.length == 0 || starts[0] != 0 || starts[starts.length - 1] != bones.length || bones.length != weights.length)
                throw new IllegalArgumentException("Invalid skeletal influence offsets");
            this.starts = starts.clone(); this.bones = bones.clone(); this.weights = weights.clone();
            for (int i = 1; i < starts.length; i++) if (starts[i] < starts[i - 1] || starts[i] > bones.length)
                throw new IllegalArgumentException("Skeletal influence offsets are out of order");
            for (int bone : bones) if (bone < 0) throw new IllegalArgumentException("Negative skeletal influence index");
        }
        public int vertexCount() { return starts.length - 1; }
        public int count(int vertex) { return starts[vertex + 1] - starts[vertex]; }
        public int influenceCount() { return bones.length; }
        public int localBone(int vertex, int influence) { return bones[starts[vertex] + Objects.checkIndex(influence, count(vertex))]; }
        public int weight(int vertex, int influence) { return weights[starts[vertex] + Objects.checkIndex(influence, count(vertex))]; }
    }

    /** An LOD's active native influence stream, including the SRV layout used for GPU skinning. */
    public record SkinWeightBuffer(SkinWeights weights, int indexBytes, int weightBytes, boolean unlimited) {
        public SkinWeightBuffer {
            Objects.requireNonNull(weights);
            if (indexBytes < 1 || indexBytes > 2 || weightBytes < 1 || weightBytes > 2)
                throw new IllegalArgumentException("Invalid skeletal skin weight element width");
            if (!unlimited && weights.vertexCount() != 0) {
                int count = weights.count(0);
                if ((count * indexBytes) % Integer.BYTES != 0 || (count * weightBytes) % Integer.BYTES != 0)
                    throw new IllegalArgumentException("Fixed skeletal skin weights must have word-aligned index and weight channels");
                for (int vertex = 1; vertex < weights.vertexCount(); vertex++)
                    if (weights.count(vertex) != count)
                        throw new IllegalArgumentException("Fixed skeletal skin weights must have one stride");
            }
        }
    }

    public static final class Lod {
        private final int index, vertexCount;
        private final @Nullable StaticMeshGeometry geometry;
        private final List<Section> sections;
        private final @Nullable SkinWeightBuffer skinWeightBuffer;
        private final boolean cpuAccess, hasColors, cpuColors, supportsUniformlyDistributedSampling;
        private final AliasTable areaSampler;
        private final int[] vertexSections;

        public Lod(StaticMeshGeometry geometry, List<Section> sections, SkinWeights weights, boolean cpuAccess,
                   int indexBytes, int weightBytes, AliasTable areaSampler) {
            this(geometry, sections, weights, cpuAccess, true, true, indexBytes, weightBytes, areaSampler);
        }

        public Lod(StaticMeshGeometry geometry, List<Section> sections, SkinWeights weights, boolean cpuAccess,
                   boolean hasColors, boolean cpuColors, int indexBytes, int weightBytes, AliasTable areaSampler) {
            this(geometry, sections, new SkinWeightBuffer(weights, indexBytes, weightBytes, true),
                    cpuAccess, hasColors, cpuColors, areaSampler, areaSampler.size() > 0);
        }

        public Lod(StaticMeshGeometry geometry, List<Section> sections, SkinWeights weights, boolean cpuAccess,
                   boolean hasColors, boolean cpuColors, int indexBytes, int weightBytes, AliasTable areaSampler,
                   boolean supportsUniformlyDistributedSampling) {
            this(geometry, sections, new SkinWeightBuffer(weights, indexBytes, weightBytes, true),
                    cpuAccess, hasColors, cpuColors, areaSampler, supportsUniformlyDistributedSampling);
        }

        public Lod(StaticMeshGeometry geometry, List<Section> sections, SkinWeightBuffer skinWeightBuffer, boolean cpuAccess,
                   boolean hasColors, boolean cpuColors, AliasTable areaSampler) {
            this(-1, geometry.vertexCount(), geometry, sections, skinWeightBuffer, cpuAccess, hasColors, cpuColors,
                    areaSampler, areaSampler.size() > 0);
        }

        public Lod(StaticMeshGeometry geometry, List<Section> sections, SkinWeightBuffer skinWeightBuffer, boolean cpuAccess,
                   boolean hasColors, boolean cpuColors, AliasTable areaSampler, boolean supportsUniformlyDistributedSampling) {
            this(-1, geometry.vertexCount(), geometry, sections, skinWeightBuffer, cpuAccess, hasColors, cpuColors,
                    areaSampler, supportsUniformlyDistributedSampling);
        }

        private Lod(int index, int vertexCount, @Nullable StaticMeshGeometry geometry, List<Section> sections,
                    @Nullable SkinWeightBuffer skinWeightBuffer, boolean cpuAccess, boolean hasColors, boolean cpuColors,
                    AliasTable areaSampler, boolean supportsUniformlyDistributedSampling) {
            if (cpuColors && !hasColors)
                throw new IllegalArgumentException("Invalid skeletal render LOD layout");
            if (vertexCount < 0 || geometry != null && geometry.vertexCount() != vertexCount
                    || geometry == null && (skinWeightBuffer != null || !sections.isEmpty()))
                throw new IllegalArgumentException("Invalid skeletal LOD residency descriptor");
            this.index = index; this.vertexCount = vertexCount;
            this.geometry = geometry; this.sections = List.copyOf(sections); this.skinWeightBuffer = skinWeightBuffer;
            this.cpuAccess = cpuAccess; this.areaSampler = areaSampler;
            this.supportsUniformlyDistributedSampling = supportsUniformlyDistributedSampling;
            this.hasColors = hasColors; this.cpuColors = cpuColors;
            vertexSections = new int[vertexCount];
            Arrays.fill(vertexSections, -1);
            for (int sectionIndex = 0; sectionIndex < this.sections.size(); sectionIndex++) {
                var section = this.sections.get(sectionIndex);
                int end = Math.addExact(section.firstVertex(), section.vertices());
                if (geometry == null || end > geometry.vertexCount() || section.firstIndex() + section.triangles() * 3L > geometry.indexCount())
                    throw new IllegalArgumentException("Skeletal render section exceeds its LOD");
                for (int vertex = section.firstVertex(); vertex < end; vertex++)
                    if (vertexSections[vertex] == -1) vertexSections[vertex] = sectionIndex;
            }
            if (skinWeightBuffer != null) validateWeights(skinWeightBuffer);
        }

        public static Lod unloaded(int vertexCount, boolean cpuAccess, boolean hasColors, boolean cpuColors) {
            return unloaded(vertexCount, cpuAccess, hasColors, cpuColors, false);
        }

        public static Lod unloaded(int vertexCount, boolean cpuAccess, boolean hasColors, boolean cpuColors,
                                   boolean supportsUniformlyDistributedSampling) {
            return new Lod(-1, vertexCount, null, List.of(), null, cpuAccess, hasColors, cpuColors,
                    new AliasTable(new float[0], new int[0], 0), supportsUniformlyDistributedSampling);
        }

        public Lod withIndex(int value) {
            if (index == value) return this;
            return new Lod(value, vertexCount, geometry, sections, skinWeightBuffer, cpuAccess, hasColors, cpuColors,
                    areaSampler, supportsUniformlyDistributedSampling);
        }

        public int index() { return index; }
        public int vertexCount() { return vertexCount; }
        public boolean resident() { return geometry != null && skinWeightBuffer != null; }

        public void validateWeights(SkinWeightBuffer buffer) {
            var weights = buffer.weights();
            if (vertexCount != weights.vertexCount())
                throw new IllegalArgumentException("Skeletal influence stream does not match its LOD vertex count");
            int indexLimit = 1 << (buffer.indexBytes() * Byte.SIZE);
            for (int vertex = 0; vertex < vertexSections.length; vertex++) {
                int section = vertexSections[vertex];
                for (int influence = 0; influence < weights.count(vertex); influence++) {
                    if (weights.weight(vertex, influence) == 0) continue;
                    int localBone = weights.localBone(vertex, influence);
                    if (section < 0 || localBone >= indexLimit || localBone >= sections.get(section).boneMap().size())
                        throw new IllegalArgumentException("Skeletal influence exceeds its section's bone map");
                }
            }
        }

        public @Nullable StaticMeshGeometry geometry() { return geometry; }
        public List<Section> sections() { return sections; }
        public SkinWeights weights() { return Objects.requireNonNull(skinWeightBuffer).weights(); }
        public @Nullable SkinWeightBuffer skinWeightBuffer() { return skinWeightBuffer; }
        public boolean cpuAccess() { return cpuAccess; }
        public boolean hasColors() { return hasColors; }
        public boolean cpuColors() { return cpuColors; }
        public boolean supportsUniformlyDistributedSampling() { return supportsUniformlyDistributedSampling; }
        public int indexBytes() { return Objects.requireNonNull(skinWeightBuffer).indexBytes(); }
        public int weightBytes() { return Objects.requireNonNull(skinWeightBuffer).weightBytes(); }
        public boolean unlimitedInfluences() { return Objects.requireNonNull(skinWeightBuffer).unlimited(); }
        public AliasTable areaSampler() { return areaSampler; }
        public int bone(int vertex, int influence) {
            return bone(weights(), vertex, influence);
        }
        public int bone(SkinWeights weights, int vertex, int influence) {
            if (weights.weight(vertex, influence) == 0) return -1;
            return sections.get(vertexSections[vertex]).boneMap().get(weights.localBone(vertex, influence));
        }
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
