package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Prepares one sprite renderer from the emitter's GPU particle dataset. */
public final class NiagaraGpuSpriteDraw implements AutoCloseable {
    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuIndirectDraw indirect;
    private final boolean partialDepth;
    private boolean closed;

    public NiagaraGpuSpriteDraw(String name, NiagaraGpuPrograms programs) {
        this(name, programs, false);
    }

    public NiagaraGpuSpriteDraw(String name, NiagaraGpuPrograms programs, boolean partialDepth) {
        RenderSystem.assertOnRenderThread();
        this.programs = programs;
        this.partialDepth = partialDepth;
        indirect = new NiagaraGpuIndirectDraw(name, programs);
    }

    public static boolean hostVisible(NiagaraDefinition.Renderer renderer, VectorDataSet system,
                                      Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input) {
        return hostVisible(renderer, system, variables, input, RendererParameterDefaults.EMPTY);
    }

    public static boolean hostVisible(NiagaraDefinition.Renderer renderer, VectorDataSet system,
                                      Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                      RendererParameterDefaults defaults) {
        return hostVisible(renderer, system, variables, input, null, defaults);
    }

    public static boolean hostVisible(NiagaraDefinition.Renderer renderer, VectorDataSet system,
                                      Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                      @Nullable NiagaraParameterStore store, RendererParameterDefaults defaults) {
        Integer enabled = NiagaraRendererParameters.integer(renderer.enabledBinding(), true, system, variables, input, store, defaults);
        if (enabled != null && enabled == 0) return false;
        if (renderer.visibilityParticleBinding()) return true;
        Integer visibility = NiagaraRendererParameters.integer(renderer.visibilityBinding(), false, system, variables, input, store, defaults);
        return visibility == null || visibility == renderer.rendererVisibility();
    }

    public NiagaraGpuSpriteFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                                         NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                                         EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                         Vec3 camera, double originX, double originY, double originZ, double time, double depth,
                                         @Nullable GpuBuffer sortedIndices, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                                         boolean stereo, boolean postOpaque) {
        return prepare(encoder, particles, emitter, renderer, definition, material, system, variables, input,
                camera, originX, originY, originZ, time, depth, sortedIndices, 0, culledCounts, culledOffset,
                stereo, postOpaque);
    }

    public NiagaraGpuSpriteFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                                         NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                                         EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                         Vec3 camera, double originX, double originY, double originZ, double time, double depth,
                                         @Nullable GpuBuffer sortedIndices, int sortedOffset,
                                         @Nullable GpuBufferSlice culledCounts, int culledOffset,
                                         boolean stereo, boolean postOpaque) {
        RenderSystem.assertOnRenderThread();
        return prepare(encoder, particles, emitter, renderer, definition, material, system, variables, input, camera,
                originX, originY, originZ, time, depth, sortedIndices, sortedOffset, culledCounts, culledOffset,
                stereo, postOpaque, input.pose());
    }

    public NiagaraGpuSpriteFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                                         NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                                         EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                         Vec3 camera, double originX, double originY, double originZ, double time, double depth,
                                         @Nullable GpuBuffer sortedIndices, int sortedOffset,
                                         @Nullable GpuBufferSlice culledCounts, int culledOffset,
                                         boolean stereo, boolean postOpaque, EffectHandle.Pose previousPose) {
        return prepare(encoder, particles, emitter, renderer, definition, material, system, variables, input, camera,
                originX, originY, originZ, time, depth, sortedIndices, sortedOffset, culledCounts, culledOffset,
                stereo, postOpaque, previousPose, true);
    }

    public NiagaraGpuSpriteFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                                         NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                                         EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                         Vec3 camera, double originX, double originY, double originZ, double time, double depth,
                                         @Nullable GpuBuffer sortedIndices, int sortedOffset,
                                         @Nullable GpuBufferSlice culledCounts, int culledOffset,
                                         boolean stereo, boolean postOpaque, EffectHandle.Pose previousPose, boolean componentVelocity) {
        return prepare(encoder, particles, emitter, renderer, definition, material, system, variables, input, camera,
                originX, originY, originZ, time, depth, sortedIndices, sortedOffset, culledCounts, culledOffset,
                stereo, postOpaque, previousPose, componentVelocity, null);
    }

    public NiagaraGpuSpriteFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                                         NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                                         EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                         Vec3 camera, double originX, double originY, double originZ, double time, double depth,
                                         @Nullable GpuBuffer sortedIndices, int sortedOffset,
                                         @Nullable GpuBufferSlice culledCounts, int culledOffset, boolean stereo,
                                         boolean postOpaque, EffectHandle.Pose previousPose, boolean componentVelocity,
                                         @Nullable NiagaraBounds componentBounds) {
        return prepare(encoder, particles, emitter, renderer, definition, material, system, variables, input, null,
                camera, originX, originY, originZ, time, depth, sortedIndices, sortedOffset, culledCounts, culledOffset,
                stereo, postOpaque, previousPose, componentVelocity, componentBounds);
    }

    public NiagaraGpuSpriteFrame prepare(CommandEncoder encoder, NiagaraGpuParticleView particles,
                                         NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                                         EmitterDefinition definition, MaterialDefinition material, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                                         @Nullable NiagaraParameterStore store,
                                         Vec3 camera, double originX, double originY, double originZ, double time, double depth,
                                         @Nullable GpuBuffer sortedIndices, int sortedOffset,
                                         @Nullable GpuBufferSlice culledCounts, int culledOffset, boolean stereo,
                                         boolean postOpaque, EffectHandle.Pose previousPose, boolean componentVelocity,
                                         @Nullable NiagaraBounds componentBounds) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU sprite draw is closed");
        boolean emitterSource = renderer.sourceMode().equals("Emitter");
        boolean requiresSort = !emitterSource && NiagaraGpuSpriteSorting.sortOffset(emitter.attributes(), renderer,
                definition.sortMode(), NiagaraGpuSpriteSorting.usesTranslucentSort(material)) != -1;
        if (requiresSort && sortedIndices == null)
            throw new IllegalArgumentException("Sorted Niagara GPU sprites require a GPU particle index buffer");
        boolean sorted = sortedIndices != null;
        NiagaraGpuDataBuffer data = particles.current();
        GpuBufferSlice uniforms = NiagaraGpuSpriteUniforms.write(encoder, emitter, renderer, data, system,
                variables, input, store, camera, originX, originY, originZ, time, sorted, sortedOffset, previousPose, componentVelocity);
        int indices = definition.spriteCutout() == null ? 6 : definition.spriteCutout().indexCount();
        GpuBufferSlice command = emitterSource ? null : indirect.prepare(encoder, particles, indices, 0, culledCounts, culledOffset, stereo, postOpaque);
        return NiagaraGpuSpriteFrame.create(material, definition.sortOrder(), renderer.coverage(), depth,
                uniforms, data, programs, sorted, sortedIndices, command, input.actorPosition(), definition.macroUVRadius(), definition.spriteCutout(), input.receivesDecals(),
                MaterialPrimitiveFrame.forMaterial(material, input, previousPose, () -> componentBounds),
                input.firstPerson(), partialDepth);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        indirect.close();
    }
}
