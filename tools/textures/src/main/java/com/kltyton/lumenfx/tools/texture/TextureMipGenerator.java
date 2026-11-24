package com.kltyton.lumenfx.tools.texture;

import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU reproduction of the UE 5.8.4 editor mip chain before BCn compression.
 *
 * Sources:
 * Engine/Source/Developer/TextureCompressor/Private/TextureCompressorModule.cpp
 *   196-256  kernel tables (2x2 average, 8-tap sharpen)
 *   607-705  2x2 average
 *   763-852  non-separable sharpen + luminance preserving ColorShift branch
 *   855-924  separable sharpen
 *   1799-2054 mip chain, average parents, 2x scale sharpen children
 * Engine/Source/Runtime/Engine/Private/TextureLODSettings.cpp:395-448
 */
public final class TextureMipGenerator {
    private static final float[] LUMINANCE = {0.3F, 0.59F, 0.11F};

    public record Level(int width, int height, float[] rgba) { }
    record Result(List<Level> levels, Boolean hasAlpha) { }

    private enum Address { WRAP, CLAMP }

    private TextureMipGenerator() { }

    public static List<Level> generate(int width, int height, int[] argb, TextureMetadata metadata, float[] decodeTable) {
        return generateProcessed(width, height, argb, metadata, decodeTable, TextureProcessing.NONE).levels();
    }

    static Result generateProcessed(int width, int height, int[] argb, TextureMetadata metadata, float[] decodeTable, TextureProcessing processing) {
        if (argb.length != width * height) throw new IllegalArgumentException("Pixel buffer does not match dimensions");
        if (metadata.srgb() && decodeTable.length != 256) throw new IllegalArgumentException("Invalid source color table");
        float[] base = new float[width * height * 4];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int red = (pixel >> 16) & 0xff, green = (pixel >> 8) & 0xff, blue = pixel & 0xff;
            float r = red * (1.0F / 255.0F), g = green * (1.0F / 255.0F), b = blue * (1.0F / 255.0F);
            if (metadata.srgb()) {
                r = decodeTable[red]; g = decodeTable[green]; b = decodeTable[blue];
            }
            base[i * 4] = r;
            base[i * 4 + 1] = g;
            base[i * 4 + 2] = b;
            base[i * 4 + 3] = ((pixel >>> 24) & 0xff) * (1.0F / 255.0F);
        }

