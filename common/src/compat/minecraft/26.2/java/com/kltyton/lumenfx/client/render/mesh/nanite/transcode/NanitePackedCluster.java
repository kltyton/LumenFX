package com.kltyton.lumenfx.client.render.mesh.nanite.transcode;

import java.nio.ByteBuffer;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.field;

/** Source SOA cluster header shared by the disk prefix and the GPU page. */
final class NanitePackedCluster {
    static final int GPU_HEADER_BYTES = 16;
    static final int CLUSTER_BYTES = 128;
    static final int MAX_VERTICES = 256;
    static final int MAX_TRIANGLES = 128;
    private final ByteBuffer page;
    private final int pageStart, index, clusters;

    NanitePackedCluster(ByteBuffer page, int pageStart, int index, int clusters) {
        if (index < 0 || index >= clusters) throw new IllegalArgumentException("Invalid packed Nanite cluster index");
        this.page = page;
        this.pageStart = pageStart;
        this.index = index;
        this.clusters = clusters;
    }

    int vertices() { return field(word(0, 0), 0, 14); }
    int positionOffset() { return field(word(0, 0), 14, 18); }
    int triangles() { return field(word(0, 1), 0, 8); }
    int indexOffset() { return field(word(0, 1), 8, 24); }
    int colorMinimum() { return word(0, 2); }
    int colorBits(int channel) { return field(word(0, 3), channel * 4, 4); }
    int positionStart(int axis) { return word(1, axis); }
    int indexBits() { return field(word(1, 3), 0, 3) + 1; }
    int positionBits(int axis) { return field(word(1, 3), 9 + axis * 5, 5); }
    int positionBits() { return positionBits(0) + positionBits(1) + positionBits(2); }
    int normalBits() { return field(word(1, 3), 24, 4); }
    int tangentBits() { return (word(5, 1) & (1 << 22)) == 0 ? 0 : field(word(1, 3), 28, 4) + 1; }
    int flagsOffset() { return GPU_HEADER_BYTES + (4 * clusters + index) * 16 + 12; }
    int attributeOffset() { return field(word(5, 0), 0, 22); }
    int attributeBits() { return field(word(5, 0), 22, 10); }
    int decodeInfoOffset() { return field(word(5, 1), 0, 22); }
    int texCoords() { return field(word(5, 1), 24, 3); }
    boolean variableColors() { return (word(5, 1) & (1 << 27)) != 0; }
    int curves() { return field(word(6, 2), 0, 8); }
    int curveRadiusBits() { return curves() == 0 || (word(6, 2) & (1 << 30)) == 0 ? 0 : field(word(6, 2), 26, 4); }
    boolean voxel() { return triangles() == 0 && curves() == 0; }

    private int word(int block, int lane) {
        return page.getInt(pageStart + GPU_HEADER_BYTES + (block * clusters + index) * 16 + lane * Integer.BYTES);
    }
}
