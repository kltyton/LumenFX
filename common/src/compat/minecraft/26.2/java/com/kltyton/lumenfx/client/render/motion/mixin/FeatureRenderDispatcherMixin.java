package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import net.minecraft.client.Minecraft;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/** Opens the level-only prepare scope; hand/screen prepare uses a different storage and is ignored. */
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {
    @WrapMethod(method = "prepareFrame")
    private FeatureRenderDispatcher.PreparedFrame lumenfx$prepare(
            SubmitNodeStorage storage,
            Operation<FeatureRenderDispatcher.PreparedFrame> original) {
        boolean level = MotionHistory.INSTANCE.isLevelStorage(storage);
        MotionHistory.INSTANCE.beginPrepare(level);
        try {
            FeatureRenderDispatcher.PreparedFrame frame = original.call(storage);
            boolean prepared = false;
            try {
                if (level) {
                    var minecraft = Minecraft.getInstance();
                    WorldHdrCapture.INSTANCE.prepareGeometryMotion(minecraft.level, minecraft.gameRenderer.mainCamera().entity());
                }
                prepared = true;
                return frame;
            } finally {
                if (!prepared) frame.close();
            }
        } finally {
            MotionHistory.INSTANCE.endPrepare();
        }
    }
}
