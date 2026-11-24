package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuEngineParameters;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraSpriteInfo;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.Map;
import java.util.function.Function;

/** Submits a stage while its particle dataset remains open through the post-stage callbacks. */
public final class NiagaraGpuPass {
    private NiagaraGpuPass() { }

    public static void dispatch(NiagaraGpuComputeStage stage, NiagaraGpuParticleBuffers particles,
                                NiagaraGpuCurves curves, CommandEncoder encoder, ComputeBindings.Builder resources,
                                GpuBufferSlice dummyInput, Map<String, GpuBufferSlice> curveOverrides,
                                NiagaraGpuEngineParameters engine, NiagaraGpuDispatchParameters dispatch,
                                PreparedNiagaraSpriteInfo sprites, Function<String, byte[]> additionalValues,
                                int width, int height, int depth) {
        bind(stage, particles, curves, resources, dummyInput, curveOverrides);
        stage.dispatch(encoder, resources, values(engine, dispatch, curves, sprites, additionalValues), width, height, depth);
    }

    public static void dispatchIndirect(NiagaraGpuComputeStage stage, NiagaraGpuParticleBuffers particles,
                                        NiagaraGpuCurves curves, CommandEncoder encoder, ComputeBindings.Builder resources,
                                        GpuBufferSlice dummyInput, Map<String, GpuBufferSlice> curveOverrides,
                                        NiagaraGpuEngineParameters engine, NiagaraGpuDispatchParameters dispatch,
                                        PreparedNiagaraSpriteInfo sprites, Function<String, byte[]> additionalValues,
                                        GpuBufferSlice arguments) {
        for (var binding : stage.shader().bindings())
            if (binding instanceof com.kltyton.lumenfx.client.render.compute.ShaderBinding.StorageBuffer storage
                    && storage.name().equals("IndirectDispatchArgs"))
                resources.storage(storage.name(), arguments, storage.access());
        bind(stage, particles, curves, resources, dummyInput, curveOverrides);
        stage.dispatchIndirect(encoder, resources, values(engine, dispatch, curves, sprites, additionalValues), arguments);
    }

    private static void bind(NiagaraGpuComputeStage stage, NiagaraGpuParticleBuffers particles, NiagaraGpuCurves curves,
                             ComputeBindings.Builder resources, GpuBufferSlice dummyInput,
                             Map<String, GpuBufferSlice> curveOverrides) {
        particles.bindParticleData(stage.shader(), resources, dummyInput);
        curves.bind(stage.shader(), resources, curveOverrides, dummyInput);
    }

    private static Function<String, byte[]> values(NiagaraGpuEngineParameters engine,
                                                    NiagaraGpuDispatchParameters dispatch, NiagaraGpuCurves curves,
                                                    PreparedNiagaraSpriteInfo sprites, Function<String, byte[]> additional) {
        return name -> {
            byte[] result = dispatch.value(name);
            if (result == null) result = engine.value(name);
            if (result == null) result = additional.apply(name);
            if (result == null) result = sprites.value(name);
            if (result == null) result = curves.value(name);
            return result;
        };
    }
}
