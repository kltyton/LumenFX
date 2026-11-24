package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Mesh.Type;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.ShapeArcMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.ShapeDistribution;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Immutable selection table for an authored mesh used as a Unity particle birth surface. */
public final class UnityMeshEmission {
    public record Sample(Vec3 position, Vec3 direction, @Nullable Color color) { }

    private record Primitive(int a, int b, int c, Vec3 fallbackNormal, @Nullable Color tint, double limit) { }

    private final StaticMeshGeometry geometry;
    private final Type placement;
    private final ShapeArcMode vertexMode;
    private final int materialSection;
    private final double normalOffset;
    private final boolean useVertexColor;
    private final @Nullable UnityShapeTexture texture;
    private final UnityShapeSettings.@Nullable Traversal edgeTraversal;
    private final List<Color> sectionTints;
    private final Vec3 scale;
    private final @Nullable Identifier sourceId;
    private final Vec3[] positions;
    private final List<Primitive> primitives;

    public UnityMeshEmission(StaticMeshGeometry geometry, Type placement, ShapeArcMode vertexMode, int materialSection, double normalOffset,
                             boolean useVertexColor, Vec3 scale, @Nullable UnityShapeTexture texture,
                             UnityShapeSettings.@Nullable Traversal edgeTraversal) {
        this(geometry, placement, vertexMode, materialSection, normalOffset, useVertexColor, scale, texture, edgeTraversal, List.of());
    }

    public UnityMeshEmission(StaticMeshGeometry geometry, Type placement, ShapeArcMode vertexMode, int materialSection, double normalOffset,
                             boolean useVertexColor, Vec3 scale, @Nullable UnityShapeTexture texture,
                             UnityShapeSettings.@Nullable Traversal edgeTraversal, List<Color> sectionTints) {
        this(geometry, placement, vertexMode, materialSection, normalOffset, useVertexColor, scale, texture,
                edgeTraversal, sectionTints, null);
    }

    public UnityMeshEmission(StaticMeshGeometry geometry, Type placement, ShapeArcMode vertexMode, int materialSection, double normalOffset,
                             boolean useVertexColor, Vec3 scale, @Nullable UnityShapeTexture texture,
                             UnityShapeSettings.@Nullable Traversal edgeTraversal, List<Color> sectionTints,
                             @Nullable Identifier sourceId) {
        if (geometry == null || placement == null || vertexMode == null || vertexMode == ShapeArcMode.BurstSpread
                || materialSection < -1 || !Double.isFinite(normalOffset))
            throw new IllegalArgumentException("Invalid Unity mesh emission source");
        this.geometry = geometry;
        this.placement = placement;
        this.vertexMode = vertexMode;
        this.materialSection = materialSection;
        this.normalOffset = normalOffset;
        this.useVertexColor = useVertexColor;
        this.texture = texture;
        this.sectionTints = List.copyOf(sectionTints);
        this.scale = Objects.requireNonNull(scale);
        this.sourceId = sourceId;
        if (edgeTraversal != null && (placement != Type.Edge || edgeTraversal.mode() == ShapeArcMode.BurstSpread))
            throw new IllegalArgumentException("Invalid Unity mesh edge traversal");
        this.edgeTraversal = edgeTraversal;
        if (texture != null && geometry.textureCoordinateCount() == 0)
            throw new IllegalArgumentException("Unity mesh emission texture requires UVs");
        positions = new Vec3[geometry.vertexCount()];
        for (int vertex = 0; vertex < positions.length; vertex++) {
            if (texture != null && (!Float.isFinite(geometry.textureCoordinate(0, vertex, 0))
                    || !Float.isFinite(geometry.textureCoordinate(0, vertex, 1))))
                throw new IllegalArgumentException("Non-finite Unity mesh emission UV");
            positions[vertex] = new Vec3(geometry.position(vertex, 0), geometry.position(vertex, 2), geometry.position(vertex, 1))
                    .multiply(scale).scale(.01);
        }
        var table = new ArrayList<Primitive>();
        var vertices = new LinkedHashSet<Integer>();
        var edges = new LongOpenHashSet();
        for (var section : geometry.sections()) {
            if (materialSection >= 0 && section.polygonGroup() != materialSection) continue;
            Color tint = section.polygonGroup() < this.sectionTints.size() ? this.sectionTints.get(section.polygonGroup()) : null;
            for (int index = section.firstIndex(), end = index + section.indexCount(); index < end; index += 3) {
                int a = geometry.index(index), b = geometry.index(index + 1), c = geometry.index(index + 2);
                Vec3 cross = positions[b].subtract(positions[a]).cross(positions[c].subtract(positions[a]));
                Vec3 normal = cross.normalize();
                switch (placement) {
                    case Vertex -> {
                        if (vertices.add(a)) append(table, a, a, a, normal, tint, 1);
                        if (vertices.add(b)) append(table, b, b, b, normal, tint, 1);
                        if (vertices.add(c)) append(table, c, c, c, normal, tint, 1);
                    }
                    case Edge -> {
                        appendEdge(table, edges, a, b, normal, tint);
                        appendEdge(table, edges, b, c, normal, tint);
                        appendEdge(table, edges, c, a, normal, tint);
                    }
                    case Triangle -> append(table, a, b, c, normal, tint, cross.length() * .5);
                }
            }
        }
        if (table.isEmpty()) throw new IllegalArgumentException("Unity mesh emission has no usable " + placement + " samples");
        if (placement == Type.Vertex) table.sort(java.util.Comparator.comparingInt(Primitive::a));
        primitives = List.copyOf(table);
    }

