package com.kltyton.lumenfx.client.render.texture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;

/** Color-only target that releases partially allocated attachments if creation fails. */
public final class OwnedColorTarget extends RenderTarget implements AutoCloseable {
    public OwnedColorTarget(String label, int width, int height, GpuFormat format) {
        super(label, format, null);
        try { resize(width, height); }
        catch (RuntimeException | Error error) { closeOnFailure(error); throw error; }
    }

    public void closeOnFailure(Throwable failure) {
        try { destroyBuffers(); }
        catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }

    @Override public void close() { destroyBuffers(); }
}
