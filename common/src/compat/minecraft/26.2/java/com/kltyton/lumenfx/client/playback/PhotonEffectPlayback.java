package com.kltyton.lumenfx.client.playback;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.playback.WorldEffectPlayback.Scope;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Runs Photon assets with LumenFX world attachment and handle semantics. */
public final class PhotonEffectPlayback {
    private PhotonEffectPlayback() {
    }

    public static EffectHandle spawn(WorldEffectPlayback playback, ClientLevel level, Identifier id, Vec3 origin, long seed) {
        var minecraft = Minecraft.getInstance();
        var handle = new EffectHandle(origin);
        handle.setEffectId(id);
        int generation = FXHelper.generation();
        var scope = Scope.capture(level, generation);
        playback.reservePhoton(handle, scope);
        CompletableFuture<FX> preloaded;
        try {
            preloaded = FXHelper.preloadForPlayback(id, generation);
        } catch (RuntimeException | Error failure) {
            playback.takePhotonReservation(handle, scope);
            handle.invalidate();
            throw failure;
        }
        preloaded.whenCompleteAsync((effect, error) -> {
            if (!playback.takePhotonReservation(handle, scope)) return;
            if (!scope.current(minecraft.level, FXHelper.generation(), VanillaParticleHost.INSTANCE.generation())
                    || !handle.isAlive() || handle.stopping()) {
                handle.invalidate();
                return;
            }
            if (error != null || effect == null || FXHelper.getCachedFX(id) != effect) {
                handle.invalidate();
                if (error != null) Photon.LOGGER.error("Could not prepare effect {}", id, error);
                else Photon.LOGGER.error("Effect {} is unavailable after resource preparation", id);
                return;
            }
            WorldExecutor executor = null;
            try {
                var runtime = effect.createRuntime();
                executor = new WorldExecutor(scope, handle, runtime, RandomSource.create(seed));
                executor.applyPose(handle.inputWithoutNiagaraAttachment().pose());
                runtime.emit(executor);
                playback.addPhotonEffect(handle, scope, runtime, executor);
            } catch (RuntimeException | Error emitError) {
                handle.invalidate();
                try {
                    if (executor != null) executor.discard();
                } catch (RuntimeException | Error cleanupError) {
                    if (cleanupError != emitError) emitError.addSuppressed(cleanupError);
                }
                if (emitError instanceof Error errorDuringEmit) throw errorDuringEmit;
                Photon.LOGGER.error("Could not emit effect {}", id, emitError);
            }
        }, minecraft);
        return handle;
    }

    static final class WorldExecutor implements IEffectExecutor {
        private record SubEffect(FXRuntime runtime, long startTick) { }

        private final Scope scope;
        private final EffectHandle handle;
        private final FXRuntime runtime;
        private final RandomSource random;
        private final List<SubEffect> subEffects = new ArrayList<>();
        private final List<CompletableFuture<?>> subEffectLoads = new ArrayList<>();
        private final AttachmentSamples samples = new AttachmentSamples();
        private EffectHandle.@Nullable Pose appliedPose;
        private EffectHandle.@Nullable Input tickInput;
        private boolean hasTickSample = true;

        private WorldExecutor(Scope scope, EffectHandle handle, FXRuntime runtime, RandomSource random) {
            this.scope = scope;
            this.handle = handle;
            this.runtime = runtime;
            this.random = random;
        }

        @Override
        public ClientLevel getLevel() {
            return scope.level();
        }

        @Override
        public RandomSource getRandomSource() {
            return random;
        }

        @Override
        public void updateFXObjectTick(IFXObject object) {
            if (object == runtime.root) {
                tickInput = handle.inputWithoutNiagaraAttachment();
                hasTickSample = samples.permitsEmission(tickInput.sequence(), tickInput.renderTracked(),
                        VanillaParticleHost.INSTANCE.tickCount());
            }
            var input = tickInput;
            updateAttachment(object, input != null ? input : handle.inputWithoutNiagaraAttachment());
        }

        @Override
        public boolean canEmit(IFXObject object) {
            return handle.isAlive() && (!(object instanceof Emitter emitter) || emitter.getScene() != runtime || hasTickSample);
        }

        @Override public boolean acceptsSubEffects() {
            return handle.isAlive() && scope.current(Minecraft.getInstance().level,
                    FXHelper.generation(), VanillaParticleHost.INSTANCE.generation());
        }

        @Override public void trackSubEffectLoad(CompletableFuture<?> load) { subEffectLoads.add(load); }

        @Override public void trackSubEffect(FXRuntime child) {
            subEffects.add(new SubEffect(child, VanillaParticleHost.INSTANCE.tickCount()));
        }

        boolean advanceSubEffects(boolean release) {
            subEffectLoads.removeIf(CompletableFuture::isDone);
            long tick = VanillaParticleHost.INSTANCE.tickCount();
            subEffects.removeIf(effect -> {
                FXRuntime child = effect.runtime();
                if (child.isFinished()) return true;
                if (!child.isTracked()) {
                    child.destroy(true);
                    return true;
                }
                if (release && effect.startTick() < tick) {
                    child.destroy(false);
                    return child.isFinished();
                }
                return false;
            });
            return !subEffects.isEmpty() || !subEffectLoads.isEmpty();
        }

        void discard() {
            subEffectLoads.clear();
            Throwable failure = null;
            try {
                runtime.destroy(true);
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            for (var child : subEffects) {
                try {
                    child.runtime().destroy(true);
                } catch (RuntimeException | Error error) {
                    if (failure == null) failure = error;
                    else if (failure != error) failure.addSuppressed(error);
                }
            }
            subEffects.clear();
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }

        @Override
        public void updateFXObjectFrame(IFXObject object, float partialTicks) {
            updateAttachment(object, handle.inputWithoutNiagaraAttachment());
        }

        private void updateAttachment(IFXObject object, EffectHandle.Input input) {
            if (object == runtime.root) {
                applyPose(input.pose());
            } else if (object instanceof Emitter emitter) {
                emitter.setPlaybackColor(input.red(), input.green(), input.blue(), input.alpha());
                if (emitter instanceof BeamEmitter beam && beam.getScene() == runtime) {
                    beam.setWorldEndpoint(handle.beamEndOverride());
                }
            }
        }

        private void applyPose(EffectHandle.Pose pose) {
            if (pose.equals(appliedPose)) return;
            var root = runtime.root;
            if (appliedPose == null) {
                runtime.setWorldOrigin(FXRuntime.snapWorldOrigin(pose.position()));
            }
            root.updatePos(runtime.toLocalSpace(pose.position()));
            root.updateRotation(new Quaternionf(pose.x(), pose.y(), pose.z(), pose.w()));
            root.updateScale(new Vector3f(pose.scale(), pose.scaleY(), pose.scaleZ()));
            appliedPose = pose;
        }
    }
}
