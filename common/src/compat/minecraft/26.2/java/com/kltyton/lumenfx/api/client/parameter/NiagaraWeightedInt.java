package com.kltyton.lumenfx.api.client.parameter;

/** One value and author weight for a Niagara integer distribution parameter. */
public record NiagaraWeightedInt(int value, float weight) {
    public NiagaraWeightedInt {
        if (!Float.isFinite(weight)) throw new IllegalArgumentException("Non-finite Niagara distribution weight");
    }
}
