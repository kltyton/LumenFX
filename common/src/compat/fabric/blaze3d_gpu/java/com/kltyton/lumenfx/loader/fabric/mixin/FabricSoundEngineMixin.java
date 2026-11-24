package com.kltyton.lumenfx.loader.fabric.mixin;

import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears cached Niagara audio bus analysis whenever the OpenAL sound engine reloads on Fabric. */
@Mixin(SoundEngine.class)
abstract class FabricSoundEngineMixin {
    @Inject(method = "loadLibrary", at = @At("RETURN"))
    private void lumenfx$soundEngineLoaded(CallbackInfo ci) {
        ClientVfxEvents.onSoundEngineLoaded();
    }
}
