package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowCaster;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Preserves particle coverage while applying shadow winding and projected depth. */
public final class ParticleShadowPipelines {
    public enum Winding { BOTH, FRONT, BACK }
    private static final AtomicLong NEXT = new AtomicLong();
    private static final Identifier CPU_MESH_VERTEX = LumenFx.id("core/instance_mesh");
    private static final Identifier GPU_MESH_VERTEX = LumenFx.id("core/niagara_gpu_mesh");
    private static final Identifier CPU_SPRITE_VERTEX = LumenFx.id("core/instance_sprite");
    private static final Identifier GPU_SPRITE_VERTEX = LumenFx.id("core/niagara_gpu_sprite");
    private static final Identifier CPU_RIBBON_VERTEX = LumenFx.id("core/instance_ribbon");
    private static final Identifier GPU_RIBBON_VERTEX = LumenFx.id("core/niagara_gpu_ribbon");
    private static final BindGroupLayout CPU_RIBBON_BASIS = BindGroupLayout.builder()
            .withUniform("NiagaraExtraInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("NiagaraExtraData", UniformType.TEXEL_BUFFER, GpuFormat.R32_FLOAT).build();
    private record Variant(RenderPipeline base, Winding winding, boolean spot, boolean directional,
                           boolean cpuRibbonBasis, boolean addCpuRibbonBindings) { }
    private final Map<Variant, RenderPipeline> variants = new HashMap<>();

    public RenderPipeline get(RenderPipeline base, Winding winding, boolean spot, boolean directional,
                              boolean cpuRibbonBasis, boolean extraBindings, ShaderSource shaders) {
        if (winding == Winding.BOTH && !spot && !directional) return base;
        return variants.computeIfAbsent(new Variant(base, winding, spot, directional, cpuRibbonBasis, cpuRibbonBasis && !extraBindings),
                variant -> create(variant, shaders));
    }

    private static RenderPipeline create(Variant variant, ShaderSource shaders) {
        var base = variant.base();
        var id = LumenFx.id("pipeline/point_shadow_particle_winding/" + NEXT.getAndIncrement());
        var builder = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(base).withLocation(id).withFragmentShader(id);
        String normalDefine = variant.directional() ? vertexNormalDefine(base, variant.cpuRibbonBasis()) : null;
        if (variant.spot()) builder.withBindGroupLayout(SpotShadowCaster.LAYOUT);
        if (variant.directional()) {
            builder.withBindGroupLayout(DirectionalShadowCaster.LAYOUT);
            if (normalDefine != null) builder.withShaderDefine(normalDefine);
            else builder.withVertexShader(id);
            if (variant.addCpuRibbonBindings()) builder.withBindGroupLayout(CPU_RIBBON_BASIS);
        }
        var pipeline = builder.build();
        String source = Objects.requireNonNull(shaders.get(base.getFragmentShader(), ShaderType.FRAGMENT));
        String coverage = switch (variant.winding()) {
            case BOTH -> "";
            case FRONT -> "    if (!gl_FrontFacing) discard;\n";
            case BACK -> "    if (gl_FrontFacing) discard;\n";
        };
        String wrapped;
        if (variant.spot()) wrapped = SpotShadowCaster.fragment(source, coverage);
        else if (variant.directional() && normalDefine == null) wrapped = DirectionalShadowCaster.fragment(source, coverage);
        else {
            int insertion = GlslSourceScanner.header(source).insertionIndex();
            wrapped = source.substring(0, insertion) + "\n#define LFX_SHADOW_DEPTH_PASS 1\n#define main lfxShadowMaterialMain\n"
                    + source.substring(insertion) + "\n#undef main\nvoid main() {\n"
                    + coverage + "    lfxShadowMaterialMain();\n}\n";
        }
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, stage) -> {
            if (!shader.equals(id)) return shaders.get(shader, stage);
            if (stage == ShaderType.VERTEX)
                return DirectionalShadowCaster.vertex(Objects.requireNonNull(shaders.get(base.getVertexShader(), stage)), false);
            return wrapped;
        }).isValid())
            throw new IllegalStateException("Particle point shadow pipeline failed to compile: " + base.getLocation());
        return pipeline;
    }

    private static @Nullable String vertexNormalDefine(RenderPipeline base, boolean cpuRibbonBasis) {
        var shader = base.getVertexShader();
        if (shader.equals(CPU_MESH_VERTEX) || shader.equals(GPU_MESH_VERTEX)) return "LFX_CSM_PARTICLE_MESH";
        if (shader.equals(CPU_SPRITE_VERTEX) || shader.equals(GPU_SPRITE_VERTEX)) return "LFX_CSM_PARTICLE_SPRITE";
        if (shader.equals(GPU_RIBBON_VERTEX) || shader.equals(CPU_RIBBON_VERTEX)
                && (cpuRibbonBasis || "1".equals(base.getShaderDefines().values().get("LFX_MATERIAL_BASIS"))))
            return "LFX_CSM_PARTICLE_RIBBON";
        return null;
    }

    public void clear() { variants.clear(); }
}
