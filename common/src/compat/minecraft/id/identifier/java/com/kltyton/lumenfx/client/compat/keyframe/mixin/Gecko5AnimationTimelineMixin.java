package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.compat.keyframe.Gecko5KeyframeBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Wraps each GeckoLib 5 particle keyframe handler invocation in a true {@code try/finally} per-dispatch identity
 * scope so public {@code LumenFxKeyframeBridge.trigger(...)} calls inside a custom handler share the exact same
 * dispatch token as KBR's automatic {@code GeckoLibHelper.processParticleEffect(event)} hook, even when the callback
 * throws an exception caught by an outer caller. Uses {@code @Redirect} because MixinExtras is not bundled across all
 * LumenFX targets and {@code @Inject(BEFORE/AFTER)} cannot guarantee exception-path cleanup. Does NOT mutate or wrap
 * {@code particleHandler}, preserving standalone callback-only semantics.
 */
@Pseudo
@Mixin(targets = "com.geckolib.animation.state.AnimationTimeline", remap = false)
public abstract class Gecko5AnimationTimelineMixin {
    @Redirect(
            method = "triggerKeyframeMarkersBetween",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/geckolib/animation/AnimationController$KeyframeEventHandler;handle(Lcom/geckolib/animation/state/KeyFrameEvent;)V",
                    ordinal = 1
            ),
            remap = false
    )
    private void lumenfx$dispatchParticleKeyframe(@Coerce Object handler, @Coerce Object event) {
        Gecko5KeyframeBridge.dispatchWithScope(this, handler, event);
    }
}
