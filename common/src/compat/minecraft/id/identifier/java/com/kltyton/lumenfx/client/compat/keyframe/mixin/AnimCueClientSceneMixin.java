package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.keyframe.AnimCueKeyframeBridge;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pure soft-compat mixin for AnimCue's {@code ClientScene}: drives LumenFX keyframes on server-synced remote scenes,
 * samples per-participant 3D bone/bend locators, and cleans up bound effects when the scene finishes or cancels.
 */
@Pseudo
@Mixin(targets = "com.kltyton.interactiveanimation.client.playback.ClientScene", remap = false)
public abstract class AnimCueClientSceneMixin {
    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void lumenfx$onClientSceneTick(Entity player, CallbackInfo ci) {
        AnimCueKeyframeBridge.onClientSceneTick(this);
    }

    @Inject(method = "finish", at = @At("HEAD"), remap = false)
    private void lumenfx$onClientSceneFinish(CallbackInfo ci) {
        AnimCueKeyframeBridge.onClientSceneFinish(this);
    }

    @Inject(method = "actor", at = @At("RETURN"), remap = false)
    private void lumenfx$onActorSampled(Entity entity, float partialTick, CallbackInfoReturnable<Object> cir) {
        AnimCueKeyframeBridge.onClientActorSampled(this, cir.getReturnValue());
    }

    @Inject(method = "collectExtras", at = @At("RETURN"), remap = false)
    private void lumenfx$onExtrasCollected(List<?> result, float partialTick, Predicate<Entity> submitted, CallbackInfo ci) {
        AnimCueKeyframeBridge.onClientExtrasCollected(this, result);
    }
}
