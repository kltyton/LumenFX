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
import org.jspecify.annotations.Nullable;

/** Builds the ribbon segment, tangent, per-ribbon and tessellation data on the GPU. */
public final class NiagaraGpuRibbonAggregation implements AutoCloseable {
    private static final int THREADS = 64;
    private static final int ACCUMULATION_BYTES = 8 * Integer.BYTES;
    private final String name;
    private final NiagaraGpuPrograms programs;
    private final GpuBuffer[] accumulation = new GpuBuffer[2];
    private GpuBuffer tangents, ribbonIndices, segments, tessellation, ribbonData, commands;
    private int capacity;
    private boolean closed;

    public record Input(NiagaraGpuParticleView particles, GpuBuffer sortedIndices, int executableInstances,
                        NiagaraGpuRibbonSort.IdType idType, int ribbonIdOffset, int positionOffset,
                        int widthOffset, int twistOffset, int normalizedAgeOffset, int u0OverrideOffset,
                        int u1OverrideOffset, int tessellationMode, float curveTension, Uv uv0, Uv uv1) { }

    public record Uv(float offsetU, float offsetV, float scaleU, float scaleV, float tilingLength,
                     int distribution, int leadingEdge, int trailingEdge, boolean uOverride, boolean vOverride) { }

    public record Output(GpuBuffer tangents, GpuBuffer ribbonIndices, GpuBuffer segments,
                         GpuBuffer ribbonData, GpuBuffer commands) { }

    public NiagaraGpuRibbonAggregation(String name, NiagaraGpuPrograms programs) {
        this.name = name;
        this.programs = programs;
    }

    public Output generate(CommandEncoder encoder, Input input) {
        RenderSystem.assertOnRenderThread();
        if (closed || input.executableInstances() < 2 || input.executableInstances() >= input.particles().current().stride())
            throw new IllegalArgumentException("Invalid Niagara ribbon aggregation capacity");
        ensureCapacity(input.executableInstances());
        int groups = Math.ceilDiv(input.executableInstances(), THREADS);
        if (groups > programs.runtime().maxWorkGroups(0))
            throw new IllegalArgumentException("Niagara ribbon aggregation exceeds device dispatch limit");
        String variant = switch (input.idType()) {
            case NONE -> "single";
            case NIAGARA_ID -> "id";
            case INTEGER -> "integer";
        };
        int current = 0;
        dispatch(encoder, input, variant, "initialize", null, accumulation[current], 0, groups * THREADS);
        for (int stride = 1; stride < input.executableInstances();) {
            int next = current ^ 1;
            dispatch(encoder, input, variant, "step", accumulation[current], accumulation[next], stride, groups * THREADS);
            current = next;
            if (stride > Integer.MAX_VALUE / 2) break;
            stride *= 2;
        }
        dispatch(encoder, input, variant, "apply", accumulation[current], null, 0, groups * THREADS);
        dispatch(encoder, input, variant, "finalize", accumulation[current], null, 0,
                input.idType() == NiagaraGpuRibbonSort.IdType.NONE ? 1 : groups * THREADS);
        dispatch(encoder, input, variant, "uv", null, null, 0, 0);
        return new Output(tangents, ribbonIndices, segments, ribbonData, commands);
    }

