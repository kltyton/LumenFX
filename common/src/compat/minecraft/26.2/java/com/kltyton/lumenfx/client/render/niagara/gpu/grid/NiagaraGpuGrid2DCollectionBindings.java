package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid.Extent;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation.InterfaceIteration;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid2DCollections.Grid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollections.Publication;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.GpuFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuGrid2DCollectionBindings {
    private record Binding(String compileName, String sourceName, String identity, String symbol, List<Function> functions) { }
    private record Function(String attribute, int width) { }
    private record Bound(Grid grid, Publication data, byte[] indices, String sourceName) { }
    private final Map<String, Binding> symbols;
    private final Map<String, Bound> iterationGrids = new HashMap<>();
    private final List<Set<String>> inputs, outputs;
    private final Map<Binding, Bound> active = new HashMap<>();
    private final Set<Grid> writing = new HashSet<>();
    private @Nullable NiagaraGpuGrid2DCollections registry;
    private int activeStage = -1;

    public NiagaraGpuGrid2DCollectionBindings(JsonObject program) {
        var bySymbol = new LinkedHashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("grid2d_collection") || !value.has("shader_symbol")) continue;
            String name = SourceNames.canonical(value.get("parameter_name").getAsString());
            String symbol = value.get("shader_symbol").getAsString();
            var functions = value.getAsJsonArray("grid2d_functions").asList().stream().map(entry -> {
                var function = entry.getAsJsonObject();
                return new Function(function.get("attribute").getAsString(), function.get("components").getAsInt());
            }).toList();
            var binding = new Binding(SourceNames.canonical(value.get("compile_name").getAsString()), name,
                    value.get("interface_id").getAsString(), symbol, functions);
            bySymbol.put(symbol, binding);
        }
        symbols = Collections.unmodifiableMap(bySymbol);
        inputs = symbols.isEmpty() ? List.of() : NiagaraGridStages.inputInterfaces(program);
        outputs = symbols.isEmpty() ? List.of() : NiagaraGridStages.outputDestinations(program);
    }

    public void beginTick(@Nullable NiagaraGpuGrid2DCollections grids, Map<String, String> selections,
                          Map<String, String> proxyNames, Map<String, Publication> steps) {
        if (symbols.isEmpty()) return;
        registry = Objects.requireNonNull(grids);
        iterationGrids.clear();
        for (var binding : symbols.values()) {
            String identity = selections.getOrDefault(binding.compileName(), binding.identity());
            var data = Objects.requireNonNull(steps.get(identity), "Missing Niagara Grid2D Collection step: " + identity);
            var grid = grids.prepare(data.owner(), data.state());
            var words = ByteBuffer.allocate(Math.max(1, Math.ceilDiv(binding.functions().size(), 4)) * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (var function : binding.functions()) words.putInt(data.state().index(function.attribute(), function.width()));
            String sourceName = proxyNames.getOrDefault(identity, binding.sourceName());
            var bound = new Bound(grid, data, words.array(), sourceName);
            active.put(binding, bound);
            iterationGrids.putIfAbsent(sourceName, bound);
        }
    }

    public boolean hasIteration(String name) { return iterationGrids.containsKey(name); }
    public InterfaceIteration iteration(String name) {
        var state = iterationGrids.get(name).grid().state;
        return new InterfaceIteration(new Extent(state.x(), state.y(), 1), null, -1);
    }
    public boolean active() { return !active.isEmpty(); }
    private boolean writes(Bound bound, int stage) {
        return !bound.data().reader() && outputs.get(stage).contains(bound.sourceName());
    }

    public boolean matches(int stage, ShaderProgram shader) {
        if (!active()) return true;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageImage image) {
            var binding = outputBinding(image.name());
            if (binding == null) continue;
            var bound = active.get(binding);
            if (image.format() != (writes(bound, stage) ? bound.grid().format() : GpuFormat.R32_FLOAT)) return false;
        }
        return true;
    }

    public void beginStage(int stage, Extent elements) {
        if (!active()) return;
        activeStage = stage;
        var readers = new HashSet<Grid>();
        for (var bound : active.values()) {
            if (inputs.get(stage).contains(bound.sourceName())) readers.add(bound.grid());
        }
        for (var bound : active.values()) {
            var grid = bound.grid();
            if (writes(bound, stage) && writing.add(grid)) grid.beginOutput(readers.contains(grid), elements);
        }
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        if (!active()) return;
        var grids = Objects.requireNonNull(registry);
        for (var descriptor : shader.bindings()) {
            if (descriptor instanceof ShaderBinding.StorageImage image) {
                var binding = outputBinding(image.name());
                if (binding == null) continue;
                var bound = active.get(binding);
                var imageTarget = writes(bound, activeStage) ? bound.grid().destination : null;
                target.image(image.name(), imageTarget == null ? grids.emptyWrite(image.format()) : imageTarget, image.access());
            } else if (descriptor instanceof ShaderBinding.SampledTexture sampled) {
                for (var entry : active.entrySet()) {
                    String input = entry.getKey().symbol() + "_Grid";
                    if (!sampled.name().equals(input) && !sampled.name().startsWith(input + "_")) continue;
                    var current = entry.getValue().grid().current;
                    target.texture(sampled.name(), current == null ? grids.emptyRead() : current, grids.sampler());
                    break;
                }
            }
        }
    }

    private @Nullable Binding outputBinding(String name) {
        return name.endsWith("_OutputGrid") ? symbols.get(name.substring(0, name.length() - "_OutputGrid".length())) : null;
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (var entry : active.entrySet()) {
            String prefix = entry.getKey().symbol() + "_";
            if (!name.startsWith(prefix)) continue;
            var bound = entry.getValue();
            var state = bound.grid().state;
            return switch (name.substring(prefix.length())) {
                case "NumAttributes" -> words(state.layers());
                case "NumCells" -> words(state.x(), state.y());
                case "UnitToUV" -> floats(1f / state.x(), 1f / state.y());
                case "CellSize" -> floats(state.cellX(), state.cellY());
                case "WorldBBoxSize" -> floats(state.worldX(), state.worldY());
                case "AttributeIndices" -> bound.indices();
                default -> null;
            };
        }
        return null;
    }

    private static byte[] floats(double x, double y) { return words(Float.floatToRawIntBits((float)x), Float.floatToRawIntBits((float)y)); }
    private static byte[] words(int... words) {
        var bytes = ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) bytes.putInt(word);
        return bytes.array();
    }

    public void reset() {
        var seen = new HashSet<Grid>();
        for (var bound : active.values()) if (!bound.data().reader() && seen.add(bound.grid())) bound.grid().reset();
    }

    public void finishStage() {
        for (var grid : writing) grid.finishOutput();
        writing.clear();
        activeStage = -1;
    }

    public void postSimulate() {
        var seen = new HashSet<Grid>();
        for (var bound : active.values()) if (!bound.data().reader() && seen.add(bound.grid())) bound.grid().publish(bound.data().target());
    }

    public void clearSnapshots() {
        active.clear();
        iterationGrids.clear();
        writing.clear();
        activeStage = -1;
        registry = null;
    }
}
