package com.kltyton.lumenfx.client.resource.texture.energy;

import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.LumenFx;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkFormatProperties;
import org.jspecify.annotations.Nullable;

/** One renderer energy table exported from the native Unreal CPU texture. */
public record ShadingEnergyTable(Identifier id, Kind kind, int width, int height, int depth,
                                 ByteBuffer primary, @Nullable ByteBuffer alternate) {
    public static final String DIRECTORY = "lumenfx/shading_energy";
    public static final Identifier PREINTEGRATED_SKIN = LumenFx.id("textures/lumenfx/shading_energy/preintegrated_skin.lftex.json");

    public enum Kind {
        GGX_REFLECTION("ggx_reflection", TextureStorageFormat.RG16, TextureStorageFormat.RG8, 2),
        GGX_TRANSMISSION("ggx_transmission", TextureStorageFormat.RG16, TextureStorageFormat.RG8, 2),
        SHEEN("sheen", TextureStorageFormat.RG16, TextureStorageFormat.RG8, 2),
        SHEEN_LEGACY("sheen_legacy", TextureStorageFormat.RG16, TextureStorageFormat.RG8, 2),
        DIFFUSE("diffuse", TextureStorageFormat.G16, TextureStorageFormat.G8, 1),
        GGX_MAX_SPECULAR("ggx_max_specular", TextureStorageFormat.RGBA16, TextureStorageFormat.RGBA8, 4),
        SHEEN_LTC("sheen_ltc", TextureStorageFormat.RGBA16F, null, 4),
        GGX_LTC_MATRIX("ggx_ltc_matrix", TextureStorageFormat.RGBA16F, null, 4),
        GGX_LTC_AMPLITUDE("ggx_ltc_amplitude", TextureStorageFormat.RG16F, null, 2);

        private final String file;
        private final TextureStorageFormat primary;
        private final @Nullable TextureStorageFormat alternate;
        private final int channels;

        Kind(String file, TextureStorageFormat primary, @Nullable TextureStorageFormat alternate, int channels) {
            this.file = file;
            this.primary = primary;
            this.alternate = alternate;
            this.channels = channels;
        }

        public Identifier resource(String namespace) {
            return Identifier.fromNamespaceAndPath(namespace, DIRECTORY + "/" + file + ".lfea");
        }
    }

    public static ShadingEnergyTable prepare(Identifier id, Resource resource) throws IOException {
        ByteBuffer data;
        try (var input = resource.open()) {
            data = ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        }
        if (data.remaining() < 28 || data.getInt() != 0x4145464c || data.getInt() != 1)
            throw new IllegalArgumentException("Invalid Unreal shading energy table header: " + id);
        int tag = data.getInt();
        Kind[] kinds = Kind.values();
        if (tag < 0 || tag >= kinds.length || !id.equals(kinds[tag].resource(id.getNamespace())))
            throw new IllegalArgumentException("Unexpected Unreal shading energy table identity: " + id);
        Kind kind = kinds[tag];
        int width = data.getInt(), height = data.getInt(), depth = data.getInt(), channels = data.getInt();
        if (width <= 0 || height <= 0 || depth <= 0 || channels != kind.channels
                || (kind == Kind.GGX_TRANSMISSION ? width != height : depth != 1))
            throw new IllegalArgumentException("Invalid Unreal shading energy table extent: " + id);
        long samples = Math.multiplyExact(Math.multiplyExact((long) width * height, depth), channels);
        if (samples != data.remaining() / Float.BYTES || data.remaining() % Float.BYTES != 0)
            throw new IllegalArgumentException("Invalid Unreal shading energy table payload length: " + id);
        ByteBuffer primary = ByteBuffer.allocateDirect((int) samples * Short.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer alternate = kind.alternate == null ? null : ByteBuffer.allocateDirect((int) samples);
        while (data.hasRemaining()) {
            float value = data.getFloat();
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite Unreal shading energy table value: " + id);
            if (alternate == null) primary.putShort(Float.floatToFloat16(value));
            else {
                primary.putShort((short) (int) Math.clamp(value * 65535, 0, 65535));
                alternate.put((byte) (int) Math.clamp(value * 255, 0, 255));
            }
        }
        return new ShadingEnergyTable(id, kind, width, height, depth, primary.flip().asReadOnlyBuffer(),
                alternate == null ? null : alternate.flip().asReadOnlyBuffer());
    }

    public EffectTexture upload() {
        RenderSystem.assertOnRenderThread();
        TextureStorageFormat fallback = kind.alternate;
        boolean useFallback = fallback != null && alternate != null && !filterable(kind.primary);
        TextureStorageFormat format = useFallback ? fallback : kind.primary;
        ByteBuffer data = useFallback ? alternate.duplicate() : primary.duplicate();
        var clamp = TextureMetadata.Address.CLAMP;
        var metadata = new TextureMetadata(width, height, false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                false, false, false, clamp, clamp, TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT,
                1, "ShadingEnergy", "NativeCpuCopy", format.name(), "", "", false);
        boolean volume = kind == Kind.GGX_TRANSMISSION;
        return EffectTextureLoader.upload(new PreparedTexture(id, metadata, List.of(data), 0, false,
                volume ? depth : 0, volume ? clamp : null));
    }

    private static boolean filterable(TextureStorageFormat format) {
        return switch (RenderSystem.getDevice().backend) {
            case GlDevice ignored -> true;
            case VulkanDevice device -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var properties = VkFormatProperties.calloc(stack);
                    VK12.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), format.vkFormat(false), properties);
                    int required = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                            | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
                    yield (properties.optimalTilingFeatures() & required) == required;
                }
            }
            default -> throw new IllegalStateException("Unsupported shading energy texture backend");
        };
    }
}
