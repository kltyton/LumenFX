package com.kltyton.lumenfx.client.graphics;

import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;

/**
 * Dynamically selects the graphics backend trial order for {@link PreferredGraphicsApi#DEFAULT}:
 * prefers Vulkan with automatic OpenGL fallback whenever the host, GPU/driver, and installed render
 * mod combination support Vulkan; otherwise automatically stays on OpenGL. Explicit
 * {@link PreferredGraphicsApi#OPENGL} and {@link PreferredGraphicsApi#VULKAN} user choices are never overridden.
 */
public final class GraphicsBackendPolicy {
    public enum BackendOrder {
        VULKAN_FIRST,
        OPENGL_FIRST
    }

    public record Decision(BackendOrder order, String reason) {
        public boolean prefersVulkan() {
            return this.order == BackendOrder.VULKAN_FIRST;
        }
    }

    private GraphicsBackendPolicy() {
    }

    public static Decision resolveForDefault() {
        final String openGlOnlyMod = detectOpenGlOnlyRenderMod();
        if (openGlOnlyMod != null) {
            return new Decision(BackendOrder.OPENGL_FIRST, "OpenGL-only render mod detected: " + openGlOnlyMod);
        }
        final BackendCreationException unavailable = checkVulkanAvailability();
        return decide(PreferredGraphicsApi.DEFAULT, unavailable == null, null,
                unavailable == null ? null : unavailable.getMessage());
    }

    public static Decision decide(
            final PreferredGraphicsApi requestedApi,
            final boolean vulkanAvailable,
            final @Nullable String openGlOnlyMod,
            final @Nullable String vulkanUnavailableReason
    ) {
        if (requestedApi == PreferredGraphicsApi.OPENGL) {
            return new Decision(BackendOrder.OPENGL_FIRST, "explicit OpenGL preference");
        }
        if (requestedApi == PreferredGraphicsApi.VULKAN) {
            return new Decision(BackendOrder.VULKAN_FIRST, "explicit Vulkan preference");
        }
        if (openGlOnlyMod != null && !openGlOnlyMod.isBlank()) {
            return new Decision(BackendOrder.OPENGL_FIRST, "OpenGL-only render mod detected: " + openGlOnlyMod);
        }
        if (!vulkanAvailable) {
            final String detail = vulkanUnavailableReason == null || vulkanUnavailableReason.isBlank()
                    ? "host or driver unavailable"
                    : vulkanUnavailableReason;
            return new Decision(BackendOrder.OPENGL_FIRST, "Vulkan unavailable (" + detail + ")");
        }
        return new Decision(BackendOrder.VULKAN_FIRST, "Vulkan available");
    }

    static @Nullable String detectOpenGlOnlyRenderMod() {
        if (ShaderpackCompat.isIrisOpenGlOnly()) {
            final String version = ShaderpackCompat.irisVersion();
            return version == null ? ShaderpackCompat.IRIS_MOD_ID : ShaderpackCompat.IRIS_MOD_ID + " " + version;
        }
        return null;
    }

    private static @Nullable BackendCreationException checkVulkanAvailability() {
        try {
            return VulkanBackend.checkBackendAvailable();
        } catch (Throwable failure) {
            return new BackendCreationException(
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
    }
}
