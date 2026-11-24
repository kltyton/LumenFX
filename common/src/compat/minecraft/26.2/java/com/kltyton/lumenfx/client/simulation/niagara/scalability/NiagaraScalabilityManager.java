package com.kltyton.lumenfx.client.simulation.niagara.scalability;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.simulation.EffectInstance;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Frequency;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.ProxyMode;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Ramp;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Reaction;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Resolved;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The owning world ranks components together by the original effect type. */
public final class NiagaraScalabilityManager {
    public enum CullingMode { Enabled, Paused, Disabled }

    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<Object, EffectInstance> cullProxies = new LinkedHashMap<>();
    private @Nullable NiagaraScalabilityView view;
    private CullingMode mode = CullingMode.Enabled;

    public void view(@Nullable NiagaraScalabilityView view) { this.view = view; }
    public boolean setMode(CullingMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (this.mode == mode) return false;
        this.mode = mode;
        if (mode != CullingMode.Disabled) groups.values().forEach(group -> group.forceRefresh = true);
        return true;
    }

    public <T> void update(Iterable<T> active, Function<T, @Nullable EffectInstance> instance, Vec3 camera, double time, boolean newOnly,
                           double renderFeedbackTime, boolean focused, float worstFxBudget, float delta,
                           Consumer<EffectInstance> registerCullProxy) {
        var platform = NiagaraPlatforms.snapshot();
        for (var group : groups.values()) group.beginFrame();
        for (T value : active) {
            var effect = instance.apply(value);
            if (effect == null) continue;
            var system = effect.definition().niagara();
            if (system == null || system.scalability() == null || !effect.handle().isAlive() || effect.isSharedCullProxy()) continue;
            var policy = system.scalability();
            String key = SourceNames.canonical(policy.effectType());
            var group = groups.get(key);
            if (mode == CullingMode.Disabled && (group == null || !group.entries.containsKey(effect))) continue;
            if (group == null) group = groups.computeIfAbsent(key, ignored -> new Group(policy));
            group.register(effect, system, platform, time);
        }
        var iterator = groups.values().iterator();
        while (iterator.hasNext()) {
            var group = iterator.next();
            group.removeExpired();
            if (group.entries.isEmpty()) iterator.remove();
            else if (mode != CullingMode.Disabled) group.update(camera, view, time, newOnly, platform, renderFeedbackTime, focused, worstFxBudget,
                    delta, mode == CullingMode.Enabled);
        }
        updateCullProxies(platform, registerCullProxy);
    }

    private void updateCullProxies(NiagaraPlatformEnvironment platform, Consumer<EffectInstance> register) {
        if (integer(platform, "fx.Niagara.AllowCullProxies") == 0) {
            retireCullProxies();
            return;
        }
        Map<Object, List<Entry>> selection = new LinkedHashMap<>();
        for (var group : groups.values()) for (var entry : group.entries.values()) {
            if (entry.managed && entry.proxySelected && entry.effect.scalabilitySuspended() && entry.effect.handle().isAlive()
                    && Objects.requireNonNull(entry.settings).proxy().mode() == ProxyMode.Instanced_Rendered)
                selection.computeIfAbsent(entry.systemKey, ignored -> new ArrayList<>()).add(entry);
        }
        var iterator = cullProxies.entrySet().iterator();
        while (iterator.hasNext()) {
            var proxy = iterator.next().getValue();
            var settings = Objects.requireNonNull(Objects.requireNonNull(proxy.definition().niagara()).scalability()).resolve(platform);
            if (!proxy.handle().isAlive() || settings.proxy().mode() != ProxyMode.Instanced_Rendered) {
                retireCullProxy(proxy);
                iterator.remove();
            }
        }
        selection.forEach((key, entries) -> cullProxies.computeIfAbsent(key, ignored -> {
            var first = entries.stream().min(Comparator.comparingInt(entry -> entry.proxyOrder)).orElseThrow();
            var proxy = first.effect.createCullProxy(Objects.requireNonNull(first.settings).distance().limit());
            register.accept(proxy);
            return proxy;
        }));
        cullProxies.forEach((key, proxy) -> {
            var entries = selection.get(key);
            proxy.cullProxyTargets(entries == null ? List.of() : entries.stream().map(entry -> entry.effect).toList());
        });
    }

