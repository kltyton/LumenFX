package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraWeightedAliasTable;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.CpuBuffers;
import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraExternalRandom;

import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraStaticMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/** Source LOD channels, material section remaps and socket remaps shared by mesh readers. */
final class NiagaraStaticMeshSampling {
    enum Selection { ALL, FILTERED, UNFILTERED }
    record TangentBasis(Vec3 normal, Vec3 bitangent, Vec3 tangent) { }
    record SocketTransform(Vec3 position, Quaternionfc rotation, Vec3 scale) { }
    static final TangentBasis DEFAULT_BASIS = new TangentBasis(new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(1, 0, 0));
    private record Sections(List<Integer> indices, int triangles) { }

    private final @Nullable StaticMeshAsset asset;
    private final @Nullable StaticMeshGeometry geometry;
    private final boolean hasVertexColors;
    private final Sections filtered, unfiltered;
    private final List<StaticMeshAsset.Socket> sockets;
    private final List<SocketTransform> socketTransforms;
    private final List<Integer> socketRemap;
    private final int filteredSocketCount;
    private final @Nullable NiagaraWeightedAliasTable allWeights, filteredWeights, unfilteredWeights;
    private final NiagaraWeightedAliasTable[] trianglesBySection;

    NiagaraStaticMeshSampling(@Nullable StaticMeshAsset asset, int lod, @Nullable StaticMeshGeometry resident,
                              NiagaraStaticMeshDefinition definition) {
        this.asset = asset;
        var selected = asset == null || lod < 0 || lod >= asset.lods().size() ? null : asset.lods().get(lod);
        geometry = selected == null || !selected.hasRenderBuffers() ? null : resident;
        hasVertexColors = selected != null && selected.hasVertexColors();
        var yes = new ArrayList<Integer>();
        var no = new ArrayList<Integer>();
        int yesTriangles = 0, noTriangles = 0;
        if (geometry != null) for (int index = 0; index < geometry.sections().size(); index++) {
            var section = geometry.sections().get(index);
            if (section.indexCount() == 0) continue;
            int slot = materialIndex(asset, section);
            if (definition.allowedSlots().isEmpty() || definition.allowedSlots().contains(slot)) {
                yes.add(index);
                yesTriangles += section.indexCount() / 3;
            } else {
                no.add(index);
                noTriangles += section.indexCount() / 3;
            }
        }
        filtered = new Sections(List.copyOf(yes), yesTriangles);
        unfiltered = new Sections(List.copyOf(no), noTriangles);
        if (geometry != null && asset.sampling().areaWeighted()) {
            int sectionCount = geometry.sections().size();
            trianglesBySection = new NiagaraWeightedAliasTable[sectionCount];
            var sectionWeights = new float[sectionCount];
            for (int index = 0; index < sectionCount; index++) {
                var range = geometry.sections().get(index);
                var weights = new float[range.indexCount() / 3];
                for (int triangle = 0; triangle < weights.length; triangle++)
                    weights[triangle] = area(geometry, range.firstIndex() + triangle * 3);
                trianglesBySection[index] = new NiagaraWeightedAliasTable(weights);
                sectionWeights[index] = trianglesBySection[index].total();
            }
            allWeights = new NiagaraWeightedAliasTable(sectionWeights);
            filteredWeights = new NiagaraWeightedAliasTable(selectWeights(sectionWeights, yes));
            unfilteredWeights = new NiagaraWeightedAliasTable(selectWeights(sectionWeights, no));
        } else {
            trianglesBySection = new NiagaraWeightedAliasTable[0];
            allWeights = filteredWeights = unfilteredWeights = null;
        }
        sockets = asset == null ? List.of() : asset.sampling().sockets();
        if (sockets.size() > 0xffff) throw new IllegalArgumentException("Niagara static mesh socket count exceeds the source uint16 layout");
        var transforms = new ArrayList<SocketTransform>(sockets.size());
        double[] quaternion = new double[4];
        for (var socket : sockets) {
            var angles = socket.pitchYawRoll();
            NativeSceneMath.rotatorQuaternion((float)angles.x, (float)angles.y, (float)angles.z, quaternion);
            transforms.add(new SocketTransform(socket.location(),
                    new Quaternionf((float)quaternion[0], (float)quaternion[1], (float)quaternion[2], (float)quaternion[3]), socket.scale()));
        }
        socketTransforms = List.copyOf(transforms);
        var remap = new ArrayList<Integer>();
        for (String name : definition.filteredSockets()) {
            for (int index = 0; index < sockets.size(); index++) if (SourceNames.equal(name, sockets.get(index).name())) {
                remap.add(index);
                break;
            }
        }
        filteredSocketCount = remap.size();
        if (!definition.filteredSockets().isEmpty()) {
            for (int index = 0; index < sockets.size(); index++) if (!remap.contains(index)) remap.add(index);
        }
        socketRemap = List.copyOf(remap);
    }

