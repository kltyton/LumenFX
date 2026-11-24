package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import org.jspecify.annotations.Nullable;

/** Frame references stay live through the final indexed mesh draw. */
public record NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                                  GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                                  NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                                   boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands, boolean receivesDecals,
                                   com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame materialPrimitive,
                                   boolean firstPerson,
                                   com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.@Nullable Renderer sceneRenderer,
                                   com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshEmitterSceneData emitterScene,
                                   com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms.@Nullable Output preparedScene,
                                   boolean readsPartialDepth) {
    public NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                               GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                               NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                               boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands,
                               boolean receivesDecals, com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive,
                               boolean firstPerson, com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.@Nullable Renderer sceneRenderer,
                               com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshEmitterSceneData emitterScene) {
        this(material, sortOrder, depth, geometry, uniforms, floatData, halfData, sortedIndices, particles,
                culledCounts, culledOffset, emitterSource, stereo, postOpaque, commands, receivesDecals, primitive,
                firstPerson, sceneRenderer, emitterScene, null, false);
    }
    public NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                               GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                               NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                               boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands,
                               boolean receivesDecals, com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive,
                               boolean firstPerson, com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.@Nullable Renderer sceneRenderer) {
        this(material, sortOrder, depth, geometry, uniforms, floatData, halfData, sortedIndices, particles,
                culledCounts, culledOffset, emitterSource, stereo, postOpaque, commands, receivesDecals, primitive, firstPerson, sceneRenderer, null);
    }
    public NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                               GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                               NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                               boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands,
                               boolean receivesDecals, com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive,
                               boolean firstPerson) {
        this(material, sortOrder, depth, geometry, uniforms, floatData, halfData, sortedIndices, particles,
                culledCounts, culledOffset, emitterSource, stereo, postOpaque, commands, receivesDecals, primitive, firstPerson, null);
    }
    public NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                               GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                               NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                               boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands,
                               boolean receivesDecals, com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive) {
        this(material, sortOrder, depth, geometry, uniforms, floatData, halfData, sortedIndices, particles,
                culledCounts, culledOffset, emitterSource, stereo, postOpaque, commands, receivesDecals, primitive, false);
    }
    public NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                               GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                               NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                               boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands, boolean receivesDecals) {
        this(material, sortOrder, depth, geometry, uniforms, floatData, halfData, sortedIndices, particles,
                culledCounts, culledOffset, emitterSource, stereo, postOpaque, commands, receivesDecals, null);
    }
    public NiagaraGpuMeshFrame(MaterialDefinition material, int sortOrder, double depth, MeshBatchGeometry geometry,
                               GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData, GpuBuffer sortedIndices,
                               NiagaraGpuParticleView particles, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                               boolean emitterSource, boolean stereo, boolean postOpaque, NiagaraGpuMeshDraw.Commands commands) {
        this(material, sortOrder, depth, geometry, uniforms, floatData, halfData, sortedIndices, particles,
                culledCounts, culledOffset, emitterSource, stereo, postOpaque, commands, true);
    }
    public @Nullable GpuBufferSlice command(CommandEncoder encoder, int indices) {
        return emitterSource && preparedScene == null ? null : commands.prepare(encoder, particles, indices, culledCounts, culledOffset, stereo, postOpaque);
    }
    public NiagaraGpuMeshFrame withScene(com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms.Output prepared,
                                         NiagaraGpuSortCollector.Sorted compacted) {
        return new NiagaraGpuMeshFrame(material, sortOrder, depth, geometry, uniforms, floatData, halfData, compacted.indices(),
                particles, compacted.culledCounts(), compacted.culledOffset(), emitterSource, stereo, postOpaque, commands,
                receivesDecals, materialPrimitive, firstPerson, sceneRenderer, emitterScene, prepared, readsPartialDepth);
    }
}
