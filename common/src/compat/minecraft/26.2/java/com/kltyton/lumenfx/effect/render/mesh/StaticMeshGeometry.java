package com.kltyton.lumenfx.effect.render.mesh;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Source-space static mesh with independent vertex instances and material sections. */
public final class StaticMeshGeometry implements MeshSectionGeometry {
    public enum VertexIdKind { SOURCE_VERTEX_INSTANCE, RENDER_VERTEX }
    public record SectionInfo(int materialIndex, boolean collision, boolean castShadow, boolean visibleInRayTracing,
                              boolean affectDistanceFieldLighting, boolean forceOpaque) { }
    public record Section(int polygonGroup, String materialSlot, int firstIndex, int indexCount, @Nullable SectionInfo info) {
        public Section(int polygonGroup, String materialSlot, int firstIndex, int indexCount) {
            this(polygonGroup, materialSlot, firstIndex, indexCount, null);
        }
        public Section {
            Objects.requireNonNull(materialSlot);
            if (polygonGroup < 0 || firstIndex < 0 || indexCount < 0 || indexCount % 3 != 0)
                throw new IllegalArgumentException("Invalid static mesh section");
        }
    }

    private final int[] sourceInstanceIds, indices;
    private final float[] positions, normals, tangents, binormalSigns, colors;
    private final float[][] textureCoordinates;
    private final int[] textureCoordinateWidths;
    private final boolean wideTextureCoordinates;
    private final List<Section> sections;
    private final VertexIdKind vertexIdKind;

    public StaticMeshGeometry(int[] sourceInstanceIds, float[] positions, float[] normals, float[] tangents,
                              float[] binormalSigns, float[] colors, List<float[]> textureCoordinates,
                              int[] indices, List<Section> sections) {
        this(sourceInstanceIds, positions, normals, tangents, binormalSigns, colors, textureCoordinates, indices, sections,
                VertexIdKind.SOURCE_VERTEX_INSTANCE);
    }

    public StaticMeshGeometry(int[] sourceInstanceIds, float[] positions, float[] normals, float[] tangents,
                              float[] binormalSigns, float[] colors, List<float[]> textureCoordinates,
                              int[] indices, List<Section> sections, VertexIdKind vertexIdKind) {
        this(sourceInstanceIds, positions, normals, tangents, binormalSigns, colors, textureCoordinates,
                defaultWidths(textureCoordinates.size()), indices, sections, vertexIdKind);
    }

    public StaticMeshGeometry(int[] sourceInstanceIds, float[] positions, float[] normals, float[] tangents,
                              float[] binormalSigns, float[] colors, List<float[]> textureCoordinates,
                              int[] textureCoordinateWidths, int[] indices, List<Section> sections, VertexIdKind vertexIdKind) {
        this.vertexIdKind = Objects.requireNonNull(vertexIdKind);
        if (textureCoordinateWidths.length != textureCoordinates.size())
            throw new IllegalArgumentException("Static mesh UV width count disagrees");
        int vertices = sourceInstanceIds.length;
        if (positions.length != Math.multiplyExact(vertices, 3) || normals.length != positions.length
                || tangents.length != positions.length || binormalSigns.length != vertices
                || colors.length != Math.multiplyExact(vertices, 4) || indices.length % 3 != 0)
            throw new IllegalArgumentException("Static mesh vertex channel lengths disagree");
        var sourceIds = new HashSet<Integer>();
        for (int id : sourceInstanceIds) {
            if (id < 0 || !sourceIds.add(id)) throw new IllegalArgumentException("Static mesh source instance IDs must be nonnegative and unique");
        }
        for (int index : indices)
            if (index < 0 || index >= vertices) throw new IllegalArgumentException("Static mesh index is out of range");
        int nextIndex = 0, previousGroup = -1;
        for (Section section : sections) {
            if (section.polygonGroup() <= previousGroup
                    || section.firstIndex() != (section.indexCount() == 0 ? 0 : nextIndex))
                throw new IllegalArgumentException("Static mesh section order or range disagrees");
            nextIndex = Math.addExact(nextIndex, section.indexCount());
            previousGroup = section.polygonGroup();
        }
        if (nextIndex != indices.length) throw new IllegalArgumentException("Static mesh sections do not cover indices");
        this.sourceInstanceIds = sourceInstanceIds.clone();
        this.indices = indices.clone();
        this.positions = finiteCopy(positions);
        this.normals = finiteCopy(normals);
        this.tangents = finiteCopy(tangents);
        this.binormalSigns = finiteCopy(binormalSigns);
        this.colors = finiteCopy(colors);
        this.textureCoordinates = new float[textureCoordinates.size()][];
        this.textureCoordinateWidths = textureCoordinateWidths.clone();
        boolean wide = false;
        for (int channel = 0; channel < textureCoordinates.size(); channel++) {
            int width = this.textureCoordinateWidths[channel];
            if (width != 2 && width != 4) throw new IllegalArgumentException("Unsupported static mesh UV width: " + width);
            if (width == 4) wide = true;
            float[] coordinates = textureCoordinates.get(channel);
            if (coordinates.length != Math.multiplyExact(vertices, width))
                throw new IllegalArgumentException("Static mesh UV channel length disagrees");
            float[] values = coordinates.clone();
            for (float value : values)
                if (Float.isNaN(value)) throw new IllegalArgumentException("NaN static mesh UV value");
            // Source half-precision RTNE storage permits signed infinity after finite UV overflow.
            this.textureCoordinates[channel] = values;
        }
        this.wideTextureCoordinates = wide;
        this.sections = List.copyOf(sections);
    }

