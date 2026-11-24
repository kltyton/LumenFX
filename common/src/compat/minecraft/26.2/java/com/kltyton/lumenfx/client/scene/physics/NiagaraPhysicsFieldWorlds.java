package com.kltyton.lumenfx.client.scene.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

public final class NiagaraPhysicsFieldWorlds {
    private static final Map<ClientLevel, NiagaraPhysicsField> FIELDS = new ConcurrentHashMap<>();

    private NiagaraPhysicsFieldWorlds() { }

    public static void publish(ClientLevel level, @Nullable NiagaraPhysicsField field) {
        if (field == null) FIELDS.remove(level);
        else FIELDS.put(level, field);
    }

    public static @Nullable NiagaraPhysicsField snapshot(@Nullable ClientLevel level) {
        return level == null ? null : FIELDS.get(level);
    }

    public static void clear() { FIELDS.clear(); }
}
