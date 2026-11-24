package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.LumenFx;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Material attachments share the host draw's actual depth, clipping and callbacks. */
public final class SceneSurfaceCapture implements AutoCloseable {
    /** Normal RGB carries transmission and W native AO; geometry stores view XY, reception bits and device depth. */
    public record Surfaces(GpuTextureView albedo, GpuTextureView normalAmbient, GpuTextureView positionDepth, int width, int height,
                           @Nullable NativeMaterialResponse materialResponse) {
        public Surfaces(GpuTextureView albedo, GpuTextureView normalAmbient, GpuTextureView positionDepth, int width, int height) {
            this(albedo, normalAmbient, positionDepth, width, height, null);
        }
    }
    public record Expansion(RenderPassDescriptor descriptor, UnaryOperator<RenderPipeline> pipeline) { }
    private static final AtomicLong NEXT = new AtomicLong();
    private static @Nullable SceneSurfaceCapture active;
    private record Variant(RenderPipeline pipeline, boolean materialResponse) { }
    private final Map<Variant, RenderPipeline> variants = new java.util.HashMap<>();
    private @Nullable SceneSurfaceShaderAdapter shaders;
    private TextureTarget albedo, normals, positions;
    private @Nullable TextureTarget responseBase, responseZero, responseUnit;
    private RenderTarget source;
    private boolean frame;
    private boolean materialResponse;

    public void begin(RenderTarget scene) {
        begin(scene, false);
    }

    public void begin(RenderTarget scene, boolean materialResponse) {
        RenderSystem.assertOnRenderThread();
        if (active != null) throw new IllegalStateException("World surface capture is already active");
        resize(scene.width, scene.height);
        if (materialResponse) resizeResponse(scene.width, scene.height);
        this.materialResponse = materialResponse;
        source = scene; frame = true; active = this;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var clear = new Vector4f();
        encoder.clearColorTexture(albedo.getColorTexture(), clear);
        encoder.clearColorTexture(normals.getColorTexture(), clear);
        encoder.clearColorTexture(positions.getColorTexture(), clear);
        if (materialResponse) {
            encoder.clearColorTexture(responseBase.getColorTexture(), clear);
            encoder.clearColorTexture(responseZero.getColorTexture(), clear);
            encoder.clearColorTexture(responseUnit.getColorTexture(), clear);
        }
    }

    public void end() { if (active == this) active = null; }
    public void discardFrame() { end(); frame = false; }
    public @Nullable Surfaces surfaces() {
        return !frame ? null : new Surfaces(albedo.getColorTextureView(), normals.getColorTextureView(), positions.getColorTextureView(), source.width, source.height,
                !materialResponse ? null : new NativeMaterialResponse(responseBase.getColorTextureView(), responseZero.getColorTextureView(), responseUnit.getColorTextureView()));
    }

    public static @Nullable Expansion expand(RenderPassDescriptor descriptor) {
        SceneSurfaceCapture capture = active;
        if (capture == null || descriptor.depthAttachment == null || descriptor.colorAttachments.size() != 1
                || descriptor.colorAttachments.getFirst() == null
                || descriptor.depthAttachment.textureView().texture() != capture.source.getDepthTexture()
                || descriptor.colorAttachments.getFirst().textureView().texture() != capture.source.getColorTexture()) return null;
        int attachments = capture.materialResponse ? 7 : 4;
        if (RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments() < attachments)
            throw new IllegalStateException("World material capture requires " + attachments + " color attachments");
        var color = descriptor.colorAttachments.getFirst();
        var expanded = RenderPassDescriptor.create(descriptor.label()).withColorAttachment(color.textureView(), color.clearValue())
                .withDepthAttachment(descriptor.depthAttachment.textureView(), descriptor.depthAttachment.clearValue())
                .withRenderArea(java.util.Objects.requireNonNull(descriptor.renderArea));
        Optional<org.joml.Vector4fc> clear = color.clearValue().isPresent() || descriptor.depthAttachment.clearValue().isPresent()
                ? Optional.of(new Vector4f()) : Optional.empty();
        expanded.withColorAttachment(capture.albedo.getColorTextureView(), clear)
                .withColorAttachment(capture.normals.getColorTextureView(), clear)
                .withColorAttachment(capture.positions.getColorTextureView(), clear);
        if (capture.materialResponse) expanded.withColorAttachment(capture.responseBase.getColorTextureView(), clear)
                .withColorAttachment(capture.responseZero.getColorTextureView(), color.clearValue())
                .withColorAttachment(capture.responseUnit.getColorTextureView(), color.clearValue());
        return new Expansion(expanded, capture::variant);
    }

