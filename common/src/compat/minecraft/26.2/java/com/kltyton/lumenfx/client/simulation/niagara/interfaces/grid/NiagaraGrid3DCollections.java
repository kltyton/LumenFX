package com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureVolume;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollection.Snapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGridCollectionUses.Use;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.grid.Grid3DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGrid3DCollections {
    public record Publication(String owner, Snapshot state, boolean reader, @Nullable RenderTargetTextureVolume output) { }
    private final Map<String, NiagaraGrid3DCollection> grids = new LinkedHashMap<>();
    private final HashSet<String> cpuReaders = new HashSet<>();
    private Map<String, String> readers = Map.of();
    private Map<String, RenderTargetTextureVolume> outputs = Map.of();

    public void register(String identity, Grid3DCollectionDefinition definition, NiagaraPlatformEnvironment environment, boolean cpu) {
        grids.computeIfAbsent(identity, ignored -> new NiagaraGrid3DCollection(definition, environment));
        if (cpu && definition.reader()) cpuReaders.add(identity);
    }

    public NiagaraGrid3DCollection grid(String identity) {
        return Objects.requireNonNull(grids.get(identity), "Uninitialized Niagara Grid3D Collection: " + identity);
    }

    public void beginBindings() { cpuReaders.clear(); }

    public void configure(List<Use> uses) {
        if (grids.isEmpty()) return;
        var usesByIdentity = NiagaraGridCollectionUses.resolve(uses, "grid3d_collection", "grid3d_functions", "components");
        var active = new HashSet<>(cpuReaders);
        active.addAll(usesByIdentity.identities());
        grids.forEach((identity, grid) -> grid.configureAttributes(usesByIdentity.columns().getOrDefault(identity, Map.of())));
        var links = new HashMap<String, String>();
        for (String identity : active) {
            var grid = grid(identity);
            var definition = grid.definition();
            if (!definition.reader()) continue;
            String target = usesByIdentity.readerTarget(definition.readerEmitter(), definition.readerInterface());
            if (target == null || grid(target).definition().reader())
                throw new IllegalArgumentException("Niagara Grid3D Collection Reader cannot resolve "
                        + definition.readerEmitter() + ":" + definition.readerInterface());
            grid.readerTarget(grid(target));
            links.put(identity, target);
        }
        readers = Map.copyOf(links);
    }

    public void captureOutputs(Map<String, TextureParameter> inputs, @Nullable NiagaraParameterStore parameters) {
        if (grids.isEmpty()) return;
        var resolved = new HashMap<String, RenderTargetTextureVolume>();
        grids.forEach((identity, grid) -> {
            String name = grid.outputBinding();
            if (grid.definition().reader() || name.isEmpty() || SourceNames.equal(name, "None")) return;
            var binding = parameters == null ? null : parameters.objectBinding(name);
            Object value = binding != null && binding.valid() ? binding.value() : null;
            if (binding == null || !binding.valid()) {
                for (var entry : inputs.entrySet()) if (SourceNames.equal(name, entry.getKey())) {
                    value = entry.getValue();
                    break;
                }
            }
            var target = value instanceof RenderTargetTextureVolume volume ? volume
                    : value instanceof TextureParameter.Resource resource ? EffectTextures.targetVolume(resource.id()) : null;
            if (target != null) resolved.put(identity, target);
        });
        outputs = Map.copyOf(resolved);
    }

    public void postSimulate() { grids.values().forEach(NiagaraGrid3DCollection::postSimulate); }
    public void postSimulate(String identity) { grid(identity).postSimulate(); }

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

    public void clear() {
        grids.clear();
        cpuReaders.clear();
        readers = Map.of();
        outputs = Map.of();
    }
}
