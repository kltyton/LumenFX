package com.kltyton.lumenfx.client.render.light.shadow.contact;

import com.kltyton.lumenfx.effect.render.light.scene.ContactShadowSettings;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;

/** Renderer-wide contact overrides retain the source negative-value disabled convention. */
public record ContactShadowControls(boolean enabled, boolean dynamicShadows, float overrideLength, boolean overrideWorldSpace,
                                    float overrideCastingIntensity, float overrideNonCastingIntensity, boolean substrateTrace) {
    public static final ContactShadowControls DEFAULT = new ContactShadowControls(true, true, -1, false, -1, -1, false);
    public ContactShadowSettings effective(ScenePointLight light) {
        if (!enabled) return ContactShadowSettings.DEFAULT;
        var value = light.data().contactShadows();
        return new ContactShadowSettings(overrideLength >= 0 ? overrideLength : value.length(),
                overrideLength >= 0 ? overrideWorldSpace : value.worldSpace(),
                overrideCastingIntensity >= 0 ? overrideCastingIntensity : value.castingIntensity(),
                overrideNonCastingIntensity >= 0 ? overrideNonCastingIntensity : value.nonCastingIntensity());
    }
    public int shadowedBits(ScenePointLight light) {
        return shadowedBits(light, false);
    }
    public int shadowedBits(ScenePointLight light, boolean lightFunctionAtlas) {
        int bits = light.data().castStaticShadow() || !lightFunctionAtlas && light.lightFunctionMaterial() != null ? 1 : 0;
        return bits | (light.data().castDynamicShadow() && dynamicShadows ? 3 : 0);
    }
    public boolean traces(ScenePointLight light) { return shadowedBits(light) > 1 && Math.abs(effective(light).encodedLength()) > 0; }
}
