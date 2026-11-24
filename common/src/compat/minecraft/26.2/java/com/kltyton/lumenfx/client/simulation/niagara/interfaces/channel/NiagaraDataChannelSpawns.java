package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Spawn counts and source execution-index lookup for one emitter and reader. */
final class NiagaraDataChannelSpawns {
    private final int[] counts;
    private final float[] scales;
    private int[] totals = new int[0];
    private List<Span> spans = List.of();
    private record Span(long end, int length, int row, int firstInRow) { }

    NiagaraDataChannelSpawns(int rows, boolean zeroScale) {
        counts = new int[rows];
        scales = new float[rows];
        if (!zeroScale) Arrays.fill(scales, 1f);
    }

    void count(int index, int count, boolean accumulate) { counts[index] = accumulate ? counts[index] + count : count; }
    void scale(int index, float scale, boolean multiply) { scales[index] = multiply ? scales[index] * scale : scale; }

    List<NiagaraGpuSpawnInfo.Entry> publish(boolean perRow) {
        totals = new int[counts.length];
        var spawn = new ArrayList<NiagaraGpuSpawnInfo.Entry>();
        int sum = 0;
        for (int row = 0; row < counts.length; row++) {
            int count = (int)(Integer.toUnsignedLong(counts[row]) * scales[row]);
            totals[row] = count;
            if (count > 0 && perRow) spawn.add(new NiagaraGpuSpawnInfo.Entry(count, 0, 0, row));
            if (!perRow) sum = Math.addExact(sum, count);
        }
        if (!perRow && sum > 0) spawn.add(new NiagaraGpuSpawnInfo.Entry(sum, 0, 0, 0));
        var indices = new ArrayList<Span>();
        int[] perRowOffsets = new int[totals.length];
        long end = 0;
        for (int shift = 15; shift >= 0; shift--) for (int row = 0; row < totals.length; row++) {
            int amount = shift == 15 ? (totals[row] >>> 15) << 15 : totals[row] & 1 << shift;
            if (amount == 0) continue;
            end += amount;
            indices.add(new Span(end, amount, row, perRowOffsets[row]));
            perRowOffsets[row] += amount;
        }
        spans = List.copyOf(indices);
        return spawn;
    }

    void lookup(int executionIndex, int[] output) {
        Arrays.fill(output, -1);
        if (executionIndex < 0 || spans.isEmpty() || executionIndex >= spans.getLast().end()) return;
        int low = 0, high = spans.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (executionIndex < spans.get(middle).end()) high = middle;
            else low = middle + 1;
        }
        var span = spans.get(low);
        output[0] = span.row();
        output[1] = span.firstInRow() + (int)(executionIndex - (span.end() - span.length()));
        output[2] = totals[span.row()];
    }

    int elements() { return totals.length; }
    int[] gpuWords() {
        int size = 16 + totals.length;
        for (int count : totals) size = Math.addExact(size, (count >>> 15) + Integer.bitCount(count & 0x7fff));
        var packed = new int[size];
        System.arraycopy(totals, 0, packed, 16, totals.length);
        int next = 16 + totals.length;
        for (int shift = 15; shift >= 0; shift--) {
            int start = next;
            for (int row = 0; row < totals.length; row++) {
                int count = shift == 15 ? totals[row] >>> 15 : (totals[row] >>> shift) & 1;
                Arrays.fill(packed, next, next + count, row);
                next += count;
            }
            packed[15 - shift] = next - start;
        }
        return packed;
    }
}
