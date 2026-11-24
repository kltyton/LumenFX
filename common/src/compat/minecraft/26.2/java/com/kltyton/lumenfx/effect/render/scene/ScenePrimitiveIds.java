package com.kltyton.lumenfx.effect.render.scene;

/** Scene proxy sources share one identity space across Niagara and component renderers. */
public final class ScenePrimitiveIds {
    private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong();
    private ScenePrimitiveIds() { }
    public static long next() { return NEXT.getAndIncrement(); }
}
