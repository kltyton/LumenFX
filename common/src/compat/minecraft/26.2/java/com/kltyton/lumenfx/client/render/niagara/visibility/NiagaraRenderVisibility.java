package com.kltyton.lumenfx.client.render.niagara.visibility;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
import com.kltyton.lumenfx.client.simulation.EffectInstance;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Per-world component visibility feedback from the real opaque depth buffer. */
public final class NiagaraRenderVisibility implements AutoCloseable {
    private static final int QUERIES_PER_BATCH = 128, QUERY_BYTES = 32, HEADER_BYTES = 16;
    private static final int MAX_PENDING_FRAMES = 4;
    private static final String PARAMETERS = "OcclusionQueries";
    private static final GpuFormat FORMAT = GpuFormat.R8_UNORM;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/niagara/visibility"))
            .withVertexShader(LumenFx.id("post/fullscreen"))
            .withFragmentShader(LumenFx.id("post/depth/niagara_visibility"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER)
                    .withSampler("FurthestDepth").build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    private record Query(EffectInstance effect, long epoch, NiagaraOcclusionBounds bounds) { }
    private record Readback(double time, List<Query> queries, @Nullable GpuBuffer buffer,
                            @Nullable GpuFence fence) implements AutoCloseable {
        boolean ready() { return fence == null || fence.awaitCompletion(0L); }
        @Override public void close() {
            try { if (fence != null) fence.close(); }
            finally { if (buffer != null) buffer.close(); }
        }
    }
    private record Target(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override public void close() {
            try { view.close(); }
            finally { texture.close(); }
        }
    }

    private final HierarchicalSceneDepth depth = new HierarchicalSceneDepth();
    private final ArrayDeque<Readback> pending = new ArrayDeque<>();
    private @Nullable Target target;
    private double completedTime = Double.NaN;

    public double completedTime() { return completedTime; }

    public void submit(GpuTextureView opaqueDepth, Matrix4fc clip, Vec3 camera, double time,
                       List<EffectInstance> instances) {
        poll();
        if (pending.size() >= MAX_PENDING_FRAMES) return;
        var device = RenderSystem.getDevice();
        var queries = new ArrayList<Query>();
        for (var effect : instances) {
            if (!effect.hasVisibleNiagaraProxy()) continue;
            var rect = NiagaraOcclusionBounds.projectWorld(clip, camera,
                    effect.scalabilityWorldBounds(effect.scalabilityPose()),
                    device.getDeviceInfo().isZZeroToOne());
            if (rect == null) continue;
            if (rect.nearPlane() || !effect.canOccludeNiagaraProxy()) effect.markNiagaraRendered(time);
            else queries.add(new Query(effect, effect.visibilityEpoch(), rect));
        }
        if (queries.isEmpty()) {
            pending.addLast(new Readback(time, List.of(), null, null));
            poll();
            return;
        }
        var encoder = device.createCommandEncoder();
        var hzb = depth.buildFurthest(encoder, opaqueDepth);
        var output = target();
        int batches = Math.ceilDiv(queries.size(), QUERIES_PER_BATCH);
        GpuBuffer staging = device.createBuffer(() -> "LumenFX Niagara visibility readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long)batches * QUERIES_PER_BATCH);
        GpuFence fence = null;
        try {
            var point = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            for (int first = 0; first < queries.size(); first += QUERIES_PER_BATCH) {
                int count = Math.min(QUERIES_PER_BATCH, queries.size() - first);
                var uniform = uniform(encoder, queries, first, count, opaqueDepth, hzb.depth());
                try (var pass = encoder.createRenderPass(() -> "LumenFX Niagara component occlusion", output.view(), Optional.empty())) {
                    pass.setPipeline(PIPELINE);
                    pass.setUniform(PARAMETERS, uniform);
                    pass.bindTexture("FurthestDepth", hzb.depth(), point);
                    pass.draw(3, 1, 0, 0);
                }
                encoder.copyTextureToBuffer(output.texture(), staging, first, () -> { }, 0);
            }
            fence = encoder.createFence();
            pending.addLast(new Readback(time, List.copyOf(queries), staging, fence));
        } catch (RuntimeException | Error failure) {
            if (fence != null) fence.close();
            staging.close();
            throw failure;
        }
    }

    public void poll() {
        while (!pending.isEmpty() && pending.getFirst().ready()) {
            try (Readback result = pending.removeFirst()) {
                if (result.buffer() != null) {
                    try (var mapped = result.buffer().map(true, false)) {
                        var samples = mapped.data();
                        for (int index = 0; index < result.queries().size(); index++) {
                            var query = result.queries().get(index);
                            if (samples.get(index) != 0 && query.effect().handle().isAlive()
                                    && query.effect().visibilityEpoch() == query.epoch())
                                query.effect().markNiagaraRendered(result.time());
                        }
                    }
                }
                completedTime = result.time();
            }
        }
    }

    private static GpuBufferSlice uniform(CommandEncoder encoder, List<Query> queries, int first, int count,
                                          GpuTextureView source, GpuTextureView hzb) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int maximumMip = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(Math.max(hzb.getWidth(0), hzb.getHeight(0)));
        try (var mapped = encoder.transientMemory().allocateGpuMapped(HEADER_BYTES + QUERIES_PER_BATCH * QUERY_BYTES,
                alignment, GpuBuffer.USAGE_UNIFORM)) {
            var buffer = mapped.data().order(ByteOrder.nativeOrder());
            buffer.putFloat(source.getWidth(0)).putFloat(source.getHeight(0)).putFloat(maximumMip).putFloat(count);
            for (int index = 0; index < count; index++) {
                var rect = queries.get(first + index).bounds();
                buffer.putFloat(rect.minX()).putFloat(rect.minY()).putFloat(rect.maxX()).putFloat(rect.maxY());
                buffer.putFloat(rect.nearestDepth()).putFloat(0).putFloat(0).putFloat(0);
            }
            return mapped.slice();
        }
    }

    private Target target() {
        if (target != null) return target;
        var device = RenderSystem.getDevice();
        var texture = device.createTexture("LumenFX Niagara component visibility",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, FORMAT, QUERIES_PER_BATCH, 1, 1, 1);
        try {
            target = new Target(texture, device.createTextureView(texture));
            return target;
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    @Override public void close() {
        while (!pending.isEmpty()) pending.removeFirst().close();
        completedTime = Double.NaN;
        try { depth.close(); }
        finally {
            if (target != null) target.close();
            target = null;
        }
    }
}
