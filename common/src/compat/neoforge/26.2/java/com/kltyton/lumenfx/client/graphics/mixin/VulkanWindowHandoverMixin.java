package com.kltyton.lumenfx.client.graphics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's early loading screen creates an OpenGL-context GLFW window, which GLFW rejects when
 * {@link com.mojang.blaze3d.vulkan.VulkanGpuSurface} calls {@code glfwCreateWindowSurface}.
 * When {@link VulkanBackend} is selected, cleanly take over and replace an OpenGL early window with a
 * {@code GLFW_NO_API} window; if a later fallback to OpenGL occurs, create a fresh OpenGL window instead
 * of reusing the destroyed early handle.
 */
@Mixin(Window.class)
abstract class VulkanWindowHandoverMixin {
    @Unique
    private static final Logger LUMENFX_WINDOW_LOGGER = LoggerFactory.getLogger("LumenFX/GraphicsBackend");
    @Unique
    private static boolean lumenfx$earlyWindowDestroyed;

    @WrapOperation(
            method = "createGlfwWindow(IILjava/lang/String;JLcom/mojang/blaze3d/systems/GpuBackend;)J",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/EarlyLoadingScreenController;takeOverGlfwWindow()J"
            )
    )
    private static long lumenfx$handoverWindowForBackend(
            final EarlyLoadingScreenController controller,
            final Operation<Long> original,
            final int width,
            final int height,
            final String title,
            final long monitor,
            final GpuBackend backend
    ) {
        if (lumenfx$earlyWindowDestroyed) {
            return GLFW.glfwCreateWindow(width, height, title, monitor, 0L);
        }
        final long earlyHandle = original.call(controller);
        if (!(backend instanceof VulkanBackend) || earlyHandle == 0L) {
            return earlyHandle;
        }
        final int clientApi = GLFW.glfwGetWindowAttrib(earlyHandle, GLFW.GLFW_CLIENT_API);
        if (clientApi == GLFW.GLFW_NO_API) {
            return earlyHandle;
        }
        final int[] w = new int[1];
        final int[] h = new int[1];
        GLFW.glfwGetWindowSize(earlyHandle, w, h);
        final int targetWidth = w[0] > 0 ? w[0] : width;
        final int targetHeight = h[0] > 0 ? h[0] : height;
        final boolean wasVisible = GLFW.glfwGetWindowAttrib(earlyHandle, GLFW.GLFW_VISIBLE) != 0;
        // Shut down NeoForge's early OpenGL loading-screen renderer with its GL context active before
        // destroying earlyHandle so ClientModLoader.finish() -> periodicTick() never touches the old handle.
        controller.completeProgress();
        GLFW.glfwMakeContextCurrent(earlyHandle);
        try {
            GL.createCapabilities();
            closeEarlyDisplay(controller);
        } finally {
            GL.setCapabilities(null);
            GLFW.glfwMakeContextCurrent(0L);
        }
        GLFW.glfwDestroyWindow(earlyHandle);
        lumenfx$earlyWindowDestroyed = true;

        GLFW.glfwDefaultWindowHints();
        backend.setWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        final long vulkanHandle = GLFW.glfwCreateWindow(targetWidth, targetHeight, title, monitor, 0L);
        if (vulkanHandle != 0L && wasVisible) {
            GLFW.glfwShowWindow(vulkanHandle);
        }
        LUMENFX_WINDOW_LOGGER.info(
                "Replaced NeoForge OpenGL early-display window with GLFW_NO_API window ({}x{}) for Vulkan backend",
                targetWidth, targetHeight);
        return vulkanHandle;
    }

    /**
     * NeoForge's {@code DisplayWindow} lives in the bootstrap {@code earlydisplay} module loaded before
     * mod/Mixin transformation and is not exposed on {@link EarlyLoadingScreenController}, so AT/Mixin cannot
     * target it directly. Invoked at most once during client startup to release the early GL renderer cleanly.
     */
    @Unique
    private static void closeEarlyDisplay(final EarlyLoadingScreenController controller) {
        try {
            if (controller instanceof AutoCloseable closeable) {
                closeable.close();
                return;
            }
            controller.getClass().getMethod("close").invoke(controller);
        } catch (Exception failure) {
            LUMENFX_WINDOW_LOGGER.warn("Could not close NeoForge early display before Vulkan window replacement", failure);
        }
    }
}
