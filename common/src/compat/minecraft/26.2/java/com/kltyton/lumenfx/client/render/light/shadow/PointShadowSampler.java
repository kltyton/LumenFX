package com.kltyton.lumenfx.client.render.light.shadow;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.OptionalDouble;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Source cubemap PCF uses bilinear clamp and a strict-less comparison with reverse-Z depths. */
public final class PointShadowSampler {
    private static final ThreadLocal<Boolean> CREATION = new ThreadLocal<>();
    private PointShadowSampler() { }

    public static GpuSampler create() {
        RenderSystem.assertOnRenderThread();
        Boolean previous = CREATION.get(); CREATION.set(true);
        GpuSampler sampler;
        try {
            sampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0));
        } finally { if (previous == null) CREATION.remove(); else CREATION.set(previous); }
        if (sampler instanceof GlSampler gl) {
            try {
                GL33C.glSamplerParameteri(gl.getId(), GL14C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
                GL33C.glSamplerParameteri(gl.getId(), GL14C.GL_TEXTURE_COMPARE_FUNC, GL11C.GL_LESS);
                GL33C.glSamplerParameteri(gl.getId(), GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
                GL33C.glSamplerParameteri(gl.getId(), GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            } catch (RuntimeException | Error failure) { sampler.close(); throw failure; }
        }
        return sampler;
    }

    public static VkSamplerCreateInfo configure(VkSamplerCreateInfo info) {
        if (Boolean.TRUE.equals(CREATION.get()))
            info.compareEnable(true).compareOp(VK12.VK_COMPARE_OP_LESS).addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        return info;
    }
}
