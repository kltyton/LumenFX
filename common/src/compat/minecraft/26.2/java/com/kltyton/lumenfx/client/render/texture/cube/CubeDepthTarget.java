package com.kltyton.lumenfx.client.render.texture.cube;

import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Six renderable face views and one sampled cube view own a single depth allocation. */
public final class CubeDepthTarget implements ShadowDepthTarget {
    public static int maximumSize() {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        var limits = device.getDeviceInfo().limits();
        int physical = limits.maxTextureSize();
        if (device.backend instanceof GlDevice) physical = Math.min(physical, GL11C.glGetInteger(GL13C.GL_MAX_CUBE_MAP_TEXTURE_SIZE));
        else if (device.backend instanceof com.mojang.blaze3d.vulkan.VulkanDevice vk) {
            try (var stack = MemoryStack.stackPush()) {
                var properties = VkPhysicalDeviceProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceProperties(vk.vkDevice().getPhysicalDevice(), properties);
                physical = Math.min(physical, properties.limits().maxImageDimensionCube());
            }
        } else throw new IllegalStateException("Cube depth storage has no backend adapter");
        int allocation = (int)Math.min(Integer.MAX_VALUE, Math.sqrt((double)limits.maxMemoryAllocationSize() / (6 * GpuFormat.D32_FLOAT.blockSize())));
        return Math.min(physical, Integer.highestOneBit(allocation));
    }
    private final GpuTexture texture;
    private final GpuTextureView sampled;
    private final OwnedColorTarget rasterColor;
    private final @Nullable GpuTextureView[] faces = new GpuTextureView[6];
    private final int size;

    public CubeDepthTarget(String label, int size) {
        RenderSystem.assertOnRenderThread();
        if (size <= 0) throw new IllegalArgumentException("Cube depth size must be positive");
        this.size = size;
        var device = RenderSystem.getDevice();
        texture = device.backend instanceof GlDevice gl ? GlCubeRenderTexture.create(gl, label, GpuFormat.D32_FLOAT, size)
                : device.createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_CUBEMAP_COMPATIBLE | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                        GpuFormat.D32_FLOAT, size, size, 6, 1);
        GpuTextureView cube = null;
        OwnedColorTarget color = null;
        try {
            // The host pipeline builder supplies a color slot even for depth-only shaders; it is never written or sampled.
            color = new OwnedColorTarget(label + " raster color slot", size, size, GpuFormat.R8_UNORM);
            cube = device.createTextureView(texture);
            for (int face = 0; face < 6; face++) faces[face] = CubeFaceViews.create(device, texture, face);
        } catch (RuntimeException | Error failure) {
            for (var face : faces) if (face != null) face.close();
            if (cube != null) cube.close();
            if (color != null) color.close();
            texture.close(); throw failure;
        }
        sampled = cube;
        rasterColor = color;
    }

    public int size() { return size; }
    @Override public int viewCount() { return faces.length; }
    public GpuTextureView rasterColorView() { return rasterColor.getColorTextureView(); }
    public GpuTextureView sampled() { return sampled; }
    GpuTexture texture() { return texture; }
    public void copyFrom(CubeDepthTarget source) { CubeDepthCopy.copy(source, this); }
    @Override public void copyFrom(ShadowDepthTarget source) {
        if (!(source instanceof CubeDepthTarget cube)) throw new IllegalArgumentException("Cube depth copy requires a cube source");
        copyFrom(cube);
    }
    public GpuTextureView face(int face) {
        if (face < 0 || face >= 6) throw new IllegalArgumentException("Invalid cube face " + face);
        return Objects.requireNonNull(faces[face]);
    }
    public RenderPassDescriptor descriptor(int face, double clearDepth) {
        return descriptor(face, OptionalDouble.of(clearDepth), () -> "LumenFX cube depth face " + face);
    }
    public RenderPassDescriptor descriptor(int face, Supplier<String> label) {
        return descriptor(face, OptionalDouble.empty(), label);
    }
    private RenderPassDescriptor descriptor(int face, OptionalDouble clearDepth, Supplier<String> label) {
        return RenderPassDescriptor.create(label)
                .withColorAttachment(rasterColor.getColorTextureView())
                .withDepthAttachment(face(face), clearDepth)
                .withRenderArea(new RenderPass.RenderArea(0, 0, size, size));
    }
    @Override public void close() {
        for (var face : faces) if (face != null) face.close();
        sampled.close(); texture.close(); rasterColor.close();
    }
}
