package com.kltyton.lumenfx.client.render.light.shadow.directional;

import com.kltyton.lumenfx.LumenFx;
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

/** Keeps directional caster clamp and depth bias in the same reverse-depth domain as its atlas. */
public final class DirectionalShadowCaster {
    public static final String UNIFORM = "DirectionalShadowCaster";
    public static final BindGroupLayout LAYOUT = BindGroupLayout.builder().withUniform(UNIFORM, UniformType.UNIFORM_BUFFER).build();

    private DirectionalShadowCaster() { }

    public static @Nullable GpuBufferSlice uniform(CommandEncoder encoder, ShadowRasterViews views, int index) {
        if (!(views instanceof DirectionalShadowMatrices matrices)) return null;
        var cascade = matrices.cascades().get(index);
        var bias = cascade.casterBias();
        var projection = cascade.tiledProjection();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(bias.x).putFloat(bias.y).putFloat(bias.z).putFloat(matrices.zeroToOne() ? 1 : 2);
            data.putFloat(1 / projection.m00()).putFloat(1 / projection.m11()).putFloat(1 / projection.m22()).putFloat(0);
            var light = matrices.hostView(index);
            data.putFloat(light.m02()).putFloat(light.m12()).putFloat(light.m22()).putFloat(0);
            return mapped.slice();
        }
    }

    public static String vertex(String source, boolean hostNormal) {
        int insertion = GlslSourceScanner.header(source).insertionIndex();
        String normal = hostNormal ? ", abs(normalize(mat3(ModelViewMat) * Normal).z)" : "";
        return source.substring(0, insertion) + "\n#define LFX_SHADOW_DEPTH_PASS 1\n#define main lfxDirectionalCasterMain\n"
                + source.substring(insertion) + "\n#undef main\n#define LFX_CSM_CASTER_VERTEX\n" + shader()
                + "void main() {\n    lfxDirectionalCasterMain();\n"
                + "    gl_Position = lfx_csm_vertex_clip(gl_Position" + normal + ");\n}\n";
    }

    public static String fragment(String source, String coverage) {
        int insertion = GlslSourceScanner.header(source).insertionIndex();
        return source.substring(0, insertion) + "\n#define LFX_SHADOW_DEPTH_PASS 1\n#define main lfxDirectionalCasterMain\n"
                + source.substring(insertion) + "\n#undef main\n" + shader()
                + "void main() {\n    float bias = lfx_csm_geometry_bias();\n" + coverage
                + "    gl_FragDepth = gl_FragCoord.z;\n    lfxDirectionalCasterMain();\n"
                + "    gl_FragDepth = clamp(gl_FragDepth - bias, 0.0, 1.0);\n}\n";
    }

    private static String shader() {
        var resource = LumenFx.id("shaders/include/lighting/directional_shadow_caster.glsl");
        try (var input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(resource).open()) {
            return "\n" + new String(input.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (IOException error) {
            throw new IllegalStateException("Missing directional shadow caster shader " + resource, error);
        }
    }
}
