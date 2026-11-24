package com.kltyton.lumenfx.client.render.light;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** GPU uploads shared by surface and volume lighting, with explicit coordinate bases. */
public final class ParticleLightBuffers implements AutoCloseable {
    public record Batch(GpuBuffer buffer, int lights) { }
    private static final int STRIDE = 48;
    private static final class Slot { GpuBuffer buffer; long capacity; }
    private final List<List<Slot>> rings = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    private long maximumBytes;
    private int ring;

    public List<Batch> uploadView(CommandEncoder encoder, List<ParticleLight> lights, Vec3 camera, Matrix4fc worldToView) {
        return upload(encoder, lights, camera, worldToView, Integer.MAX_VALUE);
    }

    public List<Batch> uploadWorld(CommandEncoder encoder, List<ParticleLight> lights, Vec3 camera) {
        return uploadWorld(encoder, lights, camera, Integer.MAX_VALUE);
    }

    public List<Batch> uploadWorld(CommandEncoder encoder, List<ParticleLight> lights, Vec3 camera, int maximumBatchLights) {
        if (maximumBatchLights < 1) throw new IllegalArgumentException("Particle light batch must contain at least one record");
        return upload(encoder, lights, camera, null, maximumBatchLights);
    }

    private List<Batch> upload(CommandEncoder encoder, List<ParticleLight> lights, Vec3 camera, @Nullable Matrix4fc worldToView,
                               int maximumBatchLights) {
        RenderSystem.assertOnRenderThread();
        if (lights.isEmpty()) return List.of();
        var device = RenderSystem.getDevice();
        if (maximumBytes == 0) try (var runtime = ComputeRuntime.create(device)) {
            maximumBytes = Math.min(runtime.maxTexelBufferElements() * 16L,
                    device.getDeviceInfo().limits().maxMemoryAllocationSize());
        }
        int maximumLights = (int)Math.min(maximumBatchLights, maximumBytes / STRIDE);
        if (maximumLights == 0) throw new IllegalStateException("Device cannot hold a particle light record");
        ring = (ring + 1) % rings.size();
        var slots = rings.get(ring);
        var output = new ArrayList<Batch>();
        var position = new Vector3f();
        for (int first = 0, batch = 0; first < lights.size(); batch++) {
            int count = Math.min(maximumLights, lights.size() - first);
            long bytes = count * (long)STRIDE;
            if (batch == slots.size()) slots.add(new Slot());
            var slot = slots.get(batch);
            if (slot.buffer == null || slot.capacity < bytes) {
                var next = device.createBuffer(() -> "LumenFX particle lights",
                        GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
                if (slot.buffer != null) slot.buffer.close();
                slot.buffer = next; slot.capacity = bytes;
            }
            GpuBufferSlice uploadSlice;
            try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
                var data = upload.data().order(ByteOrder.nativeOrder());
                for (int index = first; index < first + count; index++) {
                    var light = lights.get(index); var world = light.position();
                    position.set((float)(world.x - camera.x), (float)(world.y - camera.y), (float)(world.z - camera.z));
                    if (worldToView != null) worldToView.transformPosition(position);
                    data.putFloat(position.x).putFloat(position.y).putFloat(position.z).putFloat(light.radius());
                    data.putFloat((float)light.color().x).putFloat((float)light.color().y).putFloat((float)light.color().z).putFloat(light.exponent());
                    data.putFloat(light.diffuseScale()).putFloat(light.specularScale()).putFloat(light.volumetricScattering()).putFloat(light.inverseExposureBlend());
                }
                uploadSlice = upload.slice();
            }
            encoder.copyToBuffer(uploadSlice, slot.buffer.slice(0, bytes));
            output.add(new Batch(slot.buffer, count)); first += count;
        }
        return List.copyOf(output);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        for (var slots : rings) {
            for (var slot : slots) if (slot.buffer != null) slot.buffer.close();
            slots.clear();
        }
        maximumBytes = 0;
    }
}
