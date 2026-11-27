package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.keyframe.AnimCueKeyframeBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pure soft-compat mixin for AnimCue's {@code KbrParticleCompat.spawn} (non-overloaded method).
 * Preserves AnimCue's built-in Vanilla -> KBR priority and falls back to LumenFX on the client when neither matched.
 */
@Pseudo
@Mixin(targets = "com.kltyton.interactiveanimation.scene.playback.KbrParticleCompat", remap = false)
public abstract class AnimCueKbrParticleCompatMixin {
    @Inject(method = "spawn", at = @At("RETURN"), cancellable = true, remap = false)
    private static void lumenfx$fallbackToLumenFx(
            Level level, Entity sourceEntity, Vec3 position, String effect, String script,
            CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())
                && AnimCueKeyframeBridge.onSpawnParticleFallback(level, sourceEntity, position, effect, script)) {
            cir.setReturnValue(true);
        }
    }
}
