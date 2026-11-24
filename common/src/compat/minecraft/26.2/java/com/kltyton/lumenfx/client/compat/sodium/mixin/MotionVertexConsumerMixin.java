package com.kltyton.lumenfx.client.compat.sodium.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionIntrinsicTarget;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;

/** Preserves Sodium's fast intrinsic vertex-buffer writes when motion capture wraps particle or entity consumers. */
@Mixin(targets = "com.kltyton.lumenfx.client.render.motion.MotionVertexConsumer", remap = false)
abstract class MotionVertexConsumerMixin implements VertexBufferWriter {
    @Override
    public boolean canUseIntrinsics() {
        return VertexBufferWriter.tryOf(((MotionIntrinsicTarget) this).lumenfx$delegate()) != null;
    }

    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormat format) {
        var self = (MotionIntrinsicTarget) this;
        var writer = VertexBufferWriter.of(self.lumenfx$delegate());
        var pos = format.getElement("Position");
        if (pos != null) {
            int offset = pos.offset();
            int stride = format.getVertexSize();
            for (int i = 0; i < count; i++) {
                long addr = ptr + (long) i * stride + offset;
                self.lumenfx$recordIntrinsicVertex(
                        MemoryUtil.memGetFloat(addr),
                        MemoryUtil.memGetFloat(addr + 4L),
                        MemoryUtil.memGetFloat(addr + 8L));
            }
        }
        writer.push(stack, ptr, count, format);
    }
}
