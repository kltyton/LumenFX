package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.effect.render.SpriteCutout;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Prepared GPU sprite draw, including the renderer's actual sort key and indirect command. */
public record NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                    GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                    GpuBuffer sortedIndices, @Nullable GpuBufferSlice command,
                                    Vec3 actorPosition, double macroUVRadius, @Nullable SpriteCutout cutout, boolean receivesDecals,
                                    com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame materialPrimitive,
                                    boolean firstPerson, boolean readsPartialDepth, int maxInstances) {
    public NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sortedIndices, @Nullable GpuBufferSlice command,
                                 Vec3 actorPosition, double macroUVRadius, @Nullable SpriteCutout cutout, boolean receivesDecals,
                                 com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame materialPrimitive,
                                 boolean firstPerson, boolean readsPartialDepth) {
        this(material, sortOrder, coverage, depth, uniforms, floatData, halfData, sortedIndices, command,
                actorPosition, macroUVRadius, cutout, receivesDecals, materialPrimitive, firstPerson, readsPartialDepth,
                command == null ? 1 : Integer.MAX_VALUE);
    }
    public NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sortedIndices, @Nullable GpuBufferSlice command,
                                 Vec3 actorPosition, double macroUVRadius, @Nullable SpriteCutout cutout, boolean receivesDecals,
                                 com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive) {
        this(material, sortOrder, coverage, depth, uniforms, floatData, halfData, sortedIndices, command,
                actorPosition, macroUVRadius, cutout, receivesDecals, primitive, false, false);
    }
    public NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sortedIndices, @Nullable GpuBufferSlice command,
                                 Vec3 actorPosition, double macroUVRadius, @Nullable SpriteCutout cutout, boolean receivesDecals) {
        this(material, sortOrder, coverage, depth, uniforms, floatData, halfData, sortedIndices, command, actorPosition, macroUVRadius, cutout, receivesDecals, null);
    }
    public NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sortedIndices, @Nullable GpuBufferSlice command,
                                 Vec3 actorPosition, double macroUVRadius, @Nullable SpriteCutout cutout) {
        this(material, sortOrder, coverage, depth, uniforms, floatData, halfData, sortedIndices, command, actorPosition, macroUVRadius, cutout, true);
    }
    public NiagaraGpuSpriteFrame {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(uniforms, "uniforms");
        Objects.requireNonNull(floatData, "floatData");
        Objects.requireNonNull(halfData, "halfData");
        Objects.requireNonNull(sortedIndices, "sortedIndices");
        Objects.requireNonNull(actorPosition, "actorPosition");
    }

    public NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sortedIndices, @Nullable GpuBufferSlice command) {
        this(material, sortOrder, coverage, depth, uniforms, floatData, halfData, sortedIndices, command, Vec3.ZERO, 0);
    }

    public NiagaraGpuSpriteFrame(MaterialDefinition material, int sortOrder, SpriteCoverage coverage, double depth,
                                 GpuBufferSlice uniforms, GpuBuffer floatData, GpuBuffer halfData,
                                 GpuBuffer sortedIndices, @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius) {
        this(material, sortOrder, coverage, depth, uniforms, floatData, halfData, sortedIndices, command, actorPosition, macroUVRadius, null);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command) {
        return create(material, sortOrder, coverage, depth, uniforms, particles, programs, sorted, sortedIndices, command, Vec3.ZERO, 0);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius) {
        return create(material, sortOrder, coverage, depth, uniforms, particles, programs, sorted, sortedIndices,
                command, actorPosition, macroUVRadius, null);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius,
                                               @Nullable SpriteCutout cutout) {
        return create(material, sortOrder, coverage, depth, uniforms, particles, programs, sorted, sortedIndices,
                command, actorPosition, macroUVRadius, cutout, true);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius,
                                               @Nullable SpriteCutout cutout, boolean receivesDecals) {
        return create(material, sortOrder, coverage, depth, uniforms, particles, programs, sorted, sortedIndices,
                command, actorPosition, macroUVRadius, cutout, receivesDecals, null);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius,
                                               @Nullable SpriteCutout cutout, boolean receivesDecals,
                                               com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive) {
        return create(material, sortOrder, coverage, depth, uniforms, particles, programs, sorted, sortedIndices,
                command, actorPosition, macroUVRadius, cutout, receivesDecals, primitive, false);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius,
                                               @Nullable SpriteCutout cutout, boolean receivesDecals,
                                               com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive,
                                               boolean firstPerson) {
        return create(material, sortOrder, coverage, depth, uniforms, particles, programs, sorted, sortedIndices,
                command, actorPosition, macroUVRadius, cutout, receivesDecals, primitive, firstPerson, false);
    }

    public static NiagaraGpuSpriteFrame create(MaterialDefinition material, int sortOrder, SpriteCoverage coverage,
                                               double depth, GpuBufferSlice uniforms, NiagaraGpuDataBuffer particles,
                                               NiagaraGpuPrograms programs, boolean sorted, @Nullable GpuBuffer sortedIndices,
                                               @Nullable GpuBufferSlice command, Vec3 actorPosition, double macroUVRadius,
                                               @Nullable SpriteCutout cutout, boolean receivesDecals,
                                               com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitive,
                                               boolean firstPerson, boolean readsPartialDepth) {
        if (sorted && sortedIndices == null) throw new IllegalArgumentException("Sorted Niagara sprites require GPU indices");
        GpuBuffer dummy = programs.dummyBuffer();
        return new NiagaraGpuSpriteFrame(material, sortOrder, coverage, depth, uniforms,
                particles.floats() == null ? dummy : particles.floats(),
                particles.halves() == null ? dummy : particles.halves(),
                sortedIndices == null ? dummy : sortedIndices, command, actorPosition, macroUVRadius, cutout,
                receivesDecals, primitive, firstPerson, readsPartialDepth,
                command == null ? 1 : Math.max(1, particles.stride()));
    }
}
