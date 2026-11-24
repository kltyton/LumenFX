package com.kltyton.lumenfx.client.render.motion.mixin;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.decal.DecalNormalHistory;
import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Opens the world geometry frame before entity and block-entity render states are extracted. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMotionMixin {
    @Shadow private ClientLevel level;

    @WrapMethod(method = "extract")
    private void lumenfx$extractMotionFrame(DeltaTracker tracker, Camera camera, float partialTick,
                                           Operation<Void> original) {
        WorldHdrCapture.INSTANCE.prepareFrameOwnership();
        var effects = VfxEngine.INSTANCE;
        var profile = effects.viewSettings();
        var history = MotionHistory.INSTANCE;
        boolean external = WorldHdrCapture.INSTANCE.externalSceneOwner();
        boolean requestMotion = profile != null && (effects.hasActiveNiagaraVelocityReaders()
                || (!external && (effects.hasActiveSubsurfaceHistory()
                    || effects.hasActiveDecalRenderers() && DecalNormalHistory.enabled())));
        history.beginFrame(requestMotion, level);
        history.cameraPosition(camera.position());
        boolean extracted = false;
        try {
            original.call(tracker, camera, partialTick);
            extracted = true;
        } finally {
            if (!extracted) history.reset();
        }
    }
}
