package com.kltyton.lumenfx.effect.render.volume;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.ConsoleValue;
import java.util.Map;
import java.util.Objects;

/** Source heterogeneous-volume controls resolved from one published device-profile snapshot. */
public record VolumeTracingSettings(int lightingCache, int maxSteps, float distance, float shadowDistance,
                                    float stepSize, float shadowStepSize, int lightingDownsample,
                                    boolean continuousLod, boolean jitter, boolean heightFog, boolean volumetricFog,
                                    boolean fogInscattering, Preshading preshading) {
    public record Preshading(boolean enabled, int continuousLod, int pixelFormat, int resolutionX, int resolutionY, int resolutionZ) {
        public int x(int source) { return resolution(resolutionX, source); }
        public int y(int source) { return resolution(resolutionY, source); }
        public int z(int source) { return resolution(resolutionZ, source); }
        private static int resolution(int override, int source) { return Math.clamp(override > 0 ? override : source, 1, 1024); }
    }
    private static final String PREFIX = "r.HeterogeneousVolumes.";
    public static final Map<String, ConsoleValue> CONSOLE_DEFAULTS = Map.ofEntries(
            Map.entry("r.HeterogeneousVolumes", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "Allowed", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "LightingCache", ConsoleValue.integer(2)),
            Map.entry(PREFIX + "MaxStepCount", ConsoleValue.integer(512)),
            Map.entry(PREFIX + "MaxTraceDistance", ConsoleValue.floating(30000)),
            Map.entry(PREFIX + "MaxShadowTraceDistance", ConsoleValue.floating(30000)),
            Map.entry(PREFIX + "StepSize", ConsoleValue.floating(-1)),
            Map.entry(PREFIX + "ShadowStepSize", ConsoleValue.floating(-1)),
            Map.entry(PREFIX + "LightingCache.DownsampleFactor", ConsoleValue.integer(0)),
            Map.entry(PREFIX + "CLOD", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "Jitter", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "HeightFog", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "VolumetricFog", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "ApplyFogInscattering", ConsoleValue.integer(2)),
            Map.entry(PREFIX + "Velocity", ConsoleValue.integer(0)),
            Map.entry(PREFIX + "Preshading", ConsoleValue.integer(0)),
            Map.entry(PREFIX + "Preshading.CLOD", ConsoleValue.integer(1)),
            Map.entry(PREFIX + "Preshading.PixelFormat", ConsoleValue.integer(2)),
            Map.entry(PREFIX + "VolumeResolution.X", ConsoleValue.integer(0)),
            Map.entry(PREFIX + "VolumeResolution.Y", ConsoleValue.integer(0)),
            Map.entry(PREFIX + "VolumeResolution.Z", ConsoleValue.integer(0)));

    public static boolean sourcePlatformSupportsVolumes(NiagaraPlatformEnvironment source) {
        String format = source.shaderPlatforms().featureLevel();
        return !format.contains("ES3_1") && !format.equals("METAL_SIM")
                && Objects.requireNonNull(source.console("r.ForwardShading")).bits() == 0;
    }

    public static boolean enabled(NiagaraPlatformEnvironment source) {
        return Objects.requireNonNull(source.console("r.HeterogeneousVolumes")).bits() != 0
                && integer(source, "Allowed") != 0;
    }

    public static VolumeTracingSettings resolve(NiagaraPlatformEnvironment source) {
        return new VolumeTracingSettings(integer(source, "LightingCache"), Math.max(integer(source, "MaxStepCount"), 1),
                Math.max(floating(source, "MaxTraceDistance"), 0), Math.max(floating(source, "MaxShadowTraceDistance"), 0),
                floating(source, "StepSize"), floating(source, "ShadowStepSize"), integer(source, "LightingCache.DownsampleFactor"),
                integer(source, "CLOD") != 0, integer(source, "Jitter") != 0, integer(source, "HeightFog") != 0,
                integer(source, "VolumetricFog") != 0, integer(source, "ApplyFogInscattering") != 0,
                new Preshading(integer(source, "Preshading") != 0, integer(source, "Preshading.CLOD"),
                        integer(source, "Preshading.PixelFormat"), integer(source, "VolumeResolution.X"),
                        integer(source, "VolumeResolution.Y"), integer(source, "VolumeResolution.Z")));
    }

    private static int integer(NiagaraPlatformEnvironment source, String name) {
        return Objects.requireNonNull(source.console(PREFIX + name)).bits();
    }

    private static float floating(NiagaraPlatformEnvironment source, String name) {
        float value = Float.intBitsToFloat(integer(source, name));
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite source volume CVar " + PREFIX + name);
        return value;
    }
}
