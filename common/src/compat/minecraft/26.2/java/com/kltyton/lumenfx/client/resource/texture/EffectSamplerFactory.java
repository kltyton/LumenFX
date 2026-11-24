package com.kltyton.lumenfx.client.resource.texture;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.OptionalDouble;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Source sampler settings share native creation and deferred destruction. */
public final class EffectSamplerFactory {
    public record Settings(TextureMetadata.Address addressX, TextureMetadata.Address addressY,
                           TextureMetadata.FilterMode minMag, TextureMetadata.FilterMode mip, int maxAnisotropy) {
        public Settings {
            if (addressX == null || addressY == null || minMag == null || mip == null || mip == TextureMetadata.FilterMode.ANISO || maxAnisotropy < 1)
                throw new IllegalArgumentException("Invalid source texture sampler settings");
        }
    }
    private record SamplerCreation(Settings settings, TextureMetadata.Address addressZ, float lodBias) { }
    private static final ThreadLocal<SamplerCreation> SETTINGS = new ThreadLocal<>();
    private EffectSamplerFactory() { }

    public static Settings read(com.google.gson.JsonObject data) {
        return new Settings(TextureMetadata.Address.valueOf(data.get("address_x").getAsString().toUpperCase(java.util.Locale.ROOT)),
                TextureMetadata.Address.valueOf(data.get("address_y").getAsString().toUpperCase(java.util.Locale.ROOT)),
                TextureMetadata.FilterMode.valueOf(data.get("min_mag_filter").getAsString().toUpperCase(java.util.Locale.ROOT)),
                TextureMetadata.FilterMode.valueOf(data.get("mip_filter").getAsString().toUpperCase(java.util.Locale.ROOT)),
                data.get("max_anisotropy").getAsInt());
    }

    static GpuSampler create(TextureMetadata metadata, int mipLevels) {
        return create(new Settings(metadata.addressX(), metadata.addressY(), metadata.minMag(), metadata.mip(), metadata.maxAnisotropy()), mipLevels);
    }

    public static GpuSampler create(Settings metadata, int mipLevels) {
        return create(metadata, TextureMetadata.Address.WRAP, mipLevels, 0.0f);
    }

    public static GpuSampler create(Settings metadata, TextureMetadata.Address addressZ, int mipLevels, float lodBias) {
        if (mipLevels < 1) throw new IllegalArgumentException("Sampler requires at least one mip");
        if (addressZ == null || !Float.isFinite(lodBias)) throw new IllegalArgumentException("Invalid sampler W address or LOD bias");
        FilterMode filter = metadata.minMag() == TextureMetadata.FilterMode.POINT ? FilterMode.NEAREST : FilterMode.LINEAR;
        var device = RenderSystem.getDevice();
        int supported = device.getDeviceInfo().limits().maxAnisotropy();
        int anisotropy = metadata.minMag() == TextureMetadata.FilterMode.ANISO ? Math.min(metadata.maxAnisotropy(), supported) : 1;
        float supportedLodBias = lodBias == 0 ? 0 : supportedLodBias(device.backend);
        float boundedLodBias = Math.clamp(lodBias, -supportedLodBias, supportedLodBias);
        var creation = new SamplerCreation(metadata, addressZ, boundedLodBias);
        SamplerCreation previous = SETTINGS.get();
        GpuSampler sampler;
        SETTINGS.set(creation);
        try {
            sampler = device.createSampler(address(metadata.addressX()), address(metadata.addressY()),
                    filter, filter, anisotropy, OptionalDouble.of(mipLevels - 1));
        } finally {
            if (previous == null) SETTINGS.remove(); else SETTINGS.set(previous);
        }
        if (sampler instanceof GlSampler gl) {
            boolean point = metadata.mip() == TextureMetadata.FilterMode.POINT;
            int minFilter = filter == FilterMode.NEAREST
                    ? point ? GL33C.GL_NEAREST_MIPMAP_NEAREST : GL33C.GL_NEAREST_MIPMAP_LINEAR
                    : point ? GL33C.GL_LINEAR_MIPMAP_NEAREST : GL33C.GL_LINEAR_MIPMAP_LINEAR;
            GL33C.glSamplerParameteri(gl.getId(), GL33C.GL_TEXTURE_MIN_FILTER, minFilter);
            GL33C.glSamplerParameteri(gl.getId(), GL12C.GL_TEXTURE_WRAP_R, glAddress(addressZ));
            GL33C.glSamplerParameterf(gl.getId(), GL14C.GL_TEXTURE_LOD_BIAS, boundedLodBias);
            if (metadata.addressX() == TextureMetadata.Address.MIRROR || metadata.addressX() == TextureMetadata.Address.MIRROR_ONCE)
                GL33C.glSamplerParameteri(gl.getId(), GL33C.GL_TEXTURE_WRAP_S, glAddress(metadata.addressX()));
            if (metadata.addressY() == TextureMetadata.Address.MIRROR || metadata.addressY() == TextureMetadata.Address.MIRROR_ONCE)
                GL33C.glSamplerParameteri(gl.getId(), GL33C.GL_TEXTURE_WRAP_T, glAddress(metadata.addressY()));
        } else if (!(sampler instanceof VulkanGpuSampler)) {
            sampler.close();
            throw new IllegalStateException("Unsupported effect sampler backend " + sampler.getClass().getName());
        }
        return sampler;
    }

