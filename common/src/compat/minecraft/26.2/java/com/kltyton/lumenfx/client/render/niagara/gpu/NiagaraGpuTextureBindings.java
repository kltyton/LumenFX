package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter.Dimension;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.texture.NiagaraTextureBindings;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** GPU uniforms and image descriptors consume the same texture snapshots as the CPU interface. */
final class NiagaraGpuTextureBindings {
    private final NiagaraGpuPrograms programs;
    private record Binding(String key, String id, NiagaraTextureBindings.State state, Dimension dimension) { }
    private final Map<String, Binding> textures;
    private final NiagaraTextureBindings registry;
    private Map<String, String> selections = Map.of();
    private @Nullable Map<String, TextureParameter.Snapshot> tickSnapshots;

    NiagaraGpuTextureBindings(NiagaraGpuPrograms programs, JsonObject program, NiagaraTextureBindings registry) {
        this.programs = programs;
        this.registry = registry;
        Map<String, Binding> values = new LinkedHashMap<>();
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            Dimension dimension = switch (type) {
                case "NiagaraDataInterfaceTexture" -> Dimension.D2;
                case "NiagaraDataInterface2DArrayTexture" -> Dimension.ARRAY;
                case "NiagaraDataInterfaceCubeTexture" -> Dimension.CUBE;
                case "NiagaraDataInterfaceVolumeTexture" -> Dimension.VOLUME;
                default -> null;
            };
            if (dimension == null) continue;
            String definition = switch (dimension) {
                case D2 -> "texture_2d";
                case ARRAY -> "texture_array";
                case CUBE -> "texture_cube";
                case CUBE_ARRAY -> throw new IllegalArgumentException("Cubemap arrays do not have a native Niagara texture interface");
                case VOLUME -> "texture_volume";
            };
            if (!binding.has(definition)) throw new IllegalArgumentException("Niagara texture has no object binding");
            String id = binding.get("interface_id").getAsString();
            var state = registry.register(id, binding.getAsJsonObject(definition));
            if (!binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            String key = SourceNames.canonical(binding.get("compile_name").getAsString());
            if (values.putIfAbsent(symbol, new Binding(key, id, state, dimension)) != null)
                throw new IllegalArgumentException("Duplicate Niagara texture shader symbol " + symbol);
        }
        textures = Map.copyOf(values);
    }

    @Nullable byte[] value(String name) {
        for (var entry : textures.entrySet()) {
            if (name.equals(entry.getKey() + "_TextureSize"))
                return size(snapshot(entry.getValue()), entry.getValue().dimension());
            if (entry.getValue().dimension() == Dimension.D2 && name.equals(entry.getKey() + "_MipLevels"))
                return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(snapshot(entry.getValue()).mipLevels()).array();
        }
        return null;
    }

    void snapshots(@Nullable Map<String, TextureParameter.Snapshot> textures) {
        tickSnapshots = textures == null ? null : Map.copyOf(textures);
    }
    void selections(Map<String, String> values) { selections = Map.copyOf(values); }
    private TextureParameter.Snapshot snapshot(Binding binding) {
        String id = selections.getOrDefault(binding.key(), binding.id());
        return tickSnapshots == null ? (id.equals(binding.id()) ? binding.state() : registry.get(id)).snapshot()
                : java.util.Objects.requireNonNull(tickSnapshots.get(id), "Niagara GPU tick has no texture snapshot: " + id);
    }
    private static byte[] size(TextureParameter.Snapshot snapshot, Dimension dimension) {
        if (dimension == Dimension.ARRAY || dimension == Dimension.VOLUME)
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(snapshot.width()).putFloat(snapshot.height())
                    .putFloat(dimension == Dimension.ARRAY ? snapshot.layers() : snapshot.depth()).array();
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(snapshot.width()).putInt(snapshot.height()).array();
    }

    void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var binding : shader.bindings()) {
            if (!(binding instanceof ShaderBinding.SampledTexture sampled)) continue;
            for (var entry : textures.entrySet()) {
                String name = entry.getKey() + "_Texture";
                if (!sampled.name().equals(name) && !sampled.name().startsWith(name + "_")) continue;
                var dimension = entry.getValue().dimension();
                if (sampled.array() != (dimension == Dimension.ARRAY) || sampled.cube() != (dimension == Dimension.CUBE)
                        || sampled.volume() != (dimension == Dimension.VOLUME))
                    throw new IllegalArgumentException("Niagara texture descriptor has the wrong dimension: " + sampled.name());
                var snapshot = snapshot(entry.getValue());
                if (dimension == Dimension.VOLUME) {
                    resources.volume(sampled.name(), snapshot.volume() == null ? programs.blackVolumeTexture() : snapshot.volume(), 0);
                    break;
                }
                boolean imageOnly = sampled.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE;
                var sampler = imageOnly ? null : snapshot.sampler() == null
                        ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) : snapshot.sampler();
                if (sampled.array()) {
                    var image = snapshot.arrayTexture() == null ? programs.blackArrayTexture() : snapshot.arrayTexture();
                    if (imageOnly) resources.fullArraySampledImage(sampled.name(), image);
                    else resources.fullArrayTexture(sampled.name(), image, Objects.requireNonNull(sampler));
                } else {
                    var view = snapshot.view() == null
                            ? sampled.cube() ? programs.blackCubeTexture() : programs.blackTexture() : snapshot.view();
                    if (imageOnly) resources.sampledImage(sampled.name(), view);
                    else resources.texture(sampled.name(), view, Objects.requireNonNull(sampler));
                }
                break;
            }
        }
    }
}
