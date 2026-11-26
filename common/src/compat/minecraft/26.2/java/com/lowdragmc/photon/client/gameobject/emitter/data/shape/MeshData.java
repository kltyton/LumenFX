/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.kltyton.lumenfx.internal.photon.core.Platform;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lombok.Getter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class MeshData implements IPersistedSerializable {
    @Getter
    @Persisted
    private IModelSource source = new JsonModelSource();
    // runtime: sampling geometry derived from the source's mesh. derivedFrom tracks which
    // PhotonMesh instance the lists were built from — the shared cache hands out a fresh instance
    // after any invalidation (reload listener, reload button, file polling), so an identity
    // compare is all the staleness detection needed.
    @Nullable
    private volatile PhotonMesh derivedFrom = null;
    private final List<Vector3f> vertices = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Triangle> triangles = new ArrayList<>();
    @Getter
    private double edgeSumLength;
    @Getter
    private double triangleSumArea;

    public MeshData() {
    }

    public MeshData(CompoundTag nbt) {
        deserializeNBT(Platform.getFrozenRegistry(), nbt);
    }

    public MeshData(Identifier modelLocation) {
        this(new JsonModelSource(modelLocation));
    }

    public MeshData(IModelSource source) {
        this.source = source;
    }

    public void setSource(IModelSource source) {
        this.source = source;
        clearDerived();
    }

    private synchronized void clearDerived() {
        derivedFrom = null;
        vertices.clear();
        edges.clear();
        triangles.clear();
        edgeSumLength = 0;
        triangleSumArea = 0;
    }

    /**
     * Rebuild on a topology change, refresh the positions on a pose change.
     *
     * <p>{@link Edge} and {@link Triangle} hold references to the {@code Vector3f}s in
     * {@link #vertices}, so writing new coordinates in place moves them all and allocates nothing —
     * otherwise an animated model would rebuild four objects a vertex, a frame.</p>
     *
     * <p>⚠️ Lengths and areas stay the rest pose's: they are the sampling weights, and recomputing them
     * per pose puts the O(n) back. So a stretched limb gets its unstretched share, as in Unity.</p>
     *
     * <p>⚠️ A parallel-sim worker can read a position while another thread writes it, so a particle
     * spawned on the frame a pose changes can land between the two.</p>
     */
    private void ensureLoaded() {
        var mesh = source.getMesh();
        if (mesh == derivedFrom) return;
        synchronized (this) {
            if (mesh == derivedFrom) return; // rebuilt by a parallel-sim worker meanwhile
            var previous = derivedFrom;
            if (previous != null && previous.topology() == mesh.topology()
                    && previous.vertexCount() == mesh.vertexCount()) {
                refreshPositions(mesh);
            } else {
                vertices.clear();
                edges.clear();
                triangles.clear();
                rebuildFrom(mesh);
            }
            derivedFrom = mesh;
        }
    }

    /** Move the existing sampling geometry onto a new pose of the same topology. */
    private void refreshPositions(PhotonMesh mesh) {
        var geometry = mesh.geometry();
        for (int vertex = 0; vertex < vertices.size(); vertex++) {
            int off = PhotonMesh.geometryOffset(vertex);
            vertices.get(vertex).set(geometry[off], geometry[off + 1], geometry[off + 2]);
        }
    }

    /**
     * ⚠️ Two weights moved when the format started welding, both towards Unity's answer: Vertex
     * emission picks among distinct vertices (no valence bias), Edge counts a shared edge once.
     */
    private void rebuildFrom(PhotonMesh mesh) {
        double sumLength = 0;
        double sumArea = 0;
        var geometry = mesh.geometry();
        var indices = mesh.indices();
        // one Vector3f per vertex, shared by every edge and triangle referencing it
        var points = new Vector3f[mesh.vertexCount()];
        for (int v = 0; v < points.length; v++) {
            int off = PhotonMesh.geometryOffset(v);
            points[v] = new Vector3f(geometry[off], geometry[off + 1], geometry[off + 2]);
            this.vertices.add(points[v]);
        }
        var seen = new LongOpenHashSet();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int a = indices[i], b = indices[i + 1], c = indices[i + 2];
            if (a >= points.length || b >= points.length || c >= points.length) continue;
            sumLength += addEdgeOnce(seen, points, a, b);
            sumLength += addEdgeOnce(seen, points, b, c);
            sumLength += addEdgeOnce(seen, points, c, a);
            sumArea += addTriangle(points[a], points[b], points[c]);
        }
        this.edgeSumLength = sumLength;
        this.triangleSumArea = sumArea;
    }

    /** Adds the edge unless an adjoining triangle already contributed it; returns the length added. */
    private double addEdgeOnce(LongOpenHashSet seen, Vector3f[] points, int a, int b) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        if (!seen.add(key)) {
            return 0;
        }
        return addEdge(points[a], points[b]);
    }

    public List<Vector3f> getVertices() {
        ensureLoaded();
        return vertices;
    }

    public List<Edge> getEdges() {
        ensureLoaded();
        return edges;
    }

    public List<Triangle> getTriangles() {
        ensureLoaded();
        return triangles;
    }

    @Nullable
    public Vector3f getRandomVertex(float t) {
        ensureLoaded();
        if (vertices.isEmpty()) return null;
        return vertices.get((int) (vertices.size() * t));
    }

    @Nullable
    public Edge getRandomEdge(float t) {
        ensureLoaded();
        if (edges.isEmpty()) return null;
        var l = t * edgeSumLength;
        var cl = 0d;
        for (Edge edge : edges) {
            if (l <= edge.length + cl) {
                return edge;
            }
            cl += edge.length;
        }
        return edges.getLast();
    }

    @Nullable
    public Triangle getRandomTriangle(float t) {
        ensureLoaded();
        if (triangles.isEmpty()) return null;
        var a = t * triangleSumArea;
        var ca = 0d;
        for (var triangle : triangles) {
            if (a <= triangle.area + ca) {
                return triangle;
            }
            ca += triangle.area;
        }
        return triangles.getLast();
    }

    private double addEdge(Vector3f a, Vector3f b) {
        var ab = new Edge(a, b);
        if (ab.length > 0) {
            edges.add(ab);
        }
        return ab.length;
    }

    private double addTriangle(Vector3f a, Vector3f b, Vector3f c) {
        var abc = new Triangle(a, b, c);
        if (abc.area > 0) {
            triangles.add(abc);
        }
        return abc.area;
    }

    @Override
    public void deserialize(@NotNull ValueInput input) {
        IPersistedSerializable.super.deserialize(input);
        if (input.child("source").isEmpty()) {
            // legacy (pre-v5) payloads store a bare json model id; editor resource files and pasted
            // NBT bypass the project datafixer, so keep these in-place fallbacks
            var modelLocation = input.getString("modelLocation");
            if (modelLocation.isPresent()) {
                source = new JsonModelSource(Identifier.parse(modelLocation.get()));
            } else if (input.getString("type").isPresent()) {
                // a bare IModelSource wrapper {type, data} (renderer payloads before MeshData wrapping)
                source = IModelSource.deserializeWrapper(toCompound(input));
            }
        }
        clearDerived();
    }

    private static CompoundTag toCompound(ValueInput input) {
        var tag = new CompoundTag();
        for (var key : input.keySet()) {
            input.read(key, ExtraCodecs.NBT).ifPresent(value -> tag.put(key, value));
        }
        return tag;
    }

    /** Tag-level bridge kept for editor resources and copy/paste payloads (see {@code MeshResource}). */
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, provider);
            serialize(output);
            return output.buildResult();
        }
    }

    /** Tag-level bridge kept for editor resources and copy/paste payloads (see {@code MeshResource}). */
    public void deserializeNBT(HolderLookup.@NotNull Provider provider, @NotNull CompoundTag nbt) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            deserialize(TagValueInput.create(reporter, provider, nbt));
        }
    }

    public static class Edge {

        public final Vector3f a, b;

        public final double length;

        public Edge(Vector3f a, Vector3f b) {
            this.a = a;
            this.b = b;
            length = new Vector3f(a).sub(b).length();
        }
    }

    public static class Triangle {

        public final Vector3f a, b, c;

        public final double area;

        public Triangle(Vector3f a, Vector3f b, Vector3f c) {
            this.a = a;
            this.b = b;
            this.c = c;
            var nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y);
            var ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
            var nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            area = 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MeshData meshData = (MeshData) o;
        return source.equals(meshData.source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }
}
