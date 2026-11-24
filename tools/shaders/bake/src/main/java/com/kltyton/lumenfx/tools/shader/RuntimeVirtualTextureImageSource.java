package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exposes the pinned Windows 2D storage declarations to the existing explicit-format lowering. */
final class RuntimeVirtualTextureImageSource {
    private RuntimeVirtualTextureImageSource() { }

    static void lower(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String lowered = text.replaceAll("(?m)^DestTextureType<TYPE_64BIT>(\\s+OutCompressTexture[012]_64bit;)", "RWTexture2D<uint2>$1")
                .replaceAll("(?m)^DestTextureType<uint4>(\\s+OutCompressTexture[012]_128bit;)", "RWTexture2D<uint4>$1");
        if (lowered.equals(text)) throw new BakeException("Pinned RVT compression declarations are missing");
        Files.writeString(source, lowered, StandardCharsets.UTF_8);
    }
}
