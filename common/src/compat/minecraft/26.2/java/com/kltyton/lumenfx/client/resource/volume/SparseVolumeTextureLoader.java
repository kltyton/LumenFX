package com.kltyton.lumenfx.client.resource.volume;

import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Address;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Atlas;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Extent;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Frame;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Transform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Expands the source occupancy masks and prepares the exact mip page tables off the render thread. */
public final class SparseVolumeTextureLoader {
    private static final int TILE = 18, VOXELS = TILE * TILE * TILE, MASK_WORDS = (VOXELS + 31) / 32;
    private record Format(TextureStorageFormat storage, int pixelBytes) { }
    private SparseVolumeTextureLoader() { }

    public static SparseVolumeTextureAsset load(ResourceManager resources, Identifier id) {
        var path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/sparse_volumes/" + id.getPath() + ".lfst");
        try (var source = resources.getResourceOrThrow(path).open()) {
            var data = ByteBuffer.wrap(source.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (data.getInt() != 0x5453464c || data.getInt() != 1) throw new IOException("Invalid sparse volume asset header");
            var resolution = extent(data);
            int count = checkedCount(data.getInt(), data.remaining(), 108);
            int flags = data.getInt();
            if ((flags & ~1) != 0) throw new IOException("Invalid sparse volume asset flags");
            Address x = address(data.getInt()), y = address(data.getInt()), z = address(data.getInt());
            Format a = format(data), b = format(data);
            var frames = new ArrayList<Frame>(count);
            for (int index = 0; index < count; index++) frames.add(frame(data, a, b));
            if (data.hasRemaining()) throw new IOException("Unexpected sparse volume asset tail");
            return new SparseVolumeTextureAsset(resolution, frames, x, y, z, flags == 1);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load sparse volume " + id, failure);
        } catch (BufferUnderflowException | IllegalArgumentException | ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid sparse volume " + id, failure);
        }
    }

    private static Frame frame(ByteBuffer data, @Nullable Format a, @Nullable Format b) throws IOException {
        var pageExtent = extent(data);
        int x = data.getInt(), y = data.getInt(), z = data.getInt();
        int index = data.getInt(), mipCount = data.getInt(), tileCount = data.getInt();
        if (mipCount < 1 || mipCount > 32 - Integer.numberOfLeadingZeros(Math.max(pageExtent.x(), Math.max(pageExtent.y(), pageExtent.z())))
                || tileCount < 0 || tileCount >= 256 * 256 * 256)
            throw new IOException("Invalid sparse volume mip or tile count");
        var transform = new Transform(vector(data), data.getDouble(), data.getDouble(), data.getDouble(), data.getDouble(), vector(data));
        Extent atlasTiles = atlasTiles(tileCount + 1);
        var atlasExtent = new Extent(atlasTiles.x() * TILE, atlasTiles.y() * TILE, atlasTiles.z() * TILE);
        var mips = new ArrayList<ByteBuffer>(mipCount);
        for (int mip = 0; mip < mipCount; mip++) {
            var extent = pageExtent.mip(mip);
            var table = ByteBuffer.allocate(Math.multiplyExact(extent.pixels(), Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
            int pages = checkedCount(data.getInt(), data.remaining(), 8);
            if (pages > extent.pixels()) throw new IOException("Sparse volume page count exceeds its mip");
            for (int page = 0; page < pages; page++) {
                int coordinate = data.getInt(), tile = data.getInt();
                int px = coordinate & 2047, py = coordinate >>> 11 & 2047, pz = coordinate >>> 22;
                if (px >= extent.x() || py >= extent.y() || pz >= extent.z() || tile < 0 || tile >= tileCount)
                    throw new IOException("Sparse volume page is outside its mip");
                int destination = (pz * extent.y() + py) * extent.x() + px;
                table.putInt(destination * Integer.BYTES, packTile(tile + 1, atlasTiles) | (mip << 24));
            }
            mips.add(table);
        }
        byte[] fallbackA = a == null ? null : bytes(data, a.pixelBytes());
        byte[] fallbackB = b == null ? null : bytes(data, b.pixelBytes());
        byte[] atlasA = a == null ? null : new byte[Math.multiplyExact(atlasExtent.pixels(), a.pixelBytes())];
        byte[] atlasB = b == null ? null : new byte[Math.multiplyExact(atlasExtent.pixels(), b.pixelBytes())];
        int[] mask = new int[MASK_WORDS];
        if (a != null) copyTile(atlasA, atlasExtent, 0, atlasTiles, fallbackA, a.pixelBytes(), null, null);
        if (b != null) copyTile(atlasB, atlasExtent, 0, atlasTiles, fallbackB, b.pixelBytes(), null, null);
        for (int tile = 0; tile < tileCount; tile++) {
            if (a != null) expand(data, atlasA, atlasExtent, tile + 1, atlasTiles, fallbackA, a.pixelBytes(), mask);
            if (b != null) expand(data, atlasB, atlasExtent, tile + 1, atlasTiles, fallbackB, b.pixelBytes(), mask);
        }
        return new Frame(index, pageExtent, x, y, z, mips, atlasExtent,
                a == null ? null : new Atlas(a.storage().name(), ByteBuffer.wrap(atlasA)),
                b == null ? null : new Atlas(b.storage().name(), ByteBuffer.wrap(atlasB)), transform);
    }

    private static void expand(ByteBuffer data, byte[] atlas, Extent size, int tile, Extent slots, byte[] fallback, int pixelBytes,
                               int[] mask) throws IOException {
        int voxels = data.getInt();
        if (voxels < 0 || voxels > VOXELS) throw new IOException("Invalid sparse volume occupancy");
        int sum = 0;
        for (int i = 0; i < mask.length; i++) {
            mask[i] = data.getInt();
            sum += Integer.bitCount(mask[i]);
        }
        if (sum != voxels || (mask[mask.length - 1] >>> (VOXELS & 31)) != 0)
            throw new IOException("Sparse volume occupancy and pixel count disagree");
        copyTile(atlas, size, tile, slots, fallback, pixelBytes, data, mask);
    }

    private static void copyTile(byte[] atlas, Extent size, int tile, Extent slots, byte[] fallback, int pixelBytes,
                                 @Nullable ByteBuffer source, int @Nullable [] mask) {
        int packed = packTile(tile, slots);
        int ox = (packed & 255) * TILE, oy = (packed >>> 8 & 255) * TILE, oz = (packed >>> 16) * TILE;
        for (int z = 0; z < TILE; z++) for (int y = 0; y < TILE; y++) {
            int target = (((oz + z) * size.y() + oy + y) * size.x() + ox) * pixelBytes;
            int voxel = (z * TILE + y) * TILE;
            for (int x = 0; x < TILE; x++, voxel++, target += pixelBytes) {
                if (source != null && mask != null && (mask[voxel >>> 5] & (1 << (voxel & 31))) != 0)
                    source.get(atlas, target, pixelBytes);
                else System.arraycopy(fallback, 0, atlas, target, pixelBytes);
            }
        }
    }

    private static Extent atlasTiles(int tiles) {
        int x = (int)Math.ceil(Math.cbrt(tiles));
        int y = (int)Math.ceil(Math.sqrt((double)tiles / x));
        int z = Math.ceilDiv(tiles, x * y);
        if (x > 256 || y > 256 || z > 256) throw new IllegalArgumentException("Sparse volume physical atlas coordinate overflow");
        return new Extent(x, y, z);
    }
    private static int packTile(int tile, Extent slots) {
        return (tile % slots.x()) | ((tile / slots.x() % slots.y()) << 8) | ((tile / (slots.x() * slots.y())) << 16);
    }
    private static Extent extent(ByteBuffer data) { return new Extent(data.getInt(), data.getInt(), data.getInt()); }
    private static Vec3 vector(ByteBuffer data) { return new Vec3(data.getDouble(), data.getDouble(), data.getDouble()); }
    private static @Nullable Format format(ByteBuffer data) throws IOException {
        String name = new String(bytes(data, data.getInt()), StandardCharsets.UTF_8);
        int bytes = data.getInt();
        if (name.isEmpty() && bytes == 0) return null;
        var format = TextureStorageFormat.from(name);
        if (format.compressed() || bytes != format.byteSize(1, 1)) throw new IOException("Invalid sparse volume voxel format " + name);
        return new Format(format, bytes);
    }
    private static byte[] bytes(ByteBuffer data, int length) throws IOException {
        byte[] bytes = new byte[checkedCount(length, data.remaining(), 1)];
        data.get(bytes);
        return bytes;
    }
    private static int checkedCount(int count, int available, int bytes) throws IOException {
        if (count < 0 || count > available / bytes) throw new IOException("Truncated sparse volume asset");
        return count;
    }
    private static Address address(int value) throws IOException {
        return switch (value) { case 0 -> Address.WRAP; case 1 -> Address.CLAMP; case 2 -> Address.MIRROR;
            default -> throw new IOException("Invalid sparse volume address mode"); };
    }
}
