package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import javax.annotation.Nullable;

/** Opens particle raster batches against the actual view attachments and clips to the view. */
public final class ParticleRasterPass {
    private ParticleRasterPass() {
    }

    public static RenderPass open(CommandEncoder encoder, String label, GpuTextureView color,
                                  @Nullable GpuTextureView depth, ScissorState scissor) {
        return clip(encoder.createRenderPass(() -> label, color, Optional.empty(), depth, OptionalDouble.empty()), scissor);
    }

    public static RenderPass open(CommandEncoder encoder, RenderPassDescriptor descriptor, ScissorState scissor) {
        return clip(encoder.createRenderPass(descriptor), scissor);
    }

    private static RenderPass clip(RenderPass pass, ScissorState scissor) {
        try {
            if (scissor.enabled()) {
                pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
            return pass;
        } catch (RuntimeException | Error failure) {
            try {
                pass.close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
