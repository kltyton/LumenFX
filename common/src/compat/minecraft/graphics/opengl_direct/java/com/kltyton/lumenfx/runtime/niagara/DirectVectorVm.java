package com.kltyton.lumenfx.runtime.niagara;

import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram.CurveLut;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram.External;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram.Instruction;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram.Opcode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Pure Java 17 Niagara VectorVM bytecode interpreter matching 26.2 {@code VectorMachine}.
 * Executes compiled UE5 {@code system_spawn}, {@code system_update}, {@code emitter.spawn},
 * and {@code emitter.update} scripts natively with zero hardcoded spawn rates.
 */
public final class DirectVectorVm {
    private static final float EPSILON = 1.0e-8f;
    private static final int CHUNK_SIZE = 256;

    private static final class Register {
        int[] words;
        int offset;
        int stride = 4;

        int get(int index) {
            return words[offset + (stride == 0 ? (index & 3) : index)];
        }

        void set(int index, int value) {
            words[offset + index] = value;
        }

        void bind(int[] words, int offset, int stride) {
            this.words = words;
            this.offset = offset;
            this.stride = stride;
        }
    }

    private final DirectVectorProgram program;
    private final Register[] registers;
    private final int[][] temporary;
    private final int[][] constant;
    private final int[][] masks;
    private final int[] alive;
    private final int[] totals;
    private int nextPersistentId;

    public DirectVectorVm(DirectVectorProgram program) {
        this.program = program;
        this.registers = new Register[Math.max(1, program.registerCount())];
        for (int i = 0; i < registers.length; i++) registers[i] = new Register();
        this.temporary = new int[program.temporaryRegisters][];
        this.constant = new int[program.constantSlots.length][4];
        this.masks = new int[program.maxOutputDatasets][];
        this.alive = new int[program.maxOutputDatasets];
        this.totals = new int[program.maxOutputDatasets];
    }

    public DirectVectorProgram program() { return program; }

    public void resetIds() { nextPersistentId = 0; }

    public void execute(int instances, DirectVectorDataSet[] inputs, DirectVectorDataSet[] outputs,
                        int outputOffset, byte[] constants, SourceRandomStream rng) {
        if (instances <= 0) return;
        Arrays.fill(totals, 0);
        ByteBuffer cb = ByteBuffer.wrap(constants).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < constant.length; i++) {
            int off = program.constantSlots[i] * 4;
            int w = (off >= 0 && off + 4 <= constants.length) ? cb.getInt(off) : 0;
            Arrays.fill(constant[i], w);
        }
        for (DirectVectorDataSet out : outputs) {
            out.reserve(outputOffset + instances + 4);
        }

