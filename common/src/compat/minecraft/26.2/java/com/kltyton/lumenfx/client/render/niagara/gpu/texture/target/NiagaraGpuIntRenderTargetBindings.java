package com.kltyton.lumenfx.client.render.niagara.gpu.texture.target;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid.Extent;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSimulation.InterfaceIteration;
import com.kltyton.lumenfx.client.render.niagara.gpu.texture.target.NiagaraGpuIntRenderTargets.IntTargetPublication;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuIntRenderTargetBindings {
    private static final String IMAGE = "_RWTextureUAV";
    private static final String SIZE = "_TextureSizeAndInvSize";
    private record Binding(String compileName, String identity, String sourceName) { }
    private final Map<String, Binding> symbols;
    private final Map<String, IntTargetPublication> iterationTargets = new HashMap<>();
    private final Map<Binding, IntTargetPublication> active = new HashMap<>();
    private @Nullable NiagaraGpuIntRenderTargets targets;

    public NiagaraGpuIntRenderTargetBindings(JsonObject program) {
        var symbols = new LinkedHashMap<String, Binding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var data = item.getAsJsonObject();
            if (!data.has("int_render_target_2d") || !data.has("shader_symbol")) continue;
            var binding = new Binding(SourceNames.canonical(data.get("compile_name").getAsString()),
                    data.get("interface_id").getAsString(), SourceNames.canonical(data.get("parameter_name").getAsString()));
            symbols.put(data.get("shader_symbol").getAsString(), binding);
        }
        this.symbols = Collections.unmodifiableMap(symbols);
    }

    public void beginTick(@Nullable NiagaraGpuIntRenderTargets targets, Map<String, String> selections,
                          Map<String, String> proxyNames, Map<String, IntTargetPublication> snapshots) {
        if (symbols.isEmpty()) return;
        this.targets = Objects.requireNonNull(targets);
        iterationTargets.clear();
        for (var binding : symbols.values()) {
            String identity = selections.getOrDefault(binding.compileName(), binding.identity());
            var snapshot = Objects.requireNonNull(snapshots.get(identity), "Missing Niagara integer render target step: " + identity);
            active.put(binding, snapshot);
            iterationTargets.putIfAbsent(proxyNames.getOrDefault(identity, binding.sourceName()), snapshot);
        }
    }

    public boolean hasIteration(String name) { return iterationTargets.containsKey(name); }
    public InterfaceIteration iteration(String name) {
        var state = iterationTargets.get(name);
        return new InterfaceIteration(new Extent(state.width(), state.height(), 1), null, -1);
    }

    public @Nullable byte[] value(String qualified) {
        var state = active(qualified.substring(qualified.lastIndexOf('.') + 1), SIZE);
        if (state == null) return null;
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(state.width()).putFloat(state.height())
                .putFloat(1f / state.width()).putFloat(1f / state.height()).array();
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder output) {
        if (active.isEmpty()) return;
        var registry = Objects.requireNonNull(targets);
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageImage image) {
            var target = active(image.name(), IMAGE);
            if (target == null) continue;
            var texture = target.image();
            output.image(image.name(), texture == null ? registry.fallback() : texture, image.access());
        }
    }

    private @Nullable IntTargetPublication active(String name, String suffix) {
        if (!name.endsWith(suffix)) return null;
        var binding = symbols.get(name.substring(0, name.length() - suffix.length()));
        return binding == null ? null : active.get(binding);
    }

    public void clearSnapshots() { active.clear(); iterationTargets.clear(); targets = null; }
}
