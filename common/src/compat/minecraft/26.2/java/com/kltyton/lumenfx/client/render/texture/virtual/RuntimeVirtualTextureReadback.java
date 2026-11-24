package com.kltyton.lumenfx.client.render.texture.virtual;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;

/** Fenced transfer from a material attachment or a BC-block image. */
final class RuntimeVirtualTextureReadback {
    private static final int[] PACK = {
            GL11C.GL_PACK_ALIGNMENT, GL11C.GL_PACK_ROW_LENGTH, GL11C.GL_PACK_SKIP_PIXELS,
            GL11C.GL_PACK_SKIP_ROWS, GL11C.GL_PACK_SWAP_BYTES
    };
    private RuntimeVirtualTextureReadback() { }

    static CompletableFuture<byte[]> copy(CommandEncoder encoder, GpuTexture image, int mip, boolean computeWrites) {
        var device = RenderSystem.getDevice();
        if (image.isClosed() || (image.usage() & GpuTexture.USAGE_COPY_SRC) == 0)
            throw new IllegalArgumentException("RVT material target must support texture readback");
        int bytes = Math.multiplyExact(Math.multiplyExact(image.getWidth(mip), image.getHeight(mip)), image.getFormat().blockSize());
        var staging = device.createBuffer(() -> "LumenFX RVT layer readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
        var ready = new CompletableFuture<byte[]>();
        Runnable complete = () -> {
            if (ready.isDone()) return;
            try (staging; var mapped = staging.slice().map(true, false)) {
                byte[] result = new byte[bytes];
                mapped.data().get(result);
                ready.complete(result);
            } catch (RuntimeException | Error failure) {
                ready.completeExceptionally(failure);
            }
        };
        try {
            if (device.backend instanceof GlDevice) {
                if (computeWrites) GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_PIXEL_BUFFER_BARRIER_BIT);
                copyGl(encoder, image, mip, staging, complete);
            }
            else encoder.copyTextureToBuffer(image, staging, 0, complete, mip);
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            // The host may already have recorded the transfer before reporting an error.
            RenderSystem.queueFencedTask(staging::close);
            throw failure;
        }
        return ready;
    }

    private static void copyGl(CommandEncoder encoder, GpuTexture image, int mip, GpuBuffer staging, Runnable complete) {
        int framebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int buffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] previous = new int[PACK.length];
        for (int index = 0; index < PACK.length; index++) {
            previous[index] = GL11C.glGetInteger(PACK[index]);
            GL11C.glPixelStorei(PACK[index], index == 0 ? 1 : 0);
        }
        try {
            encoder.copyTextureToBuffer(image, staging, 0, complete, mip);
        } finally {
            for (int index = 0; index < PACK.length; index++) GL11C.glPixelStorei(PACK[index], previous[index]);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
        }
    }
}
