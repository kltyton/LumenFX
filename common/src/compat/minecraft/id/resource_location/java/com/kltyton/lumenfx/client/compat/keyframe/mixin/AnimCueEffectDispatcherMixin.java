package com.kltyton.lumenfx.client.compat.keyframe.mixin;

import com.kltyton.lumenfx.client.keyframe.AnimCueKeyframeBridge;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures active AnimCue {@code SceneHandle} and {@code SceneDefinition} around {@code SceneEffectDispatcher.dispatch}. */
@Pseudo
@Mixin(targets = "com.kltyton.interactiveanimation.scene.playback.SceneEffectDispatcher", remap = false)
public abstract class AnimCueEffectDispatcherMixin {
    @Inject(method = "dispatch", at = @At("HEAD"), remap = false)
    private void lumenfx$enterDispatch(
            Level level, @Coerce Object handle, @Coerce Object definition, @Coerce Object options,
            boolean nativePlayback, double seconds, Supplier<Vec3> primaryPos, Entity primaryEntity,
            Function<String, Vec3> rolePos, Function<String, Entity> roleEntity,
            BooleanSupplier closed, CallbackInfo ci) {
        AnimCueKeyframeBridge.enterDispatcher(this, handle, definition);
    }

    @Inject(method = "dispatch", at = @At("RETURN"), remap = false)
    private void lumenfx$exitDispatch(CallbackInfo ci) {
        AnimCueKeyframeBridge.exitDispatcher(this);
    }
}
