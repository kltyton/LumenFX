package com.kltyton.lumenfx.client.resource.texture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Immutable sidecar contract emitted by tools/compiler/Unreal/Textures.cs. */
public record TextureMetadata(int width, int height, boolean srgb, MipMode mipMode, float sharpen,
                              int kernelSize, boolean downsampleWithAverage,
                              boolean sharpenWithoutColorShift, boolean useNewMipFilter,
                              Address addressX, Address addressY, FilterMode minMag, FilterMode mip,
                              int maxAnisotropy, String lodGroup, String compression, String cookedFormat,
                              String pngSha256, String mipData, boolean legacyGamma, @Nullable SourceInput sourceInput) {
    public record SourceInput(String resource, String sha256) {
        public SourceInput {
            if (resource == null || resource.isBlank() || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("Invalid native texture source identity");
        }
    }

    public TextureMetadata(int width, int height, boolean srgb, MipMode mipMode, float sharpen,
                           int kernelSize, boolean downsampleWithAverage, boolean sharpenWithoutColorShift, boolean useNewMipFilter,
                           Address addressX, Address addressY, FilterMode minMag, FilterMode mip, int maxAnisotropy,
                           String lodGroup, String compression, String cookedFormat, String pngSha256, String mipData, boolean legacyGamma) {
        this(width, height, srgb, mipMode, sharpen, kernelSize, downsampleWithAverage, sharpenWithoutColorShift, useNewMipFilter,
                addressX, addressY, minMag, mip, maxAnisotropy, lodGroup, compression, cookedFormat, pngSha256, mipData, legacyGamma, null);
    }
    public enum MipMode { SIMPLE_AVERAGE, SHARPEN, NO_MIPMAPS, NATIVE_BUILD }

    public enum Address { WRAP, CLAMP, MIRROR, MIRROR_ONCE }

    public enum FilterMode { POINT, LINEAR, ANISO }

    public TextureMetadata withStorage(int width, int height, boolean srgb, String format,
            FilterMode minMag, FilterMode mip, int anisotropy) {
        return new TextureMetadata(width, height, srgb, mipMode, sharpen, kernelSize, downsampleWithAverage,
                sharpenWithoutColorShift, useNewMipFilter, addressX, addressY, minMag, mip, anisotropy,
                lodGroup, compression, format, pngSha256, mipData, legacyGamma, sourceInput);
    }

    public static TextureMetadata parse(JsonObject json) {
        int version = integer(json, "format_version");
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported texture metadata version " + version);
        int width = integer(json, "width");
        int height = integer(json, "height");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid texture dimensions");
        boolean srgb = bool(json, "srgb");
        MipMode mipMode = enumValue(MipMode.class, string(json, "mip_gen"));
        float sharpen = number(json, "sharpen");
        int kernelSize = integer(json, "kernel_size");
        if (!Float.isFinite(sharpen) || sharpen < 0.0F) throw new IllegalArgumentException("Invalid sharpen factor");
        if (mipMode == MipMode.SHARPEN && kernelSize != 8)
            throw new IllegalArgumentException("UE sharpen mips require kernel_size 8");
        if (mipMode == MipMode.NATIVE_BUILD && kernelSize != 0)
            throw new IllegalArgumentException("Native texture mips do not use the offline filter kernel");
        if (mipMode != MipMode.SHARPEN && mipMode != MipMode.NATIVE_BUILD && kernelSize != 2)
            throw new IllegalArgumentException("UE average mips require kernel_size 2");
        boolean downsampleWithAverage = bool(json, "downsample_with_average");
        boolean sharpenWithoutColorShift = bool(json, "sharpen_without_color_shift");
        boolean useNewMipFilter = bool(json, "use_new_mip_filter");
        Address addressX = enumValue(Address.class, string(json, "address_x"));
        Address addressY = enumValue(Address.class, string(json, "address_y"));
        FilterMode minMag = enumValue(FilterMode.class, string(json, "min_mag_filter"));
        FilterMode mip = enumValue(FilterMode.class, string(json, "mip_filter"));
        if (mip == FilterMode.ANISO) throw new IllegalArgumentException("UE mip filter cannot be aniso");
        int maxAnisotropy = integer(json, "max_anisotropy");
        if (maxAnisotropy < 1) throw new IllegalArgumentException("Invalid max_anisotropy");
        SourceInput sourceInput = null;
        String pngSha256 = "";
        if (version == 2) {
            String sourceType = string(json, "source");
            if (!sourceType.equals("unreal-texture2d-source") && !sourceType.equals("unreal-texture2d-array-source")
                    && !sourceType.equals("unreal-texture-cube-source") && !sourceType.equals("unreal-texture-cube-array-source")
                    && !sourceType.equals("unreal-texture-volume-source"))
                throw new IllegalArgumentException("Unsupported native texture source");
            sourceInput = new SourceInput(string(json, "source_data"), string(json, "source_sha256"));
        } else pngSha256 = string(json, "png_sha256");
        if (sourceInput == null && !pngSha256.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Invalid texture metadata png_sha256");
        return new TextureMetadata(width, height, srgb, mipMode, sharpen, kernelSize, downsampleWithAverage,
                sharpenWithoutColorShift, useNewMipFilter, addressX, addressY, minMag, mip, maxAnisotropy,
                string(json, "lod_group"), string(json, "compression"), string(json, "cooked_format"),
                pngSha256, string(json, "mip_data"), bool(json, "legacy_gamma"), sourceInput);
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing texture metadata " + key);
        return value.getAsString();
    }

    private static boolean bool(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing texture metadata " + key);
        return value.getAsBoolean();
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing texture metadata " + key);
        return value.getAsInt();
    }

    private static float number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing texture metadata " + key);
        return value.getAsFloat();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown texture metadata value " + value, error);
        }
    }
}
