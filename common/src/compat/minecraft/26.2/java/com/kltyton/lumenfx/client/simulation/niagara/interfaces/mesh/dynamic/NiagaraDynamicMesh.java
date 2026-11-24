package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.dynamic;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMesh;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.jspecify.annotations.Nullable;

/** Per-system section allocation and packed CPU mesh data. */
public final class NiagaraDynamicMesh {
    public enum Operation {
        GetMeshProperties, GetSectionCount, GetSectionData, GetLocalBounds,
        SetMeshProperties, SetSectionCount, SetSectionData, SetLocalBounds,
        ClearAllSectionTriangles, ClearSectionTriangles, AllocateSectionTriangles,
        GetTriangleVertices, GetVertexPosition, GetVertexTangentBasis, GetVertexTexCoord, GetVertexColor, GetVertexData,
        SetTriangleVertices, SetVertexPosition, SetVertexTangentBasis, SetVertexTexCoord, SetVertexColor, SetVertexData,
        AppendTriangle
    }
    @FunctionalInterface
    public interface Output { void set(int register, int lane, int word); }
    private static final class Section {
        int offset, maximum, allocated, material;
        Section(int maximum, int material) { this.maximum = maximum; this.material = material; }
        NiagaraDynamicMeshFrame.Section snapshot() { return new NiagaraDynamicMeshFrame.Section(offset, maximum, allocated, material); }
    }

    private final NiagaraDynamicMeshDefinition definition;
    private final List<Section> sections = new ArrayList<>();
    private NiagaraDynamicMeshVertices.Layout layout;
    private NiagaraBounds bounds;
    private int triangles;
    private int[] indices = new int[0], vertices = new int[0];
    private long revision;
    private @Nullable NiagaraDynamicMeshFrame published;
    private @Nullable List<String> cachedMaterials;

    public NiagaraDynamicMesh(NiagaraDynamicMeshDefinition definition) {
        this.definition = definition;
        bounds = definition.bounds();
        for (var section : definition.sections()) sections.add(new Section(section.triangles(), section.materialIndex()));
        layout = NiagaraDynamicMeshVertices.Layout.create(definition.vertices(), definition.texcoords(), definition.tangents(), definition.colors());
        resetGeometry();
    }
    public NiagaraDynamicMeshDefinition definition() { return definition; }

    public synchronized void beginFrame() {
        if (definition.clearTrianglesPerFrame()) {
            sections.forEach(section -> section.allocated = 0);
            revision++;
        }
    }

    public synchronized void execute(Operation operation, int lanes, int inputCount, int outputCount,
                                     IntBinaryOperator input, Output output) {
        switch (operation) {
            case SetTriangleVertices, SetVertexPosition, SetVertexTangentBasis, SetVertexTexCoord, SetVertexColor, SetVertexData, AppendTriangle -> allocateCpu();
            default -> { }
        }
        int[] arguments = new int[inputCount], results = new int[outputCount];
        boolean rebuild = false;
        int outputLane = 0;
        for (int lane = 0; lane < lanes; lane++) {
            for (int word = 0; word < arguments.length; word++) arguments[word] = input.applyAsInt(word, lane);
            Arrays.fill(results, 0);
            switch (operation) {
                case SetSectionCount -> {
                    if (arguments[0] != 0) rebuild |= resizeSections(arguments[1]);
                }
                case SetSectionData -> {
                    if (arguments[0] != 0 && hasSection(arguments[1])) {
                        var section = sections.get(arguments[1]);
                        if (arguments[2] < 0) throw new IllegalArgumentException("Negative Niagara mesh triangle capacity");
                        rebuild |= section.maximum != arguments[2];
                        section.maximum = arguments[2]; section.material = arguments[3];
                    }
                }
                case AppendTriangle -> {
                    if (arguments[0] == 0 || !hasSection(arguments[1])) continue;
                    results[0] = append(sections.get(arguments[1]), arguments);
                }
                default -> lane(operation, arguments, results);
            }
            for (int word = 0; word < results.length; word++) output.set(word, outputLane, results[word]);
            outputLane++;
        }
        if (rebuild) resetGeometry();
    }

