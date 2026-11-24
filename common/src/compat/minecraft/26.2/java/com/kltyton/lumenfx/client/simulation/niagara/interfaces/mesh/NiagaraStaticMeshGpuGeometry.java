package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.GpuBuffers;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.VertexColors;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshSampling.Selection;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Packs the original index order and static sampling tables for the source GPU shader ABI. */
public final class NiagaraStaticMeshGpuGeometry {
    public enum Buffer {
        INDICES("IndexBuffer", 1), POSITIONS("PositionBuffer", 1), TANGENTS("TangentBuffer", 4),
        UV("UVBuffer", 2), COLORS("ColorBuffer", 4), TRIANGLE_ALIAS("UniformSamplingTriangles", 2),
        SECTIONS("SectionInfos", 4), SECTION_REMAP("FilteredAndUnfilteredSections", 1),
        SOCKETS("SocketTransforms", 4), SOCKET_REMAP("FilteredAndUnfilteredSockets", 1),
        UV_MAPPING("UvMappingBuffer", 1);

        private final String member;
        private final int words;
        Buffer(String member, int words) { this.member = member; this.words = words; }
        public String member() { return member; }
    }

    private final NiagaraStaticMeshSampling mesh;
    private final @Nullable NiagaraMeshUvTree uvMapping;
    private final @Nullable VertexColors vertexColors;
    private final GpuBuffers access;
    private record Identity(NiagaraStaticMeshSampling mesh, @Nullable NiagaraMeshUvTree uvMapping, GpuBuffers access) { }

    NiagaraStaticMeshGpuGeometry(NiagaraStaticMeshSampling mesh, @Nullable NiagaraMeshUvTree uvMapping,
                                 @Nullable VertexColors vertexColors, GpuBuffers access) {
        this.mesh = mesh;
        this.uvMapping = uvMapping;
        this.vertexColors = access.validMesh() && access.colors() ? vertexColors : null;
        this.access = access;
    }
    public Object identity() { return new Identity(mesh, uvMapping, access); }
    public @Nullable VertexColors vertexColors() { return vertexColors; }
    public boolean hasVertexColors() {
        return samplingGeometry() != null && access.colors() && (mesh.hasVertexColors() || vertexColors != null && vertexColors.size() != 0);
    }
    @Nullable StaticMeshGeometry samplingGeometry() {
        var source = mesh.geometry();
        return source != null && access.validMesh() && source.indexCount() != 0 ? source : null;
    }
    int textureCoordinateCount() {
        var source = samplingGeometry();
        return source == null || !access.textureCoordinates() ? 0 : source.textureCoordinateCount();
    }
    boolean areaSampling() { return samplingGeometry() != null && access.areaSampling() && mesh.gpuAreaWeighted(); }

    public long size(Buffer buffer) {
        var geometry = samplingGeometry();
        long vertices = geometry == null ? 0 : geometry.vertexCount();
        long indices = geometry == null ? 0 : geometry.indexCount();
        long sections = mesh.sectionCount(Selection.ALL), filtered = mesh.sectionCount(Selection.FILTERED),
                unfiltered = mesh.sectionCount(Selection.UNFILTERED);
        long elements = switch (buffer) {
            case INDICES -> indices;
            case POSITIONS -> vertices * 3;
            case TANGENTS -> vertices * 2;
            case UV -> vertices * textureCoordinateCount();
            case COLORS -> hasVertexColors() ? vertices : 0;
            case TRIANGLE_ALIAS -> areaSampling() ? indices / 3 : 0;
            case SECTIONS -> sections + filtered + unfiltered;
            case SECTION_REMAP -> filtered + unfiltered;
            case SOCKETS -> (long)mesh.socketCount(Selection.ALL) * 3;
            case SOCKET_REMAP -> Math.max(mesh.socketCount(Selection.ALL), mesh.socketRemap().size());
            case UV_MAPPING -> uvMapping == null ? 0 : uvMapping.byteSize() / Integer.BYTES;
        };
        return Math.multiplyExact(Math.max(1, elements), (long)buffer.words * Integer.BYTES);
    }

