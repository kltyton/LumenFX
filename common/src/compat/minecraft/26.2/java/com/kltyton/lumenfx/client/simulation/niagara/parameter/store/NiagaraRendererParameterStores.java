package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import org.jspecify.annotations.Nullable;

/** Each emitter owns its required runtime slots and the separately bound compiled defaults. */
public final class NiagaraRendererParameterStores implements AutoCloseable {
    private final NiagaraParameterStore compiled;
    private final NiagaraParameterStore renderer;
    private final List<NiagaraParameterStore> parents = new ArrayList<>();
    private final List<RendererParameterDefaults.ExternalObject> externalObjects;
    private final byte[] reader = new byte[16];
    private final ByteBuffer floats = ByteBuffer.wrap(reader).order(ByteOrder.LITTLE_ENDIAN);

    public NiagaraRendererParameterStores(RendererParameterDefaults definition) {
        this(definition, true);
    }
    public NiagaraRendererParameterStores(RendererParameterDefaults definition, boolean bindCompiled) {
        externalObjects = definition.externalObjects();
        byte[] data = definition.dataBytes();
        var slots = new ArrayList<NiagaraParameterLayout.Variable>();
        for (var variable : definition.variables()) {
            int bytes = variable.bytes();
            if (bytes < 0) {
                int end = data.length;
                for (var candidate : definition.variables()) if (candidate.offset() > variable.offset()) end = Math.min(end, candidate.offset());
                bytes = end - variable.offset();
            }
            slots.add(new NiagaraParameterLayout.Variable(new Key(variable.name(), variable.sourceType(), variable.underlyingType(),
                    variable.flags(), Storage.NUMERIC), bytes, variable.lwc()));
        }
        var declared = definition.compiledLayout();
        var layout = declared == null ? new NiagaraParameterLayout(slots) : declared;
        compiled = new NiagaraParameterStore(layout);
        for (int index = 0; index < slots.size(); index++)
            compiled.setNumeric(slots.get(index).key(), data, definition.variables().get(index).offset(), slots.get(index).bytes());
        var required = definition.requiredLayout();
        renderer = new NiagaraParameterStore(required == null ? layout : required);
        if (bindCompiled) compiled.bindTo(renderer);
    }

    public NiagaraParameterStore renderer() { return renderer; }
    public void bindParents(NiagaraParameterStore spawn, NiagaraParameterStore update) {
        bindParents(List.of(spawn, update));
    }
    public void bindParents(NiagaraParameterStore spawn, NiagaraParameterStore update,
                            NiagaraParameterStore instance, NiagaraParameterStore override) {
        bindParents(spawn, update, instance, override, null);
    }
    public void bindParents(NiagaraParameterStore spawn, NiagaraParameterStore update,
                            NiagaraParameterStore instance, NiagaraParameterStore override, @Nullable NiagaraParameterStore combined) {
        bindParents(spawn, update, instance, override, combined, true);
    }
    public void bindParents(NiagaraParameterStore spawn, NiagaraParameterStore update,
                            NiagaraParameterStore instance, NiagaraParameterStore override, @Nullable NiagaraParameterStore combined,
                            boolean bindCompiled) {
        var sources = new ArrayList<>(List.of(spawn, update, instance, override));
        if (combined != null) sources.add(combined);
        bindParents(sources, bindCompiled);
    }
    private void bindParents(List<NiagaraParameterStore> sources) {
        bindParents(sources, true);
    }
    private void bindParents(List<NiagaraParameterStore> sources, boolean bindCompiled) {
        if (!parents.isEmpty()) throw new IllegalStateException("Renderer parent stores are already bound");
        compiled.unbind(renderer);
        for (var source : sources) source.bindTo(renderer);
        parents.addAll(sources);
        if (bindCompiled) compiled.bindTo(renderer);
    }
    public void bindCompiled() { compiled.bindTo(renderer); }
    public boolean read(String name, MaterialInstanceParameters.Source source, float[] output) {
        String type = source.sourceType();
        if (type == null) return false;
        Key key = new Key(name, type, 2, 0, Storage.NUMERIC);
        int bytes = Math.multiplyExact(output.length, Float.BYTES);
        if (output.length != source.components() || bytes > reader.length) throw new IllegalArgumentException("Renderer numeric reader width mismatch");
        int storedBytes = renderer.numericBytes(key);
        if (storedBytes >= 0 && storedBytes != bytes)
            throw new IllegalArgumentException("Renderer slot width differs from its requested type: " + name);
        if (!renderer.copyNumeric(key, reader, 0)) return false;
        for (int index = 0; index < output.length; index++) output[index] = floats.getFloat(index * Float.BYTES);
        return true;
    }
    public @Nullable Integer integer(String name, boolean bool) {
        String type = bool ? "/Script/Niagara.NiagaraBool" : "/Script/Niagara.NiagaraInt32";
        Key key = new Key(name, type, 2, 0, Storage.NUMERIC);
        int bytes = renderer.numericBytes(key);
        if (bytes < 0) return null;
        if (bytes != Integer.BYTES) throw new IllegalArgumentException("Renderer integer slot has an invalid width: " + name);
        return renderer.numericWord(renderer.numericOffset(key));
    }
    public Map<String, Integer> integers(boolean bool) {
        Map<String, Integer> result = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (var variable : renderer.layout().variables()) {
            Integer value = integer(variable.key().name(), bool);
            if (value != null) result.put(variable.key().name(), value);
        }
        return Collections.unmodifiableMap(result);
    }
    public @Nullable TextureParameter texture(MaterialInstanceParameters.TextureSlot binding) {
        if (binding.sourceType() == null || binding.dataInterface() != null || binding.collection() != null) return null;
        Key key = new Key(binding.variable(), binding.sourceType(), 1, binding.typeFlags(), Storage.OBJECT);
        if (!renderer.contains(key)) return null;
        return NiagaraParameterStoreSources.texture(renderer.object(key));
    }
    public void updateExternalObjects(NiagaraParameterStore overrides) {
        if (!renderer.objectsDirty()) return;
        for (var mapping : externalObjects) {
            Key target = mapping.target();
            Key source = new Key(mapping.external(), target.sourceType(), target.underlyingType(), target.flags(), Storage.OBJECT);
            if (overrides.contains(source) && renderer.contains(target)) renderer.setObject(target, overrides.object(source));
        }
        renderer.tick();
    }
    public void reset() {
        for (var parent : parents) parent.unbind(renderer);
        compiled.unbind(renderer);
        renderer.resetValues();
        for (var parent : parents) parent.bindTo(renderer);
        compiled.bindTo(renderer);
    }
    @Override public void close() { compiled.close(); renderer.close(); parents.clear(); }
}
