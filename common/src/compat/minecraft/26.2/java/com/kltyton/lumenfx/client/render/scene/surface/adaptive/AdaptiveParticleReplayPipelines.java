package com.kltyton.lumenfx.client.render.scene.surface.adaptive;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Reuses the original draw state and fragment discard/depth code for each packed attachment group. */
public final class AdaptiveParticleReplayPipelines {
    private static final AtomicLong NEXT = new AtomicLong();
    private record Key(RenderPipeline original, int words, int firstLayer, int colorCount,
                       boolean bottomNormal, boolean anisotropy) { }
    private final Map<Key, RenderPipeline> pipelines = new HashMap<>();

    public RenderPipeline get(RenderPipeline base, MaterialDefinition material, AdaptiveParticleBuffer.Replay replay,
                              boolean bottomNormal, boolean anisotropy, ShaderSource shaders) {
        var key = new Key(base, material.adaptiveSubstrateWords(), replay.firstLayer(), replay.layers().size(),
                bottomNormal, anisotropy && material.anisotropicSurface());
        return pipelines.computeIfAbsent(key, entry -> create(entry, shaders));
    }

    private static RenderPipeline create(Key key, ShaderSource shaders) {
        var base = key.original();
        var id = LumenFx.id("pipeline/adaptive_substrate_replay/" + NEXT.getAndIncrement());
        var builder = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(base).withLocation(id).withFragmentShader(id)
                .withShaderDefine("LFX_SUBSTRATE_ADAPTIVE_REPLAY", key.words() == 0 ? 0 : 1)
                .withShaderDefine("LFX_CLEAR_COAT_BOTTOM_NORMAL", key.bottomNormal() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_ANISOTROPIC_MATERIAL", key.anisotropy() ? 1 : 0)
                .withShaderDefine("LFX_SUBSTRATE_COVERAGE_REPLAY", 1);
        for (int attachment = 0; attachment < key.colorCount(); attachment++) {
            builder.withColorTargetState(attachment,
                    new ColorTargetState(Optional.empty(), AdaptiveParticleBuffer.FORMAT, ColorTargetState.WRITE_ALL));
        }
        if (key.words() != 0) builder.withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("LfxSubstratePacking", UniformType.UNIFORM_BUFFER).build());
        var pipeline = builder.build();
        String original = Objects.requireNonNull(shaders.get(base.getFragmentShader(), ShaderType.FRAGMENT));
        String source = replaySource(original, pipeline.getShaderDefines(), key);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, stage) ->
                shader.equals(id) && stage == ShaderType.FRAGMENT ? source : shaders.get(shader, stage)).isValid())
            throw new IllegalStateException("Adaptive particle material pipeline failed to compile: " + base.getLocation());
        return pipeline;
    }

    private static String replaySource(String original, net.minecraft.client.renderer.ShaderDefines defines, Key key) {
        var parsed = GlslSourceScanner.scan(original, defines);
        int insertion = parsed.header().insertionIndex();
        StringBuilder text = new StringBuilder(original.substring(0, insertion)).append("\n#define main lfxAdaptiveCoverageMain\n");
        for (int layer = 0; layer < key.colorCount(); layer++)
            text.append("layout(location=").append(layer).append(") out uvec4 lfxAdaptiveOut").append(layer).append(";\n");
        if (key.words() != 0) text.append("uint lfxAdaptiveWords[").append(key.words()).append("];\n");
        int cursor = insertion;
        for (var output : parsed.outputs().stream().sorted(Comparator.comparingInt(GlslOutputDeclaration::start)).toList()) {
            if (output.array() || output.declarators() != 1
                    || !output.type().matches("float|int|uint|[iu]?vec[234]"))
                throw new IllegalArgumentException("Unsupported adaptive material color output: " + output.name());
            text.append(original, cursor, output.start());
            if (output.precision() != null) text.append(output.precision()).append(' ');
            text.append(output.type()).append(' ').append(output.name()).append(';');
            cursor = output.end();
        }
        text.append(original.substring(cursor)).append("\n#undef main\nvoid main() {\n");
        if (key.words() != 0) text.append("    for (int word = 0; word < ").append(key.words()).append("; word++) lfxAdaptiveWords[word] = 0u;\n");
        text.append("    lfxAdaptiveCoverageMain();\n");
        for (int layer = 0; layer < key.colorCount(); layer++) {
            text.append("    lfxAdaptiveOut").append(layer).append(" = uvec4(");
            for (int component = 0; component < AdaptiveParticleBuffer.WORDS_PER_LAYER; component++) {
                int word = (key.firstLayer() + layer) * AdaptiveParticleBuffer.WORDS_PER_LAYER + component;
                if (component != 0) text.append(", ");
                text.append(word < key.words() ? "lfxAdaptiveWords[" + word + "]" : "0u");
            }
            text.append(");\n");
        }
        return text.append("}\n").toString();
    }

    public void clear() { pipelines.clear(); }
}