    private static void retireCullProxy(EffectInstance proxy) {
        proxy.cullProxyTargets(List.of());
        proxy.handle().invalidate();
    }

    private void retireCullProxies() {
        cullProxies.values().forEach(NiagaraScalabilityManager::retireCullProxy);
        cullProxies.clear();
    }

    public void clear() {
        retireCullProxies();
        groups.clear();
        view = null;
    }

    private static final class Group {
        final NiagaraSystemScalability policy;
        final Map<EffectInstance, Entry> entries = new LinkedHashMap<>();
        final List<Entry> ranked = new ArrayList<>();
        final LinkedHashSet<Entry> pendingPeriodic = new LinkedHashSet<>();
        double lastUpdate;
        boolean updating;
        boolean forceRefresh;
        @Nullable NiagaraPlatformEnvironment previousPlatform;

        Group(NiagaraSystemScalability policy) { this.policy = policy; }

        void beginFrame() {
            for (var entry : entries.values()) {
                entry.present = false;
                entry.evaluateNow = false;
            }
        }

        void register(EffectInstance effect, NiagaraDefinition system, NiagaraPlatformEnvironment platform, double time) {
            boolean localPlayerProtected = integer(platform, "fx.Niagara.Scalability.CanPreventCullingOnPlayerFX") != 0
                    && effect.handle().niagaraLocalPlayerEffect() && !system.scalability().allowLocalPlayerCulling();
            var entry = entries.computeIfAbsent(effect, ignored -> new Entry(effect, system));
            entry.present = true;
            boolean managed = !effect.handle().stopping() && effect.allowsScalability() && !localPlayerProtected && system.platforms().active(platform);
            if (entry.managed && !managed) {
                effect.applyScalabilityCull(null);
                effect.scalabilityView(Float.NaN, Float.MAX_VALUE, -1);
                entry.significanceIndex = -1;
                entry.proxySelected = false;
            } else if (managed && (!entry.managed || entry.activation != effect.componentActivation())) {
                entry.newlyRegistered = true;
                entry.lastInsideView = time;
                entry.independentCull = false;
                entry.visibilityCull = false;
                entry.proxySelected = false;
            }
            entry.activation = effect.componentActivation();
            entry.managed = managed;
            if (previousPlatform != platform || entry.settings == null) entry.settings = system.scalability().resolve(platform);
        }

        void removeExpired() {
            entries.values().removeIf(entry -> !entry.present || !entry.effect.handle().isAlive());
            pendingPeriodic.removeIf(entry -> !entry.present || !entry.managed || !entry.effect.handle().isAlive());
        }

