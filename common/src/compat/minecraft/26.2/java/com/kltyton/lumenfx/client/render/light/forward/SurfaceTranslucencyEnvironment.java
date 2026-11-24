package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.client.render.light.scene.LocalReflectionBindings;
import com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse;
import com.kltyton.lumenfx.client.render.light.scene.SkyReflectionBindings;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The indirect scene inputs shared by both translucent surface-lighting modes. */
public record SurfaceTranslucencyEnvironment(SceneIndirectDiffuse.Sample indirect, SkyReflectionBindings.@Nullable Sample sky,
                                             LocalReflectionBindings.@Nullable Frame local,
                                             ForwardTranslucentLights.@Nullable ReflectionGrid captureGrid) {
    private static final String PRIMITIVE_CAPTURE = "LfxForwardPrimitiveCapture";

    public static SurfaceTranslucencyEnvironment prepare(CommandEncoder encoder, SceneIndirectLighting indirect,
                                                         NiagaraPlatformEnvironment platform, Vec3 camera, int alignment,
                                                         LocalReflectionBindings.@Nullable Frame local,
                                                         ForwardTranslucentLights.@Nullable ReflectionGrid captureGrid) {
        return new SurfaceTranslucencyEnvironment(SceneIndirectDiffuse.prepare(encoder, indirect, platform, camera, alignment, true, 0),
                SkyReflectionBindings.prepare(encoder, indirect.skyLight(), platform, alignment), local, captureGrid);
    }

    public static void layout(BindGroupLayout.Builder layout, ForwardTranslucentLights.Features features, boolean singleCapture) {
        SceneIndirectDiffuse.layout(layout, features.volumetricLightmap());
        if (features.skyReflection()) SkyReflectionBindings.layout(layout, features.preintegratedGf(), features.skyBlend());
        if (features.localCaptures()) {
            LocalReflectionBindings.layout(layout);
            if (singleCapture) layout.withUniform(PRIMITIVE_CAPTURE, UniformType.UNIFORM_BUFFER);
            else ForwardTranslucentLights.ReflectionGrid.layout(layout);
        }
    }

    public static GpuBufferSlice primitiveCapture(CommandEncoder encoder, int alignment, int index) {
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(index).putInt(0).putInt(0).putInt(0);
            return mapped.slice();
        }
    }

    public void bind(RenderPass pass, @Nullable GpuBufferSlice primitiveCapture) {
        indirect.bind(pass);
        if (sky != null) sky.bind(pass);
        if (local != null) local.bind(pass);
        if (primitiveCapture != null) pass.setUniform(PRIMITIVE_CAPTURE, primitiveCapture);
        else if (captureGrid != null) captureGrid.bind(pass);
    }
}
