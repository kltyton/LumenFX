package com.lowdragmc.photon.client.gameobject.emitter.data;

/** Time-domain burst indexing shared by tick-based and continuous emitters. */
public final class EmissionBurstTiming {
    private EmissionBurstTiming() {
    }

    /** Returns the index of the first repeat in the caller's window, or {@code count}. */
    public static int firstRepeat(double start, double interval, int count, double boundary, boolean inclusive) {
        return (int) firstRepeat(start, interval, (long) count, boundary, inclusive);
    }

    public static long firstRepeat(double start, double interval, long count, double boundary, boolean inclusive) {
        if (count == 0) return 0;
        if (eligible(start, boundary, inclusive)) return 0;
        if (!eligible(time(start, interval, count - 1), boundary, inclusive)) return count;

        // Search on the actual trigger time to handle exact boundaries and zero-length intervals.
        long first = 1;
        long end = count - 1;
        while (first < end) {
            long middle = first + (end - first) / 2;
            if (eligible(time(start, interval, middle), boundary, inclusive)) {
                end = middle;
            } else {
                first = middle + 1;
            }
        }
        return first;
    }

    public static double time(double start, double interval, long index) {
        return start + interval * index;
    }

    private static boolean eligible(double trigger, double boundary, boolean inclusive) {
        return trigger > boundary || inclusive && trigger == boundary;
    }
}
