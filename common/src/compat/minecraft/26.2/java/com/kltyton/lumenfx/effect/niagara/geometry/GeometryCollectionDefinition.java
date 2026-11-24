package com.kltyton.lumenfx.effect.niagara.geometry;

import com.google.gson.JsonObject;

public record GeometryCollectionDefinition(Mode mode, String sourceComponent, String sourceActor, String parameter,
                                           String defaultCollection, boolean includeIntermediate) {
    public static final String TYPE = "NiagaraDataInterfaceGeometryCollection";

    public enum Mode { Default, Source, AttachParent, DefaultCollectionOnly, ParameterBinding }

    public static GeometryCollectionDefinition read(JsonObject data) {
        return new GeometryCollectionDefinition(Mode.valueOf(data.get("mode").getAsString()),
                data.get("source_component").getAsString(), data.get("source_actor").getAsString(),
                data.get("parameter").getAsString(), data.get("default_collection").getAsString(),
                data.get("include_intermediate").getAsBoolean());
    }
}
