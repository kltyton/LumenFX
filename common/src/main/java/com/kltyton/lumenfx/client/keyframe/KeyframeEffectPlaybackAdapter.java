package com.kltyton.lumenfx.client.keyframe;

import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Version-neutral runtime SPI decoupling {@link KeyframeEffectManager} from 26.2 {@code Identifier}/{@code VfxEngine}
 * vs 1.20.1/1.21.1/26.1.2 {@code ResourceLocation}/{@code DirectVfxEngine}.
 */
public interface KeyframeEffectPlaybackAdapter {
    interface BoundHandle {
        Object rawHandle();

        boolean isAlive();

        void updatePose(double x, double y, double z,
                        float qx, float qy, float qz, float qw,
                        float scale,
                        @Nullable double[] actorPosition,
                        @Nullable double[] actorVelocityCmPerSec);

        void setBeamEnd(double x, double y, double z);

        void close();
    }

    boolean hasEffect(String canonicalId);

    @Nullable
    BoundHandle spawn(@Nullable ClientLevel level, String canonicalId,
                      double x, double y, double z, long seed,
                      KeyframeEffectParams params, boolean trackRenderSamples);

    /** Returns whether spawning requires a non-null {@link ClientLevel}. */
    default boolean requiresLevel() {
        return true;
    }

    /** Returns the uniform entity scale across MC 1.20.1..26.2 without binding to version-only methods. */
    default float entityScale(@Nullable Entity entity) {
        return 1.0F;
    }
}
