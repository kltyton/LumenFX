package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.api.client.NiagaraCacheCapture;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelFrame;
import java.util.concurrent.CompletableFuture;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Address;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Destination;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Lease;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Row;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraExternalRandom;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Variable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.HashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One physical channel interface's consume cursor or CPU write allocation. */
public final class NiagaraDataChannelState {
    public record GpuWriter(NiagaraDataChannelDefinition definition, @Nullable Address address, @Nullable ClientLevel world, Vec3 tile,
                            @Nullable Lease lease, @Nullable NiagaraDataChannelCacheCapture recording) implements AutoCloseable {
        public GpuWriter(NiagaraDataChannelDefinition definition, @Nullable Address address, @Nullable ClientLevel world, Vec3 tile,
                         @Nullable Lease lease) {
            this(definition, address, world, tile, lease, null);
        }
        @Override public void close() { if (lease != null) lease.close(); }
    }
    public record Function(List<Variable> variables, int[] offsets, @Nullable Variable selection, int selectionOffset) { }
    private final NiagaraDataChannelDefinition definition;
    private final AtomicInteger consume = new AtomicInteger();
    private final Map<Integer, int[]> writes = new HashMap<>();
    private final Map<Integer, NiagaraDataChannelSpawns> emitterSpawns = new HashMap<>();
    private Map<Integer, List<NiagaraGpuSpawnInfo.Entry>> entries = Map.of();
    private @Nullable Buffer buffer;
    private @Nullable ClientLevel world;
    private List<Row> rows = List.of();
    private List<Row> latestCpuPublication = List.of();
    private Vec3 tile = Vec3.ZERO;
    private boolean canWrite;
    private boolean lastSubtick;
    private boolean readCurrent, autoUpload;
    private IntPredicate activeEmitter = index -> false;
    private int allocation, written;
    private @Nullable NiagaraCacheCapture recordingOwner;
    private @Nullable NiagaraDataChannelCacheCapture recording;

    public NiagaraDataChannelState(NiagaraDataChannelDefinition definition) { this.definition = definition; }
    public NiagaraDataChannelDefinition definition() { return definition; }
    public ComponentTickGroup earliestTickGroup(int forcedReadGroup) {
        if (definition.writer()) return ComponentTickGroup.TG_PrePhysics;
        if (forcedReadGroup >= 0 && forcedReadGroup < ComponentTickGroup.TG_MAX.ordinal())
            return ComponentTickGroup.values()[Math.min(forcedReadGroup, ComponentTickGroup.TG_LastDemotable.ordinal())];
        var channel = definition.channel();
        if (channel == null || !channel.enforceTickOrder()) return ComponentTickGroup.TG_PrePhysics;
        var finalWrite = channel.finalWriteTickGroup();
        return ComponentTickGroup.afterPrerequisite(finalWrite, finalWrite);
    }

    public ComponentTickGroup latestTickGroup() {
        var channel = definition.channel();
        return definition.writer() && channel != null && channel.enforceTickOrder()
                ? channel.finalWriteTickGroup() : ComponentTickGroup.TG_LastDemotable;
    }

    public void capture(@Nullable ClientLevel level, boolean forcePrevious, boolean lastSubtick, boolean autoUpload,
                        Vec3 tile, Vec3 location, @Nullable EffectHandle owner, IntPredicate activeEmitter) {
        this.tile = tile;
        this.autoUpload = autoUpload;
        var previousWorld = world;
        world = level;
        this.lastSubtick = lastSubtick;
        this.activeEmitter = activeEmitter;
        var channel = definition.channel();
        Buffer next = null;
        if (level != null && channel != null) {
            if (buffer != null && level == previousWorld && !definition.refresh()) {
                next = buffer;
            } else {
                var cfg = definition.accessContext();
                Vec3 resolvedSourcePos = cfg != null && cfg.overrideLocation()
                        ? cfg.location()
                        : NiagaraDataChannelIslands.sourcePosition(location);
                boolean overrideSystem = cfg != null && cfg.overrideSystem();
                var systems = overrideSystem ? cfg.systems() : List.<net.minecraft.resources.Identifier>of();
                String selectedSystem = overrideSystem ? cfg.selectedSystem() : "";
                var accessContext = com.kltyton.lumenfx.api.client.NiagaraDataChannels.AccessContext.fromOwner(
                        owner,
                        resolvedSourcePos,
                        cfg != null && cfg.forceAttach(),
                        cfg != null && cfg.overrideCellSize() ? cfg.cellSizeOverride() : null,
                        cfg != null && cfg.overrideBoundsPadding() ? cfg.boundsPaddingOverride() : null,
                        cfg != null ? cfg.gameplayTag() : "",
                        systems.isEmpty() ? null : systems.getFirst(),
                        systems,
                        overrideSystem,
                        selectedSystem);
                boolean readOnly = !definition.writer();
                boolean autoLink = readOnly && definition.autoLink();
                next = NiagaraDataChannelWorlds.channel(level, channel, resolvedSourcePos, accessContext, owner, readOnly, autoLink);
            }
        }
        if (next != buffer) {
            releaseBuffer();
            if (next != null) {
                next.retain();
                if (definition.gpuSpawningReader()) next.registerGpuSpawningReader();
            }
        }
        buffer = next;
        readCurrent = definition.readCurrent() && !forcePrevious;
        rows = buffer == null || definition.writer() ? List.of() : buffer.read(Destination.CPU, readCurrent);
        consume.set(0);
        writes.clear();
        written = 0;
        emitterSpawns.clear();
        entries = Map.of();
        allocation = buffer != null && definition.writer() ? definition.allocationCount() : 0;
        canWrite = !definition.onlyFinalSubtick() || lastSubtick;
    }

