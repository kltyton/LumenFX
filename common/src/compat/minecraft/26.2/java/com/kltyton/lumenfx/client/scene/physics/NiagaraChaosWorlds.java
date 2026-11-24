package com.kltyton.lumenfx.client.scene.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventDemand;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

public final class NiagaraChaosWorlds {
    private static final Map<ClientLevel, NiagaraChaosScene> SCENES = new ConcurrentHashMap<>();
    private static final Map<ClientLevel, Consumer<EventDemand>> PRODUCERS = new ConcurrentHashMap<>();

    private NiagaraChaosWorlds() { }

    public static void publish(ClientLevel level, @Nullable NiagaraChaosScene scene) {
        if (scene == null) SCENES.remove(level);
        else SCENES.put(level, scene);
    }

    public static NiagaraChaosScene snapshot(@Nullable ClientLevel level) {
        return level == null ? NiagaraChaosScene.EMPTY : SCENES.getOrDefault(level, NiagaraChaosScene.EMPTY);
    }

    public static void producer(ClientLevel level, @Nullable Consumer<EventDemand> enable) {
        if (enable == null) {
            PRODUCERS.remove(level);
            return;
        }
        Set<EventDemand> enabled = new HashSet<>();
        PRODUCERS.put(level, request -> {
            if (!enabled.add(request)) return;
            try { enable.accept(request); }
            catch (RuntimeException | Error failure) { enabled.remove(request); throw failure; }
        });
    }

    public static @Nullable Consumer<EventDemand> demand(@Nullable ClientLevel level) {
        return level == null ? null : PRODUCERS.get(level);
    }

    public static void remove(ClientLevel level) {
        PRODUCERS.remove(level);
        SCENES.remove(level);
    }

    public static void clear() {
        PRODUCERS.clear();
        SCENES.clear();
    }
}
