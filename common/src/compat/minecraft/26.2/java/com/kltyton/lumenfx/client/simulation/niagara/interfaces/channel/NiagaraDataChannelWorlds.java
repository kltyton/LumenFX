package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.api.client.NiagaraDataChannels;
import com.kltyton.lumenfx.api.client.NiagaraDataChannels.AccessContext;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.NiagaraDataChannels.Visibility;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Address;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Current and previous data channel publications are owned by the client world. */
public final class NiagaraDataChannelWorlds {
    enum Destination { GAME, CPU, GPU, GPU_ONLY }
    record Row(int[] words, Vec3 tile, double @Nullable [] gameValues) {
        Row(int[] words, Vec3 tile) { this(words, tile, null); }
    }
    private record Write(List<Row> rows, boolean game, boolean cpu, boolean gpu) { }

    public record GpuInput(NiagaraDataChannelGpuSnapshot snapshot, boolean cpuUpdated) { }

    public static final class Lease implements AutoCloseable {
        private @Nullable Buffer buffer;
        private Lease(Buffer buffer) { this.buffer = buffer; buffer.retain(); }
        @Override public void close() {
            var retained = buffer;
            buffer = null;
            if (retained != null) retained.release();
        }
    }

    static final class Buffer {
        final Channel definition;
        final Address address;
        final Vec3 tile;
        private final @Nullable Runnable onPublish;
        private final List<Write> pending = new ArrayList<>();
        private final List<Write> pendingFromGpu = new ArrayList<>();
        private final List<Write> unpublishedToGpu = new ArrayList<>();
        private final List<Row> pendingGpuRows = new ArrayList<>();
        private boolean pendingGpuCpuUpdate;
        private final EnumMap<Destination, List<Row>> current = new EnumMap<>(Destination.class);
        private final EnumMap<Destination, List<Row>> previous = new EnumMap<>(Destination.class);
        private boolean gpuUsed;
        private int gpuSpawningReaders;
        private int users;

        Buffer(Address address, Vec3 tile, @Nullable Runnable onPublish) {
            this.address = address;
            definition = address.channel();
            this.tile = tile;
            this.onPublish = onPublish;
        }
        void retain() { users++; }
        void release() { users--; }
        Lease lease() { return new Lease(this); }
        boolean inUse() {
            return users > 0 || !pending.isEmpty() || !pendingFromGpu.isEmpty() || !current.isEmpty() || !previous.isEmpty();
        }
        void usesGpu() { gpuUsed = true; }
        void registerGpuSpawningReader() { gpuSpawningReaders++; usesGpu(); }
        void unregisterGpuSpawningReader() { gpuSpawningReaders--; }

        List<Row> read(Destination destination, boolean currentFrame) {
            return (currentFrame ? current : previous).getOrDefault(destination, List.of());
        }
        List<Row> readGpu(boolean currentFrame, boolean autoUpload) {
            if (!autoUpload || gpuSpawningReaders == 0) return read(Destination.GPU, currentFrame);
            var cpu = read(Destination.CPU, currentFrame);
            var gpuOnly = read(Destination.GPU_ONLY, currentFrame);
            if (cpu.isEmpty()) return gpuOnly;
            if (gpuOnly.isEmpty()) return cpu;
            var combined = new ArrayList<Row>(cpu);
            combined.addAll(gpuOnly);
            return List.copyOf(combined);
        }
        void submit(List<Row> rows, NiagaraDataChannelDefinition writer) {
            pending.add(new Write(List.copyOf(rows), writer.publishGame(), writer.publishCpu(), writer.publishGpu()));
        }
        void flush() {
            if (pending.isEmpty()) return;
            for (Destination destination : Destination.values()) {
                ArrayList<Row> next = null;
                for (var write : pending) {
                    boolean selected = switch (destination) {
                        case GAME -> write.game();
                        case CPU -> write.cpu();
                        case GPU -> write.gpu();
                        case GPU_ONLY -> write.gpu() && !write.cpu();
                    };
                    if (!selected) continue;
                    if (next == null) next = new ArrayList<>(read(destination, true));
                    next.addAll(write.rows());
                }
                if (next != null) current.put(destination, List.copyOf(next));
            }
            for (var write : pending) if (write.cpu() || write.gpu()) unpublishedToGpu.add(write);
            pending.clear();
            if (onPublish != null) onPublish.run();
        }
        private void queueGpuInputs(boolean autoUpload) {
            if (gpuUsed) {
                boolean includeCpu = autoUpload && gpuSpawningReaders != 0;
                int firstNewRow = pendingGpuRows.size();
                if (includeCpu) for (var write : unpublishedToGpu)
                    if (write.cpu()) pendingGpuRows.addAll(write.rows());
                for (var write : unpublishedToGpu)
                    if (write.gpu() && (!includeCpu || !write.cpu())) pendingGpuRows.addAll(write.rows());
                pendingGpuCpuUpdate |= pendingGpuRows.size() != firstNewRow;
            }
            unpublishedToGpu.clear();
        }

