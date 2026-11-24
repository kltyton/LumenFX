package com.kltyton.lumenfx.client.render.mesh.nanite.transcode;

import java.nio.ByteBuffer;

final class NaniteBitStream {
    private final ByteBuffer bytes;
    private int bit;

    NaniteBitStream(ByteBuffer bytes, int byteOffset, int bitOffset) {
        this.bytes = bytes;
        this.bit = Math.addExact(Math.multiplyExact(byteOffset, 8), bitOffset);
    }

    int read(int width) {
        int word = 0;
        for (int done = 0; done < width;) {
            int withinByte = bit & 7;
            int count = Math.min(width - done, 8 - withinByte);
            int data = Byte.toUnsignedInt(bytes.get(bit >>> 3));
            word |= field(data, withinByte, count) << done;
            bit += count;
            done += count;
        }
        return word;
    }

    void write(int word, int width) {
        for (int done = 0; done < width;) {
            int address = bit >>> 3;
            int withinByte = bit & 7;
            int count = Math.min(width - done, 8 - withinByte);
            int mask = mask(count) << withinByte;
            int original = Byte.toUnsignedInt(bytes.get(address));
            int value = field(word, done, count) << withinByte;
            bytes.put(address, (byte)((original & ~mask) | value));
            bit += count;
            done += count;
        }
    }

    static int field(int word, int start, int width) { return (word >>> start) & mask(width); }
    static int mask(int width) { return (int)((1L << width) - 1L); }
    static int zigZag(int delta) { return (delta >>> 1) ^ -(delta & 1); }
}
