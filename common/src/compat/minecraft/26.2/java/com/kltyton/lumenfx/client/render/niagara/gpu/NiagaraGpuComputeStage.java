package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Prepares reflected Niagara constant blocks and records a stage on the host compute queue. */
public final class NiagaraGpuComputeStage {
    private final ComputeKernel kernel;
    private final ShaderProgram shader;
    private final Map<String, NiagaraGpuUniformLayout> uniforms;
    private final Set<String> parameters;

    public NiagaraGpuComputeStage(PreparedNiagaraShaders.Stage prepared, ComputeKernel kernel) {
        this.kernel = kernel;
        this.shader = prepared.shader();
        JsonObject metadata = prepared.metadata();
        JsonObject stage = metadata.getAsJsonArray("stages").get(0).getAsJsonObject();
        var reflected = stage.getAsJsonObject("reflection").getAsJsonArray("uniformBuffers");
        Map<String, NiagaraGpuUniformLayout> layouts = new LinkedHashMap<>();
        for (var binding : prepared.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.UniformBuffer uniform)) continue;
            JsonObject found = null;
            for (var element : reflected) {
                JsonObject block = element.getAsJsonObject();
                if (uniform.name().equals(block.get("name").getAsString())) {
                    if (found != null) throw new IllegalArgumentException("Duplicate Niagara uniform block " + uniform.name());
                    found = block;
                }
            }
            if (found == null) throw new IllegalArgumentException("Missing Niagara uniform layout " + uniform.name());
            layouts.put(uniform.name(), new NiagaraGpuUniformLayout(found));
        }
        uniforms = Map.copyOf(layouts);
        var names = new HashSet<String>();
        for (var binding : shader.bindings()) names.add(binding.name());
        for (var layout : uniforms.values()) layout.addFieldNames(names);
        parameters = Set.copyOf(names);
    }

    public ShaderProgram shader() { return shader; }
    public boolean hasParameter(String name) { return parameters.contains(name); }

    public void dispatch(CommandEncoder encoder, ComputeBindings.Builder resources, Function<String, byte[]> values,
                         int width, int height, int depth) {
        RenderSystem.assertOnRenderThread();
        if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Invalid Niagara dispatch dimensions");
        kernel.dispatch(bind(encoder, resources, values), width, height, depth);
    }

    public ComputeKernel.Dispatch prepareDispatch(CommandEncoder encoder, ComputeBindings.Builder resources,
                                                  Function<String, byte[]> values, int width, int height, int depth) {
        RenderSystem.assertOnRenderThread();
        return new ComputeKernel.Dispatch(bind(encoder, resources, values), width, height, depth);
    }
    public void dispatchIndependentBatch(List<ComputeKernel.Dispatch> requests) { kernel.dispatchIndependentBatch(requests); }

    public void dispatchIndirect(CommandEncoder encoder, ComputeBindings.Builder resources,
                                 Function<String, byte[]> values, GpuBufferSlice arguments) {
        RenderSystem.assertOnRenderThread();
        kernel.dispatchIndirect(bind(encoder, resources, values), arguments);
    }

    private ComputeBindings bind(CommandEncoder encoder, ComputeBindings.Builder resources, Function<String, byte[]> values) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        for (var entry : uniforms.entrySet()) {
            NiagaraGpuUniformLayout layout = entry.getValue();
            GpuBufferSlice slice;
            try (var mapped = encoder.transientMemory().allocateGpuMapped(layout.size(), alignment, GpuBuffer.USAGE_UNIFORM)) {
                layout.write(mapped.data(), values);
                slice = mapped.slice();
            }
            resources.uniform(entry.getKey(), slice);
        }
        return resources.build();
    }
}
