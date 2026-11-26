package com.kltyton.lumenfx.api.client.keyframe;

import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;

/**
 * Context passed to registered {@link LumenFxKeyframeBridge.Filter} callbacks before a GeckoLib / KBR
 * animation particle keyframe spawns or stops a LumenFX effect.
 */
public final class KeyframeEffectRequest {
    private final Object host;
    private final @Nullable Entity entity;
    private final String controllerName;
    private final String rawEffectId;
    private String resolvedEffectId;
    private final @Nullable String locator;
    private KeyframeEffectParams params;
    private final boolean explicitLumenFx;
    private boolean cancelled;

    public KeyframeEffectRequest(Object host, @Nullable Entity entity, String controllerName,
                                 String rawEffectId, String resolvedEffectId, @Nullable String locator,
                                 KeyframeEffectParams params, boolean explicitLumenFx) {
        this.host = host;
        this.entity = entity;
        this.controllerName = controllerName == null ? "" : controllerName;
        this.rawEffectId = rawEffectId;
        this.resolvedEffectId = resolvedEffectId;
        this.locator = locator != null && !locator.isBlank() ? locator : null;
        this.params = params;
        this.explicitLumenFx = explicitLumenFx;
    }

    public Object host() { return host; }
    public @Nullable Entity entity() { return entity; }
    public String controllerName() { return controllerName; }
    public String rawEffectId() { return rawEffectId; }
    public String resolvedEffectId() { return resolvedEffectId; }
    public void setResolvedEffectId(String resolvedEffectId) { this.resolvedEffectId = resolvedEffectId; }
    public @Nullable String locator() { return locator; }
    public KeyframeEffectParams params() { return params; }
    public void setParams(KeyframeEffectParams params) { this.params = params; }
    public boolean explicitLumenFx() { return explicitLumenFx; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }
}
