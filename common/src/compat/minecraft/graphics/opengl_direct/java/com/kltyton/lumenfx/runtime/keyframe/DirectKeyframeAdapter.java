package com.kltyton.lumenfx.runtime.keyframe;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams;
import com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager;
import com.kltyton.lumenfx.client.keyframe.KeyframeEffectPlaybackAdapter;
import com.kltyton.lumenfx.runtime.DirectVfxEngine;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * OpenGL-direct playback adapter (MC 1.20.1, 1.21.1, 26.1.2) connecting {@link KeyframeEffectManager}
 * to {@link DirectVfxEngine}.
 */
public final class DirectKeyframeAdapter implements KeyframeEffectPlaybackAdapter {
    public static final DirectKeyframeAdapter INSTANCE = new DirectKeyframeAdapter();

    /**
     * One-time MethodHandle lookup for {@code LivingEntity#getScale()} (present on MC 1.21+ / 26.x, absent on 1.20.1).
     * Avoids linking against a 1.21-only method in the shared OpenGL-direct source set while keeping zero per-frame reflection overhead.
     */
    private static final @Nullable MethodHandle LIVING_GET_SCALE = resolveLivingGetScale();

    private DirectKeyframeAdapter() { }

    public static void install() {
        KeyframeEffectManager.installAdapter(INSTANCE);
    }

    @Override
    public boolean hasEffect(String canonicalId) {
        return DirectVfxEngine.INSTANCE.hasEffect(canonicalId);
    }

    @Override
    public @Nullable BoundHandle spawn(@Nullable ClientLevel level, String canonicalId,
                                       double x, double y, double z, long seed,
                                       KeyframeEffectParams params, boolean trackRenderSamples) {
        if (!DirectVfxEngine.INSTANCE.hasEffect(canonicalId)) {
            return null;
        }
        EffectHandle handle = DirectVfxEngine.INSTANCE.spawn(level, canonicalId, new Vec3(x, y, z), seed);
        if (!handle.isAlive()) {
            return null;
        }
        if (params.hasColor()) {
            handle.setColor(params.red(), params.green(), params.blue(), params.alpha());
        }
        return new DirectBoundHandle(handle);
    }

    @Override
    public boolean requiresLevel() {
        return false;
    }

    @Override
    public float entityScale(@Nullable Entity entity) {
        if (entity instanceof LivingEntity living) {
            if (LIVING_GET_SCALE != null) {
                try {
                    return (float) LIVING_GET_SCALE.invokeExact(living);
                } catch (Throwable ignored) { }
            }
            return living.isBaby() ? 0.5F : 1.0F;
        }
        return 1.0F;
    }

    private static @Nullable MethodHandle resolveLivingGetScale() {
        try {
            return MethodHandles.publicLookup().findVirtual(
                    LivingEntity.class, "getScale", MethodType.methodType(float.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class DirectBoundHandle implements BoundHandle {
        private final EffectHandle handle;

        private DirectBoundHandle(EffectHandle handle) {
            this.handle = handle;
        }

        @Override
        public Object rawHandle() {
            return handle;
        }

        @Override
        public boolean isAlive() {
            return handle.isAlive();
        }

        @Override
        public void updatePose(double x, double y, double z,
                               float qx, float qy, float qz, float qw,
                               float scale,
                               @Nullable double[] actorPosition,
                               @Nullable double[] actorVelocityCmPerSec) {
            float safeScale = Float.isFinite(scale) && scale > 0.0F ? scale : 1.0F;
            handle.setPose(new EffectHandle.Pose(new Vec3(x, y, z), qx, qy, qz, qw, safeScale));
        }

        @Override
        public void setBeamEnd(double x, double y, double z) {
            handle.setBeamEnd(new Vec3(x, y, z));
        }

        @Override
        public void close() {
            handle.close();
        }
    }
}
