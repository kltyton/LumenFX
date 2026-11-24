package com.kltyton.lumenfx.client.render.depth;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Native custom depth plus independently writable stencil bits resolved to the source RG8_UINT layout. */
public final class ParticleCustomDepthTargets implements AutoCloseable {
    private record Seed(boolean depth, boolean stencil, boolean writesStencil) { }
    private static final RenderPipeline RESOLVE = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/custom_depth/resolve_stencil"))
            .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/custom_depth/resolve_stencil"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("StencilLow").withSampler("StencilHigh").build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RG8_UINT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    private final Map<Seed, RenderPipeline> seedPipelines = new HashMap<>();
    private @Nullable TextureTarget depth;
    private @Nullable OwnedColorTarget stencilLow, stencilHigh, packedStencil;

    public boolean hasStencil() { return packedStencil != null; }
    public GpuTextureView depth() { return Objects.requireNonNull(Objects.requireNonNull(depth).getDepthTextureView()); }
    public @Nullable GpuTextureView stencil() { return packedStencil == null ? null : packedStencil.getColorTextureView(); }

    public RenderPassDescriptor begin(CommandEncoder encoder, RenderTarget scene, NiagaraSceneInputs inputs, boolean stencil) {
        ensure(scene.width, scene.height, stencil);
        var sourceDepth = source(inputs, NiagaraSceneInputs.Texture.CUSTOM_DEPTH, scene);
        var sourceStencil = stencil ? source(inputs, NiagaraSceneInputs.Texture.CUSTOM_STENCIL, scene) : null;
        var raster = Objects.requireNonNull(depth);
        var zero = new Vector4f();
        encoder.clearColorTexture(Objects.requireNonNull(raster.getColorTexture()), zero);
        if (sourceDepth == null && sourceStencil == null) {
            encoder.clearDepthTexture(Objects.requireNonNull(raster.getDepthTexture()), 0);
            if (stencil) {
                encoder.clearColorTexture(Objects.requireNonNull(stencilLow).getColorTexture(), zero);
                encoder.clearColorTexture(Objects.requireNonNull(stencilHigh).getColorTexture(), zero);
            }
        } else {
            var key = new Seed(sourceDepth != null, sourceStencil != null, stencil);
            var pipeline = seedPipelines.computeIfAbsent(key, ParticleCustomDepthTargets::seedPipeline);
            try (var pass = encoder.createRenderPass(descriptor("LumenFX custom depth host inputs"))) {
                var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.setPipeline(pipeline);
                if (sourceDepth != null) pass.bindTexture("HostCustomDepth", sourceDepth, sampler);
                if (sourceStencil != null) pass.bindTexture("HostCustomStencil", sourceStencil, sampler);
                pass.draw(3, 1, 0, 0);
            }
        }
        return descriptor("LumenFX particle custom depth");
    }

    public void resolveStencil(CommandEncoder encoder) {
        var target = packedStencil;
        if (target == null) return;
        try (var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> "LumenFX pack custom stencil")
                .withColorAttachment(target.getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height)))) {
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setPipeline(RESOLVE);
            pass.bindTexture("StencilLow", Objects.requireNonNull(stencilLow).getColorTextureView(), sampler);
            pass.bindTexture("StencilHigh", Objects.requireNonNull(stencilHigh).getColorTextureView(), sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    public RenderPassDescriptor descriptor(String name) {
        var raster = Objects.requireNonNull(depth);
        var descriptor = RenderPassDescriptor.create(() -> name).withColorAttachment(raster.getColorTextureView())
                .withDepthAttachment(Objects.requireNonNull(raster.getDepthTextureView()), OptionalDouble.empty())
                .withRenderArea(new RenderPass.RenderArea(0, 0, raster.width, raster.height));
        if (hasStencil()) descriptor.withColorAttachment(Objects.requireNonNull(stencilLow).getColorTextureView())
                .withColorAttachment(Objects.requireNonNull(stencilHigh).getColorTextureView());
        return descriptor;
    }

    private static RenderPipeline seedPipeline(Seed key) {
        var bindings = BindGroupLayout.builder();
        var pipeline = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/custom_depth/seed_"
                        + (key.depth() ? "depth" : "empty") + (key.stencil() ? "_stencil" : key.writesStencil() ? "_zero_stencil" : "")))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/custom_depth/seed"))
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 0))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true)).withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (key.depth()) {
            pipeline.withShaderDefine("LFX_HOST_CUSTOM_DEPTH");
            bindings.withSampler("HostCustomDepth");
        }
        if (key.stencil()) {
            pipeline.withShaderDefine("LFX_HOST_CUSTOM_STENCIL");
            bindings.withSampler("HostCustomStencil");
        }
        if (key.writesStencil()) {
            pipeline.withShaderDefine("LFX_WRITE_CUSTOM_STENCIL");
            for (int i = 1; i <= 2; i++) pipeline.withColorTargetState(i,
                    new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        }
        return pipeline.withBindGroupLayout(bindings.build()).build();
    }

    private static @Nullable GpuTextureView source(NiagaraSceneInputs inputs, NiagaraSceneInputs.Texture field, RenderTarget scene) {
        var view = inputs.textures().get(field);
        if (view != null && (view.getWidth(0) != scene.width || view.getHeight(0) != scene.height))
            throw new IllegalArgumentException("Custom depth scene input has a different viewport: " + field);
        return view;
    }

    private void ensure(int width, int height, boolean stencil) {
        if (depth != null && depth.width == width && depth.height == height && stencil == hasStencil()) return;
        TextureTarget next = null;
        OwnedColorTarget low = null, high = null, packed = null;
        try {
            next = new TextureTarget("LumenFX custom depth raster", width, height, true, GpuFormat.R8_UNORM);
            if (stencil) {
                low = new OwnedColorTarget("LumenFX custom stencil low bits", width, height, GpuFormat.RGBA8_UNORM);
                high = new OwnedColorTarget("LumenFX custom stencil high bits", width, height, GpuFormat.RGBA8_UNORM);
                packed = new OwnedColorTarget("LumenFX custom stencil", width, height, GpuFormat.RG8_UINT);
            }
        } catch (RuntimeException | Error failure) {
            if (next != null) next.destroyBuffers();
            if (low != null) low.closeOnFailure(failure);
            if (high != null) high.closeOnFailure(failure);
            if (packed != null) packed.closeOnFailure(failure);
            throw failure;
        }
        close();
        depth = next; stencilLow = low; stencilHigh = high; packedStencil = packed;
    }

    @Override public void close() {
        if (depth != null) { depth.destroyBuffers(); depth = null; }
        if (stencilLow != null) { stencilLow.close(); stencilLow = null; }
        if (stencilHigh != null) { stencilHigh.close(); stencilHigh = null; }
        if (packedStencil != null) { packedStencil.close(); packedStencil = null; }
    }
}
