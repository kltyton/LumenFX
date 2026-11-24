package com.kltyton.lumenfx.client.render.mesh.nanite.transcode;

import java.nio.ByteBuffer;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.field;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.mask;

final class NaniteStripIndices {
    private final ByteBuffer disk;
    private final int masks, data, priorReferences, priorVertices;

    NaniteStripIndices(ByteBuffer disk, int masks, int data, int priorReferences, int priorVertices) {
        this.disk = disk;
        this.masks = masks;
        this.data = data;
        this.priorReferences = priorReferences;
        this.priorVertices = priorVertices;
    }

    void write(ByteBuffer output, NanitePackedCluster cluster) {
        var packed = new NaniteBitStream(output, cluster.indexOffset(), 0);
        int indexBits = cluster.indexBits();
        int[] vertices = new int[3];
        for (int triangle = 0; triangle < cluster.triangles(); triangle++) {
            triangle(triangle, vertices);
            int a = vertices[0], b = vertices[1], c = vertices[2];
            int base = a, next = b, last = c;
            if (b < Math.min(a, c)) { base = b; next = c; last = a; }
            else if (c < Math.min(a, b)) { base = c; next = a; last = b; }
            if (base < 0 || base > mask(indexBits) || next - base > 31 || last - base > 31
                    || Math.max(next, last) >= cluster.vertices())
                throw new IllegalArgumentException("Invalid native Nanite triangle index");
            packed.write(base, indexBits);
            packed.write(next - base, 5);
            packed.write(last - base, 5);
        }
    }

    private void triangle(int triangle, int[] output) {
        int wordIndex = triangle >>> 5, bit = triangle & 31;
        int offset = masks + wordIndex * 12;
        int starts = disk.getInt(offset), lefts = disk.getInt(offset + 4), references = disk.getInt(offset + 8);
        int startLeft = starts & lefts;
        int heads = (startLeft | ~starts) & references;
        int beforeReferences = wordIndex == 0 ? 0 : field(priorReferences, (wordIndex - 1) * 10, 10);
        int beforeVertices = wordIndex == 0 ? 0 : field(priorVertices, (wordIndex - 1) * 10, 10);
        int inWordReferences = referencesBefore(startLeft, references, bit);
        int refIndex = beforeReferences + inWordReferences;
        int newIndex = beforeVertices + 2 * Integer.bitCount(starts & mask(bit)) + bit - inWordReferences;
        boolean start = field(starts, bit, 1) != 0;
        boolean left = field(lefts, bit, 1) != 0;
        boolean ref = field(references, bit, 1) != 0;
        if (start) {
            int refs = (left ? 2 : 0) + (ref ? 1 : 0);
            int bits = new NaniteBitStream(disk, data, refIndex * 5).read(refs * 5);
            for (int corner = 0; corner < 3; corner++) {
                output[corner] = corner < refs ? newIndex - 1 - field(bits, corner * 5, 5) : newIndex + corner - refs;
            }
            return;
        }

        if (bit == 0) throw new IllegalArgumentException("Nanite strip crosses a start boundary");
        int bits = new NaniteBitStream(disk, data, (refIndex - 1) * 5).read(10);
        boolean previousStart = field(starts, bit - 1, 1) != 0;
        int previousNew = previousStart ? 3 - (2 * field(lefts, bit - 1, 1) + field(references, bit - 1, 1)) : 0;
        int second = newIndex - 1;
        if (field(heads, bit - 1, 1) != 0) second += previousNew - field(bits, 0, 5);
        int third = ref ? newIndex - 1 - field(bits, 5, 5) : newIndex;

        int search = (starts | (left ? ~lefts : lefts)) & mask(bit);
        if (search == 0) throw new IllegalArgumentException("Nanite strip has no preceding edge");
        int found = 31 - Integer.numberOfLeadingZeros(search);
        boolean foundStart = field(starts, found, 1) != 0;
        int foundWordRefs = referencesBefore(startLeft, references, found);
        int foundRefs = beforeReferences + foundWordRefs;
        int foundNew = beforeVertices + 2 * Integer.bitCount(starts & mask(found)) + found - foundWordRefs;
        int foundTriangleRefs = 2 * field(lefts, found, 1) + field(references, found, 1);
        boolean usesRef = foundStart ? foundTriangleRefs >= (left ? 2 : 1) : field(heads, found - 1, 1) != 0;
        if (usesRef) {
            int readOffset = foundStart ? (left ? -1 : 0) : 1;
            output[0] = foundNew - 1 - new NaniteBitStream(disk, data, (foundRefs - readOffset) * 5).read(5);
        } else output[0] = foundNew + (foundStart ? (left && foundTriangleRefs == 0 ? 1 : 0) : -1);
        output[1] = left ? third : second;
        output[2] = left ? second : third;
    }

    private static int referencesBefore(int startLeft, int references, int bit) {
        int prior = mask(bit);
        return 2 * Integer.bitCount(startLeft & prior) + Integer.bitCount(references & prior);
    }
}
