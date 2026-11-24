package com.kltyton.lumenfx.client.render.material.blend;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.lang.ref.WeakReference;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/** Selects the native dual-source blend only where the current device can provide it. */
public final class ParticleDualSourceBlend {
    // Identity marks just the LumenFX blend state whose destination factor is sourced from output index 1.
    public static final BlendFunction SCENE = new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_COLOR,
            BlendFactor.ONE, BlendFactor.SRC_ALPHA);
    public static final BlendFunction SEPARATE = new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_COLOR,
            BlendFactor.ZERO, BlendFactor.ONE);
    private static WeakReference<GpuDevice> lastDevice = new WeakReference<>(null);
    private static boolean lastSupport;

    private ParticleDualSourceBlend() { }

    public static boolean supported(GpuDevice device) {
        if (lastDevice.get() != device) {
            lastSupport = query(device);
            lastDevice = new WeakReference<>(device);
        }
        return lastSupport;
    }

    private static boolean query(GpuDevice device) {
        if (device.backend instanceof GlDevice)
            return GL.getCapabilities().OpenGL33 && GL33C.glGetInteger(GL33C.GL_MAX_DUAL_SOURCE_DRAW_BUFFERS) > 0;
        if (device.backend instanceof VulkanDevice vulkan) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var supported = VkPhysicalDeviceFeatures.calloc(stack);
                VK12.vkGetPhysicalDeviceFeatures(vulkan.vkDevice().getPhysicalDevice(), supported);
                return supported.dualSrcBlend();
            }
        }
        return false;
    }
}
