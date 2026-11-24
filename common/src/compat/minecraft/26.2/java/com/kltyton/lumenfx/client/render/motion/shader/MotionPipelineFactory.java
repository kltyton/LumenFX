package com.kltyton.lumenfx.client.render.motion.shader;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Optional;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class MotionPipelineFactory {
    private MotionPipelineFactory() { }

    public static RenderPipeline coverage(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                           @Nullable BindGroupLayout extra, boolean particle) {
        return coverage(host, shaders, location, extra, particle, new DepthStencilState(CompareOp.EQUAL, false));
    }

    public static RenderPipeline coverage(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                           @Nullable BindGroupLayout extra, boolean particle, DepthStencilState depth) {
        return coverage(host, shaders, location, extra, particle, depth, !depth.writeDepth());
    }

    public static RenderPipeline coverage(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                           @Nullable BindGroupLayout extra, boolean particle, DepthStencilState depth,
                                           boolean opaque) {
        return coverage(host, shaders, location, extra, particle, depth, opaque, false);
    }

    public static RenderPipeline coverage(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                           @Nullable BindGroupLayout extra, boolean particle, DepthStencilState depth,
                                           boolean opaque, boolean colorReplay) {
        var defines = host.getShaderDefines();
        if (particle) {
            var overrideBuilder = ShaderDefines.builder()
                    .define("LFX_PARTICLE_MOTION", 1)
                    .define("LFX_OPAQUE_MOTION", opaque ? 1 : 0)
                    .define("LFX_MOTION_AUXILIARY_PASS", 1)
                    .define("LFX_SUBSTRATE_DUAL_SOURCE", 0);
            if (colorReplay) {
                overrideBuilder.define("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY", 1);
            }
            defines = defines.withOverrides(overrideBuilder.build());
        }
        var snippet = new RenderPipeline.Snippet(Optional.empty(), Optional.empty(), Optional.of(defines), Optional.empty(),
                new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS], 0, Optional.empty(), Optional.empty(), Optional.empty(),
                new VertexFormat[16], Optional.empty());
        var builder = RenderPipeline.builder(snippet).withLocation(location).withVertexShader(shaders.vertexShader())
                .withFragmentShader(shaders.fragmentShader()).withPrimitiveTopology(host.getPrimitiveTopology())
                .withCull(host.isCull()).withPolygonMode(host.getPolygonMode())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_CLIP_FORMAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_DEPTH_FORMAT,
                        ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN))
                .withDepthStencilState(depth);
        if (particle && opaque) {
            builder.withColorTargetState(2, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_OFFSET_FORMAT, ColorTargetState.WRITE_ALL));
        }
        for (var layout : host.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        if (extra != null) builder.withBindGroupLayout(extra);
        var formats = host.getVertexFormatBindings();
        for (int index = 0; index < formats.length; index++) if (formats[index] != null) builder.withVertexBinding(index, formats[index]);
        return builder.build();
    }

    /** Builds a 3-attachment opaque velocity-clear pipeline without defining {@code LFX_PARTICLE_MOTION}. */
    public static RenderPipeline zeroOpaqueCoverage(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                                    DepthStencilState depth) {
        var defines = host.getShaderDefines().withOverrides(
                ShaderDefines.builder().define("LFX_MOTION_AUXILIARY_PASS", 1).build());
        var snippet = new RenderPipeline.Snippet(Optional.empty(), Optional.empty(), Optional.of(defines), Optional.empty(),
                new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS], 0, Optional.empty(), Optional.empty(), Optional.empty(),
                new VertexFormat[16], Optional.empty());
        var builder = RenderPipeline.builder(snippet).withLocation(location).withVertexShader(shaders.vertexShader())
                .withFragmentShader(shaders.fragmentShader()).withPrimitiveTopology(host.getPrimitiveTopology())
                .withCull(host.isCull()).withPolygonMode(host.getPolygonMode())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_CLIP_FORMAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_DEPTH_FORMAT,
                        ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN))
                .withColorTargetState(2, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_OFFSET_FORMAT, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(depth);
        for (var layout : host.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        var formats = host.getVertexFormatBindings();
        for (int index = 0; index < formats.length; index++) if (formats[index] != null) builder.withVertexBinding(index, formats[index]);
        return builder.build();
    }

    /**
     * Builds the ordered per-pixel translucent motion log capture pipeline (writeMask = 0, no depth attachment)
     * with unified motion/auxiliary defines suppressing RVT feedback side effects and dual-source blending.
     */
    public static RenderPipeline translucentMotionLog(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                                      @Nullable BindGroupLayout extra, boolean hasVelocity) {
        var overrideBuilder = ShaderDefines.builder()
                .define("LFX_PARTICLE_MOTION", hasVelocity ? 1 : 0)
                .define("LFX_OPAQUE_MOTION", 0)
                .define("LFX_TRANSLUCENT_MOTION_LOG", 1)
                .define("LFX_MOTION_AUXILIARY_PASS", 1)
                .define("LFX_SUBSTRATE_DUAL_SOURCE", 0);
        if (hasVelocity) {
            overrideBuilder.define("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY", 1);
        }
        var defines = host.getShaderDefines().withOverrides(overrideBuilder.build());
        var snippet = new RenderPipeline.Snippet(Optional.empty(), Optional.empty(), Optional.of(defines), Optional.empty(),
                new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS], 0, Optional.empty(), Optional.empty(), Optional.empty(),
                new VertexFormat[16], Optional.empty());
        var builder = RenderPipeline.builder(snippet).withLocation(location).withVertexShader(shaders.vertexShader())
                .withFragmentShader(shaders.fragmentShader()).withPrimitiveTopology(host.getPrimitiveTopology())
                .withCull(host.isCull()).withPolygonMode(host.getPolygonMode())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_CLIP_FORMAT, 0))
                .withDepthStencilState(Optional.empty());
        for (var layout : host.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        if (extra != null) builder.withBindGroupLayout(extra);
        var formats = host.getVertexFormatBindings();
        for (int index = 0; index < formats.length; index++) if (formats[index] != null) builder.withVertexBinding(index, formats[index]);
        return builder.build();
    }

    /**
     * Builds the pure GLSL 330 per-primitive translucent step-capture pipeline (2 scratch attachments + writable
     * color-replay depth, {@code LFX_TRANSLUCENT_STEP_INSTANCE_BASE = 1}, {@code LFX_MOTION_AUXILIARY_PASS = 1}).
     */
    public static RenderPipeline translucentStepCapture(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                                        @Nullable BindGroupLayout extra, DepthStencilState depth,
                                                        boolean hasVelocity) {
        return translucentStepCapture(host, shaders, location, extra, depth, hasVelocity, false);
    }

    public static RenderPipeline translucentStepCapture(RenderPipeline host, MotionShaderSet shaders, Identifier location,
                                                        @Nullable BindGroupLayout extra, DepthStencilState depth,
                                                        boolean hasVelocity, boolean indirectGuard) {
        var overrideBuilder = ShaderDefines.builder()
                .define("LFX_PARTICLE_MOTION", hasVelocity ? 1 : 0)
                .define("LFX_OPAQUE_MOTION", 0)
                .define("LFX_TRANSLUCENT_STEP_INSTANCE_BASE", 1)
                .define("LFX_TRANSLUCENT_STEP_INDIRECT_GUARD", indirectGuard ? 1 : 0)
                .define("LFX_MOTION_AUXILIARY_PASS", 1)
                .define("LFX_SUBSTRATE_DUAL_SOURCE", 0);
        if (hasVelocity) {
            overrideBuilder.define("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY", 1);
        }
        var defines = host.getShaderDefines().withOverrides(overrideBuilder.build());
        var snippet = new RenderPipeline.Snippet(Optional.empty(), Optional.empty(), Optional.of(defines), Optional.empty(),
                new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS], 0, Optional.empty(), Optional.empty(), Optional.empty(),
                new VertexFormat[16], Optional.empty());
        var builder = RenderPipeline.builder(snippet).withLocation(location).withVertexShader(shaders.vertexShader())
                .withFragmentShader(shaders.fragmentShader()).withPrimitiveTopology(host.getPrimitiveTopology())
                .withCull(host.isCull()).withPolygonMode(host.getPolygonMode())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_CLIP_FORMAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), MotionShaderLayout.MOTION_DEPTH_FORMAT,
                        ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN))
                .withDepthStencilState(depth);
        for (var layout : host.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        if (extra != null) builder.withBindGroupLayout(extra);
        var formats = host.getVertexFormatBindings();
        for (int index = 0; index < formats.length; index++) if (formats[index] != null) builder.withVertexBinding(index, formats[index]);
        return builder.build();
    }
}
