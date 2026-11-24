package com.kltyton.lumenfx.effect.niagara;

import java.util.Objects;

/** Resolved Niagara project and system Large World Coordinate settings. */
public record NiagaraLwcSettings(boolean enabled, UpdateMode updateMode, long maximumTilesBeforeReset) {
    public enum UpdateMode { RESET_SIMULATION, REBASE, REBASE_OR_RESET_SIMULATION }

    public static final NiagaraLwcSettings DEFAULT = new NiagaraLwcSettings(true, UpdateMode.REBASE, 4);

    public NiagaraLwcSettings {
        Objects.requireNonNull(updateMode);
        if (maximumTilesBeforeReset < 0 || maximumTilesBeforeReset > 0xffff_ffffL)
            throw new IllegalArgumentException("Invalid Niagara LWC reset threshold");
    }

    public boolean resetsOnRecache(float distanceInTiles) {
        return updateMode == UpdateMode.RESET_SIMULATION
                || updateMode == UpdateMode.REBASE_OR_RESET_SIMULATION && distanceInTiles >= maximumTilesBeforeReset;
    }
}
