package com.kltyton.lumenfx.effect.niagara.component;

/** Native system asset settings for the number of free and primed components. */
public record NiagaraComponentPoolSettings(int maximum, int prime) {
    public static final NiagaraComponentPoolSettings DEFAULT = new NiagaraComponentPoolSettings(32, 0);

    public NiagaraComponentPoolSettings {
        if (maximum < 0 || prime < 0) throw new IllegalArgumentException("Negative Niagara component pool size");
    }

    public int primedMinimum() { return Math.min(maximum, prime); }
}
