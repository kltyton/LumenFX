package com.kltyton.lumenfx.client.simulation.niagara.gpu;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuCountReadback;

/** CPU-side upper estimate corrected by a delayed GPU particle-count snapshot. */
public final class NiagaraGpuCountEstimate {
    private int current;
    private int maximumSeen;
    private int allocationHighWater;
    private long nextSequence;
    private long pendingSequence = -1;
    private int pendingEstimate;

    public int advance(int spawnCount, int eventSpawnCount, int maximumInstances,
                       int allocationEstimate, boolean freeBufferEarly) {
        if (spawnCount < 0 || eventSpawnCount < 0 || maximumInstances < 0 || allocationEstimate < 0)
            throw new IllegalArgumentException("Invalid Niagara GPU particle count inputs");
        long requested = (long) current + spawnCount + eventSpawnCount;
        current = (int) Math.min(requested, maximumInstances);
        maximumSeen = Math.max(maximumSeen, current);
        allocationHighWater = Math.max(allocationHighWater,
                !freeBufferEarly || current > 0 ? Math.max(current, allocationEstimate) : current);
        return current;
    }

    /** A negative result means an earlier GPU count is still awaiting completion. */
    public long beginReadback() {
        return beginReadback(current);
    }

    public long beginReadback(int sourceEstimate) {
        if (sourceEstimate < 0) throw new IllegalArgumentException("Negative Niagara GPU source estimate");
        if (pendingSequence >= 0) return -1;
        pendingSequence = nextSequence++;
        pendingEstimate = sourceEstimate;
        return pendingSequence;
    }

    public void cancelReadback(long sequence) {
        if (pendingSequence == sequence) pendingSequence = -1;
    }

    public void accept(NiagaraGpuCountReadback.Sample sample) {
        if (sample.sequence() != pendingSequence) return;
        pendingSequence = -1;
        int actual = sample.count();
        if (actual < 0 || actual > pendingEstimate) return;
        int dead = pendingEstimate - actual;
        if (dead <= current) current -= dead;
    }

    public int current() { return current; }
    public int maximumSeen() { return maximumSeen; }
    public int allocationHighWater() { return allocationHighWater; }

    public NiagaraGpuCountEstimate renderSnapshot() {
        var snapshot = new NiagaraGpuCountEstimate();
        snapshot.current = current;
        snapshot.maximumSeen = maximumSeen;
        snapshot.allocationHighWater = allocationHighWater;
        return snapshot;
    }

    /** Analytic generation replaces its whole dataset and supplies a fresh CPU upper bound each frame. */
    public void generationEstimate(int count) {
        if (count < 0 || pendingSequence >= 0) throw new IllegalStateException("Cannot replace the analytic particle-count estimate");
        current = maximumSeen = count;
        allocationHighWater = Math.max(allocationHighWater, count);
    }

    /** CPU-owned render uploads already know their live count without a GPU readback. */
    public void cpuCount(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative CPU particle count");
        if (pendingSequence >= 0) throw new IllegalStateException("Cannot replace a pending GPU estimate with a CPU upload");
        current = count;
        maximumSeen = Math.max(maximumSeen, count);
        allocationHighWater = Math.max(allocationHighWater, count);
    }

    public void reset() {
        current = 0;
        maximumSeen = 0;
        allocationHighWater = 0;
        pendingSequence = -1;
    }
}