        return generateOwned(width, height, base, metadata, processing);
    }

    public static List<Level> generateLinear(int width, int height, float[] rgba, TextureMetadata metadata) {
        return generateLinearProcessed(width, height, rgba, metadata, TextureProcessing.NONE).levels();
    }

    static Result generateLinearProcessed(int width, int height, float[] rgba, TextureMetadata metadata, TextureProcessing processing) {
        if (width != metadata.width() || height != metadata.height() || rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4))
            throw new IllegalArgumentException("Linear source pixels do not match texture dimensions");
        return generateOwned(width, height, rgba.clone(), metadata, processing);
    }

    private static Result generateOwned(int width, int height, float[] base, TextureMetadata metadata, TextureProcessing processing) {
        if (metadata.mipMode() == TextureMetadata.MipMode.NATIVE_BUILD)
            throw new IllegalArgumentException("This source mip setting requires the native Unreal texture build");
        Boolean alpha = processing == TextureProcessing.NONE ? null : processing.predictAlpha(base);
        processing.preprocess(base);
        List<Level> levels = new ArrayList<>();
        levels.add(new Level(width, height, base));
        if (metadata.mipMode() == TextureMetadata.MipMode.NO_MIPMAPS) {
            processing.postprocess(levels);
            return new Result(levels, alpha);
        }

        Address address = address(metadata);
        boolean sharpen = metadata.mipMode() == TextureMetadata.MipMode.SHARPEN;
        float[] kernel = sharpen ? sharpenKernel(metadata.sharpen()) : null;
        float[] current = base;
        int currentWidth = width, currentHeight = height;
        while (currentWidth > 1 || currentHeight > 1) {
            int nextWidth = Math.max(1, currentWidth >> 1);
            int nextHeight = Math.max(1, currentHeight >> 1);
            float[] next;
            float[] parent;
            if (sharpen) {
                next = sharpenMip(current, currentWidth, currentHeight, nextWidth, nextHeight, kernel, metadata, address);
                parent = average(current, currentWidth, currentHeight, nextWidth, nextHeight, address);
            } else {
                next = average(current, currentWidth, currentHeight, nextWidth, nextHeight, address);
                parent = next;
            }
            levels.add(new Level(nextWidth, nextHeight, next));
            current = parent;
            currentWidth = nextWidth;
            currentHeight = nextHeight;
        }
        processing.postprocess(levels);
        return new Result(levels, alpha);
    }

    private static Address address(TextureMetadata metadata) {
        if (Integer.bitCount(metadata.width()) != 1 || Integer.bitCount(metadata.height()) != 1) return Address.CLAMP;
        if (!metadata.useNewMipFilter()) return Address.WRAP;
        boolean wrapX = metadata.addressX() == TextureMetadata.Address.WRAP;
        boolean wrapY = metadata.addressY() == TextureMetadata.Address.WRAP;
        if (wrapX && wrapY) return Address.WRAP;
        if (!wrapX && !wrapY) return Address.CLAMP;
        return Address.WRAP;
    }

    private static float[] average(float[] source, int sourceWidth, int sourceHeight,
                                   int width, int height, Address address) {
        float[] result = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    float sum = sample(source, sourceWidth, sourceHeight, 2 * x, 2 * y, address, channel)
                            + sample(source, sourceWidth, sourceHeight, 2 * x + 1, 2 * y, address, channel)
                            + sample(source, sourceWidth, sourceHeight, 2 * x, 2 * y + 1, address, channel)
                            + sample(source, sourceWidth, sourceHeight, 2 * x + 1, 2 * y + 1, address, channel);
                    result[index + channel] = sum * 0.25F;
                }
            }
        }
        return result;
    }

    private static float[] sharpenMip(float[] source, int sourceWidth, int sourceHeight,
                                      int width, int height, float[] kernel, TextureMetadata metadata,
                                      Address address) {
        if (metadata.useNewMipFilter()) {
            return separableSharpen(source, sourceWidth, sourceHeight, width, height, kernel, address);
        }
        return nonSeparableSharpen(source, sourceWidth, sourceHeight, width, height, kernel,
                metadata.sharpenWithoutColorShift(), address);
    }

    /** TextureCompressorModule.cpp:763-852, bUseNewMipFilter == false. */
    private static float[] nonSeparableSharpen(float[] source, int sourceWidth, int sourceHeight,
                                               int width, int height, float[] kernel,
                                               boolean withoutColorShift, Address address) {
        int size = kernel.length;
        int center = (size - 1) / 2;
        float[] result = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            int sourceY = y * 2;
            for (int x = 0; x < width; x++) {
                int sourceX = x * 2;
                float r = 0, g = 0, b = 0, a = 0;
                for (int ky = 0; ky < size; ky++) {
                    float wy = kernel[ky];
                    for (int kx = 0; kx < size; kx++) {
                        float weight = kernel[kx] * wy;
                        int sampleX = sourceX + kx - center;
                        int sampleY = sourceY + ky - center;
                        r += sample(source, sourceWidth, sourceHeight, sampleX, sampleY, address, 0) * weight;
                        g += sample(source, sourceWidth, sourceHeight, sampleX, sampleY, address, 1) * weight;
                        b += sample(source, sourceWidth, sourceHeight, sampleX, sampleY, address, 2) * weight;
                        a += sample(source, sourceWidth, sourceHeight, sampleX, sampleY, address, 3) * weight;
                    }
                }
                if (withoutColorShift) {
                    float newLuminance = LUMINANCE[0] * r + LUMINANCE[1] * g + LUMINANCE[2] * b;
                    float filteredR = 0, filteredG = 0, filteredB = 0;
                    for (int oy = 0; oy <= 1; oy++) {
                        for (int ox = 0; ox <= 1; ox++) {
                            filteredR += sample(source, sourceWidth, sourceHeight, sourceX + ox, sourceY + oy, address, 0);
                            filteredG += sample(source, sourceWidth, sourceHeight, sourceX + ox, sourceY + oy, address, 1);
                            filteredB += sample(source, sourceWidth, sourceHeight, sourceX + ox, sourceY + oy, address, 2);
                        }
                    }
                    filteredR *= 0.25F;
                    filteredG *= 0.25F;
                    filteredB *= 0.25F;
                    float oldLuminance = LUMINANCE[0] * filteredR + LUMINANCE[1] * filteredG + LUMINANCE[2] * filteredB;
                    if (oldLuminance > 0.001F) {
                        float scale = newLuminance / oldLuminance;
                        filteredR *= scale;
                        filteredG *= scale;
                        filteredB *= scale;
                    }
                    r = filteredR;
                    g = filteredG;
                    b = filteredB;
                    // filtered.A = sharpened.A
                }
                int index = (y * width + x) * 4;
                result[index] = r;
                result[index + 1] = g;
                result[index + 2] = b;
                result[index + 3] = a;
            }
        }
        return result;
    }

    /** TextureCompressorModule.cpp:855-924, bUseNewMipFilter == true. */
    private static float[] separableSharpen(float[] source, int sourceWidth, int sourceHeight,
                                            int width, int height, float[] kernel, Address address) {
        int size = kernel.length;
        int center = (size - 1) / 2;
        float[] result = new float[width * height * 4];
        float[] row = new float[sourceWidth * 4];
        for (int y = 0; y < height; y++) {
            int sourceY = y * 2;
            for (int x = 0; x < sourceWidth; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int ky = 0; ky < size; ky++) {
                    int sampleY = sourceY + ky - center;
                    r += sample(source, sourceWidth, sourceHeight, x, sampleY, address, 0) * kernel[ky];
                    g += sample(source, sourceWidth, sourceHeight, x, sampleY, address, 1) * kernel[ky];
                    b += sample(source, sourceWidth, sourceHeight, x, sampleY, address, 2) * kernel[ky];
                    a += sample(source, sourceWidth, sourceHeight, x, sampleY, address, 3) * kernel[ky];
                }
                int index = x * 4;
                row[index] = r;
                row[index + 1] = g;
                row[index + 2] = b;
                row[index + 3] = a;
            }
            for (int x = 0; x < width; x++) {
                int sourceX = x * 2;
                float r = 0, g = 0, b = 0, a = 0;
                for (int kx = 0; kx < size; kx++) {
                    int sampleX = sourceX + kx - center;
                    int index = (address == Address.WRAP ? addressIndex(sampleX, sourceWidth)
                            : clampIndex(sampleX, sourceWidth)) * 4;
                    float weight = kernel[kx];
                    r += row[index] * weight;
                    g += row[index + 1] * weight;
                    b += row[index + 2] * weight;
                    a += row[index + 3] * weight;
                }
                int index = (y * width + x) * 4;
                result[index] = r;
                result[index + 1] = g;
                result[index + 2] = b;
                result[index + 3] = a;
            }
        }
        return result;
    }

    /**
     * TextureCompressorModule.cpp:235-256. TextureLODSettings.cpp:432-436 maps
     * TMGS_SharpenN to N*0.2 and kernel size 8; the kernel doubles the factor.
     */
    private static float[] sharpenKernel(float sharpen) {
        float factor = sharpen * 2.0F;
        float[] positive = new float[8];
        float inner = 0.5F * (1.0F + factor);
        positive[3] = positive[4] = inner;
        blur(positive, 1);
        float[] negative = new float[8];
        float negativeInner = -0.5F * factor;
        negative[3] = negative[4] = negativeInner;
        blur(negative, 3);
        for (int i = 0; i < positive.length; i++) positive[i] += negative[i];
        return positive;
    }

    private static void blur(float[] table, int times) {
        float[] temp = new float[table.length];
        for (int pass = 0; pass < times; pass++) {
            System.arraycopy(table, 0, temp, 0, table.length);
            for (int x = 0; x < table.length; x++) {
                float sum = temp[x];
                if (x > 0) sum += temp[x - 1];
                if (x < table.length - 1) sum += temp[x + 1];
                table[x] = sum / 3.0F;
            }
        }
    }

    private static int addressIndex(int coordinate, int size) {
        return Math.floorMod(coordinate, size);
    }

    private static int clampIndex(int coordinate, int size) {
        return Math.max(0, Math.min(size - 1, coordinate));
    }

    private static float sample(float[] source, int width, int height, int x, int y, Address address, int channel) {
        int sx = address == Address.WRAP ? addressIndex(x, width) : clampIndex(x, width);
        int sy = address == Address.WRAP ? addressIndex(y, height) : clampIndex(y, height);
        return source[(sy * width + sx) * 4 + channel];
    }

}
