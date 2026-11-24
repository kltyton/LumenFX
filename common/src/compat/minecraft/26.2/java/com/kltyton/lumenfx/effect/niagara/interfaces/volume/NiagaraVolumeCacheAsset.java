package com.kltyton.lumenfx.effect.niagara.interfaces.volume;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/** Frames of a native half RGBA volume cache, stored in source texture order. */
public record NiagaraVolumeCacheAsset(int width, int height, int depth, Map<Integer, Frame> frames) {
    public NiagaraVolumeCacheAsset {
        bytes(width, height, depth);
        frames = Map.copyOf(frames);
    }

    public @Nullable Frame frame(int index) { return frames.get(index); }
    public int frameBytes() { return bytes(width, height, depth); }

    private static int bytes(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Invalid Niagara Volume Cache extent");
        return Math.multiplyExact(Math.multiplyExact(width, height), Math.multiplyExact(depth, 8));
    }

    public static final class Frame {
        private final byte[] compressed;

        public Frame(byte[] compressed) { this.compressed = compressed.clone(); }

        public void unpack(ByteBuffer output, int bytes) {
            try (var input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                byte[] chunk = new byte[Math.min(bytes, 65536)];
                for (int remaining = bytes; remaining > 0; ) {
                    int count = input.readNBytes(chunk, 0, Math.min(chunk.length, remaining));
                    if (count == 0) throw new IOException("Truncated Niagara Volume Cache frame");
                    output.put(chunk, 0, count);
                    remaining -= count;
                }
                if (input.read() != -1) throw new IOException("Niagara Volume Cache frame exceeds its declared dimensions");
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not decode Niagara Volume Cache pixels", failure);
            }
        }
    }
}
