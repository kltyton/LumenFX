package com.kltyton.lumenfx.effect.asset.mesh.nanite;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset.Header;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset.PageState;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;

public final class NaniteMeshAssetLoader {
    private static final int MAGIC = 0x544e464c;
    private static final int PAGE_STATE_BYTES = 20;
    private static final int ASSEMBLY_TRANSFORM_BYTES = 12 * Float.BYTES;

    private NaniteMeshAssetLoader() { }

    public static NaniteMeshAsset load(ResourceProvider resources, Identifier indexPath) throws IOException {
        byte[] data;
        try (var stream = resources.getResourceOrThrow(indexPath).open()) { data = stream.readAllBytes(); }
        try {
            var index = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (index.getInt() != MAGIC) throw new IllegalArgumentException("Invalid Nanite index header");
            int version = index.getInt();
            if (version != 1 && version != 2) throw new IllegalArgumentException("Invalid Nanite index version");
            int flags = index.getInt(), optional = index.getInt();
            if (optional != 0 && optional != 1) throw new IllegalArgumentException("Invalid Nanite payload flags");
            int roots = index.getInt(), clusters = index.getInt(), triangles = index.getInt(), vertices = index.getInt(), curves = index.getInt();
            int position = index.getInt(), normal = index.getInt(), tangent = index.getInt();
            long voxelMask = index.getLong();
            var bounds = new Bounds(vector(index), vector(index), index.getFloat());
            int persistentHash = version == 2 ? index.getInt() : 0;
            if (version == 2 && persistentHash == 0) throw new IllegalArgumentException("Missing Nanite persistent asset hash");
            int pageCount = index.getInt();
            if (roots < 1 || roots > pageCount || pageCount > index.remaining() / PAGE_STATE_BYTES
                    || clusters <= 0 || triangles < 0 || vertices < 0 || curves < 0)
                throw new IllegalArgumentException("Invalid Nanite page or source mesh counts");
            var header = new Header(flags, optional != 0, roots, clusters, triangles, vertices, curves, position, normal, tangent, voxelMask, bounds);

            var pages = new ArrayList<PageState>(pageCount);
            for (int page = 0; page < pageCount; page++) {
                int offset = index.getInt(), size = index.getInt(), pageSize = index.getInt(), start = index.getInt();
                int count = Short.toUnsignedInt(index.getShort()), depth = Byte.toUnsignedInt(index.get()), pageFlags = Byte.toUnsignedInt(index.get());
                if (offset < 0 || pageSize <= 0 || size <= pageSize || start < 0 || (pageFlags & ~1) != 0)
                    throw new IllegalArgumentException("Invalid Nanite page state " + page);
                pages.add(new PageState(offset, size, pageSize, start, count, depth, pageFlags));
            }
            var hierarchy = array(index, NaniteMeshAsset.HIERARCHY_NODE_BYTES);
            var rootOffsets = array(index, Integer.BYTES);
            var dependencies = array(index, Short.BYTES);
            var transforms = array(index, ASSEMBLY_TRANSFORM_BYTES);
            var boneAttachments = array(index, Integer.BYTES);
            var boneIndices = array(index, Integer.BYTES);
            var rangeLookup = array(index, Integer.BYTES);
            var rootData = array(index, 1);
            if (index.hasRemaining() || hierarchy.remaining() == 0 || rootOffsets.remaining() == 0)
                throw new IllegalArgumentException("Invalid Nanite hierarchy index");
            for (int offset = 0; offset < rootOffsets.limit(); offset += Integer.BYTES) {
                int node = rootOffsets.getInt(offset);
                if (node < 0 || node >= hierarchy.limit() / NaniteMeshAsset.HIERARCHY_NODE_BYTES)
                    throw new IllegalArgumentException("Nanite hierarchy root is out of range");
            }
            for (int offset = 0; offset < dependencies.limit(); offset += Short.BYTES) {
                if (Short.toUnsignedInt(dependencies.getShort(offset)) >= pageCount)
                    throw new IllegalArgumentException("Nanite page dependency is out of range");
            }
            for (int page = 0; page < pageCount; page++) {
                var state = pages.get(page);
                if ((long)state.dependenciesStart() + state.dependencyCount() > dependencies.limit() / Short.BYTES
                        || page < roots && (long)state.bulkOffset() + state.bulkSize() > rootData.limit())
                    throw new IllegalArgumentException("Nanite page range is out of bounds: " + page);
            }
            var payloads = streaming(resources, indexPath, pageCount - roots, header.optionalStreaming());
            return new NaniteMeshAsset(header, pages, hierarchy, rootOffsets, dependencies, transforms, boneAttachments,
                    boneIndices, rangeLookup, rootData, payloads.isEmpty() ? null : (ordinal, expectedLength) -> {
                try (var stream = payloads.get(ordinal).open()) {
                    byte[] bytes = stream.readNBytes(expectedLength);
                    if (bytes.length != expectedLength || stream.read() != -1)
                        throw new IOException("Invalid Nanite page payload length: " + indexPath + " #" + ordinal);
                    return bytes;
                }
            }, persistentHash);
        } catch (BufferUnderflowException | IllegalArgumentException error) {
            throw new IOException("Cannot read Nanite mesh index " + indexPath, error);
        }
    }

    private static List<Resource> streaming(ResourceProvider resources, Identifier indexPath, int count, boolean optional) throws IOException {
        var folder = indexPath.getPath().substring(0, indexPath.getPath().lastIndexOf('/') + 1);
        var files = new ArrayList<Resource>(count);
        for (int page = 0; page < count; page++) {
            var id = Identifier.fromNamespaceAndPath(indexPath.getNamespace(), folder + "pages/" + page + ".lfnp");
            var resource = resources.getResource(id);
            if (resource.isEmpty()) {
                if (optional) return List.of();
                throw new IOException("Missing required Nanite page " + id);
            }
            files.add(resource.get());
        }
        return List.copyOf(files);
    }

    private static ByteBuffer array(ByteBuffer source, int stride) {
        int count = source.getInt();
        if (count < 0 || count > source.remaining() / stride) throw new IllegalArgumentException("Invalid Nanite index array length");
        int bytes = count * stride;
        var value = source.slice(source.position(), bytes).order(ByteOrder.LITTLE_ENDIAN);
        source.position(source.position() + bytes);
        return value;
    }

    private static Vec3 vector(ByteBuffer index) {
        return new Vec3(index.getFloat(), index.getFloat(), index.getFloat());
    }
}