    private void dispatch(CommandEncoder encoder, Input input, String variant, String pass,
                          @Nullable GpuBuffer source, @Nullable GpuBuffer destination, int scanStride, int threads) {
        NiagaraGpuComputeStage stage = programs.ribbonAggregation(pass, variant);
        NiagaraGpuDataBuffer particles = input.particles().current();
        GpuBufferSlice dummy = programs.dummyBuffer().slice();
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice buffer = switch (storage.name()) {
                case "NiagaraParticleDataFloat" -> slice(particles.floats(), dummy);
                case "NiagaraParticleDataHalf" -> slice(particles.halves(), dummy);
                case "NiagaraParticleDataInt" -> slice(particles.integers(), dummy);
                case "EmitterParticleCountsBuffer" -> input.particles().countBuffer();
                case "SortedIndices" -> input.sortedIndices().slice();
                case "InputAccumulation", "AccumulationBuffer" -> slice(source, dummy);
                case "OutputAccumulation", "OutputAccumulationBuffer" -> slice(destination, dummy);
                case "OutputTangentsAndDistances", "TangentsAndDistances" -> tangents.slice();
                case "OutputMultiRibbonIndices", "MultiRibbonIndices" -> ribbonIndices.slice();
                case "OutputSegments", "Segments" -> segments.slice();
                case "OutputTessellationStats", "TessellationStats" -> tessellation.slice();
                case "PackedPerRibbonData" -> ribbonData.slice();
                case "OutputCommandBuffer", "CommandBuffer" -> commands.slice();
                default -> throw new IllegalArgumentException("Unknown Niagara ribbon aggregation buffer " + storage.name());
            };
            bindings.storage(storage.name(), buffer, storage.access());
        }
        java.util.function.Function<String, byte[]> values = qualified -> {
            String field = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (field.startsWith("UV0Settings_")) return uvValue(input.uv0(), field.substring(12));
            if (field.startsWith("UV1Settings_")) return uvValue(input.uv1(), field.substring(12));
            return switch (field) {
                case "TotalNumParticlesDirect" -> word(input.executableInstances());
                case "EmitterParticleCountsBufferOffset" -> word(input.particles().currentCountOffset());
                case "NiagaraFloatDataStride", "NiagaraIntDataStride" -> word(particles.stride());
                case "RibbonIdDataOffset" -> word(input.ribbonIdOffset());
                case "PositionDataOffset" -> word(input.positionOffset());
                case "WidthDataOffset" -> word(input.widthOffset());
                case "TwistDataOffset" -> word(input.twistOffset());
                case "NormalizedAgeDataOffset" -> word(input.normalizedAgeOffset());
                case "U0OverrideDataOffset" -> word(input.u0OverrideOffset());
                case "U1OverrideDataOffset" -> word(input.u1OverrideOffset());
                case "U0DistributionMode" -> word(input.uv0().distribution());
                case "U1DistributionMode" -> word(input.uv1().distribution());
                case "RibbonTessellationMode" -> word(input.tessellationMode());
                case "CurveTension" -> word(Float.floatToRawIntBits(input.curveTension()));
                case "PrefixScanStride" -> word(scanStride);
                case "OutputCommandBufferIndex" -> word(0);
                case "CommandBufferOffset" -> word(0);
                case "FinalizationThreadBlockSize" -> word(THREADS);
                default -> NiagaraGpuRibbonUniforms.commonStageUniform(field);
            };
        };
        if (pass.equals("uv")) stage.dispatchIndirect(encoder, bindings, values, commands.slice(0, 3L * Integer.BYTES));
        else stage.dispatch(encoder, bindings, values, threads, 1, 1);
    }

    private void ensureCapacity(int required) {
        if (required <= capacity) return;
        long max = Math.min(programs.runtime().maxStorageBufferBytes(), programs.runtime().maxTexelBufferElements() * Integer.BYTES);
        if ((long) required * ACCUMULATION_BYTES > max)
            throw new IllegalArgumentException("Niagara ribbon aggregation buffer exceeds device limit");
        GpuBuffer[] next = new GpuBuffer[8];
        try {
            next[0] = allocate("Accumulation0", (long) required * ACCUMULATION_BYTES, false);
            next[1] = allocate("Accumulation1", (long) required * ACCUMULATION_BYTES, false);
            next[2] = allocate("Tangents", (long) required * 4 * Float.BYTES, false);
            next[3] = allocate("RibbonIndices", (long) required * Integer.BYTES, false);
            next[4] = allocate("Segments", (long) required * Integer.BYTES, false);
            next[5] = allocate("Tessellation", (long) required * 5 * Float.BYTES, false);
            next[6] = allocate("RibbonData", (long) required * 8 * Integer.BYTES, false);
            next[7] = allocate("Commands", 15L * Integer.BYTES, true);
            programs.runtime().clearBuffer(next[7].slice(), 0);
        } catch (RuntimeException | Error error) {
            for (GpuBuffer value : next) if (value != null) value.close();
            throw error;
        }
        release();
        accumulation[0] = next[0]; accumulation[1] = next[1]; tangents = next[2]; ribbonIndices = next[3];
        segments = next[4]; tessellation = next[5]; ribbonData = next[6]; commands = next[7];
        capacity = required;
    }

    private GpuBuffer allocate(String suffix, long bytes, boolean indirect) {
        String label = name + ".Ribbon" + suffix;
        int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;
        if (indirect) usage |= GpuBuffer.USAGE_INDIRECT_PARAMETERS;
        return RenderSystem.getDevice().createBuffer(() -> label, ComputeUsage.storageBuffer(usage), bytes);
    }

    private static GpuBufferSlice slice(@Nullable GpuBuffer buffer, GpuBufferSlice fallback) {
        return buffer == null ? fallback : buffer.slice();
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] uvValue(Uv uv, String field) {
        return switch (field) {
            case "Offset" -> pair(uv.offsetU(), uv.offsetV());
            case "Scale" -> pair(uv.scaleU(), uv.scaleV());
            case "TilingLength" -> word(Float.floatToRawIntBits(uv.tilingLength()));
            case "DistributionMode" -> word(uv.distribution());
            case "LeadingEdgeMode" -> word(uv.leadingEdge());
            case "TrailingEdgeMode" -> word(uv.trailingEdge());
            case "bEnablePerParticleUOverride" -> word(uv.uOverride() ? 1 : 0);
            case "bEnablePerParticleVRangeOverride" -> word(uv.vOverride() ? 1 : 0);
            default -> null;
        };
    }

    private static byte[] pair(float first, float second) {
        return ByteBuffer.allocate(2 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(first).putFloat(second).array();
    }

    private void release() {
        for (GpuBuffer buffer : accumulation) if (buffer != null) buffer.close();
        for (GpuBuffer buffer : new GpuBuffer[]{tangents, ribbonIndices, segments, tessellation, ribbonData, commands})
            if (buffer != null) buffer.close();
    }

    public void reset() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara ribbon aggregation is closed");
        if (commands != null) programs.runtime().clearBuffer(commands.slice(), 0);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        release();
    }
}
