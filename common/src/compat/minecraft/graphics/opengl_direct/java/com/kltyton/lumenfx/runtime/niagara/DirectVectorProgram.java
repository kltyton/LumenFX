package com.kltyton.lumenfx.runtime.niagara;

import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Decoded UE5/LumenFX compiled Niagara CPU VectorVM script ({@code system_spawn}, {@code system_update},
 * {@code emitter.spawn}, {@code emitter.update}).
 */
public final class DirectVectorProgram {
    public enum Opcode {
        ADD, SUB, MUL, DIV, MAD, LERP, RCP, RSQ, SQRT, NEG, ABS, FLOOR, CLAMP, MIN, MAX, POW,
        EXP, EXP2, LOG, LOG2, SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2,
        CEIL, FMOD, FRAC, TRUNC, ROUND, SIGN, STEP, SIN_COS,
        RANDOM, RANDOMI, RANDOM_ADD, RANDOM_2X, NOISE, NOISE2D, NOISE3D,
        CMPLT, CMPLE, CMPGT, CMPGE, CMPEQ, CMPNEQ, SELECT,
        ADDI, SUBI, MULI, DIVI, CLAMPI, MINI, MAXI, ABSI, NEGI, SIGNI,
        CMPLTI, CMPLEI, CMPGTI, CMPGEI, CMPEQI, CMPNEQI,
        BIT_AND, BIT_OR, BIT_XOR, BIT_NOT, BIT_LSHIFT, BIT_RSHIFT, LOGIC_AND, LOGIC_OR, LOGIC_XOR, LOGIC_NOT,
        F2I, I2F, B2I, B2F, I2B, F2B, FASI, IASF, HALF_TO_FLOAT,
        EXEC_INDEX, EXEC_INDEXF, EXEC_INDEX_ADDI,
        CMPLT_SELECT, CMPLE_SELECT, CMPEQ_SELECT, CMPLTI_SELECT, CMPLEI_SELECT, CMPEQI_SELECT,
        CMPLT_LOGIC_AND, CMPLE_LOGIC_AND, CMPGT_LOGIC_AND, CMPGE_LOGIC_AND, CMPEQ_LOGIC_AND, CMPNEQ_LOGIC_AND,
        CMPLTI_LOGIC_AND, CMPLEI_LOGIC_AND, CMPGTI_LOGIC_AND, CMPGEI_LOGIC_AND, CMPEQI_LOGIC_AND, CMPNEI_LOGIC_AND,
        CMPLT_LOGIC_OR, CMPLE_LOGIC_OR, CMPGT_LOGIC_OR, CMPGE_LOGIC_OR, CMPEQ_LOGIC_OR, CMPNEQ_LOGIC_OR,
        CMPLTI_LOGIC_OR, CMPLEI_LOGIC_OR, CMPGTI_LOGIC_OR, CMPGEI_LOGIC_OR, CMPEQI_LOGIC_OR, CMPNEI_LOGIC_OR,
        MAD_ADD, MAD_SUB0, MAD_SUB1, MAD_MUL, MAD_SQRT, MAD_MAD0, MAD_MAD1,
        MUL_MAD0, MUL_MAD1, MUL_ADD, MUL_SUB0, MUL_SUB1, MUL_MUL, MUL_MAX, MUL_2X,
        ADD_MAD1, ADD_ADD, SUB_CMPLT1, SUB_MUL, SUB_NEG, DIV_MAD0, DIV_MUL,
        MULI_ADDI, ADDI_BIT_RSHIFT, ADDI_MULI, B2I_2X,
        I2F_DIV0, I2F_DIV1, I2F_MUL, I2F_MAD0, I2F_MAD1, DIV_F2I, F2I_MAXI, F2I_SELECT1, F2I_ADDI,
        FMOD_ADD, BIT_AND_I2F, BIT_OR_MULI, MAX_F2I, SELECT_MUL, SELECT_ADD,
        BIT_RSHIFT_BIT_AND, BIT_LSHIFT_BIT_OR, NEG_CMPLT,
        ACQUIREINDEX, ACQUIRE_ID, UPDATE_ID, OUTPUTDATA_FLOAT, OUTPUTDATA_INT32, OUTPUTDATA_HALF,
        OUTPUTDATA_FLOAT_FROM_HALF, OUTPUTDATA_HALF_FROM_HALF, EXTERNAL_FUNC_CALL
    }

