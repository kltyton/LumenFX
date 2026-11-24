package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsFieldState;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborGrid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraRasterizationGrid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraVelocityGrid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.water.NiagaraWaterState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.NiagaraGeometryCollectionState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.NiagaraChaosDestructionState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.table.NiagaraDataTable;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSpline;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraSceneCapture;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer.Operation;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.dynamic.NiagaraDynamicMesh;

import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformSet;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraScalabilityJson;
import com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction;
import com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode;
import com.kltyton.lumenfx.effect.noise.NiagaraCurlNoise;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererInformation;
import com.kltyton.lumenfx.effect.render.sprite.SpriteRendererInformation;
import com.kltyton.lumenfx.client.resource.niagara.SpriteRendererInfoJson;
import com.kltyton.lumenfx.client.resource.niagara.MeshRendererInfoJson;
import com.kltyton.lumenfx.client.simulation.niagara.field.NiagaraVectorFieldData;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraCollisionState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraConsoleVariable;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.ribbon.NiagaraRibbonShape;
import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld.Hit;
import com.kltyton.lumenfx.client.simulation.niagara.curve.NativeCurveSampler;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Executes saved optimized instructions in instruction-major, four-lane chunk order. */
public final class VectorMachine {
    static final class InterfaceBindingException extends IllegalStateException {
        InterfaceBindingException(String message) { super(message); }
    }

    private static final float EPSILON = 1.e-8f;
    private static final class Register {
        int[] words;
        int offset, stride = 4;
        int get(int index) { return words[offset + (stride == 0 ? index & 3 : index)]; }
        void set(int index, int value) { words[offset + index] = value; }
        void bind(int[] words, int offset, int stride) { this.words = words; this.offset = offset; this.stride = stride; }
    }

    private final VectorProgram program;
    private final NiagaraInterfaces interfaces;
    private final String emitterOwner;
    private final boolean eventParticleRead;
    private Register[] registers;
    private @Nullable Register[] laneRegisters;
    private final int[] constantSlots;
    private final int[][] instructionRegisters;
    private final int constantBytes;
    private final int[][] temporary;
    private final int[][] constant;
    private @Nullable int[][] rowConstants;
    private record SharedRows(List<VectorMachine> owners, List<byte[]> constants, int[] outputs) { }
    private final int[][] masks;
    private final int[] alive, totals;
    private final boolean randomInstructions;
    private int[] nativeResult = new int[16], nativeSecondary = new int[16];
    private int[] curveInputs = new int[16];
    private int[] defaultInputOffsets = new int[0], defaultOutputOffsets = new int[0];
    private final float[] randomA = new float[4], randomB = new float[4];
    private final double[] fieldResult = new double[3];
    private final NiagaraCurlNoise[] curlNoise;
    private final MeshRendererInformation[] meshInformation;
    private final SpriteRendererInformation[] spriteInformation;
    private final NiagaraVectorFieldData[] vectorFields;
    private final NativeCurveSampler[][] richCurves;
    private final NiagaraPlatformSet[] platformSets;
    private final NiagaraObjectPropertyReader.Request[] propertyRequests;
    private final PropertyInterfaceCall[] propertyInterfaceCalls;
    private static final class PropertyInterfaceCall {
        final List<NiagaraPropertyInterface.Output> outputs;
        final boolean systemScript;
        @Nullable NiagaraPropertyInterface owner;
        int @Nullable [] offsets;
        boolean pendingInitialSystemBinding;

        PropertyInterfaceCall(List<NiagaraPropertyInterface.Output> outputs, boolean systemScript) {
            this.outputs = outputs;
            this.systemScript = systemScript;
            this.pendingInitialSystemBinding = systemScript;
        }

        void bind(NiagaraPropertyInterface state) {
            if (owner != state) pendingInitialSystemBinding = systemScript;
            owner = state;
            var initial = state.initialFrame();
            if (initial == null) offsets = null;
            else {
                offsets = (pendingInitialSystemBinding ? initial : state.frame()).bind(outputs);
                pendingInitialSystemBinding = false;
            }
        }

        int[] offsets(NiagaraPropertyInterface state) {
            if (owner != state || offsets == null) {
                if (owner != state) pendingInitialSystemBinding = systemScript;
                owner = state;
                var initial = Objects.requireNonNull(state.initialFrame(), "Niagara Property Interface has not been captured");
                offsets = (pendingInitialSystemBinding ? initial : state.frame()).bind(outputs);
                pendingInitialSystemBinding = false;
            }
            return offsets;
        }
    }
    private final NiagaraConsoleVariable[] consoleRequests;
    private final NiagaraConsoleVariable.Bound[] consoleBindings;
    private final @Nullable NiagaraExternalRandom externalRandom;
    private final VectorProgram.External[] externalBindings;
    private final @Nullable NiagaraDataInterfaceObject[] interfaceObjects;
    private final @Nullable NiagaraDataInterfaceObject[] userPointers;
    private final float[] curlResult = new float[3];
    private @Nullable float[] matrixToQuaternionValues;

    public VectorMachine(VectorProgram program) {
        this(program, null);
    }

    VectorMachine(VectorProgram program, NiagaraInterfaces interfaces) {
        this(program, interfaces, null);
    }

    VectorMachine(VectorProgram program, NiagaraInterfaces interfaces, String emitterOwner) {
        this(program, interfaces, emitterOwner, false);
    }

    VectorMachine(VectorProgram program, NiagaraInterfaces interfaces, String emitterOwner, boolean eventParticleRead) {
        this.program = program;
        this.interfaces = interfaces;
        this.emitterOwner = emitterOwner;
        this.eventParticleRead = eventParticleRead;
        curlNoise = new NiagaraCurlNoise[program.external().size()];
        meshInformation = new MeshRendererInformation[program.external().size()];
        spriteInformation = new SpriteRendererInformation[program.external().size()];
        vectorFields = new NiagaraVectorFieldData[program.external().size()];
        richCurves = new NativeCurveSampler[program.external().size()][];
        platformSets = new NiagaraPlatformSet[program.external().size()];
        propertyRequests = new NiagaraObjectPropertyReader.Request[program.external().size()];
        propertyInterfaceCalls = new PropertyInterfaceCall[program.external().size()];
        consoleRequests = new NiagaraConsoleVariable[program.external().size()];
        consoleBindings = new NiagaraConsoleVariable.Bound[program.external().size()];
        externalRandom = program.external().stream().anyMatch(binding ->
                (binding.interfaceType().equals("static_mesh") || binding.interfaceType().equals("skeletal_mesh"))
                        && staticMeshRandom(binding.name())
                        || binding.interfaceType().equals("data_channel") && channelSpawn(binding.name())
                        || binding.interfaceType().equals("int_distribution") && binding.name().equals("GetRandomValue"))
                ? new NiagaraExternalRandom() : null;
        externalBindings = program.external().toArray(VectorProgram.External[]::new);
        interfaceObjects = new NiagaraDataInterfaceObject[externalBindings.length];
        userPointers = new NiagaraDataInterfaceObject[program.userPointers().count()];
        for (int i = 0; i < externalBindings.length; i++) prepareExternal(i, externalBindings[i]);
        registers = new Register[program.registerCount()];
        constantSlots = program.constantSlots();
        constantBytes = program.constantData().length;
        instructionRegisters = program.instructions().stream().map(Instruction::registers).toArray(int[][]::new);
        Arrays.setAll(registers, i -> new Register());
        temporary = new int[program.temporaryRegisters()][];
        constant = new int[constantSlots.length][4];
        masks = new int[program.maxOutputDatasets()][];
        alive = new int[masks.length];
        totals = new int[masks.length];
        randomInstructions = program.instructions().stream().anyMatch(ins -> switch (ins.opcode()) {
            case RANDOM, RANDOMI, RANDOM_ADD, RANDOM_2X -> true;
            default -> false;
        });
    }

    /** Context Tick resolves the function table before ParameterStore.Tick consumes the DI dirty flag. */
    public void bindInterfaces(NiagaraParameterStore parameters) {
        bindInterfaces(parameters, null);
    }

    public void bindInterfaces(NiagaraParameterStore parameters, @Nullable NiagaraParameterStore instance) {
        var next = externalBindings.clone();
        var objects = interfaceObjects.clone();
        var pointers = new NiagaraDataInterfaceObject[userPointers.length];
        for (var binding : program.userPointers().bindings()) {
            Object value = parameters.resolveInterface(binding.key(), instance, binding.instanceKey());
            if (value == null)
                throw new InterfaceBindingException("Cannot resolve DI instance data for user pointer " + binding.index()
                        + " from parameter " + binding.key().name());
            if (!(value instanceof NiagaraDataInterfaceObject object))
                throw new IllegalStateException("Invalid DI object in parameter " + binding.key().name());
            pointers[binding.index()] = object;
        }
        for (int index = 0; index < next.length; index++) {
            var declaration = program.external().get(index);
            if (declaration.interfaceKey() == null) continue;
            Object value = parameters.resolveInterface(declaration.interfaceKey(), instance, declaration.instanceKey());
            if (value == null)
                throw new InterfaceBindingException("Cannot bind Niagara function " + declaration.name()
                        + " from interface parameter " + declaration.interfaceKey().name());
            if (!(value instanceof NiagaraDataInterfaceObject object))
                throw new IllegalStateException("Invalid DI object in parameter " + declaration.interfaceKey().name());
            var function = object.bind(declaration);
            if (function == null)
                throw new InterfaceBindingException("Data interface " + object.identity()
                        + " has no compiled function binding for " + declaration.name());
            next[index] = function;
            objects[index] = object;
        }
        for (int index = 0; index < next.length; index++) {
            if (next[index] != externalBindings[index]) {
                boolean samePropertyInterface = propertyInterfaceCalls[index] != null
                        && next[index].interfaceType().equals("property_interface")
                        && next[index].interfaceId().equals(externalBindings[index].interfaceId());
                if (!samePropertyInterface) prepareExternal(index, next[index]);
                externalBindings[index] = next[index];
            }
            // A dirty execution store rebuilds the native function table even
            // when the DI object is unchanged. Property offsets are captured
            // by GetVMExternalFunction at that binding, not by object identity.
            if (propertyInterfaceCalls[index] != null && interfaces != null)
                propertyInterfaceCalls[index].bind(interfaces.propertyInterface(next[index].interfaceId()));
            if (consoleRequests[index] != null)
                consoleBindings[index] = consoleRequests[index].bind(
                        interfaces == null ? NiagaraPlatforms.snapshot() : interfaces.platformEnvironment());
        }
        System.arraycopy(objects, 0, interfaceObjects, 0, objects.length);
        System.arraycopy(pointers, 0, userPointers, 0, pointers.length);
    }

    private void prepareExternal(int index, VectorProgram.External external) {
        curlNoise[index] = null; meshInformation[index] = null; vectorFields[index] = null;
        spriteInformation[index] = null;
        richCurves[index] = null; platformSets[index] = null;
        propertyRequests[index] = external.interfaceType().equals("object_property_reader")
                ? NiagaraObjectPropertyReader.Request.read(external.name(), Objects.requireNonNull(external.interfaceData())) : null;
        propertyInterfaceCalls[index] = external.interfaceType().equals("property_interface")
                ? new PropertyInterfaceCall(NiagaraPropertyInterface.Output.read(
                        Objects.requireNonNull(external.interfaceData()).getAsJsonArray("outputs")), emitterOwner == null) : null;
        consoleRequests[index] = external.interfaceType().equals("console_variable")
                ? NiagaraConsoleVariable.read(external.name(), Objects.requireNonNull(external.interfaceData())) : null;
        if (external.interfaceType().equals("curl_noise")) {
            var data = external.interfaceData();
            if (data == null || !data.has("seed")) throw new IllegalArgumentException("Niagara curl noise has no seed");
            curlNoise[index] = new NiagaraCurlNoise(data.get(data.has("offset_seed") ? "offset_seed" : "seed").getAsInt());
        }
        if (external.interfaceType().equals("mesh_renderer_info"))
            meshInformation[index] = MeshRendererInfoJson.read(Objects.requireNonNull(external.interfaceData(),
                    "Niagara mesh renderer information requires its serialized bounds"));
        if (external.interfaceType().equals("sprite_renderer_info"))
            spriteInformation[index] = SpriteRendererInfoJson.read(Objects.requireNonNull(external.interfaceData(),
                    "Niagara sprite renderer information requires its source defaults"));
        if (external.interfaceType().equals("vector_field")) vectorFields[index] = new NiagaraVectorFieldData(external);
        if (external.interfaceType().equals("platform_set"))
            platformSets[index] = NiagaraScalabilityJson.platform(Objects.requireNonNull(external.interfaceData(),
                    "PlatformSet requires its source set").getAsJsonObject("platforms"));
        if (external.curves().stream().anyMatch(curve -> curve.lookup() == null)) {
            var samplers = new NativeCurveSampler[external.curves().size()];
            for (int channel = 0; channel < samplers.length; channel++) {
                var curve = external.curves().get(channel);
                if (curve.lookup() == null) samplers[channel] = new NativeCurveSampler(curve);
            }
            richCurves[index] = samplers;
        }
    }

