/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import org.jspecify.annotations.Nullable;

/**
 * Translates an authored blend into the blend Photon must use while accumulating into a
 * <b>transparent premultiplied layer</b>, so that one {@code ONE / ONE_MINUS_SRC_ALPHA} composite
 * reproduces the authored look.
 *
 * <p>Two things change relative to drawing straight onto the scene:
 *
 * <ul>
 *   <li>The <b>alpha half</b> must carry <i>coverage</i>. Photon's authored default overwrites alpha,
 *       which is harmless when the destination alpha is ignored and fatal here, because the composite
 *       reads it back as "how much of the backdrop did we hide".</li>
 *   <li>Blends that <b>read the destination colour</b> (multiply, min/max, anything with a
 *       {@code DST_*} factor) cannot be reproduced at all: our destination is transparent black, not
 *       the scene.</li>
 * </ul>
 *
 * <p>Additive is the case worth getting right, because it is what most VFX use: coverage must stay
 * untouched ({@code ZERO / ONE}) so glow adds to the backdrop instead of hiding it.
 *
 * <p>The translation preserves the authored RGB factors and rewrites the alpha equation for the selected layer.
 *
 */
public final class PremultipliedBlendPlan {
    public static boolean isLayerStage(PhotonStage stage) {
        return stage == PhotonStage.DEFERRED;
    }

    /** True while a bake is generating geometry for a standalone layer. @see #accumulating() */
    private static boolean accumulating;

    /**
     * Whether pipeline keys built right now must accumulate into a transparent layer.
     *
     * <p>Read by {@code MaterialSetting.pipelineKey} rather than passed down, for the reason 1.21 gave
     * when it read the same answer off the active pipeline: a new renderer cannot forget to opt in and
     * silently punch a hole in the layer's coverage. Render thread only, set around a bake.
     */
    public static boolean accumulating() {
        return accumulating;
    }

    public static void setAccumulating(boolean value) {
        accumulating = value;
    }

    private PremultipliedBlendPlan() {
    }

    /**
     * Whether the authored blend can be reproduced using a separate layer. False for a blend that
     * reads the destination colour or directly replaces it.
     *
     * <p>Mirrors the branch structure of {@link #premultiply}; the two must stay in sync, and this is
     * the routing predicate used to decide which jobs may join a late-composited layer.
     */
    public static boolean isLayerSafe(@Nullable BlendFunction blend) {
        // Blending off means "replace the destination". In a standalone layer the destination is
        // transparent black and the alpha channel is coverage, so a fragment whose shader alpha is
        // below 1 ends up SEMI-TRANSPARENT against the scene instead of opaque — and no fixed-function
        // blend state can pin coverage to 1 independently of the source alpha. Draw these in place
        // to preserve the authored destination replacement.
        if (blend == null) return false;
        var color = blend.color();
        if (!isDestinationFree(color.sourceFactor())) return false;
        var dst = color.destFactor();
        return switch (color.op()) {
            case ADD -> dst == BlendFactor.ZERO || dst == BlendFactor.ONE_MINUS_SRC_ALPHA || dst == BlendFactor.ONE;
            case REVERSE_SUBTRACT -> dst == BlendFactor.ONE;
            default -> false;
        };
    }

    /**
     * Preserves authored RGB factors and stores coverage in the particle layer's alpha.
     */
    public static BlendFunction premultiply(@Nullable BlendFunction blend) {
        if (blend == null) {
            throw new IllegalArgumentException("Particle material requires the current scene as its blend destination");
        }
        return accumulate(blend, false);
    }

    /** Preserve authored RGB factors and store background transmission in the particle layer's alpha. */
    public static BlendFunction transmission(BlendFunction blend) {
        return accumulate(blend, true);
    }

    private static BlendFunction accumulate(BlendFunction blend, boolean transmission) {
        if (!isLayerSafe(blend)) {
            throw new IllegalArgumentException("Particle material requires the current scene as its blend destination");
        }
        var color = blend.color();
        var dstColor = color.destFactor();
        // Additive and subtractive contributions preserve the stored alpha.
        var srcAlpha = transmission || dstColor == BlendFactor.ONE ? BlendFactor.ZERO : BlendFactor.ONE;
        return new BlendFunction(color, new BlendEquation(srcAlpha, dstColor, BlendOp.ADD));
    }

    private static boolean isDestinationFree(BlendFactor factor) {
        return switch (factor) {
            case DST_COLOR, ONE_MINUS_DST_COLOR, DST_ALPHA, ONE_MINUS_DST_ALPHA -> false;
            default -> true;
        };
    }
}