    public record Parameter(String name, int offset, int bytes, String sourceType) { }
    public record Input(int dataset, int type, int component, boolean constant) { }
    public record Output(int dataset, int type, int component) { }

    public record CurveLut(float minTime, float maxTime, float[] values) {
        public float sample(float t) {
            if (values.length == 0) return 0f;
            if (values.length == 1) return values[0];
            float span = maxTime - minTime;
            float u = span > 1e-6f ? (t - minTime) / span : 0f;
            u = Math.max(0f, Math.min(1f, u));
            float pos = u * (values.length - 1);
            int idx = (int) pos;
            if (idx >= values.length - 1) return values[values.length - 1];
            float frac = pos - idx;
            return values[idx] + (values[idx + 1] - values[idx]) * frac;
        }
    }

    public record VectorField(VectorFieldVolume volume, boolean tileX, boolean tileY, boolean tileZ) { }
    public record External(String name, int inputs, int outputs, String interfaceType, List<CurveLut> curves,
                           @Nullable VectorField field) { }
    public record Instruction(Opcode opcode, int inputCount, int dataset, int function, int[] registers) { }

    public final int temporaryRegisters;
    public final int outputRegisters;
    public final int maxOutputDatasets;
    public final int runtimeBytes;
    public final boolean interpolated;
    public final int parameterBytes;
    public final List<Parameter> parameters;
    public final byte[] constantData;
    public final int[] constantSlots;
    public final List<Input> inputs;
    public final List<Output> outputs;
    public final List<External> external;
    public final List<Instruction> instructions;

    public DirectVectorProgram(int temporaryRegisters, int outputRegisters, int maxOutputDatasets,
                               int runtimeBytes, boolean interpolated, int parameterBytes,
                               List<Parameter> parameters, byte[] constantData, int[] constantSlots,
                               List<Input> inputs, List<Output> outputs,
                               List<External> external, List<Instruction> instructions) {
        this.temporaryRegisters = temporaryRegisters;
        this.outputRegisters = outputRegisters;
        this.maxOutputDatasets = Math.max(1, maxOutputDatasets);
        this.runtimeBytes = runtimeBytes;
        this.interpolated = interpolated;
        this.parameterBytes = parameterBytes;
        this.parameters = List.copyOf(parameters);
        this.constantData = constantData;
        this.constantSlots = constantSlots;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.external = List.copyOf(external);
        this.instructions = List.copyOf(instructions);
    }

    public int registerCount() {
        return temporaryRegisters + constantSlots.length + inputs.size() * 2 + outputRegisters;
    }

