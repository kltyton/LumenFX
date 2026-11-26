package com.kltyton.lumenfx.api.client.keyframe;

import com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Public entry point for integrating LumenFX with GeckoLib (4.x / 5.x) and KBR animation particle keyframes.
 *
 * <p>By default, LumenFX automatically observes particle keyframes whose effect ID matches a loaded LumenFX
 * effect (or carries an explicit {@code lumenfx:} prefix such as {@code lumenfx:my_mod:slash}), leaving all
 * KBR Bedrock/SnowStorm/3D particles and custom handlers untouched. Mod developers can customize routing,
 * override parameters, observe spawned handles, or trigger/stop keyframe effects manually through this API.
 */
public final class LumenFxKeyframeBridge {
    @FunctionalInterface
    public interface Filter {
        /** Return {@code false} (or call {@link KeyframeEffectRequest#cancel()}) to prevent LumenFX from handling this keyframe. */
        boolean allow(KeyframeEffectRequest request);
    }

    @FunctionalInterface
    public interface SpawnListener {
        /** Invoked immediately after a keyframe spawns a LumenFX {@code EffectHandle}. */
        void onSpawn(KeyframeEffectRequest request, Object effectHandle);
    }

    public record ParsedEffectId(String canonicalId, boolean explicitLumenFx) { }

    private LumenFxKeyframeBridge() { }

    public static boolean isAutoInterceptEnabled() {
        return KeyframeEffectManager.isAutoInterceptEnabled();
    }

    public static void setAutoInterceptEnabled(boolean enabled) {
        KeyframeEffectManager.setAutoInterceptEnabled(enabled);
    }

    public static void addFilter(Filter filter) {
        KeyframeEffectManager.addFilter(Objects.requireNonNull(filter, "filter"));
    }

    public static void removeFilter(Filter filter) {
        KeyframeEffectManager.removeFilter(filter);
    }

    public static void addSpawnListener(SpawnListener listener) {
        KeyframeEffectManager.addSpawnListener(Objects.requireNonNull(listener, "listener"));
    }

    public static void removeSpawnListener(SpawnListener listener) {
        KeyframeEffectManager.removeSpawnListener(listener);
    }

    /**
     * Parses raw Bedrock/GeckoLib particle keyframe effect IDs.
     * Supports explicit prefixes {@code lumenfx:<ns>:<path>} or {@code lumenfx/<ns>:<path>},
     * standard {@code <ns>:<path>}, and bare paths (resolved against {@code lumenfx:} when loaded).
     */
    public static @Nullable ParsedEffectId parseEffectId(@Nullable String rawEffect) {
        if (rawEffect == null) return null;
        String trimmed = rawEffect.trim();
        if (trimmed.isEmpty()) return null;

        boolean explicit = false;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("lumenfx:")) {
            String rest = trimmed.substring("lumenfx:".length());
            if (rest.indexOf(':') > 0) {
                explicit = true;
                trimmed = rest;
            }
        } else if (lower.startsWith("lumenfx/")) {
            String rest = trimmed.substring("lumenfx/".length());
            if (!rest.isEmpty()) {
                explicit = true;
                trimmed = rest;
            }
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.indexOf(':') < 0) {
            // Bare path: check lumenfx namespace first when queried, fallback to minecraft namespace
            normalized = "lumenfx:" + normalized;
        }
        return new ParsedEffectId(normalized, explicit);
    }

    /**
     * Manually triggers a keyframe-style LumenFX effect (useful for custom animation runners or explicit handlers).
     * Returns {@code true} if LumenFX handled the effect (spawned or stopped).
     */
    public static boolean trigger(ClientLevel level, Object host, @Nullable Entity entity,
                                  @Nullable Object controllerKey, String controllerName,
                                  String rawEffect, @Nullable String locator,
                                  @Nullable String preEffectScript, @Nullable Boolean bindToActor,
                                  double x, double y, double z) {
        return KeyframeEffectManager.handleKeyframe(level, host, entity, controllerKey, controllerName,
                rawEffect, locator, preEffectScript, bindToActor, x, y, z, false);
    }

    /** Closes and smoothly drains all active keyframe effects bound to {@code host}. */
    public static void stopForHost(Object host) {
        KeyframeEffectManager.stopForHost(host);
    }

    /** Closes and smoothly drains all active keyframe effects bound to a specific controller on {@code host}. */
    public static void stopForController(Object host, Object controllerKey) {
        KeyframeEffectManager.stopForController(host, controllerKey);
    }

    /** Closes matching keyframe effects on {@code host} filtered by optional effect ID and/or locator name. */
    public static void stopEffect(Object host, @Nullable String effectId, @Nullable String locator) {
        KeyframeEffectManager.stopMatching(host, null, effectId, locator);
    }
}
