package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.ARBTextureBufferRange;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;

/** Reusable texture names exposing the original read-only storage to GL compute samplers. */
final class GlComputeTexelViews {
    private final Map<Integer, Texture> textures = new HashMap<>();
    private final long maxElements;
    private final int rangeAlignment;

    GlComputeTexelViews() {
        var capabilities = GL.getCapabilities();
        maxElements = Integer.toUnsignedLong(GL11C.glGetInteger(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE));
        rangeAlignment = capabilities.OpenGL43 || capabilities.GL_ARB_texture_buffer_range
                ? GL11C.glGetInteger(GL43C.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT) : 0;
    }

    void bind(ShaderBinding.StorageBuffer binding, GpuBufferSlice slice, GlBuffer buffer) {
        var view = binding.openGlTexelView();
        boolean fullBuffer = slice.offset() == 0 && slice.length() == buffer.size();
        if (slice.length() / view.format().blockSize() > maxElements) {
            throw new IllegalArgumentException("Read-only compute buffer exceeds GL texel capacity: " + binding.name());
        }
        if (!fullBuffer && (rangeAlignment == 0 || slice.offset() % rangeAlignment != 0)) {
            throw new IllegalArgumentException("Unsupported GL texel buffer range: " + binding.name());
        }

        Texture texture = textures.computeIfAbsent(view.unit(),
                unit -> new Texture(GL11C.glGenTextures(), GlConst.toGlInternalId(view.format())));
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + view.unit());
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture.id());
        GL33C.glBindSampler(view.unit(), 0);
        if (fullBuffer) {
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, texture.internalFormat(), buffer.handle());
        } else if (GL.getCapabilities().OpenGL43) {
            GL43C.glTexBufferRange(GL31C.GL_TEXTURE_BUFFER, texture.internalFormat(), buffer.handle(),
                    slice.offset(), slice.length());
        } else {
            ARBTextureBufferRange.glTexBufferRange(GL31C.GL_TEXTURE_BUFFER, texture.internalFormat(), buffer.handle(),
                    slice.offset(), slice.length());
        }
    }

    void detach() {
        for (var entry : textures.entrySet()) {
            Texture texture = entry.getValue();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + entry.getKey());
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture.id());
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, texture.internalFormat(), 0);
        }
    }

    void delete() {
        for (Texture texture : textures.values()) GL11C.glDeleteTextures(texture.id());
        textures.clear();
    }

    private record Texture(int id, int internalFormat) { }
}
