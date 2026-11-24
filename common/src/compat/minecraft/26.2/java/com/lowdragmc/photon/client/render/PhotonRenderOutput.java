/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;

/**
 * Where drawing is going right now — the 26.1 answer to "the surface I am compositing into".
 *
 * <p>{@code Minecraft.getMainRenderTarget()} means "the game window", and those two are the same thing
 * only while the window is the only place a frame can land. That assumption is already wrong here: a UI
 * element with a mask or a sub-one opacity renders through LDLib2's picture-in-picture pass, a UI
 * hosted in its own operating-system window has a different target entirely, and an active Iris shaderpack
 * renders world geometry into its own targets instead of {@code mainRenderTarget}. Anything Photon copies
 * from, sizes itself after, or writes back into has to follow the active redirect or it composites into a
 * frame nobody is looking at.
 *
 * <p>Code that genuinely means the game's own frame regardless of where UI is being drawn — the Iris
 * bridge reading the pack's buffers, say — should keep asking Minecraft directly.
 *
 * <p>Render thread only (the overrides are render-thread state).
 */
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

    /**
     * Scopes Photon draws and deferred compositing to an external world composition target (such as LumenFX's
     * Iris shaderpack scene bridge) without mutating Blaze3D global output overrides.
     */
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

    /** Scopes only the model-view matrix used when preparing Photon draws outside the native level pass. */
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

    /** The colour attachment being drawn into, or null when the main target has none. */
    @Nullable
    public static GpuTextureView color() {
        if (externalColor != null) {
            return externalColor;
        }
        return RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    /** The depth attachment being drawn into, or null when the target has none. */
    @Nullable
    public static GpuTextureView depth() {
        if (externalDepth != null) {
            return externalDepth;
        }
        return RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride
                : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    /** Optional host-supplied opaque-only depth (e.g. Iris depth1) for deferred FX layers. */
    @Nullable
    public static GpuTextureView opaqueDepth() {
        return externalOpaqueDepth;
    }

    /** Model-view matrix for Photon draw transforms and dynamic lighting in the active render scope. */
    public static Matrix4f modelView() {
        return externalModelView != null ? new Matrix4f(externalModelView) : RenderSystem.getModelViewMatrixCopy();
    }

    /**
     * The engine's render-type scissor clamped to the target; 26.2 rejects a box outside the render area.
     * Check {@link #isEmpty} before drawing.
     */
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
