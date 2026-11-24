package com.kltyton.lumenfx.api.client.component;

import java.util.Set;
import java.util.UUID;

/** Transient source-actor ownership shared by one component renderer's pooled scene objects. */
public interface ComponentOwner {
    boolean alive();
    double time();
    default Set<UUID> viewActors() { return Set.of(); }
    void attach(EffectComponent component);
    void setLifeSpan(EffectComponent component, float seconds, Runnable expired);
    void destroy();
}
