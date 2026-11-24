package com.kltyton.lumenfx.client.resource.texture.lighting;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Deterministic host textures backing the fixed UE material Perlin noise lookups. */
public final class PerlinNoiseTextures {
    public static final Identifier GRADIENT = LumenFx.id("engine/material_noise_gradient");
    public static final Identifier VOLUME = LumenFx.id("engine/material_noise_volume");

    private static final int GRADIENT_EXTENT = 128;
    private static final int VOLUME_EXTENT = 16;
    private static final int[] GRADIENTS_2D = {
            0x88ffff, 0xff88ff, 0xffff88,
            0x88ff00, 0xff8800, 0xff0088,
            0x8800ff, 0x0088ff, 0x00ff88,
            0x880000, 0x008800, 0x000088
    };
    private static final int[] GRADIENTS_3D = {
            0x7ffefe, 0xfe7ffe, 0xfefe7f,
            0x7ffe00, 0xfe7f00, 0xfe007f,
            0x7f00fe, 0x007ffe, 0x00fe7f,
            0x7f0000, 0x007f00, 0x00007f
    };

    private PerlinNoiseTextures() { }

    public static boolean contains(Identifier id) {
        return id.equals(GRADIENT) || id.equals(VOLUME);
    }

    /** Returns null for texture IDs outside this producer's two reserved material lookups. */
    public static @Nullable PreparedTexture prepare(Identifier id) {
        if (id.equals(GRADIENT)) return gradient(id);
        if (id.equals(VOLUME)) return volume(id);
        return null;
    }

    private static PreparedTexture gradient(Identifier id) {
        var pixels = ByteBuffer.allocateDirect(GRADIENT_EXTENT * GRADIENT_EXTENT * Integer.BYTES);
        var random = new UnrealRandomStream(12345);
        for (int y = 0; y < GRADIENT_EXTENT; y++) {
            for (int x = 0; x < GRADIENT_EXTENT; x++) {
                int packed = GRADIENTS_2D[(int)(random.fraction() * 11.9999999f)];
                putRgba(pixels, packed, 0);
            }
        }
        pixels.flip();
        return new PreparedTexture(id, metadata(GRADIENT_EXTENT, GRADIENT_EXTENT,
                TextureMetadata.Address.WRAP, TextureMetadata.FilterMode.POINT), List.of(pixels));
    }

    private static PreparedTexture volume(Identifier id) {
        int square = VOLUME_EXTENT * VOLUME_EXTENT;
        int[] values = new int[square * VOLUME_EXTENT];
        var random = new UnrealRandomStream(0x1234);
        for (int z = 0; z < VOLUME_EXTENT - 1; z++) {
            for (int y = 0; y < VOLUME_EXTENT - 1; y++) {
                for (int x = 0; x < VOLUME_EXTENT - 1; x++) {
                    values[x + y * VOLUME_EXTENT + z * square] =
                            GRADIENTS_3D[(int)(random.fraction() * 11.9999999f)];
                }
            }
        }
        copyPeriodicBorders(values, square);

        var pixels = ByteBuffer.allocateDirect(values.length * Integer.BYTES);
        for (int z = 0; z < VOLUME_EXTENT; z++) {
            for (int y = 0; y < VOLUME_EXTENT; y++) {
                for (int x = 0; x < VOLUME_EXTENT; x++) {
                    int value = values[x + y * VOLUME_EXTENT + z * square];
                    int nx = ((value >>> 16) / 0x7f) - 1;
                    int ny = (((value >>> 8) & 0xff) / 0x7f) - 1;
                    int nz = ((value & 0xff) / 0x7f) - 1;
                    int derivative = nx * x + ny * y + nz * z + 127;
                    putRgba(pixels, value, derivative);
                }
            }
        }
        pixels.flip();
        return new PreparedTexture(id, metadata(VOLUME_EXTENT, VOLUME_EXTENT * VOLUME_EXTENT,
                TextureMetadata.Address.WRAP, TextureMetadata.FilterMode.LINEAR), List.of(pixels));
    }

    private static void copyPeriodicBorders(int[] values, int square) {
        int last = VOLUME_EXTENT - 1;
        for (int z = 0; z < VOLUME_EXTENT; z++) {
            for (int y = 0; y < VOLUME_EXTENT; y++)
                values[last + y * VOLUME_EXTENT + z * square] = values[y * VOLUME_EXTENT + z * square];
        }
        for (int z = 0; z < VOLUME_EXTENT; z++) {
            for (int x = 0; x < VOLUME_EXTENT; x++)
                values[x + last * VOLUME_EXTENT + z * square] = values[x + z * square];
        }
        for (int y = 0; y < VOLUME_EXTENT; y++) {
            for (int x = 0; x < VOLUME_EXTENT; x++)
                values[x + y * VOLUME_EXTENT + last * square] = values[x + y * VOLUME_EXTENT];
        }
    }

    private static TextureMetadata metadata(int width, int height, TextureMetadata.Address address,
                                           TextureMetadata.FilterMode filter) {
        return new TextureMetadata(width, height, false, TextureMetadata.MipMode.NO_MIPMAPS,
                0, 2, false, false, false, address, address, filter, TextureMetadata.FilterMode.POINT, 1,
                "TEXTUREGROUP_World", "TC_Default", "RGBA8", "", "", false);
    }

    /** Converts UE's BGRA8 uint table values to their sampled RGBA channel values. */
    private static void putRgba(ByteBuffer output, int packed, int alpha) {
        output.put((byte)(packed >>> 16));
        output.put((byte)(packed >>> 8));
        output.put((byte)packed);
        output.put((byte)alpha);
    }

    private static final class UnrealRandomStream {
        private int seed;

        private UnrealRandomStream(int seed) { this.seed = seed; }

        float fraction() {
            seed = seed * 196314165 + 907633515;
            return Float.intBitsToFloat(0x3f800000 | (seed >>> 9)) - 1.0f;
        }
    }
}
