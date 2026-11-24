package com.kltyton.lumenfx.client.graphics.mixin;

import com.kltyton.lumenfx.client.graphics.GraphicsBackendPolicy;
import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies LumenFX's dynamic graphics backend policy when the user has left Minecraft's graphics API
 * setting on {@link PreferredGraphicsApi#DEFAULT}: prefers Vulkan when supported by the host, driver,
 * and installed render mods, and automatically stays on OpenGL otherwise. Explicit {@code OPENGL} /
 * {@code VULKAN} settings, command-line {@code --graphicsBackend} overrides, and vanilla crash recovery
 * to {@code OPENGL} remain untouched.
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
    @Unique
    private static final Logger LUMENFX_LOGGER = LoggerFactory.getLogger("LumenFX/GraphicsBackend");

    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void lumenfx$applyDefaultBackendPolicy(final CallbackInfoReturnable<GpuBackend[]> cir) {
        if ((Object) this != PreferredGraphicsApi.DEFAULT) {
            return;
        }
        final GraphicsBackendPolicy.Decision decision = GraphicsBackendPolicy.resolveForDefault();
        if (decision.prefersVulkan()) {
            LUMENFX_LOGGER.info("LumenFX default graphics backend policy selected Vulkan with OpenGL fallback ({})",
                    decision.reason());
            cir.setReturnValue(new GpuBackend[]{new VulkanBackend(), new GlBackend()});
        } else {
            LUMENFX_LOGGER.info("LumenFX default graphics backend policy kept OpenGL ({})", decision.reason());
        }
    }
}