    boolean valid() { return geometry != null; }
    boolean hasVertexColors() { return hasVertexColors; }
    int vertexCount(CpuBuffers buffers) { return buffers.positions() && geometry != null ? geometry.vertexCount() : 0; }
    int uvVertexCount(CpuBuffers buffers) {
        return buffers.textureCoordinates() && geometry != null && geometry.textureCoordinateCount() != 0 ? geometry.vertexCount() : 0;
    }
    int colorVertexCount(CpuBuffers buffers) {
        return buffers.colors() && geometry != null && hasVertexColors ? geometry.vertexCount() : 0;
    }
    boolean hasTangents(CpuBuffers buffers) { return buffers.tangents() && geometry != null && geometry.vertexCount() != 0; }
    boolean vertexValid(int vertex, CpuBuffers buffers) { return inRange(vertex, vertexCount(buffers)); }
    int triangleCount(Selection selection, CpuBuffers buffers) {
        return selection == Selection.ALL && !buffers.indices() ? 0 : triangleCount(selection);
    }
    int triangleCount(Selection selection) {
        return switch (selection) {
            case ALL -> geometry == null ? 0 : geometry.indexCount() / 3;
            case FILTERED -> filtered.triangles();
            case UNFILTERED -> unfiltered.triangles();
        };
    }
    boolean triangleValid(int triangle, Selection selection, CpuBuffers buffers) {
        return inRange(triangle, triangleCount(selection, buffers));
    }
    int triangleVertex(int triangle, int vertex, CpuBuffers buffers) {
        int count = triangleCount(Selection.ALL, buffers);
        return count == 0 ? 0 : geometry.index(Math.clamp(triangle, 0, count - 1) * 3 + vertex);
    }
    int triangleAt(int index, Selection selection) {
        int count = triangleCount(selection);
        if (count < 2) return 0;
        if (selection == Selection.ALL) return Math.clamp(index, 0, count - 1);
        int remaining = Math.clamp(index, 0, count - 1);
        for (int section : sections(selection).indices()) {
            var range = geometry.sections().get(section);
            int triangles = range.indexCount() / 3;
            if (remaining < triangles) return range.firstIndex() / 3 + remaining;
            remaining -= triangles;
        }
        throw new IllegalStateException("Niagara static mesh section remap does not cover its triangles");
    }

    int sectionCount(Selection selection) {
        return selection == Selection.ALL ? geometry == null ? 0 : geometry.sections().size() : sections(selection).indices().size();
    }
    boolean sectionValid(int index) { return inRange(index, sectionCount(Selection.ALL)); }
    int sectionAt(int index, Selection selection) {
        int count = sectionCount(selection);
        if (count == 0) return 0;
        int clamped = Math.clamp(index, 0, count - 1);
        return selection == Selection.ALL ? clamped : sections(selection).indices().get(clamped);
    }
    int sectionTriangles(int index) { return sectionValid(index) ? geometry.sections().get(index).indexCount() / 3 : 0; }
    int sectionTriangleAt(int section, int triangle) {
        if (!sectionValid(section)) return 0;
        var range = geometry.sections().get(section);
        int maximum = range.indexCount() / 3 - 1;
        int local = triangle < 0 ? 0 : Math.min(triangle, maximum);
        return range.firstIndex() / 3 + local;
    }

