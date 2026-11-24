package com.kltyton.lumenfx.effect.niagara.grid;

import com.google.gson.JsonObject;
import java.util.Locale;

public record Grid2DCollectionDefinition(int x, int y, int maxAxis, boolean sizeFromMaxAxis, double worldX, double worldY,
                                         int unnamedAttributes, GridBufferFormat format, boolean clearOutput, String outputBinding,
                                         boolean reader, String readerEmitter, String readerInterface) {
    public static Grid2DCollectionDefinition read(JsonObject value) {
        var cells = value.getAsJsonArray("cells");
        var bounds = value.getAsJsonArray("world_bounds");
        return new Grid2DCollectionDefinition(cells.get(0).getAsInt(), cells.get(1).getAsInt(), value.get("max_axis").getAsInt(),
                value.get("size_from_max_axis").getAsBoolean(), bounds.get(0).getAsDouble(), bounds.get(1).getAsDouble(),
                value.get("unnamed_attributes").getAsInt(), GridBufferFormat.valueOf(value.get("buffer_format").getAsString().toUpperCase(Locale.ROOT)),
                value.get("clear_output").getAsBoolean(), value.get("output_binding").getAsString(),
                value.get("reader").getAsBoolean(), value.get("reader_emitter").getAsString(), value.get("reader_interface").getAsString());
    }
}
