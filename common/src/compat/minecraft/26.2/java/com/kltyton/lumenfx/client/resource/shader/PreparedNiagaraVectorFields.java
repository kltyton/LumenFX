package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/** Resource-prepared vector fields and source-axis tiling for a Niagara GPU program. */
public record PreparedNiagaraVectorFields(Map<String, Field> fields) {
    private static final VectorFieldVolume BLACK = VectorFieldVolume.fromHalfRgba(1, 1, 1, -1, -1, -1, 1, 1, 1, new short[4], true);
    public record Field(VectorFieldVolume volume, int tilingAxes) { }
    public static VectorFieldVolume blackField() { return BLACK; }
    public PreparedNiagaraVectorFields { fields = Map.copyOf(fields); }

    public static PreparedNiagaraVectorFields read(JsonObject program, Function<Identifier, VectorFieldVolume> lookup) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (var item : program.getAsJsonArray("interfaces")) {
            JsonObject binding = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceVectorField".equals(binding.get("class").getAsString())) continue;
            if (!binding.has("shader_symbol")) throw new IllegalArgumentException("Missing Niagara vector field shader symbol");
            if (binding.has("source_reference"))
                throw new IllegalArgumentException("Unresolved Niagara vector field interface " + binding.get("source_reference").getAsString());
            String symbol = binding.get("shader_symbol").getAsString();
            JsonObject properties = binding.has("properties") && binding.get("properties").isJsonObject()
                    ? binding.getAsJsonObject("properties") : new JsonObject();
            int axes = (flag(properties, "bTileX") ? 1 : 0) | (flag(properties, "bTileY") ? 2 : 0)
                    | (flag(properties, "bTileZ") ? 4 : 0);
            VectorFieldVolume volume = binding.has("field") && !binding.get("field").isJsonNull()
                    ? lookup.apply(Identifier.parse(binding.get("field").getAsString()))
                    : BLACK;
            if (fields.putIfAbsent(symbol, new Field(volume, axes)) != null)
                throw new IllegalArgumentException("Duplicate Niagara vector field shader symbol " + symbol);
        }
        return new PreparedNiagaraVectorFields(fields);
    }

    private static boolean flag(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() && value.get(name).getAsBoolean();
    }
}
