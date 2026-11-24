package com.kltyton.lumenfx.client.render.mesh.nanite.transcode;

import java.nio.ByteBuffer;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.field;
import static com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NaniteBitStream.zigZag;

final class NaniteVertexStreams {
    private record Uv(int uBits, int vBits, int uMinimum, int vMinimum) {
        static Uv read(ByteBuffer page, int offset, int channel) {
            int u = page.getInt(offset + channel * 8), v = page.getInt(offset + channel * 8 + 4);
            return new Uv(field(u, 0, 5), field(v, 0, 5), u >>> 5, v >>> 5);
        }
    }

    private static final class Deltas {
        final int low, mid, high, bytes;
        final int[] value;

        Deltas(int low, int mid, int high, int bytes, int[] value) {
            if (bytes > 3) throw new IllegalArgumentException("Invalid Nanite delta component width");
            this.low = low; this.mid = mid; this.high = high; this.bytes = bytes; this.value = value;
        }

        void write(ByteBuffer disk, int ordinal, NaniteBitStream output, int[] widths) {
            int offset = ordinal * value.length;
            for (int channel = 0; channel < value.length; channel++) {
                int address = offset + channel;
                int delta = bytes >= 1 ? Byte.toUnsignedInt(disk.get(low + address)) : 0;
                if (bytes >= 2) delta |= Byte.toUnsignedInt(disk.get(mid + address)) << 8;
                if (bytes >= 3) delta |= Byte.toUnsignedInt(disk.get(high + address)) << 16;
                value[channel] += zigZag(delta);
                output.write(value[channel], widths[channel]);
            }
        }
    }

    private static final class DiskStreams {
        int low, mid, high;
        final int vertices;

        DiskStreams(NanitePageTranscoder.DiskCluster header, int vertices) {
            low = header.low(); mid = header.mid(); high = header.high();
            this.vertices = vertices;
        }

        Deltas next(int[] widths, int[] seed) {
            int maximum = 0;
            for (int width : widths) maximum = Math.max(maximum, width);
            int bytes = (maximum + 7) / 8;
            var stream = new Deltas(low, mid, high, bytes, seed);
            int length = vertices * widths.length;
            if (bytes >= 1) low += length;
            if (bytes >= 2) mid += length;
            if (bytes >= 3) high += length;
            return stream;
        }
    }

    private NaniteVertexStreams() { }

    static void writeIndependent(ByteBuffer disk, ByteBuffer output, NanitePackedCluster cluster,
                                 NanitePageTranscoder.DiskCluster header, int[] vertices) {
        var streams = new DiskStreams(header, vertices.length);
        int[] position = {cluster.positionBits(0), cluster.positionBits(1), cluster.positionBits(2)};
        int[] initialPosition = {(1 << position[0]) >>> 1, (1 << position[1]) >>> 1, (1 << position[2]) >>> 1};
        var positions = streams.next(position, initialPosition);

        var widths = new java.util.ArrayList<int[]>();
        var attributes = new java.util.ArrayList<Deltas>();
        add(widths, attributes, streams, cluster.normalBits(), cluster.normalBits());
        add(widths, attributes, streams, cluster.tangentBits());
        add(widths, attributes, streams, cluster.curveRadiusBits());
        if (cluster.variableColors()) {
            widths.add(new int[]{cluster.colorBits(0), cluster.colorBits(1), cluster.colorBits(2), cluster.colorBits(3)});
            attributes.add(streams.next(new int[]{8, 8, 8, 8}, new int[4]));
        }
        for (int channel = 0; channel < cluster.texCoords(); channel++) {
            var uv = Uv.read(disk, header.decodeInfo(), channel);
            add(widths, attributes, streams, uv.uBits(), uv.vBits());
        }

        for (int ordinal = 0; ordinal < vertices.length; ordinal++) {
            int vertex = cluster.voxel() ? ordinal : vertices[ordinal];
            if (!cluster.voxel()) {
                var bits = new NaniteBitStream(output, cluster.positionOffset(), vertex * cluster.positionBits());
                positions.write(disk, ordinal, bits, position);
            }
            var bits = new NaniteBitStream(output, cluster.attributeOffset(), vertex * cluster.attributeBits());
            for (int channel = 0; channel < attributes.size(); channel++) attributes.get(channel).write(disk, ordinal, bits, widths.get(channel));
        }
    }

    private static void add(java.util.List<int[]> widths, java.util.List<Deltas> attributes, DiskStreams streams, int... bits) {
        widths.add(bits);
        attributes.add(streams.next(bits, new int[bits.length]));
    }

    static void writeReference(ByteBuffer disk, ByteBuffer output, ByteBuffer source, NanitePackedCluster cluster,
                               NanitePackedCluster reference, NanitePageTranscoder.DiskCluster header, int vertex, int sourceVertex) {
        var sourcePosition = new NaniteBitStream(source, reference.positionOffset(), sourceVertex * reference.positionBits());
        var targetPosition = new NaniteBitStream(output, cluster.positionOffset(), vertex * cluster.positionBits());
        for (int axis = 0; axis < 3; axis++) {
            int local = sourcePosition.read(reference.positionBits(axis)) + reference.positionStart(axis) - cluster.positionStart(axis);
            targetPosition.write(local, cluster.positionBits(axis));
        }

        var sourceAttributes = new NaniteBitStream(source, reference.attributeOffset(), sourceVertex * reference.attributeBits());
        var targetAttributes = new NaniteBitStream(output, cluster.attributeOffset(), vertex * cluster.attributeBits());
        copy(sourceAttributes, targetAttributes, 2 * cluster.normalBits());
        copy(sourceAttributes, targetAttributes, cluster.tangentBits());
        copy(sourceAttributes, targetAttributes, cluster.curveRadiusBits());

        int packedColor = reference.colorMinimum();
        int deltaColor = 0;
        for (int channel = 0; channel < 4; channel++) deltaColor |= sourceAttributes.read(reference.colorBits(channel)) << (channel * 8);
        packedColor += deltaColor;
        if (cluster.variableColors()) {
            int localColor = packedColor - cluster.colorMinimum();
            for (int channel = 0; channel < 4; channel++)
                targetAttributes.write(field(localColor, channel * 8, 8), cluster.colorBits(channel));
        }

        for (int channel = 0; channel < cluster.texCoords(); channel++) {
            var from = Uv.read(source, reference.decodeInfoOffset(), channel);
            var to = Uv.read(disk, header.decodeInfo(), channel);
            targetAttributes.write(sourceAttributes.read(from.uBits()) + from.uMinimum() - to.uMinimum(), to.uBits());
            targetAttributes.write(sourceAttributes.read(from.vBits()) + from.vMinimum() - to.vMinimum(), to.vBits());
        }
    }

    private static void copy(NaniteBitStream source, NaniteBitStream destination, int bits) {
        destination.write(source.read(bits), bits);
    }
}
