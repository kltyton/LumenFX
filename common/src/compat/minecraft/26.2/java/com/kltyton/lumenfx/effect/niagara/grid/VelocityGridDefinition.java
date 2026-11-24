package com.kltyton.lumenfx.effect.niagara.grid;

import com.google.gson.JsonObject;

public record VelocityGridDefinition(int x, int y, int z, boolean pressure) {
    public static VelocityGridDefinition read(JsonObject source) {
        var size = source.getAsJsonArray("size");
        if (size.size() != 3) throw new IllegalArgumentException("Velocity grid size must have three components");
        return new VelocityGridDefinition(size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt(),
                source.get("pressure").getAsBoolean());
    }

    public int attributes() { return pressure ? 18 : 6; }

    public String interfaceClass() {
        return pressure ? "NiagaraDataInterfacePressureGrid" : "NiagaraDataInterfaceVelocityGrid";
    }
}