    private void lane(Operation operation, int[] in, int[] out) {
        switch (operation) {
            case GetMeshProperties -> {
                out[0] = triangles; out[1] = layout.count(); out[2] = layout.uvChannels();
                out[3] = layout.colorOffset() < 0 ? 0 : -1; out[4] = layout.tangentOffset() < 0 ? 0 : -1;
            }
            case GetSectionCount -> out[0] = sections.size();
            case GetSectionData -> {
                if (!hasSection(in[0])) return;
                var section = sections.get(in[0]);
                out[0] = -1; out[1] = section.maximum; out[2] = section.material;
            }
            case GetLocalBounds -> {
                out[0] = bits(bounds.minX()); out[1] = bits(bounds.minY()); out[2] = bits(bounds.minZ());
                out[3] = bits(bounds.maxX()); out[4] = bits(bounds.maxY()); out[5] = bits(bounds.maxZ());
            }
            case SetMeshProperties -> {
                if (in[0] == 0) return;
                var next = NiagaraDynamicMeshVertices.Layout.create(in[1], in[2], in[4] != 0, in[3] != 0);
                if (!next.equals(layout)) { layout = next; resetGeometry(); }
            }
            case SetLocalBounds -> {
                if (in[0] != 0) bounds = new NiagaraBounds(number(in[1]), number(in[2]), number(in[3]), number(in[4]), number(in[5]), number(in[6]));
            }
            case ClearAllSectionTriangles -> {
                if (in[0] != 0) sections.forEach(section -> section.allocated = 0);
            }
            case ClearSectionTriangles -> {
                if (in[0] != 0 && hasSection(in[1])) sections.get(in[1]).allocated = 0;
            }
            case AllocateSectionTriangles -> {
                out[0] = -1;
                if (in[0] == 0 || !hasSection(in[1]) || in[2] <= 0) return;
                var section = sections.get(in[1]);
                int available = section.maximum - section.allocated;
                out[0] = available > 0 ? section.offset + section.allocated : -1;
                out[1] = Math.min(available, in[2]);
                section.allocated += out[1];
            }
            case GetTriangleVertices -> {
                if (indices.length == 0 || !hasTriangle(in[0])) return;
                out[0] = -1;
                System.arraycopy(indices, in[0] * 3, out, 1, 3);
            }
            case GetVertexPosition, GetVertexTangentBasis, GetVertexTexCoord, GetVertexColor, GetVertexData -> readVertex(operation, in, out);
            case SetTriangleVertices -> {
                if (in[0] != 0 && hasTriangle(in[1])) System.arraycopy(in, 2, indices, in[1] * 3, 3);
            }
            case SetVertexPosition, SetVertexTangentBasis, SetVertexTexCoord, SetVertexColor, SetVertexData -> writeVertex(operation, in);
            case SetSectionCount, SetSectionData, AppendTriangle -> throw new IllegalArgumentException("Batch operation requires its dynamic mesh dispatch");
        }
    }

    private void readVertex(Operation operation, int[] input, int[] output) {
        int vertex = input[0];
        boolean valid = vertices.length != 0 && hasVertex(vertex);
        switch (operation) {
            case GetVertexPosition -> { if (valid) NiagaraDynamicMeshVertices.readPosition(vertices, vertex, output, 1); }
            case GetVertexTangentBasis -> {
                valid &= layout.tangentOffset() >= 0;
                if (valid) NiagaraDynamicMeshVertices.readTangent(layout, vertices, vertex, output, 1);
            }
            case GetVertexTexCoord -> {
                valid &= layout.uvOffset() >= 0 && hasUv(input[1]);
                if (valid) NiagaraDynamicMeshVertices.readUv(layout, vertices, vertex, input[1], output, 1);
            }
            case GetVertexColor -> {
                valid &= layout.colorOffset() >= 0;
                Arrays.fill(output, 1, 5, NiagaraDynamicMeshVertices.WHITE);
                if (valid) NiagaraDynamicMeshVertices.readColor(layout, vertices, vertex, output, 1);
            }
            case GetVertexData -> {
                Arrays.fill(output, 15, 19, NiagaraDynamicMeshVertices.WHITE);
                if (valid) NiagaraDynamicMeshVertices.readVertex(layout, vertices, vertex, output, 1);
            }
            default -> throw new IllegalArgumentException("Invalid dynamic mesh vertex reader");
        }
        output[0] = valid ? -1 : 0;
    }

    private void writeVertex(Operation operation, int[] in) {
        if (in[0] == 0) return;
        int vertex = in[1];
        if (!hasVertex(vertex)) {
            if (operation == Operation.SetVertexData) throw new IllegalArgumentException("Niagara dynamic mesh vertex index out of range: " + vertex);
            return;
        }
        switch (operation) {
            case SetVertexPosition -> NiagaraDynamicMeshVertices.writePosition(vertices, vertex, in, 2);
            case SetVertexTangentBasis -> {
                if (layout.tangentOffset() >= 0) NiagaraDynamicMeshVertices.writeTangent(layout, vertices, vertex, in, 2);
            }
            case SetVertexTexCoord -> {
                if (layout.uvOffset() >= 0 && hasUv(in[2])) NiagaraDynamicMeshVertices.writeUv(layout, vertices, vertex, in[2], in, 3);
            }
            case SetVertexColor -> {
                if (layout.colorOffset() >= 0) NiagaraDynamicMeshVertices.writeColor(layout, vertices, vertex, in, 2);
            }
            case SetVertexData -> NiagaraDynamicMeshVertices.writeVertex(layout, vertices, vertex, in, 2);
            default -> throw new IllegalArgumentException("Invalid dynamic mesh vertex writer");
        }
    }

