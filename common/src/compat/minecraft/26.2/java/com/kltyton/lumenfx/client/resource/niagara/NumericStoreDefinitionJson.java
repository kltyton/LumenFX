package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NumericStoreDefinition;
import java.util.ArrayList;
import java.util.Base64;

final class NumericStoreDefinitionJson {
    private NumericStoreDefinitionJson() { }
    static NumericStoreDefinition read(JsonObject value) {
        if (value == null) return NumericStoreDefinition.EMPTY;
        var variables = new ArrayList<NiagaraParameterLayout.Variable>();
        var values = new ArrayList<NumericStoreDefinition.Value>();
        for (var item : value.getAsJsonArray("variables")) {
            var variable = item.getAsJsonObject();
            var key = new NiagaraParameterLayout.Key(variable.get("name").getAsString(), variable.get("source_type").getAsString(),
                    variable.get("underlying").getAsInt(), variable.get("flags").getAsInt(), NiagaraParameterLayout.Storage.NUMERIC);
            int bytes = variable.get("bytes").getAsInt();
            double[] position = null;
            if (variable.has("position") && !variable.get("position").isJsonNull()) {
                var components = variable.getAsJsonArray("position");
                if (components.size() != 3) throw new IllegalArgumentException("Instance position must have three source components");
                position = new double[] { components.get(0).getAsDouble(), components.get(1).getAsDouble(), components.get(2).getAsDouble() };
            }
            variables.add(new NiagaraParameterLayout.Variable(key, bytes,
                    variable.has("lwc") && !variable.get("lwc").isJsonNull() ? variable.get("lwc").getAsBoolean() : null));
            values.add(new NumericStoreDefinition.Value(key, variable.get("offset").getAsInt(), bytes, position));
        }
        return new NumericStoreDefinition(new NiagaraParameterLayout(variables), Base64.getDecoder().decode(value.get("data").getAsString()), values);
    }
}
