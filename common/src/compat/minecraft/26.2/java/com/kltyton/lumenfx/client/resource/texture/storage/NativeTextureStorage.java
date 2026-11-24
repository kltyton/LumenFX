package com.kltyton.lumenfx.client.resource.texture.storage;

import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;

/** Physical block and color encoding, separate from the host's logical uncompressed format. */
public record NativeTextureStorage(TextureStorageFormat format, boolean srgb) {
    public int glFormat() { return format.glFormat(srgb); }
    public int vkFormat() { return format.vkFormat(srgb); }
}
