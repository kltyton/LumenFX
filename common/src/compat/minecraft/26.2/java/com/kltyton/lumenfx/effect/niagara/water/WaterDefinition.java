package com.kltyton.lumenfx.effect.niagara.water;

import com.google.gson.JsonObject;

public record WaterDefinition(String source, boolean findClosest, boolean evaluateDepth, boolean depthPerFrame) {
    public static final String TYPE = "NiagaraDataInterfaceWater";
    public static final String DEPTH_QUERY_SUPPORTED = "fx.Niagara.Water.DepthQuerySupported";
    public static final String OCEAN_FALLBACK_DEPTH = "r.Water.OceanFallbackDepth";

    public static WaterDefinition read(JsonObject data) {
        return new WaterDefinition(data.get("source").getAsString(), data.get("find_closest").getAsBoolean(),
                data.get("evaluate_depth").getAsBoolean(), data.get("evaluate_depth_per_frame").getAsBoolean());
    }
}
