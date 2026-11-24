package com.kltyton.lumenfx.api.client;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Snapshot delivered on the owning client thread after a Unity particle simulation frame. */
public record UnityParticleTriggerEvent(String emitter, Type type, double time, Vec3 position,
                                        Vec3 velocity, double normalizedAge, List<String> colliders) {
    public enum Type { INSIDE, OUTSIDE, ENTER, EXIT }
    public UnityParticleTriggerEvent { colliders = List.copyOf(colliders); }
}
