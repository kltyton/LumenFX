package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Resolves ribbon attributes against the compiled GPU dataset layout. */
public record NiagaraGpuRibbonBindings(int position, int color, int width, int twist, int facing,
                                       int normalizedAge, int random, int velocity, int age, int lifetime,
                                       int dynamic0, int dynamic1, int dynamic2, int dynamic3, int dynamicMask,
                                       int distance, int u0, int v0, int u1, int v1,
                                       NiagaraGpuRibbonSort.IdType idType, int idOffset, int linkOffset, boolean linkFloat) {
    public static NiagaraGpuRibbonBindings resolve(NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer) {
        List<NiagaraDefinition.Attribute> attrs = emitter.attributes();
        int position = bound(attrs, renderer, "Position", 3), color = bound(attrs, renderer, "Color", 4);
        int width = bound(attrs, renderer, "RibbonWidth", 1), twist = bound(attrs, renderer, "RibbonTwist", 1);
        int facing = bound(attrs, renderer, "RibbonFacing", 3);
        var ageBinding = renderer.attributeBindings().get("NormalizedAge");
        int normalizedAge = ageBinding != null && !ageBinding.particle() ? -1
                : offset(NiagaraParticleReadTypes.renderer(attrs, ageBinding == null ? "NormalizedAge" : ageBinding.name(), "Float"));
        int dynamic0 = renderer.dynamicParameterParticleBinding() ? floating(attrs, renderer.dynamicParameterBinding(), 4) : -1;
        int[] dynamic = new int[3];
        int dynamicMask = dynamic0 == -1 ? 0 : renderer.dynamicParameterChannelMask();
        for (int i = 0; i < dynamic.length; i++) {
            String semantic = "DynamicMaterial" + (i + 1);
            dynamic[i] = bound(attrs, renderer, semantic, 4);
            var binding = renderer.attributeBindings().get(semantic);
            if (dynamic[i] != -1) dynamicMask |= (binding == null ? 15 : binding.channelMask()) << (4 * (i + 1));
        }
        NiagaraDefinition.Attribute ribbonId = NiagaraParticleReadTypes.ribbonId(attrs, renderer);
        NiagaraGpuRibbonSort.IdType idType = NiagaraGpuRibbonSort.IdType.NONE;
        int idOffset = -1;
        if (ribbonId != null) {
            idOffset = ribbonId.component();
            idType = ribbonId.count() == 2 ? NiagaraGpuRibbonSort.IdType.NIAGARA_ID : NiagaraGpuRibbonSort.IdType.INTEGER;
        }
        int link = offset(NiagaraParticleReadTypes.renderer(attrs, renderer.ribbonLinkBinding(), "Float"));
        boolean linkFloat = link != -1;
        if (link == -1 && renderer.ribbonUniqueFallback()) {
            var unique = NiagaraParticleReadTypes.renderer(attrs, "Particles.UniqueID", "Int");
            if (unique != null) link = unique.component();
        }
        if (link == -1) { link = normalizedAge; linkFloat = link != -1; }
        return new NiagaraGpuRibbonBindings(position, color, width, twist, facing, normalizedAge,
                bound(attrs, renderer, "MaterialRandom", 1), bound(attrs, renderer, "Velocity", 3),
                floating(attrs, "Particles.Age", 1), floating(attrs, "Particles.Lifetime", 1),
                dynamic0, dynamic[0], dynamic[1], dynamic[2], dynamicMask,
                bound(attrs, renderer, "RibbonUVDistance", 1), bound(attrs, renderer, "RibbonU0Override", 1),
                bound(attrs, renderer, "RibbonV0RangeOverride", 2), bound(attrs, renderer, "RibbonU1Override", 1),
                bound(attrs, renderer, "RibbonV1RangeOverride", 2), idType, idOffset, link, linkFloat);
    }

    /**
     * UE 5.9's compute initialization of a CPU emitter indexes its compact renderer upload with
     * the compiled dataset link register. Resolve the column it can actually read while LumenFX
     * keeps the full dataset resident. A register outside that upload has no link key.
     */
    int cpuUploadLinkOffset(NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer,
                            RibbonSettings settings) {
        if (!linkFloat) {
            int uploaded = switch (idType) {
                case NONE -> 0;
                case NIAGARA_ID -> 2;
                case INTEGER -> 1;
            };
            return linkOffset >= 0 && linkOffset < uploaded ? idOffset + linkOffset : -1;
        }

        var columns = new LinkedHashMap<Integer, Integer>();
        columns.putIfAbsent(position, 3);
        columns.putIfAbsent(velocity, 3);
        columns.putIfAbsent(color, 4);
        columns.putIfAbsent(width, 1);
        columns.putIfAbsent(twist, 1);
        if (settings.shape().facing() != RibbonSettings.Facing.SCREEN) columns.putIfAbsent(facing, 3);
        columns.putIfAbsent(normalizedAge, 1);
        columns.putIfAbsent(random, 1);
        columns.putIfAbsent(distance, 1);
        columns.putIfAbsent(u0, 1);
        columns.putIfAbsent(v0, 2);
        columns.putIfAbsent(u1, 1);
        columns.putIfAbsent(v1, 2);
        columns.putIfAbsent(dynamic0, 4);
        columns.putIfAbsent(dynamic1, 4);
        columns.putIfAbsent(dynamic2, 4);
        columns.putIfAbsent(dynamic3, 4);
        if (renderer.motion().precise()) {
            var attrs = emitter.attributes();
            columns.putIfAbsent(bound(attrs, renderer, "PreviousPosition", 3), 3);
            if (settings.shape().facing() != RibbonSettings.Facing.SCREEN)
                columns.putIfAbsent(bound(attrs, renderer, "PreviousRibbonFacing", 3), 3);
            columns.putIfAbsent(bound(attrs, renderer, "PreviousRibbonWidth", 1), 1);
            columns.putIfAbsent(bound(attrs, renderer, "PreviousRibbonTwist", 1), 1);
        }

        int part = linkOffset & Integer.MAX_VALUE;
        boolean half = linkOffset < 0;
        for (var column : columns.entrySet()) {
            int offset = column.getKey();
            if (offset == -1 || (offset < 0) != half) continue;
            int count = column.getValue();
            if (part < count) return offset + part;
            part -= count;
        }
        return -1;
    }

    public static int previous(NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer, String semantic, int count, int current) {
        int value = bound(emitter.attributes(), renderer, semantic, count);
        return value == -1 ? current : value;
    }

    private static int bound(List<NiagaraDefinition.Attribute> attrs, NiagaraDefinition.Renderer renderer, String semantic, int count) {
        var binding = renderer.attributeBindings().get(semantic);
        if (binding != null && !binding.particle()) return -1;
        return floating(attrs, binding == null ? "Particles." + semantic : binding.name(), count);
    }

    private static int floating(List<NiagaraDefinition.Attribute> attrs, @Nullable String name, int count) {
        var attribute = find(attrs, name);
        if (attribute == null || attribute.count() != count || attribute.type() != 0 && attribute.type() != 2) return -1;
        return offset(attribute);
    }

    private static int offset(NiagaraDefinition.@Nullable Attribute attribute) {
        return attribute == null ? -1 : attribute.component() | (attribute.type() == 2 ? Integer.MIN_VALUE : 0);
    }

    private static NiagaraDefinition.@Nullable Attribute find(List<NiagaraDefinition.Attribute> attrs, @Nullable String name) {
        if (name == null) return null;
        String bare = name.regionMatches(true, 0, "Particles.", 0, "Particles.".length())
                ? name.substring("Particles.".length()) : name;
        for (var value : attrs) {
            String attrBare = value.name().regionMatches(true, 0, "Particles.", 0, "Particles.".length())
                    ? value.name().substring("Particles.".length()) : value.name();
            if (com.kltyton.lumenfx.effect.niagara.parameter.SourceNames.equal(attrBare, bare)) return value;
        }
        return null;
    }
}