    public Function bind(List<Variable> variables, String selectedName, String selectedType) {
        var schema = definition.channel();
        var offsets = new int[variables.size()];
        for (int index = 0; index < offsets.length; index++) offsets[index] = schema == null ? -1 : schema.offset(variables.get(index));
        Variable selection = null;
        if (schema != null && !selectedName.isEmpty()) {
            for (var variable : schema.variables()) if (variable.name().equals(selectedName) && variable.type().equals(selectedType)) {
                selection = variable;
                break;
            }
            if (selection == null)
                throw new IllegalArgumentException("Missing typed Niagara data channel spawn variable: " + selectedName + " in " + schema.path());
        }
        return new Function(variables, offsets, selection, selection == null ? -1 : schema.offset(selection));
    }

    public void execute(String name, Function function, int[] input, int[] output) {
        switch (name) {
            case "Num" -> output[0] = definition.writer() ? allocation : rows.size();
            case "Read" -> read(function, input[0], output, 1);
            case "Consume" -> {
                int index = input[0] == 0 ? -1 : consume.getAndUpdate(current -> Math.min(rows.size(), current + 1));
                output[1] = index >= 0 && index < rows.size() ? index : -1;
                read(function, output[1], output, 2);
            }
            case "Write", "Append" -> output[0] = input[0] != 0 && write(name.equals("Append"), function, input) ? -1 : 0;
            case "GetNDCSpawnData" -> {
                var spawn = emitterSpawns.get(input[0]);
                if (spawn == null) Arrays.fill(output, -1);
                else spawn.lookup(input[1], output);
            }
            default -> throw new IllegalArgumentException("Unknown Niagara data channel VM operation " + name);
        }
    }

    public void spawn(String operation, Function function, int[] input, NiagaraExternalRandom random) {
        if (input[0] == 0 || rows.isEmpty() || !activeEmitter.test(input[1])) return;
        boolean scale = operation.equals("ScaleSpawnCount");
        boolean conditional = operation.equals("SpawnConditional");
        if (!scale && definition.spawnOnce() && !lastSubtick) return;
        int mode = input[2];
        if (mode < 0 || mode > 1) return;
        var counts = emitterSpawns.computeIfAbsent(input[1], ignored -> new NiagaraDataChannelSpawns(rows.size(), scale));
        for (int index = 0; index < rows.size(); index++) {
            var data = rows.get(index).words();
            if (conditional) {
                if (conditions(function, data, input)) {
                    int min = Math.max(input[4], 0), max = Math.max(input[5], 0);
                    int count = min + (int)(random.nextFromStream() * ((long)max - min + 1));
                    counts.count(index, count, mode == 1);
                }
                continue;
            }
            var variable = function.selection();
            if (variable == null) continue;
            float value = magnitude(variable, data, function.selectionOffset());
            float factor = Float.intBitsToFloat(input[3]), maximum = Float.intBitsToFloat(input[4]);
            value *= factor + random.nextFromStream() * (maximum - factor);
            if (scale) {
                float min = Math.max(0, Float.intBitsToFloat(input[5]));
                float max = Float.intBitsToFloat(input[6]);
                counts.scale(index, nativeClamp(value, min, max < 0 ? Float.MAX_VALUE : max), mode == 1);
            } else {
                int min = Math.max(input[5], 0), max = input[6] < 0 ? Integer.MAX_VALUE : input[6];
                int count = (int)value;
                counts.count(index, count < min ? min : Math.min(count, max), mode == 1);
            }
        }
    }

    public void publishSpawns() {
        if (emitterSpawns.isEmpty()) return;
        var next = new HashMap<Integer, List<NiagaraGpuSpawnInfo.Entry>>();
        emitterSpawns.forEach((emitter, counts) -> next.put(emitter, counts.publish(definition.spawnGroups())));
        entries = Map.copyOf(next);
    }

    public List<NiagaraGpuSpawnInfo.Entry> spawns(int emitter) { return entries.getOrDefault(emitter, List.of()); }

