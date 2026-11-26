package com.kltyton.lumenfx.client.graphics;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Mutable render-pass descriptor bridge for MC 26.3 RenderPearl.
 * Mirrors the 26.2 incremental {@code RenderPassDescriptor} builder API and materializes an
 * immutable {@link RenderPassDescriptor} record when opening a native RenderPearl pass.
 */
public final class LumenRenderPassDescriptor {
    public final Supplier<String> label;
    public final List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments = new ArrayList<>();
    public RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depthAttachment;
    public RenderPass.@Nullable RenderArea renderArea;

    private LumenRenderPassDescriptor(Supplier<String> label) {
        this.label = Objects.requireNonNull(label, "label");
    }

    public static LumenRenderPassDescriptor create(Supplier<String> label) {
        return new LumenRenderPassDescriptor(label);
    }

    public static LumenRenderPassDescriptor fromRenderPearl(RenderPassDescriptor descriptor) {
        LumenRenderPassDescriptor copy = new LumenRenderPassDescriptor(descriptor.label());
        copy.colorAttachments.addAll(descriptor.colorAttachments());
        copy.depthAttachment = descriptor.depthAttachment();
        copy.renderArea = descriptor.renderArea();
        return copy;
    }

    public LumenRenderPassDescriptor withColorAttachment(GpuTextureView textureView) {
        return withColorAttachment(textureView, Optional.empty());
    }

    public LumenRenderPassDescriptor withColorAttachment(GpuTextureView textureView, Optional<Vector4fc> clearValue) {
        colorAttachments.add(new RenderPassDescriptor.Attachment<>(textureView, clearValue));
        return this;
    }

    public LumenRenderPassDescriptor withUnusedColorAttachment() {
        colorAttachments.add(null);
        return this;
    }

    public LumenRenderPassDescriptor withDepthAttachment(GpuTextureView textureView) {
        return withDepthAttachment(textureView, OptionalDouble.empty());
    }

    public LumenRenderPassDescriptor withDepthAttachment(GpuTextureView textureView, OptionalDouble clearValue) {
        depthAttachment = new RenderPassDescriptor.Attachment<>(textureView, clearValue);
        return this;
    }

    public LumenRenderPassDescriptor withRenderArea(RenderPass.RenderArea area) {
        renderArea = area;
        return this;
    }

    public Supplier<String> label() {
        return label;
    }

    public List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments() {
        return colorAttachments;
    }

    public RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depthAttachment() {
        return depthAttachment;
    }

    public RenderPass.@Nullable RenderArea renderArea() {
        return renderArea;
    }

    public RenderPassDescriptor toRenderPearl() {
        RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(label);
        for (var attachment : colorAttachments) {
            if (attachment == null) {
                builder.withUnusedColorAttachment();
            } else {
                builder.withColorAttachment(attachment.textureView(), attachment.clearValue());
            }
        }
        if (depthAttachment != null) {
            builder.withDepthAttachment(depthAttachment.textureView(), depthAttachment.clearValue());
        }
        if (renderArea != null) {
            builder.withRenderArea(renderArea);
        }
        return builder.build();
    }
}
