package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import net.minecraft.resources.Identifier;

/** Immutable collection instances retain typed FName keys and distinguish an empty override from no instance. */
public final class ParameterCollectionValues {
    public boolean isEmpty() { return values.isEmpty(); }
    private sealed interface Value permits NumericValue, TextureValue, ObjectInput { }
    private record NumericValue(EffectHandle.ParameterValue value) implements Value { }
    private record TextureValue(ObjectParameter value) implements Value { }
    private record ObjectInput(ObjectParameter value, @Nullable TextureParameter texture) implements Value { }
    public record ObjectParameter(@Nullable ObjectValue value, @Nullable TextureParameter texture) { }
    public record Parameter(String name, String sourceType) {
        public Parameter {
            if (name == null || name.isBlank() || sourceType == null || sourceType.isBlank())
                throw new IllegalArgumentException("Invalid collection parameter identity");
        }
    }
    public static final ParameterCollectionValues EMPTY = new ParameterCollectionValues(Map.of());
    private static final Comparator<Parameter> ORDER = Comparator.comparing(Parameter::name, SourceNames.ASCII_CASE_INSENSITIVE)
            .thenComparing(Parameter::sourceType, SourceNames.ASCII_CASE_INSENSITIVE);
    private final Map<String, Map<Parameter, Value>> values;
    public static Comparator<Parameter> parameterOrder() { return ORDER; }

    private ParameterCollectionValues(Map<String, Map<Parameter, Value>> source) {
        TreeMap<String, Map<Parameter, Value>> collections = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        source.forEach((path, parameters) -> {
            TreeMap<Parameter, Value> copy = new TreeMap<>(ORDER);
            copy.putAll(parameters);
            collections.put(path, Collections.unmodifiableMap(copy));
        });
        values = Collections.unmodifiableMap(collections);
    }

    public boolean contains(String collection) { return values.containsKey(collection); }
    public EffectHandle.@Nullable ParameterValue get(String collection, String name, String sourceType) {
        return get(collection, new Parameter(name, sourceType));
    }
    public EffectHandle.@Nullable ParameterValue get(String collection, Parameter parameter) {
        Map<Parameter, Value> parameters = values.get(collection);
        Value value = parameters == null ? null : parameters.get(parameter);
        return value instanceof NumericValue numeric ? numeric.value() : null;
    }
    public @Nullable TextureParameter getTexture(String collection, String name, String sourceType) {
        Map<Parameter, Value> parameters = values.get(collection);
        Value value = parameters == null ? null : parameters.get(new Parameter(name, sourceType));
        if (value instanceof ObjectInput object) {
            if (object.texture() == null)
                throw new IllegalArgumentException("Collection object requires a prepared texture or resolved source class: " + name);
            return object.texture();
        }
        return value instanceof TextureValue texture ? texture.value().texture() : null;
    }
    public @Nullable ObjectParameter getObject(String collection, String name, String sourceType) {
        Map<Parameter, Value> parameters = values.get(collection);
        Value value = parameters == null ? null : parameters.get(new Parameter(name, sourceType));
        if (value instanceof ObjectInput object) return object.value();
        return value instanceof TextureValue texture ? texture.value() : null;
    }
    public ParameterCollectionValues with(String collection, String name, String sourceType, EffectHandle.ParameterValue value) {
        return withValue(collection, name, sourceType, new NumericValue(Objects.requireNonNull(value)));
    }
    /** A null object remains a typed entry and is distinct from removing the parameter. */
    public ParameterCollectionValues withTexture(String collection, String name, String sourceType, @Nullable TextureParameter value) {
        return withValue(collection, name, sourceType, new TextureValue(new ObjectParameter(null,
                value == null ? TextureParameter.unbound() : value)));
    }
    public ParameterCollectionValues withObject(String collection, String name, String sourceType, @Nullable ObjectValue value) {
        TextureParameter texture = null;
        if (value != null) {
            var source = value.source();
            if (source.has("runtime_texture"))
                texture = TextureParameter.resource(Identifier.parse(source.get("runtime_texture").getAsString()));
            else if (source.has("texture_cast") && !source.get("texture_cast").getAsBoolean())
                texture = TextureParameter.unbound();
        } else texture = TextureParameter.unbound();
        return withValue(collection, name, sourceType, new ObjectInput(new ObjectParameter(value, null), texture));
    }
    private ParameterCollectionValues withValue(String collection, String name, String sourceType, Value value) {
        requireCollection(collection); Objects.requireNonNull(value);
        TreeMap<String, Map<Parameter, Value>> copy = copy();
        TreeMap<Parameter, Value> parameters = new TreeMap<>(ORDER);
        parameters.putAll(values.getOrDefault(collection, Map.of()));
        parameters.put(new Parameter(name, sourceType), value);
        copy.put(collection, parameters);
        return new ParameterCollectionValues(copy);
    }
    public ParameterCollectionValues withoutParameter(String collection, String name, String sourceType) {
        requireCollection(collection);
        if (!values.containsKey(collection)) return this;
        TreeMap<String, Map<Parameter, Value>> copy = copy();
        TreeMap<Parameter, Value> parameters = new TreeMap<>(ORDER);
        parameters.putAll(values.get(collection)); parameters.remove(new Parameter(name, sourceType));
        copy.put(collection, parameters);
        return new ParameterCollectionValues(copy);
    }
    public ParameterCollectionValues withoutCollection(String collection) {
        requireCollection(collection);
        if (!values.containsKey(collection)) return this;
        TreeMap<String, Map<Parameter, Value>> copy = copy();
        copy.remove(collection);
        return new ParameterCollectionValues(copy);
    }
    private TreeMap<String, Map<Parameter, Value>> copy() {
        TreeMap<String, Map<Parameter, Value>> result = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        result.putAll(values);
        return result;
    }
    private static void requireCollection(String collection) {
        if (collection == null || collection.isBlank()) throw new IllegalArgumentException("Invalid collection object path");
    }
}
