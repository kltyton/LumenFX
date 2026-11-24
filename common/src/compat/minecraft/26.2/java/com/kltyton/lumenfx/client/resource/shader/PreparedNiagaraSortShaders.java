package com.kltyton.lumenfx.client.resource.shader;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Preloaded Niagara sort-key and Unreal GPU sorting programs. */
public record PreparedNiagaraSortShaders(String engineHead,
                                         Map<String, PreparedNiagaraShaders.Stage> keyVariants,
                                         Map<String, Map<String, PreparedNiagaraShaders.Stage>> bitonicStages,
                                         Map<String, Map<String, PreparedNiagaraShaders.Stage>> radixStages,
                                         Map<String, Map<String, PreparedNiagaraShaders.Stage>> ribbonStages,
                                         Map<String, Map<String, PreparedNiagaraShaders.Stage>> ribbonAggregationStages,
                                         Map<String, Map<String, PreparedNiagaraShaders.Stage>> ribbonIndexStages,
                                         Map<String, Map<String, PreparedNiagaraShaders.Stage>> neighborQueryStages) {
    private static final String RIBBON_SORT = "niagara_ribbon_sort";
    private static final String RIBBON_AGGREGATION = "niagara_ribbon_aggregation";
    private static final String RIBBON_INDICES = "niagara_ribbon_indices";
    private static final List<String> RIBBON_PIPELINES = List.of(RIBBON_SORT, RIBBON_AGGREGATION, RIBBON_INDICES);

    public PreparedNiagaraSortShaders {
        keyVariants = Map.copyOf(keyVariants);
        bitonicStages = Map.copyOf(bitonicStages);
        radixStages = Map.copyOf(radixStages);
        ribbonStages = Map.copyOf(ribbonStages);
        ribbonAggregationStages = Map.copyOf(ribbonAggregationStages);
        ribbonIndexStages = Map.copyOf(ribbonIndexStages);
        neighborQueryStages = Map.copyOf(neighborQueryStages);
    }

    public static PreparedNiagaraSortShaders prepare(ResourceManager resources, boolean needsRibbon) {
        return prepare(resources, needsRibbon, false);
    }

    public static boolean hasRibbonPipelineMetadata(ResourceManager resources) {
        return RIBBON_PIPELINES.stream().map(NiagaraUtilityShaderResources::indexPath)
                .anyMatch(id -> resources.getResource(id).isPresent());
    }

    public static boolean hasSortPipelines(ResourceManager resources) {
        return List.of("niagara_sort_key", "niagara_bitonic_sort", "niagara_radix_sort",
                        "niagara_draw_indirect", "niagara_persistent_ids").stream()
                .map(NiagaraUtilityShaderResources::indexPath)
                .allMatch(id -> resources.getResource(id).isPresent());
    }

    public static PreparedNiagaraSortShaders prepare(ResourceManager resources, boolean needsRibbon, boolean needsNeighborQuery) {
        var keys = NiagaraUtilityShaderResources.prepare(resources, "niagara_sort_key",
                Map.of("generate", Set.of("basic", "precise", "cull_basic", "cull_precise")));
        var bitonic = NiagaraUtilityShaderResources.prepare(resources, "niagara_bitonic_sort",
                Map.of("local_sort", Set.of("default"), "global_merge", Set.of("default"), "local_merge", Set.of("default")));
        var radix = NiagaraUtilityShaderResources.prepare(resources, "niagara_radix_sort",
                Map.of("clear", Set.of("default"), "upsweep", Set.of("default"),
                        "spine", Set.of("default"), "downsweep", Set.of("default")));
        var ribbon = needsRibbon ? NiagaraUtilityShaderResources.prepare(resources, RIBBON_SORT,
                Map.of("initial", Set.of("single", "id", "integer"), "merge", Set.of("single", "id", "integer"))) : null;
        var ribbonAggregation = needsRibbon ? NiagaraUtilityShaderResources.prepare(resources, RIBBON_AGGREGATION,
                Map.of("initialize", Set.of("single", "id", "integer"), "step", Set.of("single", "id", "integer"),
                        "apply", Set.of("single", "id", "integer"), "finalize", Set.of("single", "id", "integer"),
                        "uv", Set.of("single", "id", "integer"))) : null;
        var ribbonIndices = needsRibbon ? NiagaraUtilityShaderResources.prepare(resources, RIBBON_INDICES,
                Map.of("initialize", Set.of("default"), "generate", Set.of("single", "id", "integer"))) : null;
        var neighborQuery = needsNeighborQuery ? NiagaraUtilityShaderResources.prepare(resources, "niagara_neighbor_query",
                Map.of("histogram", Set.of("default"), "scan", Set.of("default"), "propagate", Set.of("default"), "scatter", Set.of("default"))) : null;
        if (!keys.engineHead().equals(bitonic.engineHead()) || !keys.engineHead().equals(radix.engineHead())
                || ribbon != null && !keys.engineHead().equals(ribbon.engineHead())
                || ribbonAggregation != null && !keys.engineHead().equals(ribbonAggregation.engineHead())
                || ribbonIndices != null && !keys.engineHead().equals(ribbonIndices.engineHead())
                || neighborQuery != null && !keys.engineHead().equals(neighborQuery.engineHead()))
            throw new IllegalArgumentException("Niagara sort shader engine versions differ");
        return new PreparedNiagaraSortShaders(keys.engineHead(), keys.programs().get("generate"),
                bitonic.programs(), radix.programs(), ribbon == null ? Map.of() : ribbon.programs(),
                ribbonAggregation == null ? Map.of() : ribbonAggregation.programs(),
                ribbonIndices == null ? Map.of() : ribbonIndices.programs(), neighborQuery == null ? Map.of() : neighborQuery.programs());
    }
}