        void update(Vec3 camera, @Nullable NiagaraScalabilityView view, double time, boolean newOnly, NiagaraPlatformEnvironment platform,
                    double renderFeedbackTime, boolean focused, float worstFxBudget, float delta, boolean cullingEnabled) {
            boolean applyPeriodic = !newOnly && updatePeriodic(time, delta, platform);
            boolean hasNew = entries.values().stream().anyMatch(entry -> entry.managed && entry.newlyRegistered);
            boolean distanceCulling = cullingEnabled && integer(platform, "fx.Niagara.Scalability.DistanceCulling") != 0;
            boolean countCulling = cullingEnabled && integer(platform, "fx.Niagara.Scalability.InstanceCountCulling") != 0;
            boolean visibilityCulling = cullingEnabled && integer(platform, "fx.Niagara.Scalability.VisibilityCulling") != 0;
            boolean dynamicBoundsCulling = integer(platform, "fx.Niagara.AllowVisibilityCullingForDynamicBounds") != 0;
            boolean budgetCulling = cullingEnabled && NiagaraFxBudget.enabled(platform)
                    && integer(platform, "fx.Niagara.Scalability.GlobalBudgetCulling") != 0;
            boolean rigidCounts = cullingEnabled && integer(platform, "fx.Niagara.Scalability.ApplyInstanceCountsRigidly") != 0;
            var actual = new Counts();
            for (var entry : entries.values()) {
                if (!entry.newlyRegistered && entry.effect.countsForScalability()) actual.add(entry, 1);
            }
            for (var entry : entries.values()) {
                if (!entry.managed) continue;
                boolean evaluate = entry.evaluateNow || entry.newlyRegistered;
                var settings = Objects.requireNonNull(entry.settings);
                if (evaluate) {
                    entry.distance = distance(camera, entry.effect);
                    var budget = settings.budget();
                    float maximumDistance = settings.distance().limit();
                    boolean distanceCull = distanceCulling && settings.distance().enabled()
                            && (entry.distance > maximumDistance || budgetCulling && budget.distanceScale() != null
                            && entry.distance > maximumDistance * budget.distanceScale().evaluate(worstFxBudget));
                    boolean fixedBounds = entry.system.systemFixedBoundsEnabled();
                    boolean outsideFrustum = visibilityCulling && (dynamicBoundsCulling || fixedBounds)
                            && outsideFrustum(entry, settings, view, time);
                    boolean notRendered = visibilityCulling && (dynamicBoundsCulling || fixedBounds)
                            && notRendered(entry, settings, renderFeedbackTime, focused);
                    boolean overBudget = budgetCulling && budget.cull() && worstFxBudget >= budget.maxUsage();
                    entry.visibilityCull = outsideFrustum || notRendered;
                    entry.independentCull = distanceCull || entry.visibilityCull || overBudget;
                    boolean preCullByFrustum = outsideFrustum && fixedBounds && settings.visibility().allowFrustumPrecull();
                    boolean hardCount = countCulling && policy.significanceClass() == null
                            && exceedsCounts(settings, actual.type, actual.system(entry), budgetCulling, worstFxBudget);
                    if (entry.newlyRegistered && !entry.effect.hasNiagaraVisibilityProxy() && policy.reaction().canPreCull()
                            && (distanceCull || preCullByFrustum || hardCount || overBudget)) {
                        entry.effect.applyScalabilityCull(Reaction.DeactivateImmediate);
                        continue;
                    }
                }
                if (entry.newlyRegistered && entry.effect.countsForScalability()) actual.add(entry, 1);
                if (!newOnly || entry.newlyRegistered)
                    entry.effect.scalabilityView(evaluate ? entry.distance : Float.NaN, settings.distance().limit(), entry.significanceIndex);
            }
            if (!applyPeriodic && !hasNew) return;

            ranked.clear();
            for (var entry : entries.values()) {
                if (!entry.managed || !entry.effect.handle().isAlive()) continue;
                var settings = Objects.requireNonNull(entry.settings);
                boolean needsSignificance = usesSignificance(settings) || entry.system.scalability().scriptReadsSignificance();
                entry.desiredCull = entry.independentCull;
                entry.proxySelected = false;
                if (needsSignificance && (isDistanceHandler() || isAgeHandler())) ranked.add(entry);
            }
            ranked.sort(Comparator.comparingDouble(this::significance).reversed());
            var selected = new Counts();
            Map<Object, Integer> proxyCounts = new HashMap<>();
            int proxyOrder = 0;
            for (var entry : ranked) {
                var settings = Objects.requireNonNull(entry.settings);
                int systemCount = selected.system(entry);
                boolean culled = entry.independentCull || countCulling
                        && exceedsCounts(settings, selected.type, systemCount, budgetCulling, worstFxBudget);
                if (!culled) selected.add(entry, 1);
                entry.desiredCull = culled;
                if (culled && !entry.visibilityCull && settings.proxy().mode() == ProxyMode.Instanced_Rendered
                        && proxyCounts.getOrDefault(entry.systemKey, 0) < settings.proxy().maximum()) {
                    entry.proxySelected = true;
                    entry.proxyOrder = proxyOrder++;
                    proxyCounts.merge(entry.systemKey, 1, Integer::sum);
                }
                entry.significanceIndex = culled ? -1 : systemCount;
                entry.effect.scalabilitySignificance(entry.significanceIndex);
            }
            if (rigidCounts) for (var entry : ranked) {
                if (applyPeriodic || entry.newlyRegistered) apply(entry, actual, true, budgetCulling, worstFxBudget);
            }
            for (var entry : entries.values()) {
                if (entry.managed && entry.effect.handle().isAlive() && (applyPeriodic || entry.newlyRegistered))
                    apply(entry, actual, rigidCounts, budgetCulling, worstFxBudget);
            }
            for (var entry : entries.values()) entry.newlyRegistered = false;
        }

