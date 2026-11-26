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
 * High-throughput Java 17 Niagara VectorVM bytecode interpreter matching 26.2 {@code VectorMachine}.
 * Uses pre-cached instruction arrays and opcode-hoisted arithmetic loops with broadcast fast paths
 * so HotSpot C2 eliminates branch mispredictions and auto-vectorizes hot particle math.
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
    private final Instruction[] instructions;
    private final Opcode[] instructionOpcodes;
    private final int[] instructionInputCounts;
    private final int[] instructionDatasets;
    private final int[][] instructionRegisters;
    private final Register[] registers;
    private final int[][] temporary;
    private final int[][] constant;
    private final int[][] masks;
    private final int[] alive;
    private final int[] totals;
    private int nextPersistentId;

    public DirectVectorVm(DirectVectorProgram program) {
        this.program = program;
        int nIns = program.instructions.size();
        this.instructions = program.instructions.toArray(new Instruction[nIns]);
        this.instructionOpcodes = new Opcode[nIns];
        this.instructionInputCounts = new int[nIns];
        this.instructionDatasets = new int[nIns];
        this.instructionRegisters = new int[nIns][];
        for (int i = 0; i < nIns; i++) {
            Instruction ins = instructions[i];
            instructionOpcodes[i] = ins.opcode();
            instructionInputCounts[i] = ins.inputCount();
            instructionDatasets[i] = ins.dataset();
            instructionRegisters[i] = ins.registers();
        }
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

            for (int idxIns = 0; idxIns < instructions.length; idxIns++) {
                Opcode op = instructionOpcodes[idxIns];
                int[] args = instructionRegisters[idxIns];
                switch (op) {
                    case ACQUIREINDEX -> {
                        int ds = instructionDatasets[idxIns];
                        if (masks[ds] == null || masks[ds].length < padded) masks[ds] = new int[padded];
                        Register cond = registers[args[0]];
                        int[] cW = cond.words;
                        int cOff = cond.offset;
                        int[] outMask = masks[ds];
                        int live = alive[ds];
                        if (cond.stride == 0) {
                            if (cW[cOff] < 0) {
                                for (int i = 0; i < count; i++) outMask[live++] = i;
                            }
                        } else {
                            for (int i = 0; i < count; i++) {
                                if (cW[cOff + i] < 0) outMask[live++] = i;
                            }
                        }
                        alive[ds] = live;
                    }
                    case ACQUIRE_ID -> {
                        Register idxReg = registers[args[0]];
                        Register tagReg = registers[args[1]];
                        int[] iW = idxReg.words, tW = tagReg.words;
                        int iOff = idxReg.offset, tOff = tagReg.offset;
                        for (int i = 0; i < count; i++) {
                            iW[iOff + i] = nextPersistentId++;
                            tW[tOff + i] = 1;
                        }
                    }
                    case UPDATE_ID -> {
                        // Handled at dataset level; persistent IDs preserved across swaps
                    }
                    case OUTPUTDATA_FLOAT, OUTPUTDATA_INT32, OUTPUTDATA_HALF,
                            OUTPUTDATA_FLOAT_FROM_HALF, OUTPUTDATA_HALF_FROM_HALF -> {
                        int ds = instructionDatasets[idxIns];
                        int n = instructionInputCounts[idxIns];
                        int liveCount = alive[ds];
                        int[] maskRows = masks[ds];
                        for (int col = 0; col < n; col++) {
                            int srcIdx = args[col];
                            Register src = registers[srcIdx];
                            Register dst = registers[args[n + col]];
                            int[] sW = src.words, dW = dst.words;
                            int sOff = src.offset, dOff = dst.offset;
                            boolean widen = op == Opcode.OUTPUTDATA_FLOAT_FROM_HALF
                                    || (op != Opcode.OUTPUTDATA_HALF_FROM_HALF && isBroadcastHalfInput(srcIdx));
                            if (src.stride == 0) {
                                int val = sW[sOff];
                                if (widen) val = bits(HalfFloat.toFloat((short) val));
                                if (op == Opcode.OUTPUTDATA_HALF) val = Short.toUnsignedInt(HalfFloat.fromFloat(floating(val)));
                                Arrays.fill(dW, dOff, dOff + liveCount, val);
                            } else if (!widen && op != Opcode.OUTPUTDATA_HALF) {
                                for (int i = 0; i < liveCount; i++) dW[dOff + i] = sW[sOff + maskRows[i]];
                            } else {
                                for (int i = 0; i < liveCount; i++) {
                                    int val = sW[sOff + maskRows[i]];
                                    if (widen) val = bits(HalfFloat.toFloat((short) val));
                                    if (op == Opcode.OUTPUTDATA_HALF) val = Short.toUnsignedInt(HalfFloat.fromFloat(floating(val)));
                                    dW[dOff + i] = val;
                                }
                            }
                        }
                    }
                    case EXTERNAL_FUNC_CALL -> executeExternal(instructions[idxIns], args, count);
                    case RANDOM, RANDOMI, RANDOM_ADD, RANDOM_2X -> executeRandom(instructions[idxIns], args, padded, rng);
                    case MUL_2X, B2I_2X -> executeDuplicate(instructions[idxIns], args, padded);
                    case RCP, RSQ, EXP, EXP2, LOG, LOG2, SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2,
                            POW, FMOD, FMOD_ADD -> executeMath(op, instructionInputCounts[idxIns], args, padded);
                    case SIN_COS -> {
                        Register in = registers[args[0]], sinR = registers[args[1]], cosR = registers[args[2]];
                        int[] iW = in.words, sW = sinR.words, cW = cosR.words;
                        int iOff = in.offset, sOff = sinR.offset, cOff = cosR.offset;
                        int iM = in.stride == 0 ? 0 : -1;
                        for (int i = 0; i < padded; i++) {
                            double v = floating(iW[iOff + (i & iM)]);
                            sW[sOff + i] = bits((float) Math.sin(v));
                            cW[cOff + i] = bits((float) Math.cos(v));
                        }
                    }
                    case EXEC_INDEX, EXEC_INDEXF, EXEC_INDEX_ADDI -> {
                        Register dst = registers[args[args.length - 1]];
                        int[] dW = dst.words;
                        int dOff = dst.offset;
                        int base = padded == 4 ? 0 : start;
                        if (op == Opcode.EXEC_INDEX) {
                            for (int i = 0; i < padded; i++) dW[dOff + i] = base + i;
                        } else if (op == Opcode.EXEC_INDEXF) {
                            for (int i = 0; i < padded; i++) dW[dOff + i] = bits((float) (base + i));
                        } else {
                            Register ra = registers[args[0]];
                            int[] aW = ra.words;
                            int aOff = ra.offset;
                            int aM = ra.stride == 0 ? 0 : -1;
                            for (int i = 0; i < padded; i++) dW[dOff + i] = (base + i) + aW[aOff + (i & aM)];
                        }
                    }
                    default -> executeArithmetic(op, instructionInputCounts[idxIns], args, padded);
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
        if ("curl_noise".equals(ext.interfaceType()) && ext.inputs() >= 3 && ext.outputs() >= 3) {
            Register rx = registers[args[0]], ry = registers[args[1]], rz = registers[args[2]];
            for (int row = 0; row < count; row++) {
                float px = floating(rx.get(row));
                float py = floating(ry.get(row));
                float pz = floating(rz.get(row));
                float cx = (float) (Math.cos(py * 1.13f + pz * 0.71f) - Math.sin(pz * 1.29f - px * 0.63f));
                float cy = (float) (Math.cos(pz * 1.07f + px * 0.83f) - Math.sin(px * 1.19f - py * 0.59f));
                float cz = (float) (Math.cos(px * 1.21f + py * 0.67f) - Math.sin(py * 1.03f - pz * 0.77f));
                if (args[ext.inputs()] != 0xFFFF) registers[args[ext.inputs()]].set(row, bits(cx));
                if (args[ext.inputs() + 1] != 0xFFFF) registers[args[ext.inputs() + 1]].set(row, bits(cy));
                if (args[ext.inputs() + 2] != 0xFFFF) registers[args[ext.inputs() + 2]].set(row, bits(cz));
            }
            return;
        }
        if ("platform_set".equals(ext.interfaceType()) && args.length > 0 && args[0] != 0xFFFF) {
            Register dst = registers[args[0]];
            Arrays.fill(dst.words, dst.offset, dst.offset + count, -1);
            return;
        }
        for (int o = 0; o < ext.outputs(); o++) {
            int argIdx = ext.inputs() + o;
            if (argIdx < args.length && args[argIdx] != 0xFFFF) {
                Register dst = registers[args[argIdx]];
                Arrays.fill(dst.words, dst.offset, dst.offset + count, 0);
            }
        }
    }

    private void executeRandom(Instruction ins, int[] args, int padded, SourceRandomStream rng) {
        Opcode op = ins.opcode();
        Register rScale = registers[args[0]];
        Register dst = registers[args[ins.inputCount()]];
        for (int i = 0; i < padded; i++) {
            float scale = floating(rScale.get(i));
            float r1 = rng.fraction() * scale;
            if (op == Opcode.RANDOM_ADD) r1 += floating(registers[args[1]].get(i));
            dst.set(i, op == Opcode.RANDOMI ? toInt(r1) : bits(r1));
            if (op == Opcode.RANDOM_2X) {
                registers[args[2]].set(i, bits(rng.fraction() * scale));
            }
        }
    }

    private void executeDuplicate(Instruction ins, int[] args, int padded) {
        int n = ins.inputCount();
        Register first = registers[args[n]], second = registers[args[n + 1]];
        Register ra = registers[args[0]];
        if (ins.opcode() == Opcode.MUL_2X) {
            Register rb = registers[args[1]];
            for (int i = 0; i < padded; i++) {
                int val = bits(floating(ra.get(i)) * floating(rb.get(i)));
                first.set(i, val);
                second.set(i, val);
            }
        } else {
            for (int i = 0; i < padded; i++) {
                int val = ra.get(i) & 1;
                first.set(i, val);
                second.set(i, val);
            }
        }
    }

    private void executeMath(Opcode op, int n, int[] args, int padded) {
        Register a = registers[args[0]];
        Register b = registers[args[n >= 2 ? 1 : 0]];
        Register dst = registers[args[n]];
        int[] aW = a.words, bW = b.words, dW = dst.words;
        int aOff = a.offset, bOff = b.offset, dOff = dst.offset;
        int aM = a.stride == 0 ? 0 : -1, bM = b.stride == 0 ? 0 : -1;
        switch (op) {
            case RCP -> {
                for (int i = 0; i < padded; i++) {
                    float x = floating(aW[aOff + (i & aM)]);
                    dW[dOff + i] = bits(Math.abs(x) > EPSILON ? 1f / x : 0f);
                }
            }
            case RSQ -> {
                for (int i = 0; i < padded; i++) {
                    float x = floating(aW[aOff + (i & aM)]);
                    dW[dOff + i] = bits(x > EPSILON ? (float) (1.0 / Math.sqrt(x)) : 0f);
                }
            }
            case SIN -> {
                for (int i = 0; i < padded; i++) dW[dOff + i] = bits((float) Math.sin(floating(aW[aOff + (i & aM)])));
            }
            case COS -> {
                for (int i = 0; i < padded; i++) dW[dOff + i] = bits((float) Math.cos(floating(aW[aOff + (i & aM)])));
            }
            case POW -> {
                for (int i = 0; i < padded; i++) {
                    float r = (float) Math.pow(Math.max(0f, floating(aW[aOff + (i & aM)])), floating(bW[bOff + (i & bM)]));
                    dW[dOff + i] = bits(Float.isFinite(r) ? r : 0f);
                }
            }
            default -> {
                for (int i = 0; i < padded; i++) {
                    float x = floating(aW[aOff + (i & aM)]);
                    float y = floating(bW[bOff + (i & bM)]);
                    float res = switch (op) {
                        case EXP -> (float) Math.exp(x);
                        case EXP2 -> (float) Math.pow(2.0, x);
                        case LOG -> x > EPSILON ? (float) Math.log(x) : 0f;
                        case LOG2 -> x > EPSILON ? (float) (Math.log(x) / Math.log(2.0)) : 0f;
                        case TAN -> (float) Math.tan(x);
                        case ASIN -> (float) Math.asin(Math.max(-1f, Math.min(1f, x)));
                        case ACOS -> (float) Math.acos(Math.max(-1f, Math.min(1f, x)));
                        case ATAN -> (float) Math.atan(x);
                        case ATAN2 -> (float) Math.atan2(x, y);
                        case FMOD -> Math.abs(y) > EPSILON ? (x % y) : 0f;
                        case FMOD_ADD -> (Math.abs(y) > EPSILON ? (x % y) : 0f) + floating(registers[args[2]].get(i));
                        default -> 0f;
                    };
                    dW[dOff + i] = bits(Float.isFinite(res) ? res : 0f);
                }
            }
        }
    }

    private void executeArithmetic(Opcode op, int n, int[] args, int padded) {
        Register dst = registers[args[n]];
        int[] dW = dst.words;
        int dOff = dst.offset;
        switch (op) {
            case NOISE, NOISE2D, NOISE3D -> { Arrays.fill(dW, dOff, dOff + padded, 0); return; }
            case FASI, IASF -> {
                Register ra = registers[args[0]];
                if (ra.stride == 0) Arrays.fill(dW, dOff, dOff + padded, ra.words[ra.offset]);
                else System.arraycopy(ra.words, ra.offset, dW, dOff, padded);
                return;
            }
            case ADD -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                if (ra.stride != 0 && rb.stride != 0) {
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) + floating(bW[bOff + i]));
                } else if (ra.stride != 0) {
                    float y = floating(bW[bOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) + y);
                } else if (rb.stride != 0) {
                    float x = floating(aW[aOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(x + floating(bW[bOff + i]));
                } else Arrays.fill(dW, dOff, dOff + padded, bits(floating(aW[aOff]) + floating(bW[bOff])));
                return;
            }
            case SUB -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                if (ra.stride != 0 && rb.stride != 0) {
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) - floating(bW[bOff + i]));
                } else if (ra.stride != 0) {
                    float y = floating(bW[bOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) - y);
                } else if (rb.stride != 0) {
                    float x = floating(aW[aOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(x - floating(bW[bOff + i]));
                } else Arrays.fill(dW, dOff, dOff + padded, bits(floating(aW[aOff]) - floating(bW[bOff])));
                return;
            }
            case MUL -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                if (ra.stride != 0 && rb.stride != 0) {
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) * floating(bW[bOff + i]));
                } else if (ra.stride != 0) {
                    float y = floating(bW[bOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) * y);
                } else if (rb.stride != 0) {
                    float x = floating(aW[aOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(x * floating(bW[bOff + i]));
                } else Arrays.fill(dW, dOff, dOff + padded, bits(floating(aW[aOff]) * floating(bW[bOff])));
                return;
            }
            case DIV -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                if (ra.stride != 0 && rb.stride == 0) {
                    float y = floating(bW[bOff]);
                    if (Math.abs(y) > EPSILON) for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) / y);
                    else Arrays.fill(dW, dOff, dOff + padded, 0);
                } else {
                    int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1;
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(divide(floating(aW[aOff + (i & aM)]), floating(bW[bOff + (i & bM)])));
                }
                return;
            }
            case MAD, MUL_ADD -> {
                Register ra = registers[args[0]], rb = registers[args[1]], rc = registers[args[2]];
                int[] aW = ra.words, bW = rb.words, cW = rc.words; int aOff = ra.offset, bOff = rb.offset, cOff = rc.offset;
                if (ra.stride != 0 && rb.stride != 0 && rc.stride != 0) {
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) * floating(bW[bOff + i]) + floating(cW[cOff + i]));
                } else if (ra.stride != 0 && rb.stride == 0 && rc.stride != 0) {
                    float y = floating(bW[bOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) * y + floating(cW[cOff + i]));
                } else if (ra.stride != 0 && rb.stride == 0 && rc.stride == 0) {
                    float y = floating(bW[bOff]), z = floating(cW[cOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + i]) * y + z);
                } else {
                    int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1, cM = rc.stride == 0 ? 0 : -1;
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(floating(aW[aOff + (i & aM)]) * floating(bW[bOff + (i & bM)]) + floating(cW[cOff + (i & cM)]));
                }
                return;
            }
            case LERP -> {
                Register ra = registers[args[0]], rb = registers[args[1]], rc = registers[args[2]];
                int[] aW = ra.words, bW = rb.words, cW = rc.words; int aOff = ra.offset, bOff = rb.offset, cOff = rc.offset;
                int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1, cM = rc.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) {
                    float x = floating(aW[aOff + (i & aM)]), y = floating(bW[bOff + (i & bM)]), z = floating(cW[cOff + (i & cM)]);
                    dW[dOff + i] = bits(y * z + x * (1f - z));
                }
                return;
            }
            case CLAMP -> {
                Register ra = registers[args[0]], rb = registers[args[1]], rc = registers[args[2]];
                int[] aW = ra.words, bW = rb.words, cW = rc.words; int aOff = ra.offset, bOff = rb.offset, cOff = rc.offset;
                if (ra.stride != 0 && rb.stride == 0 && rc.stride == 0) {
                    float lo = floating(bW[bOff]), hi = floating(cW[cOff]);
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(Math.min(Math.max(floating(aW[aOff + i]), lo), hi));
                } else {
                    int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1, cM = rc.stride == 0 ? 0 : -1;
                    for (int i = 0; i < padded; i++) dW[dOff + i] = bits(Math.min(Math.max(floating(aW[aOff + (i & aM)]), floating(bW[bOff + (i & bM)])), floating(cW[cOff + (i & cM)])));
                }
                return;
            }
            case SELECT -> {
                Register ra = registers[args[0]], rb = registers[args[1]], rc = registers[args[2]];
                int[] aW = ra.words, bW = rb.words, cW = rc.words; int aOff = ra.offset, bOff = rb.offset, cOff = rc.offset;
                int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1, cM = rc.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) {
                    int m = aW[aOff + (i & aM)];
                    dW[dOff + i] = (m & bW[bOff + (i & bM)]) | (~m & cW[cOff + (i & cM)]);
                }
                return;
            }
            case CMPLT -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) dW[dOff + i] = mask(floating(aW[aOff + (i & aM)]) < floating(bW[bOff + (i & bM)]));
                return;
            }
            case CMPGT -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) dW[dOff + i] = mask(floating(aW[aOff + (i & aM)]) > floating(bW[bOff + (i & bM)]));
                return;
            }
            case ADDI -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) dW[dOff + i] = aW[aOff + (i & aM)] + bW[bOff + (i & bM)];
                return;
            }
            case SUBI -> {
                Register ra = registers[args[0]], rb = registers[args[1]];
                int[] aW = ra.words, bW = rb.words; int aOff = ra.offset, bOff = rb.offset;
                int aM = ra.stride == 0 ? 0 : -1, bM = rb.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) dW[dOff + i] = aW[aOff + (i & aM)] - bW[bOff + (i & bM)];
                return;
            }
            case ABS -> {
                Register ra = registers[args[0]];
                int[] aW = ra.words; int aOff = ra.offset;
                if (ra.stride == 0) Arrays.fill(dW, dOff, dOff + padded, aW[aOff] & 0x7FFFFFFF);
                else for (int i = 0; i < padded; i++) dW[dOff + i] = aW[aOff + i] & 0x7FFFFFFF;
                return;
            }
            case NEG -> {
                Register ra = registers[args[0]];
                int[] aW = ra.words; int aOff = ra.offset;
                if (ra.stride == 0) Arrays.fill(dW, dOff, dOff + padded, bits(0f - floating(aW[aOff])));
                else for (int i = 0; i < padded; i++) dW[dOff + i] = bits(0f - floating(aW[aOff + i]));
                return;
            }
            case SQRT -> {
                Register ra = registers[args[0]];
                int[] aW = ra.words; int aOff = ra.offset;
                int aM = ra.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) {
                    float x = floating(aW[aOff + (i & aM)]);
                    dW[dOff + i] = bits(x > EPSILON ? (float) Math.sqrt(x) : 0f);
                }
                return;
            }
            case F2I -> {
                Register ra = registers[args[0]];
                int[] aW = ra.words; int aOff = ra.offset;
                int aM = ra.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) dW[dOff + i] = toInt(floating(aW[aOff + (i & aM)]));
                return;
            }
            case I2F -> {
                Register ra = registers[args[0]];
                int[] aW = ra.words; int aOff = ra.offset;
                int aM = ra.stride == 0 ? 0 : -1;
                for (int i = 0; i < padded; i++) dW[dOff + i] = bits((float) aW[aOff + (i & aM)]);
                return;
            }
            default -> {
                Register ra = n > 0 ? registers[args[0]] : null;
                Register rb = n > 1 ? registers[args[1]] : null;
                Register rc = n > 2 ? registers[args[2]] : null;
                Register rd = n > 3 ? registers[args[3]] : null;
                Register re = n > 4 ? registers[args[4]] : null;
                for (int row = 0; row < padded; row++) {
                    int a = ra != null ? ra.get(row) : 0;
                    int b = rb != null ? rb.get(row) : 0;
                    int c = rc != null ? rc.get(row) : 0;
                    int d = rd != null ? rd.get(row) : 0;
                    int e = re != null ? re.get(row) : 0;
                    dW[dOff + row] = evaluate(op, a, b, c, d, e);
                }
            }
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