    public UnityShapeSettings.@Nullable Traversal edgeTraversal() { return edgeTraversal; }
    public @Nullable Identifier sourceId() { return sourceId; }

    public UnityMeshEmission withGeometry(StaticMeshGeometry frame) {
        if (frame == geometry) return this;
        return new UnityMeshEmission(frame, placement, vertexMode, materialSection, normalOffset,
                useVertexColor, scale, texture, edgeTraversal, sectionTints, sourceId);
    }

    private void appendEdge(ArrayList<Primitive> table, LongOpenHashSet edges, int a, int b, Vec3 normal, @Nullable Color tint) {
        long key = (long)Math.min(a, b) << 32 | Math.max(a, b);
        if (edges.add(key)) append(table, a, b, b, normal, tint, positions[a].distanceTo(positions[b]));
    }

    private static void append(ArrayList<Primitive> table, int a, int b, int c, Vec3 normal, @Nullable Color tint, double weight) {
        if (weight > 0) {
            double previous = table.isEmpty() ? 0 : table.getLast().limit();
            table.add(new Primitive(a, b, c, normal, tint, previous + weight));
        }
    }

    private int select(double selection, int particleId) {
        int count = primitives.size();
        if (placement == Type.Vertex) {
            long ordinal = Integer.toUnsignedLong(particleId);
            return switch (vertexMode) {
                case Loop -> (int)(ordinal % count);
                case PingPong -> {
                    if (count == 1) yield 0;
                    long period = 2L * (count - 1);
                    long phase = ordinal % period;
                    yield (int)Math.min(phase, period - phase);
                }
                case Random -> Math.min(count - 1, (int)(selection * count));
                case BurstSpread -> throw new IllegalStateException("Invalid vertex emission mode");
            };
        }
        double value = selection * primitives.getLast().limit();
        int low = 0, high = count - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (value < primitives.get(middle).limit()) high = middle;
            else low = middle + 1;
        }
        return low;
    }

    public @Nullable Sample sample(double selection, int particleId, SplittableRandom random, Vec3 defaultDirection) {
        int index = select(selection, particleId);
        var selected = primitives.get(index);
        double wa = 1, wb = 0, wc = 0;
        if (placement == Type.Edge) {
            if (edgeTraversal != null) {
                double start = index == 0 ? 0 : primitives.get(index - 1).limit();
                wb = Math.clamp((selection * primitives.getLast().limit() - start) / (selected.limit() - start), 0, 1);
            } else wb = random.nextDouble();
            wa -= wb;
        } else if (placement == Type.Triangle) {
            double edgeWeight = ShapeDistribution.triangleEdgeWeight(random.nextDouble());
            wa = 1 - edgeWeight;
            wc = edgeWeight * random.nextDouble();
            wb = edgeWeight - wc;
        }
        int a = selected.a(), b = selected.b(), c = selected.c();
        Color textureColor = null;
        if (texture != null) {
            double u = geometry.textureCoordinate(0, a, 0) * wa + geometry.textureCoordinate(0, b, 0) * wb + geometry.textureCoordinate(0, c, 0) * wc;
            double v = geometry.textureCoordinate(0, a, 1) * wa + geometry.textureCoordinate(0, b, 1) * wb + geometry.textureCoordinate(0, c, 1) * wc;
            textureColor = texture.sample(u, 1 - v);
            if (textureColor == null) return null;
        }
        Vec3 position = positions[a].scale(wa).add(positions[b].scale(wb)).add(positions[c].scale(wc));
        Vec3 direction = new Vec3(normal(a, b, c, 0, wa, wb, wc), normal(a, b, c, 2, wa, wb, wc), normal(a, b, c, 1, wa, wb, wc)).normalize();
        if (direction.lengthSqr() == 0) direction = selected.fallbackNormal();
        if (direction.lengthSqr() == 0) direction = defaultDirection;
        Color tint = useVertexColor ? new Color(color(a, b, c, 0, wa, wb, wc), color(a, b, c, 1, wa, wb, wc),
                color(a, b, c, 2, wa, wb, wc), color(a, b, c, 3, wa, wb, wc)) : selected.tint();
        if (textureColor != null) tint = tint == null ? textureColor : new Color(tint.red() * textureColor.red(),
                tint.green() * textureColor.green(), tint.blue() * textureColor.blue(), tint.alpha() * textureColor.alpha());
        return new Sample(position.add(direction.scale(normalOffset)), direction, tint);
    }

    private double normal(int a, int b, int c, int axis, double wa, double wb, double wc) {
        return geometry.normal(a, axis) * wa + geometry.normal(b, axis) * wb + geometry.normal(c, axis) * wc;
    }

    private float color(int a, int b, int c, int channel, double wa, double wb, double wc) {
        return (float)(geometry.color(a, channel) * wa + geometry.color(b, channel) * wb + geometry.color(c, channel) * wc);
    }
}
