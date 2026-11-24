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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** World time and transient component owners advance once at the existing scene-frame capture boundary. */
public final class ComponentWorlds {
    static final class World {
        double time;
        final Set<ClientComponentOwner> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        void close(EffectTeardown teardown) {
            for (var owner : List.copyOf(owners)) teardown.run(owner::destroy);
            owners.clear();
        }
    }
    private static final Map<ClientLevel, World> WORLDS = new IdentityHashMap<>();
    private ComponentWorlds() { }
    public static ComponentOwner createOwner(ClientLevel level) {
        return createOwner(level, Set::of);
    }
    public static ComponentOwner createOwner(ClientLevel level, Supplier<Set<UUID>> viewActors) {
        requireClientThread();
        var world = WORLDS.computeIfAbsent(level, ignored -> new World());
        var owner = new ClientComponentOwner(world, viewActors); world.owners.add(owner);
        return owner;
    }
    public static void destroyComponent(ComponentOwner owner, EffectComponent component) {
        requireClientThread();
        if (!(owner instanceof ClientComponentOwner clientOwner))
            throw new IllegalArgumentException("Component is not owned by the client component world");
        clientOwner.destroyComponent(component);
    }
    public static boolean hasOwners(ClientLevel level) {
        requireClientThread();
        var world = WORLDS.get(level);
        return world != null && !world.owners.isEmpty();
    }
    public static void advance(ClientLevel level, double time) {
        requireClientThread();
        var world = WORLDS.computeIfAbsent(level, ignored -> new World()); world.time = time;
        if (world.owners.isEmpty()) return;
        try (var teardown = new EffectTeardown()) {
            for (var owner : List.copyOf(world.owners)) if (owner.alive()) teardown.run(owner::advance);
        }
    }
    public static void retainWorld(ClientLevel level) {
        requireClientThread();
        var retired = new ArrayList<World>();
        var iterator = WORLDS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey() != level) {
                retired.add(entry.getValue());
                iterator.remove();
            }
        }
        close(retired);
    }
    public static void clear() {
        requireClientThread();
        var retired = List.copyOf(WORLDS.values());
        WORLDS.clear();
        close(retired);
    }
    private static void close(List<World> worlds) {
        try (var teardown = new EffectTeardown()) {
            for (var world : worlds) world.close(teardown);
        }
    }
    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Component ownership requires the client thread");
    }
}