    /** Constructor hook is confined to this thread's effect sampler creation. */
    public static int vulkanMipmapMode(int original) {
        SamplerCreation creation = SETTINGS.get();
        return creation == null ? original : creation.settings().mip() == TextureMetadata.FilterMode.POINT ? 0 : 1;
    }

    public static int vulkanAddressMode(int original, boolean vertical) {
        SamplerCreation creation = SETTINGS.get();
        if (creation == null) return original;
        TextureMetadata.Address address = vertical ? creation.settings().addressY() : creation.settings().addressX();
        return switch (address) {
            case MIRROR -> org.lwjgl.vulkan.VK12.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case MIRROR_ONCE -> org.lwjgl.vulkan.VK12.VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE;
            default -> original;
        };
    }

    public static VkSamplerCreateInfo vulkanSamplerAddressW(VkSamplerCreateInfo original) {
        SamplerCreation creation = SETTINGS.get();
        if (creation == null) return original;
        return original.addressModeW(switch (creation.addressZ()) {
            case WRAP -> VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case CLAMP -> VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case MIRROR -> VK12.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case MIRROR_ONCE -> VK12.VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE;
        });
    }

    public static float vulkanLodBias(float original) {
        SamplerCreation creation = SETTINGS.get();
        return creation == null ? original : creation.lodBias();
    }

    private static float supportedLodBias(Object backend) {
        return switch (backend) {
            case GlDevice ignored -> Math.max(0.0f, GL33C.glGetFloat(GL14C.GL_MAX_TEXTURE_LOD_BIAS));
            case VulkanDevice device -> {
                try (var stack = MemoryStack.stackPush()) {
                    var properties = VkPhysicalDeviceProperties.calloc(stack);
                    VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
                    yield Math.max(0.0f, properties.limits().maxSamplerLodBias());
                }
            }
            default -> throw new IllegalStateException("Unsupported sampler backend " + backend.getClass().getName());
        };
    }

    private static int glAddress(TextureMetadata.Address address) {
        return switch (address) {
            case WRAP -> GL11C.GL_REPEAT;
            case CLAMP -> GL12C.GL_CLAMP_TO_EDGE;
            case MIRROR -> GL33C.GL_MIRRORED_REPEAT;
            case MIRROR_ONCE -> GL44C.GL_MIRROR_CLAMP_TO_EDGE;
        };
    }

    private static AddressMode address(TextureMetadata.Address address) {
        return address == TextureMetadata.Address.CLAMP ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
    }
}
