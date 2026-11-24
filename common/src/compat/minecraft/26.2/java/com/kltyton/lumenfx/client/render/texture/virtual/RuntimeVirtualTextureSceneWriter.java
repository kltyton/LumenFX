package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Request;
import com.kltyton.lumenfx.client.render.texture.NativeColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Renders registered primitives through their source material raster before storage encoding. */
public final class RuntimeVirtualTextureSceneWriter implements RuntimeVirtualTextureMaterialPass {
    public interface Scene {
        boolean ready(ClientLevel level);
        List<Draw> prepare(ClientLevel level, Request request, CommandEncoder encoder);
    }

    /** A prepared primitive in the source pass's draw order. */
    public record Draw(RenderPipeline pipeline, Consumer<RenderPass> submit) {
        public Draw {
            Objects.requireNonNull(pipeline);
            Objects.requireNonNull(submit);
        }
    }

    private record Attachment(GpuTextureView view, Runnable release) { }
    private final Scene scene;
    private final Vector4fc fixedColor;
    private final Vector4fc debugMip;

    public RuntimeVirtualTextureSceneWriter(Scene scene, Vector4fc fixedColor, Vector4fc debugMip) {
        this.scene = Objects.requireNonNull(scene);
        this.fixedColor = new Vector4f(fixedColor);
        this.debugMip = new Vector4f(debugMip);
    }

    @Override public boolean ready(ClientLevel level) { return scene.ready(level); }

    @Override public Raster draw(ClientLevel level, Request request) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        var draws = List.copyOf(scene.prepare(level, request, encoder));
        var uniforms = RuntimeVirtualTextureWriterLayout.upload(encoder, request, fixedColor, debugMip);
        var material = request.definition().material();
        int layers = RuntimeVirtualTextureWriterLayout.rasterLayers(material);
        int size = request.view().pixelSize();
        var attachments = new ArrayList<Attachment>(layers);
        Runnable release = () -> attachments.forEach(layer -> layer.release().run());
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX runtime virtual texture primitive raster")
                .withRenderArea(new RenderPass.RenderArea(0, 0, size, size));
        try {
            for (int layer = 0; layer < layers; layer++) {
                var target = target(size, RuntimeVirtualTextureWriterLayout.format(material),
                        RuntimeVirtualTextureWriterLayout.srgb(material, layer));
                attachments.add(target);
                descriptor.withColorAttachment(target.view(), request.definition().clear()
                        ? Optional.of(new Vector4f()) : Optional.empty());
            }
            boolean gl = device.backend instanceof GlDevice;
            boolean srgb = RuntimeVirtualTextureWriterLayout.srgb(material, 0);
            boolean previousSrgb = gl && GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
            if (gl && previousSrgb != srgb) srgb(srgb);
            try (var pass = encoder.createRenderPass(descriptor)) {
                pass.setUniform(RuntimeVirtualTextureWriterLayout.UNIFORM, uniforms);
                for (var primitive : draws) {
                    pass.setPipeline(primitive.pipeline());
                    primitive.submit().accept(pass);
                }
            } finally {
                if (gl && previousSrgb != srgb) srgb(previousSrgb);
            }
            return new Raster(attachments.stream().map(Attachment::view).toList(), release, true);
        } catch (RuntimeException | Error failure) {
            RenderSystem.queueFencedTask(release);
            throw failure;
        }
    }

    private static void srgb(boolean enabled) {
        if (enabled) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
        else GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
    }

    private static Attachment target(int size, GpuFormat format, boolean srgb) {
        if (srgb) {
            var target = new NativeColorTarget("LumenFX RVT primitive sRGB", size, size, true);
            return new Attachment(target.view(), target::close);
        }
        var device = RenderSystem.getDevice();
        var texture = device.createTexture("LumenFX RVT primitive layer",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                format, size, size, 1, 1);
        try {
            var view = device.createTextureView(texture);
            return new Attachment(view, () -> { try { view.close(); } finally { texture.close(); } });
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }
}
