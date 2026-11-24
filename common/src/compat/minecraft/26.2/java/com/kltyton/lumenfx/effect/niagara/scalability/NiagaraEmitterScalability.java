package com.kltyton.lumenfx.effect.niagara.scalability;

import java.util.List;

/** Effect-type selection takes its first active entry; matching emitter overrides then apply in order. */
public record NiagaraEmitterScalability(NiagaraPlatformSet platforms, List<Setting> effectSettings, List<Setting> overrides) {
    public record Setting(NiagaraPlatformSet platforms, boolean scaleEnabled, float spawnScale, boolean overrideSpawnScale) { }
    public static final NiagaraEmitterScalability DEFAULT = new NiagaraEmitterScalability(NiagaraPlatformSet.ALL, List.of(), List.of());
    public NiagaraEmitterScalability { effectSettings = List.copyOf(effectSettings); overrides = List.copyOf(overrides); }
    public boolean consoleChanged(NiagaraPlatformEnvironment previous, NiagaraPlatformEnvironment next) {
        if (platforms.consoleChanged(previous, next)) return true;
        for (Setting value : effectSettings) if (value.platforms().consoleChanged(previous, next)) return true;
        for (Setting value : overrides) if (value.platforms().consoleChanged(previous, next)) return true;
        return false;
    }
    public float spawnScale(NiagaraPlatformEnvironment environment) {
        float scale = 1f;
        for (Setting value : effectSettings) if (value.platforms().active(environment)) {
            if (value.scaleEnabled()) scale = value.spawnScale();
            break;
        }
        for (Setting value : overrides) if (value.platforms().active(environment) && value.overrideSpawnScale())
            scale = value.scaleEnabled() ? value.spawnScale() : 1f;
        return scale;
    }
}