        private void apply(Entry entry, Counts actual, boolean rigidCounts, boolean budgetCulling, float usage) {
            boolean wasRunning = entry.effect.countsForScalability();
            if (!entry.desiredCull && entry.effect.scalabilitySuspended() && rigidCounts && !wasRunning) {
                var settings = Objects.requireNonNull(entry.settings);
                int effectMaximum = settings.effectInstances().limit(), systemMaximum = settings.systemInstances().limit();
                if (budgetCulling) {
                    effectMaximum = scaleCount(effectMaximum, settings.budget().effectInstancesScale(), usage);
                    systemMaximum = scaleCount(systemMaximum, settings.budget().systemInstancesScale(), usage);
                }
                if (settings.effectInstances().enabled() && actual.type >= effectMaximum
                        && settings.systemInstances().enabled() && actual.system(entry) >= systemMaximum) return;
            }
            var reaction = policy.reaction();
            if (Objects.requireNonNull(entry.settings).proxy().mode() == ProxyMode.Instanced_Rendered) {
                if (reaction == Reaction.Deactivate) reaction = Reaction.DeactivateImmediate;
                else if (reaction == Reaction.DeactivateResume) reaction = Reaction.DeactivateImmediateResume;
            }
            entry.effect.applyScalabilityCull(entry.desiredCull ? reaction : null);
            boolean running = entry.effect.countsForScalability();
            if (wasRunning != running) actual.add(entry, running ? 1 : -1);
        }

        private boolean updatePeriodic(double time, float delta, NiagaraPlatformEnvironment platform) {
            boolean platformChanged = previousPlatform != null && previousPlatform != platform;
            previousPlatform = platform;
            if (policy.frequency() == Frequency.SpawnOnly && !platformChanged) return false;
            float period = updatePeriod(policy.frequency(), platform);
            boolean all = forceRefresh || platformChanged || policy.frequency() == Frequency.Continuous;
            forceRefresh = false;
            if (all || !updating && time >= lastUpdate + period) {
                lastUpdate = time;
                updating = true;
                pendingPeriodic.clear();
                for (var entry : entries.values()) if (entry.managed) pendingPeriodic.add(entry);
            }
            if (!updating) return false;
            int remaining = pendingPeriodic.size();
            int capacity = integer(platform, "fx.ScalabilityMaxUpdatesPerFrame");
            int count = remaining;
            if (!all && capacity > 0) {
                if (period > 1.0e-8f && delta < period) count = Math.min(count, (int)Math.ceil(remaining * delta / period));
                count = Math.min(count, capacity);
            }
            var iterator = pendingPeriodic.iterator();
            for (int index = 0; index < count && iterator.hasNext(); index++) {
                iterator.next().evaluateNow = true;
                iterator.remove();
            }
            if (!pendingPeriodic.isEmpty()) return false;
            updating = false;
            return true;
        }

        private boolean isAgeHandler() {
            String handler = policy.significanceClass();
            return handler != null && SourceNames.equal(handler, "/Script/Niagara.NiagaraSignificanceHandlerAge");
        }

        private boolean isDistanceHandler() {
            String handler = policy.significanceClass();
            return handler != null && SourceNames.equal(handler, "/Script/Niagara.NiagaraSignificanceHandlerDistance");
        }

