package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid.Extent;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation.InterfaceIteration;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuGrid3DCollections.Grid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollections.Publication;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.GpuFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuGrid3DCollectionBindings {
    private record Function(String attribute, int components) { }
    private record Binding(String compileName, String sourceName, String identity, String symbol, List<Function> functions) { }
    private record Bound(Publication publication, Grid grid, byte[] indices, String sourceName) { }
    private final Map<String, Bound> iterationGrids = new HashMap<>();
    private final Map<String, Binding> symbols;
    private final List<Set<String>> stageInputs, stageOutputs;
    private final Map<Binding, Bound> active = new HashMap<>();
    private final Set<Grid> writing = new HashSet<>();
    private int activeStage = -1;
    private @Nullable NiagaraGpuGrid3DCollections registry;

    public NiagaraGpuGrid3DCollectionBindings(JsonObject program) {
        var resources = new LinkedHashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("grid3d_collection") || !value.has("shader_symbol")) continue;
            String name = SourceNames.canonical(value.get("compile_name").getAsString());
            String source = SourceNames.canonical(value.get("parameter_name").getAsString());
            String symbol = value.get("shader_symbol").getAsString();
            var functions = new ArrayList<Function>();
            for (var entry : value.getAsJsonArray("grid3d_functions")) {
                var function = entry.getAsJsonObject();
                functions.add(new Function(function.get("attribute").getAsString(), function.get("components").getAsInt()));
            }
            var binding = new Binding(name, source, value.get("interface_id").getAsString(), symbol, List.copyOf(functions));
            resources.put(symbol, binding);
        }
        symbols = Collections.unmodifiableMap(resources);
        stageInputs = symbols.isEmpty() ? List.of() : NiagaraGridStages.inputInterfaces(program);
        stageOutputs = symbols.isEmpty() ? List.of() : NiagaraGridStages.outputDestinations(program);
    }

    public void beginTick(@Nullable NiagaraGpuGrid3DCollections grids, Map<String, String> selections,
                          Map<String, String> proxyNames, Map<String, Publication> states) {
        if (symbols.isEmpty()) return;
        registry = Objects.requireNonNull(grids);
        iterationGrids.clear();
        for (var binding : symbols.values()) {
            String identity = selections.getOrDefault(binding.compileName(), binding.identity());
            var state = Objects.requireNonNull(states.get(identity), "Missing Niagara Grid3D Collection step for " + identity);
            var grid = grids.prepare(state.owner(), state.state());
            var indices = ByteBuffer.allocate(Math.max(1, Math.ceilDiv(binding.functions().size(), 4)) * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (var function : binding.functions())
                indices.putInt(state.state().index(function.attribute(), function.components()));
            String sourceName = proxyNames.getOrDefault(identity, binding.sourceName());
            var bound = new Bound(state, grid, indices.array(), sourceName);
            active.put(binding, bound);
            iterationGrids.putIfAbsent(sourceName, bound);
        }
    }

    public boolean hasIteration(String name) { return iterationGrids.containsKey(name); }
    public InterfaceIteration iteration(String name) {
        var layout = iterationGrids.get(name).grid().state.layout();
        return new InterfaceIteration(new Extent(layout.x(), layout.y(), layout.z()), null, -1);
    }

    private boolean writes(Bound bound, int stage) {
        return !bound.publication().reader() && stageOutputs.get(stage).contains(bound.sourceName());
    }

    public boolean active() { return !active.isEmpty(); }

    public boolean matches(int stage, ShaderProgram shader) {
        if (!active()) return true;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageImage image) {
            var binding = symbol(image.name(), "_OutputGrid");
            if (binding == null) continue;
            var bound = active.get(binding);
            GpuFormat format = writes(bound, stage) ? bound.grid().format() : GpuFormat.R32_FLOAT;
            if (image.format() != format) return false;
        }
        return true;
    }

    public void beginStage(int stage, Extent dispatch) {
        if (active.isEmpty()) return;
        activeStage = stage;
        var readers = new HashSet<Grid>();
        for (var bound : active.values()) {
            if (stageInputs.get(stage).contains(bound.sourceName())) readers.add(bound.grid());
        }
        for (var bound : active.values()) {
            var grid = bound.grid();
            if (writes(bound, stage) && writing.add(grid)) grid.beginOutput(readers.contains(grid), dispatch);
        }
    }

    public void reset() {
        var reset = new HashSet<Grid>();
        for (var bound : active.values())
            if (!bound.publication().reader() && reset.add(bound.grid())) bound.grid().reset();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        if (active.isEmpty()) return;
        var grids = Objects.requireNonNull(registry);
        for (var descriptor : shader.bindings()) switch (descriptor) {
            case ShaderBinding.SampledTexture sampled -> {
                for (var entry : active.entrySet()) {
                    String image = entry.getKey().symbol() + "_Grid";
                    if (!sampled.name().equals(image) && !sampled.name().startsWith(image + "_")) continue;
                    var current = entry.getValue().grid().current;
                    target.volume(sampled.name(), current != null ? current : grids.emptyRead(), 0);
                    break;
                }
            }
            case ShaderBinding.StorageImage image -> {
                var binding = symbol(image.name(), "_OutputGrid");
                if (binding == null) continue;
                var bound = active.get(binding);
                var destination = writes(bound, activeStage) ? bound.grid().destination : null;
                target.volumeImage(image.name(), destination != null ? destination : grids.emptyWrite(image.format()), image.access());
            }
            case ShaderBinding.StorageBuffer buffer -> {
                var binding = symbol(buffer.name(), "_PerAttributeData");
                if (binding != null) target.storage(buffer.name(), grids.offsets(active.get(binding).grid()), buffer.access());
            }
            case ShaderBinding.UniformBuffer ignored -> { }
        }
    }

    private @Nullable Binding symbol(String name, String suffix) {
        return name.endsWith(suffix) ? symbols.get(name.substring(0, name.length() - suffix.length())) : null;
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (var entry : active.entrySet()) {
            String prefix = entry.getKey().symbol() + "_";
            if (!name.startsWith(prefix)) continue;
            var bound = entry.getValue();
            var state = bound.grid().state;
            var layout = state.layout();
            float clampX = 0.5f / layout.x(), clampY = 0.5f / layout.y(), clampZ = 0.5f / layout.z();
            byte[] value = switch (name.substring(prefix.length())) {
                case "NumCells" -> words(layout.x(), layout.y(), layout.z());
                case "NumTiles" -> words(layout.tilesX(), layout.tilesY(), layout.tilesZ());
                case "NumAttributes" -> words(layout.attributes());
                case "NumNamedAttributes" -> words(state.namedChannels());
                case "UnitToUV" -> floats(1f / layout.x(), 1f / layout.y(), 1f / layout.z());
                case "CellSize" -> floats(state.cellSize().x(), state.cellSize().y(), state.cellSize().z());
                case "WorldBBoxSize" -> floats(state.bounds().x(), state.bounds().y(), state.bounds().z());
                case "OneOverNumTiles" -> floats(1f / layout.tilesX(), 1f / layout.tilesY(), 1f / layout.tilesZ());
                case "UnitClampMin" -> floats(clampX, clampY, clampZ);
                case "UnitClampMax" -> floats(1f - clampX, 1f - clampY, 1f - clampZ);
                case "AttributeIndices" -> bound.indices();
                default -> null;
            };
            if (value != null) return value;
        }
        return null;
    }

    private static byte[] floats(double x, double y, double z) {
        return words(Float.floatToRawIntBits((float)x), Float.floatToRawIntBits((float)y), Float.floatToRawIntBits((float)z));
    }

    private static byte[] words(int... words) {
        var data = ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) data.putInt(word);
        return data.array();
    }

    public void finishStage() {
        for (var grid : writing) grid.finishOutput();
        writing.clear();
        activeStage = -1;
    }

    public void postSimulate() {
        var copied = new HashSet<Grid>();
        for (var bound : active.values()) {
            if (!bound.publication().reader() && copied.add(bound.grid())) bound.grid().copyOutput(bound.publication().output());
        }
    }

    public void clearSnapshots() {
        active.clear();
        iterationGrids.clear();
        writing.clear();
        activeStage = -1;
        registry = null;
    }
}
