package com.kltyton.lumenfx.client.compat.sodium.mixin;

import com.kltyton.lumenfx.client.compat.kbr.KbrSodiumBillboardBridge;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.common.ParticleVertex;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves KBR rectangular and custom-basis Bedrock billboard particles when Sodium 0.9.3+'s fast
 * intrinsic {@code QuadParticleRenderStateMixin} is active on MC 26.3.
 */
@Mixin(value = QuadParticleRenderState.class, priority = 900)
abstract class KbrBillboardSodiumCompatMixin {
    @Unique
    private static final Quaternionf LUMENFX$QUAT = new Quaternionf();
    @Unique
    private static final Vector3f LUMENFX$VECTOR = new Vector3f();

    @Inject(method = "renderRotatedQuad", at = @At("HEAD"), cancellable = true)
    private void lumenfx$emitKbrBillboardQuad(
            VertexConsumer builder,
            float x, float y, float z,
            float xRot, float yRot, float zRot, float wRot,
            float scale,
            float u0, float u1, float v0, float v1,
            int color, int lightCoords,
            CallbackInfo ci) {
        if (!KbrSodiumBillboardBridge.isActive()) {
            return;
        }
        VertexBufferWriter writer = VertexBufferWriter.sodium$tryOf(builder);
        if (writer == null) {
            return;
        }
        ci.cancel();

        float width = KbrSodiumBillboardBridge.selectNext(scale);
        LUMENFX$QUAT.set(xRot, yRot, zRot, wRot);
        int abgr = ColorARGB.toABGR(color);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long base = stack.nmalloc(4 * ParticleVertex.STRIDE);
            long ptr = base;

            lumenfx$putCorner(ptr, x, y, z, 1.0F, -1.0F, width, u1, v1, abgr, lightCoords);
            ptr += ParticleVertex.STRIDE;
            lumenfx$putCorner(ptr, x, y, z, 1.0F, 1.0F, width, u1, v0, abgr, lightCoords);
            ptr += ParticleVertex.STRIDE;
            lumenfx$putCorner(ptr, x, y, z, -1.0F, 1.0F, width, u0, v0, abgr, lightCoords);
            ptr += ParticleVertex.STRIDE;
            lumenfx$putCorner(ptr, x, y, z, -1.0F, -1.0F, width, u0, v1, abgr, lightCoords);

            writer.sodium$push(stack, base, 4, ParticleVertex.FORMAT);
        }
    }

    @Unique
    private static void lumenfx$putCorner(
            long ptr,
            float centerX, float centerY, float centerZ,
            float nx, float ny, float width,
            float u, float v, int abgr, int lightCoords) {
        Vector3f point = KbrSodiumBillboardBridge.transformCorner(
                LUMENFX$VECTOR, LUMENFX$QUAT, centerX, centerY, centerZ, nx, ny, width);
        ParticleVertex.put(ptr, point.x, point.y, point.z, u, v, abgr, lightCoords);
    }
}
