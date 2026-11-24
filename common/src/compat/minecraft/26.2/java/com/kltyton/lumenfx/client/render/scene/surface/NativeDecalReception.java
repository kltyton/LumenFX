package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import com.mojang.blaze3d.pipeline.RenderPipeline;

/** Native pipeline hints retain primitive reception separately from DBuffer channel response. */
final class NativeDecalReception {
    private NativeDecalReception() { }

    static int packed(RenderPipeline pipeline) {
        int receives = value(pipeline, "LFX_RECEIVES_DECALS", 1, 1);
        int response = value(pipeline, "LFX_DECAL_RESPONSE_MASK", 7, 7);
        return (receives << 3) | response;
    }

    private static int value(RenderPipeline pipeline, String name, int defaultValue, int maximum) {
        var defines = pipeline.getShaderDefines();
        String value = defines.values().get(name);
        if (defines.flags().contains(name)) {
            if (value != null || maximum != 1)
                throw new GlslSourceException("Decal receiver hint requires one integer value: " + name + " in " + pipeline.getLocation());
            return 1;
        }
        if (value == null) return defaultValue;
        if (value.length() != 1 || value.charAt(0) < '0' || value.charAt(0) > '0' + maximum)
            throw new GlslSourceException("Decal receiver hint must be in 0.." + maximum + ": " + name + " in " + pipeline.getLocation());
        return value.charAt(0) - '0';
    }
}
