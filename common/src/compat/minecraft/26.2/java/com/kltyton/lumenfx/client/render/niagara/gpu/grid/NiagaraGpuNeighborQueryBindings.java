package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation.InterfaceIteration;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborQueries.Field;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuNeighborQueries.Grid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborQuery.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Selects writer and reader slots by the native stage output destinations. */
public final class NiagaraGpuNeighborQueryBindings {
    private record Binding(String compileName, String parameter, String identity) { }
    private record Buffer(String symbol, Field field) { }
    private record Bound(Grid grid, String sourceName) { }
    private final Map<String, Binding> symbols;
    private final Map<String, Grid> iterationGrids = new HashMap<>();
    private final Map<String, Buffer> buffers;
    private final List<Set<String>> stageOutputs;
    private final Map<String, Bound> active = new LinkedHashMap<>();
    private final Set<Grid> writing = new LinkedHashSet<>();
    private @Nullable NiagaraGpuNeighborQueries registry;
    private long maximumCells;

    public NiagaraGpuNeighborQueryBindings(JsonObject program) {
        var definitions = new LinkedHashMap<String, Binding>();
        var resources = new HashMap<String, Buffer>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("neighbor_query") || !value.has("shader_symbol")) continue;
            String symbol = value.get("shader_symbol").getAsString();
            String parameter = SourceNames.canonical(value.get("parameter_name").getAsString());
            String compile = SourceNames.canonical(value.get("compile_name").getAsString());
            definitions.put(symbol, new Binding(compile, parameter, value.get("interface_id").getAsString()));
            for (var field : Field.values()) resources.put(symbol + "_" + field, new Buffer(symbol, field));
        }
        symbols = Collections.unmodifiableMap(definitions);
        buffers = Map.copyOf(resources);
        stageOutputs = symbols.isEmpty() ? List.of() : NiagaraGridStages.outputDestinations(program);
    }

    public void beginTick(@Nullable NiagaraGpuNeighborQueries owner, Map<String, String> selections, Map<String, String> proxyNames,
                          Map<String, Snapshot> snapshots, long maxCells) {
        if (symbols.isEmpty()) return;
        var grids = Objects.requireNonNull(owner);
        registry = grids;
        maximumCells = maxCells;
        iterationGrids.clear();
        symbols.forEach((symbol, binding) -> {
            String id = selections.getOrDefault(binding.compileName(), binding.identity());
            var grid = grids.prepare(id, Objects.requireNonNull(snapshots.get(id), "Missing Niagara Neighbor Query step: " + id));
            String sourceName = proxyNames.getOrDefault(id, binding.parameter());
            active.put(symbol, new Bound(grid, sourceName));
            iterationGrids.putIfAbsent(sourceName, grid);
        });
    }

    public boolean hasIteration(String name) { return iterationGrids.containsKey(name); }

    public InterfaceIteration iteration(String name) {
        var snapshot = iterationGrids.get(name).requested;
        return new InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(snapshot.x(), snapshot.y(), snapshot.z()), null, -1);
    }

    public void beginStage(int index, int particles) {
        writing.clear();
        if (active.isEmpty()) return;
        var outputs = stageOutputs.get(index);
        var owner = Objects.requireNonNull(registry);
        for (var bound : active.values()) {
            Grid grid = bound.grid();
            if (outputs.contains(bound.sourceName()) && writing.add(grid)) owner.beginOutput(grid, particles, maximumCells);
        }
    }

    public void reset() {
        if (active.isEmpty()) return;
        var owner = Objects.requireNonNull(registry);
        active.values().stream().map(Bound::grid).distinct().forEach(owner::resetSlots);
    }

    public void finishStage(CommandEncoder encoder) {
        if (writing.isEmpty()) return;
        var owner = Objects.requireNonNull(registry);
        for (Grid grid : writing) owner.finishOutput(grid, encoder);
        writing.clear();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        if (active.isEmpty()) return;
        var owner = Objects.requireNonNull(registry);
        for (var binding : shader.bindings()) if (binding instanceof ShaderBinding.StorageBuffer storage) {
            var buffer = buffers.get(storage.name());
            if (buffer == null) continue;
            var grid = Objects.requireNonNull(active.get(buffer.symbol())).grid();
            target.storage(storage.name(), owner.slice(grid, buffer.field(), writing.contains(grid)), storage.access());
        }
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int suffix = name.lastIndexOf('_');
        if (suffix < 0) return null;
        var bound = active.get(name.substring(0, suffix));
        if (bound == null) return null;
        var snapshot = bound.grid().allocated;
        int x = snapshot == null ? 0 : snapshot.x(), y = snapshot == null ? 0 : snapshot.y(), z = snapshot == null ? 0 : snapshot.z();
        return switch (name.substring(suffix + 1)) {
            case "NumCells" -> words(x, y, z);
            case "UnitToUV" -> snapshot == null ? words(0, 0, 0) : words(bits(1f / x), bits(1f / y), bits(1f / z));
            case "CellSize", "WorldBBoxSize" -> words(0, 0, 0);
            case "MaxCellsPerParticleValue" -> words(snapshot == null ? 0 : snapshot.slotsPerParticle());
            default -> null;
        };
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }
    private static byte[] words(int... values) {
        var bytes = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) bytes.putInt(value);
        return bytes.array();
    }

    public void clearSnapshots() { active.clear(); iterationGrids.clear(); writing.clear(); registry = null; }
}
