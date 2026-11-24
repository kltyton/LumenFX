package com.kltyton.lumenfx.client.simulation.niagara.component;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.playback.EffectTeardown;
import com.kltyton.lumenfx.client.simulation.EffectInstance;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraComponentPoolSettings;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.ConsoleType;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Owner-thread pool, scoped to the current world and the published effect definitions. */
public final class NiagaraComponentPool {
    private static final String ENABLE = "FX.NiagaraComponentPool.Enable";
    private static final String CLEAN_TIME = "FX.NiagaraComponentPool.CleanTime";
    private static final String KILL_UNUSED_TIME = "FX.NiagaraComponentPool.KillUnusedTime";
    private static final String ALLOW_PRIME = "fx.Niagara.AllowPrimedPools";

    private record Idle(EffectInstance component, double releasedAt) { }
    private final Map<EffectDefinition, ArrayDeque<Idle>> free = new IdentityHashMap<>();
    private boolean enabled = true;
    private boolean allowPrime = true;
    private double cleanTime = 30, killTime = 180, lastClean;

    /** Returns true when the current published resources should be considered for priming. */
    public boolean update(NiagaraPlatformEnvironment platform) {
        boolean newEnabled = value(platform, ENABLE, 1) != 0;
        boolean newPrime = value(platform, ALLOW_PRIME, 1) != 0;
        boolean primeNow = newEnabled && newPrime && (!enabled || !allowPrime);
        enabled = newEnabled;
        allowPrime = newPrime;
        // The native CVar references are used directly in the cleanup interval and
        // cutoff expressions, including finite negative values supplied by the host.
        cleanTime = value(platform, CLEAN_TIME, 30);
        killTime = value(platform, KILL_UNUSED_TIME, 180);
        if (!enabled && !free.isEmpty()) clear();
        return primeNow;
    }

    public boolean canPool(NiagaraComponentPoolSettings settings) { return enabled && settings.maximum() > 0; }

    public @Nullable EffectInstance acquire(EffectDefinition definition, EffectHandle handle, long seed, double worldTime) {
        if (!enabled) return null;
        var idle = free.get(definition);
        if (idle == null || idle.isEmpty()) return null;
        var component = idle.removeLast().component();
        try {
            component.reusePooledComponent(handle, seed, worldTime);
            return component;
        } catch (RuntimeException | Error failure) {
            try { component.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void reclaim(EffectInstance component, double worldTime) {
        var definition = component.definition();
        if (!component.poolReturnRequested() || definition.niagara() == null || !canPool(definition.niagara().componentPool())) {
            component.close();
            return;
        }

        try (var teardown = new EffectTeardown()) {
            teardown.run(() -> clean(worldTime));
            var idle = free.computeIfAbsent(definition, ignored -> new ArrayDeque<>());
            if (idle.size() >= definition.niagara().componentPool().maximum()) {
                teardown.run(component::close);
                return;
            }
            teardown.run(() -> {
                component.parkForPool();
                idle.addLast(new Idle(component, worldTime));
            });
        }
    }

    public void prime(EffectDefinition definition, Supplier<EffectInstance> factory, double worldTime) {
        if (!enabled || !allowPrime || definition.niagara() == null) return;
        int count = definition.niagara().componentPool().primedMinimum();
        if (count == 0) return;
        var idle = free.computeIfAbsent(definition, ignored -> new ArrayDeque<>());
        while (idle.size() < count) {
            var component = factory.get();
            try {
                component.parkPrimedComponent();
                idle.addLast(new Idle(component, worldTime));
            } catch (RuntimeException | Error failure) {
                try { component.close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    private void clean(double now) {
        if (now - lastClean <= cleanTime) return;
        lastClean = now;
        double threshold = now - killTime;
        try (var teardown = new EffectTeardown()) {
            for (var entry : free.entrySet()) {
                int retained = allowPrime ? entry.getKey().niagara().componentPool().primedMinimum() : 0;
                var idle = entry.getValue();
                while (idle.size() > retained && idle.peekFirst().releasedAt() < threshold)
                    teardown.run(idle.removeFirst().component()::close);
            }
        }
    }

    public void clear() {
        try (var teardown = new EffectTeardown()) {
            for (var idle : free.values()) for (var item : idle) teardown.run(item.component()::close);
            free.clear();
            lastClean = 0;
        }
    }

    private static double value(NiagaraPlatformEnvironment platform, String name, double fallback) {
        var value = platform.console(name);
        if (value == null) return fallback;
        double number = value.type() == ConsoleType.FLOAT ? Float.intBitsToFloat(value.bits()) : value.bits();
        return Double.isFinite(number) ? number : fallback;
    }
}
