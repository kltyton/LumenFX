package com.kltyton.lumenfx.client.render.niagara.gpu.mesh;

import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGlobalDistanceField;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField.Mip;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Physical indirection and R8 brick volumes shared by the three native mesh field mips. */
final class NiagaraGpuMeshDistanceField implements AutoCloseable {
    private static final int BRICK = StaticMeshDistanceField.BRICK_SIZE;
    private static final int MIPS = StaticMeshDistanceField.MIP_COUNT;
    private final ComputeVolume table, atlas;
    private final Map<String, byte[]> values;
    private final List<MipPlacement> placements;
    private final int tiles, width, depth;

    NiagaraGpuMeshDistanceField(String name, StaticMeshDistanceField field) {
        this(name, field.mips());
    }

    private record MipPlacement(Mip mip, int tableZ, int baseBrick) { }

    NiagaraGpuMeshDistanceField(String name, List<Mip> mips) {
        int limit = ComputeVolume.maximumSize();
        int tableWidth = 0, tableHeight = 0, tableDepth = 0, bricks = 0;
        var placements = new ArrayList<MipPlacement>(mips.size());
        for (Mip mip : mips) {
            placements.add(new MipPlacement(mip, tableDepth, bricks));
            tableWidth = Math.max(tableWidth, mip.x());
            tableHeight = Math.max(tableHeight, mip.y());
            tableDepth = Math.addExact(tableDepth, mip.z());
            bricks = Math.addExact(bricks, mip.brickCount());
        }
        int side = Math.min(limit / BRICK, Math.max(1, (int)Math.ceil(Math.cbrt(bricks))));
        if (side == 0) throw new IllegalStateException("The device cannot hold one Niagara mesh distance field brick");
        int atlasSize = side * BRICK;
        int atlasDepth = Math.multiplyExact(Math.max(1, Math.ceilDiv(bricks, side * side)), BRICK);
        if (Math.max(Math.max(tableWidth, tableHeight), tableDepth) > limit || atlasDepth > limit)
            throw new IllegalArgumentException("Native Niagara mesh field exceeds the device 3D image limit: " + name);
        var tableData = MemoryUtil.memAlloc(bytes(tableWidth, tableHeight, tableDepth, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        ComputeVolume uploadedTable;
        try {
            MemoryUtil.memSet(tableData, 0xff);
            IntBuffer words = tableData.asIntBuffer();
            for (var placement : placements)
                copyIndirection(words, placement.mip(), tableWidth, tableHeight, placement.tableZ());
            uploadedTable = ComputeVolume.createSampled(name + ".MeshFieldTable", tableWidth, tableHeight, tableDepth, List.of(tableData),
                    new NativeTextureStorage(TextureStorageFormat.R32_UINT, false), NiagaraGlobalDistanceField.PAGE_TABLE_SAMPLING);
        } finally {
            MemoryUtil.memFree(tableData);
        }
        try {
            var pixels = MemoryUtil.memCalloc(bytes(atlasSize, atlasSize, atlasDepth, 1));
            try {
                for (var placement : placements) {
                    var mip = placement.mip();
                    var source = mip.bricks();
                    for (int index = 0; index < mip.brickCount(); index++)
                        copyBrick(pixels, source, index, placement.baseBrick() + index, atlasSize, side);
                }
                atlas = ComputeVolume.createSampled(name + ".MeshFieldAtlas", atlasSize, atlasSize, atlasDepth, List.of(pixels),
                        new NativeTextureStorage(TextureStorageFormat.G8, false), NiagaraGlobalDistanceField.MIP_SAMPLING);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (RuntimeException | Error failure) {
            uploadedTable.close();
            throw failure;
        }
        table = uploadedTable;
        this.placements = List.copyOf(placements);
        tiles = side;
        width = atlasSize;
        depth = atlasDepth;
        values = mips.size() == MIPS ? directValues() : Map.of();
    }

    private Map<String, byte[]> directValues() {
        var layout = packed(MIPS * 4);
        var starts = packed(MIPS);
        var scales = packed(MIPS * 4);
        var adds = packed(MIPS * 4);
        for (var placement : placements) {
            var mip = placement.mip();
            layout.putInt(mip.x()).putInt(mip.y()).putInt(mip.z()).putInt(placement.tableZ());
            starts.putInt(placement.baseBrick());
            writeScale(scales, mip);
            writeAdd(adds, mip);
        }
        return Map.of("MeshFieldMipLayout", layout.array(), "MeshFieldBrickStarts", starts.array(),
                "MeshFieldScales", scales.array(), "MeshFieldAdds", adds.array(),
                "MeshFieldTiles", packed(2).putInt(tiles).putInt(tiles).array(),
                "MeshFieldAtlasInverseExtent", packed(3).putFloat(1f / width).putFloat(1f / width).putFloat(1f / depth).array());
    }

    void writeSharedMips(ByteBuffer bytes) {
        for (var placement : placements) {
            var mip = placement.mip();
            bytes.putInt(mip.x()).putInt(mip.y()).putInt(mip.z()).putInt(placement.tableZ());
            bytes.putInt(placement.baseBrick()).putInt(tiles).putInt(width).putInt(depth);
            writeScale(bytes, mip);
            writeAdd(bytes, mip);
        }
    }

    private static void writeScale(ByteBuffer bytes, Mip mip) {
        var scale = mip.uvScale();
        bytes.putFloat((float)scale.x * mip.x()).putFloat((float)scale.y * mip.y())
                .putFloat((float)scale.z * mip.z()).putFloat(mip.distanceScale());
    }

    private static void writeAdd(ByteBuffer bytes, Mip mip) {
        var add = mip.uvAdd();
        bytes.putFloat((float)add.x * mip.x()).putFloat((float)add.y * mip.y())
                .putFloat((float)add.z * mip.z()).putFloat(mip.distanceBias());
    }

    @Nullable byte[] value(String name) { return values.get(name); }
    ComputeVolume table() { return table; }
    ComputeVolume atlas() { return atlas; }

    private static void copyIndirection(IntBuffer target, Mip mip, int width, int height, int baseZ) {
        for (int z = 0; z < mip.z(); z++) for (int y = 0; y < mip.y(); y++) for (int x = 0; x < mip.x(); x++) {
            int from = (z * mip.y() + y) * mip.x() + x;
            int to = ((baseZ + z) * height + y) * width + x;
            target.put(to, mip.brickIndex(from));
        }
    }

    private static void copyBrick(ByteBuffer target, ByteBuffer source, int sourceBrick, int destinationBrick, int size, int tiles) {
        int x = destinationBrick % tiles * BRICK;
        int y = destinationBrick / tiles % tiles * BRICK;
        int z = destinationBrick / (tiles * tiles) * BRICK;
        int sourceOffset = sourceBrick * StaticMeshDistanceField.BRICK_BYTES;
        for (int slice = 0; slice < BRICK; slice++) for (int row = 0; row < BRICK; row++) {
            int from = sourceOffset + (slice * BRICK + row) * BRICK;
            int to = ((z + slice) * size + y + row) * size + x;
            target.put(to, source, from, BRICK);
        }
    }

    private static int bytes(int x, int y, int z, int element) {
        return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(x, y), z), element);
    }
    static ByteBuffer packed(int words) { return ByteBuffer.allocate(words * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN); }

    @Override public void close() {
        try { atlas.close(); }
        finally { table.close(); }
    }
}
