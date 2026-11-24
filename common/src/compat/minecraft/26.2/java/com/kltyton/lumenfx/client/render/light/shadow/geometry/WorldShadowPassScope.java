package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowCaster;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/** Redirects only the declared world depth targets during one native geometry submission. */
public final class WorldShadowPassScope {
    public record Expansion(UnaryOperator<RenderPipeline> pipelines, Map<String, GpuBufferSlice> uniforms) { }
    private record State(ShadowDepthTarget target, int face, GpuBufferSlice projection, @Nullable GpuBufferSlice spotCaster,
                         @Nullable GpuBufferSlice directionalCaster, Set<GpuTexture> worldDepths, WorldShadowPipelines pipelines) { }
    private static @Nullable State active;
    private WorldShadowPassScope() { }

    public static void draw(ShadowDepthTarget target, int face, GpuBufferSlice projection, @Nullable GpuBufferSlice spotCaster,
                            @Nullable GpuBufferSlice directionalCaster, Set<GpuTexture> worldDepths,
                            WorldShadowPipelines pipelines, Runnable submit) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(target.face(face));
        if (worldDepths.isEmpty()) throw new IllegalArgumentException("Shadow submission needs its actual host world depth targets");
        State previous = active;
        active = new State(target, face, projection, spotCaster, directionalCaster, Set.copyOf(worldDepths), pipelines);
        try { submit.run(); } finally { active = previous; }
    }

    public static RenderPassDescriptor redirect(RenderPassDescriptor original) {
        State state = active;
        if (state == null || original.depthAttachment() == null
                || !state.worldDepths().contains(original.depthAttachment().textureView().texture())) return original;
        var descriptor = state.target().descriptor(state.face(), original.label());
        for (int i = 1; i < original.colorAttachments().size(); i++) descriptor.withUnusedColorAttachment();
        return descriptor;
    }

    public static @Nullable Expansion expand(RenderPassDescriptor descriptor) {
        State state = active;
        if (state == null || descriptor.depthAttachment() == null
                || descriptor.depthAttachment().textureView() != state.target().face(state.face())) return null;
        var spot = state.spotCaster();
        var directional = state.directionalCaster();
        var uniforms = spot != null ? Map.of("Projection", state.projection(), SpotShadowCaster.UNIFORM, spot)
                : directional != null ? Map.of("Projection", state.projection(), DirectionalShadowCaster.UNIFORM, directional)
                : Map.of("Projection", state.projection());
        return new Expansion(pipeline -> state.pipelines().get(pipeline, spot != null, directional != null), uniforms);
    }

    public static RenderPipeline pipeline(RenderPipeline original,
            List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments) {
        State state = active;
        if (state == null || attachments.isEmpty()) return original;
        var first = attachments.getFirst();
        return first != null && first.textureView() == state.target().rasterColorView()
                ? state.pipelines().get(original, state.spotCaster() != null, state.directionalCaster() != null) : original;
    }
}
