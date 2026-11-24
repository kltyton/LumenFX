package com.kltyton.lumenfx.effect.render.fog;

/** Scene-owned fog parameters; host fog and source exponential fog use distinct inputs. */
public sealed interface FogSettings permits FogSettings.Host, ExponentialHeightFog, VolumetricFog {
    FogSettings HOST = new Host();
    record Host() implements FogSettings { }
}
