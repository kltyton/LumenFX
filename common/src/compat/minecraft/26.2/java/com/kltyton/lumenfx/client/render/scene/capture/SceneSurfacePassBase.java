package com.kltyton.lumenfx.client.render.scene.capture;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/** Shared RenderPassBackend delegation for {@link SceneSurfacePass}; loader subclasses add NeoForge extensions. */
public abstract class SceneSurfacePassBase implements RenderPassBackend {
    protected final RenderPassBackend delegate;
    private final UnaryOperator<RenderPipeline> pipelines;
    private final Map<String, GpuBufferSlice> uniforms;

    protected SceneSurfacePassBase(RenderPassBackend delegate, UnaryOperator<RenderPipeline> pipelines,
                                   Map<String, GpuBufferSlice> uniforms) {
        this.delegate = delegate;
        this.pipelines = pipelines;
        this.uniforms = Map.copyOf(uniforms);
    }

    @Override public void pushDebugGroup(Supplier<String> label) { delegate.pushDebugGroup(label); }
    @Override public void popDebugGroup() { delegate.popDebugGroup(); }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { delegate.writeTimestamp(pool, index); }
    @Override public void setPipeline(RenderPipeline pipeline) {
        var adapted = pipelines.apply(pipeline);
        delegate.setPipeline(adapted);
        if (uniforms.isEmpty()) return;
        for (var layout : adapted.getBindGroupLayouts()) for (var uniform : layout.getUniforms()) {
            var value = uniforms.get(uniform.name());
            if (value != null) delegate.setUniform(uniform.name(), value);
        }
    }
    @Override public void bindTexture(String name, @Nullable GpuTextureView view, @Nullable GpuSampler sampler) { delegate.bindTexture(name, view, sampler); }
    @Override public void setUniform(String name, GpuBuffer buffer) {
        GpuBufferSlice replacement = uniforms.get(name);
        if (replacement != null) delegate.setUniform(name, replacement); else delegate.setUniform(name, buffer);
    }
    @Override public void setUniform(String name, GpuBufferSlice buffer) {
        delegate.setUniform(name, uniforms.getOrDefault(name, buffer));
    }
    @Override public void enableScissor(int x, int y, int width, int height) { delegate.enableScissor(x, y, width, height); }
    @Override public void disableScissor() { delegate.disableScissor(); }
    @Override public void setVertexBuffer(int slot, @Nullable GpuBufferSlice buffer) { delegate.setVertexBuffer(slot, buffer); }
    @Override public void setIndexBuffer(GpuBuffer buffer, IndexType type) { delegate.setIndexBuffer(buffer, type); }
    @Override public void drawIndexed(int count, int instances, int first, int baseVertex, int firstInstance) { delegate.drawIndexed(count, instances, first, baseVertex, firstInstance); }
    @Override public void draw(int count, int instances, int first, int firstInstance) { delegate.draw(count, instances, first, firstInstance); }
    @Override public void drawIndexedIndirect(GpuBufferSlice commands, int count) { delegate.drawIndexedIndirect(commands, count); }
    @Override public void drawIndirect(GpuBufferSlice commands, int count) { delegate.drawIndirect(commands, count); }
    @Override public void multiDrawIndexed(IntBuffer commands, int instances, int firstInstance, int count) { delegate.multiDrawIndexed(commands, instances, firstInstance, count); }
    @Override public void multiDraw(IntBuffer commands, int instances, int firstInstance, int count) { delegate.multiDraw(commands, instances, firstInstance, count); }
    @Override public void multiDraw(IntBuffer first, IntBuffer counts, int count) { delegate.multiDraw(first, counts, count); }
    @Override public void multiDrawIndexed(PointerBuffer offsets, IntBuffer counts, IntBuffer bases, int count) { delegate.multiDrawIndexed(offsets, counts, bases, count); }
    @Override public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndices,
            @Nullable IndexType defaultType, Collection<String> dynamicUniforms, T argument) {
        delegate.drawMultipleIndexed(draws, defaultIndices, defaultType, dynamicUniforms, argument);
    }
}
