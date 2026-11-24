package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;

/** Two frames of actual vertex-shader clip output, indexed independently of sorted draw indices. */
public final class VertexClipAtlas implements AutoCloseable {
    public record Layout(int width, int height, int vertexCount) { }
    public record Frame(GpuTextureView current, Layout currentLayout,
                        GpuTextureView previous, Layout previousLayout, boolean historyValid) { }
    private static final Vector4f CLEAR = new Vector4f();
    private TextureTarget previous, current;
    private int previousCount, currentCount;
    private boolean previousReady, open;

    public Frame beginFrame(int vertexCount, boolean retainPrevious) {
        RenderSystem.assertOnRenderThread();
        if (open) throw new IllegalStateException("The vertex clip frame is already open");
        if (vertexCount < 0) throw new IllegalArgumentException("Negative vertex clip count");
        current = allocate(current, vertexCount);
        currentCount = vertexCount;
        previousReady &= retainPrevious;
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(current.getColorTexture(), CLEAR);
        open = true;
        boolean valid = previousReady && previous != null;
        TextureTarget readable = valid ? previous : current;
        return new Frame(current.getColorTextureView(), layout(current, currentCount),
                readable.getColorTextureView(), layout(readable, valid ? previousCount : 0), valid);
    }

    public void commitFrame() {
        RenderSystem.assertOnRenderThread();
        if (!open) throw new IllegalStateException("There is no open vertex clip frame");
        TextureTarget spare = previous;
        previous = current;
        current = spare;
        previousCount = currentCount;
        previousReady = true;
        open = false;
    }

    /** A read-only view keeps the previous geometry history and discards only the current output. */
    public void holdFrame() {
        RenderSystem.assertOnRenderThread();
        if (!open) throw new IllegalStateException("There is no open vertex clip frame");
        open = false;
    }

    /** An interrupted draw cannot become the next frame's geometry history. */
    public void discardFrame() {
        RenderSystem.assertOnRenderThread();
        previousReady = false;
        open = false;
    }

    private static Layout layout(TextureTarget target, int count) {
        return new Layout(target.width, target.height, count);
    }

    private static TextureTarget allocate(TextureTarget target, int count) {
        if (target != null && (long)target.width * target.height >= count) return target;
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA32_FLOAT);
        int needed = Math.max(count, 1);
        if ((long)limit * limit < needed)
            throw new IllegalArgumentException("Vertex clip history exceeds the device texture extent: " + needed);
        int width = Math.min(limit, (int)Math.ceil(Math.sqrt(needed)));
        int height = needed / width + (needed % width == 0 ? 0 : 1);
        if (target == null) return new TextureTarget("LumenFX vertex clip history", width, height, false, GpuFormat.RGBA32_FLOAT);
        target.resize(width, height);
        return target;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (previous != null) previous.destroyBuffers();
        if (current != null) current.destroyBuffers();
        previous = current = null;
        previousCount = currentCount = 0;
        previousReady = open = false;
    }
}
