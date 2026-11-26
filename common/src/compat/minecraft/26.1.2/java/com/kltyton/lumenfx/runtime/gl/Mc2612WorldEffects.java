package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;

/** Binds the world particle attachments between Minecraft's managed render passes. */
public final class Mc2612WorldEffects {
    private Mc2612WorldEffects() { }

    public static void render(CameraRenderState camera) {
        if (!camera.initialized) return;
        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        DirectVfxEngine engine = DirectVfxEngine.INSTANCE;
        if (engine.activeInstanceCount() == 0) {
            render(engine, camera, partialTick);
            return;
        }

        RenderTarget target = minecraft.levelRenderer.getParticlesTarget();
        if (target == null) target = minecraft.getMainRenderTarget();
        // Loader events run between passes. Binding the actual attachments also gives HDR and soft
        // particles the depth and viewport of the world instead of the default framebuffer.
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "LumenFX world particles", target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            render(engine, camera, partialTick);
        }
    }

    private static void render(DirectVfxEngine engine, CameraRenderState camera, float partialTick) {
        engine.renderLevel(new Matrix4f(camera.projectionMatrix), new Matrix4f(camera.viewRotationMatrix),
                camera.pos.x, camera.pos.y, camera.pos.z, partialTick);
    }
}