    public void execute(int instances, VectorDataSet[] inputs, VectorDataSet[] outputs, int outputOffset,
                        byte[] constants) {
        execute(instances, inputs, outputs, outputOffset, constants, null);
    }

    public void execute(int instances, VectorDataSet[] inputs, VectorDataSet[] outputs, int outputOffset,
                        byte[] constants, BatchRandom suppliedRandom) {
        execute(instances, inputs, outputs, outputOffset, constants, suppliedRandom, null);
    }

    void execute(int instances, VectorDataSet[] inputs, VectorDataSet[] outputs, int outputOffset,
                 byte[] constants, BatchRandom suppliedRandom, PersistentParticleIds[] ids) {
        if (defaultInputOffsets.length != inputs.length) defaultInputOffsets = new int[inputs.length];
        if (defaultOutputOffsets.length != outputs.length) defaultOutputOffsets = new int[outputs.length];
        Arrays.fill(defaultOutputOffsets, outputOffset);
        execute(instances, inputs, defaultInputOffsets, outputs, defaultOutputOffsets, constants, suppliedRandom, ids);
    }

    void execute(int instances, VectorDataSet[] inputs, int[] inputOffsets, VectorDataSet[] outputs, int[] outputOffsets,
                 byte[] constants, BatchRandom suppliedRandom, PersistentParticleIds[] ids) {
        execute(instances, inputs, inputOffsets, outputs, outputOffsets, constants, suppliedRandom, ids, null);
    }

    int[] executeSystemRows(VectorDataSet current, VectorDataSet parameters, VectorDataSet destination,
                            List<VectorMachine> owners, List<byte[]> constants) {
        int count = owners.size();
        if (count == 0 || constants.size() != count) throw new IllegalArgumentException("Invalid Niagara system row batch");
        for (int row = 0; row < count; row++)
            if (owners.get(row).program != program || constants.get(row).length != constantBytes)
                throw new IllegalArgumentException("Niagara shared execution requires one system script layout");
        if (laneRegisters == null) {
            var lanes = new Register[registers.length];
            Arrays.setAll(lanes, ignored -> new Register());
            laneRegisters = lanes;
        }
        int[] outputRows = new int[count];
        Arrays.fill(outputRows, -1);
        execute(count, new VectorDataSet[]{current, parameters}, new int[2], new VectorDataSet[]{destination}, new int[1],
                constants.getFirst(), null, null, new SharedRows(owners, constants, outputRows));
        return outputRows;
    }

