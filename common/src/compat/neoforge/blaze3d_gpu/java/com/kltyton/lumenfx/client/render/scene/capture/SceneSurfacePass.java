package com.kltyton.lumenfx.client.render.scene.capture;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassBackend;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Adapts the pipeline while all draw arguments, dynamic uploads and callbacks stay on the original backend. */
public final class SceneSurfacePass extends SceneSurfacePassBase {
    public SceneSurfacePass(RenderPassBackend delegate, UnaryOperator<RenderPipeline> pipelines) {
        this(delegate, pipelines, Map.of());
    }

    public SceneSurfacePass(RenderPassBackend delegate, UnaryOperator<RenderPipeline> pipelines,
                            Map<String, GpuBufferSlice> uniforms) {
        super(delegate, pipelines, uniforms);
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        delegate.setViewport(x, y, width, height);
    }
}
