package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Generates Niagara sort keys from the live GPU dataset and optional renderer culling inputs. */
public final class NiagaraGpuSortKeyGenerator {
    private static final int THREADS = 64;
    private static final int WRAPPED_STRIDE = 128;
    private static final int MAX_CULL_PLANES = 12;
    // The sort-only shader still reflects the shared FNiagaraGPUSortInfo uniform block.
    private static final Culling NO_CULLING = new Culling(-1, -1, -1, 0, -1, 0, -1,
            0f, Float.POSITIVE_INFINITY, new float[]{0, 2, 1, 0}, new float[4], new float[3], new float[0]);
    private final NiagaraGpuPrograms programs;

    public NiagaraGpuSortKeyGenerator(NiagaraGpuPrograms programs) {
        this.programs = Objects.requireNonNull(programs);
    }

    public record KeyLayout(int elementShift, int sortMask, int sortShift, int signBit) {
        public static KeyLayout forBatch(int elements, boolean highPrecision) {
            if (elements < 1 || elements > 65536) throw new IllegalArgumentException("Invalid Niagara sort batch size");
            int bits = elements == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(elements - 1);
            int elementMask = (1 << bits) - 1;
            if (!highPrecision) return new KeyLayout(16, 0xffff, 0, 0x8000);
            elementMask = Math.max(elementMask, 1);
            int unused = Integer.numberOfLeadingZeros(elementMask << 16);
            int shift = 16 + unused;
            return new KeyLayout(shift, ~(elementMask << shift), 16 - unused, 0x8000 << unused);
        }
    }

    /** Vector arrays are tightly packed in source order; planes contain four floats each. */
    public record Culling(int positionOffset, int orientationOffset, int scaleOffset,
                          int visibility, int visibilityOffset, int meshIndex, int meshIndexOffset,
                          float minDistanceSquared, float maxDistanceSquared, float[] lodScreenSize,
                          float[] localBoundingSphere, float[] worldOffset, float[] planes) {
        public Culling {
            lodScreenSize = vector(lodScreenSize, 4, "LOD screen size");
            localBoundingSphere = vector(localBoundingSphere, 4, "bounding sphere");
            worldOffset = vector(worldOffset, 3, "culling world offset");
            Objects.requireNonNull(planes);
            if (planes.length % 4 != 0 || planes.length > MAX_CULL_PLANES * 4)
                throw new IllegalArgumentException("Invalid Niagara culling planes");
            planes = planes.clone();
        }
    }

    public record Input(NiagaraGpuParticleView particles, int particleCount, int sortMode,
                        int sortAttributeOffset, float[] cameraPosition, float[] cameraDirection,
                        int batchElements, int elementIndex, int outputOffset, boolean highPrecision,
                        @Nullable Culling culling, int culledCountOffset) {
        public Input {
            Objects.requireNonNull(particles);
            if (particleCount < 0 || sortMode < 0 || sortMode > 4 || outputOffset < 0
                    || batchElements < 1 || elementIndex < 0 || elementIndex >= batchElements
                    || culling != null && culledCountOffset < 0)
                throw new IllegalArgumentException("Invalid Niagara GPU sort input");
            cameraPosition = vector(cameraPosition, 3, "camera position");
            cameraDirection = vector(cameraDirection, 3, "camera direction");
        }
    }

