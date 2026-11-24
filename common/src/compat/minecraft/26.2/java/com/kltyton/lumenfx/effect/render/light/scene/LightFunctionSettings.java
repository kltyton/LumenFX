package com.kltyton.lumenfx.effect.render.light.scene;

import net.minecraft.world.phys.Vec3;

/** Light-function projection scale is in source centimetres and uses the source axis order. */
public record LightFunctionSettings(Vec3 scale, float fadeDistance, float disabledBrightness, boolean unityCookie) {
    public LightFunctionSettings(Vec3 scale, float fadeDistance, float disabledBrightness) {
        this(scale, fadeDistance, disabledBrightness, false);
    }
    public static final LightFunctionSettings UNITY_COOKIE = new LightFunctionSettings(new Vec3(1, 1, 1), Float.MAX_VALUE, 1f, true);
    public static final LightFunctionSettings DEFAULT = new LightFunctionSettings(new Vec3(1024, 1024, 1024), 100000, .5f);
}
