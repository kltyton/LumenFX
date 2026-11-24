package com.kltyton.lumenfx.client.simulation.niagara.gpu;

import java.util.Arrays;
import java.util.List;

/** Niagara's game-thread spawn groups, before the render thread clamps against the GPU count. */
public final class NiagaraGpuSpawnInfo {
    public static final int MAX_GROUPS = 8;

    public record Entry(int count, float interval, float interpolatedStart, int group) { }
    public enum Limit { NONE, FRAME_SPAWNS, GROUPS }

    private final int[] offsets;
    private final int[] parameterWords;
    private final int count;
    private final int totalSpawnedDelta;
    private final long requestedCount;
    private final Limit limit;
    private final int rejectedEntry;

    private NiagaraGpuSpawnInfo(int[] offsets, int[] parameterWords, int count, int totalSpawnedDelta,
                                long requestedCount, Limit limit, int rejectedEntry) {
        this.offsets = offsets;
        this.parameterWords = parameterWords;
        this.count = count;
        this.totalSpawnedDelta = totalSpawnedDelta;
        this.requestedCount = requestedCount;
        this.limit = limit;
        this.rejectedEntry = rejectedEntry;
    }

    public static NiagaraGpuSpawnInfo build(List<Entry> entries, int maxFrameSpawns, boolean allowSpawning) {
        if (maxFrameSpawns < 0) throw new IllegalArgumentException("Negative GPU spawn limit");
        int[] offsets = new int[MAX_GROUPS];
        int[] words = new int[MAX_GROUPS * 4];
        Arrays.fill(offsets, Integer.MAX_VALUE);
        int groups = 0, count = 0, total = 0, rejected = -1;
        long requested = 0;
        Limit limit = Limit.NONE;
        if (allowSpawning) {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.count() > 0) {
                    requested += entry.count();
                    if (limit != Limit.NONE) continue;
                    if (groups == MAX_GROUPS) { limit = Limit.GROUPS; rejected = i; continue; }
                    if (requested > maxFrameSpawns) { limit = Limit.FRAME_SPAWNS; rejected = i; continue; }
                    int base = groups * 4;
                    words[base] = Float.floatToRawIntBits(entry.interval());
                    words[base + 1] = Float.floatToRawIntBits(entry.interpolatedStart());
                    words[base + 2] = entry.group();
                    words[base + 3] = count;
                    count += entry.count();
                    offsets[groups++] = count;
                }
                if (limit == Limit.NONE) total += entry.count();
            }
        }
        for (int i = groups; i < MAX_GROUPS; i++) words[i * 4 + 3] = count;
        return new NiagaraGpuSpawnInfo(offsets, words, count, total, requested, limit, rejected);
    }

    public int[] offsets() { return offsets.clone(); }
    public int[] parameterWords() { return parameterWords.clone(); }
    public int count() { return count; }
    public long requestedCount() { return requestedCount; }
    public int totalSpawnedDelta() { return totalSpawnedDelta; }
    public Limit limit() { return limit; }
    public int rejectedEntry() { return rejectedEntry; }
}
