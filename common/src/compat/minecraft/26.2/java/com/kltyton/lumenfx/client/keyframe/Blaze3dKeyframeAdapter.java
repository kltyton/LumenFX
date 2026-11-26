package com.kltyton.lumenfx.client.keyframe;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.2 Blaze3D playback adapter connecting {@link KeyframeEffectManager} to {@link VfxEffects}.
 */
public final class Blaze3dKeyframeAdapter implements KeyframeEffectPlaybackAdapter {
    public static final Blaze3dKeyframeAdapter INSTANCE = new Blaze3dKeyframeAdapter();

    private Blaze3dKeyframeAdapter() { }

    public static void install() {
        KeyframeEffectManager.installAdapter(INSTANCE);
    }

    @Override
    public boolean hasEffect(String canonicalId) {
        Identifier id = Identifier.tryParse(canonicalId);
        return id != null && VfxEffects.hasEffect(id);
    }

    @Override
    public @Nullable BoundHandle spawn(@Nullable ClientLevel level, String canonicalId,
                                       double x, double y, double z, long seed,
                                       KeyframeEffectParams params, boolean trackRenderSamples) {
        if (level == null) return null;
        Identifier id = Identifier.tryParse(canonicalId);
        if (id == null || !VfxEffects.hasEffect(id)) {
            return null;
        }
        EffectHandle handle = VfxEffects.spawn(level, id, new Vec3(x, y, z), seed);
        handle.setNiagaraRandomSeedOffset(Long.hashCode(seed));
        if (trackRenderSamples) {
            handle.trackRenderSamples();
        }
        if (params.hasColor()) {
            handle.setColor(params.red(), params.green(), params.blue(), params.alpha());
        }
        return new Blaze3dBoundHandle(handle);
    }

    @Override
    public float entityScale(@Nullable Entity entity) {
        return entity instanceof LivingEntity living ? living.getScale() : 1.0F;
    }

    private static final class Blaze3dBoundHandle implements BoundHandle {
        private final EffectHandle handle;

        private Blaze3dBoundHandle(EffectHandle handle) {
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
            EffectHandle.Pose pose = new EffectHandle.Pose(new Vec3(x, y, z), qx, qy, qz, qw, safeScale);
            if (actorPosition != null) {
                Vec3 actorPos = new Vec3(actorPosition[0], actorPosition[1], actorPosition[2]);
                Velocity velocity = actorVelocityCmPerSec != null
                        ? new Velocity(actorVelocityCmPerSec[0], actorVelocityCmPerSec[1], actorVelocityCmPerSec[2])
                        : null;
                handle.setPoseAndActorPosition(pose, actorPos, velocity);
            } else {
                handle.setPose(pose);
            }
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
