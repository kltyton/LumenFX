package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraConsoleVariable;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraConsoleVariable.Initialization;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Publishes the native validity and value words for each generated function in shader order. */
final class NiagaraGpuConsoleVariables implements AutoCloseable {
    private static final class Binding {
        final List<NiagaraConsoleVariable> requests;
        final String parameterKey, original;
        List<NiagaraConsoleVariable.Bound> readers = List.of();
        @Nullable Initialization initialization;
        @Nullable String selected;
        @Nullable GpuBuffer data;
        Binding(List<NiagaraConsoleVariable> requests, JsonObject definition) {
            this.requests = List.copyOf(requests);
            parameterKey = SourceNames.canonical(definition.get("compile_name").getAsString());
            original = definition.get("interface_id").getAsString();
        }

        void prepare(Initialization initial, NiagaraPlatformEnvironment tick, Map<String, String> selections) {
            String identity = selections.getOrDefault(parameterKey, original);
            if (initialization == initial && SourceNames.equal(selected, identity)) return;
            var bindingEnvironment = initialization == initial ? tick : initial.environment();
            readers = requests.stream().map(request -> request.bind(bindingEnvironment)).toList();
            initialization = initial;
            selected = identity;
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    NiagaraGpuConsoleVariables(NiagaraGpuPrograms programs, JsonObject metadata) {
        this.programs = programs;
        for (var element : metadata.getAsJsonArray("interfaces")) {
            var definition = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceConsoleVariable".equals(definition.get("class").getAsString())
                    || !definition.has("shader_symbol")) continue;
            var requests = new ArrayList<NiagaraConsoleVariable>();
            for (var item : definition.getAsJsonArray("console_variable_functions")) {
                var request = item.getAsJsonObject();
                requests.add(NiagaraConsoleVariable.read(request.get("name").getAsString(), request));
            }
            String name = definition.get("shader_symbol").getAsString() + "_ConsoleVariableData";
            if (bindings.putIfAbsent(name, new Binding(requests, definition)) != null)
                throw new IllegalArgumentException("Duplicate Niagara console variable shader symbol: " + name);
        }
    }

    void beginTick(CommandEncoder encoder, NiagaraPlatformEnvironment environment,
                   Initialization initialization, Map<String, String> selections) {
        bindings.forEach((name, binding) -> {
            binding.prepare(initialization, environment, selections);
            long bytes = Math.multiplyExact((long)Math.max(binding.requests.size(), 1), 2L * Integer.BYTES);
            if (bytes > programs.runtime().maxStorageBufferBytes())
                throw new IllegalArgumentException("Niagara console variable data exceeds storage capacity: " + name);
            var target = binding.data;
            if (target == null) {
                target = RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
                binding.data = target;
            }
            GpuBufferSlice source;
            try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                var words = upload.data().order(ByteOrder.nativeOrder()).asIntBuffer();
                if (binding.requests.isEmpty()) words.put(0).put(0);
                for (var value : binding.readers) {
                    value.sample(environment);
                    words.put(value.valid() ? 1 : 0).put(value.word());
                }
                source = upload.slice();
            }
            encoder.copyToBuffer(source, target.slice());
        });
    }

    void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var input : shader.bindings()) if (input instanceof ShaderBinding.StorageBuffer storage) {
            var binding = bindings.get(storage.name());
            if (binding != null && binding.data != null) resources.storage(storage.name(), binding.data.slice(), storage.access());
        }
    }

    @Override public void close() {
        for (var binding : bindings.values()) if (binding.data != null) {
            binding.data.close();
            binding.data = null;
        }
    }
}
