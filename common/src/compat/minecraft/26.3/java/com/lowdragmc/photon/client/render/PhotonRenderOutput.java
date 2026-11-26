/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Active world/external output target resolution for Photon on MC 26.3 RenderPearl. */
public final class PhotonRenderOutput {
    @Nullable
    private static GpuTextureView externalColor;
    @Nullable
    private static GpuTextureView externalDepth;
    @Nullable
    private static GpuTextureView externalOpaqueDepth;
    @Nullable
    private static Matrix4fc externalModelView;

    private PhotonRenderOutput() {
    }

    public static void withExternalOutput(
            @Nullable GpuTextureView color,
            @Nullable GpuTextureView depth,
            @Nullable GpuTextureView opaqueDepth,
            @Nullable Matrix4fc modelView,
            Runnable action) {
        RenderSystem.assertOnRenderThread();
        var prevColor = externalColor;
        var prevDepth = externalDepth;
        var prevOpaqueDepth = externalOpaqueDepth;
        var prevModelView = externalModelView;
        externalColor = color;
        externalDepth = depth;
        externalOpaqueDepth = opaqueDepth;
        externalModelView = modelView;
        try {
            action.run();
        } finally {
            externalColor = prevColor;
            externalDepth = prevDepth;
            externalOpaqueDepth = prevOpaqueDepth;
            externalModelView = prevModelView;
        }
    }

    public static void withModelView(@Nullable Matrix4fc modelView, Runnable action) {
        RenderSystem.assertOnRenderThread();
        var prevModelView = externalModelView;
        externalModelView = modelView;
        try {
            action.run();
        } finally {
            externalModelView = prevModelView;
        }
    }

    @Nullable
    public static GpuTextureView color() {
        if (externalColor != null) {
            return externalColor;
        }
        return Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    @Nullable
    public static GpuTextureView depth() {
        if (externalDepth != null) {
            return externalDepth;
        }
        return Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    @Nullable
    public static GpuTextureView opaqueDepth() {
        return externalOpaqueDepth;
    }

    public static Matrix4f modelView() {
        return externalModelView != null ? new Matrix4f(externalModelView) : RenderSystem.getModelViewMatrixCopy();
    }

    public static ScissorState scissor(int width, int height) {
        var clip = new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        if (!clip.enabled()) {
            return clip;
        }
        int x0 = Math.max(0, clip.x());
        int y0 = Math.max(0, clip.y());
        int x1 = Math.min(width, clip.x() + clip.width());
        int y1 = Math.min(height, clip.y() + clip.height());
        clip.enable(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        return clip;
    }

    public static boolean isEmpty(ScissorState clip) {
        return clip.enabled() && (clip.width() <= 0 || clip.height() <= 0);
    }
}
