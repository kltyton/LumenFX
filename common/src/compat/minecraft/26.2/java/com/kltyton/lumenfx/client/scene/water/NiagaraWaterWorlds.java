package com.kltyton.lumenfx.client.scene.water;

import com.kltyton.lumenfx.api.client.water.NiagaraWaterScene;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

public final class NiagaraWaterWorlds {
    private static final Map<ClientLevel, NiagaraWaterScene> SCENES = new ConcurrentHashMap<>();

    private NiagaraWaterWorlds() { }

    public static void publish(ClientLevel level, @Nullable NiagaraWaterScene scene) {
        if (scene == null) SCENES.remove(level);
        else SCENES.put(level, scene);
    }

    public static NiagaraWaterScene snapshot(@Nullable ClientLevel level) {
        return level == null ? NiagaraWaterScene.EMPTY : SCENES.getOrDefault(level, NiagaraWaterScene.EMPTY);
    }

    public static void clear() { SCENES.clear(); }
}
