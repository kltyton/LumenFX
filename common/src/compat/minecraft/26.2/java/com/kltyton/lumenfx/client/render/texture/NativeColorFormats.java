package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;

/** Physical attachment formats follow their pipeline through native compilation and cache lifetime. */
public final class NativeColorFormats {
    private static final Map<RenderPipeline, int[]> FORMATS = Collections.synchronizedMap(new WeakHashMap<>());
    private NativeColorFormats() { }

    public static void register(RenderPipeline pipeline, @Nullable NativeTextureStorage... storage) {
        var targets = pipeline.getColorTargetStates();
        if (targets.length != storage.length) throw new IllegalArgumentException("Native color format count differs from pipeline attachments");
        int[] formats = new int[storage.length];
        for (int i = 0; i < storage.length; i++) {
            NativeTextureStorage physical = storage[i];
            if (physical == null) continue;
            if (physical.format().compressed() || targets[i] == null || targets[i].format() != GpuFormat.RGBA8_UNORM)
                throw new IllegalArgumentException("Native uncompressed color format requires an RGBA8 attachment at " + i);
            formats[i] = physical.vkFormat();
        }
        FORMATS.put(pipeline, formats);
    }

    public static void applyVulkan(RenderPipeline pipeline, IntBuffer target) {
        int[] formats = FORMATS.get(pipeline);
        if (formats == null) return;
        if (target.remaining() != formats.length) throw new IllegalArgumentException("Native pipeline format buffer has an unexpected size");
        for (int i = 0; i < formats.length; i++) if (formats[i] != 0) target.put(target.position() + i, formats[i]);
    }
}