    public NiagaraDataChannelGpuSnapshot gpuSnapshot(int emitterCount) {
        var source = buffer;
        if (source != null) source.usesGpu();
        return new NiagaraDataChannelGpuSnapshot(source == null ? null : source.address, world, readCurrent,
                source == null ? List.of() : source.readGpu(readCurrent, autoUpload), emitterSpawns, emitterCount, source);
    }
    public GpuWriter gpuWriter() {
        var source = buffer;
        if (source != null && definition.publishGpu()) source.usesGpu();
        return new GpuWriter(definition, source == null ? null : source.address, world, tile, source == null ? null : source.lease(), recording);
    }

    public void recordCache(@Nullable NiagaraCacheCapture capture) {
        if (!definition.writer() || definition.channel() == null || recordingOwner == capture) return;
        recordingOwner = capture;
        recording = capture == null ? null : new NiagaraDataChannelCacheCapture();
    }

    public void discardCacheFrame() { if (recording != null) recording.discard(); }

    public CompletableFuture<ChannelFrame> captureCache(boolean rebase) {
        return Objects.requireNonNull(recording, "Niagara data channel cache was not activated").take(definition, rebase);
    }

    public ChannelFrame captureCurrentCache(boolean rebase) {
        return NiagaraDataChannelCacheCapture.encode(definition, latestCpuPublication, rebase);
    }

    public void close() {
        releaseBuffer();
        buffer = null;
        world = null;
        recording = null;
        recordingOwner = null;
        latestCpuPublication = List.of();
    }

    private void releaseBuffer() {
        if (buffer == null) return;
        if (definition.gpuSpawningReader()) buffer.unregisterGpuSpawningReader();
        buffer.release();
    }

    private static float nativeClamp(float value, float min, float max) { return value < min ? min : value < max ? value : max; }

    private static float magnitude(Variable variable, int[] data, int start) {
        if (variable.width() == 1) {
            return switch (variable.registers().getFirst()) {
                case INT, BOOL -> (float)Math.abs((long)data[start]);
                case FLOAT -> Math.abs(Float.intBitsToFloat(data[start]));
                case HALF -> Math.abs(Float.float16ToFloat((short)data[start]));
            };
        }
        float squared = 0;
        for (int i = 0; i < variable.width(); i++) {
            float value = Float.intBitsToFloat(data[start + i]);
            squared += value * value;
        }
        return (float)Math.sqrt(squared);
    }

    private static boolean conditions(Function function, int[] data, int[] input) {
        int from = 6;
        for (int field = 0; field < function.offsets().length; field++) {
            int target = function.offsets()[field];
            if (target < 0) return false;
            for (var register : function.variables().get(field).registers()) {
                int a = input[from++], b = data[target++];
                double left = switch (register) {
                    case FLOAT -> Float.intBitsToFloat(a);
                    case HALF -> Float.float16ToFloat((short)a);
                    default -> a;
                };
                double right = switch (register) {
                    case FLOAT -> Float.intBitsToFloat(b);
                    case HALF -> Float.float16ToFloat((short)b);
                    default -> b;
                };
                boolean match = switch (input[3]) {
                    case 0 -> left == right;
                    case 1 -> left != right;
                    case 2 -> left < right;
                    case 3 -> left <= right;
                    case 4 -> left > right;
                    case 5 -> left >= right;
                    default -> false;
                };
                if (!match) return false;
            }
        }
        return true;
    }

    private void read(Function function, int index, int[] output, int first) {
        Arrays.fill(output, first, output.length, 0);
        if (index < 0 || index >= rows.size()) { output[0] = 0; return; }
        var row = rows.get(index);
        int next = first;
        boolean success = true;
        for (int field = 0; field < function.offsets().length; field++) {
            int from = function.offsets()[field];
            var variable = function.variables().get(field);
            if (from < 0) success = false;
            else System.arraycopy(row.words(), from, output, next, variable.width());
            next += variable.width();
        }
        output[0] = success ? -1 : 0;
    }

    private synchronized boolean write(boolean append, Function function, int[] input) {
        var schema = definition.channel();
        if (!canWrite || schema == null) return false;
        int index = append ? written : input[1];
        if (index < 0 || index >= allocation) return false;
        var row = writes.computeIfAbsent(index, ignored -> new int[schema.width()]);
        boolean success = true;
        int from = append ? 1 : 2;
        for (int field = 0; field < function.offsets().length; field++) {
            int target = function.offsets()[field], count = function.variables().get(field).width();
            if (target < 0) success = false;
            else System.arraycopy(input, from, row, target, count);
            from += count;
        }
        written = Math.max(written, index + 1);
        return success;
    }

    public void postSimulate() {
        var target = buffer;
        if (written == 0 || target == null) {
            latestCpuPublication = List.of();
            return;
        }
        var values = new ArrayList<Row>(written);
        var zero = new int[target.definition.width()];
        for (int index = 0; index < written; index++) values.add(new Row(writes.getOrDefault(index, zero), tile));
        target.submit(values, definition);
        latestCpuPublication = List.copyOf(values);
        if (recording != null) recording.cpu(values);
        writes.clear();
        written = 0;
    }

}
