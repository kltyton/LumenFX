package com.kltyton.lumenfx.client.render.niagara.gpu.splat;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuGaussianSplatWriterBindings {
    private static final String DATA_SUFFIX = "_SplatData";
    private static final byte[] WORKING_TO_SRGB = identity();
    private record Binding(String compileName, String fallback, String symbol) { }
    private record Active(String identity, Snapshot snapshot) { }
    private final Map<String, Binding> symbols;
    private final Map<String, Binding> iterations;
    private final Map<String, Active> active = new HashMap<>();
    private final Map<String, Active> activeIterations = new HashMap<>();
    private @Nullable NiagaraGpuGaussianSplatWriters owner;

    public NiagaraGpuGaussianSplatWriterBindings(JsonObject program) {
        var symbols = new HashMap<String, Binding>();
        var iterations = new LinkedHashMap<String, Binding>();
        var items = program.getAsJsonArray("interfaces");
        if (items != null) for (var item : items) {
            var value = item.getAsJsonObject();
            String type = value.has("class") ? value.get("class").getAsString() : "";
            if (!(value.has("gaussian_splat_writer") || NiagaraGaussianSplatWriter.TYPE.equals(type))
                    || !value.has("shader_symbol")) continue;
            String symbol = value.get("shader_symbol").getAsString();
            String compileName = value.get("compile_name").getAsString();
            var binding = new Binding(SourceNames.canonical(compileName),
                    value.get("interface_id").getAsString(), symbol);
            symbols.put(symbol, binding);
            for (String field : new String[] { "parameter_name", "compile_name", "resolved_parameter_name" }) {
                if (value.has(field)) iterations.putIfAbsent(SourceNames.canonical(value.get(field).getAsString()), binding);
            }
        }
        this.symbols = Map.copyOf(symbols);
        this.iterations = Collections.unmodifiableMap(iterations);
    }

    public void beginTick(@Nullable NiagaraGpuGaussianSplatWriters owner, Map<String, String> selections,
                          Map<String, String> proxyNames, Map<String, Snapshot> writers) {
        if (symbols.isEmpty()) return;
        this.owner = Objects.requireNonNull(owner);
        symbols.forEach((symbol, binding) -> {
            String identity = selections.getOrDefault(binding.compileName(), binding.fallback());
            var snapshot = Objects.requireNonNull(writers.get(identity), "Missing Niagara Gaussian Splat Writer step: " + identity);
            active.put(symbol, new Active(identity, snapshot));
        });
        activeIterations.clear();
        iterations.forEach((name, binding) -> {
            var state = active.get(binding.symbol());
            activeIterations.putIfAbsent(proxyNames.getOrDefault(state.identity(), name), state);
        });
    }

    public boolean declaresIteration(String name) { return iterations.containsKey(name); }

    public NiagaraGpuSimulation.@Nullable InterfaceIteration iteration(String name) {
        var state = activeIterations.get(SourceNames.canonical(name));
        if (state == null) return null;
        int count = state.snapshot().config().count();
        return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(count, 1, 1), null, -1);
    }

    public @Nullable byte[] value(String qualified) {
        if (active.isEmpty()) return null;
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int split = name.lastIndexOf('_');
        if (split < 0) return null;
        var state = active.get(name.substring(0, split));
        if (state == null) return null;
        var config = state.snapshot().config();
        return switch (name.substring(split + 1)) {
            case "NumSplats" -> integer(config.count());
            case "OutputFormat" -> integer(config.quantized() ? 1 : 0);
            case "OutputStride" -> integer(config.stride());
            case "SHDimension" -> integer(config.coefficientCount());
            case "PositionScale" -> bytes(config.parameters(), 0, 12);
            case "PositionBias" -> bytes(config.parameters(), 12, 12);
            case "ScaleScale" -> bytes(config.parameters(), 24, 12);
            case "ScaleBias" -> bytes(config.parameters(), 36, 12);
            case "ColorScale" -> bytes(config.parameters(), 48, 16);
            case "ColorBias" -> bytes(config.parameters(), 64, 16);
            case "SHScale" -> bytes(config.parameters(), 80, 12);
            case "SHBias" -> bytes(config.parameters(), 92, 12);
            case "WorkingColorSpaceToSRGB" -> WORKING_TO_SRGB.clone();
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (active.isEmpty()) return;
        var registry = Objects.requireNonNull(owner);
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer buffer) {
            String name = buffer.name();
            if (!name.endsWith(DATA_SUFFIX)) continue;
            var state = active.get(name.substring(0, name.length() - DATA_SUFFIX.length()));
            if (state != null) resources.storage(name, registry.rows(state.identity(), state.snapshot()), buffer.access());
        }
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] bytes(ByteBuffer source, int offset, int count) {
        var value = new byte[count];
        source.get(offset, value);
        return value;
    }

    private static byte[] identity() {
        var matrix = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) matrix.putFloat(i * 20, 1);
        return matrix.array();
    }

    public void clearSnapshots() {
        owner = null;
        active.clear();
        activeIterations.clear();
    }
}
