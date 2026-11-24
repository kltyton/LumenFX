package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Format;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Layer;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.List;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkFormatProperties;

/** Windows desktop RVT layer layout, including the source low-quality format capability rule. */
final class RuntimeVirtualTextureStorage {
    private RuntimeVirtualTextureStorage() { }

    static List<Layer> layers(RuntimeVirtualTextureDefinition definition) {
        boolean compressed = definition.compress();
        Format four = compressed ? Format.BC3 : Format.BGRA8;
        Format three = compressed ? Format.BC1 : Format.BGRA8;
        return switch (definition.material()) {
            case BaseColor -> List.of(new Layer(three, true, false));
            case Mask4 -> List.of(linear(four));
            case BaseColor_Normal_Roughness -> {
                boolean lowQuality = compressed && definition.lowQualityCompression() && supportsLowQuality();
                yield List.of(linear(lowQuality ? Format.RGB565 : three), linear(lowQuality ? Format.RGB565 : four));
            }
            case BaseColor_Normal_Specular -> List.of(new Layer(four, true, false), linear(four));
            case BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> List.of(
                    new Layer(four, false, true), linear(compressed ? Format.BC5 : Format.BGRA8),
                    linear(definition.material() == Material.BaseColor_Normal_Specular_YCoCg
                            ? three : four));
            case WorldHeight -> List.of(linear(Format.G16));
            case Displacement -> List.of(linear(compressed ? Format.BC4 : Format.G16));
        };
    }

    private static Layer linear(Format format) { return new Layer(format, false, false); }

    static TextureStorageFormat storage(Layer layer) { return TextureStorageFormat.valueOf(layer.format().name()); }

    private static boolean supportsLowQuality() {
        var backend = RenderSystem.getDevice().backend;
        if (backend instanceof GlDevice) return TextureStorageFormat.RGB565.supportedGl(GL.getCapabilities());
        if (!(backend instanceof VulkanDevice vk)) return false;
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            var device = vk.vkDevice().getPhysicalDevice();
            int required = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                    | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            VK12.vkGetPhysicalDeviceFormatProperties(device, VK12.VK_FORMAT_B5G5R5A1_UNORM_PACK16, properties);
            if ((properties.optimalTilingFeatures() & required) != required) return false;
            VK12.vkGetPhysicalDeviceFormatProperties(device, VK12.VK_FORMAT_R5G6B5_UNORM_PACK16, properties);
            return (properties.optimalTilingFeatures() & required) == required;
        }
    }
}