    private static int[] defaultWidths(int count) {
        int[] widths = new int[count];
        java.util.Arrays.fill(widths, 2);
        return widths;
    }

    private StaticMeshGeometry(StaticMeshGeometry source, List<Section> sections) {
        vertexIdKind = source.vertexIdKind;
        sourceInstanceIds = source.sourceInstanceIds; indices = source.indices; positions = source.positions;
        normals = source.normals; tangents = source.tangents; binormalSigns = source.binormalSigns;
        colors = source.colors; textureCoordinates = source.textureCoordinates;
        textureCoordinateWidths = source.textureCoordinateWidths;
        wideTextureCoordinates = source.wideTextureCoordinates;
        this.sections = List.copyOf(sections);
    }

    public StaticMeshGeometry withSectionInfo(List<SectionInfo> metadata) {
        if (metadata.size() != sections.size()) throw new IllegalArgumentException("Mesh section metadata count disagrees");
        var result = new java.util.ArrayList<Section>(sections.size());
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            result.add(new Section(section.polygonGroup(), section.materialSlot(), section.firstIndex(), section.indexCount(),
                    Objects.requireNonNull(metadata.get(index))));
        }
        return new StaticMeshGeometry(this, result);
    }

    public int vertexCount() { return sourceInstanceIds.length; }
    public int indexCount() { return indices.length; }
    public int index(int offset) { return indices[offset]; }
    public int textureCoordinateCount() { return textureCoordinates.length; }
    public List<Section> sections() { return sections; }
    public int sourceInstanceId(int vertex) { return sourceInstanceIds[vertex]; }
    public VertexIdKind vertexIdKind() { return vertexIdKind; }
    public int vertexId(int vertex) { return sourceInstanceIds[vertex]; }
    public float position(int vertex, int axis) { return positions[vertex * 3 + axis]; }
    public float normal(int vertex, int axis) { return normals[vertex * 3 + axis]; }
    public float tangent(int vertex, int axis) { return tangents[vertex * 3 + axis]; }
    public float binormalSign(int vertex) { return binormalSigns[vertex]; }
    public float color(int vertex, int component) { return colors[vertex * 4 + component]; }
    public int textureCoordinateWidth(int channel) { return textureCoordinateWidths[channel]; }
    public boolean wideTextureCoordinates() { return wideTextureCoordinates; }
    public float textureCoordinate(int channel, int vertex, int axis) {
        int width = textureCoordinateWidths[channel];
        return axis >= width ? 0f : textureCoordinates[channel][vertex * width + axis];
    }

    public int vertexTexels() { return 4 + (wideTextureCoordinates ? textureCoordinates.length : (textureCoordinates.length + 1) / 2); }

    /** Four basis/color texels followed by source UV sets (packed 2D pairs or full 4D vec4s). */
    public void writeVertices(ByteBuffer output) {
        for (int vertex = 0; vertex < vertexCount(); vertex++) writeVertex(output, vertex);
    }

    public void writeVertex(ByteBuffer output, int vertex) {
        for (int axis = 0; axis < 3; axis++) output.putFloat(position(vertex, axis));
        output.putFloat(binormalSigns[vertex]);
        for (int axis = 0; axis < 3; axis++) output.putFloat(normal(vertex, axis));
        // Exact 16-bit halves avoid subnormal/NaN transport through floating-point texel fetches.
        output.putFloat(sourceInstanceIds[vertex] & 0xffff);
        for (int axis = 0; axis < 3; axis++) output.putFloat(tangent(vertex, axis));
        output.putFloat(sourceInstanceIds[vertex] >>> 16);
        for (int component = 0; component < 4; component++) output.putFloat(color(vertex, component));
        if (wideTextureCoordinates) {
            for (int channel = 0; channel < textureCoordinates.length; channel++) {
                float[] values = textureCoordinates[channel];
                int width = textureCoordinateWidths[channel];
                int base = vertex * width;
                output.putFloat(values[base]).putFloat(values[base + 1]);
                if (width == 4) output.putFloat(values[base + 2]).putFloat(values[base + 3]);
                else output.putFloat(0).putFloat(0);
            }
        } else {
            for (float[] channel : textureCoordinates)
                output.putFloat(channel[vertex * 2]).putFloat(channel[vertex * 2 + 1]);
            if ((textureCoordinates.length & 1) != 0) output.putFloat(0).putFloat(0);
        }
    }

    public void writeIndices(ByteBuffer output) {
        for (int index : indices) output.putInt(index);
    }

    private static float[] finiteCopy(float[] values) {
        float[] result = values.clone();
        for (float value : result)
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite static mesh vertex value");
        return result;
    }
}