    private RenderPipeline variant(RenderPipeline host) {
        var key = new Variant(host, materialResponse);
        RenderPipeline cached = variants.get(key);
        if (cached != null) return cached;
        var color = host.getColorTargetState();
        var blend = color.blendFunction();
        boolean colorWrites = (color.writeMask() & 7) != 0;
        boolean affineBlend = !materialResponse || !colorWrites || NativeSurfaceResponseContract.isAffineBlend(blend);
        boolean opaque = blend.isEmpty() && host.getDepthStencilState() != null && colorWrites;
        boolean transmission = affineBlend && colorWrites && (blend.equals(Optional.of(BlendFunction.TRANSLUCENT))
                || blend.equals(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)));
        var mode = !affineBlend ? SceneSurfaceShaderAdapter.Mode.IGNORE
                : opaque ? SceneSurfaceShaderAdapter.Mode.OPAQUE
                : transmission ? SceneSurfaceShaderAdapter.Mode.TRANSMISSION : SceneSurfaceShaderAdapter.Mode.IGNORE;
        if (shaders == null) shaders = new SceneSurfaceShaderAdapter((id, type) -> Minecraft.getInstance().getShaderManager().getShader(id, type));
        var set = shaders.create(host, mode, materialResponse);
        var builder = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(host)
                .withLocation(com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess.derivedPipelineLocation(
                        host, "pipeline/scene_surface/" + NEXT.getAndIncrement()))
                .withVertexShader(set.vertex()).withFragmentShader(set.fragment())
                .withColorTargetState(0, color)
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, opaque ? ColorTargetState.WRITE_ALL : 0))
                .withColorTargetState(2, new ColorTargetState(transmission ? blend : Optional.empty(),
                        GpuFormat.RGBA32_FLOAT, opaque ? ColorTargetState.WRITE_ALL : transmission ? 7 : 0))
                .withColorTargetState(3, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, opaque ? ColorTargetState.WRITE_ALL : 0));
        if (materialResponse) {
            builder.withColorTargetState(4, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, opaque ? 15 : 0));
            for (int i = 5; i < 7; i++) builder.withColorTargetState(i,
                    new ColorTargetState(affineBlend ? blend : Optional.empty(), GpuFormat.RGBA32_FLOAT, affineBlend ? color.writeMask() : 0));
        }
        RenderPipeline pipeline = builder.build();
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, set.source()).isValid())
            throw new IllegalArgumentException("World surface pipeline failed to compile: " + host.getLocation());
        variants.put(key, pipeline); return pipeline;
    }

    private void resize(int width, int height) {
        if (albedo == null) {
            albedo = new TextureTarget("LumenFX surface base", width, height, false, GpuFormat.RGBA32_FLOAT);
            try {
                normals = new TextureTarget("LumenFX surface normal and AO", width, height, false, GpuFormat.RGBA32_FLOAT);
                positions = new TextureTarget("LumenFX surface position", width, height, false, GpuFormat.RGBA32_FLOAT);
            } catch (RuntimeException | Error error) { close(); throw error; }
        } else if (albedo.width != width || albedo.height != height) {
            albedo.resize(width, height); normals.resize(width, height); positions.resize(width, height);
        }
    }

    private void resizeResponse(int width, int height) {
        if (responseBase == null) {
            try {
                responseBase = new TextureTarget("LumenFX material texture base", width, height, false, GpuFormat.RGBA32_FLOAT);
                responseZero = new TextureTarget("LumenFX material zero response", width, height, false, GpuFormat.RGBA32_FLOAT);
                responseUnit = new TextureTarget("LumenFX material unit response", width, height, false, GpuFormat.RGBA32_FLOAT);
            } catch (RuntimeException | Error error) { close(); throw error; }
        } else if (responseBase.width != width || responseBase.height != height) {
            responseBase.resize(width, height); responseZero.resize(width, height); responseUnit.resize(width, height);
        }
    }

    @Override public void close() {
        end(); frame = false; source = null;
        if (albedo != null) albedo.destroyBuffers();
        if (normals != null) normals.destroyBuffers();
        if (positions != null) positions.destroyBuffers();
        if (responseBase != null) responseBase.destroyBuffers();
        if (responseZero != null) responseZero.destroyBuffers();
        if (responseUnit != null) responseUnit.destroyBuffers();
        responseBase = responseZero = responseUnit = null; materialResponse = false;
        albedo = normals = positions = null; shaders = null; variants.clear();
    }
}
