package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** GPU-generated ribbon geometry and particle data for one renderer and view. */
public record NiagaraGpuRibbonFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth, boolean disableCull,
                                    GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                    GpuBuffer sorted, GpuBuffer groups, GpuBuffer packed, GpuBuffer tangents,
                                    GpuBuffer indirect, GpuBuffer sliceVertices, GpuBuffer indices,
                                     GpuBufferSlice drawArguments, boolean receivesDecals,
                                     com.kltyton.lumenfx.client.render.scene.material.@org.jspecify.annotations.Nullable MaterialPrimitiveFrame materialPrimitive,
                                     boolean firstPerson, boolean readsPartialDepth) {
    public NiagaraGpuRibbonFrame withPointShadowIndices(NiagaraGpuRibbonIndices.Output output) {
        return new NiagaraGpuRibbonFrame(material, sortOrder, coverage, depth, disableCull, uniforms, floatData, halfData,
                sorted, groups, packed, tangents, output.indirectArguments(), sliceVertices, output.indices(),
                output.indirectArguments().slice(3L * Integer.BYTES, 5L * Integer.BYTES),
                receivesDecals, materialPrimitive, firstPerson, readsPartialDepth);
    }

    public NiagaraGpuRibbonFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth, boolean disableCull,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sorted, GpuBuffer groups, GpuBuffer packed, GpuBuffer tangents,
                                 GpuBuffer indirect, GpuBuffer sliceVertices, GpuBuffer indices, GpuBufferSlice drawArguments,
                                 boolean receivesDecals, com.kltyton.lumenfx.client.render.scene.material.@org.jspecify.annotations.Nullable MaterialPrimitiveFrame primitive) {
        this(material, sortOrder, coverage, depth, disableCull, uniforms, floatData, halfData, sorted, groups, packed, tangents,
                indirect, sliceVertices, indices, drawArguments, receivesDecals, primitive, false, false);
    }
    public NiagaraGpuRibbonFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth, boolean disableCull,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sorted, GpuBuffer groups, GpuBuffer packed, GpuBuffer tangents,
                                 GpuBuffer indirect, GpuBuffer sliceVertices, GpuBuffer indices, GpuBufferSlice drawArguments,
                                 boolean receivesDecals) {
        this(material, sortOrder, coverage, depth, disableCull, uniforms, floatData, halfData, sorted, groups, packed, tangents,
                indirect, sliceVertices, indices, drawArguments, receivesDecals, null);
    }
    public NiagaraGpuRibbonFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth, boolean disableCull,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sorted, GpuBuffer groups, GpuBuffer packed, GpuBuffer tangents,
                                 GpuBuffer indirect, GpuBuffer sliceVertices, GpuBuffer indices, GpuBufferSlice drawArguments) {
        this(material, sortOrder, coverage, depth, disableCull, uniforms, floatData, halfData, sorted, groups, packed, tangents,
                indirect, sliceVertices, indices, drawArguments, true);
    }
}
