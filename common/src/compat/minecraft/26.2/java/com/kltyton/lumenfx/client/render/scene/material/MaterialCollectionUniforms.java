package com.kltyton.lumenfx.client.render.scene.material;

import com.kltyton.lumenfx.client.material.collection.MaterialCollectionWorlds;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionInstance;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** MPC uniform buffers are shared across materials and updated only when their world values or layout change. */
public final class MaterialCollectionUniforms implements AutoCloseable {
    public static final MaterialCollectionUniforms INSTANCE = new MaterialCollectionUniforms();
    private record Entry(MaterialCollectionDefinition definition, @Nullable MaterialCollectionInstance instance, long revision, GpuBuffer buffer) { }
    private final Map<String, Entry> buffers = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
    private @Nullable ClientLevel world;
    private long epoch;
    private MaterialCollectionUniforms() { }

    public Frame frame(CommandEncoder encoder, @Nullable ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        if (world != level) { close(); world = level; }
        return new Frame(encoder, level);
    }
    public final class Frame {
        private final CommandEncoder encoder;
        private final @Nullable ClientLevel level;
        private final long sourceEpoch;
        private final Map<MaterialCollectionDefinition, GpuBufferSlice> slices = new IdentityHashMap<>();
        private Frame(CommandEncoder encoder, @Nullable ClientLevel level) { this.encoder = encoder; this.level = level; sourceEpoch = epoch; }

        public Map<String, GpuBufferSlice> prepare(MaterialDefinition material) {
            if (sourceEpoch != epoch) throw new IllegalStateException("Material collection frame belongs to retired GPU resources");
            if (material.materialCollections().isEmpty()) return Map.of();
            Map<String, GpuBufferSlice> result = new LinkedHashMap<>();
            for (var binding : material.materialCollections())
                result.put(binding.uniform(), slices.computeIfAbsent(binding.source(), this::write));
            return Map.copyOf(result);
        }
        private GpuBufferSlice write(MaterialCollectionDefinition source) {
            var instance = level == null ? null : MaterialCollectionWorlds.instance(level, source);
            var snapshot = instance == null ? null : instance.snapshot();
            long revision = snapshot == null ? -1 : snapshot.revision();
            Entry entry = buffers.get(source.path());
            int bytes = Math.multiplyExact(source.vectorCount(), 16);
            if (entry != null && entry.instance() == instance && entry.revision() == revision && entry.definition().equals(source))
                return new GpuBufferSlice(entry.buffer(), 0, entry.buffer().size());
            int[] words = snapshot == null ? source.pack(Map.of(), Map.of()) : snapshot.packed();
            GpuBuffer buffer = entry == null || entry.buffer().size() != bytes
                    ? RenderSystem.getDevice().createBuffer(() -> "Material collection " + source.path(),
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, bytes) : entry.buffer();
            try {
                GpuBufferSlice upload;
                try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
                    mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().put(words);
                    upload = mapped.slice();
                }
                encoder.copyToBuffer(upload, new GpuBufferSlice(buffer, 0, buffer.size()));
            } catch (RuntimeException | Error error) {
                if (entry == null || buffer != entry.buffer()) buffer.close();
                throw error;
            }
            buffers.put(source.path(), new Entry(source, instance, revision, buffer));
            if (entry != null && entry.buffer() != buffer) entry.buffer().close();
            return new GpuBufferSlice(buffer, 0, buffer.size());
        }
    }
    public static void bind(RenderPass pass, Map<String, GpuBufferSlice> values) { values.forEach(pass::setUniform); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        ++epoch;
        buffers.values().forEach(entry -> entry.buffer().close()); buffers.clear(); world = null;
    }
}
