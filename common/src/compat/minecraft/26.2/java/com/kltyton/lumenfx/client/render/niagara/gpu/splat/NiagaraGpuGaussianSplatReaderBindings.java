package com.kltyton.lumenfx.client.render.niagara.gpu.splat;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatReader;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class NiagaraGpuGaussianSplatReaderBindings implements AutoCloseable {
    private static final String DATA_SUFFIX = "_SplatData";
    private record Binding(String compileName, String fallback) { }
    private final ComputeRuntime runtime;
    private final Map<String, Binding> symbols;
    private final Map<String, NiagaraGaussianSplatReader> active = new HashMap<>();
    private final Map<NiagaraGaussianSplatAsset, GpuBuffer> uploads = new IdentityHashMap<>();

    public NiagaraGpuGaussianSplatReaderBindings(ComputeRuntime runtime, JsonObject program) {
        this.runtime = runtime;
        var bindings = new HashMap<String, Binding>();
        var items = program.getAsJsonArray("interfaces");
        if (items != null) for (var item : items) {
            var value = item.getAsJsonObject();
            String type = value.has("class") ? value.get("class").getAsString() : "";
            if (!(value.has("gaussian_splat_reader") || NiagaraGaussianSplatReader.TYPE.equals(type))
                    || !value.has("shader_symbol")) continue;
            bindings.put(value.get("shader_symbol").getAsString(),
                    new Binding(SourceNames.canonical(value.get("compile_name").getAsString()),
                            value.get("interface_id").getAsString()));
        }
        symbols = Map.copyOf(bindings);
    }

    public void beginTick(Map<String, String> selections, Map<String, NiagaraGaussianSplatReader> readers) {
        symbols.forEach((symbol, binding) -> {
            String identity = selections.getOrDefault(binding.compileName(), binding.fallback());
            active.put(symbol, Objects.requireNonNull(readers.get(identity), "Missing Niagara Gaussian Splat Reader step: " + identity));
        });
    }

    public @Nullable byte[] value(String qualified) {
        if (active.isEmpty()) return null;
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int split = name.lastIndexOf('_');
        if (split < 0) return null;
        var reader = active.get(name.substring(0, split));
        if (reader == null) return null;
        var asset = reader.asset();
        return switch (name.substring(split + 1)) {
            case "NumSplats" -> integer(asset.count());
            case "IndexClamp" -> integer(Math.max(0, asset.count() - 1));
            case "SHDegree" -> integer(reader.degree());
            case "SHCoordinateSpace" -> integer(asset.coordinateSpace());
            case "SplatFormat" -> integer(0);
            case "SplatDataStride" -> integer(asset.stride());
            case "BoundsMin" -> vector(asset.boundsMin());
            case "BoundsMax" -> vector(asset.boundsMax());
            case "PositionScale", "PositionBias", "ScaleScale", "ScaleBias", "SHScale", "SHBias" -> new byte[12];
            case "ColorScale", "ColorBias" -> new byte[16];
            default -> null;
        };
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (active.isEmpty()) return;
        for (var descriptor : shader.bindings()) if (descriptor instanceof ShaderBinding.StorageBuffer buffer) {
            String name = buffer.name();
            if (!name.endsWith(DATA_SUFFIX)) continue;
            var reader = active.get(name.substring(0, name.length() - DATA_SUFFIX.length()));
            if (reader == null) continue;
            var upload = uploads.computeIfAbsent(reader.asset(), this::upload);
            resources.storage(name, upload.slice(), buffer.access());
        }
    }

    private GpuBuffer upload(NiagaraGaussianSplatAsset asset) {
        var rows = asset.rows();
        int bytes = Math.max(asset.stride(), rows.remaining());
        if (bytes > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara Gaussian Splat exceeds the device storage buffer limit: " + bytes);
        var upload = asset.count() == 0 ? MemoryUtil.memCalloc(bytes) : MemoryUtil.memAlloc(bytes);
        try {
            upload.put(rows);
            upload.position(0);
            return RenderSystem.getDevice().createBuffer(() -> "Niagara.GaussianSplat.Reader",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), upload);
        } finally {
            MemoryUtil.memFree(upload);
        }
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] vector(Vec3 value) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).array();
    }

    public void clearSnapshots() { active.clear(); }

    @Override public void close() {
        active.clear();
        uploads.values().forEach(buffer -> RenderSystem.queueFencedTask(buffer::close));
        uploads.clear();
    }
}