        for (int start = 0; start < instances; start += CHUNK_SIZE) {
            int count = Math.min(CHUNK_SIZE, instances - start);
            int padded = (count + 3) & ~3;
            prepareRegisters(inputs, outputs, outputOffset, start, padded);
            Arrays.fill(alive, 0);

            for (Instruction ins : program.instructions) {
                int[] args = ins.registers();
                switch (ins.opcode()) {
                    case ACQUIREINDEX -> {
                        int ds = ins.dataset();
                        if (masks[ds] == null || masks[ds].length < padded) masks[ds] = new int[padded];
                        for (int i = 0; i < count; i++) {
                            if (registers[args[0]].get(i) < 0) masks[ds][alive[ds]++] = i;
                        }
                    }
                    case ACQUIRE_ID -> {
                        Register idxReg = registers[args[0]];
                        Register tagReg = registers[args[1]];
                        for (int i = 0; i < count; i++) {
                            idxReg.set(i, nextPersistentId++);
                            tagReg.set(i, 1);
                        }
                    }
                    case UPDATE_ID -> {
                        // Handled at dataset level; keep persistent IDs intact
                    }
                    case OUTPUTDATA_FLOAT, OUTPUTDATA_INT32, OUTPUTDATA_HALF,
                            OUTPUTDATA_FLOAT_FROM_HALF, OUTPUTDATA_HALF_FROM_HALF -> {
                        int ds = ins.dataset();
                        int n = ins.inputCount();
                        Opcode op = ins.opcode();
                        for (int col = 0; col < n; col++) {
                            int srcIdx = args[col];
                            Register src = registers[srcIdx];
                            Register dst = registers[args[n + col]];
                            boolean widen = op == Opcode.OUTPUTDATA_FLOAT_FROM_HALF
                                    || (op != Opcode.OUTPUTDATA_HALF_FROM_HALF && isBroadcastHalfInput(srcIdx));
                            for (int i = 0; i < alive[ds]; i++) {
                                int val = src.get(masks[ds][i]);
                                if (widen) val = bits(HalfFloat.toFloat((short) val));
                                if (op == Opcode.OUTPUTDATA_HALF) val = Short.toUnsignedInt(HalfFloat.fromFloat(floating(val)));
                                dst.set(i, val);
                            }
                        }
                    }
                    case EXTERNAL_FUNC_CALL -> executeExternal(ins, args, count);
                    case RANDOM, RANDOMI, RANDOM_ADD, RANDOM_2X -> executeRandom(ins, args, padded, rng);
                    case MUL_2X, B2I_2X -> executeDuplicate(ins, args, padded);
                    case RCP, RSQ, EXP, EXP2, LOG, LOG2, SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2,
                            POW, FMOD, FMOD_ADD -> executeMath(ins, args, padded);
                    case SIN_COS -> {
                        Register in = registers[args[0]], sinR = registers[args[1]], cosR = registers[args[2]];
                        for (int i = 0; i < padded; i++) {
                            double v = floating(in.get(i));
                            sinR.set(i, bits((float) Math.sin(v)));
                            cosR.set(i, bits((float) Math.cos(v)));
                        }
                    }
                    case EXEC_INDEX, EXEC_INDEXF, EXEC_INDEX_ADDI -> {
                        Register dst = registers[args[args.length - 1]];
                        for (int i = 0; i < padded; i++) {
                            int idx = (padded == 4 ? 0 : start) + i;
                            dst.set(i, ins.opcode() == Opcode.EXEC_INDEXF ? bits((float) idx)
                                    : ins.opcode() == Opcode.EXEC_INDEX_ADDI ? idx + registers[args[0]].get(i) : idx);
                        }
                    }
                    default -> executeArithmetic(ins, args, padded);
                }
            }
            for (int ds = 0; ds < totals.length; ds++) totals[ds] += alive[ds];
        }
        for (int ds = 0; ds < outputs.length; ds++) {
            outputs[ds].count(outputOffset + (ds < totals.length ? totals[ds] : 0));
        }
    }

    private void prepareRegisters(DirectVectorDataSet[] inputs, DirectVectorDataSet[] outputs,
                                  int outputOffset, int start, int padded) {
        int cursor = 0;
        for (int i = 0; i < temporary.length; i++) {
            if (temporary[i] == null || temporary[i].length < padded) temporary[i] = new int[padded];
            registers[cursor++].bind(temporary[i], 0, 4);
        }
        for (int[] c : constant) {
            registers[cursor++].bind(c, 0, 0);
        }
        int inputBase = cursor;
        for (DirectVectorProgram.Input in : program.inputs) {
            int dsIdx = in.dataset();
            int[] words;
            if (dsIdx >= 0 && dsIdx < inputs.length && in.component() < inputs[dsIdx].componentCount(in.type())) {
                words = inputs[dsIdx].component(in.type(), in.component());
            } else {
                words = new int[Math.max(4, start + padded)];
            }
            if (in.constant()) {
                Register r = registers[cursor++];
                if (r.words == null || r.words.length != 4) r.words = new int[4];
                Arrays.fill(r.words, words.length > 0 ? words[0] : 0);
                r.offset = 0;
                r.stride = 0;
            } else {
                registers[cursor++].bind(words, start, 4);
            }
        }
        for (int i = 0; i < program.inputs.size(); i++) {
            Register r = registers[inputBase + i];
            registers[cursor++].bind(r.words, r.offset, r.stride);
        }
        for (DirectVectorProgram.Output out : program.outputs) {
            if (out.component() == 0xFFFF || out.dataset() >= outputs.length
                    || out.component() >= outputs[out.dataset()].componentCount(out.type())) {
                Register r = registers[cursor++];
                if (r.words == null || r.words.length < padded) r.words = new int[padded];
                r.offset = 0;
                r.stride = 4;
            } else {
                registers[cursor++].bind(
                        outputs[out.dataset()].component(out.type(), out.component()),
                        outputOffset + totals[out.dataset()], 4);
            }
        }
    }

    private boolean isBroadcastHalfInput(int reg) {
        int idx = reg - temporary.length - constant.length;
        int n = program.inputs.size();
        if (idx >= n) idx -= n;
        if (idx < 0 || idx >= n) return false;
        DirectVectorProgram.Input in = program.inputs.get(idx);
        return in.constant() && in.type() == 2;
    }

    private void executeExternal(Instruction ins, int[] args, int count) {
        if (ins.function() < 0 || ins.function() >= program.external.size()) return;
        External ext = program.external.get(ins.function());
        List<CurveLut> curves = ext.curves();
        if (!curves.isEmpty() && ext.inputs() >= 1 && ext.outputs() >= 1) {
            Register inReg = registers[args[0]];
            int outCount = Math.min(ext.outputs(), curves.size());
            for (int row = 0; row < count; row++) {
                float x = floating(inReg.get(row));
                for (int o = 0; o < outCount; o++) {
                    int outArg = args[ext.inputs() + o];
                    if (outArg != 0xFFFF) registers[outArg].set(row, bits(curves.get(o).sample(x)));
                }
            }
            return;
        }
        if ("platform_set".equals(ext.interfaceType()) && args.length > 0 && args[0] != 0xFFFF) {
            for (int row = 0; row < count; row++) registers[args[0]].set(row, -1);
            return;
        }
        // Safe zero/default outputs for unsupported optional scene/audio/mesh DIs
        for (int o = 0; o < ext.outputs(); o++) {
            int argIdx = ext.inputs() + o;
            if (argIdx < args.length && args[argIdx] != 0xFFFF) {
                Register dst = registers[args[argIdx]];
                for (int row = 0; row < count; row++) dst.set(row, 0);
            }
        }
    }

    private void executeRandom(Instruction ins, int[] args, int padded, SourceRandomStream rng) {
        Opcode op = ins.opcode();
        for (int i = 0; i < padded; i++) {
            float scale = floating(registers[args[0]].get(i));
            float r1 = rng.fraction() * scale;
            if (op == Opcode.RANDOM_ADD) r1 += floating(registers[args[1]].get(i));
            registers[args[ins.inputCount()]].set(i, op == Opcode.RANDOMI ? toInt(r1) : bits(r1));
            if (op == Opcode.RANDOM_2X) {
                registers[args[2]].set(i, bits(rng.fraction() * scale));
            }
        }
    }

    private void executeDuplicate(Instruction ins, int[] args, int padded) {
        int n = ins.inputCount();
        Register first = registers[args[n]], second = registers[args[n + 1]];
        for (int i = 0; i < padded; i++) {
            int left = registers[args[0]].get(i);
            int val = ins.opcode() == Opcode.MUL_2X
                    ? bits(floating(left) * floating(registers[args[1]].get(i))) : (left & 1);
            first.set(i, val);
            second.set(i, val);
        }
    }

    private void executeMath(Instruction ins, int[] args, int padded) {
        Register a = registers[args[0]];
        Register b = registers[args[ins.inputCount() >= 2 ? 1 : 0]];
        Register dst = registers[args[ins.inputCount()]];
        Opcode op = ins.opcode();
        for (int i = 0; i < padded; i++) {
            float x = floating(a.get(i));
            float y = floating(b.get(i));
            float res = switch (op) {
                case RCP -> Math.abs(x) > EPSILON ? 1f / x : 0f;
                case RSQ -> x > EPSILON ? (float) (1.0 / Math.sqrt(x)) : 0f;
                case EXP -> (float) Math.exp(x);
                case EXP2 -> (float) Math.pow(2.0, x);
                case LOG -> x > EPSILON ? (float) Math.log(x) : 0f;
                case LOG2 -> x > EPSILON ? (float) (Math.log(x) / Math.log(2.0)) : 0f;
                case SIN -> (float) Math.sin(x);
                case COS -> (float) Math.cos(x);
                case TAN -> (float) Math.tan(x);
                case ASIN -> (float) Math.asin(Math.max(-1f, Math.min(1f, x)));
                case ACOS -> (float) Math.acos(Math.max(-1f, Math.min(1f, x)));
                case ATAN -> (float) Math.atan(x);
                case ATAN2 -> (float) Math.atan2(x, y);
                case POW -> (float) Math.pow(Math.max(0f, x), y);
                case FMOD -> Math.abs(y) > EPSILON ? (x % y) : 0f;
                case FMOD_ADD -> (Math.abs(y) > EPSILON ? (x % y) : 0f) + floating(registers[args[2]].get(i));
                default -> 0f;
            };
            dst.set(i, bits(Float.isFinite(res) ? res : 0f));
        }
    }

    private void executeArithmetic(Instruction ins, int[] args, int padded) {
        int n = ins.inputCount();
        Register dst = registers[args[n]];
        Opcode op = ins.opcode();
        for (int row = 0; row < padded; row++) {
            int a = n > 0 ? registers[args[0]].get(row) : 0;
            int b = n > 1 ? registers[args[1]].get(row) : 0;
            int c = n > 2 ? registers[args[2]].get(row) : 0;
            int d = n > 3 ? registers[args[3]].get(row) : 0;
            int e = n > 4 ? registers[args[4]].get(row) : 0;
            dst.set(row, evaluate(op, a, b, c, d, e));
        }
    }

    private static int evaluate(Opcode op, int a, int b, int c, int d, int e) {
        float x = floating(a), y = floating(b), z = floating(c), w = floating(d), v = floating(e);
        return switch (op) {
            case ADD -> bits(x + y);
            case SUB -> bits(x - y);
            case MUL -> bits(x * y);
            case DIV -> bits(divide(x, y));
            case MAD -> bits(x * y + z);
            case LERP -> bits(y * z + x * (1f - z));
            case SQRT -> bits(x > EPSILON ? (float) Math.sqrt(x) : 0f);
            case NEG -> bits(0f - x);
            case ABS -> a & 0x7FFFFFFF;
            case CEIL -> bits((float) Math.ceil(x));
            case FLOOR -> bits((float) Math.floor(x));
            case TRUNC -> bits(truncate(x));
            case FRAC -> bits(x - truncate(x));
            case ROUND -> bits((float) Math.rint(x));
            case SIGN -> bits(x >= 0f ? 1f : -1f);
            case STEP -> bits(y - x >= 0f ? 1f : 0f);
            case CLAMP -> bits(Math.min(Math.max(x, y), z));
            case NOISE, NOISE2D, NOISE3D -> bits(0f);
            case MIN -> bits(Math.min(x, y));
            case MAX -> bits(Math.max(x, y));
            case CMPLT -> mask(x < y);
            case CMPLE -> mask(x <= y);
            case CMPGT -> mask(x > y);
            case CMPGE -> mask(x >= y);
            case CMPEQ -> mask(x == y);
            case CMPNEQ -> mask(x != y);
            case SELECT -> select(a, b, c);
            case ADDI -> a + b;
            case SUBI -> a - b;
            case MULI -> a * b;
            case DIVI -> b == 0 ? 0 : (a == Integer.MIN_VALUE && b == -1 ? Integer.MAX_VALUE : a / b);
            case CLAMPI -> Math.min(Math.max(a, b), c);
            case MINI -> Math.min(a, b);
            case MAXI -> Math.max(a, b);
            case ABSI -> Math.abs(a);
            case NEGI -> -a;
            case SIGNI -> a >= 0 ? 1 : -1;
            case CMPLTI -> mask(a < b);
            case CMPLEI -> mask(a <= b);
            case CMPGTI -> mask(a > b);
            case CMPGEI -> mask(a >= b);
            case CMPEQI -> mask(a == b);
            case CMPNEQI -> mask(a != b);
            case BIT_AND, LOGIC_AND -> a & b;
            case BIT_OR, LOGIC_OR -> a | b;
            case BIT_XOR, LOGIC_XOR -> a ^ b;
            case BIT_NOT, LOGIC_NOT -> ~a;
            case BIT_LSHIFT -> a << b;
            case BIT_RSHIFT -> a >>> b;
            case F2I -> toInt(x);
            case I2F -> bits((float) a);
            case B2I -> a & 1;
            case B2F -> a & 0x3F800000;
            case F2B -> mask(x > 0f);
            case I2B -> mask(a > 0);
            case FASI, IASF -> a;
            case HALF_TO_FLOAT -> bits(HalfFloat.toFloat((short) a));
            case CMPLT_SELECT -> select(mask(x < y), c, d);
            case CMPLE_SELECT -> select(mask(x <= y), c, d);
            case CMPEQ_SELECT -> select(mask(x == y), c, d);
            case CMPLTI_SELECT -> select(mask(a < b), c, d);
            case CMPLEI_SELECT -> select(mask(a <= b), c, d);
            case CMPEQI_SELECT -> select(mask(a == b), c, d);
            case CMPLT_LOGIC_AND -> mask(x < y) & c;
            case CMPLE_LOGIC_AND -> mask(x <= y) & c;
            case CMPGT_LOGIC_AND -> mask(x > y) & c;
            case CMPGE_LOGIC_AND -> mask(x >= y) & c;
            case CMPEQ_LOGIC_AND -> mask(x == y) & c;
            case CMPNEQ_LOGIC_AND -> mask(x != y) & c;
            case CMPLTI_LOGIC_AND -> mask(a < b) & c;
            case CMPLEI_LOGIC_AND -> mask(a <= b) & c;
            case CMPGTI_LOGIC_AND -> mask(a > b) & c;
            case CMPGEI_LOGIC_AND -> mask(a >= b) & c;
            case CMPEQI_LOGIC_AND -> mask(a == b) & c;
            case CMPNEI_LOGIC_AND -> mask(a != b) & c;
            case CMPLT_LOGIC_OR -> mask(x < y) | c;
            case CMPLE_LOGIC_OR -> mask(x <= y) | c;
            case CMPGT_LOGIC_OR -> mask(x > y) | c;
            case CMPGE_LOGIC_OR -> mask(x >= y) | c;
            case CMPEQ_LOGIC_OR -> mask(x == y) | c;
            case CMPNEQ_LOGIC_OR -> mask(x != y) | c;
            case CMPLTI_LOGIC_OR -> mask(a < b) | c;
            case CMPLEI_LOGIC_OR -> mask(a <= b) | c;
            case CMPGTI_LOGIC_OR -> mask(a > b) | c;
            case CMPGEI_LOGIC_OR -> mask(a >= b) | c;
            case CMPEQI_LOGIC_OR -> mask(a == b) | c;
            case CMPNEI_LOGIC_OR -> mask(a != b) | c;
            case MAD_ADD -> bits((x * y + z) + w);
            case MAD_SUB0 -> bits((x * y + z) - w);
            case MAD_SUB1 -> bits(w - (x * y + z));
            case MAD_MUL -> bits((x * y + z) * w);
            case MAD_SQRT -> bits((float) Math.sqrt(Math.max(0f, x * y + z)));
            case MAD_MAD0 -> bits(w * v + (x * y + z));
            case MAD_MAD1 -> bits((x * y + z) * w + v);
            case MUL_MAD0 -> bits((x * y) * z + w);
            case MUL_MAD1 -> bits(z * w + (x * y));
            case MUL_ADD -> bits(x * y + z);
            case MUL_SUB0 -> bits(x * y - z);
            case MUL_SUB1 -> bits(z - x * y);
            case MUL_MUL -> bits((x * y) * z);
            case MUL_MAX -> bits(Math.max(x * y, z));
            case ADD_MAD1 -> bits(z * w + (x + y));
            case ADD_ADD -> bits((x + y) + z);
            case SUB_CMPLT1 -> mask(z < x - y);
            case SUB_MUL -> bits((x - y) * z);
            case SUB_NEG -> bits(0f - (x - y));
            case DIV_MAD0 -> bits(divide(x, y) * z + w);
            case DIV_MUL -> bits(divide(x, y) * z);
            case MULI_ADDI -> a * b + c;
            case ADDI_BIT_RSHIFT -> (a + b) >>> c;
            case ADDI_MULI -> (a + b) * c;
            case I2F_DIV0 -> bits(divide((float) a, y));
            case I2F_DIV1 -> bits(divide(y, (float) a));
            case I2F_MUL -> bits((float) a * y);
            case I2F_MAD0 -> bits((float) a * y + z);
            case I2F_MAD1 -> bits(x * y + (float) c);
            case DIV_F2I -> toInt(divide(x, y));
            case F2I_MAXI -> Math.max(toInt(x), b);
            case F2I_SELECT1 -> select(a, toInt(y), c);
            case F2I_ADDI -> toInt(x) + b;
            case BIT_AND_I2F -> bits((float) (a & b));
            case BIT_OR_MULI -> (a | b) * c;
            case MAX_F2I -> toInt(Math.max(x, y));
            case SELECT_MUL -> bits(floating(select(a, b, c)) * w);
            case SELECT_ADD -> bits(floating(select(a, b, c)) + w);
            case BIT_RSHIFT_BIT_AND -> (a >>> b) & c;
            case BIT_LSHIFT_BIT_OR -> (a << b) | c;
            case NEG_CMPLT -> mask((0f - x) < y);
            default -> 0;
        };
    }

    private static float divide(float a, float b) { return Math.abs(b) > EPSILON ? a / b : 0f; }
    private static float truncate(float v) { return (float) (v < 0f ? Math.ceil(v) : Math.floor(v)); }
    private static int toInt(float v) { return (v >= -2147483648f && v < 2147483648f) ? (int) v : Integer.MIN_VALUE; }
    private static int select(int mask, int yes, int no) { return (mask & yes) | (~mask & no); }
    private static int mask(boolean v) { return v ? -1 : 0; }
    private static int bits(float v) { return Float.floatToRawIntBits(v); }
    private static float floating(int bits) { return Float.intBitsToFloat(bits); }
}
