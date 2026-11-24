package com.kltyton.lumenfx.effect.render.mesh.nanite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One native disk page and the updates that publish its clusters into the hierarchy. */
public final class NanitePage {
    private static final int FIXUP_MAGIC = 0x464e;
    private static final int FIXUP_HEADER_BYTES = 24;
    private static final int GROUP_BYTES = 16;
    private static final int PART_BYTES = 12;
    private static final int PARENT_BYTES = 10;
    private static final int DISK_HEADER_BYTES = 20;
    private static final int CLUSTER_DISK_HEADER_BYTES = 40;

    public record Group(int pageRange, int flags, int firstPart, int parts, int firstParent, int parents) { }
    public record Part(int page, int firstCluster, int leafCounter, int firstLocation, int locations) { }
    public record Parent(int page, int partPage, int part, int firstCluster, int clusters) { }
    public record HierarchyLocation(int node, int child) {
        public int childReferenceOffset() { return node * NaniteMeshAsset.HIERARCHY_NODE_BYTES + 140 + child * 16; }
        public int lodErrorOffset() { return node * NaniteMeshAsset.HIERARCHY_NODE_BYTES + 76 + child * 16; }
    }

    private final ByteBuffer source, disk;
    private final int groupCount, partCount, clusters, reconsiderCount, parentCount, locationCount, clusterIndexCount;
    private final int groupOffset, partOffset, parentOffset, locationOffset, reconsiderOffset, clusterIndexOffset;

    static NanitePage read(ByteBuffer bytes, int fixupSize) throws IOException {
        try {
            return new NanitePage(bytes, fixupSize);
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new IOException("Invalid native Nanite page", failure);
        }
    }

    private NanitePage(ByteBuffer bytes, int fixupSize) {
        source = bytes.slice(0, fixupSize).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        disk = bytes.slice(fixupSize, bytes.limit() - fixupSize).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        if (source.limit() < FIXUP_HEADER_BYTES || u16(0) != FIXUP_MAGIC)
            throw new IllegalArgumentException("Invalid Nanite fixup header");
        groupCount = u16(2);
        partCount = u16(4);
        clusters = u16(6);
        reconsiderCount = u16(8);
        parentCount = source.getInt(12);
        locationCount = source.getInt(16);
        clusterIndexCount = source.getInt(20);
        groupOffset = FIXUP_HEADER_BYTES;
        partOffset = end(groupOffset, groupCount, GROUP_BYTES);
        parentOffset = end(partOffset, partCount, PART_BYTES);
        locationOffset = end(parentOffset, parentCount, PARENT_BYTES);
        reconsiderOffset = end(locationOffset, locationCount, Integer.BYTES);
        clusterIndexOffset = end(reconsiderOffset, reconsiderCount, Short.BYTES);
        if (end(clusterIndexOffset, clusterIndexCount, 1) != fixupSize || clusters == 0
                || (disk.limit() & 3) != 0 || disk.limit() < DISK_HEADER_BYTES + clusters * CLUSTER_DISK_HEADER_BYTES
                || disk.getInt(0) != clusters)
            throw new IllegalArgumentException("Nanite fixup and disk page sizes disagree");
        for (int index = 0; index < groupCount; index++) {
            var group = group(index);
            range(group.firstPart(), group.parts(), partCount);
            range(group.firstParent(), group.parents(), parentCount);
        }
        for (int index = 0; index < partCount; index++) {
            var part = part(index);
            range(part.firstLocation(), part.locations(), locationCount);
        }
        for (int index = 0; index < parentCount; index++) {
            var parent = parent(index);
            range(parent.firstCluster(), parent.clusters(), clusterIndexCount);
        }
    }

    public int clusters() { return clusters; }
    public int groupCount() { return groupCount; }
    public int reconsiderCount() { return reconsiderCount; }
    public ByteBuffer disk() { return disk.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }

    public Group group(int index) {
        int offset = offset(index, groupCount, groupOffset, GROUP_BYTES);
        return new Group(source.getInt(offset), source.getInt(offset + 4),
                u16(offset + 8), u16(offset + 10), u16(offset + 12), u16(offset + 14));
    }

    public Part part(int index) {
        int offset = offset(index, partCount, partOffset, PART_BYTES);
        return new Part(u16(offset), u8(offset + 2), u8(offset + 3), source.getInt(offset + 4), source.getInt(offset + 8));
    }

    public Parent parent(int index) {
        int offset = offset(index, parentCount, parentOffset, PARENT_BYTES);
        return new Parent(u16(offset), u16(offset + 2), u16(offset + 4), u16(offset + 8), u16(offset + 6));
    }

    public HierarchyLocation location(int index) {
        int value = source.getInt(offset(index, locationCount, locationOffset, Integer.BYTES));
        return new HierarchyLocation(value >>> 2, value & 3);
    }

    public int reconsider(int index) {
        return u16(offset(index, reconsiderCount, reconsiderOffset, Short.BYTES));
    }

    public int clusterIndex(int index) {
        return u8(offset(index, clusterIndexCount, clusterIndexOffset, 1));
    }

    private int end(int offset, int count, int stride) {
        if (count < 0 || count > (source.limit() - offset) / stride)
            throw new IllegalArgumentException("Nanite fixup table extends beyond the page");
        return offset + count * stride;
    }

    private static void range(int first, int count, int length) {
        if (first < 0 || count < 0 || (long)first + count > length)
            throw new IllegalArgumentException("Nanite fixup references an unavailable range");
    }

    private static int offset(int index, int count, int first, int stride) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
        return first + index * stride;
    }

    private int u16(int offset) { return Short.toUnsignedInt(source.getShort(offset)); }
    private int u8(int offset) { return Byte.toUnsignedInt(source.get(offset)); }
}
