package com.kltyton.lumenfx.client.playback.mixin;

import com.lowdragmc.photon.client.fx.BlockEffectExecutor;
import com.lowdragmc.photon.client.fx.EntityEffectExecutor;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes the world particle engine's clock and clear generation to LumenFX playback. */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineLifecycleMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void lumenfx$beginParticleTick(CallbackInfo callback) {
        VanillaParticleHost.onEngineTick();
    }

    @Inject(method = "clearParticles", at = @At("RETURN"))
    private void lumenfx$particlesCleared(CallbackInfo callback) {
        VanillaParticleHost.onWipe();
        EntityEffectExecutor.CACHE.clear();
        BlockEffectExecutor.CACHE.clear();
    }
}
