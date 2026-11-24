package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowCaster;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Host geometry retains its shaders, coverage, bindings and callbacks while only real depth writers cast. */
public final class WorldShadowPipelines {
    private static final AtomicLong NEXT = new AtomicLong();
    private static final Identifier ENTITY_VERTEX = Identifier.withDefaultNamespace("core/entity");
    private record Variant(RenderPipeline host, boolean spot, boolean directional) { }
    private final Map<Variant, RenderPipeline> variants = new HashMap<>();
    private final Set<RenderPipeline> prepared = Collections.newSetFromMap(new IdentityHashMap<>());

    public RenderPipeline get(RenderPipeline host, boolean spot, boolean directional) {
        if (prepared.contains(host)) return host;
        RenderPipeline result = variants.computeIfAbsent(new Variant(host, spot, directional), WorldShadowPipelines::create);
        prepared.add(result);
        return result;
    }

    private static RenderPipeline create(Variant variant) {
        var host = variant.host();
        var depth = host.getDepthStencilState();
        boolean writesDepth = depth != null && depth.writeDepth();
        var id = com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess.derivedPipelineLocation(
                host, "pipeline/point_shadow_world/" + NEXT.getAndIncrement());
        var builder = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(host).withLocation(id)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writesDepth))
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 0));
        for (int i = 1; i < host.getColorTargetStates().length; i++) builder.withUnusedColorTargetState(i);
        if ((!variant.spot() && !variant.directional()) || !writesDepth) return builder.build();
        boolean entityNormal = host.getVertexShader().equals(ENTITY_VERTEX);
        if (variant.spot()) builder.withFragmentShader(id).withBindGroupLayout(SpotShadowCaster.LAYOUT);
        if (variant.directional()) {
            builder.withVertexShader(id).withBindGroupLayout(DirectionalShadowCaster.LAYOUT);
            if (!entityNormal) builder.withFragmentShader(id);
        }
        var pipeline = builder.build();
        var shaders = Minecraft.getInstance().getShaderManager();
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, stage) -> {
            if (!shader.equals(id)) return shaders.getShader(shader, stage);
            if (stage == ShaderType.VERTEX)
                return DirectionalShadowCaster.vertex(Objects.requireNonNull(shaders.getShader(host.getVertexShader(), stage)), entityNormal);
            String source = Objects.requireNonNull(shaders.getShader(host.getFragmentShader(), stage));
            return variant.spot() ? SpotShadowCaster.fragment(source, "") : DirectionalShadowCaster.fragment(source, "");
        }).isValid())
            throw new IllegalStateException("Failed to prepare projected world caster " + host.getLocation());
        return pipeline;
    }

    public void clear() { variants.clear(); prepared.clear(); }
}