        GpuInput takeGpuInput(ClientLevel level, boolean autoUpload) {
            flush();
            queueGpuInputs(autoUpload);
            var rows = List.copyOf(pendingGpuRows);
            var snapshot = new NiagaraDataChannelGpuSnapshot(address, level, true, rows, Map.of(), 0);
            var input = new GpuInput(snapshot, pendingGpuCpuUpdate);
            pendingGpuRows.clear();
            pendingGpuCpuUpdate = false;
            return input;
        }

        void advance(boolean autoUpload) {
            flush();
            queueGpuInputs(autoUpload);
            // The render dispatcher must observe a completed empty CPU frame too.
            pendingGpuCpuUpdate |= gpuUsed;
            previous.clear();
            if (definition.keepPrevious()) previous.putAll(current);
            current.clear();
            pending.addAll(pendingFromGpu);
            pendingFromGpu.clear();
        }
    }

    record AttachedHandlerOwner(@Nullable Object parentOwnerKey, @Nullable NiagaraBounds parentBounds,
                                @Nullable Supplier<Pose> parentPose, @Nullable BooleanSupplier parentValid) { }

    static final class World {
        final ClientLevel level;
        final Map<Address, Buffer> channels = new HashMap<>();
        final Map<EffectHandle, Address> handlerOwners = new IdentityHashMap<>();
        final Map<EffectHandle, AttachedHandlerOwner> attachedHandlers = new IdentityHashMap<>();
        final Map<Channel, NiagaraDataChannelIslands> islands = new HashMap<>();
        final Map<Channel, NiagaraDataChannelGameplayBursts> gameplayBursts = new HashMap<>();
        final Map<String, Channel> definitions = new HashMap<>();
        long islandSequence;
        double time = Double.NaN;
        World(ClientLevel level) { this.level = level; }
        void close() {
            islands.values().forEach(NiagaraDataChannelIslands::close);
            islands.clear();
            gameplayBursts.values().forEach(NiagaraDataChannelGameplayBursts::close);
            gameplayBursts.clear();
            channels.clear();
            handlerOwners.clear();
            attachedHandlers.clear();
        }
    }

    private static final Map<ClientLevel, World> WORLDS = new IdentityHashMap<>();
    private NiagaraDataChannelWorlds() { }

    static World world(ClientLevel level) {
        return WORLDS.computeIfAbsent(level, World::new);
    }

    static @Nullable Buffer channel(ClientLevel level, Channel definition, Vec3 location, @Nullable EffectHandle handler) {
        return channel(level, definition, location, AccessContext.at(NiagaraDataChannelIslands.sourcePosition(location)), handler);
    }

    static @Nullable Buffer channel(ClientLevel level, Channel definition, Vec3 sourceLocation, AccessContext context, @Nullable EffectHandle handler) {
        return channel(level, definition, sourceLocation, context, handler, false, handler != null);
    }

    static @Nullable Buffer channel(ClientLevel level, Channel definition, Vec3 sourceLocation, AccessContext context,
                                    @Nullable EffectHandle handler, boolean autoLink) {
        return channel(level, definition, sourceLocation, context, handler, autoLink, autoLink);
    }