    public void write(Buffer buffer, ByteBuffer data) {
        var geometry = samplingGeometry();
        if (geometry != null) switch (buffer) {
            case INDICES -> geometry.writeIndices(data);
            case POSITIONS -> {
                for (int vertex = 0; vertex < geometry.vertexCount(); vertex++)
                    for (int axis = 0; axis < 3; axis++) data.putFloat(geometry.position(vertex, axis));
            }
            case TANGENTS -> {
                for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
                    for (int axis = 0; axis < 3; axis++) data.putFloat(geometry.tangent(vertex, axis));
                    data.putFloat(1);
                    for (int axis = 0; axis < 3; axis++) data.putFloat(geometry.normal(vertex, axis));
                    data.putFloat(geometry.binormalSign(vertex));
                }
            }
            case UV -> {
                for (int vertex = 0; vertex < geometry.vertexCount(); vertex++)
                    for (int channel = 0, channels = textureCoordinateCount(); channel < channels; channel++)
                        for (int axis = 0; axis < 2; axis++) data.putFloat(geometry.textureCoordinate(channel, vertex, axis));
            }
            case COLORS -> {
                if (hasVertexColors()) {
                    for (int vertex = 0; vertex < geometry.vertexCount(); vertex++)
                        for (int channel = 0; channel < 4; channel++)
                            data.putFloat(vertexColors == null ? geometry.color(vertex, channel) : vertexColors.channel(vertex, channel) / 255f);
                }
            }
            case TRIANGLE_ALIAS -> {
                if (areaSampling()) for (int section = 0; section < geometry.sections().size(); section++) {
                    var weights = mesh.triangleWeights(section);
                    for (int triangle = 0; triangle < weights.size(); triangle++)
                        data.putFloat(weights.probability(triangle)).putInt(weights.alias(triangle));
                }
            }
            default -> { }
        }
        if (buffer == Buffer.SECTIONS && mesh.geometry() != null) {
            for (Selection selection : Selection.values()) writeSections(data, selection);
        } else if (buffer == Buffer.SECTION_REMAP) {
            writeRemap(data, mesh.sectionRemap(Selection.FILTERED));
            writeRemap(data, mesh.sectionRemap(Selection.UNFILTERED));
        } else if (buffer == Buffer.SOCKETS) {
            for (var socket : mesh.socketTransforms()) {
                var p = socket.position(); var q = socket.rotation(); var s = socket.scale();
                data.putFloat((float)p.x).putFloat((float)p.y).putFloat((float)p.z).putFloat(0);
                data.putFloat(q.x()).putFloat(q.y()).putFloat(q.z()).putFloat(q.w());
                data.putFloat((float)s.x).putFloat((float)s.y).putFloat((float)s.z).putFloat(0);
            }
        } else if (buffer == Buffer.SOCKET_REMAP) writeRemap(data, mesh.socketRemap());
        else if (buffer == Buffer.UV_MAPPING && uvMapping != null) uvMapping.write(data);
        while (data.remaining() >= Integer.BYTES) data.putInt(buffer == Buffer.COLORS ? Float.floatToRawIntBits(1f) : 0);
    }

    private void writeSections(ByteBuffer data, Selection selection) {
        var geometry = Objects.requireNonNull(mesh.geometry());
        var weights = selection == Selection.ALL && !mesh.gpuAreaWeighted() ? null : mesh.weights(selection);
        int count = mesh.sectionCount(selection);
        for (int index = 0; index < count; index++) {
            var section = geometry.sections().get(mesh.sectionAt(index, selection));
            data.putInt(section.firstIndex() / 3).putInt(section.indexCount() / 3);
            data.putFloat(weights == null ? 1f : weights.probability(index)).putInt(weights == null ? index : weights.alias(index));
        }
    }

    private static void writeRemap(ByteBuffer data, List<Integer> values) { for (int value : values) data.putInt(value); }
}
