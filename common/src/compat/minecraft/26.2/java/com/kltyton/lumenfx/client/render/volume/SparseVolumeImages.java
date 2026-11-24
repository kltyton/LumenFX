package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume.Sampling;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Atlas;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Extent;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Frame;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** GPU ownership of a single immutable page table and its two physical voxel atlases. */
public final class SparseVolumeImages implements AutoCloseable {
    private static final Sampling PAGE = new Sampling(
            new Settings(Address.CLAMP, Address.CLAMP, FilterMode.POINT, FilterMode.POINT, 1), Address.CLAMP);
    private static final Sampling ATLAS = new Sampling(
            new Settings(Address.CLAMP, Address.CLAMP, FilterMode.LINEAR, FilterMode.POINT, 1), Address.CLAMP);

    private final ComputeVolume pageTable;
    private final @Nullable ComputeVolume a, b;

    private SparseVolumeImages(ComputeVolume pageTable, @Nullable ComputeVolume a, @Nullable ComputeVolume b) {
        this.pageTable = pageTable;
        this.a = a;
        this.b = b;
    }

    public static SparseVolumeImages upload(String label, Frame frame) {
        var pages = upload(label + ".PageTable", frame.pageExtent(), TextureStorageFormat.R32_UINT, frame.mips(), PAGE);
        ComputeVolume a = null;
        try {
            a = atlas(label + ".A", frame.atlasExtent(), frame.attributeA());
            return new SparseVolumeImages(pages, a, atlas(label + ".B", frame.atlasExtent(), frame.attributeB()));
        } catch (RuntimeException | Error failure) {
            try { if (a != null) a.close(); }
            finally { pages.close(); }
            throw failure;
        }
    }

    private static @Nullable ComputeVolume atlas(String label, Extent extent, @Nullable Atlas atlas) {
        return atlas == null ? null : upload(label, extent, TextureStorageFormat.valueOf(atlas.format()), List.of(atlas.pixels()), ATLAS);
    }

    static ComputeVolume blackAttribute() {
        return ComputeVolume.createSampled("Niagara.SparseVolume.BlackAttribute", 1, 1, 1,
                List.of(ByteBuffer.allocateDirect(4)), new NativeTextureStorage(TextureStorageFormat.BGRA8, false), ATLAS);
    }

    private static ComputeVolume upload(String label, Extent extent, TextureStorageFormat format,
                                        List<ByteBuffer> mips, Sampling sampling) {
        var upload = new ArrayList<ByteBuffer>(mips.size());
        var allocations = new ArrayList<ByteBuffer>(mips.size());
        try {
            for (var source : mips) {
                if (source.isDirect()) upload.add(source);
                else {
                    var nativeBuffer = MemoryUtil.memAlloc(source.remaining());
                    allocations.add(nativeBuffer);
                    upload.add(nativeBuffer.put(source.duplicate()).flip());
                }
            }
            return ComputeVolume.createSampled(label, extent.x(), extent.y(), extent.z(), upload,
                    new NativeTextureStorage(format, false), sampling);
        } finally {
            allocations.forEach(MemoryUtil::memFree);
        }
    }

    public static int packedPageOffset(Frame frame) {
        return (frame.pageX() & 2047) | ((frame.pageY() & 2047) << 11) | ((frame.pageZ() & 1023) << 22);
    }

    public static int packedFrameMip(Frame frame) {
        return (frame.index() & 65535) | ((frame.mipCount() - 1) << 16);
    }

    public ComputeVolume pageTable() { return pageTable; }
    public @Nullable ComputeVolume a() { return a; }
    public @Nullable ComputeVolume b() { return b; }

    @Override public void close() {
        try (pageTable; a; b) {
        }
    }
}
