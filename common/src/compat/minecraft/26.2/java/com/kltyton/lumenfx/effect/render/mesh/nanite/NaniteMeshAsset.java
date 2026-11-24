package com.kltyton.lumenfx.effect.render.mesh.nanite;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Packed source hierarchy and page payloads produced by the target's Nanite builder. */
public final class NaniteMeshAsset {
    public static final int HIERARCHY_NODE_BYTES = 60 * Integer.BYTES;

    public record Header(int flags, boolean optionalStreaming, int rootPages, int clusters, int sourceTriangles,
                         int sourceVertices, int sourceCurves, int positionPrecision, int normalPrecision,
                         int tangentPrecision, long voxelMaterialMask, Bounds bounds) { }

    public record PageState(int bulkOffset, int bulkSize, int diskPageSize, int dependenciesStart, int dependencyCount,
                            int maxHierarchyDepth, int flags) {
        public int fixupSize() { return bulkSize - diskPageSize; }
        public boolean relativeEncoding() { return (flags & 1) != 0; }
    }

    @FunctionalInterface
    public interface StreamingPayload {
        byte[] read(int ordinal, int expectedLength) throws IOException;
    }

    private final Header header;
    private final int persistentHash;
    private final List<PageState> pages;
    private final ByteBuffer hierarchy, rootOffsets, dependencies, assemblyTransforms, assemblyBoneAttachments, boneIndices;
    private final ByteBuffer pageRangeLookup, rootData;
    private final @Nullable StreamingPayload streamedPages;

    public NaniteMeshAsset(Header header, List<PageState> pages, ByteBuffer hierarchy, ByteBuffer rootOffsets, ByteBuffer dependencies,
                           ByteBuffer assemblyTransforms, ByteBuffer assemblyBoneAttachments, ByteBuffer boneIndices,
                           ByteBuffer pageRangeLookup, ByteBuffer rootData, @Nullable StreamingPayload streamedPages) {
        this(header, pages, hierarchy, rootOffsets, dependencies, assemblyTransforms, assemblyBoneAttachments, boneIndices,
                pageRangeLookup, rootData, streamedPages, 0);
    }

    public NaniteMeshAsset(Header header, List<PageState> pages, ByteBuffer hierarchy, ByteBuffer rootOffsets, ByteBuffer dependencies,
                           ByteBuffer assemblyTransforms, ByteBuffer assemblyBoneAttachments, ByteBuffer boneIndices,
                           ByteBuffer pageRangeLookup, ByteBuffer rootData, @Nullable StreamingPayload streamedPages, int persistentHash) {
        this.header = header;
        this.persistentHash = persistentHash;
        this.pages = List.copyOf(pages);
        this.hierarchy = view(hierarchy);
        this.rootOffsets = view(rootOffsets);
        this.dependencies = view(dependencies);
        this.assemblyTransforms = view(assemblyTransforms);
        this.assemblyBoneAttachments = view(assemblyBoneAttachments);
        this.boneIndices = view(boneIndices);
        this.pageRangeLookup = view(pageRangeLookup);
        this.rootData = view(rootData);
        this.streamedPages = streamedPages;
    }

    public Header header() { return header; }
    public int persistentHash() { return persistentHash; }
    public List<PageState> pages() { return pages; }
    public ByteBuffer hierarchy() { return view(hierarchy); }
    public ByteBuffer rootOffsets() { return view(rootOffsets); }
    public ByteBuffer assemblyTransforms() { return view(assemblyTransforms); }
    public ByteBuffer assemblyBoneAttachments() { return view(assemblyBoneAttachments); }
    public ByteBuffer boneIndices() { return view(boneIndices); }
    public ByteBuffer pageRangeLookup() { return view(pageRangeLookup); }

    public boolean root(int page) { return page >= 0 && page < header.rootPages(); }
    public boolean canLoad(int page) { return page >= 0 && page < pages.size() && (root(page) || streamedPages != null); }
    public int dependency(PageState state, int offset) {
        if (offset < 0 || offset >= state.dependencyCount()) throw new IndexOutOfBoundsException(offset);
        return Short.toUnsignedInt(dependencies.getShort((state.dependenciesStart() + offset) * Short.BYTES));
    }

    public NanitePage readPage(int page) throws IOException {
        var state = pages.get(page);
        ByteBuffer bytes;
        if (root(page)) bytes = view(rootData.slice(state.bulkOffset(), state.bulkSize()));
        else {
            if (streamedPages == null) throw new IOException("Optional Nanite stream is not mounted");
            var payload = streamedPages.read(page - header.rootPages(), state.bulkSize());
            if (payload.length != state.bulkSize()) throw new IOException("Nanite page length differs from the source index");
            bytes = view(ByteBuffer.wrap(payload));
        }
        return NanitePage.read(bytes, state.fixupSize());
    }

    private static ByteBuffer view(ByteBuffer bytes) {
        return bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
}
