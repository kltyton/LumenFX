package com.kltyton.lumenfx.client.component.lifecycle;

import com.kltyton.lumenfx.api.client.component.ComponentOwner;
import com.kltyton.lumenfx.api.client.component.EffectComponent;
import com.kltyton.lumenfx.client.playback.EffectTeardown;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Owns component membership and world-clock lifetime delegates without creating gameplay entities. */
final class ClientComponentOwner implements ComponentOwner {
    private record Lifetime(double deadline, Runnable expired) { }
    private final ComponentWorlds.World world;
    private final Supplier<Set<UUID>> viewActors;
    private final Set<EffectComponent> components = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<EffectComponent, Lifetime> lifetimes = new IdentityHashMap<>();
    private boolean alive = true;

    ClientComponentOwner(ComponentWorlds.World world, Supplier<Set<UUID>> viewActors) {
        this.world = world;
        this.viewActors = viewActors;
    }
    @Override public boolean alive() { return alive; }
    @Override public double time() { return world.time; }
    @Override public Set<UUID> viewActors() { return viewActors.get(); }
    @Override public void attach(EffectComponent component) {
        if (!alive) throw new IllegalStateException("Component owner was destroyed");
        components.add(java.util.Objects.requireNonNull(component));
    }
    void destroyComponent(EffectComponent component) {
        lifetimes.remove(component);
        if (components.remove(component) && component.alive()) component.close();
    }
    @Override public void setLifeSpan(EffectComponent component, float seconds, Runnable expired) {
        if (!alive) throw new IllegalStateException("Component owner was destroyed");
        if (seconds > 0) lifetimes.put(component, new Lifetime(time() + seconds, java.util.Objects.requireNonNull(expired)));
        else lifetimes.remove(component);
    }
    void advance() {
        components.removeIf(component -> !component.alive());
        var due = new ArrayList<Map.Entry<EffectComponent, Lifetime>>();
        var iterator = lifetimes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getKey().alive()) iterator.remove();
            else if (entry.getValue().deadline() <= time()) {
                due.add(Map.entry(entry.getKey(), entry.getValue())); iterator.remove();
            }
        }
        if (due.isEmpty()) return;
        try (var teardown = new EffectTeardown()) {
            for (var entry : due) if (alive && entry.getKey().alive()) teardown.run(entry.getValue().expired());
        }
    }
    @Override public void destroy() {
        if (!alive) return;
        alive = false;
        lifetimes.clear();
        world.owners.remove(this);
        try (var teardown = new EffectTeardown()) {
            for (var component : List.copyOf(components)) teardown.run(() -> destroyComponent(component));
        }
    }
}
