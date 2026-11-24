package com.kltyton.lumenfx.effect.niagara;

/** Warmup, fixed-step, frame dependencies and the source Niagara project's delta limit. */
public record NiagaraTiming(int warmupTicks, float warmupDelta, boolean fixedTick, float fixedDelta,
                            boolean requireCurrentFrameData, float maximumDelta, NiagaraSystemState systemState,
                            boolean initialOwnerVelocityFromActor) {
    public record Warmup(int ticks, float delta) {
        public Warmup {
            if (!Float.isFinite(delta)) throw new IllegalArgumentException("Invalid Niagara warmup delta");
        }

        public int activeTicks() { return ticks > 0 && delta > 1.0e-8f ? ticks : 0; }
    }

    public static final float DEFAULT_MAXIMUM_DELTA = .125f;
    public static final NiagaraTiming DEFAULT = new NiagaraTiming(0, 1.0f / 15.0f, false, .01667f);

    public NiagaraTiming(int warmupTicks, float warmupDelta, boolean fixedTick, float fixedDelta) {
        this(warmupTicks, warmupDelta, fixedTick, fixedDelta, true);
    }

    public NiagaraTiming(int warmupTicks, float warmupDelta, boolean fixedTick, float fixedDelta,
                         boolean requireCurrentFrameData) {
        this(warmupTicks, warmupDelta, fixedTick, fixedDelta, requireCurrentFrameData, DEFAULT_MAXIMUM_DELTA);
    }

    public NiagaraTiming(int warmupTicks, float warmupDelta, boolean fixedTick, float fixedDelta,
                         boolean requireCurrentFrameData, float maximumDelta) {
        this(warmupTicks, warmupDelta, fixedTick, fixedDelta, requireCurrentFrameData, maximumDelta, NiagaraSystemState.DEFAULT);
    }

    public NiagaraTiming(int warmupTicks, float warmupDelta, boolean fixedTick, float fixedDelta,
                         boolean requireCurrentFrameData, float maximumDelta, NiagaraSystemState systemState) {
        this(warmupTicks, warmupDelta, fixedTick, fixedDelta, requireCurrentFrameData, maximumDelta, systemState, true);
    }

    public NiagaraTiming {
        if (!Float.isFinite(warmupDelta) || !Float.isFinite(fixedDelta) || Float.isNaN(maximumDelta) || maximumDelta < 0)
            throw new IllegalArgumentException("Invalid Niagara system timing");
    }

    public Warmup warmup() { return new Warmup(warmupTicks, warmupDelta); }

    public int activeWarmupTicks() { return warmup().activeTicks(); }
}
