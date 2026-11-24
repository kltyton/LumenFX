package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.client.render.texture.NativeColorTarget;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.effect.render.decal.DBufferTexture;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Native DBuffer color, normal, material and AO surfaces share one viewport and frame lifetime. */
public final class DecalBuffers implements AutoCloseable {
    public record Frame(GpuTextureView color, GpuTextureView normal, GpuTextureView material, int width, int height, int outputs) {
        public GpuTextureView texture(DBufferTexture texture) {
            return switch (texture) { case BASE_COLOR -> color; case WORLD_NORMAL -> normal; case MATERIAL -> material; };
        }
    }
    private @Nullable NativeColorTarget color, normal, material;
    private @Nullable RenderTarget ambientOcclusion;
    private int width, height;

    public void prepare(int width, int height, CommandEncoder encoder) {
        RenderSystem.assertOnRenderThread();
        if (color == null || this.width != width || this.height != height) resize(width, height);
        encoder.clearColorTexture(colorTarget().texture(), new Vector4f(0, 0, 0, 1));
        encoder.clearColorTexture(normalTarget().texture(), new Vector4f(128f / 255, 128f / 255, 128f / 255, 1));
        encoder.clearColorTexture(materialTarget().texture(), new Vector4f(0, 0, 0, 1));
    }

    public RenderTarget prepareAmbientOcclusion(CommandEncoder encoder) {
        if (ambientOcclusion == null)
            ambientOcclusion = new OwnedColorTarget("LumenFX decal ambient occlusion", width, height, GpuFormat.R8_UNORM);
        encoder.clearColorTexture(ambientOcclusion.getColorTexture(), new Vector4f(1));
        return ambientOcclusion;
    }

    private void resize(int width, int height) {
        NativeColorTarget nextColor = null, nextNormal = null, nextMaterial = null;
        try {
            nextColor = new NativeColorTarget("LumenFX DBuffer A", width, height, true);
            nextNormal = new NativeColorTarget("LumenFX DBuffer B", width, height, false);
            nextMaterial = new NativeColorTarget("LumenFX DBuffer C", width, height, false);
        } catch (RuntimeException | Error error) {
            if (nextColor != null) nextColor.close();
            if (nextNormal != null) nextNormal.close();
            if (nextMaterial != null) nextMaterial.close();
            throw error;
        }
        close();
        color = nextColor; normal = nextNormal; material = nextMaterial;
        this.width = width; this.height = height;
    }

    public RenderPassDescriptor descriptor() {
        return RenderPassDescriptor.create(() -> "LumenFX decal material buffers")
                .withColorAttachment(colorTarget().view()).withColorAttachment(normalTarget().view()).withColorAttachment(materialTarget().view())
                .withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, width, height));
    }

    public NativeColorTarget colorTarget() { return java.util.Objects.requireNonNull(color, "DBuffer has not been prepared"); }
    public NativeColorTarget normalTarget() { return java.util.Objects.requireNonNull(normal, "DBuffer has not been prepared"); }
    public NativeColorTarget materialTarget() { return java.util.Objects.requireNonNull(material, "DBuffer has not been prepared"); }
    public Frame frame(int outputs) {
        return new Frame(colorTarget().view(), normalTarget().view(), materialTarget().view(), width, height, outputs);
    }

    @Override public void close() {
        if (color != null) color.close();
        if (normal != null) normal.close();
        if (material != null) material.close();
        if (ambientOcclusion != null) ambientOcclusion.destroyBuffers();
        color = normal = material = null; ambientOcclusion = null; width = height = 0;
    }
}
