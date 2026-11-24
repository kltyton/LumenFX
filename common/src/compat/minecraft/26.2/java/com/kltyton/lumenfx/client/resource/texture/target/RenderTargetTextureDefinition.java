package com.kltyton.lumenfx.client.resource.texture.target;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.GpuFormat;
import java.io.IOException;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** Resolved color-image settings of a source TextureRenderTarget2D asset. */
public record RenderTargetTextureDefinition(int width, int height, GpuFormat format, String sourceFormat, boolean srgb,
        boolean autoMips, float[] clearColor, EffectSamplerFactory.Settings sampler, EffectSamplerFactory.Settings mipSampler,
        int targetFormat, boolean formatOverride, boolean storageWritable, String filter, EffectSamplerFactory.Settings defaultSampler) {
    public static final int SIGNED_INT = 11;
    private static final String[] FORMATS = {"RTF_R8", "RTF_RG8", "RTF_RGBA8", "RTF_RGBA8_SRGB", "RTF_R16f",
            "RTF_RG16f", "RTF_RGBA16f", "RTF_R32f", "RTF_RG32f", "RTF_RGBA32f", "RTF_RGB10A2"};
    public RenderTargetTextureDefinition(int width, int height, GpuFormat format, String sourceFormat, boolean srgb,
            boolean autoMips, float[] clearColor, EffectSamplerFactory.Settings sampler, EffectSamplerFactory.Settings mipSampler,
            int targetFormat, boolean formatOverride, boolean storageWritable, String filter) {
        this(width, height, format, sourceFormat, srgb, autoMips, clearColor, sampler, mipSampler,
                targetFormat, formatOverride, storageWritable, filter, sampler);
    }
    public RenderTargetTextureDefinition(int width, int height, GpuFormat format, String sourceFormat, boolean srgb,
            boolean autoMips, float[] clearColor, EffectSamplerFactory.Settings sampler, EffectSamplerFactory.Settings mipSampler) {
        this(width, height, format, sourceFormat, srgb, autoMips, clearColor, sampler, mipSampler,
                ordinal(format, srgb), false, false, "TF_Default");
    }
    public RenderTargetTextureDefinition {
        clearColor = clearColor.clone();
        boolean signed = format == GpuFormat.R32_SINT && targetFormat == SIGNED_INT && storageWritable
                && "PF_R32_SINT".equals(sourceFormat) && !srgb && !autoMips;
        if (width < 1 || height < 1 || format == null || !signed && !SampledTextureFormats.floatingColor(format)
                || sourceFormat == null || sampler == null || mipSampler == null || clearColor.length != 4
                || targetFormat < 0 || targetFormat > (signed ? SIGNED_INT : 10) || filter == null || defaultSampler == null)
            throw new IllegalArgumentException("Invalid render target texture definition");
        for (float value : clearColor) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite render target clear color");
        filter = switch (filter.substring(filter.lastIndexOf(':') + 1)) {
            case "TF_Nearest", "0" -> "TF_Nearest"; case "TF_Bilinear", "1" -> "TF_Bilinear";
            case "TF_Trilinear", "2" -> "TF_Trilinear"; case "TF_Default", "3" -> "TF_Default";
            default -> throw new IllegalArgumentException("Unknown render target filter " + filter);
        };
    }
    @Override public float[] clearColor() { return clearColor.clone(); }
    public int mipLevels() { return autoMips ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1; }
    public RenderTargetTextureDefinition resized(int width, int height) {
        return new RenderTargetTextureDefinition(width, height, format, sourceFormat, srgb, autoMips, clearColor, sampler, mipSampler,
                targetFormat, formatOverride, storageWritable, filter, defaultSampler);
    }

    public RenderTargetTextureDefinition forNiagara(int width, int height, int targetFormat, boolean autoMips,
                                                   String filter) {
        GpuFormat actual = formatOverride ? format : gpuFormat(targetFormat);
        String source = formatOverride ? sourceFormat : sourceFormat(targetFormat);
        boolean encoded = formatOverride ? srgb : targetFormat == 3;
        TextureMetadata.FilterMode minMag, mip;
        switch (filter) {
            case "TF_Nearest", "0" -> { minMag = TextureMetadata.FilterMode.POINT; mip = TextureMetadata.FilterMode.POINT; }
            case "TF_Bilinear", "1" -> { minMag = TextureMetadata.FilterMode.LINEAR; mip = TextureMetadata.FilterMode.POINT; }
            case "TF_Trilinear", "2" -> { minMag = TextureMetadata.FilterMode.LINEAR; mip = TextureMetadata.FilterMode.LINEAR; }
            case "TF_Default", "3" -> { minMag = defaultSampler.minMag(); mip = defaultSampler.mip(); }
            default -> throw new IllegalArgumentException("Unknown render target filter " + filter);
        }
        var resolved = new EffectSamplerFactory.Settings(this.sampler.addressX(), this.sampler.addressY(),
                minMag, mip, defaultSampler.maxAnisotropy());
        return new RenderTargetTextureDefinition(width, height, actual, source, encoded, autoMips, clearColor, resolved, mipSampler,
                targetFormat, formatOverride, true, filter, defaultSampler);
    }

    public static GpuFormat gpuFormat(int ordinal) {
        return switch (ordinal) {
            case 0 -> GpuFormat.R8_UNORM; case 1 -> GpuFormat.RG8_UNORM; case 2, 3 -> GpuFormat.RGBA8_UNORM;
            case 4 -> GpuFormat.R16_FLOAT; case 5 -> GpuFormat.RG16_FLOAT; case 6 -> GpuFormat.RGBA16_FLOAT;
            case 7 -> GpuFormat.R32_FLOAT; case 8 -> GpuFormat.RG32_FLOAT; case 9 -> GpuFormat.RGBA32_FLOAT;
            case 10 -> GpuFormat.RGB10A2_UNORM;
            case SIGNED_INT -> GpuFormat.R32_SINT;
            default -> throw new IllegalArgumentException("Invalid render target format " + ordinal);
        };
    }

    public static int targetFormat(String value) {
        for (int i = 0; i < FORMATS.length; i++) if (FORMATS[i].equals(value)) return i;
        if (value.matches("[0-9]+")) {
            int ordinal = Integer.parseInt(value);
            if (ordinal >= 0 && ordinal < FORMATS.length) return ordinal;
        }
        throw new IllegalArgumentException("Unknown render target format " + value);
    }
    public static String sourceFormat(int ordinal) {
        return switch (ordinal) {
            case 0 -> "PF_G8"; case 1 -> "PF_R8G8"; case 2, 3 -> "PF_R8G8B8A8";
            case 4 -> "PF_R16F"; case 5 -> "PF_G16R16F"; case 6 -> "PF_FloatRGBA";
            case 7 -> "PF_R32_FLOAT"; case 8 -> "PF_G32R32F"; case 9 -> "PF_A32B32G32R32F"; case 10 -> "PF_A2B10G10R10";
            case SIGNED_INT -> "PF_R32_SINT";
            default -> throw new IllegalArgumentException("Invalid render target format " + ordinal);
        };
    }
    private static int ordinal(GpuFormat format, boolean srgb) {
        for (int i = 0; i <= SIGNED_INT; i++) if (gpuFormat(i) == format && (format != GpuFormat.RGBA8_UNORM || (i == 3) == srgb)) return i;
        throw new IllegalArgumentException("Unknown render target format " + format + ", sRGB=" + srgb);
    }

    public @Nullable NativeTextureStorage allocationStorage() {
        return storageWritable && format == GpuFormat.RGBA8_UNORM
                ? new NativeTextureStorage(TextureStorageFormat.RGBA8, srgb) : storage();
    }
    public @Nullable NativeTextureStorage storage() {
        return format == GpuFormat.RGBA8_UNORM
                ? new NativeTextureStorage(TextureStorageFormat.from(sourceFormat), srgb) : null;
    }

    public static RenderTargetTextureDefinition read(ResourceManager resources, Identifier id) {
        try (var reader = resources.getResourceOrThrow(id).openAsReader()) {
            return read(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare render target texture " + id, error);
        }
    }

    public static RenderTargetTextureDefinition read(JsonObject data) {
        if (integer(data, "format_version") != 1) throw new IllegalArgumentException("Unsupported render target texture version");
        GpuFormat format = GpuFormat.valueOf(data.get("format").getAsString());
        String source = data.get("source_format").getAsString();
        GpuFormat sourceFormat = switch (source) {
            case "PF_G8", "PF_R8" -> GpuFormat.R8_UNORM; case "PF_R8G8" -> GpuFormat.RG8_UNORM;
            case "PF_B8G8R8A8", "PF_R8G8B8A8" -> GpuFormat.RGBA8_UNORM; case "PF_R16F" -> GpuFormat.R16_FLOAT;
            case "PF_G16R16F" -> GpuFormat.RG16_FLOAT; case "PF_FloatRGBA" -> GpuFormat.RGBA16_FLOAT;
            case "PF_FloatRGB" -> GpuFormat.RG11B10_FLOAT;
            case "PF_R32_FLOAT" -> GpuFormat.R32_FLOAT; case "PF_G32R32F" -> GpuFormat.RG32_FLOAT;
            case "PF_A32B32G32R32F" -> GpuFormat.RGBA32_FLOAT; case "PF_A2B10G10R10" -> GpuFormat.RGB10A2_UNORM;
            case "PF_R32_SINT" -> GpuFormat.R32_SINT;
            default -> throw new IllegalArgumentException("Unsupported render target source format " + source);
        };
        if (format != sourceFormat) throw new IllegalArgumentException("Render target logical and source formats differ");
        var clear = data.getAsJsonArray("clear");
        if (clear.size() != 4) throw new IllegalArgumentException("Render target clear color requires four components");
        float[] color = new float[4];
        for (int i = 0; i < 4; i++) color[i] = clear.get(i).getAsFloat();
        JsonObject sampler = data.getAsJsonObject("sampler");
        var sampling = new EffectSamplerFactory.Settings(address(sampler.get("address_x").getAsString()), address(sampler.get("address_y").getAsString()),
                filter(sampler.get("min_mag_filter").getAsString()), filter(sampler.get("mip_filter").getAsString()), integer(sampler, "max_anisotropy"));
        var mips = new EffectSamplerFactory.Settings(address(data.get("mip_address_x").getAsString()), address(data.get("mip_address_y").getAsString()),
                filter(data.get("mip_filter").getAsString()), TextureMetadata.FilterMode.POINT, 1);
        return new RenderTargetTextureDefinition(integer(data, "width"), integer(data, "height"), format, source,
                data.get("srgb").getAsBoolean(), data.get("auto_mips").getAsBoolean(), color, sampling, mips,
                data.has("target_format") ? integer(data, "target_format") : ordinal(format, data.get("srgb").getAsBoolean()),
                data.has("override_format") && data.get("override_format").getAsBoolean(),
                data.has("storage_writable") && data.get("storage_writable").getAsBoolean(),
                data.has("filter") ? data.get("filter").getAsString() : "TF_Default",
                data.has("default_sampler") ? EffectSamplerFactory.read(data.getAsJsonObject("default_sampler")) : sampling);
    }

    private static int integer(JsonObject data, String name) {
        var value = data.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Render target field must be an integer: " + name);
        return value.getAsBigDecimal().intValueExact();
    }
    private static TextureMetadata.Address address(String value) { return TextureMetadata.Address.valueOf(value.toUpperCase(Locale.ROOT)); }
    private static TextureMetadata.FilterMode filter(String value) { return TextureMetadata.FilterMode.valueOf(value.toUpperCase(Locale.ROOT)); }
}
