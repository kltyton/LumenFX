package com.kltyton.lumenfx.effect.asset.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Decodes mesh resources during reload preparation without opening files on render workers. */
public final class StaticMeshGeometryLoader {
    private StaticMeshGeometryLoader() { }

    public static StaticMeshGeometry load(ResourceProvider resources, Identifier path) {
        try {
            return load(resources.getResourceOrThrow(path), path);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot find static mesh " + path, error);
        }
    }

    public static StaticMeshGeometry load(Resource payload, Identifier path) {
        try (var input = payload.open()) {
            ByteBuffer data = ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (data.remaining() < 24 || data.getInt() != 0x484d464c)
                throw new IOException("Unsupported static mesh header: " + path);
            int version = data.getInt();
            if (version < 1 || version > 3) throw new IOException("Unsupported static mesh version: " + path);
            var idKind = StaticMeshGeometry.VertexIdKind.SOURCE_VERTEX_INSTANCE;
            if (version >= 2) {
                if (data.remaining() < 20) throw new IOException("Truncated static mesh header: " + path);
                int kind = data.getInt();
                if (kind < 0 || kind >= StaticMeshGeometry.VertexIdKind.values().length)
                    throw new IOException("Unknown static mesh vertex ID kind: " + path);
                idKind = StaticMeshGeometry.VertexIdKind.values()[kind];
            }
            int vertices = data.getInt(), indexCount = data.getInt(), uvCount = data.getInt(), sectionCount = data.getInt();
            if (vertices < 0 || indexCount < 0 || indexCount % 3 != 0 || uvCount < 0 || uvCount > 128 || sectionCount < 0)
                throw new IOException("Invalid static mesh counts: " + path);
            int[] uvWidths = new int[uvCount];
            if (version >= 3) {
                if (data.remaining() < (long)uvCount * Integer.BYTES)
                    throw new IOException("Truncated static mesh UV widths: " + path);
                for (int channel = 0; channel < uvCount; channel++) {
                    int width = data.getInt();
                    if (width != 2 && width != 4) throw new IOException("Unsupported static mesh UV width: " + width);
                    uvWidths[channel] = width;
                }
            } else {
                java.util.Arrays.fill(uvWidths, 2);
            }
            long uvFloats = 0L;
            for (int width : uvWidths) uvFloats += width;
            long numericBytes = ((long)vertices * (15L + uvFloats) + indexCount) * 4;
            if (numericBytes + (long)sectionCount * 16 > data.remaining())
                throw new IOException("Truncated static mesh channels: " + path);
            int[] instanceIds = integers(data, vertices);
            float[] positions = floats(data, Math.multiplyExact(vertices, 3));
            float[] normals = floats(data, positions.length);
            float[] tangents = floats(data, positions.length);
            float[] binormalSigns = floats(data, vertices);
            float[] colors = floats(data, Math.multiplyExact(vertices, 4));
            List<float[]> coordinates = new ArrayList<>(uvCount);
            for (int channel = 0; channel < uvCount; channel++)
                coordinates.add(floats(data, Math.multiplyExact(vertices, uvWidths[channel])));
            int[] indices = integers(data, indexCount);
            List<StaticMeshGeometry.Section> sections = new ArrayList<>(sectionCount);
            for (int section = 0; section < sectionCount; section++) {
                if (data.remaining() < 16) throw new IOException("Truncated static mesh section: " + path);
                int polygonGroup = data.getInt(), nameBytes = data.getInt();
                if (nameBytes < 0 || nameBytes > data.remaining() - 8)
                    throw new IOException("Invalid static mesh material name extent: " + path);
                ByteBuffer encodedName = data.slice(data.position(), nameBytes);
                String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(encodedName).toString();
                data.position(data.position() + nameBytes);
                sections.add(new StaticMeshGeometry.Section(polygonGroup, name, data.getInt(), data.getInt()));
            }
            if (data.hasRemaining()) throw new IOException("Unexpected trailing static mesh data: " + path);
            return new StaticMeshGeometry(instanceIds, positions, normals, tangents, binormalSigns, colors,
                    coordinates, uvWidths, indices, sections, idKind);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load static mesh " + path, error);
        }
    }

    private static float[] floats(ByteBuffer source, int count) {
        float[] result = new float[count];
        source.asFloatBuffer().get(result);
        source.position(Math.addExact(source.position(), Math.multiplyExact(count, Float.BYTES)));
        return result;
    }

    private static int[] integers(ByteBuffer source, int count) {
        int[] result = new int[count];
        source.asIntBuffer().get(result);
        source.position(Math.addExact(source.position(), Math.multiplyExact(count, Integer.BYTES)));
        return result;
    }
}
