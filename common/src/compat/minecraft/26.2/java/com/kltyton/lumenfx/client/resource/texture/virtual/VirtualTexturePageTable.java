package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.lwjgl.system.MemoryUtil;

/** Each virtual mip maps its page to the finest resident ancestor, using UE's UInt32 page entry layout. */
final class VirtualTexturePageTable implements AutoCloseable {
    private static final int TEXEL_BYTES = 4 * Integer.BYTES;
    private static final int UPLOAD_BYTES = 1 << 20;
    private final GpuTexture texture;
    private final GpuTextureView view;
    private final int pagesX;
    private final int pagesY;

    VirtualTexturePageTable(String label, int pagesX, int pagesY, int maxMip) {
        this.pagesX = pagesX;
        this.pagesY = pagesY;
        int width = ceilingPowerOfTwo(pagesX);
        int height = ceilingPowerOfTwo(pagesY);
        var device = RenderSystem.getDevice();
        int limit = device.getDeviceInfo().limits().maxTextureSize();
        if (width > limit || height > limit) throw new IllegalArgumentException("Virtual texture page table exceeds the device limit: " + label);
        texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA32_UINT, width, height, 1, maxMip + 1);
        try {
            for (int mip = 0; mip <= maxMip; mip++)
                write(mip, 0, 0, width >> mip, height >> mip, ignored -> 0);
            view = device.createTextureView(texture);
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    GpuTextureView view() { return view; }

    void update(Set<Page> changed, ToIntFunction<Page> residentEntry) {
        for (Page source : changed) {
            for (int mip = 0; mip <= Math.min(source.mip(), texture.getMipLevels() - 1); mip++) {
                int scale = 1 << (source.mip() - mip);
                int x = source.x() * scale;
                int y = source.y() * scale;
                int width = Math.min(scale, ceilDivide(pagesX, 1 << mip) - x);
                int height = Math.min(scale, ceilDivide(pagesY, 1 << mip) - y);
                if (width > 0 && height > 0) write(mip, x, y, width, height, residentEntry);
            }
        }
    }

    private void write(int mip, int x, int y, int width, int height, ToIntFunction<Page> value) {
        int rowBytes = Math.multiplyExact(width, TEXEL_BYTES);
        int rows = Math.min(height, Math.max(1, UPLOAD_BYTES / rowBytes));
        var data = MemoryUtil.memAlloc(rowBytes * rows).order(ByteOrder.LITTLE_ENDIAN);
        try {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int row = 0; row < height; row += rows) {
                int count = Math.min(rows, height - row);
                data.clear();
                for (int dy = 0; dy < count; dy++) for (int dx = 0; dx < width; dx++)
                    data.putInt(value.applyAsInt(new Page(mip, x + dx, y + row + dy))).putInt(0).putLong(0);
                encoder.writeToTexture(texture, data.flip(), mip, 0, x, y + row, width, count);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    static int ceilDivide(int value, int denominator) { return (value - 1) / denominator + 1; }
    static int ceilingPowerOfTwo(int value) { return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1)); }

    @Override public void close() {
        RenderSystem.queueFencedTask(() -> {
            try { view.close(); } finally { texture.close(); }
        });
    }
}
