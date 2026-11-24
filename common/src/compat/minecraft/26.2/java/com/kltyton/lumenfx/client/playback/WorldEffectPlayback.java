package com.kltyton.lumenfx.client.playback;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.playback.PhotonEffectPlayback.WorldExecutor;
import com.kltyton.lumenfx.client.simulation.EffectInstance;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.VanillaParticleHost;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Owns the world effect handles on the client thread. Each effect advances at its authored cadence. */
public final class WorldEffectPlayback {
    public record Scope(ClientLevel level, long resourceGeneration, int particleGeneration) {
        public static Scope capture(ClientLevel level, long resourceGeneration) {
            return new Scope(level, resourceGeneration, VanillaParticleHost.INSTANCE.generation());
        }

        public boolean current(@Nullable ClientLevel level, long resourceGeneration, int particleGeneration) {
            return this.level == level && this.resourceGeneration == resourceGeneration
                    && this.particleGeneration == particleGeneration;
        }
    }

    public sealed interface Active permits FrameEffect, PhotonEffect {
        EffectHandle handle();

        void discard();
    }

    public record FrameEffect(EffectInstance instance, long activation) implements Active {
        @Override
        public EffectHandle handle() {
            return instance.handle();
        }

        @Override
        public void discard() {
            instance.close();
        }
    }

    private record PhotonEffect(EffectHandle handle, Scope scope, FXRuntime runtime, WorldExecutor executor) implements Active {
        @Override
        public void discard() {
            executor.discard();
        }
    }

    private final Map<EffectHandle, Active> active = new LinkedHashMap<>();
    private final Collection<Active> ordered = Collections.unmodifiableCollection(active.values());
    private final Map<EffectHandle, Scope> pendingPhoton = new ConcurrentHashMap<>();
    private int frameEffectCount;
    private long frameActivation;

    public long frameActivation() {
        return frameActivation;
    }

    public boolean hasFrameEffects() {
        return frameEffectCount != 0;
    }

    public boolean anyLiveFrameEffect(Predicate<FrameEffect> predicate) {
        if (!hasFrameEffects()) return false;
        for (var value : ordered) {
            if (value instanceof FrameEffect effect && effect.handle().isAlive() && predicate.test(effect)) return true;
        }
        return false;
    }

    public Collection<Active> activeEffects() {
        return ordered;
    }

    public void addFrameEffect(EffectInstance instance) {
        add(new FrameEffect(instance, ++frameActivation));
        frameEffectCount++;
    }

    void reservePhoton(EffectHandle handle, Scope scope) {
        pendingPhoton.put(handle, scope);
    }

    boolean takePhotonReservation(EffectHandle handle, Scope scope) {
        return pendingPhoton.remove(handle, scope);
    }

    void addPhotonEffect(EffectHandle handle, Scope scope, FXRuntime runtime, WorldExecutor executor) {
        add(new PhotonEffect(handle, scope, runtime, executor));
    }

    private void add(Active effect) {
        if (active.putIfAbsent(effect.handle(), effect) != null) {
            throw new IllegalStateException("Effect handle is already playing");
        }
    }

    public void collectFrameEffects(List<EffectInstance> output, Consumer<EffectInstance> reclaim) {
        if (!hasFrameEffects()) return;
        EffectTeardown teardown = null;
        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            if (!(iterator.next() instanceof FrameEffect effect)) continue;
            if (effect.handle().isAlive()) {
                output.add(effect.instance());
            } else {
                iterator.remove();
                frameEffectCount--;
                if (teardown == null) teardown = new EffectTeardown();
                teardown.run(() -> reclaim.accept(effect.instance()));
            }
        }
        if (teardown != null) teardown.close();
    }

    public void afterParticleTick(ClientLevel level) {
        int resourceGeneration = FXHelper.generation();
        int particleGeneration = VanillaParticleHost.INSTANCE.generation();
        pendingPhoton.forEach((handle, scope) -> {
            if (handle.stopping() || !scope.current(level, resourceGeneration, particleGeneration))
                discardPendingPhoton(handle, scope);
        });
        if (active.size() == frameEffectCount) return;
        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            if (!(iterator.next() instanceof PhotonEffect effect)) continue;
            var runtime = effect.runtime();
            if (!effect.handle().isAlive() || !effect.scope().current(level, resourceGeneration, particleGeneration)
                    || !runtime.isTracked() && !runtime.isFinished()) {
                iterator.remove();
                effect.handle().invalidate();
                effect.discard();
                continue;
            }
            boolean stopping = effect.handle().stopping();
            if (stopping) runtime.destroy(false);
            boolean finished = runtime.isFinished();
            boolean subEffectsRemain = effect.executor().advanceSubEffects(stopping && finished);
            if (finished && !subEffectsRemain) {
                iterator.remove();
                effect.handle().invalidate();
            }
        }
    }

    public void discardFrameEffects() {
        discard(true, false);
    }

    public void discardPhotonEffects() {
        discard(false, true);
    }

    public void discardAll() {
        discard(true, true);
    }

    private void discardPendingPhoton(EffectHandle handle, Scope scope) {
        if (takePhotonReservation(handle, scope)) handle.invalidate();
    }

    private void discard(boolean frames, boolean photon) {
        if (photon) pendingPhoton.forEach(this::discardPendingPhoton);
        if ((!photon && frameEffectCount == 0) || (!frames && active.size() == frameEffectCount)) return;
        try (var teardown = new EffectTeardown()) {
            var iterator = active.values().iterator();
            while (iterator.hasNext()) {
                var effect = iterator.next();
                boolean frameEffect = effect instanceof FrameEffect;
                if (frameEffect ? !frames : !photon) continue;
                iterator.remove();
                if (frameEffect) frameEffectCount--;
                effect.handle().invalidate();
                teardown.run(effect::discard);
            }
        }
    }
}
