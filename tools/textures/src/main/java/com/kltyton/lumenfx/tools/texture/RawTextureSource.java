package com.kltyton.lumenfx.tools.texture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Decodes the original editor-source precision using FImage's linear-channel contracts. */
final class RawTextureSource {
    private static final Set<String> PRECISE = Set.of("TSF_G16", "TSF_RGBA16", "TSF_R16F", "TSF_R32F", "TSF_RGBA16F", "TSF_RGBA32F", "TSF_BGRE8");
    private RawTextureSource() { }

    static boolean required(String format) { return PRECISE.contains(format); }

    static float[] read(Path pixels, TextureMetadata metadata, String expectedFormat) throws IOException {
        Path description = pixels.resolveSibling(pixels.getFileName() + ".json");
        JsonObject data = JsonParser.parseString(Files.readString(description, StandardCharsets.UTF_8)).getAsJsonObject();
        if (data.get("format_version").getAsInt() != 1) throw new IOException("Unsupported raw texture source version: " + description);
        JsonObject base = null;
        for (var item : data.getAsJsonArray("levels")) {
            JsonObject level = item.getAsJsonObject();
            if (level.get("block").getAsInt() != 0 || level.get("layer").getAsInt() != 0 || level.get("mip").getAsInt() != 0) continue;
            if (base != null) throw new IOException("Duplicate base raw texture source: " + description);
            base = level;
        }
        if (base == null || base.get("slices").getAsInt() != 1 || base.get("width").getAsInt() != metadata.width()
                || base.get("height").getAsInt() != metadata.height() || !base.get("format").getAsString().equals(expectedFormat))
            throw new IOException("Raw texture source layout differs from its metadata: " + description);
        int channels = Math.multiplyExact(Math.multiplyExact(metadata.width(), metadata.height()), 4);
        int bytes = switch (expectedFormat) {
            case "TSF_G16", "TSF_R16F" -> channels / 2;
            case "TSF_R32F", "TSF_BGRE8" -> channels;
            case "TSF_RGBA16", "TSF_RGBA16F" -> Math.multiplyExact(channels, 2);
            case "TSF_RGBA32F" -> Math.multiplyExact(channels, 4);
            default -> throw new IOException("Unsupported high-precision source format " + expectedFormat);
        };
        byte[] source = Files.readAllBytes(pixels);
        long offset = base.get("offset").getAsLong();
        if (offset < 0 || offset > source.length || bytes > source.length - offset)
            throw new IOException("Truncated raw texture source " + pixels);
        ByteBuffer input = ByteBuffer.wrap(source, Math.toIntExact(offset), bytes).slice().order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[channels];
        for (int pixel = 0; pixel < result.length; pixel += 4) {
            switch (expectedFormat) {
                case "TSF_G16" -> {
                    float value = Short.toUnsignedInt(input.getShort()) * (1f / 65535f);
                    result[pixel] = result[pixel + 1] = result[pixel + 2] = value;
                    result[pixel + 3] = 1;
                }
                case "TSF_RGBA16" -> { for (int channel = 0; channel < 4; channel++) result[pixel + channel] = Short.toUnsignedInt(input.getShort()) * (1f / 65535f); }
                case "TSF_R16F" -> { result[pixel] = Float.float16ToFloat(input.getShort()); result[pixel + 3] = 1; }
                case "TSF_R32F" -> { result[pixel] = input.getFloat(); result[pixel + 3] = 1; }
                case "TSF_RGBA16F" -> { for (int channel = 0; channel < 4; channel++) result[pixel + channel] = Float.float16ToFloat(input.getShort()); }
                case "TSF_RGBA32F" -> { for (int channel = 0; channel < 4; channel++) result[pixel + channel] = input.getFloat(); }
                case "TSF_BGRE8" -> {
                    int blue = Byte.toUnsignedInt(input.get()), green = Byte.toUnsignedInt(input.get());
                    int red = Byte.toUnsignedInt(input.get()), exponent = Byte.toUnsignedInt(input.get());
                    if (exponent != 0) {
                        float scale = Math.scalb(1f, exponent - 136);
                        result[pixel] = (red + .5f) * scale; result[pixel + 1] = (green + .5f) * scale; result[pixel + 2] = (blue + .5f) * scale;
                    }
                    result[pixel + 3] = 1;
                }
                default -> throw new IOException("Unsupported raw source format " + expectedFormat);
            }
        }
        return result;
    }
}
