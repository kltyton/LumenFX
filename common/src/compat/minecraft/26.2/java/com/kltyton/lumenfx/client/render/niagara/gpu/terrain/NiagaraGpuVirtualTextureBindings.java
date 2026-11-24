package com.kltyton.lumenfx.client.render.niagara.gpu.terrain;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Uint4;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraVirtualTextureState.Frame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Supplies actual packed page tables, physical layers and view-relative uniforms to the source VT shaders. */
public final class NiagaraGpuVirtualTextureBindings {
    private record Source(String compileName, String identity, boolean runtime) { }
    private record Image(@Nullable GpuTextureView view, @Nullable GpuSampler sampler) { }
    private record Binding(Map<String, byte[]> uniforms, Map<String, Image> images) { }
    private static final Uint4 ZERO = new Uint4(0, 0, 0, 0);
    private final Map<String, Source> sources = new HashMap<>();
    private final Map<String, Binding> active = new HashMap<>();
    private final NiagaraGpuTerrainImages images;

    public NiagaraGpuVirtualTextureBindings(JsonObject program, NiagaraGpuTerrainImages images) {
        this.images = images;
        for (var element : program.getAsJsonArray("interfaces")) {
            var data = element.getAsJsonObject();
            if (!data.has("virtual_texture") || !data.has("shader_symbol")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var source = new Source(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString(),
                    data.getAsJsonObject("virtual_texture").get("runtime").getAsBoolean());
            if (sources.putIfAbsent(symbol, source) != null)
                throw new IllegalArgumentException("Duplicate Niagara virtual texture symbol " + symbol);
        }
    }

    public void beginTick(Map<String, String> aliases, Map<String, Frame> frames) {
        active.clear();
        for (var entry : sources.entrySet()) {
            var source = entry.getValue();
            var frame = frames.get(aliases.getOrDefault(source.compileName(), source.identity()));
            var runtime = frame == null ? null : frame.runtime();
            var streaming = frame == null ? null : frame.streaming();
            active.put(entry.getKey(), source.runtime()
                    ? runtime(runtime == null ? null : runtime.snapshot(), frame == null ? Vec3.ZERO : frame.tileOrigin())
                    : streaming(streaming == null ? null : streaming.snapshot()));
        }
    }

    public void clear() { active.clear(); }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int separator = name.lastIndexOf('_');
        if (separator < 0) return null;
        var binding = active.get(name.substring(0, separator));
        return binding == null ? null : binding.uniforms().get(name.substring(separator + 1));
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder target, Supplier<GpuTextureView> black) {
        for (var descriptor : shader.bindings()) {
            if (!(descriptor instanceof ShaderBinding.SampledTexture sampled) || sampled.array() || sampled.volume() || sampled.cube()) continue;
            for (var entry : active.entrySet()) {
                String prefix = entry.getKey() + "_";
                if (!sampled.name().startsWith(prefix)) continue;
                String field = sampled.name().substring(prefix.length());
                int suffix = field.indexOf('_');
                var image = entry.getValue().images().get(suffix < 0 ? field : field.substring(0, suffix));
                if (image == null) continue;
                images.bind(sampled, target, image.view(), image.sampler(), black);
                break;
            }
        }
    }

    private static Binding runtime(NiagaraRuntimeVirtualTexture.@Nullable Snapshot source, Vec3 tile) {
        var uniforms = new HashMap<String, byte[]>();
        var images = new HashMap<String, Image>();
        int mask = 0;
        for (int index = 0; index < 3; index++) {
            String prefix = "VirtualTexture" + index;
            var layer = source == null ? null : source.layers().get(index);
            uniforms.put(prefix + "TextureUniforms", words(layer == null ? ZERO : layer.uniforms()));
            images.put(prefix, new Image(layer == null ? null : layer.physical(), source == null ? null : source.sampler()));
            images.put(prefix + "PageTable", new Image(layer == null ? null : layer.pageTable(), null));
            if (layer != null) mask |= 1 << index;
        }
        uniforms.put("ValidLayersMask", ints(mask));
        uniforms.put("MaterialType", ints(source == null ? Material.values().length : source.material().ordinal()));
        uniforms.put("PageTableUniforms", words(source == null ? ZERO : source.table0(), source == null ? ZERO : source.table1()));
        var vectors = bytes(12 * Float.BYTES);
        List<Vec3> rows = source == null ? List.of(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
                : List.of(source.origin().subtract(tile), source.u(), source.v());
        for (Vec3 row : rows) vectors.putFloat((float)row.x).putFloat((float)row.y).putFloat((float)row.z).putFloat(0);
        uniforms.put("UVUniforms", vectors.array());
        uniforms.put("WorldHeightUnpack", bytes(2 * Float.BYTES)
                .putFloat(source == null ? 0 : source.heightScale()).putFloat(source == null ? 0 : source.heightBias()).array());
        return new Binding(uniforms, images);
    }

    private static Binding streaming(NiagaraStreamingVirtualTexture.@Nullable Snapshot source) {
        var uniforms = Map.of("TextureSize", ints(source == null ? 0 : source.width(), source == null ? 0 : source.height()),
                "MipLevels", ints(source == null ? 0 : source.mips()),
                "VTPackedPageTableUniform", words(source == null ? ZERO : source.table0(), source == null ? ZERO : source.table1()),
                "VTPackedUniform", words(source == null ? ZERO : source.layer().uniforms()));
        var images = Map.of("PhysicalTexture", new Image(source == null ? null : source.layer().physical(), source == null ? null : source.sampler()),
                "PageTableTexture0", new Image(source == null ? null : source.layer().pageTable(), null),
                "PageTableTexture1", new Image(source == null ? null : source.secondTable(), null));
        return new Binding(uniforms, images);
    }

    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] words(Uint4... values) {
        var data = bytes(values.length * 4 * Integer.BYTES);
        for (var value : values) data.putInt(value.x()).putInt(value.y()).putInt(value.z()).putInt(value.w());
        return data.array();
    }
    private static byte[] ints(int... values) {
        var data = bytes(values.length * Integer.BYTES);
        for (int value : values) data.putInt(value);
        return data.array();
    }
}
