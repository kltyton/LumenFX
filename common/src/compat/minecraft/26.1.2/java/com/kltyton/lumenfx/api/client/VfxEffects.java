package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Public client VFX entry point for Minecraft 26.1.2. */
public final class VfxEffects {
    private VfxEffects() { }

    public static EffectHandle spawn(ClientLevel level, Identifier effect, Vec3 position, long seed) {
        return DirectVfxEngine.INSTANCE.spawn(level, effect.toString(), position, seed);
    }

    public static EffectHandle createNiagaraComponent(ClientLevel level, Identifier effect, Vec3 position, long seed) {
        return DirectVfxEngine.INSTANCE.spawn(level, effect.toString(), position, seed);
    }

    public static int loadedEffectCount() {
        return DirectVfxEngine.INSTANCE.loadedEffectCount();
    }

    public static int activeEffectCount() {
        return DirectVfxEngine.INSTANCE.activeInstanceCount();
    }

    public static int liveGpuParticleCount() {
        return DirectVfxEngine.INSTANCE.liveGpuParticleCount();
    }

    public static int resourceGeneration() {
        return DirectVfxEngine.INSTANCE.resourceGeneration();
    }
}