        private double significance(Entry entry) {
            if (entry.independentCull && (isAgeHandler()
                    || Objects.requireNonNull(entry.settings).proxy().mode() == ProxyMode.None)) return 0;
            return 1.0 / (isAgeHandler() ? entry.effect.scalabilityAge() : entry.distance);
        }
    }

    private static final class Entry {
        final EffectInstance effect;
        final NiagaraDefinition system;
        final Object systemKey;
        @Nullable Resolved settings;
        boolean managed, present, newlyRegistered, independentCull, visibilityCull, evaluateNow, desiredCull, proxySelected;
        float distance;
        double lastInsideView;
        long activation;
        int significanceIndex = -1;
        int proxyOrder;

        Entry(EffectInstance effect, NiagaraDefinition system) {
            this.effect = effect;
            this.system = system;
            systemKey = system.sourceSystem() == null ? effect.definition() : SourceNames.canonical(system.sourceSystem());
        }
    }

    private static final class Counts {
        final Map<Object, Integer> bySystem = new HashMap<>();
        int type;

        int system(Entry entry) { return bySystem.getOrDefault(entry.systemKey, 0); }
        void add(Entry entry, int delta) {
            type += delta;
            bySystem.merge(entry.systemKey, delta, Integer::sum);
        }
    }

    private static boolean outsideFrustum(Entry entry, Resolved settings, @Nullable NiagaraScalabilityView view, double time) {
        if (!settings.visibility().outsideFrustum()) return false;
        if (view == null || view.intersectsWorldBounds(entry.effect.scalabilityWorldBounds(entry.effect.handle().input().pose()))) {
            entry.lastInsideView = time;
            return false;
        }
        return time - entry.lastInsideView > settings.visibility().maxTimeOutsideFrustum();
    }

    private static boolean notRendered(Entry entry, Resolved settings, double feedbackTime, boolean focused) {
        if (Double.isNaN(feedbackTime)) return false;
        if (!entry.effect.hasNiagaraVisibilityProxy()) {
            entry.effect.markNiagaraRendered(feedbackTime);
            return false;
        }
        return focused && settings.visibility().notRendered()
                && entry.effect.timeSinceNiagaraRendered(feedbackTime) > settings.visibility().maxTimeWithoutRender();
    }

    private static boolean usesSignificance(Resolved settings) {
        return settings.effectInstances().enabled() || settings.systemInstances().enabled()
                || settings.proxy().mode() != ProxyMode.None;
    }

    private static boolean exceedsCounts(Resolved settings, int effect, int system, boolean budgetEnabled, float usage) {
        int effectMaximum = settings.effectInstances().limit(), systemMaximum = settings.systemInstances().limit();
        if (settings.effectInstances().enabled() && effect >= effectMaximum
                || settings.systemInstances().enabled() && system >= systemMaximum) return true;
        if (!budgetEnabled) return false;
        return settings.effectInstances().enabled() && effect >= scaleCount(effectMaximum, settings.budget().effectInstancesScale(), usage)
                || settings.systemInstances().enabled() && system >= scaleCount(systemMaximum, settings.budget().systemInstancesScale(), usage);
    }

    private static int scaleCount(int count, @Nullable Ramp ramp, float usage) {
        return ramp == null ? count : (int)(count * ramp.evaluate(usage));
    }

    private static float distance(Vec3 camera, EffectInstance effect) {
        return (float)(camera.distanceTo(effect.handle().input().pose().position()) * 100);
    }

    private static float updatePeriod(Frequency frequency, NiagaraPlatformEnvironment platform) {
        return switch (frequency) {
            case SpawnOnly -> Float.POSITIVE_INFINITY;
            case Continuous -> 0;
            case Low, Medium, High -> Float.intBitsToFloat(integer(platform, "fx.NiagaraScalabilityUpdateTime_" + frequency.name()));
        };
    }

    private static int integer(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name)).bits();
    }
}
