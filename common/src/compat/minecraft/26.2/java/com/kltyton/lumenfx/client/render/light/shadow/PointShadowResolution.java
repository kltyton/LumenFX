package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.client.render.light.scene.PointLightProjection;
import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;

/** Selects cube or projected depth sizes after source resolution scaling and fade calculation. */
public final class PointShadowResolution {
    static final int SPOT_BORDER = 4;
    public record Settings(int maximumResolution, int minimumResolution, int fadeResolution, float texelsPerPixel,
                           float fadeExponent, boolean fadeUsesResolutionScale, int quality, boolean allowCubeShadows,
                           boolean allowStaticLighting) {
        public Settings(int maximumResolution, int minimumResolution, int fadeResolution, float texelsPerPixel,
                        float fadeExponent, boolean fadeUsesResolutionScale, int quality, boolean allowCubeShadows) {
            this(maximumResolution, minimumResolution, fadeResolution, texelsPerPixel, fadeExponent,
                    fadeUsesResolutionScale, quality, allowCubeShadows, true);
        }
        public static final Settings DEFAULT = new Settings(2048, 32, 64, 1.27324f, .25f, true, 5, true, true);
        public Settings {
            if (maximumResolution <= 0) throw new IllegalArgumentException("Shadow maximum resolution must be positive");
            minimumResolution = Math.max(0, minimumResolution); fadeResolution = Math.max(0, fadeResolution);
            quality = Math.clamp(quality, 0, 5);
        }
    }
    public record Plan(boolean required, int resolution, float fade, int quality, float desiredResolution) {
        public Plan(boolean required, int resolution, float fade, int quality) { this(required, resolution, fade, quality, resolution); }
        public boolean projected() { return resolution > 0; }
        public Plan withResolution(int value) { return new Plan(required, value, fade, quality, desiredResolution); }
    }
    private PointShadowResolution() { }

    public static Plan prepare(ScenePointLight light, PointLightProjection view, Settings settings, int maximumTextureDimension) {
        var authored = light.data().shadowSettings();
        boolean staticShadowing = authored.staticShadowing();
        int quality = authored.hardShadow() ? Math.min(settings.quality(), 1) : settings.quality();
        // Runtime component duplicates have no precomputed level-lighting record.
        boolean requested = light.data().castDynamicShadow() && (!staticShadowing || !settings.allowStaticLighting())
                || staticShadowing && light.data().castStaticShadow();
        boolean spot = light.data().shader().cone() != null;
        float near = authored.nearClipCm() > 0 ? authored.nearClipCm() : spot ? .1f : 1f;
        if (!requested || (!spot && !settings.allowCubeShadows()) || quality == 0
                || (spot && light.data().radius() <= near)
                || (authored.nearClipCm() > 0 && light.data().radius() <= near))
            return new Plan(false, 0, 0, quality);
        if (maximumTextureDimension <= 0) throw new IllegalArgumentException("Shadow allocation needs the actual device texture limit");
        int bufferResolution = Math.clamp(settings.maximumResolution(), 1, maximumTextureDimension);
        int border = spot ? 2 * SPOT_BORDER : 0;
        int maximumResolution = bufferResolution - border;
        if (maximumResolution <= 0) return new Plan(false, 0, 0, quality);
        if (authored.customResolution() > 0) {
            int actual = Math.min(authored.customResolution(), maximumResolution) + border;
            return new Plan(true, actual, 1, quality, actual);
        }
        float raw = view.shadowScreenRadius(light) * settings.texelsPerPixel() * (spot ? 2f : 1f);
        float scale = authored.resolutionScale();
        float clamped = raw;
        if (scale > 1f) clamped *= scale;
        clamped = UnrealRendererMath.min(clamped, maximumResolution);
        if (scale <= 1f) clamped *= scale;
        float fadeInput = settings.fadeUsesResolutionScale() ? clamped : raw;
        float fade = UnrealRendererMath.max(0f, ShadowResolutionFade.alpha(fadeInput, settings.fadeResolution(),
                settings.minimumResolution(), settings.fadeExponent()));
        if (!(fade > 1f / 256f)) return new Plan(false, 0, fade, quality);
        float desired = UnrealRendererMath.max(0f, UnrealRendererMath.max(clamped,
                Math.min(settings.minimumResolution(), maximumResolution)));
        if (spot) {
            int inner = desired >= maximumResolution ? maximumResolution
                    : Math.max(1, Integer.highestOneBit((int)Math.ceil(desired)) - border);
            return new Plan(true, inner + border, fade, quality, desired + border);
        }
        int input = (int)desired;
        int[] supported = {bufferResolution / 2, bufferResolution / 4, bufferResolution / 8,
                bufferResolution / 16, settings.minimumResolution()};
        for (int index = 0; index < supported.length; index++) {
            if (input >= supported[index]) {
                int half = Math.max(bufferResolution / 2, 1);
                int actual = index == 4 ? settings.minimumResolution() : Math.max(half >> index, 1);
                if (actual <= 0) throw new IllegalArgumentException("Selected cube-depth surface has zero resolution");
                return new Plan(true, actual, fade, quality, desired);
            }
        }
        throw new IllegalStateException("Point shadow resolution did not match any native surface size");
    }
}
