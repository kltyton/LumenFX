package com.kltyton.lumenfx.client.render.compute.mixin;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds the real Vulkan storage usage bits without reinterpreting any host usage bit. */
@Mixin(VulkanConst.class)
abstract class VulkanComputeUsageMixin {
    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void lumenfx$storageImage(
            final int usage,
            final GpuFormat format,
            final CallbackInfoReturnable<Integer> cir
    ) {
        if ((usage & ComputeUsage.STORAGE_IMAGE) != 0) {
            cir.setReturnValue(cir.getReturnValueI() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        }
    }

    @Inject(method = "bufferUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void lumenfx$storageBuffer(
            final int usage,
            final CallbackInfoReturnable<Integer> cir
    ) {
        if ((usage & ComputeUsage.STORAGE_BUFFER) != 0) {
            cir.setReturnValue(cir.getReturnValueI() | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
    }
}
