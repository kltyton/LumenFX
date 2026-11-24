package com.kltyton.lumenfx.effect.niagara.grid;

import com.google.gson.JsonObject;
import java.util.Locale;

public record Grid3DCollectionDefinition(Resolution resolution, int x, int y, int z, float cellSize, int maxAxis,
                                         double worldX, double worldY, double worldZ, int unnamedAttributes,
                                         GridBufferFormat bufferFormat, boolean clearOutput, String outputBinding,
                                         boolean reader, String readerEmitter, String readerInterface) {
    public enum Resolution { INDEPENDENT, MAXAXIS, CELLSIZE }

    public static Grid3DCollectionDefinition read(JsonObject value) {
        var cells = value.getAsJsonArray("cells");
        var bounds = value.getAsJsonArray("world_bounds");
        return new Grid3DCollectionDefinition(Resolution.valueOf(value.get("resolution").getAsString().toUpperCase(Locale.ROOT)),
                cells.get(0).getAsInt(), cells.get(1).getAsInt(), cells.get(2).getAsInt(), value.get("cell_size").getAsFloat(),
                value.get("max_axis").getAsInt(), bounds.get(0).getAsDouble(), bounds.get(1).getAsDouble(), bounds.get(2).getAsDouble(),
                value.has("unnamed_attributes") ? value.get("unnamed_attributes").getAsInt() : 0,
                value.has("buffer_format") ? GridBufferFormat.valueOf(value.get("buffer_format").getAsString().toUpperCase(Locale.ROOT))
                        : GridBufferFormat.HALFFLOAT, !value.has("clear_output") || value.get("clear_output").getAsBoolean(),
                value.has("output_binding") ? value.get("output_binding").getAsString() : "",
                value.has("reader") && value.get("reader").getAsBoolean(),
                value.has("reader_emitter") ? value.get("reader_emitter").getAsString() : "",
                value.has("reader_interface") ? value.get("reader_interface").getAsString() : "");
    }
}
