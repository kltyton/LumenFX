package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuRasterizationGrids.Grid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraRasterizationGrid.Snapshot;
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

public final class NiagaraGpuRasterizationGridBindings {
    private record Binding(String compileName, String sourceName, String identity, String symbol) { }
    private record Bound(String identity, String sourceName, Grid grid) { }
    private enum Kind { INPUT, OUTPUT, ATTRIBUTES }
    private record Resource(Binding binding, Kind kind) { }

    private final List<Binding> bindings;
    private final Map<String, Bound> iterationGrids = new HashMap<>();
    private final Map<String, Resource> resources;
    private final List<Set<String>> stageDestinations;
    private final Map<Binding, Bound> active = new HashMap<>();
    private Set<String> stageOutputs = Set.of();
    private @Nullable NiagaraGpuRasterizationGrids registry;

    public NiagaraGpuRasterizationGridBindings(JsonObject program) {
        var bindings = new LinkedHashMap<String, Binding>();
        var descriptors = new HashMap<String, Resource>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var value = entry.getAsJsonObject();
            if (!value.has("shader_symbol") || !"NiagaraDataInterfaceRasterizationGrid3D".equals(value.get("class").getAsString())) continue;
            String compileName = SourceNames.canonical(value.get("compile_name").getAsString());
            String sourceName = SourceNames.canonical(value.get("parameter_name").getAsString());
            String symbol = value.get("shader_symbol").getAsString();
            var binding = new Binding(compileName, sourceName, value.get("interface_id").getAsString(), symbol);
            bindings.put(compileName, binding);
            descriptors.put(symbol + "_IntGrid", new Resource(binding, Kind.INPUT));
            descriptors.put(symbol + "_OutputIntGrid", new Resource(binding, Kind.OUTPUT));
            descriptors.put(symbol + "_PerAttributeData", new Resource(binding, Kind.ATTRIBUTES));
        }
        this.bindings = List.copyOf(bindings.values());
        resources = Map.copyOf(descriptors);
        stageDestinations = bindings.isEmpty() ? List.of() : NiagaraGridStages.outputDestinations(program);
    }

    public void beginTick(@Nullable NiagaraGpuRasterizationGrids owner, Map<String, String> selections, Map<String, String> proxyNames,
                          Map<String, Snapshot> states, int maximum) {
        if (bindings.isEmpty()) return;
        var grids = Objects.requireNonNull(owner);
        registry = grids;
        iterationGrids.clear();
        var physical = new HashMap<String, Grid>();
        for (var binding : bindings) {
            String identity = selections.getOrDefault(binding.compileName(), binding.identity());
            var grid = physical.computeIfAbsent(identity, id -> {
                var state = Objects.requireNonNull(states.get(id), "Missing Niagara Rasterization Grid3D step for " + id);
                return grids.prepare(id, state, maximum);
            });
            String sourceName = proxyNames.getOrDefault(identity, binding.sourceName());
            var bound = new Bound(identity, sourceName, grid);
            active.put(binding, bound);
            iterationGrids.putIfAbsent(sourceName, bound);
        }
    }

    public boolean hasIteration(String name) { return iterationGrids.containsKey(name); }

    public NiagaraGpuSimulation.InterfaceIteration iteration(String name) {
        var layout = iterationGrids.get(name).grid().state().layout();
        return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(layout.x(), layout.y(), layout.z()), null, -1);
    }

    public void beginStage(int stage) {
        if (active.isEmpty()) return;
        var outputs = new HashSet<String>();
        for (var bound : active.values()) {
            if (!stageDestinations.get(stage).contains(bound.sourceName()) || !outputs.add(bound.identity())) continue;
            var grid = bound.grid();
            if (grid.volume() != null && grid.state().clearOutput()) grid.volume().clearSigned(grid.state().resetValue());
        }
        stageOutputs = outputs;
    }

    public void reset() {
        var cleared = new HashSet<String>();
        for (var bound : active.values()) {
            var grid = bound.grid();
            if (grid.volume() != null && cleared.add(bound.identity())) grid.volume().clearSigned(grid.state().resetValue());
        }
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        if (active.isEmpty()) return;
        var grids = Objects.requireNonNull(registry);
        for (var descriptor : shader.bindings()) {
            var resource = resources.get(descriptor.name());
            if (resource == null) continue;
            var bound = active.get(resource.binding());
            var grid = bound.grid();
            var volume = grid.volume();
            boolean output = stageOutputs.contains(bound.identity());
            switch (resource.kind()) {
                case INPUT -> target.volume(descriptor.name(), !output && volume != null ? volume : grids.emptyRead(), 0);
                case OUTPUT -> target.volumeImage(descriptor.name(), output && volume != null ? volume : grids.emptyWrite(),
                        ((ShaderBinding.StorageImage)descriptor).access());
                case ATTRIBUTES -> target.storage(descriptor.name(), grids.offsets(grid), ((ShaderBinding.StorageBuffer)descriptor).access());
            }
        }
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (var entry : active.entrySet()) {
            String symbol = entry.getKey().symbol();
            if (!name.startsWith(symbol + "_")) continue;
            var grid = entry.getValue().grid();
            var state = grid.state();
            var layout = state.layout();
            boolean available = grid.volume() != null;
            byte[] value = switch (name.substring(symbol.length())) {
                case "_NumCells" -> available ? words(layout.x(), layout.y(), layout.z()) : words(0, 0, 0);
                case "_UnitToUV" -> available ? words(bits(1f / layout.x()), bits(1f / layout.y()), bits(1f / layout.z())) : words(0, 0, 0);
                case "_Precision" -> words(available ? bits(state.precision()) : 0);
                case "_NumAttributes" -> words(available ? layout.attributes() : 0);
                default -> null;
            };
            if (value != null) return value;
        }
        return null;
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }

    private static byte[] words(int... values) {
        var data = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) data.putInt(value);
        return data.array();
    }

    public void clearSnapshots() {
        active.clear();
        iterationGrids.clear();
        stageOutputs = Set.of();
        registry = null;
    }
}
