package com.kltyton.lumenfx.client.resource.texture.storage;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;

/** Retains host view counting and destruction for a native sample-only texture. */
final class SourceGlTexture extends GlTexture implements SampledTextureStorage {
    private final NativeTextureStorage storage;
    private final boolean cubeArray;

    SourceGlTexture(String label, int width, int height, int layers, int mips, int id, boolean cube,
                    FrameBufferCache frameBuffers, NativeTextureStorage storage) {
        this(label, width, height, layers, mips, id, cube, false, frameBuffers, storage);
    }

    SourceGlTexture(String label, int width, int height, int layers, int mips, int id, boolean cube, boolean cubeArray,
                    FrameBufferCache frameBuffers, NativeTextureStorage storage) {
        super(GpuTexture.USAGE_TEXTURE_BINDING | (cube || cubeArray ? GpuTexture.USAGE_CUBEMAP_COMPATIBLE : 0),
                label, GpuFormat.RGBA8_UNORM, width, height, layers, mips, id, frameBuffers);
        this.storage = storage;
        this.cubeArray = cubeArray;
    }

    @Override public int getWidth(int level) { return Math.max(1, super.getWidth(level)); }
    @Override public int getHeight(int level) { return Math.max(1, super.getHeight(level)); }
    @Override public NativeTextureStorage sampledStorage() { return storage; }
    @Override public boolean cubeArray() { return cubeArray; }

    @Override public GpuFormat getFormat() {
        throw new UnsupportedOperationException("Sample-only source storage has no host GpuFormat: " + storage);
    }
}
