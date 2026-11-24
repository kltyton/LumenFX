package com.kltyton.lumenfx.effect.niagara.cache;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Mip-zero volume data with shared physical tiles and separate virtual tile positions. */
public record NiagaraCacheVolumeFrame(int format, int width, int height, int depth, boolean writePixels,
                                      float[] fallback, int tileCount, List<Page> pages, byte[] pixels) {
    public static final int TILE_SIZE = 16;
    public static final NiagaraCacheVolumeFrame ABSENT = new NiagaraCacheVolumeFrame(-1, 0, 0, 0, false,
            new float[4], 0, List.of(), new byte[0]);

    public record Page(int x, int y, int z, int tile) {
        public Page {
            if (x < 0 || y < 0 || z < 0 || (x | y | z) % TILE_SIZE != 0 || tile < 0)
                throw new IllegalArgumentException("Invalid Niagara cache volume page");
        }
    }

    public NiagaraCacheVolumeFrame {
        pages = List.copyOf(pages);
        fallback = fallback.clone();
        pixels = pixels.clone();
        if (fallback.length != 4) throw new IllegalArgumentException("Invalid Niagara cache volume fallback");
        for (float value : fallback) if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid Niagara cache volume fallback");
        if (format == -1) {
            if (width != 0 || height != 0 || depth != 0 || writePixels || tileCount != 0 || !pages.isEmpty() || pixels.length != 0)
                throw new IllegalArgumentException("Absent Niagara cache volume contains data");
        } else {
            if (width < 1 || height < 1 || depth < 1 || tileCount < 0
                    || (long)tileCount * TILE_SIZE * TILE_SIZE * TILE_SIZE * NiagaraCacheInterfaceData.pixelBytes(format) != pixels.length
                    || !writePixels && (tileCount != 0 || !pages.isEmpty()))
                throw new IllegalArgumentException("Invalid Niagara cache volume extent or storage");
            for (var page : pages)
                if (page.x() >= width || page.y() >= height || page.z() >= depth || page.tile() >= tileCount)
                    throw new IllegalArgumentException("Niagara cache volume page is outside its extent");
        }
    }

    @Override public float[] fallback() { return fallback.clone(); }
    @Override public byte[] pixels() { return pixels.clone(); }
    public ByteBuffer pixelData() { return ByteBuffer.wrap(pixels).asReadOnlyBuffer(); }
    public int tileBytes() { return TILE_SIZE * TILE_SIZE * TILE_SIZE * NiagaraCacheInterfaceData.pixelBytes(format); }

    /** Dense capture retains zero tiles in the same portable format used for sparse playback. */
    public static NiagaraCacheVolumeFrame packPixels(int format, int width, int height, int depth, byte[] volume,
                                                     boolean omitZeroTiles) {
        int bytes = NiagaraCacheInterfaceData.pixelBytes(format);
        if (width < 1 || height < 1 || depth < 1 || (long)width * height * depth * bytes != volume.length)
            throw new IllegalArgumentException("Invalid dense Niagara cache volume");
        byte[] tile = new byte[TILE_SIZE * TILE_SIZE * TILE_SIZE * bytes];
        var pages = new ArrayList<Page>();
        var voxels = new ByteArrayOutputStream();
        for (int z = 0; z < depth; z += TILE_SIZE) for (int y = 0; y < height; y += TILE_SIZE)
            for (int x = 0; x < width; x += TILE_SIZE) {
                Arrays.fill(tile, (byte)0);
                boolean keep = !omitZeroTiles;
                int rowBytes = Math.min(TILE_SIZE, width - x) * bytes;
                for (int localZ = 0; localZ < TILE_SIZE && z + localZ < depth; localZ++)
                    for (int localY = 0; localY < TILE_SIZE && y + localY < height; localY++) {
                        int source = (((z + localZ) * height + y + localY) * width + x) * bytes;
                        int destination = ((localZ * TILE_SIZE + localY) * TILE_SIZE) * bytes;
                        System.arraycopy(volume, source, tile, destination, rowBytes);
                        if (!keep) for (int i = 0; i < rowBytes; i++) if (volume[source + i] != 0) {
                            keep = true;
                            break;
                        }
                    }
                if (!keep) continue;
                pages.add(new Page(x, y, z, pages.size()));
                voxels.writeBytes(tile);
            }
        return new NiagaraCacheVolumeFrame(format, width, height, depth, true, new float[4], pages.size(), pages, voxels.toByteArray());
    }
}
