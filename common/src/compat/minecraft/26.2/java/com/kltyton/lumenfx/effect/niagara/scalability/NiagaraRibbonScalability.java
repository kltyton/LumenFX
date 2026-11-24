package com.kltyton.lumenfx.effect.niagara.scalability;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.ConsoleValue;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import java.util.Map;
import java.util.Objects;

/** Active source platform limits for CPU and GPU Niagara ribbon generation. */
public record NiagaraRibbonScalability(boolean enabled, int maximumFactor, float angle,
                                       float screenPercentage, float minimumDisplacementCm, float minimumSegmentCm) {
    private static final String ENABLED = "Niagara.Ribbon.Tessellation.Enabled";
    private static final String MAXIMUM_FACTOR = "Niagara.Ribbon.Tessellation.MaxInterp";
    private static final String ANGLE = "Niagara.Ribbon.Tessellation.MinAngle";
    private static final String SCREEN_PERCENTAGE = "Niagara.Ribbon.Tessellation.MaxErrorScreenPercentage";
    private static final String MINIMUM_DISPLACEMENT = "Niagara.Ribbon.Tessellation.MinAbsoluteError";
    private static final String MINIMUM_SEGMENT = "Niagara.Ribbon.MinSegmentLength";
    private static final String RENDERING_ENABLED = "fx.EnableNiagaraRibbonRendering";
    private static final String GPU_ENABLED = "Niagara.Ribbon.GpuEnabled";
    private static final String GPU_INIT_MODE = "Niagara.Ribbon.GpuInitMode";

    static final Map<String, ConsoleValue> CONSOLE_DEFAULTS = Map.of(
            ENABLED, ConsoleValue.integer(1),
            MAXIMUM_FACTOR, ConsoleValue.integer(16),
            ANGLE, ConsoleValue.floating(15f * (2f * (float)Math.PI) / 360f),
            SCREEN_PERCENTAGE, ConsoleValue.floating(.002f),
            MINIMUM_DISPLACEMENT, ConsoleValue.floating(.5f),
            MINIMUM_SEGMENT, ConsoleValue.floating(1f),
            RENDERING_ENABLED, ConsoleValue.bool(true),
            GPU_ENABLED, ConsoleValue.integer(1),
            GPU_INIT_MODE, ConsoleValue.integer(0));

    public static boolean renderingEnabled(NiagaraPlatformEnvironment platform) {
        return bits(platform, RENDERING_ENABLED) != 0;
    }

    public static boolean gpuEnabled(NiagaraPlatformEnvironment platform) {
        return bits(platform, GPU_ENABLED) != 0;
    }

    public static boolean useGpuForCpuRibbon(RibbonSettings settings, NiagaraPlatformEnvironment platform) {
        int mode = bits(platform, GPU_INIT_MODE);
        return gpuEnabled(platform) && mode != 2 && (settings.gpuInitialization() || mode == 1);
    }

    public static NiagaraRibbonScalability from(NiagaraPlatformEnvironment platform) {
        return new NiagaraRibbonScalability(bits(platform, ENABLED) != 0,
                Math.max(1, bits(platform, MAXIMUM_FACTOR)), floating(platform, ANGLE), floating(platform, SCREEN_PERCENTAGE),
                floating(platform, MINIMUM_DISPLACEMENT), floating(platform, MINIMUM_SEGMENT));
    }

    public int maximum(RibbonSettings settings) {
        return switch (settings.tessellationMode()) {
            case AUTOMATIC -> maximumFactor;
            case CUSTOM -> Math.min(maximumFactor, settings.maximumTessellation());
            case DISABLED -> 1;
        };
    }

    public boolean constant(RibbonSettings settings) {
        return settings.tessellationMode() == RibbonSettings.TessellationMode.CUSTOM && settings.constantTessellation();
    }

    private static int bits(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name)).bits();
    }

    private static float floating(NiagaraPlatformEnvironment platform, String name) {
        return Float.intBitsToFloat(bits(platform, name));
    }
}
