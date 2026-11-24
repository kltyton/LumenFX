package com.kltyton.lumenfx.client.resource.texture;

import com.mojang.blaze3d.GpuFormat;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.EXTTextureSRGB;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.vulkan.VK12;

/** Physical source storage, kept separate from the host's uncompressed-only format enum. */
public enum TextureStorageFormat {
    BGRA8(4), RGBA8(4), G8(1), G16(2), R16F(2), R32F(4), RG8(2), RG16(4), RG16F(4), RG32F(8),
    RGBA16(8), RGBA16F(8), RGBA32F(16), RGB565(2), RGB10A2(4), R11G11B10(4), R32_UINT(4), R32_SINT(4),
    BC1(8), BC2(16), BC3(16), BC4(8), BC5(16), BC6H(16), BC7(16);

    private final int blockBytes;
    TextureStorageFormat(int blockBytes) { this.blockBytes = blockBytes; }
    public boolean compressed() { return switch (this) { case BC1, BC2, BC3, BC4, BC5, BC6H, BC7 -> true; default -> false; }; }
    public boolean floatingColor() { return this != R32_UINT && this != R32_SINT; }
    public boolean supportedGl(GLCapabilities capabilities) {
        return switch (this) {
            case BC1, BC2, BC3 -> capabilities.GL_EXT_texture_compression_s3tc;
            case BC4, BC5 -> capabilities.OpenGL30 || capabilities.GL_ARB_texture_compression_rgtc;
            case BC6H, BC7 -> capabilities.OpenGL42 || capabilities.GL_ARB_texture_compression_bptc;
            case RGB565 -> capabilities.OpenGL41 || capabilities.GL_ARB_ES2_compatibility;
            default -> true;
        };
    }
    public int byteSize(int width, int height) {
        return !compressed() ? Math.multiplyExact(Math.multiplyExact(width, height), blockBytes)
                : Math.multiplyExact(Math.multiplyExact((width + 3) / 4, (height + 3) / 4), blockBytes);
    }
    public int glFormat(boolean srgb) {
        return switch (this) {
            case BGRA8, RGBA8 -> srgb ? GL21C.GL_SRGB8_ALPHA8 : GL11C.GL_RGBA8;
            case G8 -> GL30C.GL_R8; case G16 -> GL30C.GL_R16;
            case R16F -> GL30C.GL_R16F; case R32F -> GL30C.GL_R32F;
            case R32_UINT -> GL30C.GL_R32UI;
            case R32_SINT -> GL30C.GL_R32I;
            case RG8 -> GL30C.GL_RG8; case RG16 -> GL30C.GL_RG16; case RG16F -> GL30C.GL_RG16F; case RG32F -> GL30C.GL_RG32F;
            case RGBA16 -> GL11C.GL_RGBA16; case RGBA16F -> GL30C.GL_RGBA16F; case RGBA32F -> GL30C.GL_RGBA32F;
            case RGB565 -> GL41C.GL_RGB565; case RGB10A2 -> GL11C.GL_RGB10_A2; case R11G11B10 -> GL30C.GL_R11F_G11F_B10F;
            case BC1 -> srgb ? EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT : EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
            case BC2 -> srgb ? EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT : EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT3_EXT;
            case BC3 -> srgb ? EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT : EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
            case BC4 -> GL30C.GL_COMPRESSED_RED_RGTC1; case BC5 -> GL30C.GL_COMPRESSED_RG_RGTC2;
            case BC6H -> GL42C.GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT;
            case BC7 -> srgb ? GL42C.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM : GL42C.GL_COMPRESSED_RGBA_BPTC_UNORM;
        };
    }
    public int vkFormat(boolean srgb) {
        return switch (this) {
            case BGRA8 -> srgb ? VK12.VK_FORMAT_B8G8R8A8_SRGB : VK12.VK_FORMAT_B8G8R8A8_UNORM;
            case RGBA8 -> srgb ? VK12.VK_FORMAT_R8G8B8A8_SRGB : VK12.VK_FORMAT_R8G8B8A8_UNORM;
            case G8 -> VK12.VK_FORMAT_R8_UNORM; case G16 -> VK12.VK_FORMAT_R16_UNORM;
            case R16F -> VK12.VK_FORMAT_R16_SFLOAT; case R32F -> VK12.VK_FORMAT_R32_SFLOAT;
            case R32_UINT -> VK12.VK_FORMAT_R32_UINT;
            case R32_SINT -> VK12.VK_FORMAT_R32_SINT;
            case RG8 -> VK12.VK_FORMAT_R8G8_UNORM; case RG16 -> VK12.VK_FORMAT_R16G16_UNORM;
            case RG16F -> VK12.VK_FORMAT_R16G16_SFLOAT; case RG32F -> VK12.VK_FORMAT_R32G32_SFLOAT;
            case RGBA16 -> VK12.VK_FORMAT_R16G16B16A16_UNORM;
            case RGBA16F -> VK12.VK_FORMAT_R16G16B16A16_SFLOAT; case RGBA32F -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGB565 -> VK12.VK_FORMAT_R5G6B5_UNORM_PACK16;
            case RGB10A2 -> VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32; case R11G11B10 -> VK12.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case BC1 -> srgb ? VK12.VK_FORMAT_BC1_RGBA_SRGB_BLOCK : VK12.VK_FORMAT_BC1_RGBA_UNORM_BLOCK;
            case BC2 -> srgb ? VK12.VK_FORMAT_BC2_SRGB_BLOCK : VK12.VK_FORMAT_BC2_UNORM_BLOCK;
            case BC3 -> srgb ? VK12.VK_FORMAT_BC3_SRGB_BLOCK : VK12.VK_FORMAT_BC3_UNORM_BLOCK;
            case BC4 -> VK12.VK_FORMAT_BC4_UNORM_BLOCK; case BC5 -> VK12.VK_FORMAT_BC5_UNORM_BLOCK;
            case BC6H -> VK12.VK_FORMAT_BC6H_UFLOAT_BLOCK; case BC7 -> srgb ? VK12.VK_FORMAT_BC7_SRGB_BLOCK : VK12.VK_FORMAT_BC7_UNORM_BLOCK;
        };
    }
    public int glPixelFormat() {
        if (compressed()) throw new IllegalStateException("Block-compressed pixels have no uncompressed channel layout");
        return switch (this) {
            case BGRA8 -> GL12C.GL_BGRA;
            case G8, G16, R16F, R32F -> GL11C.GL_RED;
            case R32_UINT, R32_SINT -> GL30C.GL_RED_INTEGER;
            case RG8, RG16, RG16F, RG32F -> GL30C.GL_RG;
            case RGB565, R11G11B10 -> GL11C.GL_RGB;
            default -> GL11C.GL_RGBA;
        };
    }
    public int glPixelType() {
        return switch (this) { case G16, RG16, RGBA16 -> GL11C.GL_UNSIGNED_SHORT; case R16F, RG16F, RGBA16F -> GL30C.GL_HALF_FLOAT;
            case R32F, RG32F, RGBA32F -> GL11C.GL_FLOAT; case BGRA8, RGBA8, G8, RG8 -> GL11C.GL_UNSIGNED_BYTE;
            case R32_UINT -> GL11C.GL_UNSIGNED_INT;
            case R32_SINT -> GL11C.GL_INT;
            case RGB565 -> GL12C.GL_UNSIGNED_SHORT_5_6_5;
            case RGB10A2 -> GL12C.GL_UNSIGNED_INT_2_10_10_10_REV;
            case R11G11B10 -> GL30C.GL_UNSIGNED_INT_10F_11F_11F_REV;
            default -> throw new IllegalStateException("Block-compressed pixels have no uncompressed component type"); };
    }
    public static TextureStorageFormat forRenderTarget(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> G8; case RG8_UNORM -> RG8; case RGBA8_UNORM -> RGBA8;
            case R16_FLOAT -> R16F; case RG16_FLOAT -> RG16F; case RGBA16_FLOAT -> RGBA16F;
            case R16_UNORM -> G16; case RG16_UNORM -> RG16; case RGBA16_UNORM -> RGBA16;
            case R32_FLOAT -> R32F; case RG32_FLOAT -> RG32F; case RGBA32_FLOAT -> RGBA32F;
            case RGB10A2_UNORM -> RGB10A2; case RG11B10_FLOAT -> R11G11B10;
            case R32_SINT -> R32_SINT;
            default -> throw new IllegalArgumentException("Unsupported volume render target format " + format);
        };
    }
    public static TextureStorageFormat from(String value) {
        return switch (value) {
            case "B8G8R8A8", "BGRA8", "PF_B8G8R8A8" -> BGRA8;
            case "R8G8B8A8", "RGBA8", "PF_R8G8B8A8" -> RGBA8;
            case "G8", "PF_G8", "R8", "PF_R8" -> G8; case "G16", "PF_G16" -> G16;
            case "R16F", "PF_R16F" -> R16F; case "R32F", "R32_FLOAT", "PF_R32_FLOAT" -> R32F;
            case "R8G8", "PF_R8G8", "RG8" -> RG8; case "G16R16", "PF_G16R16", "RG16" -> RG16;
            case "R5G6B5_UNORM", "PF_R5G6B5_UNORM" -> RGB565;
            case "G16R16F", "PF_G16R16F", "RG16F" -> RG16F;
            case "G32R32F", "PF_G32R32F", "RG32F" -> RG32F;
            case "A2B10G10R10", "PF_A2B10G10R10", "RGB10A2" -> RGB10A2;
            case "FloatRGB", "PF_FloatRGB", "R11G11B10" -> R11G11B10;
            case "RGBA16F", "FloatRGBA", "PF_FloatRGBA" -> RGBA16F;
            case "RGBA16", "A16B16G16R16", "PF_A16B16G16R16" -> RGBA16;
            case "RGBA32F", "A32B32G32R32F", "PF_A32B32G32R32F" -> RGBA32F;
            case "DXT1", "PF_DXT1" -> BC1;
            case "DXT3", "PF_DXT3" -> BC2;
            case "DXT5", "PF_DXT5" -> BC3;
            case "BC4", "PF_BC4" -> BC4; case "BC5", "PF_BC5" -> BC5; case "BC6H", "PF_BC6H" -> BC6H; case "BC7", "PF_BC7" -> BC7;
            default -> throw new IllegalArgumentException("Unsupported source texture storage " + value);
        };
    }
}
