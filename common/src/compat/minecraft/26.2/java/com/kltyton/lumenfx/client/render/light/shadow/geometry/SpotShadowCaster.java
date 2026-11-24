package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Converts caster depth to the same linear domain used by the projected receiver filter. */
public final class SpotShadowCaster {
    public static final String UNIFORM = "SpotShadowCaster";
    public static final BindGroupLayout LAYOUT = BindGroupLayout.builder().withUniform(UNIFORM, UniformType.UNIFORM_BUFFER).build();

    private SpotShadowCaster() { }

    public static @Nullable GpuBufferSlice uniform(CommandEncoder encoder, ShadowRasterViews views) {
        if (!(views instanceof PointShadowMatrices matrices) || !matrices.spot()) return null;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(32,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            matrices.writeSpotCaster(mapped.data().order(ByteOrder.nativeOrder()));
            return mapped.slice();
        }
    }

    public static String fragment(String source, String coverage) {
        int insertion = GlslSourceScanner.header(source).insertionIndex();
        var include = LumenFx.id("shaders/include/lighting/spot_shadow_caster.glsl");
        String depth;
        try (var input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(include).open()) {
            depth = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Missing spot shadow caster shader " + include, error);
        }
        return source.substring(0, insertion) + "\n#define LFX_SHADOW_DEPTH_PASS 1\n#define main lfxSpotCasterOriginalMain\n"
                + source.substring(insertion) + "\n#undef main\n" + depth
                + "\nvoid main() {\n    float lfxCasterBias = lfx_spot_caster_bias();\n"
                + coverage + "    gl_FragDepth = gl_FragCoord.z;\n    lfxSpotCasterOriginalMain();\n"
                + "    gl_FragDepth = lfx_spot_caster_depth(gl_FragDepth, lfxCasterBias);\n}\n";
    }
}
