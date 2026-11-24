package com.kltyton.lumenfx.client.resource.texture.storage;

import com.mojang.blaze3d.textures.GpuTexture;

/** Physical storage of a sample-only image that has no equivalent in the host's GpuFormat enum. */
public interface SampledTextureStorage {
    NativeTextureStorage sampledStorage();
    default boolean cubeArray() { return false; }

    static boolean isCubeArray(GpuTexture texture) {
        return texture instanceof SampledTextureStorage source && source.cubeArray();
    }
}
