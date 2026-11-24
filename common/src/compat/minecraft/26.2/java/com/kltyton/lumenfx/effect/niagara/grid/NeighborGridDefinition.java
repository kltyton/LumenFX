package com.kltyton.lumenfx.effect.niagara.grid;

import com.google.gson.JsonObject;
import java.util.Locale;

/** Saved resolution and original bounds of a Neighbor Grid3D data interface. */
public record NeighborGridDefinition(Resolution resolution, int x, int y, int z, float cellSize,
                                     int maxAxis, int neighbors, double worldX, double worldY, double worldZ) {
    public enum Resolution { INDEPENDENT, MAXAXIS, CELLSIZE }

    public static NeighborGridDefinition read(JsonObject value) {
        var cells = value.getAsJsonArray("cells");
        var bounds = value.getAsJsonArray("world_bounds");
        return new NeighborGridDefinition(Resolution.valueOf(value.get("resolution").getAsString().toUpperCase(Locale.ROOT)),
                cells.get(0).getAsInt(), cells.get(1).getAsInt(), cells.get(2).getAsInt(), value.get("cell_size").getAsFloat(),
                value.get("max_axis").getAsInt(), value.get("neighbors").getAsInt(),
                bounds.get(0).getAsDouble(), bounds.get(1).getAsDouble(), bounds.get(2).getAsDouble());
    }
}
