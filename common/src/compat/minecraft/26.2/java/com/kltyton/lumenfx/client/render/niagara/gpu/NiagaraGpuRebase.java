package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Applies the UE Niagara LWC tile shift to the current GPU particle dataset. */
public final class NiagaraGpuRebase {
    private static final int MAX_POSITIONS = 8, WRAPPED_GROUP_STRIDE = 128;
    private NiagaraGpuRebase() { }

    public static void apply(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraGpuParticleBuffers particles,
                             List<NiagaraDefinition.Attribute> attributes, double x, double y, double z) {
        int instances = particles.estimate().current();
        if (instances == 0 || x == 0 && y == 0 && z == 0) return;
        List<Integer> positions = new ArrayList<>();
        for (var attribute : attributes) {
            if (!NiagaraDefinition.POSITION_TYPE.equals(attribute.sourceType())) continue;
            if (attribute.type() != 0 || attribute.count() != 3)
                throw new IllegalArgumentException("Invalid Niagara GPU position layout " + attribute.name());
            positions.add(attribute.component());
        }
        if (positions.isEmpty()) return;
        particles.detachPublishedWrite(encoder, programs.runtime());
        NiagaraGpuDataBuffer data = particles.current();
        if (data.floats() == null) throw new IllegalStateException("Niagara GPU position buffer is unavailable");
        NiagaraGpuComputeStage shader = programs.persistentIds("rebase");
        int groups = Math.ceilDiv(instances, shader.shader().workgroupX());
        int gx = groups, gy = 1, gz = 1;
        var runtime = programs.runtime();
        if (gx > runtime.maxWorkGroups(0) || (long)gx * shader.shader().workgroupX() > Integer.MAX_VALUE) {
            gx = WRAPPED_GROUP_STRIDE;
            gy = Math.ceilDiv(groups, WRAPPED_GROUP_STRIDE);
            if (gy > runtime.maxWorkGroups(1)) {
                gz = Math.ceilDiv(gy, WRAPPED_GROUP_STRIDE);
                gy = WRAPPED_GROUP_STRIDE;
            }
        }
        if (gx > runtime.maxWorkGroups(0) || gy > runtime.maxWorkGroups(1) || gz > runtime.maxWorkGroups(2))
            throw new IllegalArgumentException("Niagara GPU LWC dispatch exceeds device limits");
        int width = Math.multiplyExact(gx, shader.shader().workgroupX());
        for (int first = 0; first < positions.size(); first += MAX_POSITIONS) {
            int count = Math.min(MAX_POSITIONS, positions.size() - first);
            int[] offsets = new int[MAX_POSITIONS];
            for (int i = 0; i < count; i++) offsets[i] = positions.get(first + i);
            ComputeBindings.Builder resources = ComputeBindings.builder();
            for (var binding : shader.shader().bindings()) {
                if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
                switch (storage.name()) {
                    case "FloatBuffer" -> resources.storage(storage.name(), data.floats().slice(), storage.access());
                    case "CountBuffer" -> resources.storage(storage.name(), particles.countBuffer(), storage.access());
                    default -> throw new IllegalArgumentException("Unknown Niagara LWC buffer " + storage.name());
                }
            }
            shader.dispatch(encoder, resources, qualified -> {
                String name = qualified.substring(qualified.lastIndexOf('.') + 1);
                return switch (name) {
                    case "FloatBufferStride" -> words(data.stride());
                    case "CountBufferOffset" -> words(particles.currentCountOffset());
                    case "NumPositions" -> words(count);
                    case "PositionComponentOffsets" -> words(offsets);
                    case "TileShift" -> floats((float)x, (float)y, (float)z);
                    case "NumInstances" -> words(instances);
                    default -> null;
                };
            }, width, gy, gz);
        }
    }

    private static byte[] words(int... values) {
        ByteBuffer output = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) output.putInt(value);
        return output.array();
    }

    private static byte[] floats(float... values) {
        ByteBuffer output = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) output.putFloat(value);
        return output.array();
    }
}