    private void execute(int instances, VectorDataSet[] inputs, int[] inputOffsets, VectorDataSet[] outputs, int[] outputOffsets,
                         byte[] constants, @Nullable BatchRandom suppliedRandom, PersistentParticleIds[] ids, @Nullable SharedRows rows) {
        if (instances == 0) return;
        if (instances < 0 || inputOffsets.length != inputs.length || outputOffsets.length != outputs.length
                || constants.length != constantBytes)
            throw new IllegalArgumentException("Invalid vector invocation dimensions");
        for (int offset : inputOffsets) if (offset < 0) throw new IllegalArgumentException("Negative vector input offset");
        for (int offset : outputOffsets) if (offset < 0) throw new IllegalArgumentException("Negative vector output offset");
        BatchRandom random = randomInstructions ? suppliedRandom == null ? new BatchRandom() : suppliedRandom : null;
        Arrays.fill(totals, 0);
        if (rows == null) {
            ByteBuffer bytes = ByteBuffer.wrap(constants).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < constant.length; i++) Arrays.fill(constant[i], bytes.getInt(constantSlots[i] * 4));
        }
        for (int i = 0; i < outputs.length; i++) outputs[i].reserve(Math.addExact(outputOffsets[i], instances + 4));
        VectorMachine owner = rows == null ? this : rows.owners().getFirst();
        var platform = owner.interfaces == null ? NiagaraPlatforms.snapshot() : owner.interfaces.platformEnvironment();
        int maxChunk = chunkCapacity(instances, platform.vectorVmPageBytes());
        if (externalRandom != null) externalRandom.begin(rows == null ? Math.min(maxChunk, instances) : 1);
        for (int start = 0; start < instances; start += maxChunk) {
            int count = Math.min(maxChunk, instances - start), padded = (count + 3) & ~3;
            prepareRegisters(inputs, inputOffsets, outputs, outputOffsets, start, padded, rows);
            Arrays.fill(alive, 0);
            for (int instructionIndex = 0; instructionIndex < instructionRegisters.length; instructionIndex++) {
                Instruction instruction = program.instructions().get(instructionIndex);
                int[] args = instructionRegisters[instructionIndex];
                switch (instruction.opcode()) {
                    case ACQUIREINDEX -> {
                        int dataset = instruction.dataset();
                        if (masks[dataset] == null || masks[dataset].length < count) masks[dataset] = new int[padded];
                        for (int i = 0; i < count; i++) if (registers[args[0]].get(i) < 0) masks[dataset][alive[dataset]++] = i;
                    }
                    case ACQUIRE_ID -> {
                        PersistentParticleIds table = ids[instruction.dataset()];
                        Register index = registers[args[0]], tag = registers[args[1]];
                        table.beginAcquireBatch(count);
                        for (int i = 0; i < count; i++) {
                            index.set(i, table.acquire());
                            tag.set(i, table.tag());
                        }
                    }
                    case UPDATE_ID -> {
                        int dataset = instruction.dataset(), live = 0;
                        PersistentParticleIds table = ids[dataset];
                        Register index = registers[args[0]];
                        for (int i = 0; i < count; i++) {
                            int id = index.get(i);
                            if (live < alive[dataset] && masks[dataset][live] == i)
                                table.alive(id, outputOffsets[dataset] + totals[dataset] + live++);
                            else table.dead(id);
                        }
                    }
                    case OUTPUTDATA_FLOAT, OUTPUTDATA_INT32, OUTPUTDATA_HALF,
                            OUTPUTDATA_FLOAT_FROM_HALF, OUTPUTDATA_HALF_FROM_HALF -> {
                        int dataset = instruction.dataset(), n = instruction.inputCount();
                        var opcode = instruction.opcode();
                        for (int column = 0; column < n; column++) {
                            int sourceIndex = args[column];
                            Register source = registers[sourceIndex], destination = registers[args[n + column]];
                            boolean widen = opcode == Opcode.OUTPUTDATA_FLOAT_FROM_HALF
                                    || opcode != Opcode.OUTPUTDATA_HALF_FROM_HALF && isBroadcastHalfInput(sourceIndex);
                            for (int i = 0; i < alive[dataset]; i++) {
                                int value = source.get(masks[dataset][i]);
                                if (widen) value = bits(Float.float16ToFloat((short)value));
                                if (opcode == Opcode.OUTPUTDATA_HALF) value = halfBits(value);
                                destination.set(i, value);
                            }
                        }
                    }
                    case EXTERNAL_FUNC_CALL -> {
                        if (rows == null) external(instruction, args, count, externalRandom);
                        else for (int lane = 0; lane < count; lane++)
                            externalSystemRow(instruction, args, lane, rows.owners().get(start + lane));
                    }
                    case RANDOM, RANDOMI, RANDOM_ADD, RANDOM_2X ->
                            random(instruction, args, padded, Objects.requireNonNull(random));
                    case MUL_2X, B2I_2X -> duplicate(instruction, args, padded);
                    case RCP, RSQ, EXP, EXP2, LOG, LOG2, SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2,
                            POW, FMOD, FMOD_ADD -> nativeMath(instruction, args, padded);
                    case SIN_COS -> nativeSinCos(args, padded);
                    case EXEC_INDEX, EXEC_INDEXF, EXEC_INDEX_ADDI -> {
                        Register destination = registers[args[args.length - 1]];
                        for (int i = 0; i < padded; i++) {
                            // The native one-vector dispatch starts at zero, including a short final chunk.
                            int index = (padded == 4 ? 0 : start) + i;
                            destination.set(i, instruction.opcode() == Opcode.EXEC_INDEXF ? bits((float)index)
                                    : instruction.opcode() == Opcode.EXEC_INDEX_ADDI ? index + registers[args[0]].get(i) : index);
                        }
                    }
                    default -> arithmetic(instruction, args, padded);
                }
            }
            if (rows != null && totals.length != 0)
                for (int row = 0; row < alive[0]; row++) rows.outputs()[start + masks[0][row]] = totals[0] + row;
            for (int dataset = 0; dataset < totals.length; dataset++) totals[dataset] += alive[dataset];
        }
        for (int dataset = 0; dataset < outputs.length; dataset++)
            outputs[dataset].count(outputOffsets[dataset] + (dataset < totals.length ? totals[dataset] : 0));
    }

    private boolean isBroadcastHalfInput(int register) {
        int inputIndex = register - temporary.length - constant.length;
        int inputCount = program.inputs().size();
        if (inputIndex >= inputCount) inputIndex -= inputCount;
        if (inputIndex < 0 || inputIndex >= inputCount) return false;
        var input = program.inputs().get(inputIndex);
        // The native VM widens no-advance Half inputs in its constant bank. The imported bank keeps raw Half words
        // so that the optimizer's explicit half_to_float instructions can read them as Half too.
        return input.constant() && input.type() == 2;
    }

    private void prepareRegisters(VectorDataSet[] inputs, int[] inputOffsets, VectorDataSet[] outputs, int[] outputOffsets,
                                  int start, int padded, @Nullable SharedRows rows) {
        int cursor = 0;
        for (int i = 0; i < temporary.length; i++) {
            if (temporary[i] == null || temporary[i].length < padded) temporary[i] = new int[padded];
            registers[cursor++].bind(temporary[i], 0, 4);
        }
        if (rows == null) for (int[] values : constant) registers[cursor++].bind(values, 0, 0);
        else {
            var laneConstants = rowConstants;
            if (laneConstants == null) rowConstants = laneConstants = new int[constant.length][];
            for (int slot = 0; slot < constantSlots.length; slot++) {
                if (laneConstants[slot] == null || laneConstants[slot].length < padded) laneConstants[slot] = new int[padded];
                int offset = Math.multiplyExact(constantSlots[slot], Integer.BYTES);
                for (int lane = 0; lane < padded; lane++) {
                    byte[] source = rows.constants().get(Math.min(start + lane, rows.constants().size() - 1));
                    laneConstants[slot][lane] = Byte.toUnsignedInt(source[offset]) | Byte.toUnsignedInt(source[offset + 1]) << 8
                            | Byte.toUnsignedInt(source[offset + 2]) << 16 | source[offset + 3] << 24;
                }
                registers[cursor++].bind(laneConstants[slot], 0, 4);
            }
        }
        int inputBase = cursor;
        for (var input : program.inputs()) {
            int[] words = inputs[input.dataset()].component(input.type(), input.component());
            if (input.constant() && rows == null) {
                Register register = registers[cursor++];
                if (register.words == null || register.words.length != 4) register.words = new int[4];
                Arrays.fill(register.words, words[inputOffsets[input.dataset()]]);
                register.offset = 0; register.stride = 0;
            } else {
                // Each shared lane belongs to a separate system and carries that system's broadcast value.
                registers[cursor++].bind(words, inputOffsets[input.dataset()] + start, 4);
            }
        }
        for (int i = 0; i < program.inputs().size(); i++) {
            Register input = registers[inputBase + i];
            registers[cursor++].bind(input.words, input.offset, input.stride);
        }
        for (var output : program.outputs()) {
            if (output.component() == 0xffff) {
                Register register = registers[cursor++];
                if (register.words == null || register.words.length < padded) register.words = new int[padded];
                register.offset = 0; register.stride = 4;
            } else registers[cursor++].bind(outputs[output.dataset()].component(output.type(), output.component()),
                    outputOffsets[output.dataset()] + totals[output.dataset()], 4);
        }
    }

    private int chunkCapacity(int instances, long pageBytes) {
        long loops = Math.max((instances + 3L) >>> 2, 1);
        // Win64 batch structure: 224 bytes rounded to 256; pointer tables use 8-byte pointers.
        long overhead = 256 + 512 + program.maxOutputDatasets() * 16L + program.maxExternalRegisters() * 9L
                + program.dummyRegisters() * 16L + program.registerCount() * 9L;
        long bytesPerLoop = program.temporaryRegisters() * 16L + program.maxOutputDatasets();
        long available = pageBytes - overhead;
        if (available <= 0) throw new IllegalArgumentException("Vector program exceeds vm.PageSizeInKB batch budget");
        long required = ((loops + 3) & ~3L) * bytesPerLoop;
        long chunks = Math.max(1, Math.ceilDiv(required, available));
        return Math.toIntExact(Math.ceilDiv(loops, chunks) * 4);
    }

    private void random(Instruction instruction, int[] args, int padded, BatchRandom random) {
        for (int group = 0; group < padded; group += 4) {
            random.next(randomA);
            if (instruction.opcode() == Opcode.RANDOM_2X) random.next(randomB);
            for (int lane = 0; lane < 4; lane++) writeRandom(instruction, args, group + lane, lane);
        }
    }

    private void writeRandom(Instruction instruction, int[] args, int index, int lane) {
        // RANDOMI consumes a float scale and truncates only the sampled result.
        float scale = floating(registers[args[0]].get(index));
        float value = randomA[lane] * scale;
        if (instruction.opcode() == Opcode.RANDOM_ADD) value += floating(registers[args[1]].get(index));
        registers[args[instruction.inputCount()]].set(index,
                instruction.opcode() == Opcode.RANDOMI ? integer(value) : bits(value));
        if (instruction.opcode() == Opcode.RANDOM_2X) registers[args[2]].set(index, bits(randomB[lane] * scale));
    }

    private void externalSystemRow(Instruction instruction, int[] args, int lane, VectorMachine owner) {
        var local = Objects.requireNonNull(laneRegisters);
        for (int index : args) if (index != 0xffff) bindLane(local[index], registers[index], lane, false);
        int pointer = instruction.userPointerRegister();
        if (pointer >= 0) bindLane(local[pointer], registers[pointer], lane, true);
        var previous = owner.registers;
        owner.registers = local;
        // PerInsFn replaces the instance binding but retains the parent VM batch's random context.
        try { owner.external(instruction, args, 1, externalRandom); }
        finally { owner.registers = previous; }
    }

    private static void bindLane(Register target, Register source, int lane, boolean constant) {
        target.bind(source.words, source.offset + (source.stride == 0 ? lane & 3 : lane), constant ? 0 : source.stride);
    }

    private void nativeMath(Instruction instruction, int[] args, int padded) {
        Register a = registers[args[0]], b = registers[args[instruction.inputCount() >= 2 ? 1 : 0]];
        if (nativeResult.length < padded) nativeResult = new int[padded];
        int operation = switch (instruction.opcode()) {
            case RCP -> 0; case SIN -> 1; case COS -> 2; case ACOS -> 3; case POW -> 4; case RSQ -> 5;
            case FMOD, FMOD_ADD -> 6; case EXP -> 7; case EXP2 -> 8; case LOG -> 9; case LOG2 -> 10;
            case TAN -> 11; case ASIN -> 12; case ATAN -> 13; case ATAN2 -> 14;
            default -> throw new IllegalArgumentException("Not an intrinsic vector instruction");
        };
        NativeVectorMath.evaluate(operation, padded / 4, a.words, a.offset, a.stride, b.words, b.offset, b.stride, nativeResult);
        Register destination = registers[args[instruction.inputCount()]];
        if (instruction.opcode() == Opcode.FMOD_ADD) {
            Register addend = registers[args[2]];
            for (int row = 0; row < padded; row++)
                destination.set(row, bits(floating(nativeResult[row]) + floating(addend.get(row))));
        } else System.arraycopy(nativeResult, 0, destination.words, destination.offset, padded);
    }

    private void nativeSinCos(int[] args, int padded) {
        Register input = registers[args[0]], sine = registers[args[1]], cosine = registers[args[2]];
        if (nativeResult.length < padded) nativeResult = new int[padded];
        if (nativeSecondary.length < padded) nativeSecondary = new int[padded];
        NativeVectorMath.evaluateSinCos(padded / 4, input.words, input.offset, input.stride,
                nativeResult, nativeSecondary);
        System.arraycopy(nativeResult, 0, sine.words, sine.offset, padded);
        System.arraycopy(nativeSecondary, 0, cosine.words, cosine.offset, padded);
    }

    private void external(Instruction instruction, int[] args, int count, @Nullable NiagaraExternalRandom randomContext) {
        var binding = externalBindings[instruction.function()];
        if (instruction.userPointerRegister() >= 0) {
            Register pointer = registers[instruction.userPointerRegister()];
            if (pointer.stride != 0) throw new IllegalStateException("DI user pointer must use a constant input register");
            int index = pointer.get(0);
            if (index < 0 || index >= userPointers.length || userPointers[index] == null
                    || userPointers[index] != interfaceObjects[instruction.function()])
                throw new IllegalStateException("DI user pointer does not resolve the bound instance data: " + index);
        }
        if (binding.interfaceType().equals("platform_set")) {
            NiagaraPlatformSet set = platformSets[instruction.function()];
            boolean active = set.active(interfaces == null ? NiagaraPlatforms.snapshot() : interfaces.platformEnvironment());
            Register output = registers[args[0]];
            for (int index = 0; index < count; index++) output.set(index, active ? -1 : 0);
            return;
        }
        if (binding.interfaceType().equals("camera")) {
            cameraExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("fast_path")) {
            matrixToQuaternion(args, count);
            return;
        }
        if (binding.interfaceType().equals("collision_query")) {
            if (interfaces == null) throw new IllegalStateException("Niagara collision query state is unavailable");
            var collision = interfaces.collision(binding.interfaceId());
            synchronized (collision) { collisionExternal(binding, args, count, collision); }
            return;
        }
        if (binding.interfaceType().equals("physics_field")) {
            if (interfaces == null) throw new IllegalStateException("Niagara physics field world is unavailable");
            interfaces.physicsField().execute(NiagaraPhysicsFieldState.Operation.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("console_variable")) {
            int function = instruction.function();
            var environment = interfaces == null ? NiagaraPlatforms.snapshot() : interfaces.platformEnvironment();
            var value = consoleBindings[function];
            if (value == null) consoleBindings[function] = value = consoleRequests[function].bind(environment);
            value.sample(environment);
            int word = value.vmWord();
            for (int row = 0; row < count; row++) {
                writeExternalRaw(args, 0, row, value.valid() ? -1 : 0);
                writeExternalRaw(args, 1, row, word);
            }
            return;
        }
        if (binding.interfaceType().equals("ribbon_shape")) {
            if (count != 0 && registers[args[0]].get(0) != 0) {
                var ribbon = Objects.requireNonNull(interfaceObjects[instruction.function()], "Niagara Ribbon Interface is unbound");
                var shape = NiagaraRibbonShape.read(ribbon.ribbonProperties(), binding.name().equals("SetRibbonShapeFull"),
                        binding.inputs(), word -> floating(registers[args[word]].get(0)));
                Objects.requireNonNull(interfaces, "Niagara Ribbon Interface has no system state").setRibbonShape(ribbon, shape);
            }
            return;
        }
        if (binding.interfaceType().equals("static_mesh")) {
            staticMeshExternal(binding, args, count, randomContext);
            return;
        }
        if (binding.interfaceType().equals("skeletal_mesh")) {
            skeletalMeshExternal(binding, args, count, randomContext);
            return;
        }
        if (binding.interfaceType().equals("particle_export")) {
            if (interfaces == null) throw new IllegalStateException("Niagara particle export state is unavailable");
            var state = interfaces.particleExport(binding.interfaceId());
            for (int row = 0; row < count; row++) {
                boolean enabled = registers[args[0]].get(row) != 0;
                boolean stored = enabled && state.store(floating(registers[args[1]].get(row)), floating(registers[args[2]].get(row)),
                        floating(registers[args[3]].get(row)), floating(registers[args[4]].get(row)), floating(registers[args[5]].get(row)),
                        floating(registers[args[6]].get(row)), floating(registers[args[7]].get(row)));
                writeExternalRaw(args, 8, row, stored ? -1 : 0);
            }
            return;
        }
        if (binding.interfaceType().equals("data_channel")) {
            if (interfaces == null) throw new IllegalStateException("Niagara data channel state is unavailable");
            var state = interfaces.dataChannel(binding.interfaceId());
            var data = Objects.requireNonNull(binding.interfaceData());
            String variable = data.has("selected_name") ? SourceNames.canonical(data.get("selected_name").getAsString()) : "";
            String type = data.has("selected_type") ? SourceNames.canonical(data.get("selected_type").getAsString()) : "";
            var function = state.bind(NiagaraDataChannelDefinition.variables(data.getAsJsonArray("variables")), variable, type);
            int[] input = new int[binding.inputs()], output = new int[binding.outputs()];
            for (int row = 0; row < count; row++) {
                for (int i = 0; i < input.length; i++) input[i] = registers[args[i]].get(row);
                if (channelSpawn(binding.name())) state.spawn(binding.name(), function, input, Objects.requireNonNull(randomContext));
                else state.execute(binding.name(), function, input, output);
                for (int i = 0; i < output.length; i++) writeExternalRaw(args, input.length + i, row, output[i]);
            }
            return;
        }
        if (binding.interfaceType().equals("audio_player")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Audio Player state is unavailable");
            var state = interfaces.audioPlayer(binding.interfaceId());
            int[] input = new int[binding.inputs()];
            for (int row = 0; row < count; row++) {
                for (int i = 0; i < input.length; i++) input[i] = registers[args[i]].get(row);
                int output = state.execute(binding.name(), input);
                if (binding.outputs() != 0) writeExternalRaw(args, input.length, row, output);
            }
            return;
        }
        if (binding.interfaceType().equals("dynamic_mesh")) {
            if (interfaces == null) throw new IllegalStateException("Niagara dynamic mesh is unavailable");
            int inputs = binding.inputs();
            interfaces.dynamicMesh(binding.interfaceId()).execute(NiagaraDynamicMesh.Operation.valueOf(binding.name()),
                    count, inputs, binding.outputs(), (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("memory_buffer")) {
            if (interfaces == null) throw new IllegalStateException("Niagara memory buffer is unavailable");
            int inputs = binding.inputs();
            interfaces.memoryBuffer(binding.interfaceId()).execute(Operation.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("neighbor_grid")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Neighbor Grid3D is unavailable");
            int inputs = binding.inputs();
            interfaces.neighborGrid(binding.interfaceId()).execute(NiagaraNeighborGrid.Function.valueOf(binding.name()), count,
                    interfaces.platformEnvironment(),
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("rasterization_grid")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Rasterization Grid3D is unavailable");
            int inputs = binding.inputs();
            interfaces.rasterizationGrid(binding.interfaceId()).execute(NiagaraRasterizationGrid.Function.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("water")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Water is unavailable");
            int inputs = binding.inputs();
            interfaces.water(binding.interfaceId()).execute(NiagaraWaterState.Function.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("velocity_grid")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Velocity/Pressure Grid is unavailable");
            int inputs = binding.inputs();
            interfaces.velocityGrid(binding.interfaceId()).execute(NiagaraVelocityGrid.Function.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("neighbor_query")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Neighbor Query is unavailable");
            var query = interfaces.neighborQuery(binding.interfaceId());
            var state = query.snapshot();
            for (int lane = 0; lane < count; lane++) switch (binding.name()) {
                case "GetNumCells" -> {
                    writeExternalRaw(args, 0, lane, state.x());
                    writeExternalRaw(args, 1, lane, state.y());
                    writeExternalRaw(args, 2, lane, state.z());
                }
                case "MaxCellsPerParticle" -> writeExternalRaw(args, 0, lane, state.slotsPerParticle());
                case "SetNumCells" -> {
                    boolean changed = query.resize(count, registers[args[0]].get(lane), registers[args[1]].get(lane),
                            registers[args[2]].get(lane), registers[args[3]].get(lane), interfaces.platformEnvironment());
                    writeExternalRaw(args, 4, lane, changed ? -1 : 0);
                }
                default -> throw new IllegalArgumentException("Unknown Niagara Neighbor Query function: " + binding.name());
            }
            return;
        }
        if (binding.interfaceType().equals("debug_draw")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Debug Draw is unavailable");
            var data = Objects.requireNonNull(binding.interfaceData());
            // Persistent functions generate compiler tags. The system consumes them in the post-simulation callback.
            if (data.has("compiler_tag")) return;
            int limit = data.get("line_limit").getAsInt();
            interfaces.debugLines().draw(binding.interfaceId(), binding.name(), binding.inputs(), count, limit,
                    (word, lane) -> registers[args[word]].get(lane));
            return;
        }
        if (binding.interfaceType().equals("volume_cache")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Volume Cache is unavailable");
            if (count == 0) return;
            var data = Objects.requireNonNull(binding.interfaceData()).getAsJsonObject("volume_cache");
            var cache = interfaces.volumeCache(binding.interfaceId(), data);
            switch (binding.name()) {
                case "SetFrame" -> {
                    cache.frame(registers[args[0]].get(0));
                    writeExternalRaw(args, 1, 0, -1);
                }
                case "ReadFile" -> {
                    cache.readFile((registers[args[0]].get(0) & 255) != 0);
                    writeExternalRaw(args, 1, 0, -1);
                }
                case "GetNumCells" -> {
                    for (int lane = 0; lane < count; lane++) {
                        writeExternalRaw(args, 0, lane, cache.width());
                        writeExternalRaw(args, 1, lane, cache.height());
                        writeExternalRaw(args, 2, lane, cache.depth());
                    }
                }
                default -> throw new IllegalArgumentException("Unknown Niagara Volume Cache function: " + binding.name());
            }
            return;
        }
        if (binding.interfaceType().equals("hair_strands")) {
            if (interfaces == null) throw new IllegalStateException("Niagara HairStrands is unavailable");
            if (binding.interfaceData().get("native_writes").getAsBoolean()) {
                interfaces.hairStrands(binding.interfaceId()).execute(binding.name(), count,
                        (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            }
            return;
        }
        if (binding.interfaceType().equals("chaos_destruction")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Chaos Destruction is unavailable");
            int inputs = binding.inputs();
            interfaces.chaosDestruction(binding.interfaceId()).execute(NiagaraChaosDestructionState.Function.valueOf(binding.name()),
                    count, lane -> registers[args[0]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("geometry_collection")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Geometry Collection is unavailable");
            int inputs = binding.inputs();
            interfaces.geometryCollection(binding.interfaceId()).execute(NiagaraGeometryCollectionState.Function.valueOf(binding.name()),
                    count, (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("rigid_mesh_collision")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Rigid Mesh Collision is unavailable");
            if (count == 0) return;
            var state = interfaces.rigidMesh(binding.interfaceId());
            switch (binding.name()) {
                case "FindActors" -> {
                    for (int lane = 0; lane < count; lane++) {
                        boolean changed = registers[args[11]].get(lane) == 0 && state.find(
                                externalFloat(args, 0, lane), externalFloat(args, 1, lane), externalFloat(args, 2, lane),
                                externalFloat(args, 3, lane), externalFloat(args, 4, lane), externalFloat(args, 5, lane), externalFloat(args, 6, lane),
                                externalFloat(args, 7, lane), externalFloat(args, 8, lane), externalFloat(args, 9, lane),
                                registers[args[10]].get(lane));
                        writeExternalRaw(args, 12, lane, changed ? -1 : 0);
                    }
                }
                case "GetNumElements" -> {
                    var frame = state.frame();
                    writeExternalRaw(args, 0, 0, frame == null ? 0 : frame.count());
                }
                case "IsWorldPositionInsideCombinedBounds" -> writeExternalRaw(args, 3, 0,
                        state.contains(externalFloat(args, 0, 0), externalFloat(args, 1, 0), externalFloat(args, 2, 0)) ? -1 : 0);
                default -> throw new IllegalArgumentException("Unknown Niagara Rigid Mesh Collision function: " + binding.name());
            }
            return;
        }
        if (binding.interfaceType().equals("material_collection_writer")) {
            if (interfaces == null) throw new IllegalStateException("Niagara material collection writer is unavailable");
            if (count == 0) return;
            var writer = interfaces.materialCollectionWriter(binding.interfaceId());
            String parameter = Objects.requireNonNull(binding.interfaceData()).get("parameter").getAsString();
            int first = registers[args[0]].get(0);
            if (binding.name().equals("SetScalarParameter")) writer.setScalar(parameter, first);
            else writer.setVector(parameter, first, registers[args[1]].get(0), registers[args[2]].get(0), registers[args[3]].get(0));
            return;
        }
        if (binding.interfaceType().equals("material_instance_dynamic")) {
            if (interfaces == null) throw new IllegalStateException("Niagara dynamic material writer is unavailable");
            if (count == 0) return;
            var writer = interfaces.dynamicMaterialWriter(binding.interfaceId());
            String parameter = Objects.requireNonNull(binding.interfaceData()).get("parameter").getAsString();
            int first = registers[args[0]].get(0);
            if (binding.name().equals("SetScalarParameter")) writer.setScalar(parameter, first);
            else writer.setVector(parameter, first, registers[args[1]].get(0), registers[args[2]].get(0), registers[args[3]].get(0));
            return;
        }
        if (binding.interfaceType().equals("socket_reader")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Socket Reader is unavailable");
            interfaces.socketReader(binding.interfaceId()).execute(NiagaraSocketReader.Function.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("scene_capture_2d")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Scene Capture 2D is unavailable");
            interfaces.sceneCapture(binding.interfaceId()).execute(NiagaraSceneCapture.Function.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("landscape")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Landscape is unavailable");
            var landscape = interfaces.landscape(binding.interfaceId());
            for (int lane = 0; lane < count; lane++) {
                if ((registers[args[8]].get(lane) & 255) == 0) continue;
                int channels = ((registers[args[5]].get(lane) & 255) != 0 ? 1 : 0)
                        | ((registers[args[6]].get(lane) & 255) != 0 ? 2 : 0)
                        | ((registers[args[7]].get(lane) & 255) != 0 ? 4 : 0);
                landscape.feedback(externalFloat(args, 0, lane), externalFloat(args, 1, lane),
                        externalFloat(args, 2, lane), externalFloat(args, 3, lane), registers[args[4]].get(lane), channels);
            }
            return;
        }
        if (binding.interfaceType().equals("virtual_texture_sample")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Virtual Texture Sample is unavailable");
            var texture = interfaces.virtualTexture(binding.interfaceId());
            switch (binding.name()) {
                case "GetTextureDimensions" -> {
                    for (int lane = 0; lane < count; lane++) {
                        int mip = registers[args[0]].get(lane);
                        writeExternalRaw(args, 1, lane, Float.floatToRawIntBits(texture.width(mip)));
                        writeExternalRaw(args, 2, lane, Float.floatToRawIntBits(texture.height(mip)));
                    }
                }
                case "GetNumMipLevels" -> {
                    int mips = texture.mipLevels();
                    for (int lane = 0; lane < count; lane++) writeExternalRaw(args, 0, lane, mips);
                }
                default -> throw new IllegalArgumentException("Unknown Niagara Virtual Texture Sample function: " + binding.name());
            }
            return;
        }
        if (binding.interfaceType().equals("spline")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Spline is unavailable");
            interfaces.spline(binding.interfaceId()).execute(NiagaraSpline.Function.valueOf(binding.name()), count,
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("int_distribution")) {
            if (interfaces == null) throw new IllegalStateException("Niagara integer distribution is unavailable");
            var distribution = interfaces.intDistribution(binding.interfaceId());
            var function = NiagaraIntDistribution.Function.valueOf(binding.name());
            for (int lane = 0; lane < count; lane++) {
                int index = binding.inputs() == 0 ? 0 : registers[args[0]].get(lane);
                int value = switch (function) {
                    case Length -> distribution.length();
                    case LastIndex -> distribution.length() - 1;
                    case IsValidIndex -> distribution.validIndex(index) ? -1 : 0;
                    case Get -> distribution.get(index, 2);
                    case GetProbabilityAlias -> distribution.get(index, 0);
                    case GetRandomValue -> {
                        var stream = Objects.requireNonNull(randomContext);
                        stream.select(lane, index, registers[args[1]].get(lane), registers[args[2]].get(lane));
                        yield distribution.sample(stream.next(), stream.next());
                    }
                };
                writeExternalRaw(args, binding.inputs(), lane, value);
                if (function == NiagaraIntDistribution.Function.Get)
                    writeExternalRaw(args, binding.inputs() + 1, lane, distribution.get(index, 3));
                else if (function == NiagaraIntDistribution.Function.GetProbabilityAlias)
                    writeExternalRaw(args, binding.inputs() + 1, lane, distribution.get(index, 1));
            }
            return;
        }
        if (binding.interfaceType().equals("mouse_position")) {
            if (interfaces == null) throw new IllegalStateException("Niagara mouse position is unavailable");
            var view = interfaces.viewQueries();
            for (int lane = 0; lane < count; lane++) {
                boolean normalized = registers[args[0]].get(lane) != 0;
                float x = normalized ? view.mouseX() / view.viewportWidth() : view.mouseX();
                float y = normalized ? view.mouseY() / view.viewportHeight() : view.mouseY();
                writeExternalRaw(args, 1, lane, Float.floatToRawIntBits(x));
                writeExternalRaw(args, 2, lane, Float.floatToRawIntBits(y));
            }
            return;
        }
        if (binding.interfaceType().equals("movie_render_queue")) {
            if (interfaces == null) throw new IllegalStateException("Niagara movie render sample is unavailable");
            var sample = interfaces.viewQueries().movie();
            for (int lane = 0; lane < count; lane++) {
                writeExternalRaw(args, 0, lane, sample.active() ? -1 : 0);
                writeExternalRaw(args, 1, lane, sample.sampleCount());
                writeExternalRaw(args, 2, lane, sample.sampleIndex());
                writeExternalRaw(args, 3, lane, Float.floatToRawIntBits((float)sample.sequenceFramesPerSecond()));
            }
            return;
        }
        if (binding.interfaceType().equals("int_render_target_2d")) {
            if (interfaces == null) throw new IllegalStateException("Niagara integer render target is unavailable");
            var target = interfaces.intRenderTarget2D(binding.interfaceId());
            if (binding.name().equals("GetRenderTargetSize")) {
                var state = target.snapshot();
                for (int lane = 0; lane < count; lane++) {
                    writeExternalRaw(args, 0, lane, state.width());
                    writeExternalRaw(args, 1, lane, state.height());
                }
            } else {
                for (int lane = 0; lane < count; lane++) {
                    boolean success = target.setSize(count, registers[args[0]].get(lane), registers[args[1]].get(lane),
                            interfaces.platformEnvironment());
                    writeExternalRaw(args, 2, lane, success ? -1 : 0);
                }
            }
            return;
        }
        if (binding.interfaceType().equals("data_table")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Data Table is unavailable");
            var columns = Objects.requireNonNull(binding.interfaceData()).getAsJsonArray("columns");
            interfaces.dataTable(binding.interfaceId()).execute(NiagaraDataTable.Function.valueOf(binding.name()), count, columns,
                    lane -> registers[args[0]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("gaussian_splat_reader")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Gaussian Splat Reader is unavailable");
            interfaces.gaussianSplatReader(binding.interfaceId()).execute(NiagaraGaussianSplatReader.Function.valueOf(binding.name()),
                    count, (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("gaussian_splat_writer")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Gaussian Splat Writer is unavailable");
            interfaces.gaussianSplatWriter(binding.interfaceId()).execute(NiagaraGaussianSplatWriter.Function.valueOf(binding.name()),
                    count, (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, binding.inputs() + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("grid2d_collection")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Grid2D Collection is unavailable");
            int inputs = binding.inputs();
            var attribute = Objects.requireNonNull(binding.interfaceData());
            interfaces.grid2DCollection(binding.interfaceId()).execute(NiagaraGrid2DCollection.Function.valueOf(binding.name()), count,
                    interfaces.platformEnvironment(), attribute.has("attribute") ? attribute.get("attribute").getAsString() : "",
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("grid3d_collection")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Grid3D Collection is unavailable");
            int inputs = binding.inputs();
            var attribute = Objects.requireNonNull(binding.interfaceData());
            interfaces.grid3DCollection(binding.interfaceId()).execute(NiagaraGrid3DCollection.Function.valueOf(binding.name()), count,
                    interfaces.platformEnvironment(), attribute.has("attribute") ? attribute.get("attribute").getAsString() : "",
                    (word, lane) -> registers[args[word]].get(lane),
                    (word, lane, value) -> writeExternalRaw(args, inputs + word, lane, value));
            return;
        }
        if (binding.interfaceType().equals("sparse_volume_texture")) {
            if (interfaces == null) throw new IllegalStateException("Niagara sparse volume state is unavailable");
            var texture = interfaces.sparseVolume(binding.interfaceId());
            switch (binding.name()) {
                case "GetSparseVolumeTextureDimensions" -> {
                    for (int row = 0; row < count; row++) {
                        int mip = registers[args[0]].get(row);
                        for (int axis = 0; axis < 3; axis++)
                            writeExternalRaw(args, 1 + axis, row, texture.dimension(axis, mip));
                    }
                }
                case "GetSparseVolumeTextureNumMipLevels", "GetSparseVolumeTextureNumFrames" -> {
                    int value = binding.name().equals("GetSparseVolumeTextureNumMipLevels") ? texture.mips() : texture.frames();
                    for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, value);
                }
                case "RequestSparseVolumeTextureFrame" -> {
                    if (count != 0) texture.request(registers[args[0]].get(0), registers[args[1]].get(0));
                    for (int row = 0; row < count; row++) writeExternalRaw(args, 2, row, -1);
                }
                default -> throw new IllegalArgumentException("Unknown Niagara sparse volume VM function " + binding.name());
            }
            return;
        }
        if (binding.interfaceType().equals("sim_cache_reader")) {
            if (interfaces == null) throw new IllegalStateException("Niagara SimCache Reader is unavailable");
            var source = interfaces.simCacheReader(binding.interfaceId());
            var data = Objects.requireNonNull(binding.interfaceData());
            var call = source.bind(binding.name(), data.has("attribute") ? data.get("attribute").getAsString() : "");
            int[] input = new int[binding.inputs()], output = new int[binding.outputs()];
            for (int row = 0; row < count; row++) {
                for (int i = 0; i < input.length; i++) input[i] = registers[args[i]].get(row);
                call.read(input, output);
                for (int i = 0; i < output.length; i++) writeExternalRaw(args, input.length + i, row, output[i]);
            }
            return;
        }
        if (binding.interfaceType().equals("audio_analysis")) {
            if (interfaces == null) throw new IllegalStateException("Niagara audio analyzer is unavailable");
            var source = interfaces.audioFrame(binding.interfaceId());
            int inputs = binding.inputs();
            for (int row = 0; row < count; row++) {
                int result = inputs == 0 ? source.channels() : Float.floatToRawIntBits(source.sample(
                        floating(registers[args[0]].get(row)), registers[args[1]].get(row)));
                writeExternalRaw(args, inputs, row, result);
            }
            return;
        }
        if (binding.interfaceType().equals("property_interface")) {
            if (interfaces == null) throw new IllegalStateException("Niagara Property Interface state is unavailable");
            var state = interfaces.propertyInterface(binding.interfaceId());
            var frame = state.frame();
            int[] offsets = propertyInterfaceCalls[instruction.function()].offsets(state);
            for (int output = 0; output < offsets.length; output++) {
                int value = frame.readWord(offsets[output]);
                for (int row = 0; row < count; row++) writeExternalRaw(args, output, row, value);
            }
            return;
        }
        if (binding.interfaceType().equals("object_property_reader") || binding.interfaceType().equals("actor_component")) {
            if (interfaces == null) throw new IllegalStateException("Niagara object data interface state is unavailable");
            int[] values = binding.interfaceType().equals("object_property_reader")
                    ? interfaces.objectPropertyReader(binding.interfaceId()).read(propertyRequests[instruction.function()])
                    : interfaces.actorComponent(binding.interfaceId()).snapshot().read(binding.name());
            for (int row = 0; row < count; row++)
                for (int output = 0; output < values.length; output++) writeExternalRaw(args, output, row, values[output]);
            return;
        }
        if (binding.interfaceType().equals("array")) {
            arrayExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("curl_noise")) {
            var object = interfaceObjects[instruction.function()];
            NiagaraCurlNoise noise = object == null ? curlNoise[instruction.function()] : object.curlNoise();
            for (int row = 0; row < count; row++) {
                noise.sample(floating(registers[args[0]].get(row)), floating(registers[args[1]].get(row)),
                        floating(registers[args[2]].get(row)), curlResult);
                for (int i = 0; i < 3; i++) writeExternal(args, instruction.inputCount() + i, row, curlResult[i]);
            }
            return;
        }
        if (binding.interfaceType().equals("particle_read")) {
            particleReadExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("simple_counter")) {
            counterExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("sprite_renderer_info")) {
            var object = interfaceObjects[instruction.function()];
            var info = object == null ? null : object.spriteInformation();
            spriteRendererInfoExternal(binding, info == null ? spriteInformation[instruction.function()] : info, args, count);
            return;
        }
        if (binding.interfaceType().equals("mesh_renderer_info")) {
            var object = interfaceObjects[instruction.function()];
            var info = object == null ? null : object.meshInformation();
            meshRendererInfoExternal(binding.name(), info == null ? meshInformation[instruction.function()] : info, args, count);
            return;
        }
        if (binding.interfaceType().equals("emitter_properties")) {
            emitterPropertiesExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("texture_2d") || binding.interfaceType().equals("texture_array")
                || binding.interfaceType().equals("texture_cube") || binding.interfaceType().equals("texture_volume")) {
            textureExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("render_target_2d")) {
            renderTarget2DExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("render_target_array") || binding.interfaceType().equals("render_target_volume")
                || binding.interfaceType().equals("render_target_cube")) {
            dimensionalTargetExternal(binding, args, count);
            return;
        }
        if (binding.interfaceType().equals("vector_field")) {
            var object = interfaceObjects[instruction.function()];
            var field = object == null ? null : object.vectorField();
            vectorFieldExternal(binding, field == null ? vectorFields[instruction.function()] : field, args, count);
            return;
        }
        if (richCurves[instruction.function()] != null) {
            richCurveExternal(binding, richCurves[instruction.function()], args, count);
            return;
        }
        for (int row = 0; row < count; row++) {
            float x = floating(registers[args[0]].get(row));
            if (binding.field() != null) {
                float y = floating(registers[args[1]].get(row)), z = floating(registers[args[2]].get(row));
                binding.field().sampleSource(x, y, z, binding.tileX(), binding.tileY(), binding.tileZ(), fieldResult, 0);
                writeExternal(args, instruction.inputCount(), row, (float)fieldResult[0]);
                writeExternal(args, instruction.inputCount() + 1, row, (float)fieldResult[1]);
                writeExternal(args, instruction.inputCount() + 2, row, (float)fieldResult[2]);
            } else {
                for (int channel = 0; channel < binding.outputs(); channel++)
                    writeExternal(args, instruction.inputCount() + channel, row, (float)binding.curves().get(channel).sample(x));
            }
        }
    }

    private void richCurveExternal(VectorProgram.External binding, NativeCurveSampler[] samplers, int[] args, int count) {
        if (curveInputs.length < count) curveInputs = new int[count];
        if (nativeResult.length < count) nativeResult = new int[count];
        // An output may reuse the input register, so capture it once before writing any curve channel.
        for (int row = 0; row < count; row++) curveInputs[row] = registers[args[0]].get(row);
        for (int channel = 0; channel < samplers.length; channel++) {
            var sampler = samplers[channel];
            if (sampler != null) {
                sampler.sample(count, curveInputs, nativeResult);
                for (int row = 0; row < count; row++) writeExternalRaw(args, 1 + channel, row, nativeResult[row]);
            } else {
                var curve = binding.curves().get(channel);
                for (int row = 0; row < count; row++) writeExternal(args, 1 + channel, row, curve.sample(floating(curveInputs[row])));
            }
        }
    }

    private void vectorFieldExternal(VectorProgram.External binding, NiagaraVectorFieldData field, int[] args, int count) {
        if (!binding.name().equals("SampleField") && !binding.name().equals("LoadField")) {
            for (int component = 0; component < binding.outputs(); component++) {
                float value = field.constant(binding.name(), component);
                for (int row = 0; row < count; row++) writeExternal(args, component, row, value);
            }
            return;
        }
        for (int row = 0; row < count; row++) {
            if (binding.name().equals("LoadField"))
                field.load(registers[args[0]].get(row), registers[args[1]].get(row), registers[args[2]].get(row), fieldResult);
            else field.sample(floating(registers[args[0]].get(row)), floating(registers[args[1]].get(row)),
                    floating(registers[args[2]].get(row)), fieldResult);
            for (int component = 0; component < 3; component++) writeExternal(args, 3 + component, row, (float)fieldResult[component]);
        }
    }

    private void renderTarget2DExternal(VectorProgram.External binding, int[] args, int count) {
        if (interfaces == null) throw new IllegalStateException("Niagara render target state is unavailable");
        var target = interfaces.renderTarget2D(binding.interfaceId());
        switch (binding.name()) {
            case "GetRenderTargetSize" -> {
                var size = target.snapshot();
                for (int row = 0; row < count; row++) {
                    writeExternalRaw(args, 0, row, size.width()); writeExternalRaw(args, 1, row, size.height());
                }
            }
            case "GetNumMipLevels" -> {
                int levels = target.mipLevels();
                for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, levels);
            }
            case "SetRenderTargetSize" -> {
                for (int row = 0; row < count; row++) writeExternalRaw(args, 3, row,
                        target.setSize(count, registers[args[0]].get(row), registers[args[1]].get(row), registers[args[2]].get(row) != 0) ? -1 : 0);
            }
            case "ReleaseResources" -> {
                for (int row = 0; row < count; row++) target.release(registers[args[0]].get(row) != 0);
            }
            case "SetRenderTargetFormat" -> {
                for (int row = 0; row < count; row++) writeExternalRaw(args, 1, row,
                        target.setFormat(count, registers[args[0]].get(row)) ? -1 : 0);
            }
            default -> throw new IllegalArgumentException("Unknown RenderTarget2D CPU function " + binding.name());
        }
    }

    private void dimensionalTargetExternal(VectorProgram.External binding, int[] args, int count) {
        if (interfaces == null) throw new IllegalStateException("Niagara render target array state is unavailable");
        var target = interfaces.dimensionalTarget(binding.interfaceId());
        boolean cube = binding.interfaceType().equals("render_target_cube");
        switch (binding.name()) {
            case "GetRenderTargetSize" -> {
                var size = target.snapshot();
                for (int row = 0; row < count; row++) {
                    writeExternalRaw(args, 0, row, size.width());
                    if (!cube) {
                        writeExternalRaw(args, 1, row, size.height());
                        writeExternalRaw(args, 2, row, size.extentZ());
                    }
                }
            }
            case "GetNumMipLevels" -> {
                var user = target.snapshot().userTarget();
                int mips = user == null ? 1 : user.settings().mipLevels();
                for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, mips);
            }
            case "SetRenderTargetSize" -> {
                for (int row = 0; row < count; row++) {
                    int width = registers[args[0]].get(row);
                    boolean success = cube ? target.setSize(count, width, width, 6)
                            : target.setSize(count, width, registers[args[1]].get(row), registers[args[2]].get(row));
                    writeExternalRaw(args, cube ? 1 : 3, row, success ? -1 : 0);
                }
            }
            case "SetRenderTargetFormat" -> {
                for (int row = 0; row < count; row++)
                    writeExternalRaw(args, 1, row, target.setFormat(count, registers[args[0]].get(row)) ? -1 : 0);
            }
            default -> throw new IllegalArgumentException("Unknown render target array CPU function " + binding.name());
        }
    }

    private void textureExternal(VectorProgram.External binding, int[] args, int count) {
        if (interfaces == null) throw new IllegalStateException("Niagara texture state is unavailable");
        var texture = interfaces.textures().get(binding.interfaceId()).snapshot();
        switch (binding.name()) {
            case "TextureDimensions" -> {
                boolean cube = binding.interfaceType().equals("texture_cube");
                for (int row = 0; row < count; row++) {
                    if (cube) {
                        writeExternalRaw(args, 0, row, texture.width());
                        writeExternalRaw(args, 1, row, texture.height());
                    } else {
                        writeExternal(args, 0, row, texture.width());
                        writeExternal(args, 1, row, texture.height());
                        writeExternal(args, 2, row, texture.layers());
                    }
                }
            }
            case "TextureDimensions3D" -> {
                for (int row = 0; row < count; row++) {
                    writeExternal(args, 0, row, texture.width());
                    writeExternal(args, 1, row, texture.height());
                    writeExternal(args, 2, row, texture.depth());
                }
            }
            case "GetTextureDimensions" -> {
                for (int row = 0; row < count; row++) {
                    int mip = registers[args[0]].get(row);
                    writeExternal(args, 1, row, Math.max(texture.width() >> mip, 1));
                    writeExternal(args, 2, row, Math.max(texture.height() >> mip, 1));
                }
            }
            case "GetNumMipLevels" -> {
                for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, texture.mipLevels());
            }
            case "SampleTexture2D", "SamplePseudoVolumeTexture", "SampleCubeTexture", "SampleVolumeTexture" -> {
                // These CPU functions return the source interface's magenta value; GPU sampling uses its image.
                for (int row = 0; row < count; row++) {
                    int output = binding.inputs();
                    writeExternal(args, output, row, 1); writeExternal(args, output + 1, row, 0);
                    writeExternal(args, output + 2, row, 1); writeExternal(args, output + 3, row, 1);
                }
            }
            default -> throw new IllegalArgumentException("Unknown Niagara texture CPU function " + binding.name());
        }
    }

    private void emitterPropertiesExternal(VectorProgram.External binding, int[] args, int count) {
        var data = binding.interfaceData();
        if (data == null) throw new IllegalStateException("Niagara emitter properties binding is unavailable");
        var state = interfaces.emitterBounds(data.get("emitter").getAsString());
        var snapshot = state == null ? null : state.snapshot();
        if (binding.name().equals("SetFixedBounds")) {
            if (state == null) return;
            for (int row = 0; row < count; row++) {
                if (registers[args[0]].get(row) == 0) continue;
                state.fixedBounds(registers[args[1]].get(row) == 0 ? null
                        : new com.kltyton.lumenfx.effect.niagara.NiagaraBounds(
                                floating(registers[args[2]].get(row)), floating(registers[args[3]].get(row)),
                                floating(registers[args[4]].get(row)), floating(registers[args[5]].get(row)),
                                floating(registers[args[6]].get(row)), floating(registers[args[7]].get(row))));
            }
            return;
        }
        if (binding.name().equals("GetLocalSpace")) {
            for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row,
                    snapshot != null && snapshot.localSpace() ? -1 : 0);
            return;
        }
        var bounds = switch (binding.name()) {
            case "GetBounds" -> snapshot == null ? null : snapshot.bounds();
            case "GetFixedBounds" -> state == null ? null : state.fixedBounds();
            default -> throw new IllegalArgumentException("Unknown Niagara emitter properties query " + binding.name());
        };
        for (int row = 0; row < count; row++) {
            writeExternalRaw(args, 0, row, bounds == null ? 0 : -1);
            writeExternal(args, 1, row, bounds == null ? 0 : (float)bounds.minX());
            writeExternal(args, 2, row, bounds == null ? 0 : (float)bounds.minY());
            writeExternal(args, 3, row, bounds == null ? 0 : (float)bounds.minZ());
            writeExternal(args, 4, row, bounds == null ? 0 : (float)bounds.maxX());
            writeExternal(args, 5, row, bounds == null ? 0 : (float)bounds.maxY());
            writeExternal(args, 6, row, bounds == null ? 0 : (float)bounds.maxZ());
        }
    }

    private void spriteRendererInfoExternal(VectorProgram.External binding, SpriteRendererInformation data, int[] args, int count) {
        int value = switch (binding.name()) {
            case "IsValid" -> data.valid() ? -1 : 0;
            case "GetSourceMode" -> data.sourceMode();
            case "GetAlignment" -> data.alignment();
            case "GetFacingMode" -> data.facingMode();
            case "GetSubUVDetails" -> data.subImageBlend() ? -1 : 0;
            default -> throw new IllegalArgumentException("Unknown Niagara sprite renderer query " + binding.name());
        };
        boolean size = binding.name().equals("GetSubUVDetails");
        for (int row = 0; row < count; row++) {
            writeExternalRaw(args, 0, row, value);
            if (size) {
                writeExternal(args, 1, row, data.columns());
                writeExternal(args, 2, row, data.rows());
            }
        }
    }

    private void meshRendererInfoExternal(String name, MeshRendererInformation info, int[] args, int count) {
        switch (name) {
            case "GetNumMeshes" -> {
                for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, info.meshCount());
            }
            case "GetSubUVDetails" -> {
                for (int row = 0; row < count; row++) {
                    writeExternalRaw(args, 0, row, info.subImageBlend() ? -1 : 0);
                    writeExternal(args, 1, row, info.columns());
                    writeExternal(args, 2, row, info.rows());
                }
            }
            case "GetMeshLocalBounds" -> {
                for (int row = 0; row < count; row++) {
                    int mesh = info.meshCount() == 0 ? 0 : registers[args[0]].get(row);
                    for (int axis = 0; axis < 3; axis++) {
                        float minimum = info.minimum(mesh, axis), maximum = info.maximum(mesh, axis);
                        writeExternal(args, 1 + axis, row, minimum);
                        writeExternal(args, 4 + axis, row, maximum);
                        writeExternal(args, 7 + axis, row, maximum - minimum);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown Niagara mesh renderer query " + name);
        }
    }

    private void counterExternal(VectorProgram.External binding, int[] args, int count) {
        if (interfaces == null) throw new IllegalStateException("Niagara counter state is unavailable");
        NiagaraInterfaces.CounterState counter = interfaces.counter(binding.interfaceId(), binding.interfaceData());
        String name = binding.name();
        if (name.equals("Get")) {
            int value = counter.get();
            for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, value);
            return;
        }
        if ((name.equals("Increment") || name.equals("Decrement")) && count > 0 && registers[args[0]].stride == 0) {
            int delta = name.equals("Increment") ? 1 : -1;
            boolean execute = registers[args[0]].get(0) != 0;
            int previous = execute ? counter.add(delta * count) : counter.get();
            for (int row = 0; row < count; row++) {
                int current = previous + (execute ? delta : 0);
                writeExternalRaw(args, 1, row, previous);
                writeExternalRaw(args, 2, row, current);
                previous = current;
            }
        } else {
            for (int row = 0; row < count; row++) {
                if (name.equals("GetNextValue")) {
                    writeExternalRaw(args, 0, row, counter.add(1) + 1);
                    continue;
                }
                boolean execute = registers[args[0]].get(row) != 0;
                switch (name) {
                    case "Set" -> { if (execute) counter.exchange(registers[args[1]].get(row)); }
                    case "Exchange" -> {
                        int previous = execute ? counter.exchange(registers[args[1]].get(row)) : counter.get();
                        writeExternalRaw(args, 2, row, previous);
                    }
                    case "Add", "Increment", "Decrement" -> {
                        int amount = name.equals("Add") ? registers[args[1]].get(row) : name.equals("Increment") ? 1 : -1;
                        int previous = execute ? counter.add(amount) : counter.get();
                        int output = binding.inputs();
                        writeExternalRaw(args, output, row, previous);
                        writeExternalRaw(args, output + 1, row, previous + (execute ? amount : 0));
                    }
                    default -> throw new IllegalArgumentException("Unknown Niagara counter function " + name);
                }
            }
        }
        counter.modified();
    }

    private void particleReadExternal(VectorProgram.External binding, int[] args, int count) {
        var details = binding.interfaceData();
        if (interfaces == null || details == null)
            throw new IllegalStateException("Niagara particle reader state is unavailable");
        var gpuEmitter = interfaces.gpuParticleEmitter(details);
        if (gpuEmitter != null) {
            if (binding.name().equals("GetLocalSpace")) {
                for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, gpuEmitter.localSpace() ? -1 : 0);
                return;
            }
            String gpuType = details.get("value_type").getAsString();
            if (!gpuType.isEmpty()) {
                for (int row = 0; row < count; row++) {
                    int output = binding.inputs();
                    writeExternalRaw(args, output, row, 0);
                    for (int component = 0; component < binding.outputs() - 1; component++)
                        writeExternalRaw(args, output + 1 + component, row, particleReadDefault(gpuType, component));
                }
                return;
            }
            for (int row = 0; row < count; row++) {
                switch (binding.name()) {
                    case "Get Num Particles" -> writeExternalRaw(args, 0, row, interfaces.gpuParticleCount(details, false));
                    case "Get Num Spawned Particles" -> writeExternalRaw(args, 0, row, interfaces.gpuParticleCount(details, true));
                    case "Get ID At Spawn Index" -> {
                        writeExternalRaw(args, 1, row, 0);
                        writeExternalRaw(args, 2, row, 0);
                        writeExternalRaw(args, 3, row, 0);
                    }
                    case "Get Particle Index" -> writeExternalRaw(args, 2, row, -1);
                    case "Get Particle Index From ID Table" -> {
                        writeExternalRaw(args, 1, row, 0);
                        writeExternalRaw(args, 2, row, -1);
                    }
                    default -> throw new IllegalArgumentException("Unknown Niagara particle reader function " + binding.name());
                }
            }
            return;
        }
        NiagaraParticleReadSource emitter = interfaces.particleEmitter(details);
        boolean self = emitter != null && emitterOwner != null && SourceNames.equal(emitterOwner, emitter.particleReadName());
        boolean previous = self && !eventParticleRead;
        VectorDataSet data = emitter == null ? null : emitter.particleReadData(previous);
        int[] ids = emitter == null ? new int[0] : self && eventParticleRead
                ? emitter.eventParticleReadIndex() : emitter.particleReadIndex(previous);
        String name = binding.name(), type = details.get("value_type").getAsString();
        var attribute = emitter == null || type.isEmpty() ? null : emitter.particleReadAttribute(details.get("attribute").getAsString(), type);
        if (emitter != null && !type.isEmpty() && attribute == null)
            interfaces.reportParticleAttribute(emitter.particleReadName(), details.get("attribute").getAsString(), type);
        for (int row = 0; row < count; row++) {
            switch (name) {
                case "GetLocalSpace" -> writeExternalRaw(args, 0, row, emitter != null && emitter.particleReadLocalSpace() ? -1 : 0);
                case "Get Num Particles" -> writeExternalRaw(args, 0, row, data == null ? 0 : data.count());
                case "Get Num Spawned Particles" -> writeExternalRaw(args, 0, row, emitter == null ? 0 : emitter.frameSpawned(previous));
                case "Get ID At Spawn Index" -> {
                    int index = registers[args[0]].get(row);
                    boolean valid = emitter != null && index >= 0 && index < emitter.spawnedParticleCount();
                    writeExternalRaw(args, 1, row, valid ? -1 : 0);
                    writeExternalRaw(args, 2, row, valid ? emitter.spawnedParticleId(index) : 0);
                    writeExternalRaw(args, 3, row, valid ? emitter.spawnedParticleTag() : 0);
                }
                case "Get Particle Index" -> {
                    int id = registers[args[0]].get(row), tag = registers[args[1]].get(row);
                    writeExternalRaw(args, 2, row, emitter == null ? -1 : emitter.particleIndex(data, ids, id, tag));
                }
                case "Get Particle Index From ID Table" -> {
                    int id = registers[args[0]].get(row);
                    int index = id >= 0 && id < ids.length ? ids[id] : -1;
                    writeExternalRaw(args, 1, row, index != -1 ? -1 : 0);
                    writeExternalRaw(args, 2, row, index);
                }
                default -> {
                    boolean byId = name.endsWith(" By ID");
                    int index = registers[args[0]].get(row);
                    if (byId) index = emitter == null ? -1 : emitter.particleIndex(data, ids, index, registers[args[1]].get(row));
                    boolean valid = data != null && attribute != null && index >= 0 && index < data.count();
                    int output = binding.inputs();
                    writeExternalRaw(args, output, row, valid ? -1 : 0);
                    for (int component = 0; component < binding.outputs() - 1; component++) {
                        int value;
                        if (valid) {
                            int source = attribute.component() + component;
                            value = attribute.type() == 2 ? bits(data.half(source, index)) : data.component(attribute.type(), source)[index];
                        } else value = particleReadDefault(type, component);
                        writeExternalRaw(args, output + 1 + component, row, value);
                    }
                }
            }
        }
    }

    private static int particleReadDefault(String type, int component) {
        if (type.equals("ID")) return -1;
        if (type.equals("Color") || type.equals("Quaternion") && component == 3) return bits(1f);
        return 0;
    }

    private void matrixToQuaternion(int[] args, int count) {
        if (matrixToQuaternionValues == null) matrixToQuaternionValues = new float[20];
        var values = matrixToQuaternionValues;
        for (int row = 0; row < count; row++) {
            for (int input = 0; input < 16; input++)
                values[input] = floating(registers[args[input]].get(row));
            NiagaraFastPathMath.matrixToQuaternion(values);
            for (int output = 16; output < 20; output++)
                writeExternal(args, output, row, values[output]);
        }
    }

    private void cameraExternal(VectorProgram.External binding, int[] args, int count) {
        if (interfaces == null) throw new IllegalStateException("Niagara camera interface state is unavailable");
        switch (binding.name()) {
            case "GetFieldOfView" -> {
                float value = interfaces.fieldOfView(binding);
                for (int row = 0; row < count; row++) writeExternal(args, 0, row, value);
            }
            case "GetSplitscreenInfo" -> {
                int value = interfaces.splitscreen(binding);
                for (int row = 0; row < count; row++) if (args[0] != 0xffff) registers[args[0]].set(row, value);
            }
            case "GetCameraPropertiesCPU/GPU" -> {
                float[] values = interfaces.cameraProperties(binding);
                // Saved programs before viewport size and near clip were appended declare only the four vectors.
                for (int row = 0; row < count; row++) for (int i = 0; i < binding.outputs(); i++)
                    writeExternal(args, i, row, values[i]);
            }
            case "CalculateParticleDistancesCPU" -> {
                for (int row = 0; row < count; row++) interfaces.queueDistance(binding,
                        registers[args[0]].get(row), registers[args[1]].get(row),
                        floating(registers[args[2]].get(row)), floating(registers[args[3]].get(row)),
                        floating(registers[args[4]].get(row)));
            }
            case "QueryClosestParticlesCPU" -> {
                int limit = count == 0 ? 0 : registers[args[2]].get(0);
                java.util.Set<Long> closestIds = interfaces.closest(binding, limit);
                for (int row = 0; row < count; row++) if (args[3] != 0xffff) {
                    boolean closest = !closestIds.isEmpty() && closestIds.contains(NiagaraInterfaces.id(
                            registers[args[0]].get(row), registers[args[1]].get(row)));
                    registers[args[3]].set(row, closest ? -1 : 0);
                }
            }
            default -> throw new IllegalArgumentException("Unknown Niagara CPU camera function " + binding.name());
        }
    }

    private void arrayExternal(VectorProgram.External binding, int[] args, int count) {
        if (interfaces == null) throw new IllegalStateException("Niagara array interface state is unavailable");
        NiagaraInterfaces.ArrayState array = interfaces.array(binding);
        synchronized (array) {
            int width = array.width;
            switch (binding.name()) {
                case "Length", "LastIndex" -> {
                    int result = array.size() - (binding.name().equals("LastIndex") ? 1 : 0);
                    for (int row = 0; row < count; row++) writeExternalRaw(args, 0, row, result);
                }
                case "IsValidIndex" -> {
                    for (int row = 0; row < count; row++) writeExternalRaw(args, 1, row,
                            array.valid(registers[args[0]].get(row)) ? -1 : 0);
                }
                case "Get" -> {
                    for (int row = 0; row < count; row++) {
                        int index = array.size() == 0 ? 0 : registers[args[0]].get(row);
                        for (int component = 0; component < width; component++)
                            writeExternalRaw(args, 1 + component, row, array.get(index, component));
                    }
                }
                case "Clear" -> array.resize(0);
                case "Resize" -> array.resize(registers[args[0]].get(0));
                case "ResizeAndFill" -> {
                    int old = array.size(), size = registers[args[0]].get(0);
                    boolean newOnly = registers[args[1]].get(0) != 0;
                    array.resize(size);
                    for (int index = newOnly ? old : 0; index < size; index++)
                        for (int component = 0; component < width; component++)
                            array.set(index, component, registers[args[2 + component]].get(0));
                }
                case "SetArrayElem" -> {
                    array.beginWrite();
                    for (int row = 0; row < count; row++) {
                        int index = registers[args[1]].get(row);
                        if (registers[args[0]].get(row) == 0 && array.valid(index))
                            for (int component = 0; component < width; component++)
                                array.set(index, component, registers[args[2 + component]].get(row));
                    }
                }
                case "Add" -> {
                    array.beginWrite();
                    for (int row = 0; row < count; row++) if (registers[args[0]].get(row) == 0 && array.canAdd()) {
                        int index = array.size(); array.resize(Math.addExact(index, 1));
                        for (int component = 0; component < width; component++)
                            array.set(index, component, registers[args[1 + component]].get(row));
                    }
                }
                case "RemoveLastElem" -> {
                    array.beginWrite();
                    for (int row = 0; row < count; row++) {
                        boolean valid = registers[args[0]].get(row) == 0 && array.size() > 0;
                        int index = valid ? array.size() - 1 : 0;
                        for (int component = 0; component < width; component++)
                            writeExternalRaw(args, 1 + component, row, valid ? array.get(index, component) : array.defaults[component]);
                        writeExternalRaw(args, 1 + width, row, valid ? -1 : 0);
                        if (valid) array.resize(index);
                    }
                }
                case "AtomicAdd", "AtomicMin", "AtomicMax" -> {
                    array.beginWrite();
                    for (int row = 0; row < count; row++) {
                        int index = registers[args[1]].get(row), previous = 0, next = 0;
                        if (registers[args[0]].get(row) == 0 && array.valid(index)) {
                            previous = array.get(index, 0);
                            int value = registers[args[2]].get(row);
                            next = switch (binding.name()) {
                                case "AtomicAdd" -> previous + value;
                                case "AtomicMin" -> Math.min(previous, value);
                                case "AtomicMax" -> Math.max(previous, value);
                                default -> throw new IllegalStateException();
                            };
                            array.set(index, 0, next);
                        }
                        writeExternalRaw(args, 3, row, previous); writeExternalRaw(args, 4, row, next);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown Niagara CPU array function " + binding.name());
            }
        }
    }

    private void staticMeshExternal(VectorProgram.External binding, int[] args, int count,
                                    @Nullable NiagaraExternalRandom randomContext) {
        if (interfaces == null) throw new IllegalStateException("Niagara static mesh reader state is unavailable");
        var mesh = interfaces.staticMesh(binding.interfaceId());
        if (binding.name().equals("BuildUvMapping")) {
            if (count == 0) return;
            int channel = registers[args[0]].get(0);
            for (int row = 1; row < count; row++) if (registers[args[0]].get(row) != channel)
                throw new IllegalArgumentException("Niagara Static Mesh Reader UV mapping requires a single UV channel");
            mesh.buildUvMapping(channel);
            return;
        }
        if (binding.name().equals("SetInstanceIndex")) {
            synchronized (mesh) {
                int selected = mesh.instance();
                boolean changed = false;
                for (int row = 0; row < count; row++) {
                    int next = registers[args[0]].get(row);
                    changed |= next != selected;
                    selected = next;
                }
                if (changed) mesh.setInstance(selected);
            }
            return;
        }
        int[] input = new int[binding.inputs()], output = new int[binding.outputs()];
        boolean random = staticMeshRandom(binding.name()), legacyRandom = binding.name().startsWith("DeprecatedRandom");
        for (int row = 0; row < count; row++) {
            for (int word = 0; word < input.length; word++) input[word] = registers[args[word]].get(row);
            if (random) {
                var stream = Objects.requireNonNull(randomContext);
                if (legacyRandom) stream.select(row, 0, 0, -1);
                else stream.select(row, input[0], input[1], input[2]);
                mesh.random(binding.name(), input, output, stream);
            } else mesh.read(binding.name(), input, output);
            for (int word = 0; word < output.length; word++) writeExternalRaw(args, input.length + word, row, output[word]);
        }
    }

    private static boolean staticMeshRandom(String name) { return name.startsWith("Random") || name.startsWith("DeprecatedRandom"); }
    private static boolean channelSpawn(String name) {
        return name.equals("SpawnConditional") || name.equals("SpawnDirect") || name.equals("ScaleSpawnCount");
    }

    private void skeletalMeshExternal(VectorProgram.External binding, int[] args, int count,
                                      @Nullable NiagaraExternalRandom randomContext) {
        if (interfaces == null) throw new IllegalStateException("Niagara skeletal mesh reader state is unavailable");
        var mesh = interfaces.skeletalMesh(binding.interfaceId());
        int[] input = new int[binding.inputs()], output = new int[binding.outputs()];
        boolean random = binding.name().startsWith("Random");
        for (int row = 0; row < count; row++) {
            for (int word = 0; word < input.length; word++) input[word] = registers[args[word]].get(row);
            if (random) {
                var stream = Objects.requireNonNull(randomContext);
                stream.select(row, input[0], input[1], input[2]);
                mesh.random(binding.name(), output, stream);
            } else mesh.read(binding.name(), input, output);
            for (int word = 0; word < output.length; word++) writeExternalRaw(args, input.length + word, row, output[word]);
        }
    }

    private void collisionExternal(VectorProgram.External binding, int[] args, int count, NiagaraCollisionState collision) {
        boolean async = binding.name().equals(NiagaraCollisionState.ASYNC);
        int first = async ? 1 : 0;
        int output = binding.inputs() + first;
        for (int row = 0; row < count; row++) {
            boolean skip = registers[args[first + 7]].get(row) != 0;
            int query = 0;
            Hit hit = null;
            if (!skip) {
                var start = collision.worldPosition(floating(registers[args[first]].get(row)),
                        floating(registers[args[first + 1]].get(row)), floating(registers[args[first + 2]].get(row)));
                var end = collision.worldPosition(floating(registers[args[first + 3]].get(row)),
                        floating(registers[args[first + 4]].get(row)), floating(registers[args[first + 5]].get(row)));
                int channel = registers[args[first + 6]].get(row);
                boolean complex = registers[args[first + 8]].get(row) != 0;
                if (async) query = collision.submit(start, end, channel, complex);
                else hit = collision.trace(start, end, channel, complex);
            }
            if (async) {
                int previous = registers[args[0]].get(row);
                writeExternalRaw(args, binding.inputs(), row, query);
                hit = collision.result(previous);
            }
            if (hit == null) {
                for (int word = 0; word < 11; word++) writeExternalRaw(args, output + word, row, 0);
                continue;
            }
            writeExternalRaw(args, output, row, -1);
            writeExternalRaw(args, output + 1, row, hit.inside() ? -1 : 0);
            writeExternal(args, output + 2, row, (float)collision.sourceX(hit.position()));
            writeExternal(args, output + 3, row, (float)collision.sourceY(hit.position()));
            writeExternal(args, output + 4, row, (float)collision.sourceZ(hit.position()));
            writeExternal(args, output + 5, row, (float)hit.normal().x);
            writeExternal(args, output + 6, row, (float)hit.normal().z);
            writeExternal(args, output + 7, row, (float)hit.normal().y);
            writeExternal(args, output + 8, row, hit.friction());
            writeExternal(args, output + 9, row, hit.restitution());
            writeExternalRaw(args, output + 10, row, hit.materialId());
        }
    }

    private void writeExternalRaw(int[] args, int output, int row, int value) {
        if (args[output] != 0xffff) registers[args[output]].set(row, value);
    }

    private void writeExternal(int[] args, int output, int row, float value) {
        if (args[output] != 0xffff) registers[args[output]].set(row, bits(value));
    }

    private void arithmetic(Instruction instruction, int[] args, int padded) {
        int n = instruction.inputCount();
        Register destination = registers[args[n]];
        for (int row = 0; row < padded; row++) {
            int a = n > 0 ? registers[args[0]].get(row) : 0, b = n > 1 ? registers[args[1]].get(row) : 0;
            int c = n > 2 ? registers[args[2]].get(row) : 0, d = n > 3 ? registers[args[3]].get(row) : 0;
            int e = n > 4 ? registers[args[4]].get(row) : 0;
            destination.set(row, evaluate(instruction.opcode(), a, b, c, d, e));
        }
    }

    private void duplicate(Instruction instruction, int[] args, int padded) {
        int inputs = instruction.inputCount();
        Register first = registers[args[inputs]], second = registers[args[inputs + 1]];
        for (int row = 0; row < padded; row++) {
            int left = registers[args[0]].get(row);
            int value = instruction.opcode() == Opcode.MUL_2X
                    ? bits(floating(left) * floating(registers[args[1]].get(row))) : left & 1;
            first.set(row, value);
            second.set(row, value);
        }
    }

    private static int evaluate(Opcode opcode, int a, int b, int c, int d, int e) {
        float x = floating(a), y = floating(b), z = floating(c), w = floating(d), v = floating(e);
        return switch (opcode) {
            case ADD -> bits(x + y); case SUB -> bits(x - y); case MUL -> bits(x * y); case DIV -> bits(divide(x, y));
            case MAD -> bits(x * y + z); case LERP -> bits(y * z + x * (1f - z));
            case SQRT -> bits(root(x)); case NEG -> bits(0f - x); case ABS -> a & 0x7fffffff;
            case CEIL -> bits((float)Math.ceil(x)); case FLOOR -> bits((float)Math.floor(x));
            case TRUNC -> bits(truncate(x)); case FRAC -> bits(x - truncate(x));
            case ROUND -> bits((float)Math.rint(x)); case SIGN -> bits(x >= 0 ? 1f : -1f);
            case STEP -> bits(y - x >= 0 ? 1f : 0f); case CLAMP -> bits(min(max(x, y), z));
            // UE 5.8 optimized CPU VectorVM defines all three VVM_noise overloads as zero.
            case NOISE, NOISE2D, NOISE3D -> bits(0f);
            case MIN -> bits(min(x, y)); case MAX -> bits(max(x, y));
            case CMPLT -> mask(x < y); case CMPLE -> mask(x <= y); case CMPGT -> mask(x > y);
            case CMPGE -> mask(x >= y); case CMPEQ -> mask(x == y); case CMPNEQ -> mask(x != y);
            case SELECT -> select(a, b, c);
            case ADDI -> a + b; case SUBI -> a - b; case MULI -> a * b;
            case DIVI -> b == 0 ? 0 : a == Integer.MIN_VALUE && b == -1 ? Integer.MAX_VALUE : a / b;
            case CLAMPI -> Math.min(Math.max(a, b), c); case MINI -> Math.min(a, b); case MAXI -> Math.max(a, b);
            case ABSI -> Math.abs(a); case NEGI -> -a; case SIGNI -> a >= 0 ? 1 : -1;
            case CMPLTI -> mask(a < b); case CMPLEI -> mask(a <= b); case CMPGTI -> mask(a > b);
            case CMPGEI -> mask(a >= b); case CMPEQI -> mask(a == b); case CMPNEQI -> mask(a != b);
            case BIT_AND, LOGIC_AND -> a & b; case BIT_OR, LOGIC_OR -> a | b; case BIT_XOR, LOGIC_XOR -> a ^ b;
            case BIT_NOT, LOGIC_NOT -> ~a; case BIT_LSHIFT -> a << b; case BIT_RSHIFT -> a >>> b;
            case F2I -> integer(x); case I2F -> bits((float)a); case B2I -> a & 1;
            case B2F -> a & 0x3f800000; case F2B -> mask(x > 0); case I2B -> mask(a > 0);
            case FASI, IASF -> a; case HALF_TO_FLOAT -> bits(Float.float16ToFloat((short)a));
            case CMPLT_SELECT -> select(mask(x < y), c, d); case CMPLE_SELECT -> select(mask(x <= y), c, d);
            case CMPEQ_SELECT -> select(mask(x == y), c, d); case CMPLTI_SELECT -> select(mask(a < b), c, d);
            case CMPLEI_SELECT -> select(mask(a <= b), c, d); case CMPEQI_SELECT -> select(mask(a == b), c, d);
            case CMPLT_LOGIC_AND -> mask(x < y) & c; case CMPLE_LOGIC_AND -> mask(x <= y) & c;
            case CMPGT_LOGIC_AND -> mask(x > y) & c; case CMPGE_LOGIC_AND -> mask(x >= y) & c;
            case CMPEQ_LOGIC_AND -> mask(x == y) & c; case CMPNE_LOGIC_AND -> mask(x != y) & c;
            case CMPLTI_LOGIC_AND -> mask(a < b) & c; case CMPLEI_LOGIC_AND -> mask(a <= b) & c;
            case CMPGTI_LOGIC_AND -> mask(a > b) & c; case CMPGEI_LOGIC_AND -> mask(a >= b) & c;
            case CMPEQI_LOGIC_AND -> mask(a == b) & c; case CMPNEI_LOGIC_AND -> mask(a != b) & c;
            case CMPLT_LOGIC_OR -> mask(x < y) | c; case CMPLE_LOGIC_OR -> mask(x <= y) | c;
            case CMPGT_LOGIC_OR -> mask(x > y) | c; case CMPGE_LOGIC_OR -> mask(x >= y) | c;
            case CMPEQ_LOGIC_OR -> mask(x == y) | c; case CMPNE_LOGIC_OR -> mask(x != y) | c;
            case CMPLTI_LOGIC_OR -> mask(a < b) | c; case CMPLEI_LOGIC_OR -> mask(a <= b) | c;
            case CMPGTI_LOGIC_OR -> mask(a > b) | c; case CMPGEI_LOGIC_OR -> mask(a >= b) | c;
            case CMPEQI_LOGIC_OR -> mask(a == b) | c;
            case CMPNEI_LOGIC_OR -> mask(a != b) | c;
            case MAD_ADD -> bits((x * y + z) + w); case MAD_SUB0 -> bits((x * y + z) - w);
            case MAD_SUB1 -> bits(w - (x * y + z)); case MAD_MUL -> bits((x * y + z) * w);
            case MAD_SQRT -> bits((float)Math.sqrt(x * y + z)); case MAD_MAD0 -> bits(w * v + (x * y + z));
            case MAD_MAD1 -> bits((x * y + z) * w + v); case MUL_MAD0 -> bits((x * y) * z + w);
            case MUL_MAD1 -> bits(z * w + (x * y)); case MUL_ADD -> bits(x * y + z);
            case MUL_SUB0 -> bits(x * y - z); case MUL_SUB1 -> bits(z - x * y); case MUL_MUL -> bits((x * y) * z);
            case MUL_MAX -> bits(max(x * y, z)); case ADD_MAD1 -> bits(z * w + (x + y));
            case ADD_ADD -> bits((x + y) + z); case SUB_CMPLT1 -> mask(z < x - y);
            case SUB_MUL -> bits((x - y) * z); case SUB_NEG -> bits(0f - (x - y));
            case DIV_MAD0 -> bits(divide(x, y) * z + w); case DIV_MUL -> bits(divide(x, y) * z);
            case MULI_ADDI -> a * b + c; case ADDI_BIT_RSHIFT -> (a + b) >>> c; case ADDI_MULI -> (a + b) * c;
            case I2F_DIV0 -> bits(divide((float)a, y));
            case I2F_DIV1 -> bits(divide(y, (float)a)); case I2F_MUL -> bits((float)a * y);
            case I2F_MAD0 -> bits((float)a * y + z); case I2F_MAD1 -> bits(x * y + (float)c);
            case DIV_F2I -> integer(divide(x, y)); case F2I_MAXI -> Math.max(integer(x), b);
            case F2I_SELECT1 -> select(a, integer(y), c); case F2I_ADDI -> integer(x) + b;
            case BIT_AND_I2F -> bits((float)(a & b)); case BIT_OR_MULI -> (a | b) * c;
            case MAX_F2I -> integer(max(x, y));
            case SELECT_MUL -> bits(floating(select(a, b, c)) * w);
            case SELECT_ADD -> bits(floating(select(a, b, c)) + w);
            case BIT_RSHIFT_BIT_AND -> a >>> b & c;
            case BIT_LSHIFT_BIT_OR -> a << b | c; case NEG_CMPLT -> mask((0f - x) < y);
            default -> throw new IllegalArgumentException("Opcode is not a scalar arithmetic instruction: " + opcode);
        };
    }

    private static float divide(float a, float b) { return Math.abs(b) > EPSILON ? a / b : 0; }
    private static float root(float value) { return value > EPSILON ? (float)Math.sqrt(value) : 0; }
    private static float truncate(float value) { return (float)(value < 0 ? Math.ceil(value) : Math.floor(value)); }
    private static float min(float a, float b) { return a < b ? a : b; }
    private static float max(float a, float b) { return a > b ? a : b; }
    private static int integer(float value) { return value >= -2147483648f && value < 2147483648f ? (int)value : Integer.MIN_VALUE; }
    private static int halfBits(int value) {
        float number = floating(value);
        if (Float.isNaN(number)) return (value >>> 16 & 0x8000) | 0x7e00;
        return Short.toUnsignedInt(Float.floatToFloat16(number));
    }
    private static int select(int mask, int yes, int no) { return mask & yes | ~mask & no; }
    private static int mask(boolean value) { return value ? -1 : 0; }
    private static int bits(float value) { return Float.floatToRawIntBits(value); }
    private float externalFloat(int[] args, int word, int lane) { return floating(registers[args[word]].get(lane)); }
    private static float floating(int value) { return Float.intBitsToFloat(value); }
}
