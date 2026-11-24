package com.kltyton.lumenfx.client.resource.texture;

import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Original encoded mip payloads prepared off the render thread. */
public record PreparedTexture(Identifier id, TextureMetadata metadata, List<ByteBuffer> mips, int arrayLayers, boolean cube,
                              int volumeDepth, TextureMetadata.@Nullable Address volumeAddressZ, int cubeArrayLayers) {
    public PreparedTexture(Identifier id, TextureMetadata metadata, List<ByteBuffer> mips) {
        this(id, metadata, mips, 0, false);
    }
    public PreparedTexture(Identifier id, TextureMetadata metadata, List<ByteBuffer> mips, int arrayLayers) {
        this(id, metadata, mips, arrayLayers, false);
    }
    public PreparedTexture(Identifier id, TextureMetadata metadata, List<ByteBuffer> mips, int arrayLayers, boolean cube) {
        this(id, metadata, mips, arrayLayers, cube, 0, null);
    }

    public PreparedTexture(Identifier id, TextureMetadata metadata, List<ByteBuffer> mips, int arrayLayers, boolean cube,
                           int volumeDepth, TextureMetadata.@Nullable Address volumeAddressZ) {
        this(id, metadata, mips, arrayLayers, cube, volumeDepth, volumeAddressZ, 0);
    }

    public PreparedTexture {
        mips = List.copyOf(mips);
        if (mips.isEmpty() || arrayLayers < 0 || volumeDepth < 0 || cubeArrayLayers < 0 || cubeArrayLayers > Integer.MAX_VALUE / 6
                || (cube || cubeArrayLayers > 0) && (arrayLayers != 0 || metadata.width() != metadata.height())
                || cube && cubeArrayLayers > 0 || volumeDepth > 0 && (arrayLayers != 0 || cube || cubeArrayLayers > 0 || volumeAddressZ == null))
            throw new IllegalArgumentException("Invalid encoded texture shape or mip chain");
    }

    public boolean array() { return arrayLayers != 0; }
    public boolean cubeArray() { return cubeArrayLayers != 0; }
    public boolean volume() { return volumeDepth != 0; }
    public int layers() { return cubeArray() ? cubeArrayLayers * 6 : cube ? 6 : Math.max(1, arrayLayers); }
}
