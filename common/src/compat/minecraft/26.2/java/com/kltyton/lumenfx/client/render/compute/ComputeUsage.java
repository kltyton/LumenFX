package com.kltyton.lumenfx.client.render.compute;

/**
 * Local usage markers that the host enums do not define.
 *
 * <p>These bits are never reinterpreted as render-attachment or texture-binding flags. GL ignores
 * them; the Vulkan usage mixin maps them only onto {@code VK_IMAGE_USAGE_STORAGE_BIT} and
 * {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT}.</p>
 */
public final class ComputeUsage {
    public static final int STORAGE_IMAGE = 1 << 5;
    public static final int STORAGE_BUFFER = 1 << 10;

    private ComputeUsage() {
    }

    public static int storageImage(final int usage) {
        return usage | STORAGE_IMAGE;
    }

    public static int storageBuffer(final int usage) {
        return usage | STORAGE_BUFFER;
    }
}
