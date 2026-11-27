package com.kltyton.lumenfx.api.client.keyframe;

import com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Public entry point for integrating LumenFX with GeckoLib (4.x / 5.x), KBR / KBR-Lite,
 * Player Animation Library (PAL), {@code PlayerAnimationLibraryMoreRotation} (PALMore), and
 * AnimCue ({@code interactive_animation}) particle keyframes.
 *
 * <p>Standalone GeckoLib and PAL / PALMore keep their native callback-only particle keyframe model (LumenFX does not
 * auto-spawn particles there); mods trigger LumenFX inside their particle keyframe callback via {@link #trigger},
 * automatically gaining per-frame 3D locator/bone/bend pose tracking and controller reset/stop cleanup.
 * When KBR / KBR-Lite or AnimCue (0.1.2+) is installed (which perform automatic particle keyframe playback), LumenFX
 * hooks their particle dispatchers to play loaded LumenFX effects (or explicit {@code lumenfx:} prefixes) cleanly
 * while preserving vanilla particles, KBR Bedrock/SnowStorm/3D particles, and custom callbacks.</p>
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
            explicit = true;
            String rest = trimmed.substring("lumenfx:".length());
            if (rest.indexOf(':') > 0) {
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
