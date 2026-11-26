package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Tracks motion history during the native item submission. */
@Mixin(ItemFeatureRenderer.class)
abstract class ItemFeatureRendererMixin {
    @WrapMethod(method = "prepareSubmit")
    private void lumenfx$itemSubmit(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
        boolean active = MotionHistory.INSTANCE.beginSubmit(submit);
        try {
            original.call(submit);
        } finally {
            if (active) MotionHistory.INSTANCE.endSubmit();
        }
    }
}