    int socketCount(Selection selection) {
        return switch (selection) {
            case ALL -> sockets.size();
            case FILTERED -> filteredSocketCount;
            case UNFILTERED -> sockets.size() - filteredSocketCount;
        };
    }
    int socketAt(int index, Selection selection) {
        int count = switch (selection) {
            case ALL -> sockets.size();
            case FILTERED -> filteredSocketCount;
            case UNFILTERED -> socketRemap.size() - filteredSocketCount;
        };
        if (count == 0) return -1;
        int clamped = Math.clamp(index, 0, count - 1);
        if (selection == Selection.ALL) return clamped;
        return socketRemap.get(selection == Selection.UNFILTERED ? filteredSocketCount + clamped : clamped);
    }
    @Nullable SocketTransform socket(int index, Selection selection) {
        int selected = socketAt(index, selection);
        return selected < 0 ? null : socketTransforms.get(selected);
    }

    Vec3 position(int vertex, CpuBuffers buffers) {
        int count = vertexCount(buffers);
        if (count == 0) return Vec3.ZERO;
        int index = Math.clamp(vertex, 0, count - 1);
        return new Vec3(geometry.position(index, 0), geometry.position(index, 1), geometry.position(index, 2));
    }
    TangentBasis tangentBasis(int vertex, CpuBuffers buffers) {
        var basis = rawTangentBasis(vertex, buffers);
        return basis.bitangent().lengthSqr() <= .001f ? DEFAULT_BASIS : basis;
    }
    TangentBasis rawTangentBasis(int vertex, CpuBuffers buffers) {
        if (!buffers.tangents() || geometry == null) return DEFAULT_BASIS;
        int index = Math.clamp(vertex, 0, geometry.vertexCount() - 1);
        var normal = new Vec3(geometry.normal(index, 0), geometry.normal(index, 1), geometry.normal(index, 2));
        var tangent = new Vec3(geometry.tangent(index, 0), geometry.tangent(index, 1), geometry.tangent(index, 2));
        return new TangentBasis(normal, normal.cross(tangent).scale(geometry.binormalSign(index)), tangent);
    }
    float color(int vertex, int component, CpuBuffers buffers) {
        int count = colorVertexCount(buffers);
        if (count == 0) return 1;
        float packed = geometry.color(Math.clamp(vertex, 0, count - 1), component);
        return component < 3 ? SourceColorMath.linear(SourceColorMath.unorm8(packed)) : packed;
    }
    float uv(int vertex, int channel, int component, CpuBuffers buffers) {
        int count = uvVertexCount(buffers);
        if (count == 0) return 0;
        return geometry.textureCoordinate(Math.clamp(channel, 0, geometry.textureCoordinateCount() - 1),
                Math.clamp(vertex, 0, count - 1), component);
    }
    @Nullable Bounds bounds() { return asset == null ? null : asset.extendedBounds(); }
    @Nullable StaticMeshGeometry geometry() { return geometry; }
    boolean gpuAreaWeighted() { return geometry != null && allWeights != null && asset.sampling().gpuAreaWeighted(); }
    List<Integer> sectionRemap(Selection selection) { return sections(selection).indices(); }
    List<Integer> socketRemap() { return filteredSocketCount == 0 ? List.of() : socketRemap; }
    List<SocketTransform> socketTransforms() { return socketTransforms; }
    NiagaraWeightedAliasTable triangleWeights(int section) { return trianglesBySection[section]; }

    int randomVertex(NiagaraExternalRandom random, CpuBuffers buffers) {
        return (int)(random.next() * Math.max(vertexCount(buffers), 1));
    }

    int randomSection(NiagaraExternalRandom random, Selection selection) {
        int count = sectionCount(selection);
        var weights = weights(selection);
        if (count == 0) {
            if (selection == Selection.ALL) {
                if (weights != null) {
                    // The source weighted sampler consumes two draws and returns INDEX_NONE for an empty table.
                    random.next();
                    random.next();
                    return -1;
                }
                random.next();
            }
            return 0;
        }
        int slot = weights == null ? (int)(random.next() * count)
                : selection == Selection.ALL ? sampleAreaWeighted(weights, random) : weights.sample(random);
        return sectionAt(slot, selection);
    }