    private int append(Section section, int[] in) {
        if (section.allocated >= section.maximum) return -1;
        int triangle = section.allocated, vertex = Math.multiplyExact(triangle, 3);
        if (!hasVertex(vertex + 2)) throw new IllegalArgumentException("Niagara dynamic mesh append exceeds the vertex capacity");
        section.allocated++;
        for (int corner = 0; corner < 3; corner++) {
            indices[vertex + corner] = vertex + corner;
            NiagaraDynamicMeshVertices.writeVertex(layout, vertices, vertex + corner, in, 2 + corner * NiagaraDynamicMeshVertices.FULL_VERTEX_WORDS);
        }
        return triangle;
    }

    private boolean resizeSections(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative Niagara mesh section count");
        if (sections.size() == count) return false;
        if (sections.size() > count) sections.subList(count, sections.size()).clear();
        while (sections.size() < count) sections.add(new Section(0, 0));
        return true;
    }
    private void resetGeometry() {
        int next = 0;
        for (var section : sections) {
            section.offset = next;
            section.allocated = definition.clearTrianglesPerFrame() ? 0 : section.maximum;
            next = Math.addExact(next, section.maximum);
        }
        triangles = next;
        indices = new int[0]; vertices = new int[0];
        revision++;
    }
    private void allocateCpu() {
        int indexWords = Math.multiplyExact(triangles, 3);
        if (indices.length != indexWords) indices = Arrays.copyOf(indices, indexWords);
        if (vertices.length != layout.words()) vertices = Arrays.copyOf(vertices, layout.words());
        revision++;
    }
    private boolean hasSection(int index) { return index >= 0 && index < sections.size(); }
    private boolean hasTriangle(int index) { return index >= 0 && index < triangles; }
    private boolean hasVertex(int index) { return index >= 0 && index < layout.count(); }
    private boolean hasUv(int index) { return index >= 0 && index < layout.uvChannels(); }

    public synchronized NiagaraDynamicMeshFrame publish() {
        if (published == null || published.revision() != revision)
            published = captureFrame();
        return published;
    }

    public synchronized NiagaraDynamicMeshFrame captureFrame() {
        return new NiagaraDynamicMeshFrame(revision, triangles, layout.count(), layout.uvChannels(), layout.tangentOffset(), layout.uvOffset(),
                layout.colorOffset(), layout.words(), bounds, sections.stream().map(Section::snapshot).toList(), indices, vertices);
    }

    public synchronized List<String> materialSources(NiagaraDynamicMeshFrame frame) {
        if (cachedMaterials != null) return cachedMaterials;
        return frame.sections().stream().map(section -> {
            int material = section.materialIndex();
            return material < 0 || material >= definition.materials().size() ? "" : definition.materials().get(material).source();
        }).toList();
    }

    public synchronized List<String> materialParameters(NiagaraDynamicMeshFrame frame) {
        if (cachedMaterials != null) return Collections.nCopies(frame.sections().size(), "");
        return frame.sections().stream().map(section -> {
            int material = section.materialIndex();
            return material < 0 || material >= definition.materials().size() ? "" : definition.materials().get(material).userParameter();
        }).toList();
    }

    public synchronized NiagaraDynamicMeshFrame readCache(@Nullable DynamicMesh value, boolean gpu) {
        if (value == null) {
            sections.clear();
            layout = NiagaraDynamicMeshVertices.Layout.create(0, 0, false, false);
            resetGeometry();
            cachedMaterials = List.of();
            return publish();
        }
        var frame = value.frame();
        bounds = frame.bounds();
        triangles = frame.triangles();
        layout = new NiagaraDynamicMeshVertices.Layout(frame.vertices(), frame.uvChannels(), frame.tangentOffset(),
                frame.uvOffset(), frame.colorOffset(), frame.vertexWords());
        sections.clear();
        for (var source : frame.sections()) {
            var section = new Section(source.maxTriangles(), source.materialIndex());
            section.offset = source.triangleOffset(); section.allocated = source.allocatedTriangles();
            sections.add(section);
        }
        int[] indexWords = frame.indices(), vertexWords = frame.attributes();
        indices = gpu ? new int[0] : indexWords;
        vertices = gpu ? new int[0] : vertexWords;
        cachedMaterials = value.materials();
        published = new NiagaraDynamicMeshFrame(++revision, triangles, layout.count(), layout.uvChannels(), layout.tangentOffset(),
                layout.uvOffset(), layout.colorOffset(), layout.words(), bounds, frame.sections(), indexWords, vertexWords);
        return published;
    }
    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
    private static float number(int word) { return Float.intBitsToFloat(word); }
}
