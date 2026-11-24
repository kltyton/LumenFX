package com.kltyton.lumenfx.client.render.depth;

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
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Writes only the selected source stencil bits while sharing ordinary particle coverage. */
public final class ParticleCustomDepthPipelines {
    private record Variant(RenderPipeline base, boolean stencilTargets, boolean depthWrite, int stencilMask, boolean reverseCull) { }
    private static final AtomicLong NEXT = new AtomicLong();
    private final Map<Variant, RenderPipeline> variants = new HashMap<>();

    public RenderPipeline get(RenderPipeline base, boolean stencilTargets, boolean depthWrite,
                              int stencilMask, boolean reverseCull, ShaderSource shaders) {
        return variants.computeIfAbsent(new Variant(base, stencilTargets, depthWrite, stencilMask, reverseCull),
                variant -> create(variant, shaders));
    }

    private static RenderPipeline create(Variant variant, ShaderSource shaders) {
        var base = variant.base();
        var id = LumenFx.id("pipeline/custom_depth/particle_" + NEXT.getAndIncrement());
        var builder = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(base).withLocation(id)
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 0))
                .withDepthStencilState(new DepthStencilState(variant.depthWrite() ? CompareOp.GREATER_THAN_OR_EQUAL
                        : CompareOp.ALWAYS_PASS, variant.depthWrite()));
        if (variant.stencilTargets()) {
            builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, variant.stencilMask() & 15));
            builder.withColorTargetState(2, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, variant.stencilMask() >>> 4));
        }
        boolean writesStencil = variant.stencilMask() != 0;
        if (!writesStencil && !variant.reverseCull()) {
            var result = builder.build();
            if (!RenderSystem.getDevice().precompilePipeline(result, shaders).isValid())
                throw new IllegalStateException("Particle custom depth pipeline failed to compile: " + base.getLocation());
            return result;
        }
        if (writesStencil) builder.withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("CustomStencilPrimitive", UniformType.UNIFORM_BUFFER).build());
        if (variant.reverseCull()) builder.withCull(false);
        var result = builder.withFragmentShader(id).build();
        String original = Objects.requireNonNull(shaders.get(base.getFragmentShader(), ShaderType.FRAGMENT));
        int insertion = GlslSourceScanner.header(original).insertionIndex();
        String stencilDeclarations = writesStencil ? """
                vec4 lfx_stencil_bits(int value, ivec4 bits) {
                    return vec4(notEqual(ivec4(value) & bits, ivec4(0)));
                }
                layout(std140) uniform CustomStencilPrimitive { ivec4 StencilReference; };
                layout(location=1) out vec4 lfxStencilLow;
                layout(location=2) out vec4 lfxStencilHigh;
                """ : "";
        String stencilWrites = writesStencil ? """
                    lfxStencilLow = lfx_stencil_bits(StencilReference.x, ivec4(1, 2, 4, 8));
                    lfxStencilHigh = lfx_stencil_bits(StencilReference.x, ivec4(16, 32, 64, 128));
                """ : "";
        String source = original.substring(0, insertion) + "\n" + stencilDeclarations + "#define main lfxCustomDepthMain\n"
                + original.substring(insertion) + "\n#undef main\nvoid main() {\n"
                + (variant.reverseCull() ? "    if (gl_FrontFacing) discard;\n" : "")
                + "    lfxCustomDepthMain();\n" + stencilWrites + "}\n";
        if (!RenderSystem.getDevice().precompilePipeline(result, (shader, stage) ->
                shader.equals(id) && stage == ShaderType.FRAGMENT ? source : shaders.get(shader, stage)).isValid())
            throw new IllegalStateException("Particle custom stencil pipeline failed to compile: " + base.getLocation());
        return result;
    }

    public void clear() { variants.clear(); }
}
