package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborGrids.Grid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborGrid.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Selects the physical grid and applies the source stage's SRV/UAV and clear policy. */
public final class NiagaraGpuNeighborGridBindings {
    private record Binding(String name, String sourceName, String identity, String symbol) { }
    private record Bound(String identity, String sourceName, Grid grid) { }
    private record Buffer(Binding binding, boolean counts, boolean output) { }
    private final List<Binding> bindings;
    private final Map<String, Bound> iterationGrids = new HashMap<>();
    private final Map<String, Buffer> buffers;
    private final List<Set<String>> outputs;
    private final Map<Binding, Bound> active = new HashMap<>();
    private Set<String> stageOutputs = Set.of();
    private @Nullable NiagaraGpuNeighborGrids registry;

    public NiagaraGpuNeighborGridBindings(JsonObject program) {
        var bindings = new LinkedHashMap<String, Binding>();
        var resources = new HashMap<String, Buffer>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var value = entry.getAsJsonObject();
            if (!value.has("shader_symbol") || !value.get("class").getAsString().equals("NiagaraDataInterfaceNeighborGrid3D")) continue;
            String name = SourceNames.canonical(value.get("compile_name").getAsString());
            String sourceName = SourceNames.canonical(value.get("parameter_name").getAsString());
            String symbol = value.get("shader_symbol").getAsString();
            var binding = new Binding(name, sourceName, value.get("interface_id").getAsString(), symbol);
            bindings.put(name, binding);
            resources.put(symbol + "_ParticleNeighbors", new Buffer(binding, false, false));
            resources.put(symbol + "_ParticleNeighborCount", new Buffer(binding, true, false));
            resources.put(symbol + "_OutputParticleNeighbors", new Buffer(binding, false, true));
            resources.put(symbol + "_OutputParticleNeighborCount", new Buffer(binding, true, true));
        }
        this.bindings = List.copyOf(bindings.values());
        buffers = Map.copyOf(resources);
        outputs = bindings.isEmpty() ? List.of() : NiagaraGridStages.outputDestinations(program);
    }

    public void beginTick(@Nullable NiagaraGpuNeighborGrids owner, Map<String, String> selections, Map<String, String> proxyNames, Map<String, Snapshot> states,
                          long maximumCells) {
        if (bindings.isEmpty()) return;
        var grids = Objects.requireNonNull(owner);
        registry = grids;
        iterationGrids.clear();
        for (var binding : bindings) {
            String identity = selections.getOrDefault(binding.name(), binding.identity());
            var state = Objects.requireNonNull(states.get(identity), "Missing Niagara Neighbor Grid3D step for " + identity);
            String sourceName = proxyNames.getOrDefault(identity, binding.sourceName());
            var bound = new Bound(identity, sourceName, grids.prepare(identity, state, maximumCells));
            active.put(binding, bound);
            iterationGrids.putIfAbsent(sourceName, bound);
        }
    }

    public boolean hasIteration(String name) { return iterationGrids.containsKey(name); }

    public NiagaraGpuSimulation.InterfaceIteration iteration(String name) {
        var state = iterationGrids.get(name).grid().snapshot();
        return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(state.x(), state.y(), state.z()), null, -1);
    }

    public void clearOutputs(int stage) {
        if (active.isEmpty()) return;
        var outputGrids = new HashSet<String>();
        var grids = Objects.requireNonNull(registry);
        for (var bound : active.values()) {
            if (outputs.get(stage).contains(bound.sourceName()) && outputGrids.add(bound.identity())) grids.clear(bound.grid());
        }
        stageOutputs = outputGrids;
    }

    public void reset() {
        if (active.isEmpty()) return;
        var cleared = new HashSet<String>();
        var grids = Objects.requireNonNull(registry);
        for (var bound : active.values()) if (cleared.add(bound.identity())) grids.clear(bound.grid());
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        if (active.isEmpty()) return;
        var grids = Objects.requireNonNull(registry);
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer storage) {
            var buffer = buffers.get(storage.name());
            if (buffer == null) continue;
            var bound = active.get(buffer.binding());
            var buffers = bound.grid().buffers();
            boolean output = stageOutputs.contains(bound.identity());
            var slice = buffers == null || buffer.output() != output ? (buffer.output() ? grids.emptyWrite(buffer.counts()) : grids.emptyRead())
                    : buffer.counts() ? buffers.counts().slice() : buffers.neighbors().slice();
            target.storage(storage.name(), slice, storage.access());
        }
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (var entry : active.entrySet()) {
            String symbol = entry.getKey().symbol();
            if (!name.startsWith(symbol + "_")) continue;
            var grid = entry.getValue().grid();
            var state = grid.snapshot();
            boolean available = grid.buffers() != null;
            byte[] value = switch (name.substring(symbol.length())) {
                case "_NumCells" -> available ? words(state.x(), state.y(), state.z()) : words(0, 0, 0);
                case "_UnitToUV" -> available ? floats(1f / state.x(), 1f / state.y(), 1f / state.z()) : words(0, 0, 0);
                case "_CellSize" -> available ? floats((float)(state.worldX() / state.x()), (float)(state.worldY() / state.y()),
                        (float)(state.worldZ() / state.z())) : words(0, 0, 0);
                case "_WorldBBoxSize" -> available ? floats((float)state.worldX(), (float)state.worldY(), (float)state.worldZ()) : words(0, 0, 0);
                case "_MaxNeighborsPerCellValue" -> words(available ? state.neighbors() : 0);
                default -> null;
            };
            if (value != null) return value;
        }
        return null;
    }

    private static byte[] floats(float x, float y, float z) {
        return words(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z));
    }

    private static byte[] words(int... values) {
        var data = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) data.putInt(value);
        return data.array();
    }

    public void clearSnapshots() { active.clear(); iterationGrids.clear(); stageOutputs = Set.of(); registry = null; }
}
