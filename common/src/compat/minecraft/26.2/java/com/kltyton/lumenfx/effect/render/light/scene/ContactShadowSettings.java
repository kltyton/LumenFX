package com.kltyton.lumenfx.effect.render.light.scene;

/** Source light contact-shadow length and the two receiver-selected occlusion intensities. */
public record ContactShadowSettings(float length, boolean worldSpace, float castingIntensity, float nonCastingIntensity) {
    public static final ContactShadowSettings DEFAULT = new ContactShadowSettings(0, false, 1, 0);
    public float encodedLength() { return length * (worldSpace ? -1f : 1f); }
}
