package com.kltyton.lumenfx.client.compat.iris;

import java.util.Objects;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;

public record IrisSceneColor(
        int glTextureId,
        int glTarget,
        int width,
        int height,
        InternalTextureFormat physicalFormat,
        boolean altSide
) {
    public IrisSceneColor {
        Objects.requireNonNull(physicalFormat, "physicalFormat");
        if (glTextureId <= 0 || glTarget <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid Iris scene colour target");
        }
    }

    public int glInternalFormat() {
        return this.physicalFormat.getGlFormat();
    }
}
