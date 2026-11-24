package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.client.render.compute.ComputeAccess;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.resource.shader.PreparedFogIntegration;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Each XY invocation retains the original integration order while writing all depth slices. */
public final class FogIntegration implements AutoCloseable {
    private final ComputeRuntime runtime;
    private final ComputeKernel kernel;

    public FogIntegration(PreparedFogIntegration prepared) {
        RenderSystem.assertOnRenderThread();
        runtime = ComputeRuntime.create(RenderSystem.getDevice());
        try { kernel = runtime.createKernel(prepared.shader()); }
        catch (RuntimeException | Error error) { runtime.close(); throw error; }
    }

    ComputeTexture createOutput(FroxelGrid grid) {
        return runtime.createTexture2D("Fog.Integrated", GpuFormat.RGBA16_FLOAT,
                grid.atlas().width(), grid.atlas().height(), 1);
    }

    GpuTextureView integrate(GpuTextureView scattering, GpuBufferSlice uniforms, FroxelGrid grid, ComputeTexture output) {
        RenderSystem.assertOnRenderThread();
        var bindings = ComputeBindings.builder().uniform("FogVolume", uniforms)
                .texture("ScatteringSampler", scattering, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                .image("IntegratedOutput", output, ComputeAccess.WRITE_ONLY, 0, 0, 1).build();
        kernel.dispatch(bindings, grid.x(), grid.y());
        return output.sampledView();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        kernel.close();
        runtime.close();
    }
}
