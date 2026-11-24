package com.kltyton.lumenfx.client.simulation.niagara.host.mixin;

import com.kltyton.lumenfx.client.VfxEngine;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies the world-ready, pre-camera frame boundary that NeoForge's render events do not expose. */
@Mixin(Minecraft.class)
abstract class MinecraftVfxFrameMixin {
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
    private void lumenfx$simulateBeforeCamera(boolean advanceGameTime, CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        var level = minecraft.level;
        if (advanceGameTime && level != null && minecraft.isGameLoadFinished()) {
            double time = (level.getGameTime() + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0;
            VfxEngine.INSTANCE.simulateBeforeCamera(level, minecraft.gameRenderer.mainCamera(), time);
        }
    }
}