    public static @Nullable DirectVectorProgram fromJson(@Nullable JsonObject obj,
                                                         Function<String, VectorFieldVolume> fields) {
        if (obj == null || !obj.has("instructions") || !obj.get("instructions").isJsonArray()) return null;
        int temp = readInt(obj, "temporary_registers", 0);
        int outRegs = readInt(obj, "output_registers", 0);
        int maxOutDs = readInt(obj, "max_output_datasets", 1);
        int runtimeBytes = readInt(obj, "runtime_bytes", 32);
        boolean interpolated = obj.has("interpolated") && obj.get("interpolated").getAsBoolean();
        int paramBytes = readInt(obj, "parameter_bytes", 0);

        List<Parameter> params = new ArrayList<>();
        if (obj.has("parameters") && obj.get("parameters").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("parameters")) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                params.add(new Parameter(
                        readString(p, "name", ""),
                        readInt(p, "offset", 0),
                        readInt(p, "bytes", 4),
                        readString(p, "source_type", "")));
            }
        }

        byte[] constData = new byte[0];
        if (obj.has("constant_data") && obj.get("constant_data").isJsonPrimitive()) {
            constData = Base64.getDecoder().decode(obj.get("constant_data").getAsString());
        }
        int[] constSlots = readIntArray(obj.getAsJsonArray("constant_slots"));

        List<Input> inputs = new ArrayList<>();
        if (obj.has("inputs") && obj.get("inputs").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("inputs")) {
                if (!el.isJsonObject()) continue;
                JsonObject in = el.getAsJsonObject();
                inputs.add(new Input(
                        readInt(in, "dataset", 0),
                        readInt(in, "type", 0),
                        readInt(in, "component", 0),
                        in.has("constant") && in.get("constant").getAsBoolean()));
            }
        }

        List<Output> outputs = new ArrayList<>();
        if (obj.has("outputs") && obj.get("outputs").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("outputs")) {
                if (!el.isJsonObject()) continue;
                JsonObject out = el.getAsJsonObject();
                outputs.add(new Output(
                        readInt(out, "dataset", 0),
                        readInt(out, "type", 0),
                        readInt(out, "component", 0)));
            }
        }

        List<External> externals = new ArrayList<>();
        if (obj.has("external") && obj.get("external").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("external")) {
                if (!el.isJsonObject()) continue;
                JsonObject ext = el.getAsJsonObject();
                List<CurveLut> curves = parseCurves(ext.getAsJsonArray("curves"));
                String name = readString(ext, "name", "");
                String fieldId = readString(ext, "field", "");
                VectorField field = null;
                if (!fieldId.isBlank() && (name.equals("SampleField") || name.equals("LoadField"))) {
                    var tiling = ext.getAsJsonArray("tile");
                    field = new VectorField(fields.apply(fieldId), tile(tiling, 0), tile(tiling, 1), tile(tiling, 2));
                }
                externals.add(new External(
                        name,
                        readInt(ext, "inputs", 0),
                        readInt(ext, "outputs", 0),
                        readString(ext, "interface_type", ""),
                        curves, field));
            }
        }

        List<Instruction> insns = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("instructions")) {
            if (!el.isJsonObject()) continue;
            JsonObject ins = el.getAsJsonObject();
            String opName = readString(ins, "op", "").toUpperCase(Locale.ROOT);
            Opcode op;
            try {
                op = Opcode.valueOf(opName);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            insns.add(new Instruction(
                    op,
                    readInt(ins, "inputs", 0),
                    readInt(ins, "dataset", 0),
                    readInt(ins, "function", 0),
                    readIntArray(ins.getAsJsonArray("registers"))));
        }

        return new DirectVectorProgram(temp, Math.max(outRegs, outputs.size()), maxOutDs,
                runtimeBytes, interpolated, paramBytes, params, constData, constSlots,
                inputs, outputs, externals, insns);
    }

    private static boolean tile(@Nullable JsonArray tiling, int axis) {
        return tiling != null && axis < tiling.size() && tiling.get(axis).getAsBoolean();
    }

    private static List<CurveLut> parseCurves(@Nullable JsonArray arr) {
        if (arr == null || arr.size() == 0) return List.of();
        List<CurveLut> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            JsonObject lut = c.has("lookup") && c.get("lookup").isJsonObject() ? c.getAsJsonObject("lookup") : c;
            float minT = readFloat(lut, "min", readFloat(lut, "min_time", 0f));
            float maxT = readFloat(lut, "max", readFloat(lut, "max_time", 1f));
            JsonArray vals = lut.has("values") && lut.get("values").isJsonArray()
                    ? lut.getAsJsonArray("values")
                    : lut.has("samples") && lut.get("samples").isJsonArray() ? lut.getAsJsonArray("samples") : null;
            if (vals != null && vals.size() > 0) {
                float[] v = new float[vals.size()];
                for (int i = 0; i < vals.size(); i++) v[i] = vals.get(i).getAsFloat();
                out.add(new CurveLut(minT, maxT, v));
            } else if (c.has("keys") && c.get("keys").isJsonArray()) {
                JsonArray keys = c.getAsJsonArray("keys");
                int n = keys.size();
                float[] v = new float[Math.max(1, n)];
                float lo = 0f, hi = 1f;
                for (int i = 0; i < n; i++) {
                    JsonObject k = keys.get(i).getAsJsonObject();
                    if (i == 0) lo = readFloat(k, "time", 0f);
                    if (i == n - 1) hi = readFloat(k, "time", 1f);
                    v[i] = readFloat(k, "value", 0f);
                }
                out.add(new CurveLut(lo, hi, v));
            }
        }
        return List.copyOf(out);
    }

    private static int[] readIntArray(@Nullable JsonArray arr) {
        if (arr == null) return new int[0];
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsInt();
        return out;
    }

    private static int readInt(JsonObject o, String key, int fb) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fb; }
        catch (RuntimeException ignored) { return fb; }
    }

    private static float readFloat(JsonObject o, String key, float fb) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsFloat() : fb; }
        catch (RuntimeException ignored) { return fb; }
    }

    private static String readString(JsonObject o, String key, String fb) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fb;
    }
}