    int randomTriangle(NiagaraExternalRandom random, Selection selection, CpuBuffers buffers) {
        var weights = weights(selection);
        if (selection == Selection.ALL) {
            if (weights == null) return (int)(random.next() * Math.max(triangleCount(Selection.ALL, buffers), 1));
            int section = weights.size() == 0 ? 0 : sampleAreaWeighted(weights, random);
            return weightedTriangle(random, section);
        }
        int count = sectionCount(selection);
        if (count == 0) return 0;
        int section = sectionAt(weights == null ? (int)(random.next() * count) : weights.sample(random), selection);
        return randomTriangleInSection(random, section);
    }

    int randomTriangleInSection(NiagaraExternalRandom random, int index) {
        int count = sectionCount(Selection.ALL);
        if (count == 0) return 0;
        int section = Math.clamp(index, 0, count - 1);
        if (allWeights != null) return weightedTriangle(random, section);
        var range = geometry.sections().get(section);
        return range.firstIndex() / 3 + (int)(random.next() * (range.indexCount() / 3));
    }

    private int weightedTriangle(NiagaraExternalRandom random, int section) {
        if (!inRange(section, trianglesBySection.length) || trianglesBySection[section].size() == 0) return 0;
        return geometry.sections().get(section).firstIndex() / 3 + sampleAreaWeighted(trianglesBySection[section], random);
    }

    private static int sampleAreaWeighted(NiagaraWeightedAliasTable weights, NiagaraExternalRandom random) {
        float threshold = random.next();
        float slot = random.next();
        return weights.sample(slot, threshold);
    }

    int randomSocket(NiagaraExternalRandom random, Selection selection) {
        int count = switch (selection) {
            case ALL -> sockets.size();
            case FILTERED -> filteredSocketCount;
            case UNFILTERED -> socketRemap.size() - filteredSocketCount;
        };
        return count == 0 ? -1 : socketAt((int)(random.next() * count), selection);
    }

    @Nullable NiagaraWeightedAliasTable weights(Selection selection) {
        return switch (selection) {
            case ALL -> allWeights;
            case FILTERED -> filteredWeights;
            case UNFILTERED -> unfilteredWeights;
        };
    }

    private static float[] selectWeights(float[] weights, List<Integer> sections) {
        var result = new float[sections.size()];
        for (int i = 0; i < result.length; i++) result[i] = weights[sections.get(i)];
        return result;
    }

    private static float area(StaticMeshGeometry geometry, int offset) {
        int a = geometry.index(offset), b = geometry.index(offset + 1), c = geometry.index(offset + 2);
        float ax = geometry.position(b, 0) - geometry.position(a, 0);
        float ay = geometry.position(b, 1) - geometry.position(a, 1);
        float az = geometry.position(b, 2) - geometry.position(a, 2);
        float bx = geometry.position(c, 0) - geometry.position(a, 0);
        float by = geometry.position(c, 1) - geometry.position(a, 1);
        float bz = geometry.position(c, 2) - geometry.position(a, 2);
        float x = ay * bz - az * by, y = az * bx - ax * bz, z = ax * by - ay * bx;
        return .5f * (float)Math.sqrt(x * x + y * y + z * z);
    }

    private Sections sections(Selection selection) {
        return switch (selection) {
            case FILTERED -> filtered;
            case UNFILTERED -> unfiltered;
            case ALL -> throw new IllegalArgumentException("Unfiltered source order has no section remap");
        };
    }
    private static boolean inRange(int index, int count) { return index >= 0 && index < count; }
    private static int materialIndex(StaticMeshAsset asset, StaticMeshGeometry.Section section) {
        if (section.info() != null) return section.info().materialIndex();
        for (int i = 0; i < asset.materials().size(); i++)
            if (SourceNames.equal(asset.materials().get(i).name(), section.materialSlot())) return i;
        return -1;
    }
}
