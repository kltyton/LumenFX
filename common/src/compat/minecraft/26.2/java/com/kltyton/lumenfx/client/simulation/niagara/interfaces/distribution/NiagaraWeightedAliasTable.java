package com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraExternalRandom;

/** Alias-table construction in source element order and FP32 arithmetic. */
public final class NiagaraWeightedAliasTable {
    private final float[] probability;
    private final int[] alias;
    private final float total;

    public NiagaraWeightedAliasTable(float[] weights) {
        int size = weights.length;
        float sum = 0;
        for (float weight : weights) sum += weight;
        total = sum;
        probability = new float[size];
        alias = new int[size];
        var scaled = new float[size];
        var small = new int[size];
        var large = new int[size];
        int smallCount = 0, largeCount = 0;
        float scale = sum == 0 ? 0 : size / sum;
        for (int index = size - 1; index >= 0; index--) {
            scaled[index] = weights[index] * scale;
            if (scaled[index] < 1) small[smallCount++] = index;
            else large[largeCount++] = index;
        }
        while (smallCount != 0 && largeCount != 0) {
            int low = small[--smallCount], high = large[--largeCount];
            probability[low] = scaled[low];
            alias[low] = high;
            scaled[high] = (scaled[high] + scaled[low]) - 1f;
            if (scaled[high] < 1f) small[smallCount++] = high;
            else large[largeCount++] = high;
        }
        for (int index = 0; index < smallCount; index++) finish(small[index]);
        for (int index = 0; index < largeCount; index++) finish(large[index]);
    }

    private void finish(int index) { probability[index] = 1; alias[index] = index; }
    public float total() { return total; }
    public int size() { return alias.length; }
    public float probability(int index) { return probability[index]; }
    public int alias(int index) { return alias[index]; }
    public int sample(NiagaraExternalRandom random) {
        if (alias.length == 0) return -1;
        return sample(random.next(), random.next());
    }
    public int sample(float slotRandom, float thresholdRandom) {
        if (alias.length == 0) return -1;
        int index = Math.clamp((int)(slotRandom * alias.length), 0, alias.length - 1);
        return thresholdRandom < probability[index] ? index : alias[index];
    }
}
