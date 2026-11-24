package com.kltyton.lumenfx.client.simulation.niagara;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Up to ten per-instance high-water marks for one emitter definition. */
public final class NiagaraAllocationHistory {
    private static final int MAX_REPORTS = 10;
    private static final AtomicLong NEXT_REPORTER = new AtomicLong();
    private final long[] reporters = new long[MAX_REPORTS];
    private final int[] counts = new int[MAX_REPORTS], ordered = new int[MAX_REPORTS];
    private int size;
    private volatile int estimate;

    public static long nextReporter() { return NEXT_REPORTER.incrementAndGet(); }

    public int estimate() { return estimate; }

    public synchronized void record(long reporter, int count) {
        for (int index = 0; index < size; index++) {
            if (reporters[index] != reporter) continue;
            if (counts[index] < count) {
                counts[index] = count;
                updateEstimate();
            }
            return;
        }
        int index;
        if (size < MAX_REPORTS) index = size++;
        else {
            index = ThreadLocalRandom.current().nextInt(MAX_REPORTS + 1);
            if (index == MAX_REPORTS) return;
        }
        reporters[index] = reporter;
        counts[index] = count;
        updateEstimate();
    }

    public synchronized void remove(long reporter) {
        for (int index = 0; index < size; index++) {
            if (reporters[index] != reporter) continue;
            --size;
            reporters[index] = reporters[size];
            counts[index] = counts[size];
            updateEstimate();
            return;
        }
    }

    public synchronized void clear() {
        size = 0;
        estimate = 0;
    }

    private void updateEstimate() {
        System.arraycopy(counts, 0, ordered, 0, size);
        Arrays.sort(ordered, 0, size);
        estimate = size == 0 ? 0 : ordered[size / 2];
    }
}
