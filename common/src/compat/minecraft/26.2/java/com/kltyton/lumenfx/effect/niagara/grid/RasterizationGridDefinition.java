package com.kltyton.lumenfx.effect.niagara.grid;

import com.google.gson.JsonObject;

public record RasterizationGridDefinition(int x, int y, int z, int attributes, float precision,
                                          int resetValue, boolean clearOutput) {
    public static RasterizationGridDefinition read(JsonObject value) {
        var cells = value.getAsJsonArray("cells");
        return new RasterizationGridDefinition(cells.get(0).getAsInt(), cells.get(1).getAsInt(), cells.get(2).getAsInt(),
                value.get("attributes").getAsInt(), value.get("precision").getAsFloat(),
                value.get("reset_value").getAsInt(), value.get("clear_output").getAsBoolean());
    }
}
