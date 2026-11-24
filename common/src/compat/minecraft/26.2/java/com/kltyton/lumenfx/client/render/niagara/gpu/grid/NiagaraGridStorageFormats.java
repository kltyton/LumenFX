package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.GridBufferFormat;
import com.mojang.blaze3d.GpuFormat;
import java.util.function.Predicate;

final class NiagaraGridStorageFormats {
    private static final int[][] PIXEL_FORMATS = {{7, 4, 0}, {8, 5, 1}, {9, 6, 2}, {9, 6, 2}};

    private NiagaraGridStorageFormats() { }

    static int select(int channels, GridBufferFormat format, Predicate<GpuFormat> supported) {
        int candidate = PIXEL_FORMATS[channels - 1][format.ordinal()];
        while (!supported.test(RenderTargetTextureDefinition.gpuFormat(candidate))) {
            candidate = switch (candidate) {
                case 0 -> 4; case 4 -> 7; case 7 -> 8;
                case 1 -> 5; case 5 -> 8; case 8 -> 9;
                case 2 -> 6; case 6 -> 9;
                default -> throw new IllegalStateException("No supported typed storage format for Niagara Grid Collection");
            };
        }
        return candidate;
    }
}
