package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Exact per-submit scope in the native item private renderer method. */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
    @WrapMethod(method = "prepareSubmit")
    private void lumenfx$itemSubmit(
            ItemFeatureRenderer.Submit submit, boolean foil, Operation<Void> original) {
        boolean active = MotionHistory.INSTANCE.beginSubmit(submit);
        try {
            original.call(submit, foil);
        } finally {
            if (active) {
                MotionHistory.INSTANCE.endSubmit();
            }
        }
    }
}
