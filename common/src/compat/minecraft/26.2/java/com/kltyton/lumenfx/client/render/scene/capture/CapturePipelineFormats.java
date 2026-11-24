package com.kltyton.lumenfx.client.render.scene.capture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/** Retargets host color pipelines only for the private Niagara capture attachment. */
public final class CapturePipelineFormats {
    private record Variant(RenderPipeline pipeline, GpuFormat format) { }
    private static @Nullable CapturePipelineFormats active;

    private final Map<Variant, RenderPipeline> variants = new HashMap<>();
    private @Nullable GpuTextureView colorView;

    void draw(GpuTextureView colorView, Runnable submit) {
        var previous = active;
        var previousView = this.colorView;
        this.colorView = colorView;
        active = this;
        try {
            submit.run();
        } finally {
            active = previous;
            this.colorView = previousView;
        }
    }

    public static RenderPipeline adapt(RenderPipeline pipeline,
            List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments) {
        var capture = active;
        if (capture == null || attachments.isEmpty()) return pipeline;
        var attachment = attachments.getFirst();
        if (attachment == null || attachment.textureView() != capture.colorView) return pipeline;
        var color = pipeline.getColorTargetState();
        var format = attachment.textureView().texture().getFormat();
        if (color == null || color.format() == format) return pipeline;
        return capture.variants.computeIfAbsent(new Variant(pipeline, format), key -> com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(pipeline)
                .withColorTargetState(0, new ColorTargetState(color.blendFunction(), format, color.writeMask()))
                .build());
    }

    void clear() {
        variants.clear();
    }
}
