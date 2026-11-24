package com.kltyton.lumenfx.client.render.scene.surface;

import com.mojang.blaze3d.textures.GpuTextureView;

/** Tinted material RGB and affine native responses; base alpha is zero or one plus the overlay weight. */
public record NativeMaterialResponse(GpuTextureView base, GpuTextureView zero, GpuTextureView unit) { }
