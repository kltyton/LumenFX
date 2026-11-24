package com.kltyton.lumenfx.client.render.unity;

import com.kltyton.lumenfx.client.component.light.PointLightEffectComponent;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.light.scene.ContactShadowSettings;
import com.kltyton.lumenfx.effect.render.light.scene.LightFunctionSettings;
import com.kltyton.lumenfx.effect.render.light.scene.PointLightDistanceSettings;
import com.kltyton.lumenfx.effect.render.light.scene.PointLightShaderParameters;
import com.kltyton.lumenfx.effect.render.light.scene.PointShadowLightSettings;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SpotLightCone;
import com.kltyton.lumenfx.effect.unity.UnityParticleLights;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Adapts Shuriken particle light poses and units to the shared scene light submissions. */
public final class UnityParticleLightProxy {
    private UnityParticleLightProxy() { }

    public static ScenePointLight prepare(EffectDefinition effect, long identity, UnityParticleLights.Profile profile,
                                          Vec3 position, float radius, Vec3 tint, Vec3 euler, Quaterniondc owner) {
        // Shuriken Euler values apply Z, X, then Y. The shared Unity simulation stores the Z spin negated.
        var rotation = new Quaterniond().rotationYXZ(euler.y, euler.x, -euler.z);
        if (profile.forwardToUp()) rotation.set(rotation.x, rotation.z, -rotation.y, rotation.w);
        else rotation.set(-rotation.x, -rotation.y, rotation.z, rotation.w);
        rotation.premul(owner);
        var forward = sourceDirection(rotation, profile.forward()).normalize();
        var up = sourceDirection(rotation, profile.up());
        var right = up.cross(forward, new Vector3d()).normalize();
        up = forward.cross(right, up);
        var lightToWorld = new Matrix4d(
                forward.x, forward.y, forward.z, 0,
                right.x, right.y, right.z, 0,
                up.x, up.y, up.z, 0,
                position.x * 100, position.z * 100, position.y * 100, 1);
        var worldToLight = new Matrix4d(lightToWorld).invertAffine();
        var cone = profile.shape() == UnityParticleLights.Shape.SPOT
                ? SpotLightCone.fromUnityAngles(profile.innerAngle(), profile.outerAngle()) : null;
        // Inv-square integration uses cm² in the shared shaders. Unity intensity is evaluated with metre distances.
        var shader = PointLightShaderParameters.prepare(lightToWorld, worldToLight, 1f / radius,
                tint.scale(10_000), true, 0, 1, 1, 0, 0, 0, 0, -1, -1, true, cone);
        boolean shadows = profile.shadows() != UnityParticleLights.Shadows.NONE && profile.shadowStrength() > 0;
        var shadow = new PointShadowLightSettings(1, profile.shadowBias(), profile.shadowNormalBias(), 0,
                false, false, profile.shadowStrength(), profile.shadowNear() * 100, profile.shadowResolution(),
                profile.shadows() == UnityParticleLights.Shadows.HARD);
        var cookie = profile.cookieMaterial() == null ? LightFunctionSettings.DEFAULT : LightFunctionSettings.UNITY_COOKIE;
        var data = new PointLightEffectComponent.Frame(shader, radius, 1, 0, shadows, shadows,
                false, cookie, worldToLight, shadow, PointLightDistanceSettings.DEFAULT, 1,
                ContactShadowSettings.DEFAULT, shadows);
        return new ScenePointLight(effect, identity, data, null, profile.cookieMaterial());
    }

    private static Vector3d sourceDirection(Quaterniondc rotation, Vec3 axis) {
        var direction = rotation.transform(new Vector3d(axis.x, axis.y, axis.z));
        return direction.set(direction.x, direction.z, direction.y);
    }
}
