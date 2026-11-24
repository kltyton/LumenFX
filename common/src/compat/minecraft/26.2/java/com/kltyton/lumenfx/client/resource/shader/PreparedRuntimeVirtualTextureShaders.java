package com.kltyton.lumenfx.client.resource.shader;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Native RVT page compression permutations required by the current resource generation. */
public record PreparedRuntimeVirtualTextureShaders(Map<Material, PreparedNiagaraShaders.Stage> stages) {
    public PreparedRuntimeVirtualTextureShaders { stages = Map.copyOf(stages); }

    public static PreparedRuntimeVirtualTextureShaders prepare(ResourceManager resources, boolean needsCompression) {
        if (!needsCompression) return new PreparedRuntimeVirtualTextureShaders(Map.of());
        var materials = EnumSet.complementOf(EnumSet.of(Material.WorldHeight));
        var programs = new LinkedHashMap<String, Set<String>>();
        for (Material material : materials) programs.put(key(material), Set.of("windows_sm5"));
        var loaded = NiagaraUtilityShaderResources.prepare(resources, "runtime_virtual_texture", programs);
        var stages = new EnumMap<Material, PreparedNiagaraShaders.Stage>(Material.class);
        for (Material material : materials) stages.put(material, loaded.programs().get(key(material)).get("windows_sm5"));
        return new PreparedRuntimeVirtualTextureShaders(stages);
    }

    private static String key(Material material) {
        String key = material.name().toLowerCase(Locale.ROOT);
        return material == Material.BaseColor || key.startsWith("basecolor_") ? key.replace("basecolor", "base_color") : key;
    }
}
