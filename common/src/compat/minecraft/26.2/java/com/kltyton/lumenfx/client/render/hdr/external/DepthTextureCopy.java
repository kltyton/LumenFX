package com.kltyton.lumenfx.client.render.hdr.external;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * Copies depth textures safely across host, Iris shaderpack, and internal render targets.
 * Uses Blaze3D {@code copyTextureToTexture} only when formats, dimensions, and copy usage flags
 * all permit it. OpenGL depth blits require matching formats. Format conversion uses a fullscreen
 * depth pass and stages through an owned attachment when the external GL target is copy-only.
 */
public final class DepthTextureCopy implements AutoCloseable {
    public enum Mode {
        NO_OP,
        DIRECT_COPY,
        GL_BLIT,
        SHADER_PASS,
        SHADER_THEN_GL_BLIT,
        UNSUPPORTED
    }

    private @Nullable RenderPipeline shaderPipeline;
    private @Nullable OwnedColorTarget shaderColorSlot;
    private @Nullable GpuTexture shaderDepthSlot;
    private @Nullable GpuTexture cachedSourceTexture;
    private @Nullable GpuTextureView cachedSourceView;
    private @Nullable GpuTexture cachedDestTexture;
    private @Nullable GpuTextureView cachedDestView;

    /**
     * Pure capability-based copy strategy selector, kept free of live GL/Vulkan calls so every
     * external/Iris depth usage-flag combination can be verified deterministically in unit tests.
     */
    public static Mode selectMode(
            GpuFormat sourceFormat, int sourceWidth, int sourceHeight, int sourceUsage, boolean sourceGl,
            GpuFormat destFormat, int destWidth, int destHeight, int destUsage, boolean destGl,
            boolean sameTexture) {
        if (sameTexture) {
            return Mode.NO_OP;
        }
        boolean sameFormat = sourceFormat == destFormat;
        if (sameFormat
                && sourceWidth == destWidth
                && sourceHeight == destHeight
                && (sourceUsage & GpuTexture.USAGE_COPY_SRC) != 0
                && (destUsage & GpuTexture.USAGE_COPY_DST) != 0) {
            return Mode.DIRECT_COPY;
        }
        if (sourceGl && destGl && sameFormat) {
            return Mode.GL_BLIT;
        }
        if ((sourceUsage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            if ((destUsage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
                return Mode.SHADER_PASS;
            }
            if (sourceGl && destGl) {
                return Mode.SHADER_THEN_GL_BLIT;
            }
        }
        return Mode.UNSUPPORTED;
    }

    public void copy(CommandEncoder encoder, GpuTexture source, GpuTexture destination, GlSceneTransfer glTransfer) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        int sourceWidth = source.getWidth(0);
        int sourceHeight = source.getHeight(0);
        int destWidth = destination.getWidth(0);
        int destHeight = destination.getHeight(0);
        Mode mode = selectMode(
                source.getFormat(), sourceWidth, sourceHeight, source.usage(), source instanceof GlTexture,
                destination.getFormat(), destWidth, destHeight, destination.usage(), destination instanceof GlTexture,
                source == destination);
        switch (mode) {
            case NO_OP -> { }
            case DIRECT_COPY -> encoder.copyTextureToTexture(
                    source, destination, 0, 0, 0, 0, 0, destWidth, destHeight);
            case GL_BLIT -> glTransfer.transferDepth(
                    source, sourceWidth, sourceHeight, destination, destWidth, destHeight);
            case SHADER_PASS -> copyWithShader(encoder, source, sourceWidth, sourceHeight, destination, destWidth, destHeight);
            case SHADER_THEN_GL_BLIT -> copyViaShaderDepthSlot(encoder, source, sourceWidth, sourceHeight,
                    destination, destWidth, destHeight, glTransfer);
            case UNSUPPORTED -> throw new IllegalStateException(
                    "Cannot copy depth texture '" + source.getLabel() + "' (format=" + source.getFormat()
                            + ", usage=" + source.usage() + ") into '" + destination.getLabel()
                            + "' (format=" + destination.getFormat() + ", usage=" + destination.usage() + ")");
        }
    }

    private void copyViaShaderDepthSlot(CommandEncoder encoder, GpuTexture source, int sourceWidth, int sourceHeight,
                                       GpuTexture destination, int destWidth, int destHeight, GlSceneTransfer glTransfer) {
        GpuTexture previous = null;
        if (shaderDepthSlot == null || shaderDepthSlot.getFormat() != destination.getFormat()
                || shaderDepthSlot.getWidth(0) != destWidth || shaderDepthSlot.getHeight(0) != destHeight) {
            previous = shaderDepthSlot;
            shaderDepthSlot = RenderSystem.getDevice().createTexture(() -> "LumenFX temporary depth copy attachment",
                    GpuTexture.USAGE_RENDER_ATTACHMENT, destination.getFormat(), destWidth, destHeight, 1, 1);
        }
        try {
            copyWithShader(encoder, source, sourceWidth, sourceHeight, shaderDepthSlot, destWidth, destHeight);
            glTransfer.transferDepth(shaderDepthSlot, destWidth, destHeight, destination, destWidth, destHeight);
        } finally {
            if (previous != null) {
                PhotonWorldRenderState.closeAtFrameEnd(previous);
            }
        }
    }

    private void copyWithShader(CommandEncoder encoder, GpuTexture source, int sourceWidth, int sourceHeight,
                                GpuTexture destination, int destWidth, int destHeight) {
        if (shaderPipeline == null) {
            shaderPipeline = RenderPipeline.builder()
                    .withLocation(LumenFx.id("pipeline/depth/copy"))
                    .withVertexShader(LumenFx.id("post/fullscreen"))
                    .withFragmentShader(LumenFx.id("post/depth/copy_depth"))
                    .withCull(false)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 0))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("SourceDepth")
                            .withUniform("DepthCopyInfo", UniformType.UNIFORM_BUFFER)
                            .build())
                    .build();
        }
        if (shaderColorSlot == null || shaderColorSlot.width != destWidth || shaderColorSlot.height != destHeight) {
            if (shaderColorSlot != null) {
                PhotonWorldRenderState.closeAtFrameEnd(shaderColorSlot);
            }
            shaderColorSlot = new OwnedColorTarget("LumenFX depth copy raster slot", destWidth, destHeight, GpuFormat.R8_UNORM);
        }
        GpuTextureView sourceView = viewForSource(source);
        GpuTextureView destView = viewForDest(destination);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice info;
        try (var uniform = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            uniform.data().order(ByteOrder.nativeOrder())
                    .putInt(sourceWidth).putInt(sourceHeight).putInt(destWidth).putInt(destHeight);
            info = uniform.slice();
        }
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX depth copy")
                .withColorAttachment(Objects.requireNonNull(shaderColorSlot.getColorTextureView()))
                .withDepthAttachment(destView, OptionalDouble.empty())
                .withRenderArea(new RenderPass.RenderArea(0, 0, destWidth, destHeight));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(shaderPipeline);
            pass.setUniform("DepthCopyInfo", info);
            pass.bindTexture("SourceDepth", sourceView, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private GpuTextureView viewForSource(GpuTexture source) {
        if (cachedSourceView != null && cachedSourceTexture == source && !cachedSourceView.isClosed()) {
            return cachedSourceView;
        }
        if (cachedSourceView != null && !cachedSourceView.isClosed()) {
            PhotonWorldRenderState.closeAtFrameEnd(cachedSourceView);
        }
        cachedSourceTexture = source;
        cachedSourceView = RenderSystem.getDevice().createTextureView(source);
        return cachedSourceView;
    }

    private GpuTextureView viewForDest(GpuTexture destination) {
        if (cachedDestView != null && cachedDestTexture == destination && !cachedDestView.isClosed()) {
            return cachedDestView;
        }
        if (cachedDestView != null && !cachedDestView.isClosed()) {
            PhotonWorldRenderState.closeAtFrameEnd(cachedDestView);
        }
        cachedDestTexture = destination;
        cachedDestView = RenderSystem.getDevice().createTextureView(destination);
        return cachedDestView;
    }

    public void endFrame() {
        if (cachedSourceView != null && !cachedSourceView.isClosed()) {
            PhotonWorldRenderState.closeAtFrameEnd(cachedSourceView);
        }
        if (cachedDestView != null && !cachedDestView.isClosed()) {
            PhotonWorldRenderState.closeAtFrameEnd(cachedDestView);
        }
        cachedSourceTexture = null;
        cachedSourceView = null;
        cachedDestTexture = null;
        cachedDestView = null;
    }

    @Override
    public void close() {
        if (cachedSourceView != null && !cachedSourceView.isClosed()) {
            cachedSourceView.close();
        }
        if (cachedDestView != null && !cachedDestView.isClosed()) {
            cachedDestView.close();
        }
        cachedSourceTexture = null;
        cachedSourceView = null;
        cachedDestTexture = null;
        cachedDestView = null;
        if (shaderColorSlot != null) {
            shaderColorSlot.close();
            shaderColorSlot = null;
        }
        if (shaderDepthSlot != null) {
            shaderDepthSlot.close();
            shaderDepthSlot = null;
        }
        shaderPipeline = null;
    }
}
