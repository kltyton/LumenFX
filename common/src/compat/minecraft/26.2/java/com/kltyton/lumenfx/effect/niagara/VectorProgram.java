package com.kltyton.lumenfx.effect.niagara;

import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import org.jspecify.annotations.Nullable;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;

/** Optimized instructions and typed register bindings of one saved CPU script. */
public record VectorProgram(int temporaryRegisters, int outputRegisters, int maxOutputDatasets,
                            int maxExternalRegisters, int dummyRegisters, int runtimeBytes,
                            boolean interpolated, int parameterBytes, List<Parameter> parameters,
                            byte[] constantData, int[] constantSlots, List<Input> inputs,
                            List<Output> outputs, List<External> external, List<Instruction> instructions,
                            @Nullable ScriptParameterStoreDefinition parameterStore, UserPointerLayout userPointers) {
    public VectorProgram(int temporaryRegisters, int outputRegisters, int maxOutputDatasets,
                         int maxExternalRegisters, int dummyRegisters, int runtimeBytes,
                         boolean interpolated, int parameterBytes, List<Parameter> parameters,
                         byte[] constantData, int[] constantSlots, List<Input> inputs,
                         List<Output> outputs, List<External> external, List<Instruction> instructions,
                         @Nullable ScriptParameterStoreDefinition parameterStore) {
        this(temporaryRegisters, outputRegisters, maxOutputDatasets, maxExternalRegisters, dummyRegisters, runtimeBytes,
                interpolated, parameterBytes, parameters, constantData, constantSlots, inputs, outputs, external, instructions,
                parameterStore, UserPointerLayout.EMPTY);
    }
    public VectorProgram(int temporaryRegisters, int outputRegisters, int maxOutputDatasets,
                         int maxExternalRegisters, int dummyRegisters, int runtimeBytes,
                         boolean interpolated, int parameterBytes, List<Parameter> parameters,
                         byte[] constantData, int[] constantSlots, List<Input> inputs,
                         List<Output> outputs, List<External> external, List<Instruction> instructions) {
        this(temporaryRegisters, outputRegisters, maxOutputDatasets, maxExternalRegisters, dummyRegisters, runtimeBytes,
                interpolated, parameterBytes, parameters, constantData, constantSlots, inputs, outputs, external, instructions, null);
    }
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
        CMPLT_LOGIC_AND, CMPLE_LOGIC_AND, CMPGT_LOGIC_AND, CMPGE_LOGIC_AND, CMPEQ_LOGIC_AND, CMPNE_LOGIC_AND,
        CMPLTI_LOGIC_AND, CMPLEI_LOGIC_AND, CMPGTI_LOGIC_AND, CMPGEI_LOGIC_AND, CMPEQI_LOGIC_AND, CMPNEI_LOGIC_AND,
        CMPLT_LOGIC_OR, CMPLE_LOGIC_OR, CMPGT_LOGIC_OR, CMPGE_LOGIC_OR, CMPEQ_LOGIC_OR, CMPNE_LOGIC_OR,
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
    public record Parameter(String name, int offset, int bytes, String sourceType, @Nullable NiagaraCollectionBinding collection,
                            int underlyingType, int typeFlags, @Nullable Boolean lwc) {
        public Parameter(String name, int offset, int bytes, String sourceType, @Nullable NiagaraCollectionBinding collection,
                         int underlyingType, int typeFlags) {
            this(name, offset, bytes, sourceType, collection, underlyingType, typeFlags, null);
        }
        public Parameter(String name, int offset, int bytes, String sourceType, @Nullable NiagaraCollectionBinding collection) {
            this(name, offset, bytes, sourceType, collection, 2, 0);
        }
        public Parameter(String name, int offset, int bytes, String sourceType) { this(name, offset, bytes, sourceType, null); }
        public Parameter {
            if (underlyingType < 0 || underlyingType > 3 || typeFlags < 0 || typeFlags > 255)
                throw new IllegalArgumentException("Invalid script parameter type definition");
            if (collection != null && (collection.bytes() != bytes || !collection.sourceType().equals(sourceType)
                    || !SourceNames.equal(collection.name(), name))) throw new IllegalArgumentException("Collection parameter layout mismatch: " + name);
        }
    }
    public record Input(int dataset, int type, int component, boolean constant) { }
    public record Output(int dataset, int type, int component) { }
    public record UserPointer(int index, Key key, @Nullable Key instanceKey) {
        public UserPointer(int index, Key key) { this(index, key, null); }
        public UserPointer {
            if (index < 0 || key.storage() != Storage.INTERFACE
                    || instanceKey != null && instanceKey.storage() != Storage.INTERFACE)
                throw new IllegalArgumentException("Invalid DI user pointer binding");
        }
    }
    public record UserPointerLayout(int count, List<UserPointer> bindings) {
        public static final UserPointerLayout EMPTY = new UserPointerLayout(0, List.of());
        public UserPointerLayout {
            bindings = List.copyOf(bindings);
            if (count < 0) throw new IllegalArgumentException("Negative script user pointer count");
            for (var binding : bindings) if (binding.index() >= count)
                throw new IllegalArgumentException("DI user pointer exceeds its script table");
        }
    }
    public record External(String name, int inputs, int outputs, List<NiagaraCurve> curves,
                           @Nullable VectorFieldVolume field, boolean tileX, boolean tileY, boolean tileZ,
                           String interfaceType, String interfaceId, int controllerIndex, boolean requireCurrent,
                           @Nullable JsonObject interfaceData, String interfaceParameter,
                           @Nullable Key interfaceKey, @Nullable JsonObject bindingInfo, @Nullable Key instanceKey) {
        public External(String name, int inputs, int outputs, List<NiagaraCurve> curves,
                        @Nullable VectorFieldVolume field, boolean tileX, boolean tileY, boolean tileZ,
                        String interfaceType, String interfaceId, int controllerIndex, boolean requireCurrent,
                        @Nullable JsonObject interfaceData, String interfaceParameter,
                        @Nullable Key interfaceKey, @Nullable JsonObject bindingInfo) {
            this(name, inputs, outputs, curves, field, tileX, tileY, tileZ, interfaceType, interfaceId,
                    controllerIndex, requireCurrent, interfaceData, interfaceParameter, interfaceKey, bindingInfo, null);
        }
        public External(String name, int inputs, int outputs, List<NiagaraCurve> curves,
                        @Nullable VectorFieldVolume field, boolean tileX, boolean tileY, boolean tileZ,
                        String interfaceType, String interfaceId, int controllerIndex, boolean requireCurrent,
                        @Nullable JsonObject interfaceData, String interfaceParameter) {
            this(name, inputs, outputs, curves, field, tileX, tileY, tileZ, interfaceType, interfaceId,
                    controllerIndex, requireCurrent, interfaceData, interfaceParameter, null, null);
        }
        public External(String name, int inputs, int outputs, List<NiagaraCurve> curves,
                        @Nullable VectorFieldVolume field, boolean tileX, boolean tileY, boolean tileZ) {
            this(name, inputs, outputs, curves, field, tileX, tileY, tileZ, "", "", 0, true, null, "");
        }
        public External {
            curves = List.copyOf(curves);
            if (instanceKey != null && instanceKey.storage() != Storage.INTERFACE)
                throw new IllegalArgumentException("System function binding requires a DI instance key");
            interfaceData = interfaceData == null ? null : interfaceData.deepCopy();
            bindingInfo = bindingInfo == null ? null : bindingInfo.deepCopy();
        }
        @Override public @Nullable JsonObject interfaceData() { return interfaceData == null ? null : interfaceData.deepCopy(); }
        @Override public @Nullable JsonObject bindingInfo() { return bindingInfo == null ? null : bindingInfo.deepCopy(); }
    }
    public record Instruction(Opcode opcode, int inputCount, int dataset, int function, int[] registers, int userPointerRegister) {
        public Instruction(Opcode opcode, int inputCount, int dataset, int function, int[] registers) {
            this(opcode, inputCount, dataset, function, registers, -1);
        }
        public Instruction { registers = registers.clone(); }
        @Override public int[] registers() { return registers.clone(); }
    }
    public VectorProgram {
        constantData = constantData.clone();
        constantSlots = constantSlots.clone();
        parameters = List.copyOf(parameters);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        external = List.copyOf(external);
        instructions = List.copyOf(instructions);
        Objects.requireNonNull(userPointers);
        if (temporaryRegisters < 0 || outputRegisters < outputs.size() || maxOutputDatasets < 1
                || runtimeBytes != 32 && runtimeBytes != 640 || parameterBytes < 0)
            throw new IllegalArgumentException("Invalid optimized vector program layout");
        for (int slot : constantSlots) if (slot < 0 || (long)slot * 4 + 4 > constantData.length)
            throw new IllegalArgumentException("Vector program constant is outside its buffer");
        int registerCount = temporaryRegisters + constantSlots.length + inputs.size() * 2 + outputRegisters;
        for (var instruction : instructions) if (instruction.userPointerRegister() != -1
                && (instruction.opcode() != Opcode.EXTERNAL_FUNC_CALL || instruction.userPointerRegister() < 0
                    || instruction.userPointerRegister() >= registerCount || userPointers.count() == 0))
            throw new IllegalArgumentException("Invalid external DI user pointer register");
    }

    public int registerCount() {
        return temporaryRegisters + constantSlots.length + inputs.size() * 2 + outputRegisters;
    }

    @Override public byte[] constantData() { return constantData.clone(); }
    @Override public int[] constantSlots() { return constantSlots.clone(); }
}
