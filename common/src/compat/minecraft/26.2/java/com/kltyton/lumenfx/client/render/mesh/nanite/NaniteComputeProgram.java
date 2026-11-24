package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.Map;

final class NaniteComputeProgram implements AutoCloseable {
    private final ComputeKernel kernel;
    private final NiagaraGpuComputeStage stage;

    NaniteComputeProgram(ComputeRuntime runtime, PreparedNiagaraShaders.Stage source, int workgroup) {
        var shader = source.shader();
        if (shader.workgroupX() != workgroup || shader.workgroupY() != 1 || shader.workgroupZ() != 1)
            throw new IllegalArgumentException("Invalid Nanite compute workgroup");
        kernel = runtime.createKernel(shader);
        try { stage = new NiagaraGpuComputeStage(source, kernel); }
        catch (RuntimeException | Error failure) {
            kernel.close();
            throw failure;
        }
    }

    void dispatch(CommandEncoder encoder, Map<String, GpuBufferSlice> buffers, Map<String, byte[]> uniforms,
                  int width, int rows) {
        var bindings = ComputeBindings.builder();
        for (var binding : stage.shader().bindings()) {
            if (binding instanceof ShaderBinding.StorageBuffer storage) {
                var slice = buffers.get(storage.name());
                if (slice == null) throw new IllegalArgumentException("Missing Nanite compute binding " + storage.name());
                bindings.storage(storage.name(), slice, storage.access());
            }
        }
        stage.dispatch(encoder, bindings, name -> uniforms.get(name.substring(name.lastIndexOf('.') + 1)), width, rows, 1);
    }

    @Override public void close() { kernel.close(); }
}
