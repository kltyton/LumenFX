package com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraWeightedInt;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Immutable CPU/GPU publication of the native probability, alias, value and weight words. */
public final class NiagaraIntDistribution {
    public static final String TYPE = "/Script/Niagara.NiagaraDataInterfaceArrayDistributionInt";
    public enum Function { Length, IsValidIndex, LastIndex, Get, GetProbabilityAlias, GetRandomValue }
    private static final NiagaraIntDistribution EMPTY = new NiagaraIntDistribution(new int[4]);
    private final int[] words;

    private NiagaraIntDistribution(int[] words) { this.words = words; }

    public static NiagaraIntDistribution read(JsonObject json) {
        if (json.has("cooked_words")) {
            var values = json.getAsJsonArray("cooked_words");
            if (values.isEmpty() || values.size() % 4 != 0)
                throw new IllegalArgumentException("Invalid Niagara integer distribution table length");
            int[] words = new int[values.size()];
            for (int index = 0; index < words.length; index++) words[index] = values.get(index).getAsInt();
            for (int index = 0; index < words.length; index += 4) {
                float probability = Float.intBitsToFloat(words[index]);
                if (!(probability >= 0 && probability <= 1) || words[index + 1] < 0 || words[index + 1] >= words.length / 4)
                    throw new IllegalArgumentException("Invalid Niagara distribution alias entry");
            }
            return new NiagaraIntDistribution(words);
        }
        var values = new ArrayList<NiagaraWeightedInt>();
        for (var value : json.getAsJsonArray("entries")) {
            var entry = value.getAsJsonArray();
            values.add(new NiagaraWeightedInt(entry.get(0).getAsInt(), entry.get(1).getAsFloat()));
        }
        return build(values);
    }

    public static NiagaraIntDistribution build(List<NiagaraWeightedInt> entries) {
        if (entries.isEmpty()) return EMPTY;
        int count = entries.size();
        var weights = new float[count];
        for (int index = 0; index < count; index++) weights[index] = Math.max(1.e-8f, entries.get(index).weight());
        var aliases = new NiagaraWeightedAliasTable(weights);
        int[] words = new int[Math.multiplyExact(count, 4)];
        for (int index = 0; index < count; index++) {
            var entry = entries.get(index);
            int offset = index * 4;
            words[offset] = Float.floatToRawIntBits(aliases.probability(index));
            words[offset + 1] = aliases.alias(index);
            words[offset + 2] = entry.value();
            words[offset + 3] = Float.floatToRawIntBits(entry.weight());
        }
        return new NiagaraIntDistribution(words);
    }

    public int length() { return words.length / 4; }
    public int byteSize() { return Math.multiplyExact(words.length, Integer.BYTES); }
    public void write(ByteBuffer buffer) { for (int word : words) buffer.putInt(word); }

    public boolean validIndex(int index) { return index >= 0 && index < length(); }
    public int get(int index, int field) { return words[Math.clamp(index, 0, length() - 1) * 4 + field]; }
    public int sample(float indexRandom, float probabilityRandom) {
        int index = (int)(indexRandom * length());
        return get(probabilityRandom > Float.intBitsToFloat(get(index, 0)) ? get(index, 1) : index, 2);
    }
}
