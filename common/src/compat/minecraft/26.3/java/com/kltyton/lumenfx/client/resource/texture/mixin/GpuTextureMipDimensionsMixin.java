package com.kltyton.lumenfx.client.resource.texture.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Clamps non-square mip level extents to at least 1x1 to match GL and Vulkan storage rules on MC 26.3. */
@Mixin(BaseGpuTexture.class)
abstract class GpuTextureMipDimensionsMixin {
    @Shadow @Final private int width;
    @Shadow @Final private int height;

    @ModifyReturnValue(method = "getWidth(I)I", at = @At("RETURN"))
    private int lumenfx$clampMipWidth(int original) {
        return width > 0 ? Math.max(1, original) : original;
    }

    @ModifyReturnValue(method = "getHeight(I)I", at = @At("RETURN"))
    private int lumenfx$clampMipHeight(int original) {
        return height > 0 ? Math.max(1, original) : original;
    }
}
