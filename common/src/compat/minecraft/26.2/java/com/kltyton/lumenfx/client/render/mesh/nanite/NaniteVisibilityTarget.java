package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
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
import org.jspecify.annotations.Nullable;

/** Rasterizes hardware candidates and resolves both raster paths against the incoming opaque depth. */
final class NaniteVisibilityTarget implements AutoCloseable {
    private static final GpuFormat ID_FORMAT = GpuFormat.R32_UINT;
    private static final String HARDWARE_OFFSETS = "NaniteHardwareOffsets";
    private static final String RESOLVE_OFFSETS = "NaniteResolveOffsets";
    private static final String HARDWARE_POSITIONS = "NaniteHardwarePositions";
    private static final String HARDWARE_IDS = "NaniteHardwarePrimitives";
    private static final String SOFTWARE_DEPTH = "NaniteSoftwareDepth";
    private static final String SOFTWARE_IDS = "NaniteSoftwarePrimitives";
    private static final ColorTargetState ID_COLOR = new ColorTargetState(Optional.empty(), ID_FORMAT, ColorTargetState.WRITE_ALL);
    private static final RenderPipeline CLEAR = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/nanite/clear_visibility"))
            .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/nanite/clear_visibility"))
            .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ID_COLOR)
            .withDepthStencilState(Optional.empty()).build();
    private static final RenderPipeline RESOLVE = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/nanite/resolve_visibility"))
            .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/nanite/resolve_visibility"))
            .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ID_COLOR)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("HardwareVisibility").withSampler("HardwareDepth")
                    .withUniform(RESOLVE_OFFSETS, UniformType.UNIFORM_BUFFER)
                    .withUniform(SOFTWARE_DEPTH, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform(SOFTWARE_IDS, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT).build()).build();
    static final BindGroupLayout HARDWARE_INPUTS = BindGroupLayout.builder().withUniform(HARDWARE_OFFSETS, UniformType.UNIFORM_BUFFER)
            .withUniform(HARDWARE_POSITIONS, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform(HARDWARE_IDS, UniformType.TEXEL_BUFFER, GpuFormat.RG32_UINT).build();
    private static final RenderPipeline HARDWARE_UP = hardwarePipeline(false);
    private static final RenderPipeline HARDWARE_DOWN = hardwarePipeline(true);

    record View(GpuTextureView primitives, GpuTextureView depth) { }

    record HardwarePass(Attachments target, NaniteTriangleRaster.Hardware geometry, GpuBufferSlice offsets) {
        RenderPassDescriptor descriptor() {
            GpuTextureView ids = target.idView();
            return RenderPassDescriptor.create(() -> "LumenFX Nanite hardware triangles")
                    .withColorAttachment(ids).withDepthAttachment(target.depthView())
                    .withRenderArea(new RenderPass.RenderArea(0, 0, ids.getWidth(0), ids.getHeight(0)));
        }

        void bind(RenderPass pass) {
            pass.setUniform(HARDWARE_OFFSETS, offsets);
            pass.setUniform(HARDWARE_POSITIONS, geometry.positions().buffer());
            pass.setUniform(HARDWARE_IDS, geometry.primitives().buffer());
        }
    }

    private record Attachments(OwnedColorTarget ids, GpuTexture depth, GpuTextureView depthView) implements AutoCloseable {
        GpuTextureView idView() { return Objects.requireNonNull(ids.getColorTextureView()); }
        boolean matches(int width, int height, GpuFormat format) {
            return ids.width == width && ids.height == height && depth.getFormat() == format;
        }
        @Override public void close() {
            try { depthView.close(); }
            finally {
                try { depth.close(); }
                finally { ids.close(); }
            }
        }
    }

    private @Nullable Attachments hardwareTarget, resolvedTarget;

    View rasterize(CommandEncoder encoder, GpuTextureView opaqueDepth, NaniteTriangleRaster.Output draws,
                   NaniteTriangleRaster.RasterView view, boolean ignoreOpaqueDepth,
                   java.util.function.@Nullable Consumer<HardwarePass> programmable) {
        RenderSystem.assertOnRenderThread();
        if (!opaqueDepth.texture().getFormat().hasDepthAspect() || opaqueDepth.baseMipLevel() != 0)
            throw new IllegalArgumentException("Nanite requires the full opaque depth target");
        int width = view.width(), height = view.height();
        var format = opaqueDepth.texture().getFormat();
        var hardware = ensure(width, height, format, hardwareTarget, "LumenFX Nanite hardware visibility");
        hardwareTarget = hardware;
        if (ignoreOpaqueDepth) encoder.clearDepthTexture(hardware.depth(), 0);
        else com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.copyDepth(encoder, opaqueDepth.texture(), hardware.depth());
        try (var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> "LumenFX clear Nanite visibility")
                .withColorAttachment(hardware.idView()).withRenderArea(new RenderPass.RenderArea(0, 0, width, height)))) {
            pass.setPipeline(CLEAR);
            pass.draw(3, 1, 0, 0);
        }

        var geometry = draws.hardware();
        var hardwareOffsets = offsets(encoder, texelOffset(geometry.positions(), 16), texelOffset(geometry.primitives(), 8), 0);
        var hardwarePass = new HardwarePass(hardware, geometry, hardwareOffsets);
        try (var pass = encoder.createRenderPass(hardwarePass.descriptor())) {
            pass.setPipeline(view.textureOriginTopDown() ? HARDWARE_DOWN : HARDWARE_UP);
            hardwarePass.bind(pass);
            pass.drawIndirect(geometry.bins().hardwareCommand(), 1);
        }
        if (programmable != null) programmable.accept(hardwarePass);

        var software = draws.software();
        if (software == null) return new View(hardware.idView(), hardware.depthView());
        var resolved = ensure(width, height, format, resolvedTarget, "LumenFX Nanite resolved visibility");
        resolvedTarget = resolved;
        var resolveOffsets = offsets(encoder, texelOffset(software.depth(), 4), texelOffset(software.primitives(), 4), width);
        var point = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> "LumenFX resolve Nanite visibility")
                .withColorAttachment(resolved.idView()).withDepthAttachment(resolved.depthView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height)))) {
            pass.setPipeline(RESOLVE);
            pass.setUniform(RESOLVE_OFFSETS, resolveOffsets);
            pass.setUniform(SOFTWARE_DEPTH, software.depth().buffer());
            pass.setUniform(SOFTWARE_IDS, software.primitives().buffer());
            pass.bindTexture("HardwareVisibility", hardware.idView(), point);
            pass.bindTexture("HardwareDepth", hardware.depthView(), point);
            pass.draw(3, 1, 0, 0);
        }
        return new View(resolved.idView(), resolved.depthView());
    }

    private static RenderPipeline hardwarePipeline(boolean topDown) {
        var pipeline = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/nanite/hardware_visibility_" + (topDown ? "down" : "up")))
                .withVertexShader(LumenFx.id("post/nanite/hardware_visibility"))
                .withFragmentShader(LumenFx.id("post/nanite/hardware_visibility"))
                .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ID_COLOR)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                .withBindGroupLayout(HARDWARE_INPUTS);
        if (topDown) pipeline.withShaderDefine("LFX_NANITE_TEXTURE_Y_DOWN");
        return pipeline.build();
    }

    private static int texelOffset(GpuBufferSlice slice, int bytes) {
        if (slice.offset() % bytes != 0) throw new IllegalArgumentException("Misaligned Nanite texel buffer");
        return Math.toIntExact(slice.offset() / bytes);
    }

    private static GpuBufferSlice offsets(CommandEncoder encoder, int first, int second, int width) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(first).putInt(second).putInt(width).putInt(0);
            return mapped.slice();
        }
    }

    private static Attachments ensure(int width, int height, GpuFormat format, @Nullable Attachments previous, String label) {
        if (previous != null && previous.matches(width, height, format)) return previous;
        var ids = new OwnedColorTarget(label + " primitives", width, height, ID_FORMAT);
        var device = RenderSystem.getDevice();
        GpuTexture depth = null;
        GpuTextureView depthView = null;
        try {
            depth = device.createTexture(label + " depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, format, width, height, 1, 1);
            depthView = device.createTextureView(depth);
        } catch (RuntimeException | Error failure) {
            if (depthView != null) depthView.close();
            if (depth != null) depth.close();
            ids.closeOnFailure(failure);
            throw failure;
        }
        if (previous != null) previous.close();
        return new Attachments(ids, depth, depthView);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        try { if (resolvedTarget != null) resolvedTarget.close(); }
        finally {
            resolvedTarget = null;
            if (hardwareTarget != null) hardwareTarget.close();
            hardwareTarget = null;
        }
    }
}
