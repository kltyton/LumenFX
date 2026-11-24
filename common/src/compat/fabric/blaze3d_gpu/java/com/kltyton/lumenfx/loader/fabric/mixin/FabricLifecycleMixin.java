package com.kltyton.lumenfx.loader.fabric.mixin;

import com.kltyton.lumenfx.client.event.WorldFxClientEvents;
import com.kltyton.lumenfx.client.render.temporal.view.ViewFrameClockEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies render-frame start/end boundaries on Fabric matching NeoForge's RenderFrameEvent.Pre/Post. */
@Mixin(Minecraft.class)
abstract class FabricLifecycleMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void lumenfx$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        ViewFrameClockEvents.begin();
        WorldFxClientEvents.onFrameStart();
    }

    @Inject(method = "renderFrame(Z)V", at = @At("RETURN"))
    private void lumenfx$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
        WorldFxClientEvents.onFrameEnd();
    }
}
