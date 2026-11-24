package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import org.jspecify.annotations.Nullable;

/** Component-local Niagara tick controls, sampled together at a simulation boundary. */
public record NiagaraComponentTickSettings(boolean forceSolo, float customTimeDilation,
                                           @Nullable NiagaraTickBehavior behavior, @Nullable ComponentTickGroup tickGroup) {
    public static final NiagaraComponentTickSettings DEFAULT = new NiagaraComponentTickSettings(false, 1.0f);
    private static final float SOURCE_SMALL_NUMBER = 1.0e-8f;

    public NiagaraComponentTickSettings(boolean forceSolo, float customTimeDilation) {
        this(forceSolo, customTimeDilation, null, null);
    }

    public NiagaraComponentTickSettings {
        if (!Float.isFinite(customTimeDilation))
            throw new IllegalArgumentException("Niagara custom time dilation must be finite");
    }

    NiagaraComponentTickSettings withForceSolo(boolean enabled) {
        return enabled == forceSolo ? this : new NiagaraComponentTickSettings(enabled, customTimeDilation, behavior, tickGroup);
    }

    NiagaraComponentTickSettings withCustomTimeDilation(float dilation) {
        return Math.abs(customTimeDilation - dilation) <= SOURCE_SMALL_NUMBER
                ? this : new NiagaraComponentTickSettings(forceSolo, dilation, behavior, tickGroup);
    }

    NiagaraComponentTickSettings withTickBehavior(@Nullable NiagaraTickBehavior behavior, @Nullable ComponentTickGroup tickGroup) {
        return new NiagaraComponentTickSettings(forceSolo, customTimeDilation, behavior, tickGroup);
    }

    public boolean requiresSolo() {
        return forceSolo || Math.abs(customTimeDilation - 1.0f) > SOURCE_SMALL_NUMBER;
    }
}