    /** Outputs and the cull counter belong to the caller and must remain alive through GPU execution. */
    public void generate(CommandEncoder encoder, Input input, GpuBufferSlice outputKeys,
                         GpuBufferSlice outputIndices, @Nullable GpuBufferSlice culledCounts) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(encoder);
        Objects.requireNonNull(outputKeys);
        Objects.requireNonNull(outputIndices);
        if (input.particleCount() == 0) return;
        Culling culling = input.culling();
        if (culling != null && culledCounts == null) throw new IllegalArgumentException("Missing Niagara cull count buffer");
        KeyLayout layout = KeyLayout.forBatch(input.batchElements(), input.highPrecision());
        String variant = (culling == null ? "" : "cull_") + (input.highPrecision() ? "precise" : "basic");
        NiagaraGpuComputeStage stage = programs.sortKey(variant);
        NiagaraGpuDataBuffer data = input.particles().current();
        GpuBufferSlice dummy = programs.dummyBuffer().slice();
        ComputeBindings.Builder resources = ComputeBindings.builder();
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice buffer = switch (storage.name()) {
                case "NiagaraParticleDataFloat" -> slice(data.floats(), dummy);
                case "NiagaraParticleDataHalf" -> slice(data.halves(), dummy);
                case "NiagaraParticleDataInt" -> slice(data.integers(), dummy);
                case "GPUParticleCountBuffer" -> input.particles().countBuffer();
                case "OutKeys" -> outputKeys;
                case "OutParticleIndices" -> outputIndices;
                case "OutCulledParticleCounts" -> culledCounts == null ? dummy : culledCounts;
                default -> throw new IllegalArgumentException("Unknown Niagara sort resource " + storage.name());
            };
            resources.storage(storage.name(), buffer, storage.access());
        }
        int groupsX = Math.ceilDiv(input.particleCount(), THREADS), groupsY = 1, groupsZ = 1;
        var runtime = programs.runtime();
        if (runtime.maxWorkGroups(0) < WRAPPED_STRIDE || runtime.maxWorkGroups(1) < WRAPPED_STRIDE)
            throw new IllegalStateException("Device cannot satisfy Niagara wrapped-dispatch stride");
        if (groupsX > runtime.maxWorkGroups(0)) {
            groupsY = Math.ceilDiv(groupsX, WRAPPED_STRIDE);
            groupsX = WRAPPED_STRIDE;
        }
        if (groupsY > runtime.maxWorkGroups(1)) {
            groupsZ = Math.ceilDiv(groupsY, WRAPPED_STRIDE);
            groupsY = WRAPPED_STRIDE;
        }
        if (groupsZ > runtime.maxWorkGroups(2)) throw new IllegalArgumentException("Niagara sort dispatch exceeds device limit");
        stage.dispatch(encoder, resources, name -> value(name, input, data.stride(), layout),
                Math.multiplyExact(groupsX, THREADS), groupsY, groupsZ);
    }

    private static @Nullable byte[] value(String qualifiedName, Input input, int stride, KeyLayout key) {
        String name = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        Culling c = input.culling() == null ? NO_CULLING : input.culling();
        return switch (name) {
            case "FloatDataStride", "HalfDataStride", "IntDataStride" -> word(stride);
            case "ParticleCount" -> word(input.particleCount());
            case "GPUParticleCountOffset" -> word(input.particles().currentCountOffset());
            case "CulledGPUParticleCountOffset" -> word(c == NO_CULLING ? -1 : input.culledCountOffset());
            case "EmitterKey" -> word(input.elementIndex() << key.elementShift());
            case "OutputOffset" -> word(input.outputOffset());
            case "CameraPosition" -> floats(input.cameraPosition());
            case "CameraDirection" -> floats(input.cameraDirection());
            case "SortMode" -> word(input.sortMode());
            case "SortAttributeOffset" -> word(input.sortAttributeOffset());
            case "SortKeyMask" -> word(key.sortMask());
            case "SortKeyShift" -> word(key.sortShift());
            case "SortKeySignBit" -> word(key.signBit());
            case "CullPositionAttributeOffset" -> word(c.positionOffset());
            case "CullOrientationAttributeOffset" -> word(c.orientationOffset());
            case "CullScaleAttributeOffset" -> word(c.scaleOffset());
            case "NumCullPlanes" -> word(c.planes().length / 4);
            case "CullPlanes" -> planes(c.planes());
            case "RendererVisibility" -> word(c.visibility());
            case "RendererVisTagAttributeOffset" -> word(c.visibilityOffset());
            case "MeshIndex" -> word(c.meshIndex());
            case "MeshIndexAttributeOffset" -> word(c.meshIndexOffset());
            case "CullDistanceRangeSquared" -> floats(new float[]{c.minDistanceSquared(), c.maxDistanceSquared()});
            case "LODScreenSize" -> floats(c.lodScreenSize());
            case "LocalBoundingSphere" -> floats(c.localBoundingSphere());
            case "CullingWorldSpaceOffset" -> floats(c.worldOffset());
            default -> null;
        };
    }

    private static GpuBufferSlice slice(@Nullable GpuBuffer buffer, GpuBufferSlice dummy) {
        return buffer == null ? dummy : buffer.slice();
    }

    private static float[] vector(float[] value, int size, String label) {
        if (value == null || value.length != size) throw new IllegalArgumentException("Invalid Niagara " + label);
        return value.clone();
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] floats(float[] values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) result.putFloat(value);
        return result.array();
    }

    private static byte[] planes(float[] values) {
        float[] all = new float[MAX_CULL_PLANES * 4];
        System.arraycopy(values, 0, all, 0, values.length);
        return floats(all);
    }
}
