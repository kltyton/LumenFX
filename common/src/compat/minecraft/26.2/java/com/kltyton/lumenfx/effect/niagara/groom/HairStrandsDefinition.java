package com.kltyton.lumenfx.effect.niagara.groom;

import com.google.gson.JsonObject;

public record HairStrandsDefinition(String sourceActor) {
    public static final String TYPE = "NiagaraDataInterfaceHairStrands";

    public static boolean gpuUsesHairStrands(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces"))
            if (item.getAsJsonObject().has("hair_strands")) return true;
        return false;
    }

    public static HairStrandsDefinition read(JsonObject data) {
        return new HairStrandsDefinition(data.get("source_actor").getAsString());
    }
}
