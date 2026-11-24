package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Evaluates a material against its hardware triangle before depth selects the visible primitive. */
public final class NaniteProgrammableRasterPipelines {
    public static final String BLUE_NOISE = "LfxNaniteScalarBlueNoise";
    private static final AtomicLong NEXT = new AtomicLong();
    private record Key(RenderPipeline base, boolean topDown, boolean ditherMask) { }
    private final Map<Key, RenderPipeline> variants = new HashMap<>();

    public RenderPipeline get(RenderPipeline base, boolean topDown, boolean ditherMask, ShaderSource shaders) {
        return variants.computeIfAbsent(new Key(base, topDown, ditherMask), key -> create(key, shaders));
    }

    private static RenderPipeline create(Key key, ShaderSource shaders) {
        var base = key.base();
        var id = LumenFx.id("pipeline/nanite/programmable_raster_" + NEXT.getAndIncrement());
        var snippet = new RenderPipeline.Snippet(Optional.empty(), Optional.empty(), Optional.of(base.getShaderDefines()), Optional.empty(),
                new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS], 0, Optional.empty(), Optional.empty(), Optional.empty(),
                new VertexFormat[16], Optional.empty());
        var builder = RenderPipeline.builder(snippet).withLocation(id)
                .withVertexShader(LumenFx.id("post/nanite/hardware_visibility")).withFragmentShader(id)
                .withPrimitiveTopology(base.getPrimitiveTopology()).withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_UINT, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                .withShaderDefine("LFX_NANITE_RASTER_MATERIAL", 1).withBindGroupLayout(NaniteVisibilityTarget.HARDWARE_INPUTS);
        if (key.topDown()) builder.withShaderDefine("LFX_NANITE_TEXTURE_Y_DOWN");
        for (var layout : base.getBindGroupLayouts())
            builder.withBindGroupLayout(layout == NaniteMaterialGeometry.LAYOUT ? NaniteMaterialGeometry.RASTER_LAYOUT : layout);
        if (key.ditherMask()) builder.withBindGroupLayout(BindGroupLayout.builder().withSampler(BLUE_NOISE).build());

        var pipeline = builder.build();
        String original = Objects.requireNonNull(shaders.get(base.getFragmentShader(), ShaderType.FRAGMENT));
        var source = GlslSourceScanner.scan(original, pipeline.getShaderDefines());
        if (source.outputs().size() != 1 || source.mains().size() != 1)
            throw new IllegalArgumentException("Nanite programmable raster requires a single opaque material output");
        var output = source.outputs().getFirst();
        if (!output.type().equals("vec4") || output.array() || output.declarators() != 1)
            throw new IllegalArgumentException("Invalid Nanite opaque material output");
        int insertion = source.header().insertionIndex();
        String fragment = original.substring(0, insertion) + "\n#define main lfxNaniteRasterMain\n"
                + original.substring(insertion, output.start()) + "vec4 " + output.name() + ";"
                + original.substring(output.end()) + """
                \n#undef main
                layout(location = 0) out uint lfxNaniteRasterId;
                void main() {
                    lfxNaniteRasterMain();
                    lfxNaniteRasterId = primitiveData.x;
                }
                """;
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, stage) ->
                shader.equals(id) && stage == ShaderType.FRAGMENT ? fragment : shaders.get(shader, stage)).isValid())
            throw new IllegalStateException("Nanite programmable raster pipeline failed to compile: " + base.getLocation());
        return pipeline;
    }

    public void clear() { variants.clear(); }
}
