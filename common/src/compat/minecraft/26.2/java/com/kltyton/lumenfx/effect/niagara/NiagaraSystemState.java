package com.kltyton.lumenfx.effect.niagara;

/** Saved Niagara system-state settings, including the source fast-path script selection. */
public record NiagaraSystemState(boolean runSpawn, boolean runUpdate, boolean ignoreState,
                                boolean resampleDuration, boolean delayEnabled, boolean firstDelayOnly, boolean resampleDelay,
                                InactiveResponse inactiveResponse, LoopBehavior loopBehavior, int loopCount,
                                Range duration, Range delay) {
    public enum InactiveResponse { COMPLETE, KILL }
    public enum LoopBehavior { INFINITE, MULTIPLE, ONCE }

    public record Range(float min, float max) { }

    public static final NiagaraSystemState DEFAULT = new NiagaraSystemState(true, true, true, false, false, false, false,
            InactiveResponse.COMPLETE, LoopBehavior.ONCE, 1, new Range(0, 0), new Range(0, 0));
}
