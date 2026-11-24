package com.kltyton.lumenfx.client.render.niagara.gpu.texture;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.volume.SparseVolumeImages;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraSparseVolumeTexture.Snapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Frame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Publishes the captured Niagara frame to the shader aliases selected by the active parameter store. */
public final class NiagaraGpuSparseVolumeBindings implements AutoCloseable {
    private record Binding(String compileName, String original) { }
    private final NiagaraGpuPrograms programs;
    private final Map<String, Binding> bindings;
    private final Map<Frame, SparseVolumeImages> images = new IdentityHashMap<>();
    private final Map<String, Snapshot> selected = new HashMap<>();

    public NiagaraGpuSparseVolumeBindings(NiagaraGpuPrograms programs, JsonObject source) {
        this.programs = programs;
        var bindings = new HashMap<String, Binding>();
        for (var entry : source.getAsJsonArray("interfaces")) {
            var data = entry.getAsJsonObject();
            if (!data.has("shader_symbol") || !data.get("class").getAsString().equals("NiagaraDataInterfaceSparseVolumeTexture")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var binding = new Binding(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString());
            if (bindings.putIfAbsent(symbol, binding) != null)
                throw new IllegalArgumentException("Duplicate Niagara sparse volume symbol " + symbol);
        }
        this.bindings = Map.copyOf(bindings);
    }

    public void beginTick(Map<String, String> aliases, Map<String, Snapshot> snapshots) {
        if (bindings.isEmpty()) return;
        var activeFrames = Collections.newSetFromMap(new IdentityHashMap<Frame, Boolean>());
        for (var entry : bindings.entrySet()) {
            var binding = entry.getValue();
            String id = aliases.getOrDefault(binding.compileName(), binding.original());
            var snapshot = Objects.requireNonNull(snapshots.get(id), "GPU sparse volume state was not published: " + id);
            selected.put(entry.getKey(), snapshot);
            if (snapshot.frame() != null) activeFrames.add(snapshot.frame());
        }
        var iterator = images.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!activeFrames.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    public @Nullable byte[] value(String name) {
        int member = name.lastIndexOf('.') + 1, separator = name.lastIndexOf('_');
        if (separator <= member) return null;
        var snapshot = selected.get(name.substring(member, separator));
        if (snapshot == null) return null;
        var asset = snapshot.asset();
        var frame = snapshot.frame();
        return switch (name.substring(separator + 1)) {
            case "TextureSize" -> asset == null ? new byte[12]
                    : words(asset.resolution().x(), asset.resolution().y(), asset.resolution().z());
            case "MipLevels" -> words(frame == null ? 0 : frame.mipCount());
            case "NumFrames" -> words(snapshot.frameCount());
            case "PackedUniforms0" -> asset == null || frame == null ? new byte[16] : words(
                    bits(asset.resolution().x() / 16f), bits(asset.resolution().y() / 16f), bits(asset.resolution().z() / 16f),
                    SparseVolumeImages.packedPageOffset(frame));
            case "PackedUniforms1" -> frame == null ? new byte[16] : words(
                    bits(1f / frame.atlasExtent().x()), bits(1f / frame.atlasExtent().y()), bits(1f / frame.atlasExtent().z()),
                    SparseVolumeImages.packedFrameMip(frame));
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (selected.isEmpty()) return;
        for (var descriptor : shader.bindings()) {
            if (!(descriptor instanceof ShaderBinding.SampledTexture sampled) || !sampled.volume()) continue;
            String name = sampled.name();
            for (var entry : selected.entrySet()) {
                String symbol = entry.getKey();
                int input;
                if (matches(name, symbol + "_PageTableTexture")) input = 0;
                else if (matches(name, symbol + "_PhysicalTileDataATexture")) input = 1;
                else if (matches(name, symbol + "_PhysicalTileDataBTexture")) input = 2;
                else continue;
                var frame = entry.getValue().frame();
                var gpu = frame == null ? null : images.computeIfAbsent(frame,
                        data -> SparseVolumeImages.upload("Niagara.SparseVolume." + symbol, data));
                ComputeVolume texture = gpu == null ? null : switch (input) {
                    case 0 -> gpu.pageTable();
                    case 1 -> gpu.a();
                    default -> gpu.b();
                };
                if (texture == null) texture = input == 0 ? programs.blackIntegerVolumeTexture() : programs.blackVolumeTexture();
                resources.volume(name, texture, 0);
                break;
            }
        }
    }

    private static boolean matches(String name, String base) { return name.equals(base) || name.startsWith(base + "_"); }
    private static int bits(float value) { return Float.floatToRawIntBits(value); }
    private static byte[] words(int... values) {
        var output = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) output.putInt(value);
        return output.array();
    }

    public void clearSnapshots() { selected.clear(); }

    @Override public void close() {
        clearSnapshots();
        images.values().forEach(SparseVolumeImages::close);
        images.clear();
    }
}
