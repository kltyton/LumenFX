package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Native tile directories and payload ranges. Reading the directory does not load physical tiles. */
public final class VirtualTexturePages {
    public record Layout(int width, int height, int blocksX, int blocksY, int pagesX, int pagesY,
                         int tileSize, int border, int maxAnisotropy, int physicalAnisotropy,
                         int filter, int addressU, int addressV) {
        public int physicalSize() { return tileSize + border * 2; }
    }
    public record Layer(TextureStorageFormat format, boolean srgb, int fallbackColor, int offset, int bytes) { }
    public record Page(int mip, int x, int y) {
        public Page parent() { return new Page(mip + 1, x >> 1, y >> 1); }
    }
    public record Range(long offset, int bytes) { }

    private record Level(int width, int height, int chunk, long base, int[] addresses, int[] offsets) {
        int index(int x, int y) {
            int address = morton(x) | (morton(y) << 1);
            int index = Arrays.binarySearch(addresses, address);
            return index < 0 ? -index - 2 : index;
        }
        private static int morton(int value) {
            value = (value | value << 8) & 0x00ff00ff;
            value = (value | value << 4) & 0x0f0f0f0f;
            value = (value | value << 2) & 0x33333333;
            return (value | value << 1) & 0x55555555;
        }
    }

    private final Layout layout;
    private final List<Layer> layers;
    private final List<Level> levels;
    private final int tileStride;
    private final long[] chunks;

    private VirtualTexturePages(Layout layout, List<Layer> layers, List<Level> levels, int tileStride, long[] chunks) {
        this.layout = layout;
        this.layers = List.copyOf(layers);
        this.levels = List.copyOf(levels);
        this.tileStride = tileStride;
        this.chunks = chunks;
    }

    public Layout layout() { return layout; }
    public List<Layer> layers() { return layers; }
    public int mips() { return levels.size(); }
    public long length() { return chunks[chunks.length - 1]; }

    public @Nullable Range range(Page page, int layerIndex) {
        if (page.mip() < 0 || page.mip() >= levels.size()) return null;
        var level = levels.get(page.mip());
        if (level.base() < 0 || page.x() < 0 || page.y() < 0 || page.x() >= level.width() || page.y() >= level.height())
            return null;
        int index = level.index(page.x(), page.y());
        if (index < 0 || level.offsets()[index] == -1) return null;
        int address = Level.morton(page.x()) | (Level.morton(page.y()) << 1);
        long tile = Integer.toUnsignedLong(level.offsets()[index]) + (address - level.addresses()[index]);
        var layer = layers.get(layerIndex);
        return new Range(chunks[level.chunk()] + level.base() + tile * tileStride + layer.offset(), layer.bytes());
    }

