package com.kltyton.lumenfx.client.render.niagara.splat;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Unified render proxy payload produced by Niagara Gaussian Splat writers and splat renderers. */
public record NiagaraGaussianSplatDraw(
        GpuBufferSlice splats,
        int count,
        int stride,
        int degree,
        int coordinateSpace,
        boolean quantized,
        @Nullable ByteBuffer quantizationParameters,
        boolean localSpace,
        Pose pose,
        Vec3 tileOrigin,
        @Nullable NiagaraBounds localBounds,
        float pointScaleFactor,
        float opacityScale,
        boolean invertToneCurve,
        float inverseToneCurveStrength,
        boolean invertExpandGamut
) {
    public NiagaraGaussianSplatDraw {
        if (count < 0 || stride <= 0 || degree < 0 || degree > 3 || coordinateSpace < 0 || coordinateSpace > 2)
            throw new IllegalArgumentException("Invalid Niagara Gaussian Splat draw descriptor");
    }
}
