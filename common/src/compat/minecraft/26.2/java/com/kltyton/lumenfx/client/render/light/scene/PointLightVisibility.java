package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;
import com.kltyton.lumenfx.effect.render.light.scene.PointLightDistanceSettings;

/** Source local-light screen-size culling and color fade remain distinct from shadow-map fade. */
public final class PointLightVisibility {
    public record Controls(float minimumScreenRadius, float maximumDistanceScale, boolean legacyFade) {
        public static final Controls DEFAULT = new Controls(.03f, 1f, false);
    }
    private PointLightVisibility() { }
    private static float sizeSquared(float radius, float distanceSquared, float lod, Controls controls) {
        float coefficient = UnrealRendererMath.min(.0002f, controls.minimumScreenRadius() / radius) * lod;
        return (coefficient * coefficient) * distanceSquared;
    }
    public static boolean inRange(float radius, float distanceSquared, float lod, PointLightDistanceSettings limits, Controls controls) {
        float maximum = limits.maximum() * controls.maximumDistanceScale();
        return sizeSquared(radius, distanceSquared, lod, controls) < 1f
                && (limits.maximum() <= 0f || distanceSquared < maximum * maximum);
    }
    public static float colorFade(float radius, float distanceSquared, float lod, PointLightDistanceSettings limits, Controls controls) {
        float size = UnrealRendererMath.clamp(6f - 6f * sizeSquared(radius, distanceSquared, lod, controls), 0, 1);
        float range = controls.legacyFade() ? limits.fadeRange() : UnrealRendererMath.max(.001f, limits.fadeRange() * controls.maximumDistanceScale());
        float fade = limits.maximum() > 0f ? (limits.maximum() * controls.maximumDistanceScale() - (float)Math.sqrt(distanceSquared)) / range : 1f;
        return size * UnrealRendererMath.clamp(fade, 0, 1);
    }
}
