package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.joml.Matrix4fc;

/** Binds the resident triangle data and source visibility ID for a material pass. */
public final class NaniteMaterialGeometry {
    private static final String IDS = "NaniteVisiblePrimitives";
    private static final String DEPTH = "NaniteVisibleDepth";
    private static final String LOOKUP = "NanitePrimitiveLookup";
    private static final String PRIMITIVES = "NaniteShadingPrimitives";
    private static final String VERTICES = "NaniteShadingVertices";
    private static final String INSTANCE_FLOATS = "NaniteShadingInstanceFloats";
    private static final String TRANSFORMS = "NaniteShadingTransforms";
    private static final String INTERPOLANTS = "NaniteVertexInterpolants";
    private static final String INTERPOLANT_LAYOUT = "NaniteInterpolantLayout";
    private static final String VIEW = "NaniteShadingView";
    private static final int UNIFORM_BYTES = 192;
    static final int FIXED_RASTER = 1 << 7;
    static final int SHADOW_DEPTH = 1 << 8;

    public static final BindGroupLayout LAYOUT = layout(true);
    public static final BindGroupLayout RASTER_LAYOUT = layout(false);

    private static BindGroupLayout layout(boolean visibility) {
        var builder = BindGroupLayout.builder();
        if (visibility) builder.withSampler(IDS).withSampler(DEPTH);
        return builder.withUniform(LOOKUP, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
            .withUniform(PRIMITIVES, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
            .withUniform(VERTICES, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform(INSTANCE_FLOATS, UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT)
            .withUniform(TRANSFORMS, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform(INTERPOLANTS, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform(INTERPOLANT_LAYOUT, UniformType.UNIFORM_BUFFER)
            .withUniform(VIEW, UniformType.UNIFORM_BUFFER).build();
    }

    private NaniteMaterialGeometry() { }

    static GpuBufferSlice write(CommandEncoder encoder, NaniteViewRasterization.Draw draw,
                                NaniteViewProjection current, NaniteViewProjection previous, int slot, int frameFlags) {
        return write(encoder, draw.shading(), draw.scene(), current, previous, slot, frameFlags);
    }

    static GpuBufferSlice write(CommandEncoder encoder, NaniteShadingGeometry.Output shading,
                                NaniteGpuInstanceScene.Output scene, NaniteViewProjection current,
                                NaniteViewProjection previous, int slot) {
        return write(encoder, shading, scene, current, previous, slot, 0);
    }

    static GpuBufferSlice write(CommandEncoder encoder, NaniteShadingGeometry.Output shading,
                                        NaniteGpuInstanceScene.Output scene, NaniteViewProjection current,
                                        NaniteViewProjection previous, int slot, int frameFlags) {
        return write(encoder, shading, scene, current, previous, slot, frameFlags, false);
    }

    static GpuBufferSlice writeCompute(CommandEncoder encoder, NaniteShadingGeometry.Output shading,
            NaniteGpuInstanceScene.Output scene, NaniteViewProjection current, NaniteViewProjection previous,
            int slot, int frameFlags) {
        return write(encoder, shading, scene, current, previous, slot, frameFlags, true);
    }

    private static GpuBufferSlice write(CommandEncoder encoder, NaniteShadingGeometry.Output shading,
            NaniteGpuInstanceScene.Output scene, NaniteViewProjection current, NaniteViewProjection previous,
            int slot, int frameFlags, boolean bufferRelative) {
        if (slot < 0 || slot >= NaniteRasterBins.MATERIAL_SLOTS)
            throw new IllegalArgumentException("Invalid Nanite material slot " + slot);
        var view = current.raster();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var uniform = encoder.transientMemory().allocateGpuMapped(UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var bytes = uniform.data().order(ByteOrder.nativeOrder());
            writeMatrix(bytes, view.clip());
            writeMatrix(bytes, previous.raster().clip());
            bytes.putInt(bufferRelative ? 0 : offset(shading.lookup(), Integer.BYTES))
                    .putInt(bufferRelative ? 0 : offset(shading.primitives(), 16))
                    .putInt(bufferRelative ? 0 : offset(shading.vertices(), 16)).putInt(slot);
            bytes.putFloat(1f / view.width()).putFloat(1f / view.height())
                    .putFloat(view.textureOriginTopDown() ? -1 : 1).putFloat(view.zeroToOne() ? 1 : 0);
            bytes.putInt(bufferRelative ? 0 : offset(scene.customFloats(), Float.BYTES)).putInt(scene.customFloatCount())
                    .putInt(bufferRelative ? 0 : offset(scene.instances().transforms(), 16)).putInt(frameFlags);
            bytes.putInt(view.offsetX()).putInt(view.offsetY()).putInt(view.clampNear() ? 1 : 0).putInt(0);
            return uniform.slice();
        }
    }

    static GpuBufferSlice writeInterpolants(CommandEncoder encoder, NaniteViewRasterization.Draw draw, int slot) {
        return writeInterpolants(encoder, Objects.requireNonNull(draw.interpolants().get(slot), "Missing Nanite material interpolants for slot " + slot));
    }

    static GpuBufferSlice writeInterpolants(CommandEncoder encoder, NaniteViewRasterization.Interpolants interpolants) {
        return writeInterpolants(encoder, interpolants, false);
    }

    static GpuBufferSlice writeComputeInterpolants(CommandEncoder encoder, NaniteViewRasterization.Interpolants interpolants) {
        return writeInterpolants(encoder, interpolants, true);
    }

    private static GpuBufferSlice writeInterpolants(CommandEncoder encoder, NaniteViewRasterization.Interpolants interpolants,
                                                     boolean bufferRelative) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var uniform = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            uniform.data().order(ByteOrder.nativeOrder()).putInt(bufferRelative ? 0 : offset(interpolants.slice(), 16))
                    .putInt(interpolants.wordsPerVertex()).putInt(0).putInt(0);
            return uniform.slice();
        }
    }

    static void bind(RenderPass pass, NaniteViewRasterization.Draw draw, GpuBufferSlice view,
                     int slot, GpuBufferSlice interpolantLayout) {
        var point = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.bindTexture(IDS, draw.visibility().primitives(), point);
        pass.bindTexture(DEPTH, draw.visibility().depth(), point);
        bindGeometry(pass, draw.shading(), draw.scene(), Objects.requireNonNull(draw.interpolants().get(slot)), view, interpolantLayout);
    }

    static void bindGeometry(RenderPass pass, NaniteShadingGeometry.Output shading, NaniteGpuInstanceScene.Output scene,
                             NaniteViewRasterization.Interpolants interpolants, GpuBufferSlice view, GpuBufferSlice interpolantLayout) {
        pass.setUniform(LOOKUP, shading.lookup().buffer());
        pass.setUniform(PRIMITIVES, shading.primitives().buffer());
        pass.setUniform(VERTICES, shading.vertices().buffer());
        pass.setUniform(INSTANCE_FLOATS, scene.customFloats().buffer());
        pass.setUniform(TRANSFORMS, scene.instances().transforms().buffer());
        pass.setUniform(INTERPOLANTS, interpolants.slice().buffer());
        pass.setUniform(INTERPOLANT_LAYOUT, interpolantLayout);
        pass.setUniform(VIEW, view);
    }

    private static int offset(GpuBufferSlice slice, int stride) {
        if (slice.offset() % stride != 0) throw new IllegalArgumentException("Misaligned Nanite material texel buffer");
        return Math.toIntExact(slice.offset() / stride);
    }

    private static void writeMatrix(ByteBuffer target, Matrix4fc matrix) {
        matrix.get(target.position(), target);
        target.position(target.position() + 64);
    }
}
