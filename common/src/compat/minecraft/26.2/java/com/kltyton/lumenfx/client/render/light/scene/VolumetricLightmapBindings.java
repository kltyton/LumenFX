package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap.Layer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Source VLM texture layers and camera-relative addressing shared by surface and volume passes. */
public final class VolumetricLightmapBindings {
    private static final String UNIFORM = "LfxVolumetricLightmap";
    private static final String INDIRECTION = "LfxVlmIndirection";
    private static final String AMBIENT = "LfxVlmAmbient";
    private static final String SH_RED = "LfxVlmShRed";
    private static final String SH_GREEN = "LfxVlmShGreen";
    private static final String SH_BLUE = "LfxVlmShBlue";
    private static final String SH_RED_HIGH = "LfxVlmShRedHigh";
    private static final String SH_GREEN_HIGH = "LfxVlmShGreenHigh";
    private static final String SH_BLUE_HIGH = "LfxVlmShBlueHigh";
    private static final String SKY_BENT = "LfxVlmSkyBent";

    private VolumetricLightmapBindings() { }

    public static void layout(BindGroupLayout.Builder bindings, boolean highBands) {
        bindings.withUniform(UNIFORM, UniformType.UNIFORM_BUFFER).withSampler(INDIRECTION).withSampler(AMBIENT)
                .withSampler(SH_RED).withSampler(SH_GREEN).withSampler(SH_BLUE).withSampler(SKY_BENT);
        if (highBands) bindings.withSampler(SH_RED_HIGH).withSampler(SH_GREEN_HIGH).withSampler(SH_BLUE_HIGH);
    }

    public static GpuBufferSlice uniforms(CommandEncoder encoder, VolumetricLightmap lightmap,
                                          Vec3 camera, int alignment, float scatteringScale) {
        var min = lightmap.min();
        var max = lightmap.max();
        double scaleX = 1 / (max.x - min.x);
        double scaleY = 1 / (max.y - min.y);
        double scaleZ = 1 / (max.z - min.z);
        try (var mapped = encoder.transientMemory().allocateGpuMapped(32, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat((float) (scaleX * 100)).putFloat((float) (scaleY * 100)).putFloat((float) (scaleZ * 100))
                    .putFloat(lightmap.brickSize());
            data.putFloat((float) ((camera.x * 100 - min.x) * scaleX))
                    .putFloat((float) ((camera.z * 100 - min.y) * scaleY))
                    .putFloat((float) ((camera.y * 100 - min.z) * scaleZ))
                    .putFloat(scatteringScale);
            return mapped.slice();
        }
    }

    public static void bind(RenderPass pass, VolumetricLightmap lightmap, GpuBufferSlice uniforms, boolean highBands) {
        pass.setUniform(UNIFORM, uniforms);
        bind(pass, INDIRECTION, lightmap, Layer.INDIRECTION);
        bind(pass, AMBIENT, lightmap, Layer.AMBIENT);
        bind(pass, SH_RED, lightmap, Layer.SH0);
        bind(pass, SH_GREEN, lightmap, Layer.SH2);
        bind(pass, SH_BLUE, lightmap, Layer.SH4);
        bind(pass, SKY_BENT, lightmap, lightmap.skyBentNormal() ? Layer.SKY_BENT_NORMAL : Layer.AMBIENT);
        if (highBands) {
            bind(pass, SH_RED_HIGH, lightmap, Layer.SH1);
            bind(pass, SH_GREEN_HIGH, lightmap, Layer.SH3);
            bind(pass, SH_BLUE_HIGH, lightmap, Layer.SH5);
        }
    }

    private static void bind(RenderPass pass, String sampler, VolumetricLightmap lightmap, Layer layer) {
        var id = lightmap.texture(layer);
        EffectTexture texture = Objects.requireNonNull(EffectTextures.get(id), "Missing volumetric lightmap texture " + id);
        pass.bindTexture(sampler, texture.arrayView(), texture.sampler());
    }
}