    static @Nullable Buffer channel(ClientLevel level, Channel definition, Vec3 sourceLocation, AccessContext context,
                                    @Nullable EffectHandle handler, boolean readOnly, boolean autoLink) {
        var world = WORLDS.computeIfAbsent(level, World::new);
        var prior = world.definitions.putIfAbsent(definition.path(), definition);
        if (prior != null && !prior.equals(definition))
            throw new IllegalArgumentException("Conflicting Niagara data channel layouts for " + definition.path());
        if (definition.gameplayBurst() != null) {
            if (autoLink) {
                var linked = handler == null ? null : world.handlerOwners.get(handler);
                return linked != null && linked.channel().equals(definition) ? world.channels.get(linked) : null;
            }
            return world.gameplayBursts.computeIfAbsent(definition, ignored -> new NiagaraDataChannelGameplayBursts(world, definition))
                    .acquire(sourceLocation, context, handler);
        }
        if (definition.islands() == null) {
            var address = new Address(definition, 0);
            return world.channels.computeIfAbsent(address, ignored -> new Buffer(address, Vec3.ZERO, null));
        }
        // UE 5.9 UNiagaraDataChannelHandler_Islands uses legacy SearchParams (no bIsAutoLinkingSystem):
        // ReadOnly checks Island.IsHandlerSystem(Owner) first, then falls through to FindOrCreateIsland by location.
        if (readOnly && handler != null) {
            var linked = world.handlerOwners.get(handler);
            if (linked != null && linked.channel().equals(definition)) return Objects.requireNonNull(world.channels.get(linked));
        }
        return world.islands.computeIfAbsent(definition, ignored -> new NiagaraDataChannelIslands(world, definition)).acquire(sourceLocation);
    }

    public static void advance(ClientLevel level, double time, boolean autoUpload) {
        var world = WORLDS.computeIfAbsent(level, World::new);
        if (time == world.time) return;
        world.gameplayBursts.values().forEach(NiagaraDataChannelGameplayBursts::syncAttached);
        if (!Double.isNaN(world.time)) world.channels.values().forEach(buffer -> buffer.advance(autoUpload));
        world.islands.values().forEach(NiagaraDataChannelIslands::retireUnused);
        world.gameplayBursts.values().forEach(NiagaraDataChannelGameplayBursts::retireUnused);
        world.time = time;
    }

    public static void flush(ClientLevel level) {
        var world = WORLDS.get(level);
        if (world != null) world.channels.values().forEach(Buffer::flush);
    }

    public static Map<Address, GpuInput> takeGpuInputs(ClientLevel level, boolean autoUpload) {
        var world = WORLDS.get(level);
        if (world == null || world.channels.isEmpty()) return Map.of();
        var inputs = new HashMap<Address, GpuInput>();
        for (var channel : world.channels.values()) if (channel.gpuUsed)
            inputs.put(channel.address, channel.takeGpuInput(level, autoUpload));
        return Map.copyOf(inputs);
    }

    public static @Nullable Lease retain(ClientLevel level, Address address) {
        var world = WORLDS.get(level);
        var channel = world == null ? null : world.channels.get(address);
        return channel == null ? null : channel.lease();
    }

    public static void publishGpuReadback(ClientLevel level, Address address, NiagaraDataChannelDefinition writer, List<int[]> values, Vec3 tile) {
        var world = WORLDS.get(level);
        var definition = writer.channel();
        if (world == null || definition == null || values.isEmpty()) return;
        var channel = world.channels.get(address);
        if (channel == null || !channel.definition.equals(definition)) return;
        var rows = new ArrayList<Row>(values.size());
        int width = definition.width();
        for (var value : values) {
            if (value.length != width) throw new IllegalArgumentException("Niagara channel GPU readback does not match its source schema");
            rows.add(new Row(value, tile));
        }
        channel.pendingFromGpu.add(new Write(List.copyOf(rows), writer.publishGame(), writer.publishCpu(), false));
    }

    public static void publishGame(ClientLevel level, Channel schema, Vec3 location, List<NiagaraDataChannels.Row> data, Visibility visibility) {
        publishGame(level, schema, AccessContext.at(location), data, visibility);
    }

