package com.kltyton.lumenfx.client.render.mesh.nanite.transcode;

import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NanitePage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.IntFunction;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.field;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.zigZag;

/** Transcodes the pinned disk format into the packed page format consumed by Nanite. */
public final class NanitePageTranscoder {
    public static final int ROOT_PAGE_BYTES = 1 << 15;
    public static final int STREAM_PAGE_BYTES = 1 << 17;
    private static final int PAGE_HEADER_BYTES = 20;
    private static final int CLUSTER_HEADER_BYTES = 40;

    record DiskCluster(int decodeInfo, int indexData, int pageClusterMap, int refData, int low, int mid, int high,
                       int references, int previousReferences, int previousVertices) {
        static DiskCluster read(ByteBuffer source, int index) {
            int offset = PAGE_HEADER_BYTES + index * CLUSTER_HEADER_BYTES;
            return new DiskCluster(source.getInt(offset), source.getInt(offset + 4), source.getInt(offset + 8), source.getInt(offset + 12),
                    source.getInt(offset + 16), source.getInt(offset + 20), source.getInt(offset + 24), source.getInt(offset + 28),
                    source.getInt(offset + 32), source.getInt(offset + 36));
        }
    }

    private NanitePageTranscoder() { }

    public static ByteBuffer transcode(NaniteMeshAsset asset, int pageIndex, NanitePage page,
                                       IntFunction<ByteBuffer> resolvedDependencies) {
        var disk = page.disk();
        int clusters = page.clusters();
        int prefix = PAGE_HEADER_BYTES + CLUSTER_HEADER_BYTES * clusters;
        int rawSize = Math.multiplyExact(disk.getInt(4), 16);
        int outputSize = asset.root(pageIndex) ? ROOT_PAGE_BYTES : STREAM_PAGE_BYTES;
        if (rawSize < NanitePackedCluster.GPU_HEADER_BYTES + NanitePackedCluster.CLUSTER_BYTES * clusters
                || rawSize > outputSize || rawSize > disk.limit() - prefix)
            throw new IllegalArgumentException("Invalid Nanite packed page prefix");
        var output = ByteBuffer.allocate(outputSize).order(ByteOrder.LITTLE_ENDIAN);
        output.put(0, disk, prefix, rawSize);
        if (Short.toUnsignedInt(output.getShort(0)) != clusters)
            throw new IllegalArgumentException("Invalid Nanite GPU page cluster count");

        for (int index = 0; index < clusters; index++) {
            var header = DiskCluster.read(disk, index);
            var cluster = new NanitePackedCluster(output, 0, index, clusters);
            int vertexCount = cluster.vertices();
            if (vertexCount > NanitePackedCluster.MAX_VERTICES || cluster.triangles() > NanitePackedCluster.MAX_TRIANGLES
                    || cluster.texCoords() > 4 || header.references() < 0 || header.references() > vertexCount)
                throw new IllegalArgumentException("Invalid Nanite cluster channel counts");
            int stripMask = disk.getInt(12) + index * (NanitePackedCluster.MAX_TRIANGLES / 32) * 12;
            new NaniteStripIndices(disk, stripMask, header.indexData(), header.previousReferences(), header.previousVertices()).write(output, cluster);
            NaniteVertexStreams.writeIndependent(disk, output, cluster, header, nonReferences(disk, index, vertexCount, header.references()));
        }

        var state = asset.pages().get(pageIndex);
        for (int index = 0; index < clusters; index++) {
            var header = DiskCluster.read(disk, index);
            if (header.references() != 0) {
                var cluster = new NanitePackedCluster(output, 0, index, clusters);
                copyReferences(asset, state, disk, output, index, cluster, header, resolvedDependencies);
            }
        }
        return output.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int[] nonReferences(ByteBuffer disk, int cluster, int vertices, int refs) {
        var indices = new int[vertices - refs];
        int count = 0;
        for (int vertex = 0; vertex < vertices; vertex++) {
            if (reference(disk, cluster, vertex)) continue;
            if (count == indices.length) throw new IllegalArgumentException("Nanite reference bitmask count disagrees");
            indices[count++] = vertex;
        }
        if (count != indices.length) throw new IllegalArgumentException("Nanite reference bitmask count disagrees");
        return indices;
    }

    private static boolean reference(ByteBuffer disk, int cluster, int vertex) {
        int offset = disk.getInt(16) + cluster * (NanitePackedCluster.MAX_VERTICES / 8) + vertex / 8;
        return (disk.get(offset) & (1 << (vertex & 7))) != 0;
    }

    private static void copyReferences(NaniteMeshAsset asset, NaniteMeshAsset.PageState state, ByteBuffer disk, ByteBuffer output,
                                       int clusterIndex, NanitePackedCluster cluster, DiskCluster header, IntFunction<ByteBuffer> dependencies) {
        int ordinal = 0, previousVertex = 0;
        int refVertexBase = header.refData() + disk.getInt(8);
        for (int vertex = 0; vertex < cluster.vertices(); vertex++) {
            if (!reference(disk, clusterIndex, vertex)) continue;
            int pageCluster = Byte.toUnsignedInt(disk.get(header.refData() + ordinal));
            int source = disk.getInt(header.pageClusterMap() + pageCluster * Integer.BYTES);
            int sourcePage = source >>> 8, sourceCluster = field(source, 0, 8);
            previousVertex += zigZag(Byte.toUnsignedInt(disk.get(refVertexBase + ordinal)));
            int sourceVertex = previousVertex & 0xff;
            ByteBuffer sourceBuffer = output;
            if (sourcePage > 0) {
                if (!state.relativeEncoding()) throw new IllegalArgumentException("Unexpected cross-page Nanite vertex reference");
                int dependency = asset.dependency(state, sourcePage - 1);
                sourceBuffer = Objects.requireNonNull(dependencies.apply(dependency), "Missing decoded Nanite page dependency");
            }
            int sourceClusters = Short.toUnsignedInt(sourceBuffer.getShort(0));
            var reference = new NanitePackedCluster(sourceBuffer, 0, sourceCluster, sourceClusters);
            if (sourceVertex >= reference.vertices()) throw new IllegalArgumentException("Invalid Nanite vertex reference");
            NaniteVertexStreams.writeReference(disk, output, sourceBuffer, cluster, reference, header, vertex, sourceVertex);
            ordinal++;
        }
        if (ordinal != header.references()) throw new IllegalArgumentException("Nanite reference bitmask count disagrees");
    }

    public static void clearStreamingLeaf(ByteBuffer output, int cluster) {
        int clusters = Short.toUnsignedInt(output.getShort(0));
        int offset = new NanitePackedCluster(output, 0, cluster, clusters).flagsOffset();
        output.putInt(offset, output.getInt(offset) & ~2);
    }
}
