package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NumericStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.google.gson.JsonParseException;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Reads a complete execution declaration list without interpreting object references as numeric defaults. */
public final class ScriptParameterStoreJson {
    private ScriptParameterStoreJson() { }
    public static ScriptParameterStoreDefinition read(JsonObject value) {
        return read(value, id -> { throw new JsonParseException("Vector field resolver required for " + id); });
    }
    public static ScriptParameterStoreDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields) {
        var variables = new ArrayList<NiagaraParameterLayout.Variable>();
        var numeric = new ArrayList<NumericStoreDefinition.Value>();
        var references = new ArrayList<ScriptParameterStoreDefinition.Reference>();
        var interfaceValues = objects(value.getAsJsonArray("interface_values"), fields);
        var objectValues = objects(value.getAsJsonArray("object_values"), fields);
        for (var item : value.getAsJsonArray("variables")) {
            JsonObject variable = item.getAsJsonObject();
            Key key = key(variable);
            int bytes = variable.get("bytes").getAsInt();
            variables.add(new NiagaraParameterLayout.Variable(key, bytes,
                    variable.has("lwc") && !variable.get("lwc").isJsonNull() ? variable.get("lwc").getAsBoolean() : null,
                    key.storage() == Storage.NUMERIC ? variable.get("offset").getAsInt() : -1,
                    variable.has("object_index") ? variable.get("object_index").getAsInt() : -1));
            if (key.storage() == Storage.NUMERIC)
                numeric.add(new NumericStoreDefinition.Value(key, variable.get("offset").getAsInt(), bytes, position(variable)));
        }
        for (var item : value.getAsJsonArray("references")) {
            JsonObject reference = item.getAsJsonObject();
            JsonObject collection = reference.getAsJsonObject("collection");
            references.add(new ScriptParameterStoreDefinition.Reference(key(reference.getAsJsonObject("key")),
                    object(reference.get("value"), fields), collection == null ? null
                    : new ScriptParameterStoreDefinition.CollectionObjectBinding(collection.get("path").getAsString(),
                        collection.get("system_override").getAsBoolean(), object(collection.get("base_default"), fields))));
        }
        byte[] data = Base64.getDecoder().decode(value.get("data").getAsString());
        var userInterfaces = new ArrayList<ScriptParameterStoreDefinition.UserInterfaceBinding>();
        if (value.has("instance_bindings")) for (var item : value.getAsJsonArray("instance_bindings")) {
            JsonObject binding = item.getAsJsonObject();
            userInterfaces.add(new ScriptParameterStoreDefinition.UserInterfaceBinding(
                    key(binding.getAsJsonObject("source")), key(binding.getAsJsonObject("target"))));
        }
        return new ScriptParameterStoreDefinition(new NiagaraParameterLayout(variables, data.length,
                value.has("interface_values") ? interfaceValues.size() : -1, value.has("object_values") ? objectValues.size() : -1), data, numeric, references,
                value.get("current_bytes").getAsInt(), value.get("interpolated").getAsBoolean(),
                sources(value.getAsJsonArray("initial_sources"), fields), sources(value.getAsJsonArray("instance_sources"), fields), userInterfaces,
                interfaceValues, objectValues);
    }
    private static List<@Nullable ObjectValue> objects(@Nullable JsonArray values, Function<Identifier, VectorFieldVolume> fields) {
        if (values == null) return List.of();
        var result = new ArrayList<@Nullable ObjectValue>();
        for (var value : values) result.add(object(value, fields));
        return result;
    }
    private static @Nullable ObjectValue object(JsonElement value, Function<Identifier, VectorFieldVolume> fields) {
        if (value.isJsonNull()) return null;
        JsonObject object = value.getAsJsonObject();
        return new ObjectValue(object.get("id").getAsString(), object,
                object.has("curve_defaults") ? NiagaraJson.curveDefaults(object.getAsJsonArray("curve_defaults")) : null,
                object.has("vector_field_defaults") ? NiagaraJson.vectorFieldDefault(object.getAsJsonObject("vector_field_defaults"), fields) : null);
    }
    private static List<ScriptParameterStoreDefinition.Source> sources(@Nullable JsonArray values, Function<Identifier, VectorFieldVolume> fields) {
        if (values == null) return List.of();
        var result = new ArrayList<ScriptParameterStoreDefinition.Source>();
        for (var item : values) {
            JsonObject source = item.getAsJsonObject();
            List<ScriptParameterStoreDefinition.BoundOffset> offsets = null;
            if (source.has("bound_offsets")) {
                offsets = new ArrayList<>();
                for (var entry : source.getAsJsonArray("bound_offsets")) {
                    JsonObject offset = entry.getAsJsonObject();
                    offsets.add(new ScriptParameterStoreDefinition.BoundOffset(storage(offset),
                            offset.get("source").getAsInt(), offset.get("target").getAsInt(), offset.get("bytes").getAsInt()));
                }
            }
            result.add(new ScriptParameterStoreDefinition.Source(source.get("id").getAsString(),
                    read(source.getAsJsonObject("definition"), fields), source.get("tick").getAsBoolean(), offsets));
        }
        return List.copyOf(result);
    }
    public static Key key(JsonObject value) {
        return new Key(value.get("name").getAsString(), value.get("source_type").getAsString(),
                value.get("underlying").getAsInt(), value.get("flags").getAsInt(), storage(value));
    }
    private static Storage storage(JsonObject value) {
        return switch (value.get("storage").getAsString()) {
            case "numeric" -> Storage.NUMERIC;
            case "interface" -> Storage.INTERFACE;
            case "object" -> Storage.OBJECT;
            default -> throw new IllegalArgumentException("Unknown execution parameter storage");
        };
    }
    private static double @Nullable [] position(JsonObject value) {
        if (!value.has("position") || value.get("position").isJsonNull()) return null;
        JsonArray components = value.getAsJsonArray("position");
        if (components.size() != 3) throw new IllegalArgumentException("Store position requires three source coordinates");
        return new double[] {components.get(0).getAsDouble(), components.get(1).getAsDouble(), components.get(2).getAsDouble()};
    }
}
