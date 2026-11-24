package com.kltyton.lumenfx.effect.render.light.scene;

/** Component distance limits use source centimeters; zero maximum leaves distance unlimited. */
public record PointLightDistanceSettings(float maximum, float fadeRange) {
    public static final PointLightDistanceSettings DEFAULT = new PointLightDistanceSettings(0, 0);
}
