package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureArray;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGridCollectionUses.Use;
import com.kltyton.lumenfx.effect.niagara.grid.Grid2DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class NiagaraGrid2DCollections {
    public record Publication(String owner, NiagaraGrid2DCollection.Snapshot state, boolean reader, @Nullable TextureParameter target) { }
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, NiagaraGrid2DCollection> grids = new HashMap<>();
    private final HashSet<String> cpuReaders = new HashSet<>();
    private Map<String, String> readers = Map.of();
    private Map<String, TextureParameter> outputs = Map.of();

    public void register(String identity, Grid2DCollectionDefinition definition, NiagaraPlatformEnvironment environment, boolean cpu) {
        grids.computeIfAbsent(identity, ignored -> new NiagaraGrid2DCollection(definition, environment));
        if (cpu && definition.reader()) cpuReaders.add(identity);
    }

    public NiagaraGrid2DCollection grid(String identity) {
        return Objects.requireNonNull(grids.get(identity), "Uninitialized Niagara Grid2D Collection: " + identity);
    }

    public void beginBindings() { cpuReaders.clear(); }

    public void configure(List<Use> uses) {
        if (grids.isEmpty()) return;
        var use = NiagaraGridCollectionUses.resolve(uses, "grid2d_collection", "grid2d_functions", "storage_components");
        var active = new HashSet<>(cpuReaders);
        var links = new HashMap<String, String>();
        active.addAll(use.identities());
        grids.forEach((identity, grid) -> grid.configureAttributes(use.columns().getOrDefault(identity, Map.of())));
        for (String identity : active) {
            var grid = grid(identity);
            var definition = grid.definition();
            if (!definition.reader()) continue;
            String source = use.readerTarget(definition.readerEmitter(), definition.readerInterface());
            var target = source == null ? null : grids.get(source);
            if (target == null || target.definition().reader()) {
                LOGGER.warn("Niagara Grid2D Collection Reader cannot resolve {}:{}", definition.readerEmitter(), definition.readerInterface());
                grid.readerTarget(null);
                continue;
            }
            if (target.definition().unnamedAttributes() > 0 && target.definition().unnamedAttributes() != target.capture().layers())
                LOGGER.warn("Niagara Grid2D Collection Reader {} uses a source with both named and unnamed attributes", identity);
            grid.readerTarget(target);
            links.put(identity, source);
        }
        readers = Map.copyOf(links);
    }

    public void postSimulate() { grids.values().forEach(NiagaraGrid2DCollection::postSimulate); }
    public void postSimulate(String identity) { grid(identity).postSimulate(); }

    public void captureOutputs(Map<String, TextureParameter> inputs, @Nullable NiagaraParameterStore parameters) {
        if (grids.isEmpty()) return;
        var targets = new HashMap<String, TextureParameter>();
        grids.forEach((identity, grid) -> {
            String name = grid.definition().outputBinding();
            if (grid.definition().reader() || name.isEmpty() || SourceNames.equal(name, "None")) return;
            var binding = parameters == null ? null : parameters.objectBinding(name);
            Object value = binding != null && binding.valid() ? binding.value() : null;
            if (binding == null || !binding.valid()) {
                for (var entry : inputs.entrySet()) if (SourceNames.equal(name, entry.getKey())) {
                    value = entry.getValue();
                    break;
                }
            }
            if (value instanceof TextureParameter.Resource resource) {
                value = EffectTextures.targetArray(resource.id());
                if (value == null) value = EffectTextures.target(resource.id());
            }
            if (value instanceof RenderTargetTextureArray array) targets.put(identity, array);
            else if (value instanceof RenderTargetTexture texture) targets.put(identity, texture);
        });
        outputs = Map.copyOf(targets);
    }

    public Map<String, Publication> capture() {
        if (grids.isEmpty()) return Map.of();
        var result = new HashMap<String, Publication>();
        grids.forEach((identity, grid) -> {
            String owner = readers.getOrDefault(identity, identity);
            result.put(identity, new Publication(owner, grid(owner).capture(), grid.definition().reader(), outputs.get(owner)));
        });
        return Map.copyOf(result);
    }

    public @Nullable TextureParameter output(String identity) { return outputs.get(identity); }

    public void clear() { grids.clear(); cpuReaders.clear(); readers = Map.of(); outputs = Map.of(); }
}
