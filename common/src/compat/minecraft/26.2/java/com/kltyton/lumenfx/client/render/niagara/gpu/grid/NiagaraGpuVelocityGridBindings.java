package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation;
import com.kltyton.lumenfx.client.render.niagara.gpu.grid.NiagaraGpuVelocityGrids.Grid;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraVelocityGrid.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuVelocityGridBindings {
    private static final String CURRENT = "_GridCurrentBuffer";
    private static final String DESTINATION = "_GridDestinationBuffer";
    private static final String SIZE = "_GridSize";
    private static final String TRANSFORM = "_WorldTransform";
    private static final String INVERSE = "_WorldInverse";
    private static final byte[] IDENTITY = matrix(new Matrix4f().get(new float[16]));
    private static final byte[] ZERO_SIZE = size(0, 0, 0);

    private record Binding(String compileName, String identity, String symbol, String sourceName) {
        boolean used(NiagaraGpuComputeStage stage) {
            return stage.hasParameter(symbol + CURRENT) || stage.hasParameter(symbol + DESTINATION)
                    || stage.hasParameter(symbol + SIZE) || stage.hasParameter(symbol + TRANSFORM)
                    || stage.hasParameter(symbol + INVERSE);
        }
    }
    private record Bound(Snapshot state, @Nullable Grid grid) { }
    private record Resource(Binding binding, boolean destination) { }

    private final Map<String, Bound> iterations = new HashMap<>();
    private final Map<String, Binding> symbols;
    private final Map<String, Resource> resources;
    private final Map<Binding, Bound> active = new HashMap<>();
    private final Set<Grid> stageGrids = new HashSet<>();
    private @Nullable NiagaraGpuVelocityGrids owner;

    public NiagaraGpuVelocityGridBindings(JsonObject program) {
        var shaderSymbols = new LinkedHashMap<String, Binding>();
        var descriptors = new HashMap<String, Resource>();
        for (var entry : program.getAsJsonArray("interfaces")) {
            var source = entry.getAsJsonObject();
            if (!source.has("velocity_grid") || !source.has("shader_symbol")) continue;
            String symbol = source.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(source.get("compile_name").getAsString()),
                    source.get("interface_id").getAsString(), symbol, SourceNames.canonical(source.get("parameter_name").getAsString()));
            shaderSymbols.put(symbol, binding);
            descriptors.put(symbol + CURRENT, new Resource(binding, false));
            descriptors.put(symbol + DESTINATION, new Resource(binding, true));
        }
        symbols = Collections.unmodifiableMap(shaderSymbols);
        resources = Map.copyOf(descriptors);
    }

    public void beginTick(@Nullable NiagaraGpuVelocityGrids registry, Map<String, String> selections,
                          Map<String, String> proxyNames, Map<String, Snapshot> snapshots) {
        if (symbols.isEmpty()) return;
        owner = Objects.requireNonNull(registry);
        active.clear();
        iterations.clear();
        var physical = new HashMap<String, Bound>();
        for (var binding : symbols.values()) {
            String identity = selections.getOrDefault(binding.compileName(), binding.identity());
            var bound = physical.computeIfAbsent(identity, id -> {
                var snapshot = Objects.requireNonNull(snapshots.get(id), "Missing Niagara Velocity/Pressure Grid step for " + id);
                return new Bound(snapshot, registry.prepare(id, snapshot));
            });
            active.put(binding, bound);
            iterations.putIfAbsent(proxyNames.getOrDefault(identity, binding.sourceName()), bound);
        }
    }

    public boolean hasIteration(String name) { return iterations.containsKey(name); }

    public NiagaraGpuSimulation.InterfaceIteration iteration(String name) {
        var extent = iterations.get(name).state().size();
        return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(
                nodes(extent.x()), nodes(extent.y()), nodes(extent.z())), null, -1);
    }

    private static int nodes(int cells) { return Math.max(0, Math.addExact(cells, 1)); }

    public void beginStage(CommandEncoder encoder, NiagaraGpuComputeStage stage, boolean first) {
        stageGrids.clear();
        for (var entry : active.entrySet()) {
            var bound = entry.getValue();
            var grid = bound.grid();
            if (grid != null && entry.getKey().used(stage) && stageGrids.add(grid) && first)
                Objects.requireNonNull(owner).beginStage(encoder, grid, bound.state().pressure());
        }
    }

    public void reset(Supplier<NiagaraGpuComputeStage> initialStage) {
        if (active.isEmpty()) return;
        var shader = initialStage.get();
        var reset = new HashSet<Grid>();
        for (var entry : active.entrySet()) {
            var grid = entry.getValue().grid();
            if (grid != null && entry.getKey().used(shader) && reset.add(grid)) Objects.requireNonNull(owner).reset(grid);
        }
    }

    public void finishStage(CommandEncoder encoder) {
        for (var grid : stageGrids) encoder.copyToBuffer(grid.destination().slice(), grid.current().slice());
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target) {
        for (var descriptor : shader.bindings()) {
            var resource = resources.get(descriptor.name());
            if (resource == null) continue;
            var grid = active.get(resource.binding()).grid();
            if (grid == null) grid = Objects.requireNonNull(owner).empty();
            var buffer = resource.destination() ? grid.destination() : grid.current();
            target.storage(descriptor.name(), buffer.slice(), ((ShaderBinding.StorageBuffer)descriptor).access());
        }
    }

    public @Nullable byte[] value(String qualifiedName) {
        String name = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        int split = name.lastIndexOf('_');
        if (split < 0) return null;
        var binding = symbols.get(name.substring(0, split));
        if (binding == null) return null;
        var bound = active.get(binding);
        boolean available = bound.grid() != null;
        var snapshot = bound.state();
        return switch (name.substring(split)) {
            case SIZE -> available ? size(snapshot.size().x(), snapshot.size().y(), snapshot.size().z()) : ZERO_SIZE;
            case TRANSFORM -> available ? matrix(snapshot.transform()) : IDENTITY;
            case INVERSE -> available ? matrix(snapshot.inverse()) : IDENTITY;
            default -> null;
        };
    }

    private static byte[] size(int x, int y, int z) {
        return ByteBuffer.allocate(3 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).putInt(z).array();
    }

    private static byte[] matrix(float[] elements) {
        var bytes = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(elements);
        return bytes.array();
    }

    public void clearSnapshots() {
        active.clear();
        iterations.clear();
        stageGrids.clear();
        owner = null;
    }
}
