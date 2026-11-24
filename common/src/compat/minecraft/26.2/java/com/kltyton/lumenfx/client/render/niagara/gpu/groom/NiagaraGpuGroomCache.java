package com.kltyton.lumenfx.client.render.niagara.gpu.groom;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleBuffers;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuHairBuffers.Resource;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.HairStrandsFrame;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuGroomCache {
    private static final Map<String, Resource> RESOURCES = resources();

    private NiagaraGpuGroomCache() { }

    public static void apply(CommandEncoder encoder, NiagaraGpuPrograms programs, NiagaraGpuHairBuffers buffers,
                             String identity, HairStrandsFrame frame, NiagaraGpuParticleBuffers particles,
                             int nodeComponent, int restComponent, int rows) {
        var group = frame.group();
        var guides = group == null ? null : group.guides();
        if (group == null || guides == null || rows == 0) return;
        int size = group.physics().strandSize();
        if (size == 0 || (long) guides.curves() * size > rows)
            throw new IllegalArgumentException("Niagara groom cache contains fewer nodes than its guide layout: " + identity);
        var entry = buffers.prepare(identity, frame);
        entry.beginCache(frame, group);
        var shader = programs.groomCacheInterpolation(group.roots() != null);
        var data = particles.current();
        var bindings = ComputeBindings.builder();
        for (var binding : shader.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            var slice = storage.name().equals("NiagaraFloatBuffer")
                    ? Objects.requireNonNull(data.floats(), "Groom cache has no float dataset").slice()
                    : entry.buffer(encoder, Objects.requireNonNull(RESOURCES.get(storage.name())), frame);
            bindings.storage(storage.name(), slice, storage.access());
        }
        shader.dispatch(encoder, bindings, name -> uniform(name, frame, size, guides.points(), data.stride(),
                nodeComponent, restComponent), Math.ceilDiv(guides.points(), shader.shader().workgroupX()), 1, 1);
        entry.synchronizeCacheHistory(encoder, frame);
        entry.publish(encoder, frame);
    }

    private static @Nullable byte[] uniform(String name, HairStrandsFrame frame, int size, int points, int stride,
                                             int node, int rest) {
        return switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "StrandsSize" -> integer(size);
            case "NumPoints" -> integer(points);
            case "NiagaraFloatStride" -> integer(stride);
            case "NodePositionComponent" -> integer(node);
            case "RestPositionComponent" -> integer(rest);
            case "RestPositionOffset" -> frame.value("RestPositionOffset");
            case "WorldToLocal" -> frame.value("WorldInverse");
            default -> null;
        };
    }

    private static Map<String, Resource> resources() {
        var names = new HashMap<String, Resource>();
        for (Resource resource : Resource.values()) names.put(resource.symbol, resource);
        return Map.copyOf(names);
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
