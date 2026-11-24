package com.kltyton.lumenfx.client.render.niagara.gpu.view;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraSceneCapture.Publication;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraSceneCaptureView;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuSceneCaptureBindings {
    private record Source(String compileName, String identity) { }
    private static final NiagaraSceneCaptureView EMPTY = new NiagaraSceneCaptureView();
    private final Map<String, Source> bindings = new HashMap<>();
    private final Map<String, Publication> active = new HashMap<>();

    public NiagaraGpuSceneCaptureBindings(JsonObject program) {
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("scene_capture_2d") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var source = new Source(SourceNames.canonical(binding.get("compile_name").getAsString()),
                    binding.get("interface_id").getAsString());
            if (bindings.putIfAbsent(symbol, source) != null)
                throw new IllegalArgumentException("Duplicate Niagara Scene Capture 2D shader symbol " + symbol);
        }
    }

    public void beginTick(Map<String, String> aliases, Map<String, Publication> frames) {
        active.clear();
        for (var item : bindings.entrySet()) {
            var source = item.getValue();
            var frame = frames.get(aliases.getOrDefault(source.compileName(), source.identity()));
            if (frame != null) active.put(item.getKey(), frame);
        }
    }

    public void clear() { active.clear(); }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int separator = name.lastIndexOf('_');
        if (separator < 0) return null;
        String symbol = name.substring(0, separator);
        if (!bindings.containsKey(symbol)) return null;
        var frame = active.get(symbol);
        return (frame == null ? EMPTY : frame.view()).shaderValue(name.substring(separator + 1));
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources, Supplier<GpuTextureView> white) {
        if (bindings.isEmpty()) return;
        for (var descriptor : shader.bindings()) {
            if (!(descriptor instanceof ShaderBinding.SampledTexture sampled) || sampled.array() || sampled.volume() || sampled.cube()) continue;
            String name = sampled.name();
            for (String symbol : bindings.keySet()) {
                String imageName = symbol + "_Texture";
                if (!name.equals(imageName) && !name.startsWith(imageName + "_")) continue;
                var frame = active.get(symbol);
                var image = frame == null ? null : frame.texture().view();
                var sampler = frame == null ? null : frame.texture().sampler();
                if (image == null) image = white.get();
                if (sampled.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.sampledImage(name, image);
                else resources.texture(name, image, sampler == null ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) : sampler);
                break;
            }
        }
    }
}