    public static VirtualTexturePages read(InputStream stream, byte[] metadataDigest) throws IOException {
        Header input = new Header(stream);
        if (input.word() != 0x5456464c || input.word() != 1 || !MessageDigest.isEqual(input.bytes(32), metadataDigest))
            throw new IOException("Virtual texture tile stream does not match its metadata");

        int width = input.positive(Integer.MAX_VALUE), height = input.positive(Integer.MAX_VALUE);
        int mips = input.positive(16), blocksX = input.positive(32768), blocksY = input.positive(32768);
        int tile = input.positive(32768), border = input.bounded(tile), layerCount = input.positive(8);
        int chunkCount = input.positive(mips), stride = input.positive(Integer.MAX_VALUE);
        int pagesX = input.positive(32768), pagesY = input.positive(32768), maxAnisotropy = input.bounded(Integer.MAX_VALUE);
        int physicalAnisotropy = input.bounded(Math.min(maxAnisotropy, border));
        int filter = input.bounded(4), addressU = input.bounded(2), addressV = input.bounded(2);
        if (Integer.bitCount(tile) != 1 || pagesX % blocksX != 0 || pagesY % blocksY != 0 || (filter >= 3 && physicalAnisotropy == 0))
            throw new IOException("Invalid virtual texture page allocation");
        var layout = new Layout(width, height, blocksX, blocksY, pagesX, pagesY, tile, border, maxAnisotropy,
                physicalAnisotropy, filter, addressU, addressV);
        List<Layer> layers = new ArrayList<>(layerCount);
        int layerOffset = 0;
        for (int layer = 0; layer < layerCount; layer++) {
            TextureStorageFormat format;
            try {
                format = TextureStorageFormat.from(new String(input.bytes(input.positive(64)), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Unsupported native virtual texture layer " + layer, invalid);
            }
            boolean srgb = input.bounded(1) != 0;
            int fallback = input.word(), offset = input.bounded(stride), bytes = input.positive(stride);
            if (offset != layerOffset || bytes > stride - offset
                    || bytes != format.byteSize(layout.physicalSize(), layout.physicalSize())
                    || format.compressed() && layout.physicalSize() % 4 != 0)
                throw new IOException("Invalid virtual texture physical layer size");
            layers.add(new Layer(format, srgb, fallback, offset, bytes));
            layerOffset += bytes;
        }
        if (layerOffset != stride) throw new IOException("Virtual texture physical layers do not fill the tile stride");

        List<Level> levels = new ArrayList<>(mips);
        long[] requiredChunkSizes = new long[chunkCount];
        for (int mip = 0; mip < mips; mip++) {
            int tilesX = input.positive(32768), tilesY = input.positive(32768), maxAddress = input.positive(1 << 30);
            int chunk = input.bounded(chunkCount - 1);
            int baseWord = input.word();
            long base = baseWord == -1 ? -1 : Integer.toUnsignedLong(baseWord);
            int runs = input.bounded(maxAddress);
            int[] addresses = new int[runs], offsets = new int[runs];
            for (int run = 0; run < runs; run++) {
                addresses[run] = input.bounded(maxAddress - 1);
                offsets[run] = input.word();
                if (run == 0 ? addresses[run] != 0 : addresses[run] <= addresses[run - 1])
                    throw new IOException("Invalid virtual texture Morton runs");
            }
            if (base >= 0) for (int run = 0; run < runs; run++) {
                if (offsets[run] == -1) continue;
                int end = run + 1 == runs ? maxAddress : addresses[run + 1];
                long endTile = Integer.toUnsignedLong(offsets[run]) + (end - addresses[run]);
                requiredChunkSizes[chunk] = Math.max(requiredChunkSizes[chunk], Math.addExact(base, Math.multiplyExact(endTile, stride)));
            }
            levels.add(new Level(tilesX, tilesY, chunk, base, addresses, offsets));
        }

        long[] chunks = new long[chunkCount + 1];
        long offset = input.position + (long)chunkCount * Integer.BYTES;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            long size = Integer.toUnsignedLong(input.word());
            if (size == 0 || size < requiredChunkSizes[chunk]) throw new IOException("Virtual texture chunk truncates a physical tile");
            chunks[chunk] = offset;
            offset += size;
        }
        chunks[chunkCount] = offset;
        return new VirtualTexturePages(layout, layers, levels, stride, chunks);
    }

    private static final class Header {
        private final DataInputStream source;
        private long position;
        Header(InputStream source) { this.source = new DataInputStream(source); }
        int word() throws IOException {
            int value = Integer.reverseBytes(source.readInt());
            position += Integer.BYTES;
            return value;
        }
        byte[] bytes(int size) throws IOException {
            byte[] bytes = source.readNBytes(size);
            if (bytes.length != size) throw new EOFException("Truncated virtual texture directory");
            position += size;
            return bytes;
        }
        int bounded(int maximum) throws IOException {
            int value = word();
            if (value < 0 || value > maximum) throw new IOException("Invalid virtual texture directory field");
            return value;
        }
        int positive(int maximum) throws IOException {
            int value = bounded(maximum);
            if (value == 0) throw new IOException("Missing virtual texture directory field");
            return value;
        }
    }
}
