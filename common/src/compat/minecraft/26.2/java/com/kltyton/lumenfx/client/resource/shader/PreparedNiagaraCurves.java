package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Serialized Niagara curve LUTs and their offsets in the emitter's static float buffer. */
public record PreparedNiagaraCurves(int[] staticWords, List<Binding> bindings) {
    public PreparedNiagaraCurves {
        staticWords = staticWords.clone();
        bindings = List.copyOf(bindings);
    }
    @Override public int[] staticWords() { return staticWords.clone(); }

    public record Curve(int minimumBits, int maximumBits, int inverseRangeBits, int samplesMinusOne, int[] words) {
        public Curve { words = words.clone(); }
        @Override public int[] words() { return words.clone(); }
    }
    public record Binding(String name, String symbol, boolean unique, int staticOffset, @Nullable Curve defaultCurve) { }

    public static PreparedNiagaraCurves read(JsonObject program) {
        List<Integer> packed = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        JsonArray interfaces = program.getAsJsonArray("interfaces");
        if (interfaces == null) throw new IllegalArgumentException("Missing Niagara GPU data interfaces");
        for (var item : interfaces) {
            JsonObject source = item.getAsJsonObject();
            String type = source.get("class").getAsString();
            int components = switch (type) {
                case "NiagaraDataInterfaceCurve" -> 1;
                case "NiagaraDataInterfaceVector2DCurve" -> 2;
                case "NiagaraDataInterfaceVectorCurve" -> 3;
                case "NiagaraDataInterfaceVector4Curve", "NiagaraDataInterfaceColorCurve" -> 4;
                default -> 0;
            };
            if (components == 0) continue;
            if (!source.has("shader_symbol") || !source.has("curve_use_unique_buffer"))
                throw new IllegalArgumentException("Unresolved Niagara GPU curve shader binding " + source.get("compile_name"));
            String name = source.get("compile_name").getAsString(), symbol = source.get("shader_symbol").getAsString();
            boolean unique = source.get("curve_use_unique_buffer").getAsBoolean();
            JsonObject properties = source.has("properties") && source.get("properties").isJsonObject()
                    ? source.getAsJsonObject("properties") : null;
            Curve curve = properties == null ? null : curve(properties, components, name);
            int offset = curve == null || unique ? -1 : append(packed, curve.words);
            bindings.add(new Binding(name, symbol, unique, offset, curve));
        }
        int[] words = new int[packed.size()];
        for (int i = 0; i < words.length; i++) words[i] = packed.get(i);
        return new PreparedNiagaraCurves(words, bindings);
    }

    public static Curve curve(JsonObject properties, int components, String name) {
        JsonArray lut = properties.getAsJsonArray("ShaderLUT");
        if (lut == null || !properties.has("LUTNumSamplesMinusOne") || !properties.has("LUTMinTime")
                || !properties.has("LUTMaxTime") || !properties.has("LUTInvTimeRange"))
            throw new IllegalArgumentException("Missing serialized Niagara GPU curve LUT " + name);
        int samplesMinusOne = properties.get("LUTNumSamplesMinusOne").getAsInt();
        if (samplesMinusOne < 0 || lut.size() != Math.multiplyExact(samplesMinusOne + 1, components))
            throw new IllegalArgumentException("Invalid Niagara GPU curve LUT size " + name);
        int[] words = new int[lut.size()];
        for (int i = 0; i < words.length; i++) words[i] = Float.floatToRawIntBits(lut.get(i).getAsFloat());
        return new Curve(Float.floatToRawIntBits(properties.get("LUTMinTime").getAsFloat()),
                Float.floatToRawIntBits(properties.get("LUTMaxTime").getAsFloat()),
                Float.floatToRawIntBits(properties.get("LUTInvTimeRange").getAsFloat()), samplesMinusOne, words);
    }

    private static int append(List<Integer> destination, int[] source) {
        outer: for (int i = 0; i <= destination.size() - source.length; i++) {
            for (int j = 0; j < source.length; j++) if (destination.get(i + j) != source[j]) continue outer;
            return i;
        }
        int offset = destination.size();
        for (int word : source) destination.add(word);
        return offset;
    }
}
