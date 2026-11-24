package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Generates packed ribbon indices and GPU-owned indexed draw arguments. */
public final class NiagaraGpuRibbonIndices implements AutoCloseable {
    private final String name;
    private final NiagaraGpuPrograms programs;
    private GpuBuffer indices, indirect;
    private long capacity;
    private boolean closed;

    public record Shape(GpuBuffer triangleVertices, int triangleVertexCount, int trianglesPerSegment,
                        int verticesPerSlice, int shapeBits, int shapeMask, int segmentShift, int segmentMask,
                        int subSegmentShift, int subSegmentMask) { }

    public record Tessellation(int ribbonMode, int mode, boolean constantFactor, int customFactor,
                               float customMinAngle, boolean customScreenSpace, int maximumFactor,
                               float defaultAngle, float screenPercentage, boolean enabled,
                               float minimumDisplacementError, int maxSubSegments, float viewDistance, float lodDistanceFactor) {
        public Tessellation withLodDistanceFactor(float factor) {
            return new Tessellation(ribbonMode, mode, constantFactor, customFactor, customMinAngle, customScreenSpace,
                    maximumFactor, defaultAngle, screenPercentage, enabled, minimumDisplacementError, maxSubSegments, viewDistance, factor);
        }
    }

    public record Input(NiagaraGpuParticleView particles, GpuBuffer sortedIndices,
                        NiagaraGpuRibbonAggregation.Output vertices, int executableInstances,
                        NiagaraGpuRibbonSort.IdType idType, Shape shape, Tessellation tessellation) { }

    public record Output(GpuBuffer indices, GpuBuffer indirectArguments) { }

    public NiagaraGpuRibbonIndices(String name, NiagaraGpuPrograms programs) {
        this.name = name;
        this.programs = programs;
    }

    public Output generate(CommandEncoder encoder, Input input) {
        RenderSystem.assertOnRenderThread();
        if (closed || input.executableInstances() < 2 || input.tessellation().maxSubSegments() < 1
                || input.shape().trianglesPerSegment() < 1)
            throw new IllegalArgumentException("Invalid Niagara ribbon index generation input");
        long indexCount = Math.multiplyExact(Math.multiplyExact((long) input.executableInstances() - 1,
                input.tessellation().maxSubSegments()), Math.multiplyExact(input.shape().trianglesPerSegment(), 3));
        ensureCapacity(indexCount);
        dispatch(encoder, input, "initialize", "default", false);
        String variant = switch (input.idType()) {
            case NONE -> "single";
            case NIAGARA_ID -> "id";
            case INTEGER -> "integer";
        };
        dispatch(encoder, input, "generate", variant, true);
        return new Output(indices, indirect);
    }

    private void dispatch(CommandEncoder encoder, Input input, String pass, String variant, boolean indirectDispatch) {
        NiagaraGpuComputeStage stage = programs.ribbonIndices(pass, variant);
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice buffer = switch (storage.name()) {
                case "EmitterParticleCountsBuffer" -> input.particles().countBuffer();
                case "IndirectDrawOutput", "IndirectDrawInfo" -> indirect.slice();
                case "VertexGenerationResults" -> input.vertices().commands().slice();
                case "GeneratedIndicesBuffer" -> indices.slice();
                case "SortedIndices" -> input.sortedIndices().slice();
                case "MultiRibbonIndices" -> input.vertices().ribbonIndices().slice();
                case "Segments" -> input.vertices().segments().slice();
                case "TriangleToVertexIds" -> input.shape().triangleVertices().slice();
                default -> throw new IllegalArgumentException("Unknown Niagara ribbon index buffer " + storage.name());
            };
            bindings.storage(storage.name(), buffer, storage.access());
        }
        java.util.function.Function<String, byte[]> values = qualified -> {
            String field = qualified.substring(qualified.lastIndexOf('.') + 1);
            Shape shape = input.shape(); Tessellation tess = input.tessellation();
            return switch (field) {
                case "TotalNumParticlesDirect" -> word(input.executableInstances());
                case "EmitterParticleCountsBufferOffset" -> word(input.particles().currentCountOffset());
                case "IndirectDrawOutputIndex", "VertexGenerationResultsIndex", "IndexBufferOffset", "IndirectDrawInfoIndex" -> word(0);
                case "IndexGenThreadSize" -> word(64);
                case "TrianglesPerSegment" -> word(shape.trianglesPerSegment());
                case "TriangleToVertexIdsCount" -> word(shape.triangleVertexCount());
                case "NumVerticesInSlice" -> word(shape.verticesPerSlice());
                case "BitsNeededForShape" -> word(shape.shapeBits());
                case "BitMaskForShape" -> word(shape.shapeMask());
                case "SegmentBitShift" -> word(shape.segmentShift());
                case "SegmentBitMask" -> word(shape.segmentMask());
                case "SubSegmentBitShift" -> word(shape.subSegmentShift());
                case "SubSegmentBitMask" -> word(shape.subSegmentMask());
                case "RibbonTessellationMode" -> word(tess.ribbonMode());
                case "TessellationMode" -> word(tess.mode());
                case "bCustomUseConstantFactor" -> word(tess.constantFactor() ? 1 : 0);
                case "CustomTessellationFactor" -> word(tess.customFactor());
                case "CustomTessellationMinAngle" -> real(tess.customMinAngle());
                case "bCustomUseScreenSpace" -> word(tess.customScreenSpace() ? 1 : 0);
                case "GNiagaraRibbonMaxTessellation" -> word(tess.maximumFactor());
                case "GNiagaraRibbonTessellationAngle" -> real(tess.defaultAngle());
                case "GNiagaraRibbonTessellationScreenPercentage" -> real(tess.screenPercentage());
                case "GNiagaraRibbonTessellationEnabled" -> word(tess.enabled() ? 1 : 0);
                case "GNiagaraRibbonTessellationMinDisplacementError" -> real(tess.minimumDisplacementError());
                case "ViewDistance" -> real(tess.viewDistance());
                case "LODDistanceFactor" -> real(tess.lodDistanceFactor());
                default -> NiagaraGpuRibbonUniforms.commonStageUniform(field);
            };
        };
        if (indirectDispatch) stage.dispatchIndirect(encoder, bindings, values, indirect.slice(0, 3L * Integer.BYTES));
        else stage.dispatch(encoder, bindings, values, 1, 1, 1);
    }

    private void ensureCapacity(long count) {
        if (count <= capacity) return;
        long bytes = Math.multiplyExact(count, Integer.BYTES);
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara ribbon index buffer exceeds device limit");
        GpuBuffer newIndices = null, newIndirect = null;
        try {
            String indexLabel = name + ".RibbonIndices", indirectLabel = name + ".RibbonIndirect";
            newIndices = RenderSystem.getDevice().createBuffer(() -> indexLabel,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST), bytes);
            newIndirect = RenderSystem.getDevice().createBuffer(() -> indirectLabel,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST
                            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), 17L * Integer.BYTES);
        } catch (RuntimeException | Error error) {
            if (newIndices != null) newIndices.close();
            if (newIndirect != null) newIndirect.close();
            throw error;
        }
        if (indices != null) indices.close();
        if (indirect != null) indirect.close();
        indices = newIndices; indirect = newIndirect; capacity = count;
    }

    private static byte[] real(float value) { return word(Float.floatToRawIntBits(value)); }
    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if (indices != null) indices.close();
        if (indirect != null) indirect.close();
    }
}
