package com.kltyton.lumenfx.client.resource.texture.storage;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

/** Uses native allocation, view counts and deferred destruction with source storage. */
final class SourceVulkanTexture extends VulkanGpuTexture implements SampledTextureStorage {
    private final NativeTextureStorage storage;
    private final boolean cubeArray;

    private SourceVulkanTexture(VulkanDevice device, String label, int width, int height, int layers, int mips, boolean cube,
                                boolean cubeArray, NativeTextureStorage storage) {
        super(device, GpuTexture.USAGE_TEXTURE_BINDING | (cube || cubeArray ? GpuTexture.USAGE_CUBEMAP_COMPATIBLE : 0),
                label, GpuFormat.RGBA8_UNORM, width, height, layers, mips);
        this.storage = storage;
        this.cubeArray = cubeArray;
    }

    static SourceVulkanTexture create(VulkanDevice device, String label, int width, int height, int layers, int mips, boolean cube,
                                      NativeTextureStorage storage) {
        return create(device, label, width, height, layers, mips, cube, false, storage);
    }

    static SourceVulkanTexture create(VulkanDevice device, String label, int width, int height, int layers, int mips, boolean cube,
                                      boolean cubeArray, NativeTextureStorage storage) {
        return NativeTextureCreation.create(storage,
                () -> new SourceVulkanTexture(device, label, width, height, layers, mips, cube, cubeArray, storage));
    }

    GpuTextureView createView(VulkanDevice device) {
        return NativeTextureCreation.create(storage, () -> device.createTextureView(this));
    }

    long createArrayView(VulkanDevice device) {
        try (var stack = MemoryStack.stackPush()) {
            var info = VkImageViewCreateInfo.calloc(stack).sType$Default();
            info.image(vkImage()).viewType(VK12.VK_IMAGE_VIEW_TYPE_2D_ARRAY).format(storage.vkFormat());
            info.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(getMipLevels())
                    .baseArrayLayer(0).layerCount(getDepthOrLayers());
            var view = stack.callocLong(1);
            VulkanUtils.crashIfFailure(device, VK12.vkCreateImageView(device.vkDevice(), info, null, view),
                    "Failed to create a native source texture array view");
            return view.get(0);
        }
    }

    @Override public int getWidth(int level) { return Math.max(1, super.getWidth(level)); }
    @Override public int getHeight(int level) { return Math.max(1, super.getHeight(level)); }
    @Override public NativeTextureStorage sampledStorage() { return storage; }
    @Override public boolean cubeArray() { return cubeArray; }

    @Override public GpuFormat getFormat() {
        // The host constructor and view constructor ask only for the color aspect.
        // Image and view format are supplied independently by the creation hooks.
        if (storage == null || NativeTextureCreation.current() == storage) return super.getFormat();
        throw new UnsupportedOperationException("Sample-only source storage has no host GpuFormat: " + storage);
    }
}