    public static void publishGame(ClientLevel level, Channel schema, AccessContext context, List<NiagaraDataChannels.Row> data, Visibility visibility) {
        Objects.requireNonNull(visibility);
        if (data.isEmpty() || !visibility.game() && !visibility.cpu() && !visibility.gpu()) return;
        Vec3 loc = context.location() != null ? context.location()
                : context.ownerLocation() != null ? context.ownerLocation() : Vec3.ZERO;
        var channel = channel(level, schema, NiagaraDataChannelIslands.sourcePosition(loc), context, null);
        if (channel == null) return;
        var rows = new ArrayList<Row>(data.size());
        for (var value : data) rows.add(gameRow(schema, value, channel.tile));
        channel.pending.add(new Write(List.copyOf(rows), visibility.game(), visibility.cpu(), visibility.gpu()));
    }

    public static List<NiagaraDataChannels.Row> readGame(ClientLevel level, Channel schema, Vec3 location, boolean previous) {
        return readGame(level, schema, AccessContext.at(location), previous);
    }

    public static List<NiagaraDataChannels.Row> readGame(ClientLevel level, Channel schema, AccessContext context, boolean previous) {
        Vec3 loc = context.location() != null ? context.location()
                : context.ownerLocation() != null ? context.ownerLocation() : Vec3.ZERO;
        var channel = channel(level, schema, NiagaraDataChannelIslands.sourcePosition(loc), context, null);
        if (channel == null) return List.of();
        var rows = channel.read(Destination.GAME, !previous);
        var result = new ArrayList<NiagaraDataChannels.Row>(rows.size());
        for (var row : rows) {
            int at = 0;
            var values = new HashMap<String, List<Number>>();
            for (var variable : schema.variables()) {
                var components = new ArrayList<Number>(variable.width());
                boolean position = variable.type().equals("/script/niagara.niagaraposition");
                for (int component = 0; component < variable.width(); component++, at++) {
                    int word = row.words()[at];
                    switch (variable.registers().get(component)) {
                        case FLOAT -> {
                            var game = row.gameValues();
                            double value = game == null ? Float.intBitsToFloat(word) : game[at];
                            if (game == null && position) value += coordinate(row.tile(), component);
                            components.add(value);
                        }
                        case INT -> components.add(word);
                        case BOOL -> components.add(word != 0 ? -1 : 0);
                        case HALF -> components.add(Float.float16ToFloat((short)word));
                    }
                }
                values.put(variable.name(), components);
            }
            result.add(new NiagaraDataChannels.Row(values));
        }
        return List.copyOf(result);
    }

    private static Row gameRow(Channel schema, NiagaraDataChannels.Row source, Vec3 tile) {
        var fields = new HashMap<>(source.fields());
        int width = schema.width(), at = 0;
        var words = new int[width];
        var gameValues = new double[width];
        for (var variable : schema.variables()) {
            var components = fields.remove(variable.name());
            if (components == null) { at += variable.width(); continue; }
            if (components.size() != variable.width())
                throw new IllegalArgumentException("Wrong component count for Niagara data channel field " + variable.name());
            for (int index = 0; index < components.size(); index++, at++) {
                var value = components.get(index);
                gameValues[at] = value.doubleValue();
                words[at] = switch (variable.registers().get(index)) {
                    case FLOAT -> variable.type().equals("/script/niagara.niagaraposition")
                            ? Float.floatToRawIntBits((float)(value.doubleValue() - coordinate(tile, index)))
                            : Float.floatToRawIntBits(value.floatValue());
                    case HALF -> Short.toUnsignedInt(Float.floatToFloat16(value.floatValue()));
                    case INT -> integer(value, variable.name());
                    case BOOL -> value.doubleValue() != 0 ? -1 : 0;
                };
            }
        }
        if (!fields.isEmpty()) throw new IllegalArgumentException("Unknown Niagara data channel fields: " + fields.keySet());
        return new Row(words, tile, gameValues);
    }

    private static int integer(Number value, String field) {
        long integral = value.longValue();
        if (integral != value.doubleValue() || integral < Integer.MIN_VALUE || integral > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid int32 for Niagara data channel field " + field);
        return (int)integral;
    }

    private static double coordinate(Vec3 position, int axis) {
        return switch (axis) {
            case 0 -> position.x;
            case 1 -> position.y;
            case 2 -> position.z;
            default -> throw new IllegalArgumentException("Niagara position has an invalid component count");
        };
    }

    public static void clear(ClientLevel level) {
        var world = WORLDS.remove(level);
        if (world != null) world.close();
    }
    public static void clear() { WORLDS.values().forEach(World::close); WORLDS.clear(); }
}
