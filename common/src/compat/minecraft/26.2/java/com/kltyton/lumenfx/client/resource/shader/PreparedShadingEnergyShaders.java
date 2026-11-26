package com.kltyton.lumenfx.client.resource.shader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** Source energy integration kernels with the exact typed image formats used by each pass. */
public record PreparedShadingEnergyShaders(Map<String, Map<String, PreparedNiagaraShaders.Stage>> programs) {
    private static final String PIPELINE = "shading_energy";
    private static final Set<String> SUBSTRATE = Set.of("substrate");

    public static @Nullable PreparedShadingEnergyShaders prepare(ResourceManager resources) {
        var index = Identifier.fromNamespaceAndPath("lumenfx", "shaders/compute/" + PIPELINE + "/index.json");
        if (!ShaderResourceFiles.exists(resources, index)) return null;
        var expected = new LinkedHashMap<String, Set<String>>();
        for (String format : Set.of("float", "unorm16", "unorm8")) {
            expected.put("reflection_" + format, SUBSTRATE);
            expected.put("transmission_" + format, SUBSTRATE);
            expected.put("sheen_" + format, Set.of("substrate", "legacy"));
        }
        expected.put("diffuse_unorm16", SUBSTRATE);
        expected.put("diffuse_unorm8", SUBSTRATE);
        expected.put("ggx_max_half", SUBSTRATE);
        return new PreparedShadingEnergyShaders(NiagaraUtilityShaderResources.prepare(resources, PIPELINE, expected).programs());
    }
}
